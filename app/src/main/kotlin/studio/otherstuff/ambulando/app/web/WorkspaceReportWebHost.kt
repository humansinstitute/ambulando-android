package studio.otherstuff.ambulando.app.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.surfaces.WorkspaceReport
import java.util.UUID

/** Fixed envelope adapter for the same parent.postMessage bundle used by web. No Java reflection bridge. */
class WorkspaceReportWebHost private constructor(val view: WebView, val session: String) {
    @Volatile private var closed = false
    private fun onMain(action: () -> Unit) { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action() else view.post(action) }
    fun deliver(value: JsonObject) {
        onMain { if (!closed) view.evaluateJavascript("window.dispatchEvent(new MessageEvent('message',{data:${value.toString().replace("<", "\\u003c")},source:window,origin:'null'}))", null) }
    }
    fun destroy() { if (!closed) { closed = true; onMain { WebViewCompat.removeWebMessageListener(view, "nappletNative"); view.stopLoading(); view.destroy() } } }
    companion object {
        fun create(context: Context, html: String, onMessage: (JsonObject) -> Unit, onStopped: () -> Unit): Result<WorkspaceReportWebHost> = runCatching {
            require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            val session = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            val seen = mutableSetOf<String>()
            val adapter = """<script>(()=>{Object.defineProperty(window,'nappletSession',{value:'$session'});if(typeof crypto.randomUUID!=='function'){Object.defineProperty(crypto,'randomUUID',{value:()=>{const b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;const h=Array.from(b,n=>n.toString(16).padStart(2,'0')).join('');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20)}})}const send=window.nappletNative.postMessage.bind(window.nappletNative);window.postMessage=(data)=>{send(JSON.stringify(data))};})();</script>"""
            // This constructor retains every network/navigation/storage restriction of ordinary HTML.
            val view = createSandboxedHtmlView(context, adapter + html, onStopped = onStopped, beforeLoad = { view ->
                WebViewCompat.addWebMessageListener(view, "nappletNative", setOf("*")) { _, message, origin, main, _ ->
                    if (main && origin.toString() in setOf("null", "")) {
                        val raw = message.data
                        if (raw != null && raw.length <= 1024) runCatching { WorkspaceReport.request(raw, session) }.onSuccess { request ->
                            val id=WorkspaceReport.text(request,"requestId")
                            if (seen.size < 10000 && seen.add(id)) onMessage(request)
                        }
                    }
                }
            }, onNavigation = onStopped).getOrThrow()
            WorkspaceReportWebHost(view, session)
        }
    }
}
