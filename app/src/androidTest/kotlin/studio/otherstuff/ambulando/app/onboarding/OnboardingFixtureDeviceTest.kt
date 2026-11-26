package studio.otherstuff.ambulando.app.onboarding

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.browser.BrowserHttpsFixture
import studio.otherstuff.ambulando.app.ui.onboarding.OnboardingWizard
import studio.otherstuff.ambulando.app.ui.onboarding.TopupScreen
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.Nip49
import studio.otherstuff.ambulando.core.identitybackup.FileHostedBackupStore
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupController
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupStage
import studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.onboarding.FakeOnboardingTower
import studio.otherstuff.ambulando.core.onboarding.FileOnboardingDraftStore
import studio.otherstuff.ambulando.core.onboarding.FileTopupDraftStore
import studio.otherstuff.ambulando.core.onboarding.OnboardingController
import studio.otherstuff.ambulando.core.onboarding.OnboardingFailure
import studio.otherstuff.ambulando.core.onboarding.OnboardingInviteCode
import studio.otherstuff.ambulando.core.onboarding.OnboardingIntentStatus
import studio.otherstuff.ambulando.core.onboarding.OnboardingPaymentOption
import studio.otherstuff.ambulando.core.onboarding.OnboardingPhase
import studio.otherstuff.ambulando.core.onboarding.TopupController
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.OkHttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File

/**
 * FIXTURE PROOF, NOT A LIVE SERVICE. A bounded loopback HTTPS server (PUBLIC DISPOSABLE test key, trusted only by the debug
 * localhost network config) answers with the shared strict NIP-98 NStack contract double ([FakeOnboardingTower]). The app's real
 * OkHttp transport, real TLS, real scrypt/XChaCha backup, real file stores and real Compose screen run on the device.
 * Synthetic identities and a synthetic "paid" button only: no NStack, Stripe, provider, model or funds are involved.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFixtureDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val password = "synthetic fixture password 16+"
    private val secret = ByteArray(32) { 0x29 }
    private val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!

    private class Rig(val https: BrowserHttpsFixture, val tower: FakeOnboardingTower, val dir: File) : AutoCloseable {
        override fun close() { https.close(); dir.deleteRecursively() }
    }

    private fun rig(): Rig {
        val https = BrowserHttpsFixture()
        val tower = FakeOnboardingTower(origin = https.base)
        https.respond = { r ->
            val res = runBlocking { tower.send(HttpRequest(r.method, https.base + r.target, r.headers.mapKeys { it.key.split('-').joinToString("-") { p -> p.replaceFirstChar(Char::uppercase) } }, r.body.takeIf { r.method != "GET" }?.toByteArray())) }
            BrowserHttpsFixture.Response(String(res.body), res.status, mapOf("Content-Type" to "application/json"))
        }
        tower.newInvite("inv_AbCdEfGh12345678")
        return Rig(https, tower, File(instrumentation.targetContext.cacheDir, "fixture-${System.nanoTime()}").also { it.mkdirs() })
    }

    private fun shoot(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); Thread.sleep(500)
        val resolver = instrumentation.targetContext.contentResolver
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = "fixture-$name.png"
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(file))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file); put(MediaStore.Downloads.MIME_TYPE, "image/png"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/ambulando-fixture")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun topup(r: Rig, origin: TowerOrigin, active: () -> String = { npub }) = TopupController(
        origin, npub, { NostrKeys.sign(it, secret) }, { if (active() != it) throw IdentityError.SignerMismatch }, OkHttpTransport(),
        FileTopupDraftStore(File(r.dir, "topup").also { it.mkdirs() }),
    )

    /** New identity: backup gate over real TLS, then payment, provisioning, and the top-up retry/incomplete-list rules. */
    @Test fun backupGatePaymentAndTopupRetryOverRealTls() = runBlocking {
        rig().use { r ->
            val origin = TowerOrigin(r.https.base)
            var active = npub
            val transport = OkHttpTransport()
            val require: suspend (String) -> Unit = { if (active != it) throw IdentityError.SignerMismatch }
            val backup = HostedBackupController(origin, npub, { NostrKeys.sign(it, secret) }, require, { p, n -> Nip49.encrypt(secret, p, n) }, transport, FileHostedBackupStore(File(r.dir, "backup").also { it.mkdirs() }))
            val onboarding = OnboardingController(origin, npub, { NostrKeys.sign(it, secret) }, require, transport, FileOnboardingDraftStore(File(r.dir, "draft").also { it.mkdirs() }), identityGate = backup.gate(IdentityOrigin.NEW))
            onboarding.load()
            assertNull(onboarding.snapshot().failure)
            onboarding.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of("inv_AbCdEfGh12345678")); onboarding.confirmReview()
            assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, onboarding.continueToPayment().failure!!.kind)
            assertEquals("no intent, seat or checkout before the backup", 0, r.tower.mutations().size)

            // Identity binding: a swapped identity signs and sends nothing.
            active = "npub1someoneelse"
            assertNotNull(backup.save("alice", password, password).failure)
            assertEquals("a swapped identity signs and sends nothing", 0, r.tower.mutations().size)
            active = npub

            assertEquals(HostedBackupStage.CONFIRMED, backup.save("alice", password, password).stage)
            val stored = r.tower.backups.values.single()
            assertEquals("public ciphertext decrypts on-device to the same identity", secret.toList(), Nip49.decrypt(stored.ncryptsec, password).secret.toList())
            assertTrue("read-back used an unsigned public GET", r.tower.log.any { it.method == "GET" && it.signer == null && it.path.contains("alice") })

            val paying = onboarding.continueToPayment()
            assertNull(paying.failure); assertNotNull(paying.checkoutUrl)
            assertTrue(r.tower.mutations().first { it.path.endsWith("/intents") }.body.contains("\"identity_origin\":\"new\""))
            r.tower.pay(paying.intent!!.id)                      // FIXTURE: synthetic "paid", no Stripe
            onboarding.provision()
            assertEquals(OnboardingIntentStatus.PROVISIONED, onboarding.snapshot().intent!!.status)

            // Top-up: a lost answer replays the same key from disk, never a second order.
            val first = topup(r, origin); first.load(); first.setAmount(2500)
            r.tower.loseAnswer("/topups/checkout")
            assertEquals(OnboardingFailure.Kind.NETWORK, first.startCheckout().failure!!.kind)
            val key = first.snapshot().draft.key
            val relaunched = topup(r, origin)
            assertEquals(key, relaunched.snapshot().draft.key); assertEquals(2500, relaunched.snapshot().draft.amountCents)
            relaunched.load()
            val ordered = relaunched.startCheckout()
            assertNotNull(ordered.checkoutUrl); assertEquals(1, r.tower.topupOrders.size)
            val d = ordered.draft

            // Incomplete page that omits the order: not terminal, same attempt kept, status said to be unknown.
            r.tower.truncatedOut.add(d.orderId!!)
            val unknown = relaunched.refresh()
            assertEquals(d, unknown.draft); assertTrue(unknown.awaiting); assertNotNull(unknown.notice)
            assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, relaunched.setAmount(9900).failure!!.kind)
            // Older NStack with no flag at all reads the same way.
            r.tower.truncatedOut.clear(); r.tower.omitPendingCompleteFlag = true; r.tower.endTopup(d.orderId!!)
            assertEquals(d, relaunched.refresh().draft)
            // A complete page that no longer lists it closes the attempt and the next one gets a fresh key.
            r.tower.omitPendingCompleteFlag = false
            val closed = relaunched.refresh()
            assertNull(closed.draft.orderId); assertTrue(closed.draft.key != key)
        }
    }

    /**
     * Initial onboarding over Lightning, over real TLS and real files: the quote key and method are on disk before the request, a lost
     * reply and a relaunch replay the same key (one quote on the server), the other method stays locked, and money counts only once a
     * server refresh reports the intent funded. The provider is the strict fake NStack's synthetic one: no real invoice exists.
     */
    @Test fun initialLightningKeepsItsKeyAcrossALostReplyAndRelaunchAndFundsOnlyThroughNStack() = runBlocking {
        rig().use { r ->
            r.tower.lightningConfigured = true
            val origin = TowerOrigin(r.https.base)
            val transport = OkHttpTransport()
            val drafts = File(r.dir, "draft").also { it.mkdirs() }
            fun controller() = OnboardingController(origin, npub, { NostrKeys.sign(it, secret) }, {}, transport, FileOnboardingDraftStore(drafts))
            val c = controller()
            c.load()
            assertTrue("config over TLS offers Lightning", c.snapshot().paymentOptions.single { it.method == OnboardingPaymentOption.Method.LIGHTNING }.available)
            c.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of("inv_AbCdEfGh12345678")); c.confirmReview()
            r.tower.loseAnswer("/lightning/quote")
            val lost = c.continueToPayment(OnboardingPaymentOption.Method.LIGHTNING)
            assertEquals(OnboardingFailure.Kind.NETWORK, lost.failure!!.kind)
            val key = lost.draft.quoteKey!!

            val relaunched = controller()                         // a new process: everything comes from the files
            assertEquals(OnboardingPaymentOption.Method.LIGHTNING, relaunched.snapshot().draft.method)
            assertEquals(key, relaunched.snapshot().draft.quoteKey); assertTrue(relaunched.snapshot().draft.quoteSubmitted)
            relaunched.load()
            assertEquals("method_locked", relaunched.continueToPayment(OnboardingPaymentOption.Method.CARD).failure!!.code)
            assertEquals(0, r.tower.log.count { it.path.endsWith("/checkout") })
            val shown = relaunched.continueToPayment(OnboardingPaymentOption.Method.LIGHTNING)
            assertNull(shown.failure); assertNotNull(shown.quote?.invoiceSafe)
            assertEquals("the replayed key returned the same single quote", 1, r.tower.quotes.size)
            assertEquals(setOf("""{"idempotency_key":"$key"}"""), r.tower.mutations().filter { it.path.endsWith("/lightning/quote") }.map { it.body }.toSet())

            // A second device (its own, empty files; same identity) resumes NStack's one outstanding invoice with no replay key and asks for nothing new.
            val second = OnboardingController(origin, npub, { NostrKeys.sign(it, secret) }, {}, transport, FileOnboardingDraftStore(File(r.dir, "draft-second").also { it.mkdirs() }))
            second.load()
            assertEquals(OnboardingPaymentOption.Method.LIGHTNING, second.snapshot().draft.method)
            assertEquals(shown.quote!!.id, second.snapshot().draft.quoteId); assertNull(second.snapshot().draft.quoteKey)
            assertEquals(shown.quote!!.invoice, second.snapshot().quote!!.invoice)
            assertEquals("method_locked", second.continueToPayment(OnboardingPaymentOption.Method.CARD).failure!!.code)
            assertEquals(1, r.tower.quotes.size)
            assertEquals("every quote request used the one key", 1, r.tower.mutations().filter { it.path.endsWith("/lightning/quote") }.map { it.body }.toSet().size)

            // The wallet or provider "paying" is only a fact at the provider; nothing is funded until NStack is asked.
            r.tower.providerSays(shown.quote!!.id, "settled")
            val third = controller()
            assertNull("a new process knows nothing of funding until it asks NStack", third.snapshot().intent)
            third.load()
            assertTrue(third.snapshot().intent!!.fundingConfirmed)
            assertEquals(OnboardingPhase.ReadyToProvision, third.snapshot().phase)
            assertEquals(0, r.tower.mutations().count { it.path.endsWith("/provision") })
            third.provision()
            assertEquals(OnboardingIntentStatus.PROVISIONED, third.snapshot().intent!!.status)
        }
    }

    /** The real wizard on the device showing NStack's invoice (amount, expiry, wallet hand-off), as screenshot evidence. */
    @Test fun wizardOnDeviceShowsTheLightningInvoiceFromNStack() {
        val r = rig()
        try {
            r.tower.lightningConfigured = true
            val origin = TowerOrigin(r.https.base)
            val c = OnboardingController(origin, npub, { NostrKeys.sign(it, secret) }, {}, OkHttpTransport(), FileOnboardingDraftStore(File(r.dir, "draft").also { it.mkdirs() }))
            runBlocking {
                c.load(); c.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of("inv_AbCdEfGh12345678")); c.confirmReview()
                assertNull(c.continueToPayment(OnboardingPaymentOption.Method.LIGHTNING).failure)
            }
            val scope = CoroutineScope(Dispatchers.Default)
            compose.setContent { AmbulandoTheme { OnboardingWizard(c, launch = { scope.launch { it() } }, isConnected = { false }, connect = {}, onFinished = {}) } }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("onboarding.ln.invoice")).fetchSemanticsNodes().isNotEmpty() }
            for (tag in listOf("onboarding.ln.sats", "onboarding.ln.expiry", "onboarding.ln.open", "onboarding.ln.copy")) assertTrue(tag, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty())
            shoot("onboarding-lightning-invoice")
        } finally { r.close() }
    }

    /** The real screen on the device while NStack's waiting list is incomplete, then complete. Screenshots are evidence. */
    @Test fun topupScreenOnDeviceShowsUnknownStatusThenSettles() {
        val r = rig()
        try {
            val origin = TowerOrigin(r.https.base)
            val c = topup(r, origin)
            runBlocking {
                val backup = HostedBackupController(origin, npub, { NostrKeys.sign(it, secret) }, {}, { p, n -> Nip49.encrypt(secret, p, n) }, OkHttpTransport(), FileHostedBackupStore(File(r.dir, "backup").also { it.mkdirs() }))
                val ob = OnboardingController(origin, npub, { NostrKeys.sign(it, secret) }, {}, OkHttpTransport(), FileOnboardingDraftStore(File(r.dir, "draft").also { it.mkdirs() }), identityGate = backup.gate(IdentityOrigin.EXISTING))
                ob.load(); ob.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of("inv_AbCdEfGh12345678")); ob.confirmReview(); ob.continueToPayment()
                r.tower.pay(ob.snapshot().intent!!.id); ob.provision()
                c.load(); c.startCheckout()
                r.tower.truncatedOut.add(c.snapshot().draft.orderId!!)
            }
            val scope = CoroutineScope(Dispatchers.Default)
            compose.setContent { AmbulandoTheme { TopupScreen(c, launch = { scope.launch { it() } }) } }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("topup.explain")).fetchSemanticsNodes().isNotEmpty() }
            scope.launch { c.refresh() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("topup.notice")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(c.snapshot().locked)
            shoot("topup-incomplete-unknown")
            r.tower.truncatedOut.clear(); r.tower.endTopup(c.snapshot().draft.orderId!!)
            scope.launch { c.refresh() }
            compose.waitUntil(20_000) { !c.snapshot().locked && compose.onAllNodes(hasTestTag("topup.locked")).fetchSemanticsNodes().isEmpty() }
            shoot("topup-complete-settled")
        } finally { r.close() }
    }
}
