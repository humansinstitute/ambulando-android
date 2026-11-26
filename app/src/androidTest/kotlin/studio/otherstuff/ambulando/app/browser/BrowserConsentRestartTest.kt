package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.browser.*
import studio.otherstuff.ambulando.core.nostr.*

/** Run writeGrant and readGrantAfterProcessRestart in separate instrumentation processes on a disposable device. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class BrowserConsentRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val secret = ByteArray(32) { 1 }
    private val own = NostrKeys.publicKeyHex(secret)
    private val account = "browser-restart-fixture"
    private val grant = NIP07Grant("https://sample.example", own, "encrypt")
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        error("Restart fixture timed out")
    }
    private fun open(consent: BrowserConsentPersistence): BrowserModel {
        lateinit var browser: BrowserModel
        instrumentation.runOnMainSync {
            browser = BrowserModel(instrumentation.targetContext)
            browser.configure(false, emptyMap(), { own }, { NostrKeys.sign(it, secret) }, NIP07SitePersistence {},
                cipher = { _, _, _, _ -> "fixture-result" }, grants = consent.load(), persistGrants = consent::save)
            browser.samplePage = "https://sample.example/" to "<title>READY</title>"
            browser.open("https://sample.example/")
        }
        waitFor { browser.selected?.title == "READY" }
        instrumentation.runOnMainSync { browser.selected!!.webView.evaluateJavascript("nostr.nip44.encrypt('$own','fixture').then(x=>document.title=x).catch(()=>document.title='DENIED')", null) }
        return browser
    }
    @Test fun aWriteGrant() {
        val consent = BrowserConsentPersistence(instrumentation.targetContext, account)
        consent.save(emptySet())
        val browser = open(consent)
        try {
            waitFor { browser.prompt?.kind is NIP07Prompt.Kind.Cipher }
            instrumentation.runOnMainSync { browser.answer(browser.prompt!!, NIP07Decision.Allow(true)) }
            waitFor { browser.selected?.title == "fixture-result" }
            assertEquals(setOf(grant), consent.load())
            assertTrue(BrowserConsentPersistence(instrumentation.targetContext, "$account-other").load().isEmpty())
        } finally { instrumentation.runOnMainSync { browser.closeAll() } }
    }
    @Test fun bReadGrantAfterProcessRestart() {
        val consent = BrowserConsentPersistence(instrumentation.targetContext, account)
        // This method can also run alone after writeGrant; no in-memory broker carries over.
        assertEquals(setOf(grant), consent.load())
        val browser = open(consent)
        try {
            waitFor { browser.selected?.title == "fixture-result" }
            instrumentation.runOnMainSync { assertNull(browser.prompt); browser.revoke(grant) }
            assertTrue(BrowserConsentPersistence(instrumentation.targetContext, account).load().isEmpty())
        } finally { instrumentation.runOnMainSync { browser.closeAll() }; consent.save(emptySet()) }
    }
}
