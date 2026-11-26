package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import studio.otherstuff.ambulando.app.ui.onboarding.TopupScreen
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.onboarding.FakeOnboardingTower
import studio.otherstuff.ambulando.core.onboarding.MemoryOnboardingDraftStore
import studio.otherstuff.ambulando.core.onboarding.MemoryTopupDraftStore
import studio.otherstuff.ambulando.core.onboarding.OnboardingController
import studio.otherstuff.ambulando.core.onboarding.OnboardingInviteCode
import studio.otherstuff.ambulando.core.onboarding.TopupController
import studio.otherstuff.ambulando.core.transport.TowerOrigin

private const val INVITE = "inv_AbCdEfGh12345678"

/** Compose states of the repeat top-up screen against the in-process NStack fixture (Stripe test mode; no real payment). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopupUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val secret = ByteArray(32) { 0x29 }
    private val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!

    private fun tower(provisioned: Boolean = true) = FakeOnboardingTower().also { t ->
        t.newInvite(INVITE)
        if (provisioned) runBlocking {
            val c = OnboardingController(TowerOrigin(t.origin), npub, { NostrKeys.sign(it, secret) }, {}, t, MemoryOnboardingDraftStore())
            c.load(); c.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of(INVITE)); c.confirmReview(); c.continueToPayment()
            t.pay(c.snapshot().intent!!.id); c.provision()
        }
    }

    private fun show(t: FakeOnboardingTower, store: MemoryTopupDraftStore = MemoryTopupDraftStore()): TopupController {
        val c = TopupController(TowerOrigin(t.origin), npub, { NostrKeys.sign(it, secret) }, {}, t, store)
        compose.setContent { AmbulandoTheme { TopupScreen(c, launch = { scope.launch { it() } }) } }
        return c
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun explainsWhatItDoesAndDoesNotDoAndAsksForAPageOnlyOnTap() {
        val t = tower(); t.log.clear(); show(t)
        waitFor("topup.start")
        compose.onNodeWithTag("topup.explain").assertExists()
        compose.onNodeWithTag("topup.start").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        assertTrue("loading only reads", t.mutations().isEmpty())
        compose.onNodeWithTag("topup.start").performScrollTo().performClick()
        waitFor("topup.open")
        assertEquals(1, t.topupOrders.size)
        assertEquals(1, t.intents.size)
    }

    @Test fun creditAppearsOnlyAfterNStackReportsIt() {
        val t = tower(); val c = show(t)
        waitFor("topup.start")
        compose.onNodeWithTag("topup.none").assertExists()
        compose.onNodeWithTag("topup.start").performScrollTo().performClick()
        waitFor("topup.open")
        compose.onNodeWithTag("topup.check").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("topup.none").assertExists()
        t.creditTopup(c.snapshot().draft.orderId!!)
        compose.onNodeWithTag("topup.check").performScrollTo().performClick()
        waitFor("topup.credited")
    }

    @Test fun unavailableStatesAreFriendly() {
        val t = tower(); t.cardConfigured = false; t.log.clear(); show(t)
        waitFor("topup.unavailable")
        compose.onNodeWithTag("topup.start").assertIsNotEnabled()
        assertTrue(t.mutations().isEmpty())
    }

    @Test fun capturesTopupForEvidence() {
        val dir = java.io.File("build/onboarding-evidence").also { it.mkdirs() }
        val t = tower(); show(t); waitFor("topup.start")
        java.io.FileOutputStream(java.io.File(dir, "6-topup.png")).use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(java.io.File(dir, "6-topup.png").length() > 1000)
    }

    @Test fun lightningShowsTheInvoiceCopyAndWalletOnlyAfterTheQuoteIsSavedAndNeverCreditsLocally() {
        val t = tower(); t.lightningConfigured = true; val store = MemoryTopupDraftStore(); val c = show(t, store)
        waitFor("topup.method.lightning")
        compose.onNodeWithTag("topup.method.lightning").performScrollTo().performClick()
        compose.onNodeWithTag("topup.ln.start").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        waitFor("topup.ln.invoice")
        assertEquals(c.snapshot().draft.quoteId, t.quotes.keys.single())
        assertEquals("durable before the invoice", c.snapshot().draft.quoteId, store.load(t.origin, npub)!!.quoteId)
        compose.onNodeWithTag("topup.ln.sats").assertExists()
        compose.onNodeWithTag("topup.ln.expiry").assertExists()
        compose.onNodeWithTag("topup.ln.copy").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        waitFor("topup.local")
        compose.onNodeWithTag("topup.none").assertExists()                       // copying or opening a wallet credits nothing
        compose.onNodeWithTag("topup.method.card").assertIsNotEnabled()          // no second payable method
        compose.onNodeWithTag("topup.amount.5000").assertIsNotEnabled()
        compose.onNodeWithTag("topup.locked").assertExists()
        t.payQuote(t.quotes.keys.single())
        compose.onNodeWithTag("topup.check").performScrollTo().performClick()
        waitFor("topup.credited")
        assertEquals(0, t.mutations().count { it.path.endsWith("/topups/checkout") })
    }

    @Test fun anExpiredInvoiceAndAHeldPaymentAreExplainedWithoutOfferingToPay() {
        val t = tower(); t.lightningConfigured = true; show(t)
        waitFor("topup.method.lightning")
        compose.onNodeWithTag("topup.method.lightning").performScrollTo().performClick()
        compose.onNodeWithTag("topup.ln.start").performScrollTo().performClick()
        waitFor("topup.ln.invoice")
        val id = t.quotes.keys.single()
        t.quotes.getValue(id).expiresAt = "2020-01-01T00:00:00.000Z"
        compose.onNodeWithTag("topup.check").performScrollTo().performClick()
        waitFor("topup.ln.expired")
        compose.onNodeWithTag("topup.ln.open").assertDoesNotExist(); compose.onNodeWithTag("topup.ln.copy").assertDoesNotExist()
        t.setQuoteState(id, "mismatch")
        compose.onNodeWithTag("topup.check").performScrollTo().performClick()
        waitFor("topup.ln.support")
        compose.onNodeWithTag("topup.ln.open").assertDoesNotExist()
    }

    @Test fun lightningOffHidesTheChoice() {
        val t = tower(); show(t); waitFor("topup.start")
        compose.onNodeWithTag("topup.method.lightning").assertDoesNotExist()
    }

    @Test fun anUnreadableSavedAttemptShowsOnlyTheFailure() {
        val broken = object : studio.otherstuff.ambulando.core.onboarding.TopupDraftStore {
            override fun load(origin: String, signerNpub: String): studio.otherstuff.ambulando.core.onboarding.TopupDraft? = throw studio.otherstuff.ambulando.core.onboarding.TopupDraftUnreadable()
            override fun save(draft: studio.otherstuff.ambulando.core.onboarding.TopupDraft) = error("must never be written")
            override fun clear(origin: String, signerNpub: String) = error("must never be cleared")
        }
        val t2 = tower(); t2.log.clear()
        val c = TopupController(TowerOrigin(t2.origin), npub, { NostrKeys.sign(it, secret) }, {}, t2, broken)
        compose.setContent { AmbulandoTheme { TopupScreen(c, launch = { scope.launch { it() } }) } }
        waitFor("topup.failure")
        compose.onNodeWithTag("topup.start").assertDoesNotExist(); compose.onNodeWithTag("topup.amount.5000").assertDoesNotExist()
        assertTrue(t2.log.isEmpty())
    }
}
