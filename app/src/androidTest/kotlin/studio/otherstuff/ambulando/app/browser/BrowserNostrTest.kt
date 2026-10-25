package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.browser.NIP07Decision
import studio.otherstuff.ambulando.core.browser.NIP07Prompt
import studio.otherstuff.ambulando.core.browser.NIP07SitePersistence
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.nostr.WireJson

/** Drives window.nostr in a real WebView on a sample HTTPS page (no network) with the public NIP-19 test key. */
@RunWith(AndroidJUnit4::class)
class BrowserNostrTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val secret = NostrKeys.secretKey("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")!!
    private val page = """
        <html><body><script>
        async function run() {
          try {
            const pk = await window.nostr.getPublicKey();
            const ev = await window.nostr.signEvent({ kind: 27235, created_at: Math.floor(Date.now()/1000), tags: [["u","https://sample.example/api"],["method","GET"]], content: "" });
            document.title = 'OK ' + pk + ' ' + JSON.stringify(ev);
          } catch (e) { document.title = 'ERR ' + e.message; }
        }
        run();
        </script></body></html>
    """.trimIndent()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        throw AssertionError("condition not met")
    }

    @Test fun pageGetsPublicKeyAndAnApprovedSignatureButNeverTheKey() {
        lateinit var browser: BrowserModel
        instrumentation.runOnMainSync {
            browser = BrowserModel(instrumentation.targetContext)
            browser.configure(
                locked = false, savedSites = emptyMap(),
                publicKey = { NostrKeys.publicKeyHex(secret) },
                sign = { NostrKeys.sign(it, secret) },
                persist = NIP07SitePersistence { },
            )
            browser.samplePage = "https://sample.example/" to page
            browser.open("https://sample.example/")
        }
        // First the site asks for the public key...
        waitFor { browser.prompt?.kind is NIP07Prompt.Kind.Connect }
        instrumentation.runOnMainSync { browser.answer(browser.prompt!!, NIP07Decision.Allow(remember = true)) }
        // ...then every signature is shown before signing.
        waitFor { browser.prompt?.kind is NIP07Prompt.Kind.Sign }
        instrumentation.runOnMainSync {
            assertEquals("https://sample.example", browser.prompt!!.context.origin)
            browser.answer(browser.prompt!!, NIP07Decision.Allow(remember = false))
        }
        var title = ""
        waitFor { title = browser.selected?.title.orEmpty(); title.startsWith("OK") || title.startsWith("ERR") }
        assertTrue(title, title.startsWith("OK "))
        val parts = title.split(" ", limit = 3)
        assertEquals(NostrKeys.publicKeyHex(secret), parts[1])
        val event = WireJson.decodeFromString(NostrEvent.serializer(), parts[2])
        assertEquals(27235, event.kind)
        assertTrue(SignatureVerifier.verify(event))
        assertTrue("the secret never reaches the page", !title.contains("nsec") )
        instrumentation.runOnMainSync { browser.closeAll() }
    }

    @Test fun lockedBrowserRefusesEveryRequest() {
        lateinit var browser: BrowserModel
        instrumentation.runOnMainSync {
            browser = BrowserModel(instrumentation.targetContext)
            browser.configure(true, emptyMap(), { NostrKeys.publicKeyHex(secret) }, { NostrKeys.sign(it, secret) }, NIP07SitePersistence { })
            browser.samplePage = "https://sample.example/" to page
            browser.open("https://sample.example/")
        }
        var title = ""
        waitFor { title = browser.selected?.title.orEmpty(); title.startsWith("ERR") || title.startsWith("OK") }
        assertTrue(title, title.startsWith("ERR"))
        instrumentation.runOnMainSync { assertEquals(null, browser.prompt); browser.closeAll() }
    }
}
