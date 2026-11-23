package studio.otherstuff.ambulando.app.web

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.surfaces.GenericNapplet
import java.security.SecureRandom

/** Opaque single document adapter, no Java reflection, signer, storage or network bridge. */
class GenericNappletWebHost private constructor(val view: WebView,val session: String) {
    private var closed=false
    private fun main(action: () -> Unit) { if(Looper.myLooper()==Looper.getMainLooper()) action() else view.post(action) }
    fun deliver(value: JsonObject) = main { if(!closed) view.evaluateJavascript("window.dispatchEvent(new MessageEvent('message',{data:${value.toString().replace("<","\\u003c")},source:window,origin:'null'}))",null) }
    fun destroy() { if(!closed) { closed=true; main { WebViewCompat.removeWebMessageListener(view,"nappletNative"); view.stopLoading(); view.loadUrl("about:blank"); view.destroy() } } }
    companion object {
        fun create(context: Context,html: String,binding: GenericNapplet.Binding,accept: (JsonObject)->Boolean,onStopped: ()->Unit): Result<GenericNappletWebHost> = runCatching {
            check(Looper.myLooper()==Looper.getMainLooper()); require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            val session=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            val seen=mutableSetOf<String>()
            val adapter="""<script>(()=>{
                Object.defineProperty(window,'nappletSession',{value:'$session'});
                if(typeof crypto.randomUUID!=='function'){Object.defineProperty(crypto,'randomUUID',{value:()=>{const b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;const h=Array.from(b,n=>n.toString(16).padStart(2,'0')).join('');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20)}})}
                const encoder=new TextEncoder(),unsafe=new Set(['__proto__','prototype','constructor']);
                const text=s=>{if(typeof s!=='string')throw 0;for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);if(c>=0xd800&&c<=0xdbff){const n=s.charCodeAt(++i);if(!(n>=0xdc00&&n<=0xdfff))throw 0;}else if(c>=0xdc00&&c<=0xdfff)throw 0;}if(encoder.encode(s).length>65536)throw 0;};
                const validate=value=>{
                    let nodes=0;
                    const visit=(v,depth)=>{
                        if(++nodes>50000||depth>32)throw 0;
                        if(v===null||typeof v==='boolean')return;
                        if(typeof v==='number'){if(!Number.isFinite(v)||Object.is(v,-0)||Math.abs(v)>Number.MAX_SAFE_INTEGER)throw 0;return;}
                        if(typeof v==='string'){text(v);return;}
                        if(!v||typeof v!=='object'||(!Array.isArray(v)&&Object.getPrototypeOf(v)!==Object.prototype&&Object.getPrototypeOf(v)!==null))throw 0;
                        if(Array.isArray(v)){if(v.length>50000)throw 0;const keys=Object.keys(v);if(keys.length!==v.length||keys.some((k,i)=>k!==String(i)))throw 0;}
                        for(const k of Object.keys(v)){if(unsafe.has(k))throw 0;text(k);visit(v[k],depth+1);}
                    };
                    if(!value||typeof value!=='object'||Array.isArray(value))throw 0;
                    visit(value,1);if(encoder.encode(JSON.stringify(value)).length>1048576)throw 0;
                };
                const send=window.nappletNative.postMessage.bind(window.nappletNative);
                window.postMessage=data=>{try{if(data&&data.operation==='save')validate(data.value);const raw=JSON.stringify(data);if(encoder.encode(raw).length<=1048576+2048)send(raw);}catch{}};
            })();</script>"""
            val view=createSandboxedHtmlView(context,adapter+html,onStopped=onStopped,beforeLoad={ view ->
                WebViewCompat.addWebMessageListener(view,"nappletNative",setOf("*")) { _,message,origin,isMain,_ ->
                    if(isMain && origin.toString() in setOf("null","")) {
                        val raw=message.data
                        if(raw!=null && raw.length<=1048576+2048) runCatching { GenericNapplet.request(raw,session,binding) }.onSuccess { request ->
                            val id=GenericNapplet.text(request,"requestId")
                            // Busy envelopes do not consume their IDs. Dirty IDs share the same replay budget.
                            if(seen.size<10000 && id !in seen && accept(request)) seen.add(id)
                        }
                    }
                }
            },onNavigation=onStopped).getOrThrow()
            GenericNappletWebHost(view,session)
        }
    }
}
