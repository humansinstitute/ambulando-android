package studio.otherstuff.ambulando.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Hosts one of the bundled editor pages (`assets/editors/index.html` for boards, `document.html` for documents).
 *
 * The page is served only from the app's assets on the WebViewAssetLoader origin; every other request (network,
 * file, content) gets an empty 403, every navigation away is refused and no windows open. A document-start script
 * defines `window.webkit.messageHandlers.<channel>.postMessage` and forwards each message to an origin-scoped
 * `WebMessageListener`, so the vendored pages run unmodified. Messages from frames other than the main frame, or from
 * another origin, are dropped. The page never sees a key, signer or `window.nostr`.
 */
@SuppressLint("SetJavaScriptEnabled")
class EditorWebHost private constructor(
    context: Context,
    private val page: String,
    private val channels: Set<String>,
    private val maxMessageChars: Int,
    private val onMessage: (channel: String, body: String) -> Unit,
) {
    var failure by androidx.compose.runtime.mutableStateOf<String?>(null); private set
    private var destroyed = false
    val webView: WebView = WebView(context)

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        require(channels.all { it.matches(Regex("[A-Za-z]{1,32}")) }) { "Invalid channel name" }
        WebProfiles.assign(webView, WebProfiles.EDITORS)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                failure = "The editor stopped. Your last persisted draft is kept; close and reopen the editor."
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                destroy()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (isEditorAsset(url)) assetLoader.shouldInterceptRequest(url)?.let { return it }
                return refused()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        }
        installBridge()
    }

    /** Loads the page; [query] carries non-secret options such as `theme=dark`. */
    fun load(query: String? = null) {
        val url = "$ORIGIN/assets/editors/$page" + (query?.let { "?$it" } ?: "")
        webView.loadUrl(url)
    }

    /** Calls a page function with JSON-encoded string arguments (never interpolated as code). */
    fun call(function: String, vararg args: String?) {
        if (destroyed) return
        require(function.matches(Regex("[A-Za-z_.]{1,80}"))) { "Invalid function" }
        val encoded = args.joinToString(",") { it?.let(JSONObject::quote) ?: "null" }
        webView.evaluateJavascript("(function(){try{$function($encoded)}catch(e){}})()", null)
    }

    fun evaluate(script: String, result: (String?) -> Unit) { if (destroyed) result(null) else webView.evaluateJavascript(script) { result(it) } }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        webView.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, LISTENER)
        }
        webView.destroy()
    }

    private fun installBridge() {
        val handlers = channels.joinToString(",") { ch ->
            "$ch:{postMessage:function(m){$LISTENER.postMessage(JSON.stringify({c:'$ch',b:String(m)}))}}"
        }
        val shim = "(function(){if(window.top!==window)return;" +
            "Object.defineProperty(window,'webkit',{value:Object.freeze({messageHandlers:Object.freeze({$handlers})}),configurable:false});})();"
        WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf(ORIGIN))
        WebViewCompat.addWebMessageListener(webView, LISTENER, setOf(ORIGIN)) { _: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != ORIGIN) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > maxMessageChars + 64) return@addWebMessageListener
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val channel = parsed.optString("c")
            if (channel !in channels) return@addWebMessageListener
            onMessage(channel, parsed.optString("b"))
        }
    }

    private fun isEditorAsset(url: Uri): Boolean =
        url.scheme == "https" && url.host == ASSET_HOST && url.port == -1 &&
            (url.path ?: "").startsWith("/assets/editors/") && !(url.path ?: "").contains("..")

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        /**
         * Creates a host, or a failure the screen shows as "editor unavailable" (never a crash) when this device's
         * WebView lacks the message bridge, document-start scripts or profile isolation the editors need.
         */
        fun create(
            context: Context, page: String, channels: Set<String>, maxMessageChars: Int,
            onMessage: (channel: String, body: String) -> Unit,
        ): Result<EditorWebHost> = runCatching {
            // Checked before any WebView exists, so an unsupported device never creates one.
            requireBridgeSupport()
            if (!WebProfiles.supported) throw WebSurfaceUnavailable(WebProfiles.UNSUPPORTED_MESSAGE)
            EditorWebHost(context, page, channels, maxMessageChars, onMessage)
        }.recoverCatching { if (it is WebSurfaceUnavailable) throw it else throw WebSurfaceUnavailable(it.message ?: WebProfiles.UNSUPPORTED_MESSAGE) }

        private fun requireBridgeSupport() {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) throw WebSurfaceUnavailable(WebProfiles.UNSUPPORTED_MESSAGE)
        }

        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$ASSET_HOST"
        private const val LISTENER = "ambulandoNative"
    }
}
