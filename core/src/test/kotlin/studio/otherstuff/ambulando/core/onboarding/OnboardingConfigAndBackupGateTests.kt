package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupController
import studio.otherstuff.ambulando.core.identitybackup.IdentityBackupGate
import studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin
import studio.otherstuff.ambulando.core.identitybackup.MemoryHostedBackupStore
import studio.otherstuff.ambulando.core.crypto.Nip49
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.TowerOrigin

private const val INVITE = "inv_AbCdEfGh12345678"
private const val RETURN = "https://return.example.com/paid"
private const val PASSWORD = "correct horse battery staple 42"

private class GateRig(val origin: IdentityOrigin, val tower: FakeOnboardingTower = FakeOnboardingTower()) {
    val secret = ByteArray(32) { 0x41 }
    val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!
    val to = TowerOrigin(tower.origin)
    val backups = MemoryHostedBackupStore()
    val backup = HostedBackupController(to, npub, { NostrKeys.sign(it, secret) }, {}, { p, n -> Nip49.encrypt(secret, p, n) }, tower, backups)
    var n = 0
    init { tower.newInvite(INVITE) }
    fun controller() = OnboardingController(
        to, npub, { NostrKeys.sign(it, secret) }, {}, tower, MemoryOnboardingDraftStore(), makeKey = { "ob_key_%04d".format(++n) }, identityGate = backup.gate(origin),
    )
}

private suspend fun OnboardingController.fill(): OnboardingSnapshot {
    edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of(INVITE)); return confirmReview()
}

class OnboardingConfigAndBackupGateTests {
    // MARK: config

    @Test fun cardIsOfferedOnlyForAValidAddressFromTheNStacksOwnApprovedList() = runBlocking {
        val ok = GateRig(IdentityOrigin.EXISTING).controller().also { it.load() }.snapshot()
        assertTrue(ok.paymentOptions.first().available)
        assertEquals(true, ok.config?.cardAvailable)

        // Addresses that are not plain https (or carry credentials / query / fragment) are never used, even if the server lists them.
        for (bad in listOf("http://return.example.com/ok", "https://u:p@return.example.com/ok", "https://return.example.com/ok?x=1", "https://return.example.com/ok#f", "myapp://paid", "not a url")) {
            val r = GateRig(IdentityOrigin.EXISTING, FakeOnboardingTower(returnAllowlist = setOf(bad)))
            val s = r.controller().also { it.load() }.snapshot()
            assertFalse(bad, s.paymentOptions.first().available)
            assertEquals(OnboardingController.CARD_UNAVAILABLE, s.paymentOptions.first().unavailableReason)
        }
        // The first VALID member is used when the list mixes good and bad entries.
        val mixed = GateRig(IdentityOrigin.EXISTING, FakeOnboardingTower().also { it.configReturnUrls = listOf("http://bad.example.com/x", RETURN); it.returnAllowlist = setOf(RETURN) })
        val c = mixed.controller(); c.load(); c.fill(); c.continueToPayment()
        assertTrue(c.snapshot().checkoutUrl != null)
        assertEquals(RETURN, Regex("\"return_url\":\"([^\"]+)\"").find(mixed.tower.log.first { it.path.endsWith("/checkout") }.body)!!.groupValues[1])

        // An empty server list keeps card off with the friendly text.
        assertFalse(GateRig(IdentityOrigin.EXISTING, FakeOnboardingTower(returnAllowlist = emptySet())).controller().also { it.load() }.snapshot().paymentOptions.first().available)
    }

    @Test fun anOlderNStackWithoutTheConfigRouteKeepsCardOffWithoutGuessing() = runBlocking {
        val r = GateRig(IdentityOrigin.EXISTING); r.tower.configAvailable = false
        val c = r.controller(); c.load()
        assertNull(c.snapshot().config)
        assertFalse(c.snapshot().paymentOptions.first().available)
        c.fill(); c.continueToPayment()
        assertEquals(0, r.tower.requests("/api/v4/onboarding/intents/").size + r.tower.mutations().count { it.path.endsWith("/checkout") })
    }

    @Test fun cardOffOnTheServerOrOnboardingDisabledIsReportedBeforeAnythingIsAttempted() = runBlocking {
        val off = GateRig(IdentityOrigin.EXISTING); off.tower.cardConfigured = false
        val a = off.controller().also { it.load() }.snapshot()
        assertFalse(a.paymentOptions.first().available)
        val disabled = GateRig(IdentityOrigin.EXISTING); disabled.tower.rolloutEnabled = false
        val b = disabled.controller().also { it.load() }.snapshot()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.RolloutDisabled), b.phase)
        assertEquals(0, disabled.tower.mutations().size)
    }

    @Test fun lightningIsOfferedOnlyWhenTheServerSaysItCanIssueInvoicesNow() = runBlocking {
        val r = GateRig(IdentityOrigin.EXISTING); r.tower.lightningConfigured = true
        val l = r.controller().also { it.load() }.snapshot().paymentOptions.last()
        assertTrue(l.available); assertNull(l.unavailableReason)
        val off = GateRig(IdentityOrigin.EXISTING).controller().also { it.load() }.snapshot().paymentOptions.last()
        assertFalse(off.available); assertTrue(off.unavailableReason!!.contains("unavailable right now"))
        val disabled = GateRig(IdentityOrigin.EXISTING); disabled.tower.lightningConfigured = true; disabled.tower.rolloutEnabled = false
        assertFalse(disabled.controller().also { it.load() }.snapshot().paymentOptions.last().available)
        val older = GateRig(IdentityOrigin.EXISTING); older.tower.lightningConfigured = true; older.tower.configAvailable = false
        assertFalse("an older NStack without the config route is never guessed at", older.controller().also { it.load() }.snapshot().paymentOptions.last().available)
    }

    @Test fun aNewIdentityCannotCreateAnythingOrAskForAnInvoiceUntilTheServerHoldsItsBackup() = runBlocking {
        val r = GateRig(IdentityOrigin.NEW); r.tower.lightningConfigured = true
        val c = r.controller(); c.load(); c.fill()
        val s = c.continueToPayment(OnboardingPaymentOption.Method.LIGHTNING)
        assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, s.failure?.kind)
        assertEquals("no account, intent or quote is created before the backup", 0, r.tower.mutations().size)
        assertNull(s.draft.method); assertFalse(s.draft.quoteSubmitted)
    }

    // MARK: new-identity backup gate

    @Test fun anExistingIdentityIsNeverGatedAndSendsNoOriginField() = runBlocking {
        val r = GateRig(IdentityOrigin.EXISTING); val c = r.controller()
        c.load(); c.fill(); c.continueToPayment()
        assertTrue(c.snapshot().checkoutUrl != null)
        assertFalse(c.snapshot().backupRequired)
        assertTrue(r.tower.log.none { it.body.contains("identity_origin") })
        assertEquals(0, r.tower.requests("/api/v4/identity-backup/me").size)
    }

    @Test fun aNewIdentityCannotStartPaymentUntilTheHostedCopyIsConfirmedByNStack() = runBlocking {
        val r = GateRig(IdentityOrigin.NEW); val c = r.controller()
        c.load(); c.fill()
        assertTrue(c.snapshot().backupRequired)
        val blocked = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, blocked.failure!!.kind)
        assertNull(blocked.checkoutUrl)
        assertEquals("no account, intent, seat or checkout before the backup", 0, r.tower.mutations().size)

        assertEquals(true, r.backup.save("alice", PASSWORD, PASSWORD).stage.name == "CONFIRMED")
        val paid = c.continueToPayment()
        assertNull(paid.failure)
        assertTrue(paid.checkoutUrl != null)
        assertFalse(paid.backupRequired)
        val create = r.tower.mutations().first { it.path.endsWith("/intents") }
        assertTrue(create.body.contains("\"identity_origin\":\"new\""))
    }

    @Test fun aLocalConfirmedFlagAloneNeverOpensPayment() = runBlocking {
        val r = GateRig(IdentityOrigin.NEW); val c = r.controller()
        r.backup.save("alice", PASSWORD, PASSWORD)
        c.load(); c.fill()
        r.tower.backups.clear()                              // the hosted copy vanished: the local record is stale
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, s.failure!!.kind)
        assertEquals(0, r.tower.mutations().count { it.path.startsWith("/api/v4/onboarding") })
    }

    @Test fun theServersOwnBackupGateIsMappedToTheSameFriendlyState() = runBlocking {
        // A client that believes the copy exists but whose server disagrees at checkout time.
        val r = GateRig(IdentityOrigin.NEW)
        val liar = object : IdentityBackupGate { override val origin = IdentityOrigin.NEW; override fun locallyConfirmed() = true; override suspend fun confirmedOnServer() = true }
        val c = OnboardingController(r.to, r.npub, { NostrKeys.sign(it, r.secret) }, {}, r.tower, MemoryOnboardingDraftStore(), identityGate = liar)
        c.load(); c.fill()
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, s.failure!!.kind)
        assertNull(s.checkoutUrl)
        assertEquals(0, r.tower.paidOrders())
    }

    @Test fun anUnknownOriginIsGatedLikeANewKeyAcrossRelaunchWithZeroPaymentRequests() = runBlocking {
        val r = GateRig(IdentityOrigin.UNKNOWN)
        repeat(2) {                                              // second pass = a relaunch with a fresh controller
            val c = r.controller(); c.load(); c.fill()
            assertTrue(c.snapshot().backupRequired)
            assertEquals(OnboardingFailure.Kind.BACKUP_REQUIRED, c.continueToPayment().failure!!.kind)
        }
        assertEquals(0, r.tower.mutations().size)
        r.backup.save("alice", PASSWORD, PASSWORD)
        val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        assertTrue(c.snapshot().checkoutUrl != null)
        assertTrue(r.tower.mutations().first { it.path.endsWith("/intents") }.body.contains("\"identity_origin\":\"new\""))
    }
}
