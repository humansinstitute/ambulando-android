package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.browser.*
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.nostr.*
import java.io.File
import java.util.UUID

/** Local browser persistence only: disposable identity, HTTPS fixture, no relay or owner identity. */
@RunWith(AndroidJUnit4::class)
class BrowserNip44Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        error("WebView fixture timed out")
    }
    private fun js(browser: BrowserModel, script: String) = instrumentation.runOnMainSync { browser.selected!!.webView.evaluateJavascript(script, null) }
    private fun approve(browser: BrowserModel, type: Class<out NIP07Prompt.Kind>) {
        waitFor { type.isInstance(browser.prompt?.kind) }
        instrumentation.runOnMainSync { browser.answer(browser.prompt!!, NIP07Decision.Allow(false)) }
    }
    private fun title(browser: BrowserModel, prefix: String) { waitFor { browser.selected?.title?.startsWith(prefix) == true } }

    @Test fun signedPrivateServiceListSavesReloadsAndDecryptsThroughRealBridge() = runBlocking {
        val dir = File(instrumentation.targetContext.cacheDir, "nip44-${UUID.randomUUID()}").apply { mkdirs() }
        val secrets = object : VaultSecretStore {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(data: ByteArray) { bytes = data.copyOf() }
        }
        val identity = IdentityService(File(dir, "vault.json"), secrets)
        identity.importIdentity("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5", "4821")
        val own = identity.publicKeyHex()
        lateinit var browser: BrowserModel
        val slot = "nip44-test-${UUID.randomUUID()}"
        val html = """
          <html><title>READY</title><body><script>
          const own = '$own';
          const slot = '$slot';
          const list = JSON.stringify([['g','wss://private.example/']]);
          async function save() {
            try {
              const content = await nostr.nip44.encrypt(own, list, {version:2});
              const ev = await nostr.signEvent({kind:10318, tags:[], content});
              localStorage.setItem(slot, JSON.stringify(ev));
              document.title = 'SAVED';
            } catch(e) { document.title = 'ERR ' + e.message; }
          }
          async function read() {
            try {
              const ev = JSON.parse(localStorage.getItem(slot));
              const plain = await nostr.nip44.decrypt(ev.pubkey, ev.content);
              document.title = plain === list && ev.kind === 10318 ? 'READBACK' : 'MISMATCH';
            } catch(e) { document.title = 'DENIED ' + e.message; }
          }
          </script></body></html>
        """.trimIndent()
        try {
            instrumentation.runOnMainSync {
                browser = BrowserModel(instrumentation.targetContext)
                browser.configure(false, emptyMap(), { identity.publicKeyHex() }, { identity.sign(it) }, NIP07SitePersistence {},
                    cipher = { expected, peer, text, decrypt -> identity.nip44(expected, peer, text, decrypt) })
                browser.samplePage = "https://sample.example/" to html
                browser.open("https://sample.example/")
            }
            title(browser, "READY")
            js(browser, "save()")
            approve(browser, NIP07Prompt.Kind.Cipher::class.java)
            approve(browser, NIP07Prompt.Kind.Sign::class.java)
            title(browser, "SAVED")
            var event: NostrEvent? = null
            instrumentation.runOnMainSync {
                browser.selected!!.webView.evaluateJavascript("localStorage.getItem('$slot')") { value ->
                    val text = WireJson.parseToJsonElement(value).let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    event = WireJson.decodeFromString(NostrEvent.serializer(), text)
                }
            }
            waitFor { event != null }
            assertTrue(SignatureVerifier.verify(event!!))
            assertEquals(10318, event!!.kind)
            assertFalse(event!!.content.contains("private.example"))
            assertEquals("[[\"g\",\"wss://private.example/\"]]", identity.nip44(own, own, event!!.content, true))
            instrumentation.runOnMainSync { browser.selected!!.webView.reload() }
            title(browser, "READY")
            js(browser, "read()")
            approve(browser, NIP07Prompt.Kind.Cipher::class.java)
            title(browser, "READBACK")
            js(browser, "read()")
            waitFor { browser.prompt?.kind is NIP07Prompt.Kind.Cipher }
            instrumentation.runOnMainSync { browser.answer(browser.prompt!!, NIP07Decision.Deny(false)) }
            title(browser, "DENIED")
            // A remembered connect grant never suppresses the separate encryption approval.
            js(browser, "nostr.nip44.encrypt(own, 'x', {version:1}).catch(e => document.title='VERSION DENIED')")
            title(browser, "VERSION DENIED")
            instrumentation.runOnMainSync { assertNull(browser.prompt); browser.updateLocked(true) }
            js(browser, "read()")
            title(browser, "DENIED")
            instrumentation.runOnMainSync { assertNull(browser.prompt) }
            // The subframe receives no window.nostr; native listener independently rejects its calls.
            instrumentation.runOnMainSync { browser.updateLocked(false) }
            js(browser, """
              const f = document.createElement('iframe');
              f.srcdoc = '<script>parent.postMessage(typeof window.nostr,"*")<\/script>';
              window.addEventListener('message', e => { if(e.source===f.contentWindow) document.title='FRAME '+e.data; });
              document.body.appendChild(f);
            """.trimIndent())
            title(browser, "FRAME undefined")
            js(browser, """
              const blocked = document.createElement('iframe');
              blocked.srcdoc = '<script>const b=window.ambulandoNostr; b.onmessage=e=>parent.postMessage(JSON.parse(e.data).ok ? "escaped" : "native denied","*"); b.postMessage(JSON.stringify({id:901,method:"nip44.encrypt",params:{pubkey:"$own",text:"x"}}));<\/script>';
              window.addEventListener('message', e => { if(e.source===blocked.contentWindow) document.title='RAW FRAME '+e.data; });
              document.body.appendChild(blocked);
            """.trimIndent())
            title(browser, "RAW FRAME native denied")
            instrumentation.runOnMainSync { assertNull(browser.prompt) }
            js(browser, "localStorage.removeItem('$slot')")
        } finally {
            instrumentation.runOnMainSync { runCatching { browser.closeAll() } }
            identity.remove(); dir.deleteRecursively()
        }
    }
}
