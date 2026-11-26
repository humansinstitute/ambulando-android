package studio.otherstuff.ambulando.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import studio.otherstuff.ambulando.app.ui.onboarding.OnboardingWizard
import studio.otherstuff.ambulando.app.ui.onboarding.onboardingGate
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.onboarding.FakeOnboardingTower
import studio.otherstuff.ambulando.core.onboarding.MemoryOnboardingDraftStore
import studio.otherstuff.ambulando.core.onboarding.OnboardingController
import studio.otherstuff.ambulando.core.transport.TowerOrigin

private const val INVITE = "inv_AbCdEfGh12345678"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingUiTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val secret = ByteArray(32) { 0x21 }
    private val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!

    private fun rig(returnUrl: String? = "https://return.example.com/paid", configure: (FakeOnboardingTower) -> Unit = {}): Pair<FakeOnboardingTower, OnboardingController> {
        val tower = FakeOnboardingTower().also { it.newInvite(INVITE); it.returnAllowlist = setOfNotNull(returnUrl); configure(it) }
        var n = 0
        return tower to OnboardingController(TowerOrigin(tower.origin), npub, EventSigner { NostrKeys.sign(it, secret) }, {}, tower, MemoryOnboardingDraftStore(), makeKey = { "ob_ui_key_%04d".format(++n) })
    }

    private fun show(controller: OnboardingController, connected: Boolean = false) = compose.setContent {
        AmbulandoTheme { OnboardingWizard(controller, launch = { scope.launch { it() } }, isConnected = { connected }, connect = {}, onFinished = {}) }
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    private fun fillAndReview(name: String = "Studio") {
        waitFor("onboarding.name")
        compose.onNodeWithTag("onboarding.name").performTextInput(name)
        compose.onNodeWithTag("onboarding.invite").performTextInput(INVITE)
        tap("onboarding.review")
        waitFor("onboarding.pay")
    }

    @Test fun gateExplainsEveryUnavailableState() {
        assertNull(onboardingGate(false, false, "npub1x"))
        assertTrue(onboardingGate(false, true, "npub1x")!!.contains("family"))
        assertTrue(onboardingGate(true, false, "npub1x")!!.contains("Sample"))
        assertTrue(onboardingGate(false, false, null)!!.contains("Unlock"))
    }

    @Test fun reviewShowsHonestPaymentOptionsAndNothingWasSentBeforeConfirming() {
        val (tower, controller) = rig(); show(controller)
        fillAndReview()
        assertTrue("only reads and the descriptor so far", tower.mutations().isEmpty())
        compose.onNodeWithText("Workspace: Studio").assertExists()
        compose.onNodeWithText("Funding: US$21").assertExists()
        compose.onNodeWithText("Lightning — unavailable", substring = true).assertExists()
        compose.onNodeWithText("Lightning is unavailable right now.").assertExists()
        compose.onNodeWithTag("onboarding.pay").assertIsEnabled()
    }

    @Test fun cardCheckoutIsVisiblyGatedWithoutAnApprovedReturnAddress() {
        val (tower, controller) = rig(returnUrl = null); show(controller)
        fillAndReview()
        compose.onNodeWithTag("onboarding.pay").assertIsNotEnabled()
        compose.onNodeWithText("Card payments are unavailable right now. Your setup is saved; try again later or contact your inviter.").assertExists()
        assertTrue(tower.mutations().isEmpty())
    }

    @Test fun fullPathThroughPaymentToHonestLanding() {
        val (tower, controller) = rig { it.offers.add(FakeOnboardingTower.Offer("11111111-1111-4111-8111-111111111111", "helper", 100, 500)) }
        show(controller)
        waitFor("onboarding.agent.11111111-1111-4111-8111-111111111111")
        compose.onNodeWithTag("onboarding.agent.11111111-1111-4111-8111-111111111111").performScrollTo().performClick()
        fillAndReview()
        tap("onboarding.pay")
        waitFor("onboarding.openPayment")
        // Looking at the payment page, or coming back from it, funds nothing.
        tap("onboarding.check")
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding.openPayment").assertExists()
        assertEquals(0, tower.log.count { it.path.endsWith("/provision") })
        tower.pay(tower.intents.keys.single())
        // Either the manual check or the foreground poll may observe it first; both only read the server.
        if (compose.onAllNodes(hasTestTag("onboarding.check")).fetchSemanticsNodes().isNotEmpty()) runCatching { tap("onboarding.check") }
        waitFor("onboarding.provision")
        tap("onboarding.provision")
        waitFor("onboarding.done")
        compose.onNodeWithText("Agent: chosen but not running. Nothing has been started or spent on it, and it stays off until you send its first request.").assertExists()
        compose.onNodeWithText("General channel: not reported yet", substring = true).assertExists()
        assertEquals(1, tower.log.count { it.path.endsWith("/provision") })
        compose.onNodeWithTag("onboarding.connect").assertExists()
    }

    @Test fun lightningShowsTheInvoiceAndOnlyNStacksRecordMovesSetupForward() {
        val (tower, controller) = rig { it.lightningConfigured = true }; show(controller)
        fillAndReview()
        compose.onNodeWithText("Lightning (bitcoin)").assertExists()
        tap("onboarding.pay.lightning")
        compose.onNodeWithText("Get a Lightning invoice").assertExists()
        assertTrue(tower.mutations().isEmpty())
        tap("onboarding.pay")
        waitFor("onboarding.ln.invoice")
        for (tag in listOf("onboarding.ln.sats", "onboarding.ln.expiry", "onboarding.ln.open", "onboarding.ln.copy")) compose.onNodeWithTag(tag).performScrollTo().assertExists()
        for (tag in listOf("onboarding.ln.open", "onboarding.ln.copy", "onboarding.check")) compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        assertEquals("no card checkout was asked for", 0, tower.log.count { it.path.endsWith("/checkout") })
        val quote = tower.quotes.values.single()
        tower.providerSays(quote.id, "settled")
        compose.waitForIdle()
        assertEquals("the provider paying changes nothing until NStack is asked", 0, tower.log.count { it.path.endsWith("/provision") })
        if (compose.onAllNodes(hasTestTag("onboarding.check")).fetchSemanticsNodes().isNotEmpty()) runCatching { tap("onboarding.check") }
        waitFor("onboarding.provision")
        assertEquals("setup never provisions itself", 0, tower.log.count { it.path.endsWith("/provision") })
    }

    @Test fun aHeldLightningPaymentShowsSupportCopyAndNoWalletButtons() {
        val (tower, controller) = rig { it.lightningConfigured = true }; show(controller)
        fillAndReview()
        tap("onboarding.pay.lightning"); tap("onboarding.pay")
        waitFor("onboarding.ln.invoice")
        tower.providerSays(tower.quotes.values.single().id, "late_payment")
        tap("onboarding.check")
        waitFor("onboarding.ln.support")
        compose.onNodeWithTag("onboarding.ln.open").assertDoesNotExist(); compose.onNodeWithTag("onboarding.ln.copy").assertDoesNotExist()
        assertEquals(1, tower.quotes.size)
    }

    @Test fun aServerVerifiedExpiryAllowsAFreshChoiceOfMethod() {
        val (tower, controller) = rig { it.lightningConfigured = true }; show(controller)
        fillAndReview()
        tap("onboarding.pay.lightning"); tap("onboarding.pay")
        waitFor("onboarding.ln.invoice")
        tower.providerSays(tower.quotes.values.single().id, "expired")
        tap("onboarding.check")
        waitFor("onboarding.review.methodLock")
        compose.onNodeWithText("That Lightning invoice ended and nothing was paid", substring = true).assertExists()
        compose.onNodeWithTag("onboarding.pay.card").assertExists()
    }

    @Test fun anUnfinishedPaymentThisPhoneCannotContinueIsHeldForSupportAndReleasedOnlyOnPurpose() {
        val (tower, controller) = rig { it.lightningConfigured = true }; show(controller)
        fillAndReview()
        tap("onboarding.pay"); waitFor("onboarding.openPayment")
        // NStack now also holds a second unfinished payment for this intent (a legacy row): more than one is never resumed
        val intentId = tower.intents.keys.single()
        tower.quotes["legacy"] = FakeOnboardingTower.Quote(java.util.UUID.randomUUID().toString(), npub, "legacy_key_1", 2100, intentId)
        tap("onboarding.check")
        waitFor("onboarding.support")
        compose.onNodeWithTag("onboarding.openPayment").assertDoesNotExist(); compose.onNodeWithTag("onboarding.repay").assertDoesNotExist()
        val before = tower.mutations().size
        compose.onNodeWithTag("onboarding.release.confirm").assertDoesNotExist()
        tap("onboarding.release")
        compose.onNodeWithTag("onboarding.release.confirm").assertHeightIsAtLeast(48.dp)
        assertEquals("asking to switch sends nothing until it is confirmed", before, tower.mutations().size)
        tap("onboarding.release.confirm")
        waitFor("onboarding.review.methodLock")
        compose.onNodeWithTag("onboarding.pay.card").assertExists()
    }

    @Test fun rolloutDisabledIsExplainedAndKeepsProgress() {
        val (tower, controller) = rig(); show(controller)
        fillAndReview()
        tower.rolloutEnabled = false              // switched off after the config was read
        tap("onboarding.pay")
        waitFor("onboarding.unavailable")
        compose.onNodeWithText("Invited onboarding is switched off on this NStack. Your progress is kept; check again later.").assertExists()
        assertTrue(tower.intents.isEmpty())
        compose.onNodeWithTag("onboarding.retry").assertExists()
    }

    @Test fun configReportingOnboardingOffIsExplainedBeforeAnythingIsAttempted() {
        val (tower, controller) = rig { it.rolloutEnabled = false }; show(controller)
        waitFor("onboarding.unavailable")
        compose.onNodeWithText("Invited onboarding is switched off on this NStack. Your progress is kept; check again later.").assertExists()
        assertTrue(tower.mutations().isEmpty())
    }

    @Test fun missingBackendIsExplainedWithoutClaimingAnOutcome() {
        val (_, controller) = rig { it.routerMissing = true }; show(controller)
        waitFor("onboarding.unavailable")
        compose.onNodeWithTag("onboarding.unavailable").assertTextEquals("This NStack does not offer invited onboarding.")
    }

    @Test fun invalidFormKeepsReviewDisabledAndCustomAmountIsValidated() {
        val (_, controller) = rig(); show(controller)
        waitFor("onboarding.name")
        compose.onNodeWithTag("onboarding.review").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding.name").performTextInput("Studio")
        compose.onNodeWithTag("onboarding.invite").performTextInput(INVITE)
        compose.onNodeWithTag("onboarding.funding.custom").performTextInput("20.99")
        compose.onNodeWithTag("onboarding.review").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding.funding.custom").performTextInput("0")   // 20.990 is still invalid
        compose.onNodeWithTag("onboarding.review").assertIsNotEnabled()
    }

    @Test fun controlsMeetTouchTargetsAndHeadingsAreAnnounced() {
        val (_, controller) = rig(); show(controller)
        fillAndReview()
        for (tag in listOf("onboarding.pay", "onboarding.back")) compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        val headings = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        assertTrue(headings.size >= 2)
        assertNotNull(compose.onNodeWithText("Review before anything is sent").fetchSemanticsNode())
    }

    @Test fun nothingSecretIsRenderedOnReview() {
        val (_, controller) = rig(); show(controller)
        fillAndReview()
        assertTrue(compose.onAllNodes(hasText(INVITE, substring = true)).fetchSemanticsNodes().isEmpty())
        assertFalse(controller.snapshot().toString().contains(INVITE))
    }

    /** UI evidence: PNGs of the real screens written under build/ (not committed). */
    @Test fun capturesScreensForEvidence() {
        val dir = java.io.File("build/onboarding-evidence").also { it.mkdirs() }
        fun shot(name: String) = java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val (_, controller) = rig(returnUrl = null); show(controller)
        waitFor("onboarding.name"); shot("1-form")
        fillAndReview(); shot("2-review-card-gated")
        assertTrue(java.io.File(dir, "2-review-card-gated.png").length() > 1000)
    }
}
