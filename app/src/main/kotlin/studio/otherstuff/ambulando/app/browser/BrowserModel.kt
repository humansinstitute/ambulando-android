package studio.otherstuff.ambulando.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.app.web.WebProfiles
import studio.otherstuff.ambulando.app.web.WebSurfaceUnavailable
import studio.otherstuff.ambulando.core.browser.BrowserFavourite
import studio.otherstuff.ambulando.core.browser.BrowserFavourites
import studio.otherstuff.ambulando.core.browser.BrowserURLPolicy
import studio.otherstuff.ambulando.core.browser.NIP07Broker
import studio.otherstuff.ambulando.core.browser.NIP07Context
import studio.otherstuff.ambulando.core.browser.NIP07Decision
import studio.otherstuff.ambulando.core.browser.NIP07Prompt
import studio.otherstuff.ambulando.core.browser.NIP07Result
import studio.otherstuff.ambulando.core.browser.NIP07SitePermission
import studio.otherstuff.ambulando.core.browser.NIP07SitePersistence
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import java.net.URI
import java.util.UUID

/** One browser tab: a live WebView (page, scroll and history stay while other tabs or Ambulando are shown). */
class BrowserTab internal constructor(val webView: WebView, val openerId: UUID? = null) {
    val id: UUID = UUID.randomUUID()
    var title by mutableStateOf("")
    var url by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    /** The last navigation the browser refused, shown above the page until the next page loads. */
    var refusal by mutableStateOf<String?>(null)
    /** Bumped on every main-frame navigation; `window.nostr` requests are bound to the document that made them. */
    @Volatile var documentId: Long = 0
    /** The committed page's HTTPS origin (null for `about:blank` and anything not HTTPS). */
    @Volatile var origin: String? = null
    /** The WApp or artifact address this tab was opened for from an Ambulando launcher. */
    var launchURL: String? = null
    /** Provisional main-frame URL, including TLS failures that never reach onPageStarted. */
    internal var pendingNavigationURL: String? = null
    @Volatile internal var restorable = false
    internal var directoryRequested = false

    val isBlank: Boolean get() = openerId == null && (url == null || url?.startsWith("about:") == true)
    val displayTitle: String get() = title.ifEmpty { url?.let { BrowserURLPolicy.navigationAddress(it)?.host } ?: if (openerId != null) "New window" else "New tab" }
}

/** Where the favourites list is kept: device app state (`BrowserFavourites.STATE_KEY`). Null clears it. */
interface BrowserFavouritesPersistence {
    suspend fun load(): String?
    suspend fun save(text: String?)
}

/** Favourites in this device's local store, apart from website data, Nostr permissions and any connection. */
class StoreFavouritesPersistence(private val store: LocalStore) : BrowserFavouritesPersistence {
    override suspend fun load(): String? = store.reader.state(BrowserFavourites.STATE_KEY)
    override suspend fun save(text: String?) { store.writer.setState(BrowserFavourites.STATE_KEY, text) }
}

/** A JavaScript `alert()` or `confirm()` waiting for the user. */
class BrowserDialog(val tab: BrowserTab, val origin: String, val message: String, val confirm: Boolean,
    val defaultValue: String? = null, private val complete: (Boolean, String) -> Unit) {
    private val document = tab.documentId
    private var completed = false
    fun reply(allow: Boolean, value: String = "") {
        if (completed) return
        completed = true
        complete(allow && tab.documentId == document, value)
    }
}

/**
 * The in-app browser: full-screen tabs over Ambulando, with `window.nostr` answered natively by [NIP07Broker].
 *
 * Ambulando's own screens stay alive underneath (the browser is a cover, not a navigation change), so returning finds
 * the same section, channel, scroll position and composer draft. Live history stays in process; cold restoration saves only allowed GET URLs and selection.
 * Website data lives in the browser's own WebView profile, separate from editors and artefacts.
 */
class BrowserModel(private val context: Context) {
    companion object {
        const val BRIDGE = "ambulandoNostr"
        const val MAX_TABS = 12
        private val cleanedStaleFiles = java.util.concurrent.atomic.AtomicBoolean()
        // HTTPS top-frame only. Page-created bytes never receive native credentials or local filesystem paths.
        val FILE_SCRIPT = """
            (() => {
              if (window.top !== window || location.protocol !== 'https:') return;
              let seq = 0; const pending = new Map();
              window.ambulandoFiles.onmessage = e => {
                const r=JSON.parse(e.data), p=pending.get(r.id); if (!p) return;
                pending.delete(r.id); r.error ? p.reject(new Error(r.error)) : p.resolve();
              };
              const send = value => new Promise((resolve,reject) => {
                const id=++seq; pending.set(id,{resolve,reject});
                window.ambulandoFiles.postMessage(JSON.stringify({...value,id}));
                setTimeout(()=>{if(pending.delete(id))reject(new Error('Download timed out.'));},30000);
              });
              const exportResponse = async (response, filename='download') => {
                if (!response.ok) throw new Error('Download failed (HTTP '+response.status+').');
                if(Number(response.headers.get('Content-Length'))>256*1024*1024)throw new Error('Page export exceeds 256 MB.');
                await send({start:true, filename, mime:response.headers.get('Content-Type') || 'application/octet-stream'});
                try {
                  const reader=response.body.getReader(); let size=0;
                  for (;;) {
                    const {done,value}=await reader.read(); if(done)break;
                    size+=value.length; if(size>256*1024*1024) {await reader.cancel();throw new Error('Page export exceeds 256 MB.');}
                    for(let i=0;i<value.length;i+=32768) {
                      const chunk=value.subarray(i,i+32768); let binary='';
                      for(let j=0;j<chunk.length;j++)binary+=String.fromCharCode(chunk[j]);
                      await send({chunk:btoa(binary)});
                    }
                  }
                  await send({end:true});
                } catch(e) { await send({abort:true}).catch(()=>{}); throw e; }
              };
              // The response keeps the page's POST/bearer context. No native identity or request replay.
              window.ambulandoDownload = exportResponse;
              const handle = async a => {
                const url = new URL(a.href, location.href);
                try {
                  if(url.protocol==='https:') {await send({url:url.href,filename:a.download||url.pathname.split('/').pop()});return;}
                  await exportResponse(await fetch(url.href),a.download||'download');
                } catch(e) {send({error:String(e.message||e)}).catch(()=>{});}
              };
              const eligible = a => a && (a.hasAttribute('download') || /^(blob:|data:)/.test(a.href)) && /^(https:|blob:|data:)/.test(a.href);
              let directoryInput=null, pickingDirectory=false;
              document.addEventListener('change',event=>{
                if(event.target!==directoryInput)return;
                const paths=window.__ambulandoDirectoryPaths||[];
                Array.from(directoryInput.files||[]).forEach((file,index)=>{
                  const path=paths[index];if(path && path.split('/').pop()===file.name)Object.defineProperty(file,'webkitRelativePath',{value:path});
                });
                window.__ambulandoDirectoryPaths=[];
              },true);
              document.addEventListener('click', event => {
                const input=event.target;
                if(input?.tagName==='INPUT' && input.type==='file' && input.webkitdirectory){
                  if(pickingDirectory){pickingDirectory=false;return;}
                  event.preventDefault();directoryInput=input;
                  send({directory:true}).then(()=>{pickingDirectory=true;input.click();}).catch(()=>{});return;
                }
                const a=event.target.closest && event.target.closest('a[href]');
                if(!eligible(a))return; event.preventDefault(); handle(a);
              },true);
              const click=HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click=function(){if(eligible(this))handle(this);else click.call(this);};
            })();
        """.trimIndent()

        /**
         * NIP-07 for the top frame of HTTPS pages. Every call goes to the native listener, which checks the calling frame
         * and origin itself; the page only ever receives the public key and events the user approved.
         */
        /**
         * The icon the page names for home screens (`apple-touch-icon`), else its largest `icon`, else `/favicon.ico`.
         * Only an HTTPS answer is kept, and it is only ever shown as a picture.
         */
        val SITE_ICON_SCRIPT = """
            (() => {
              const links = [...document.querySelectorAll('link[rel][href]')].map(l => ({ rel: l.rel.toLowerCase(), href: l.href, size: parseInt((l.sizes && l.sizes.value || '0').split('x')[0]) || 0 }));
              const touch = links.filter(l => l.rel.includes('apple-touch-icon')).sort((a, b) => b.size - a.size)[0];
              const icon = links.filter(l => l.rel.split(/\s+/).includes('icon')).sort((a, b) => b.size - a.size)[0];
              return (touch || icon || {}).href || (location.origin + '/favicon.ico');
            })()
        """.trimIndent()

        val NOSTR_SCRIPT = """
            (() => {
              if (window.top !== window || location.protocol !== 'https:') return;
              if (window.nostr && window.nostr.__ambulando) return;
              const bridge = window.$BRIDGE;
              if (!bridge) return;
              let next = 0;
              const pending = new Map();
              bridge.onmessage = (event) => {
                let reply; try { reply = JSON.parse(event.data); } catch (_) { return; }
                const waiter = pending.get(reply.id); if (!waiter) return;
                pending.delete(reply.id);
                if (reply.ok) waiter.resolve(reply.value); else waiter.reject(new Error(reply.error || 'Request failed'));
              };
              const call = (method, params) => new Promise((resolve, reject) => {
                const id = ++next; pending.set(id, { resolve, reject });
                bridge.postMessage(JSON.stringify({ id, method, params: params === undefined ? null : params }));
              });
              const unsupported = (name) => () => Promise.reject(new Error(name + ' is not supported by Ambulando.'));
              const cipher = (method, pubkey, text, options) => {
                if (options !== undefined && (!options || options.version !== 2)) return Promise.reject(new Error('Only NIP-44 v2 is supported.'));
                return call(method, {pubkey, text});
              };
              const nostr = {
                getPublicKey: () => call('getPublicKey'),
                signEvent: (event) => { try { return call('signEvent', JSON.parse(JSON.stringify(event))); } catch (e) { return Promise.reject(e); } },
                getRelays: () => call('getRelays'),
                nip04: { encrypt: unsupported('nip04.encrypt'), decrypt: unsupported('nip04.decrypt') },
                nip44: { encrypt: (pubkey, text, options) => cipher('nip44.encrypt', pubkey, text, options), decrypt: (pubkey, text, options) => cipher('nip44.decrypt', pubkey, text, options) },
              };
              Object.defineProperty(nostr, '__ambulando', { value: true });
              Object.defineProperty(window, 'nostr', { value: Object.freeze(nostr), configurable: true, writable: false });
            })();
        """.trimIndent()
    }

    init {
        if (cleanedStaleFiles.compareAndSet(false, true)) {
            context.cacheDir.listFiles()?.filter { it.name.startsWith("browser-export-") }?.forEach { it.delete() }
            java.io.File(context.cacheDir, "AmbulandoFiles").listFiles()?.filter { it.name.startsWith("browser-capture-") }?.forEach { it.delete() }
        }
    }
    val tabs = mutableStateListOf<BrowserTab>()
    var fileSelection by mutableStateOf<BrowserFileSelection?>(null)
    var download by mutableStateOf<BrowserDownload?>(null)
    var fileTransfer by mutableStateOf<BrowserFileTransfer?>(null)
    private var fileEpoch = 0L
    private val fileMutex = kotlinx.coroutines.sync.Mutex()
    private var generated: BrowserGenerated? = null
    private val uploads = mutableListOf<BrowserFileSelection>()
    fun cancelFileRequests(tab: BrowserTab? = null) {
        fileEpoch += 1
        uploads.filter { tab == null || it.tab === tab }.toList().forEach { it.finish(null); it.cleanup(); uploads.remove(it) }
        if (tab == null || generated?.tab === tab) { generated?.cancel(); generated = null }
        if (tab == null || fileSelection?.tab === tab) { fileSelection?.finish(null); fileSelection = null }
        if (tab == null || download?.tab === tab) { download?.invalidate(); download?.file?.delete(); download = null }
        if (tab == null || fileTransfer?.request?.tab === tab) fileTransfer?.cancel()
    }
    var selectedId by mutableStateOf<UUID?>(null)
    var presented by mutableStateOf(false)
        private set
    var showingTabs by mutableStateOf(false)
    /** An address for another app (mail, phone, wallet...) waiting for the user to confirm the hand-off. */
    var pendingExternal by mutableStateOf<BrowserExternalRequest?>(null)
    var dialog by mutableStateOf<BrowserDialog?>(null)
    var fullscreen by mutableStateOf<BrowserFullscreen?>(null)
        private set
    var permission by mutableStateOf<BrowserPermission?>(null)
    fun exitFullscreen() { val old = fullscreen; fullscreen = null; old?.finish() }
    private val grantedPageTabs = mutableSetOf<BrowserTab>()
    fun revokePagePermissions(tab: BrowserTab) {
        if (grantedPageTabs.remove(tab)) {
            tab.webView.evaluateJavascript("window.__ambulandoStopMedia?.()", null)
            tab.url?.takeIf(BrowserFilePolicy::https)?.let { tab.webView.loadUrl(it) }
        }
    }
    private fun cancelPageUI(tab: BrowserTab? = null, navigating: Boolean = false) {
        if (tab == null || dialog?.tab === tab) { dialog?.reply(false); dialog = null }
        if (tab == null || permission?.tab === tab) { permission?.finish(false); permission = null }
        if (tab == null || fullscreen?.tab === tab) exitFullscreen()
        tabs.filter { tab == null || it === tab }.forEach {
            it.webView.evaluateJavascript("window.__ambulandoStopMedia?.()", null)
        }
        grantedPageTabs.filter { tab == null || it === tab }.toList().forEach {
            if (navigating) grantedPageTabs.remove(it) else revokePagePermissions(it)
        }
        pendingExternal = null
    }
    private fun showDialog(tab: BrowserTab, url: String?, message: String?, result: JsResult, confirm: Boolean, value: String? = null): Boolean {
        val origin = BrowserURLPolicy.origin(url?.let { BrowserURLPolicy.navigationAddress(it) })
        if (tab !== selected || !presented || origin == null || origin != tab.origin || dialog != null) {
            result.cancel(); return true
        }
        dialog = BrowserDialog(tab, origin, message.orEmpty(), confirm, value) { accepted, text ->
            if (!accepted) result.cancel() else if (result is JsPromptResult) result.confirm(text) else result.confirm()
        }
        return true
    }
    /** The broker's current question, mirrored for Compose. */
    var prompt by mutableStateOf<NIP07Prompt?>(null)
        private set
    var locked by mutableStateOf(true)
        private set
    var sites by mutableStateOf<Map<String, NIP07SitePermission>>(emptyMap())
        private set
    /** Why the browser cannot open on this device (WebView without isolation support), shown instead of tabs. */
    var unavailable by mutableStateOf<String?>(null)
        private set

    /** Sites saved to the new-tab page, kept on this device apart from website data and Nostr permissions. */
    var favourites by mutableStateOf<List<BrowserFavourite>>(emptyList())
        private set
    private val favouriteList = BrowserFavourites()
    private var favouritesPersistence: BrowserFavouritesPersistence? = null
    /** Favourites writes run one after another, so the stored list is always the latest. */
    private var favouritesWrite: Job? = null

    /** Sample-data mode and tests only: this HTML answers for the URL's host instead of the network. */
    var samplePage: Pair<String, String>? = null

    private val sessionPrefs = context.getSharedPreferences("browser-tabs-v1", Context.MODE_PRIVATE)
    private var restoredSession = false
    private fun persistTabs() {
        if (!restoredSession) return
        val safe = tabs.filter { it.restorable && it.url?.let(BrowserFilePolicy::https) == true }
        val urls = org.json.JSONArray(safe.map { it.url })
        sessionPrefs.edit().putString("urls", urls.toString()).putInt("selected", safe.indexOf(selected).coerceAtLeast(0)).apply()
    }
    private fun restoreTabs() {
        if (restoredSession) return
        restoredSession = true
        if (tabs.isNotEmpty()) return
        val urls = runCatching { org.json.JSONArray(sessionPrefs.getString("urls", "[]")) }.getOrNull() ?: return
        val selection = sessionPrefs.getInt("selected", 0)
        repeat(minOf(urls.length(), MAX_TABS)) { i ->
            val url = urls.optString(i)
            val uri = BrowserURLPolicy.navigationAddress(url)
            if (uri != null && uri.scheme == "https" && BrowserURLPolicy.decide(uri, true) == BrowserURLPolicy.Decision.Load) {
                addTab()?.let { load(url, it) }
            }
        }
        tabs.getOrNull(selection)?.let { selectedId = it.id }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var broker: NIP07Broker? = null

    val selected: BrowserTab? get() = tabs.firstOrNull { it.id == selectedId } ?: tabs.lastOrNull()

    /** Connects the broker to the identity. Called once the signer and saved site permissions are available. */
    fun configure(
        locked: Boolean,
        savedSites: Map<String, NIP07SitePermission>,
        publicKey: suspend () -> String,
        sign: suspend (NostrEventTemplate) -> NostrEvent,
        persist: NIP07SitePersistence,
        cipher: (suspend (String, String, String, Boolean) -> String)? = null,
    ) {
        broker?.setLocked(true)
        val made = NIP07Broker(locked, savedSites, publicKey, sign, persist, cipher)
        made.onChange = { scope.launch { prompt = made.current; this@BrowserModel.locked = made.locked; sites = made.sites } }
        broker = made
        this.locked = locked
        sites = savedSites
    }

    // Favourites

    /** Loads the saved favourites and keeps later changes in [persistence]. */
    suspend fun configureFavourites(persistence: BrowserFavouritesPersistence) {
        val loaded = BrowserFavourites.decode(persistence.load())
        favouritesPersistence = persistence
        favouriteList.replaceWith(loaded)
        favourites = favouriteList.items
    }

    fun favourite(tab: BrowserTab?): BrowserFavourite? = tab?.takeUnless { it.isBlank }?.url?.let { favouriteList.favourite(it) }

    /** Whether the menu can offer to save this tab's page (an HTTPS page the browser opens). */
    fun canSaveFavourite(tab: BrowserTab?): Boolean = tab?.url?.takeUnless { tab.isBlank }?.let { BrowserFavourites.opens(it) } == true

    /** Adds the tab's page to the favourites (or removes it if it is one), then looks for the icon the page advertises. */
    fun toggleFavourite(tab: BrowserTab) {
        val url = tab.url?.takeUnless { tab.isBlank } ?: return
        favouriteList.favourite(url)?.let { removeFavourite(it.id); return }
        val saved = runCatching { favouriteList.add(tab.title, url) }.getOrElse { tab.refusal = it.message; return }
        persistFavourites()
        val documentId = tab.documentId
        tab.webView.evaluateJavascript(SITE_ICON_SCRIPT) { found ->
            // Only the page that was saved answers; the reply is a JSON string literal (or null).
            if (tab.documentId != documentId) return@evaluateJavascript
            val icon = runCatching { WireJson.parseToJsonElement(found).jsonPrimitive.content }.getOrNull()?.takeIf { found != "null" }
            favouriteList.setSiteIcon(icon, saved.id)
            persistFavourites()
        }
    }

    /** Saves an edited favourite, or a new one typed on the start page. Throws the reason it was refused. */
    fun saveFavourite(favourite: BrowserFavourite) {
        favouriteList.save(favourite)
        persistFavourites()
    }

    fun removeFavourite(id: UUID) {
        favouriteList.remove(id)
        persistFavourites()
    }

    fun moveFavourite(id: UUID, by: Int) {
        favouriteList.moveBy(id, by)
        persistFavourites()
    }

    /** Opens a favourite in the tab showing the start page (or a new tab if there is none). */
    fun openFavourite(favourite: BrowserFavourite) {
        val tab = selected
        if (tab != null && tab.isBlank) { load(favourite.url, tab); presented = true } else open(favourite.url)
    }

    /** Resolves once every favourites change made so far has been written (tests). */
    suspend fun favouritesSaved() { favouritesWrite?.join() }

    private fun persistFavourites() {
        favourites = favouriteList.items
        val persistence = favouritesPersistence ?: return
        val text = favouriteList.encoded()
        val previous = favouritesWrite
        favouritesWrite = scope.launch {
            previous?.join()
            runCatching { persistence.save(text) }
        }
    }

    // Tabs

    /** Opens [url] (or an empty tab for the address field) as a new tab and shows the browser. */
    fun open(url: String?) {
        restoreTabs()
        cancelPageUI()
        cancelFileRequests()
        val tab = addTab() ?: run { presented = true; return }
        url?.let { load(it, tab) }
        presented = true
    }

    /**
     * A launcher in Ambulando (Deck's Apps, the Toolbox, Context Tree artifacts) opening an HTTPS web app as a tab, where
     * it may ask to use `window.nostr`. The tab it opened before is shown again rather than a new one. Returns false if
     * the address is not one the browser opens.
     */
    fun launch(url: String): Boolean {
        val uri = BrowserURLPolicy.navigationAddress(url) ?: return false
        if (BrowserURLPolicy.decide(uri, mainFrame = true) != BrowserURLPolicy.Decision.Load) return false
        val existing = tabs.firstOrNull { it.launchURL == url }
        if (existing != null) select(existing) else {
            open(url)
            selected?.launchURL = url
        }
        presented = true
        return true
    }

    /** The globe button: back to the open tabs, or a new tab when there are none. */
    fun show() { restoreTabs(); if (tabs.isEmpty()) open(null) else presented = true }

    /** Back to Ambulando, exactly where the user was. Waiting `window.nostr` requests are cancelled. */
    fun hide() {
        if (!presented) return
        presented = false
        cancelFileRequests()
        tabs.forEach { broker?.tabClosed(it.id) }
        cancelPageUI()
    }

    fun select(tab: BrowserTab) { if (selectedId != tab.id) { cancelFileRequests(); cancelPageUI() }; selectedId = tab.id; showingTabs = false; persistTabs() }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }.takeIf { it >= 0 } ?: return
        broker?.tabClosed(tab.id)
        cancelPageUI(tab, navigating = true)
        cancelFileRequests(tab)
        tab.documentId += 1
        tab.origin = null
        tab.webView.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(tab.webView, BRIDGE)
        WebViewCompat.removeWebMessageListener(tab.webView, "ambulandoFiles")
        tab.webView.destroy()
        tabs.removeAt(index)
        if (selectedId == tab.id) selectedId = tabs.firstOrNull { it.id == tab.openerId }?.id ?: tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
        if (tabs.isEmpty()) { presented = false; showingTabs = false }
        persistTabs()
    }

    fun closeAll() { tabs.toList().forEach { close(it) } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addTab(opener: BrowserTab? = null): BrowserTab? {
        if (!WebProfiles.supported || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            unavailable = WebProfiles.UNSUPPORTED_MESSAGE
            return null
        }
        // A popup must never evict its opener (including a background opener) or another active session.
        if (tabs.size >= MAX_TABS && opener != null) {
            opener.refusal = "Close a browser tab before opening another popup."
            return null
        }
        if (tabs.size >= MAX_TABS) tabs.firstOrNull { it.id != selectedId }?.let { close(it) }
        val webView = WebView(context).apply {
            // WebView treats a wrap-content height as content-sized even when Compose measures it
            // to fill the screen. Give CSS percentages and viewport units a definite viewport.
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        try {
            WebProfiles.assign(webView, WebProfiles.BROWSER)
        } catch (e: WebSurfaceUnavailable) {
            webView.destroy()
            unavailable = e.message
            return null
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        val tab = BrowserTab(webView, opener?.id)
        webView.webViewClient = TabClient(tab)
        webView.webChromeClient = TabChrome(tab)
        webView.setDownloadListener { url, _, disposition, mime, _ ->
            requestDownload(tab, url, android.webkit.URLUtil.guessFileName(url, disposition, mime), mime)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, """
            (()=>{
              if(window.top!==window || location.protocol!=='https:')return;
              const streams=new Set(), watches=new Set();
              const devices=navigator.mediaDevices;
              if(devices?.getUserMedia){const original=devices.getUserMedia.bind(devices);devices.getUserMedia=async c=>{const s=await original(c);streams.add(s);return s;};}
              const geo=navigator.geolocation;
              if(geo){const watch=geo.watchPosition.bind(geo);geo.watchPosition=(...args)=>{const id=watch(...args);watches.add(id);return id;};}
              window.__ambulandoStopMedia=()=>{for(const s of streams)s.getTracks().forEach(t=>t.stop());streams.clear();for(const id of watches)geo.clearWatch(id);watches.clear();};
            })();
        """.trimIndent(), setOf("*"))
        WebViewCompat.addDocumentStartJavaScript(webView, FILE_SCRIPT, setOf("*"))
        WebViewCompat.addWebMessageListener(webView, "ambulandoFiles", setOf("*")) { _, message, origin, main, reply ->
            val raw = message.data.orEmpty()
            if (main && presented && tab === selected && tab.origin != null && tab.origin == BrowserURLPolicy.origin(runCatching { URI(origin.toString()) }.getOrNull()) && raw.length <= 64 * 1024) {
                val document = tab.documentId
                val source = tab.origin
                val epoch = fileEpoch
                fun current() = fileEpoch == epoch && tab.documentId == document && tab.origin == source && tab === selected && presented
                scope.launch {
                    fileMutex.withLock {
                        if (!current()) return@withLock
                        var id = 0
                        var exportTouched = false
                        val outcome = runCatching {
                            val request = WireJson.parseToJsonElement(raw).jsonObject
                            id = request["id"]?.jsonPrimitive?.int ?: 0
                            when {
                                request["directory"] != null -> { tab.directoryRequested = true }
                                request["error"] != null -> { tab.refusal = request["error"]!!.jsonPrimitive.content.take(200) }
                                request["start"] != null -> {
                                    check(download == null && fileTransfer == null && generated == null) { "Finish or cancel the current download first." }
                                    val created = kotlinx.coroutines.withContext(Dispatchers.IO) { BrowserGenerated(context, tab, BrowserFilePolicy.filename(request["filename"]?.jsonPrimitive?.content), request["mime"]?.jsonPrimitive?.content ?: "application/octet-stream") }
                                    if (current()) { generated = created; exportTouched = true } else { created.cancel(); error("Page changed during export.") }
                                }
                                request["chunk"] != null -> {
                                    val active = generated ?: error("No page export is active.")
                                    check(active.tab === tab && active.document == tab.documentId)
                                    exportTouched = true
                                    kotlinx.coroutines.withContext(Dispatchers.IO) { active.append(android.util.Base64.decode(request["chunk"]!!.jsonPrimitive.content, android.util.Base64.DEFAULT)) }
                                }
                                request["end"] != null -> {
                                    val active = generated ?: error("No page export is active.")
                                    check(active.tab === tab && active.document == tab.documentId)
                                    generated = null
                                    val ready = kotlinx.coroutines.withContext(Dispatchers.IO) { active.finish() }
                                    if (current()) download = ready else ready.file?.delete()
                                }
                                request["abort"] != null -> { if (generated?.tab === tab) { generated?.cancel(); generated = null } }
                                request["url"] != null -> requestDownload(tab, request["url"]!!.jsonPrimitive.content, request["filename"]?.jsonPrimitive?.content, "application/octet-stream")
                            }
                        }
                        outcome.exceptionOrNull()?.let { if (current()) tab.refusal = it.message?.take(200) ?: "Page export failed."; if (exportTouched && generated?.tab === tab) { generated?.cancel(); generated = null } }
                        if (current()) runCatching { reply.postMessage(buildJsonObject { put("id", id); outcome.exceptionOrNull()?.let { put("error", it.message ?: "Page export failed.") } }.toString()) }
                    }
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, NOSTR_SCRIPT, setOf("*"))
        WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
            onBridgeMessage(tab, message, sourceOrigin, isMainFrame, reply)
        }
        tabs.add(tab)
        selectedId = tab.id
        return tab
    }

    /** Loads an address typed in the address field. Returns false if it is not one the browser opens. */
    fun go(text: String, tab: BrowserTab): Boolean {
        val uri = BrowserURLPolicy.typedAddress(text) ?: run {
            tab.refusal = "Enter an HTTPS address, for example example.com."
            return false
        }
        load(uri.toString(), tab)
        return true
    }

    fun load(url: String, tab: BrowserTab) {
        val uri = BrowserURLPolicy.navigationAddress(url) ?: run { tab.refusal = "This address could not be read."; return }
        when (val decision = BrowserURLPolicy.decide(uri, mainFrame = true)) {
            BrowserURLPolicy.Decision.Load -> {
                tab.refusal = null
                tab.restorable = true
                tab.pendingNavigationURL = uri.toString()
                val sample = samplePage
                if (sample != null && runCatching { URI(sample.first).host }.getOrNull() == uri.host) {
                    tab.webView.loadDataWithBaseURL(sample.first, sample.second, "text/html", "utf-8", null)
                } else {
                    tab.webView.loadUrl(uri.toString())
                }
            }
            is BrowserURLPolicy.Decision.External -> pendingExternal = BrowserExternalRequest(tab, decision.url.toString())
            is BrowserURLPolicy.Decision.Refuse -> tab.refusal = decision.reason
        }
    }

    /**
     * Links tapped in Ambulando (messages, documents, the Toolbox): HTTPS opens a tab; other apps' schemes return
     * false so the caller hands them to Android as before.
     */
    fun handleLink(url: String): Boolean {
        val uri = BrowserURLPolicy.navigationAddress(url) ?: return false
        return when (val decision = BrowserURLPolicy.decide(uri, mainFrame = true)) {
            BrowserURLPolicy.Decision.Load -> { open(url); true }
            is BrowserURLPolicy.Decision.External -> false
            is BrowserURLPolicy.Decision.Refuse -> { show(); selected?.refusal = decision.reason; true }
        }
    }


    private fun requestDownload(tab: BrowserTab, url: String, name: String?, mime: String?) {
        if (!presented || tab !== selected || tab.origin == null || !BrowserFilePolicy.https(url)) {
            tab.refusal = "Only HTTPS files or page-created downloads can be saved."; return
        }
        if (download != null || fileTransfer != null) { tab.refusal = "Finish or cancel the open save picker first."; return }
        download = BrowserDownload(tab, tab.documentId, url, null, BrowserFilePolicy.filename(name), mime ?: "application/octet-stream")
    }

    // Signing state

    fun answer(prompt: NIP07Prompt, decision: NIP07Decision) { broker?.resolve(prompt.id, decision) }

    fun updateLocked(value: Boolean) {
        locked = value
        broker?.setLocked(value)
        if (value) { cancelFileRequests(); cancelPageUI() }
    }

    fun forget(origin: String) { broker?.forget(origin) }

    /** The identity was removed or the Tower disconnected: tabs close and no site keeps a permission. */
    fun reset() {
        restoredSession = true
        closeAll()
        sessionPrefs.edit().clear().apply()
        broker?.reset()
    }

    /** Removes the browser profile's cookies, storage and cache (the browser's data only). */
    fun clearBrowsingData() {
        if (!WebProfiles.supported) return
        val profile = ProfileStore.getInstance().getProfile(WebProfiles.BROWSER) ?: return
        closeAll()
        sessionPrefs.edit().clear().apply()
        profile.cookieManager.removeAllCookies(null)
        profile.webStorage.deleteAllData()
        tabs.forEach { it.webView.clearCache(true) }
    }

    fun reload(tab: BrowserTab) { tab.refusal = null; tab.pendingNavigationURL = tab.webView.url; tab.webView.reload() }
    fun back(tab: BrowserTab) { tab.refusal = null; tab.webView.goBack() }
    fun forward(tab: BrowserTab) { tab.refusal = null; tab.webView.goForward() }

    // WebView plumbing

    private fun committed(tab: BrowserTab, url: String?) {
        tab.directoryRequested = false
        cancelFileRequests(tab)
        cancelPageUI(tab, navigating = true)
        tab.documentId += 1
        tab.origin = BrowserURLPolicy.origin(url?.let { BrowserURLPolicy.navigationAddress(it) })
        // HTTP errors may arrive before onPageStarted; clearing here hides the failure immediately.
        broker?.documentChanged(tab.id, tab.documentId)
    }

    private fun onBridgeMessage(tab: BrowserTab, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val broker = broker ?: return
        val raw = message.data ?: return
        if (raw.length > 512 * 1024) return
        val request = runCatching { WireJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val id = runCatching { request["id"]!!.jsonPrimitive.int }.getOrNull() ?: return
        val origin = BrowserURLPolicy.origin(runCatching { URI(sourceOrigin.toString()) }.getOrNull())
        // Only the top frame of the tab's committed HTTPS origin is answered.
        if (!presented || !isMainFrame || origin == null || origin != tab.origin) {
            reply.postMessage(answer(id, false, "This frame cannot use Nostr in Ambulando."))
            return
        }
        val method = (request["method"] as? JsonPrimitive)?.content ?: ""
        val documentId = tab.documentId
        val requestContext = NIP07Context(tab.id, documentId, origin)
        scope.launch {
            val result = broker.handle(method, request["params"], requestContext) {
                presented && tab.documentId == documentId && tab.origin == origin && tabs.any { it.id == tab.id }
            }
            if (tab.documentId != documentId || tabs.none { it.id == tab.id }) return@launch
            val text = when (result) {
                is NIP07Result.Success -> answer(id, true, null, result.value.javaScriptValue)
                is NIP07Result.Failure -> answer(id, false, result.error.message)
            }
            runCatching { reply.postMessage(text) }
        }
    }

    private fun answer(id: Int, ok: Boolean, error: String?, value: kotlinx.serialization.json.JsonElement? = null): String =
        buildJsonObject {
            put("id", id)
            put("ok", ok)
            if (value != null) put("value", value)
            if (error != null) put("error", error)
        }.toString()

    private inner class TabClient(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
            if (request.isForMainFrame) tab.restorable = request.method.equals("GET", ignoreCase = true)
            return null
        }
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            close(tab)
            unavailable = "The browser tab stopped. Open the address again to retry."
            return true
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            tab.loading = false
            tab.origin = null
            cancelPageUI(tab)
            broker?.tabClosed(tab.id)
            tab.refusal = "Could not load ${hostOf(request.url.toString())}: ${error.description}. Check the address or connection and retry."
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame) {
                tab.loading = false
                tab.refusal = "${hostOf(request.url.toString())} returned HTTP ${response.statusCode}. Retry or check the address."
            }
        }

        // Never replace certificate validation with a retry or proceed() fallback.
        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            if (error.url == tab.pendingNavigationURL || error.url == tab.url) {
                tab.loading = false
                tab.origin = null
                broker?.tabClosed(tab.id)
                tab.refusal = "Could not establish a secure connection to ${hostOf(error.url)}. The website’s certificate could not be verified."
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = BrowserURLPolicy.navigationAddress(request.url.toString()) ?: run {
                if (request.isForMainFrame) tab.refusal = "This address could not be read."
                return true
            }
            return when (val decision = BrowserURLPolicy.decide(uri, request.isForMainFrame)) {
                BrowserURLPolicy.Decision.Load -> {
                    if (request.isForMainFrame) {
                        tab.refusal = null
                        tab.pendingNavigationURL = request.url.toString()
                    }
                    false
                }
                is BrowserURLPolicy.Decision.External -> {
                    // Other apps open only from a navigation the user started, and only after a confirmation.
                    if (request.isForMainFrame && request.hasGesture() && tab === selected && tab.origin != null) pendingExternal = BrowserExternalRequest(tab, decision.url.toString())
                    true
                }
                is BrowserURLPolicy.Decision.Refuse -> { if (request.isForMainFrame) tab.refusal = decision.reason; true }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            committed(tab, url)
            tab.url = url
            tab.pendingNavigationURL = url
            tab.loading = true
            update(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.loading = false
            tab.url = url
            update(view)
            persistTabs()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            tab.url = url
            update(view)
        }

        private fun update(view: WebView) {
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            tab.title = view.title.orEmpty().takeUnless { it == tab.url }.orEmpty()
        }
    }

    private inner class TabChrome(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress / 100f }
        override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty() }

        /** Popups (`target=_blank`, `window.open`) become tabs; their first navigation still passes the URL policy. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val child = addTab(opener = tab) ?: return false
            // Opener-written about:blank documents need not emit navigation/title callbacks.
            child.url = "about:blank"
            transport.webView = child.webView
            resultMsg.sendToTarget()
            presented = true
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (!presented || tab !== selected || tab.origin == null || fileSelection != null) { callback.onReceiveValue(null); return true }
            fileSelection = BrowserFileSelection(tab, params, callback, tab.directoryRequested).also { uploads += it; tab.directoryRequested = false }
            return true
        }

        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.webView === window }?.let { close(it) } }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult) = showDialog(tab, url, message, result, false)
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult) = showDialog(tab, url, message, result, true)
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult) = showDialog(tab, url, message, result, true, defaultValue.orEmpty())
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult) = showDialog(tab, url, "Leave this page? Unsaved changes may be lost.", result, true)
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
            if (tab !== selected || !presented || fullscreen != null) { callback.onCustomViewHidden(); return }
            fullscreen = BrowserFullscreen(tab, view, callback)
        }
        override fun onHideCustomView() { if (fullscreen?.tab === tab) exitFullscreen() }
        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = BrowserURLPolicy.origin(BrowserURLPolicy.navigationAddress(request.origin.toString()))
            val supported = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (tab !== selected || !presented || origin == null || origin != tab.origin || permission != null || supported.isEmpty()) { request.deny(); return }
            permission = BrowserPermission(tab, origin, supported.map { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) android.Manifest.permission.CAMERA else android.Manifest.permission.RECORD_AUDIO }, token = request) { allow ->
                if (allow) { grantedPageTabs += tab; request.grant(supported.toTypedArray()) } else request.deny()
            }
        }
        override fun onPermissionRequestCanceled(request: PermissionRequest) { if (permission?.token === request) { permission?.finish(false); permission = null } }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            val normalized = origin?.let { BrowserURLPolicy.origin(BrowserURLPolicy.navigationAddress(it)) }
            if (tab !== selected || !presented || normalized == null || normalized != tab.origin || permission != null) { callback.invoke(origin, false, false); return }
            permission = BrowserPermission(tab, normalized, listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) { allowed -> if (allowed) grantedPageTabs += tab; callback.invoke(origin, allowed, false) }
        }
        override fun onGeolocationPermissionsHidePrompt() { if (permission?.tab === tab) { permission?.finish(false); permission = null } }

    }

    private fun hostOf(url: String?): String = url?.let { BrowserURLPolicy.navigationAddress(it)?.host } ?: "This page"
}
