package studio.otherstuff.ambulando.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.GeolocationPermissions
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.app.web.WebProfiles
import studio.otherstuff.ambulando.app.web.WebSurfaceUnavailable
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
import java.net.URI
import java.util.UUID

/** One browser tab: a live WebView (page, scroll and history stay while other tabs or Ambulando are shown). */
class BrowserTab internal constructor(val webView: WebView) {
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

    val isBlank: Boolean get() = url == null || url?.startsWith("about:") == true
    val displayTitle: String get() = title.ifEmpty { url?.let { runCatching { URI(it).host }.getOrNull() } ?: "New tab" }
}

/** A JavaScript `alert()` or `confirm()` waiting for the user. */
class BrowserDialog(val origin: String, val message: String, val confirm: Boolean, val reply: (Boolean) -> Unit)

/**
 * The in-app browser: full-screen tabs over Ambulando, with `window.nostr` answered natively by [NIP07Broker].
 *
 * Ambulando's own screens stay alive underneath (the browser is a cover, not a navigation change), so returning finds
 * the same section, channel, scroll position and composer draft. Tabs live while the app runs (not restored after the
 * process ends). Website data lives in the browser's own WebView profile, separate from editors and artefacts.
 */
class BrowserModel(private val context: Context) {
    companion object {
        const val BRIDGE = "ambulandoNostr"
        const val MAX_TABS = 12
        // HTTPS top-frame only. Page-created bytes never receive native credentials or local filesystem paths.
        val FILE_SCRIPT = """
            (() => {
              if (window.top !== window || location.protocol !== 'https:') return;
              const send = value => window.ambulandoFiles.postMessage(JSON.stringify(value));
              document.addEventListener('click', async event => {
                const a = event.target.closest && event.target.closest('a[href]');
                if (!a || !event.isTrusted) return;
                const url = new URL(a.href, location.href);
                if (!a.hasAttribute('download') && !['blob:', 'data:'].includes(url.protocol)) return;
                if (!['https:', 'blob:', 'data:'].includes(url.protocol)) return;
                event.preventDefault();
                try {
                  if (url.protocol === 'https:') { send({url: url.href, filename: a.download || url.pathname.split('/').pop()}); return; }
                  const response = await fetch(url.href); const blob = await response.blob();
                  if (blob.size > 16 * 1024 * 1024) throw new Error('Download exceeds the 16 MB limit.');
                  const reader = new FileReader();
                  reader.onload = () => send({filename: a.download || 'download', base64: reader.result.split(',')[1]});
                  reader.onerror = () => send({error: 'Cannot read page download.'});
                  reader.readAsDataURL(blob);
                } catch (e) { send({error: String(e.message || e)}); }
              }, true);
            })();
        """.trimIndent()

        /**
         * NIP-07 for the top frame of HTTPS pages. Every call goes to the native listener, which checks the calling frame
         * and origin itself; the page only ever receives the public key and events the user approved.
         */
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
              const nostr = {
                getPublicKey: () => call('getPublicKey'),
                signEvent: (event) => { try { return call('signEvent', JSON.parse(JSON.stringify(event))); } catch (e) { return Promise.reject(e); } },
                getRelays: () => call('getRelays'),
                nip04: { encrypt: unsupported('nip04.encrypt'), decrypt: unsupported('nip04.decrypt') },
                nip44: { encrypt: unsupported('nip44.encrypt'), decrypt: unsupported('nip44.decrypt') },
              };
              Object.defineProperty(nostr, '__ambulando', { value: true });
              Object.defineProperty(window, 'nostr', { value: Object.freeze(nostr), configurable: true, writable: false });
            })();
        """.trimIndent()
    }

    val tabs = mutableStateListOf<BrowserTab>()
    var fileSelection by mutableStateOf<BrowserFileSelection?>(null)
    var download by mutableStateOf<BrowserDownload?>(null)
    var fileTransfer by mutableStateOf<BrowserFileTransfer?>(null)
    fun cancelFileRequests(tab: BrowserTab? = null) {
        if (tab == null || fileSelection?.tab === tab) { fileSelection?.finish(null); fileSelection = null }
        if (tab == null || download?.tab === tab) download = null
        if (tab == null || fileTransfer?.request?.tab === tab) fileTransfer?.cancel()
    }
    var selectedId by mutableStateOf<UUID?>(null)
    var presented by mutableStateOf(false)
        private set
    var showingTabs by mutableStateOf(false)
    /** An address for another app (mail, phone, wallet...) waiting for the user to confirm the hand-off. */
    var pendingExternal by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<BrowserDialog?>(null)
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

    /** Sample-data mode and tests only: this HTML answers for the URL's host instead of the network. */
    var samplePage: Pair<String, String>? = null

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
    ) {
        broker?.setLocked(true)
        val made = NIP07Broker(locked, savedSites, publicKey, sign, persist)
        made.onChange = { scope.launch { prompt = made.current; this@BrowserModel.locked = made.locked; sites = made.sites } }
        broker = made
        this.locked = locked
        sites = savedSites
    }

    // Tabs

    /** Opens [url] (or an empty tab for the address field) as a new tab and shows the browser. */
    fun open(url: String?) {
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
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
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
    fun show() { if (tabs.isEmpty()) open(null) else presented = true }

    /** Back to Ambulando, exactly where the user was. Waiting `window.nostr` requests are cancelled. */
    fun hide() {
        if (!presented) return
        presented = false
        cancelFileRequests()
        tabs.forEach { broker?.tabClosed(it.id) }
        dialog?.reply?.invoke(false)
        dialog = null
    }

    fun select(tab: BrowserTab) { if (selectedId != tab.id) cancelFileRequests(); selectedId = tab.id; showingTabs = false }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }.takeIf { it >= 0 } ?: return
        broker?.tabClosed(tab.id)
        cancelFileRequests(tab)
        tab.webView.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(tab.webView, BRIDGE)
        WebViewCompat.removeWebMessageListener(tab.webView, "ambulandoFiles")
        tab.webView.destroy()
        tabs.removeAt(index)
        if (selectedId == tab.id) selectedId = tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
        if (tabs.isEmpty()) { presented = false; showingTabs = false }
    }

    fun closeAll() { tabs.toList().forEach { close(it) } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addTab(): BrowserTab? {
        if (!WebProfiles.supported || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            unavailable = WebProfiles.UNSUPPORTED_MESSAGE
            return null
        }
        if (tabs.size >= MAX_TABS) tabs.firstOrNull { it.id != selectedId }?.let { close(it) }
        val webView = WebView(context)
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
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        val tab = BrowserTab(webView)
        webView.webViewClient = TabClient(tab)
        webView.webChromeClient = TabChrome(tab)
        webView.setDownloadListener { url, _, disposition, mime, _ ->
            requestDownload(tab, url, android.webkit.URLUtil.guessFileName(url, disposition, mime), mime)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, FILE_SCRIPT, setOf("*"))
        WebViewCompat.addWebMessageListener(webView, "ambulandoFiles", setOf("*")) { _, message, origin, main, _ ->
            if (main && tab.origin != null && tab.origin == BrowserURLPolicy.origin(runCatching { URI(origin.toString()) }.getOrNull()) && message.data.orEmpty().length <= BrowserFilePolicy.MAX_BYTES * 4 / 3 + 4096) {
                runCatching {
                    val request = WireJson.parseToJsonElement(message.data!!).jsonObject
                    val error = request["error"]?.jsonPrimitive?.content
                    if (error != null) tab.refusal = error.take(200) else {
                        val url = request["url"]?.jsonPrimitive?.content
                        val name = request["filename"]?.jsonPrimitive?.content
                        val encoded = request["base64"]?.jsonPrimitive?.content
                        if (encoded != null) {
                            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                            require(bytes.size <= BrowserFilePolicy.MAX_BYTES)
                            if (download == null && fileTransfer == null) download = BrowserDownload(tab, tab.documentId, null, bytes, BrowserFilePolicy.filename(name), "application/octet-stream")
                        } else if (url != null) requestDownload(tab, url, name, "application/octet-stream")
                    }
                }.onFailure { tab.refusal = "This download could not be read (maximum 16 MB)." }
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
        val uri = runCatching { URI(url) }.getOrNull() ?: run { tab.refusal = "This address could not be read."; return }
        when (val decision = BrowserURLPolicy.decide(uri, mainFrame = true)) {
            BrowserURLPolicy.Decision.Load -> {
                tab.refusal = null
                val sample = samplePage
                if (sample != null && runCatching { URI(sample.first).host }.getOrNull() == uri.host) {
                    tab.webView.loadDataWithBaseURL(sample.first, sample.second, "text/html", "utf-8", null)
                } else {
                    tab.webView.loadUrl(uri.toString())
                }
            }
            is BrowserURLPolicy.Decision.External -> pendingExternal = decision.url.toString()
            is BrowserURLPolicy.Decision.Refuse -> tab.refusal = decision.reason
        }
    }

    /**
     * Links tapped in Ambulando (messages, documents, the Toolbox): HTTPS opens a tab; other apps' schemes return
     * false so the caller hands them to Android as before.
     */
    fun handleLink(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return when (val decision = BrowserURLPolicy.decide(uri, mainFrame = true)) {
            BrowserURLPolicy.Decision.Load -> { open(url); true }
            is BrowserURLPolicy.Decision.External -> false
            is BrowserURLPolicy.Decision.Refuse -> { show(); selected?.refusal = decision.reason; true }
        }
    }


    private fun requestDownload(tab: BrowserTab, url: String, name: String?, mime: String?) {
        if (tab.origin == null || !BrowserFilePolicy.https(url)) {
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
        if (value) { cancelFileRequests(); dialog?.reply?.invoke(false); dialog = null }
    }

    fun forget(origin: String) { broker?.forget(origin) }

    /** The identity was removed or the Tower disconnected: tabs close and no site keeps a permission. */
    fun reset() {
        closeAll()
        broker?.reset()
    }

    /** Removes the browser profile's cookies, storage and cache (the browser's data only). */
    fun clearBrowsingData() {
        if (!WebProfiles.supported) return
        val profile = ProfileStore.getInstance().getProfile(WebProfiles.BROWSER) ?: return
        profile.cookieManager.removeAllCookies(null)
        profile.webStorage.deleteAllData()
        tabs.forEach { it.webView.clearCache(true) }
    }

    // WebView plumbing

    private fun committed(tab: BrowserTab, url: String?) {
        cancelFileRequests(tab)
        tab.documentId += 1
        tab.origin = BrowserURLPolicy.origin(url?.let { runCatching { URI(it) }.getOrNull() })
        tab.refusal = null
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
        if (!isMainFrame || origin == null || origin != tab.origin) {
            reply.postMessage(answer(id, false, "This frame cannot use Nostr in Ambulando."))
            return
        }
        val method = (request["method"] as? JsonPrimitive)?.content ?: ""
        val documentId = tab.documentId
        val requestContext = NIP07Context(tab.id, documentId, origin)
        scope.launch {
            val result = broker.handle(method, request["params"], requestContext) {
                tab.documentId == documentId && tab.origin == origin && tabs.any { it.id == tab.id }
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
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            close(tab)
            unavailable = "The browser tab stopped. Open the address again to retry."
            return true
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return true
            return when (val decision = BrowserURLPolicy.decide(uri, request.isForMainFrame)) {
                BrowserURLPolicy.Decision.Load -> false
                is BrowserURLPolicy.Decision.External -> {
                    // Other apps open only from a navigation the user started, and only after a confirmation.
                    if (request.isForMainFrame && request.hasGesture()) pendingExternal = decision.url.toString()
                    true
                }
                is BrowserURLPolicy.Decision.Refuse -> { if (request.isForMainFrame) tab.refusal = decision.reason; true }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            committed(tab, url)
            tab.url = url
            tab.loading = true
            update(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.loading = false
            tab.url = url
            update(view)
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
            val child = addTab() ?: return false
            transport.webView = child.webView
            resultMsg.sendToTarget()
            presented = true
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (tab.origin == null || fileSelection != null) { callback.onReceiveValue(null); return true }
            fileSelection = BrowserFileSelection(tab, params, callback)
            return true
        }

        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.webView === window }?.let { close(it) } }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            dialog = BrowserDialog(hostOf(url), message.orEmpty(), confirm = false) { result.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            dialog = BrowserDialog(hostOf(url), message.orEmpty(), confirm = true) { if (it) result.confirm() else result.cancel() }
            return true
        }

        // Camera, microphone and location are never granted to pages.
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
    }

    private fun hostOf(url: String?): String = url?.let { runCatching { URI(it).host }.getOrNull() } ?: "This page"
}
