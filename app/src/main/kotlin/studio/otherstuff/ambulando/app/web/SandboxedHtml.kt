package studio.otherstuff.ambulando.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import java.io.ByteArrayInputStream

/**
 * The web client's canvas HTML sandbox (`CANVAS_HTML_CSP` and its link/form guard): an artefact can run its own script
 * but cannot fetch, frame, submit or navigate, and gets no bridge, signer or `window.nostr`.
 */
object HtmlArtefactDocument {
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
        "connect-src 'none'; media-src data: blob:; frame-src 'none'; child-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"

    private const val GUARD = "<script>(()=>{const stop=e=>{const l=e.target&&e.target.closest&&e.target.closest('a[href],form');if(l){e.preventDefault();" +
        "e.stopImmediatePropagation();const h=e.type==='click'&&l.getAttribute('href');if(h&&h[0]==='#'&&h.length>1){let t=null;try{t=document.getElementById(" +
        "decodeURIComponent(h.slice(1)))}catch{}if(t)t.scrollIntoView({block:'start'})}}};addEventListener('click',stop,true);addEventListener('submit',stop,true);" +
        "window.open=()=>null;})();</script>"

    fun build(html: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\"><meta name=\"referrer\" content=\"no-referrer\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;min-height:100%;font:14px system-ui;color:#172235;background:#fff}" +
            "*{box-sizing:border-box}</style>$GUARD</head><body>$html</body></html>"
}

/**
 * Creates the sandboxed view, or a failure when the device cannot isolate it (see [WebProfiles]). The document loads
 * once with an opaque origin; every request it makes is refused at the WebView too (not only by its CSP), it may not
 * navigate or open windows, and it has no storage, file access or script bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createSandboxedHtmlView(context: Context, html: String, onStopped: () -> Unit = {}): Result<WebView> = runCatching {
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

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (loaded) view.stopLoading()
        }

        override fun onPageFinished(view: WebView, url: String?) { loaded = true }
    }
    view.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }
    view.setDownloadListener { _, _, _, _, _ -> }
    view.loadDataWithBaseURL(null, HtmlArtefactDocument.build(html), "text/html", "utf-8", null)
    view
}

/**
 * An HTML artefact shown in its labelled, sandboxed view (a canvas naplet expanded, or opened from a reference).
 * The label names where the content comes from and says it is a sandboxed web document.
 */
@Composable
fun SandboxedHtmlScreen(html: String, label: String, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val context = LocalContext.current
    val stopped = remember(html) { androidx.compose.runtime.mutableStateOf(false) }
    val view = remember(html) { createSandboxedHtmlView(context, html) { stopped.value = true } }
    DisposableEffect(view) { onDispose { view.getOrNull()?.destroy() } }
    Column(modifier.fillMaxSize()) {
        Row(
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
