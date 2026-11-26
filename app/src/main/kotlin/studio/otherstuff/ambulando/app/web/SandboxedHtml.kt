package studio.otherstuff.ambulando.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.core.surfaces.HtmlCommentAnchor
import java.io.ByteArrayInputStream

data class HtmlAnchorTargetSpec(
    val threadId: String,
    val anchor: HtmlCommentAnchor,
    val resolved: Boolean,
    val replyCount: Int,
)

/**
 * Narrow, data-only review callback bridge for sandboxed HTML artefacts.
 * Never exposes credentials, signing, Tower URLs, network, or storage to the page.
 * All incoming payloads are strictly bounded and fenced to the active version & comment mode.
 */
class HtmlReviewBridge(
    private val isActive: () -> Boolean,
    private val isCommentModeActive: () -> Boolean,
    private val onTargetSelected: (HtmlCommentAnchor) -> Unit,
    private val onAnchorMatches: (Map<String, Boolean>) -> Unit,
    private val onBadgeTapped: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val MAX_ANCHOR_JSON_CHARS = 4096
        const val MAX_MATCHES_JSON_CHARS = 65536
        const val MAX_MATCH_ENTRIES = 500
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

        fun parseBoundedAnchor(anchorJson: String): HtmlCommentAnchor? {
            if (anchorJson.length > MAX_ANCHOR_JSON_CHARS) return null
            val parsed = runCatching { Json.parseToJsonElement(anchorJson).jsonObject }.getOrNull() ?: return null
            return HtmlCommentAnchor.parse(parsed)
        }

        fun parseBoundedMatches(matchesJson: String): Map<String, Boolean>? {
            if (matchesJson.length > MAX_MATCHES_JSON_CHARS) return null
            val parsed = runCatching { Json.parseToJsonElement(matchesJson).jsonObject }.getOrNull() ?: return null
            if (parsed.size > MAX_MATCH_ENTRIES) return null
            return parsed.entries.mapNotNull { (k, v) ->
                if (!UUID_RE.matches(k)) return@mapNotNull null
                val matched = (v as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
                k.lowercase() to matched
            }.toMap()
        }

        fun parseBoundedThreadId(threadId: String): String? {
            val trimmed = threadId.trim()
            return if (UUID_RE.matches(trimmed)) trimmed.lowercase() else null
        }
    }

    @JavascriptInterface
    fun targetSelected(anchorJson: String) {
        if (!isActive() || !isCommentModeActive()) return
        val anchor = parseBoundedAnchor(anchorJson) ?: return
        main.post { if (isActive() && isCommentModeActive()) onTargetSelected(anchor) }
    }

    @JavascriptInterface
    fun anchorMatches(matchesJson: String) {
        if (!isActive()) return
        val map = parseBoundedMatches(matchesJson) ?: return
        main.post { if (isActive()) onAnchorMatches(map) }
    }

    @JavascriptInterface
    fun badgeTapped(threadId: String) {
        if (!isActive()) return
        val id = parseBoundedThreadId(threadId) ?: return
        main.post { if (isActive()) onBadgeTapped(id) }
    }
}

/**
 * The web client's canvas HTML sandbox (`CANVAS_HTML_CSP` and its link/form guard): an artefact can run its own script
 * but cannot fetch, frame, submit or navigate, and gets no signer or `window.nostr`.
 */
object HtmlArtefactDocument {
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
        "connect-src 'none'; media-src data: blob:; frame-src 'none'; child-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"

    private const val GUARD = "<script>(()=>{const stop=e=>{const l=e.target&&e.target.closest&&e.target.closest('a[href],form');if(l){e.preventDefault();" +
        "e.stopImmediatePropagation();const h=e.type==='click'&&l.getAttribute('href');if(h&&h[0]==='#'&&h.length>1){let t=null;try{t=document.getElementById(" +
        "decodeURIComponent(h.slice(1)))}catch{}if(t)t.scrollIntoView({block:'start'})}}};addEventListener('click',stop,true);addEventListener('submit',stop,true);" +
        "window.open=()=>null;})();</script>"

    internal const val REVIEW_STYLE = "<style>" +
        ".amb-review-highlight{outline:2px solid #2563eb !important;outline-offset:2px !important;border-radius:3px !important;}" +
        ".amb-review-highlight-resolved{outline:2px dashed #64748b !important;outline-offset:2px !important;border-radius:3px !important;}" +
        ".amb-review-focus{outline:3px solid #f59e0b !important;outline-offset:3px !important;transition:outline-color .2s;}" +
        ".amb-review-badge{position:absolute;z-index:2147483640;background:#2563eb;color:#fff;font:600 11px/1 system-ui,sans-serif;padding:3px 7px;border-radius:999px;border:1.5px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.22);cursor:pointer;user-select:none;}" +
        ".amb-review-badge-resolved{background:#64748b;}" +
        "html.amb-comment-mode,html.amb-comment-mode *{cursor:crosshair !important;}" +
        "</style>"

    internal const val REVIEW_SCRIPT = """<script>(()=>{
if(!window.__ambReview)return;
let commentMode=false;
let currentSpecs=[];
let badgeLayer=null;
const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
const ensureLayer=()=>{
  if(badgeLayer&&badgeLayer.isConnected)return badgeLayer;
  badgeLayer=document.createElement('div');
  badgeLayer.setAttribute('data-amb-review-ui','1');
  badgeLayer.style.cssText='position:absolute;top:0;left:0;width:0;height:0;pointer-events:none;z-index:2147483640;';
  document.body&&document.body.appendChild(badgeLayer);
  return badgeLayer;
};
const domPathFor=el=>{
  const path=[];let cur=el;
  while(cur&&cur!==document.body&&cur!==document.documentElement){
    const p=cur.parentElement;if(!p)break;
    const idx=Array.prototype.indexOf.call(p.children,cur);
    if(idx<0)break;
    path.unshift(idx);cur=p;
  }
  return path;
};
const selectorFor=el=>{
  if(el.id&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(el.id))return '#'+el.id;
  const parts=[];let cur=el;
  while(cur&&cur!==document.body&&cur!==document.documentElement&&parts.length<8){
    if(cur.id&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(cur.id)){parts.unshift('#'+cur.id);break;}
    const tag=cur.tagName.toLowerCase();
    const p=cur.parentElement;
    if(!p){parts.unshift(tag);break;}
    const sibs=Array.prototype.filter.call(p.children,c=>c.tagName===cur.tagName);
    const idx=sibs.indexOf(cur)+1;
    parts.unshift(sibs.length>1?(tag+':nth-of-type('+idx+')'):tag);
    cur=p;
  }
  return parts.join(' > ');
};
const attrFingerprintFor=el=>{
  const keys=['data-asset','data-asset-background','alt','aria-label','title','name','type','role','href'];
  const parts=[];
  for(const k of keys){
    const v=norm(el.getAttribute&&el.getAttribute(k));
    if(v)parts.push(k+'='+v.slice(0,60));
  }
  return parts.join('|').slice(0,256);
};
const strictElementMatches=(el,a)=>{
  if(!el||el.closest('[data-amb-review-ui]'))return false;
  const tag=a.tag_name?String(a.tag_name).toLowerCase():null;
  if(tag&&el.tagName.toLowerCase()!==tag)return false;
  const quote=a.text_quote?norm(a.text_quote):'';
  const curText=norm(el.innerText||el.textContent||'');
  const expectedFp=a.attr_fingerprint?norm(a.attr_fingerprint):'';
  const curFp=attrFingerprintFor(el);
  if(quote){
    // Exact normalized match, or stored 160-char prefix of unchanged longer text. Never match shortened/mutated text.
    if(curText!==quote&&!(quote.length>=155&&curText.startsWith(quote)))return false;
  }
  if(expectedFp){
    if(curFp!==expectedFp)return false;
  }
  // For textless elements without an explicit element_id, require a distinguishing attr_fingerprint (never guess on tag+dom_path alone).
  if(!quote&&!expectedFp&&!a.element_id)return false;
  return true;
};
const resolveAnchor=a=>{
  if(!a||a.kind==='document')return null;
  if(a.element_id){
    const byId=document.getElementById(a.element_id);
    if(strictElementMatches(byId,a))return byId;
  }
  if(a.selector){
    try{
      const nodes=document.querySelectorAll(a.selector);
      if(nodes.length===1&&strictElementMatches(nodes[0],a))return nodes[0];
    }catch{}
  }
  if(Array.isArray(a.dom_path)&&a.dom_path.length>0&&document.body){
    let cur=document.body;
    for(const idx of a.dom_path){
      if(!cur||!cur.children||idx<0||idx>=cur.children.length){cur=null;break;}
      cur=cur.children[idx];
    }
    if(strictElementMatches(cur,a))return cur;
  }
  return null;
};
const renderOverlays=()=>{
  document.querySelectorAll('.amb-review-highlight,.amb-review-highlight-resolved,.amb-review-focus').forEach(el=>{
    el.classList.remove('amb-review-highlight','amb-review-highlight-resolved','amb-review-focus');
  });
  const layer=ensureLayer();
  if(layer)layer.innerHTML='';
  const matches={};
  for(const spec of currentSpecs){
    const el=resolveAnchor(spec.anchor);
    const matched=Boolean(el);
    matches[spec.threadId]=matched;
    if(!el)continue;
    el.classList.add(spec.resolved?'amb-review-highlight-resolved':'amb-review-highlight');
    const rect=el.getBoundingClientRect();
    if(layer&&(rect.width>0||rect.height>0)){
      const btn=document.createElement('button');
      btn.type='button';
      btn.className='amb-review-badge'+(spec.resolved?' amb-review-badge-resolved':'');
      btn.style.pointerEvents='auto';
      btn.style.left=Math.max(4,rect.left+window.scrollX)+'px';
      btn.style.top=Math.max(4,rect.top+window.scrollY-12)+'px';
      btn.textContent=spec.replyCount>0?('💬 '+(spec.replyCount+1)):'💬';
      btn.addEventListener('click',ev=>{ev.preventDefault();ev.stopPropagation();window.__ambReview.badgeTapped(spec.threadId);});
      layer.appendChild(btn);
    }
  }
  window.__ambReview.anchorMatches(JSON.stringify(matches));
};
window.__ambSetCommentMode=on=>{
  commentMode=Boolean(on);
  document.documentElement.classList.toggle('amb-comment-mode',commentMode);
};
window.__ambSyncAnchors=specs=>{
  currentSpecs=Array.isArray(specs)?specs:[];
  renderOverlays();
};
window.__ambFocusThread=threadId=>{
  const spec=currentSpecs.find(s=>s.threadId===threadId);
  if(!spec)return;
  const el=resolveAnchor(spec.anchor);
  if(!el)return;
  el.scrollIntoView({behavior:'smooth',block:'center'});
  el.classList.add('amb-review-focus');
  setTimeout(()=>el.classList.remove('amb-review-focus'),1800);
};
document.addEventListener('click',e=>{
  if(!commentMode)return;
  const target=e.target&&e.target.nodeType===1?e.target:e.target&&e.target.parentElement;
  if(!target||target.closest('[data-amb-review-ui]')||target===document.documentElement||target===document.body)return;
  e.preventDefault();e.stopImmediatePropagation();
  const txt=norm(target.innerText||target.textContent||'').slice(0,160);
  const fp=attrFingerprintFor(target);
  const anchor={
    kind:'html_element',
    element_id:target.id||undefined,
    selector:selectorFor(target),
    dom_path:domPathFor(target),
    tag_name:target.tagName.toLowerCase(),
    text_quote:txt||undefined,
    attr_fingerprint:fp||undefined
  };
  window.__ambReview.targetSelected(JSON.stringify(anchor));
},true);
window.addEventListener('resize',()=>{if(currentSpecs.length)renderOverlays();});
})();</script>"""

    fun build(html: String, enableReviewOverlay: Boolean = false): String {
        val reviewHead = if (enableReviewOverlay) REVIEW_STYLE else ""
        val reviewTail = if (enableReviewOverlay) REVIEW_SCRIPT else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\"><meta name=\"referrer\" content=\"no-referrer\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;min-height:100%;font:14px system-ui;color:#172235;background:#fff}" +
            "*{box-sizing:border-box}</style>$reviewHead$GUARD</head><body>$html$reviewTail</body></html>"
    }
}

/**
 * Creates the sandboxed view, or a failure when the device cannot isolate it (see [WebProfiles]). The document loads
 * once with an opaque origin; every request it makes is refused at the WebView too (not only by its CSP), it may not
 * navigate or open windows, and it has no storage, file access, credentials, or signer bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createSandboxedHtmlView(
    context: Context,
    html: String,
    beforeLoad: (WebView) -> Unit = {},
    onNavigation: () -> Unit = {},
    onStopped: () -> Unit = {},
    reviewBridge: HtmlReviewBridge? = null,
    onPageReady: (WebView) -> Unit = {},
): Result<WebView> = runCatching {
    if (!WebProfiles.supported) throw WebSurfaceUnavailable(WebProfiles.UNSUPPORTED_MESSAGE)
    val view = WebView(context)
    try {
        WebProfiles.assign(view, WebProfiles.ARTEFACTS)
    } catch (e: Exception) {
        view.destroy()
        throw e
    }
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        blockNetworkLoads = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    if (reviewBridge != null) {
        view.addJavascriptInterface(reviewBridge, "__ambReview")
    }
    var loaded = false
    view.webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
            onStopped()
            return true
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Only the generated document itself loads (once); every request it makes is refused.
            if (!loaded && request.isForMainFrame && request.url.scheme in setOf("data", "about")) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { onNavigation(); return true }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (loaded) { view.stopLoading(); onNavigation() }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loaded = true
            onPageReady(view)
        }
    }
    view.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }
    view.setDownloadListener { _, _, _, _, _ -> }
    beforeLoad(view)
    view.loadDataWithBaseURL(null, HtmlArtefactDocument.build(html, enableReviewOverlay = reviewBridge != null), "text/html", "utf-8", null)
    view
}

private fun encodeAnchorSpecsJson(specs: List<HtmlAnchorTargetSpec>): String = buildJsonArray {
    specs.forEach { spec ->
        add(buildJsonObject {
            put("threadId", spec.threadId)
            put("resolved", spec.resolved)
            put("replyCount", spec.replyCount)
            put("anchor", spec.anchor.toJson())
        })
    }
}.toString()

/**
 * An HTML artefact shown in its labelled, sandboxed view (a canvas naplet expanded, or opened from a reference).
 * The label names where the content comes from and says it is a sandboxed web document.
 */
@Composable
fun SandboxedHtmlScreen(
    html: String,
    label: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    commentMode: Boolean = false,
    anchorSpecs: List<HtmlAnchorTargetSpec> = emptyList(),
    focusThreadNonce: Pair<String, Int>? = null,
    onTargetSelected: ((HtmlCommentAnchor) -> Unit)? = null,
    onAnchorMatches: ((Map<String, Boolean>) -> Unit)? = null,
    onBadgeTapped: ((String) -> Unit)? = null,
) {
    val amb = Amb.colors
    val context = LocalContext.current
    val stopped = remember(html) { androidx.compose.runtime.mutableStateOf(false) }
    val pageReady = remember(html) { androidx.compose.runtime.mutableStateOf(false) }
    val commentModeRef = remember { androidx.compose.runtime.mutableStateOf(commentMode) }
    commentModeRef.value = commentMode
    val activeRef = remember(html) { androidx.compose.runtime.mutableStateOf(true) }
    DisposableEffect(html) {
        activeRef.value = true
        onDispose { activeRef.value = false }
    }
    val reviewBridge = remember(html, onTargetSelected != null) {
        if (onTargetSelected == null && onAnchorMatches == null && onBadgeTapped == null) null
        else HtmlReviewBridge(
            isActive = { activeRef.value },
            isCommentModeActive = { commentModeRef.value },
            onTargetSelected = { onTargetSelected?.invoke(it) },
            onAnchorMatches = { onAnchorMatches?.invoke(it) },
            onBadgeTapped = { onBadgeTapped?.invoke(it) },
        )
    }
    val view = remember(html, reviewBridge != null) {
        createSandboxedHtmlView(
            context = context,
            html = html,
            onStopped = { stopped.value = true },
            reviewBridge = reviewBridge,
            onPageReady = { pageReady.value = true },
        )
    }
    LaunchedEffect(view, pageReady.value, commentMode) {
        if (pageReady.value && reviewBridge != null) {
            view.getOrNull()?.evaluateJavascript("window.__ambSetCommentMode&&window.__ambSetCommentMode(${if (commentMode) "true" else "false"});", null)
        }
    }
    LaunchedEffect(view, pageReady.value, anchorSpecs) {
        if (pageReady.value && reviewBridge != null) {
            val payload = encodeAnchorSpecsJson(anchorSpecs)
            view.getOrNull()?.evaluateJavascript("window.__ambSyncAnchors&&window.__ambSyncAnchors($payload);", null)
        }
    }
    LaunchedEffect(view, pageReady.value, focusThreadNonce) {
        val threadId = focusThreadNonce?.first ?: return@LaunchedEffect
        if (pageReady.value && reviewBridge != null) {
            val escaped = JsonPrimitive(threadId).toString()
            view.getOrNull()?.evaluateJavascript("window.__ambFocusThread&&window.__ambFocusThread($escaped);", null)
        }
    }
    DisposableEffect(view) { onDispose { view.getOrNull()?.destroy() } }
    Column(modifier.fillMaxSize()) {
        if (showLabel) Row(
            Modifier.fillMaxWidth().background(amb.warningBackground).padding(horizontal = 12.dp, vertical = 6.dp).testTag("web.label"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Public, null, tint = amb.warning, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = amb.warning, maxLines = 2)
        }
        if (stopped.value) { Notice(NoticeTone.WARNING, "The HTML viewer stopped. Reopen the artefact to retry."); return@Column }
        view.fold(
            onSuccess = { webView -> AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().testTag("naplet.html")) },
            onFailure = { Notice(NoticeTone.WARNING, it.message ?: WebProfiles.UNSUPPORTED_MESSAGE, Modifier.padding(16.dp)) },
        )
    }
}
