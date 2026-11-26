package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.IOException

/** Fails to write when [failWhen] holds for the draft about to be saved (a full disk, a revoked file). */
class FlakyStore(val inner: OnboardingDraftStore = MemoryOnboardingDraftStore()) : OnboardingDraftStore {
    var failWhen: (OnboardingDraft) -> Boolean = { false }
    var saves = 0
    override fun load(origin: String, signerNpub: String) = inner.load(origin, signerNpub)
    override fun save(draft: OnboardingDraft) { if (failWhen(draft)) throw IOException("disk full"); saves++; inner.save(draft) }
    override fun clear(origin: String, signerNpub: String) = inner.clear(origin, signerNpub)
}

private const val INVITE = "inv_AbCdEfGh12345678"

private class Rig(val tower: FakeOnboardingTower = FakeOnboardingTower(), val store: FlakyStore = FlakyStore(), secretByte: Int = 0x21,
                  val returnUrl: String? = "https://return.example.com/paid") {
    val secret = ByteArray(32) { secretByte.toByte() }
    val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!
    var active = npub
    var signerOverride: EventSigner? = null
    var counter = 0
    val signer = EventSigner { signerOverride?.sign(it) ?: NostrKeys.sign(it, secret) }
    val origin = TowerOrigin(tower.origin)
    fun controller(npub: String = this.npub) = OnboardingController(
        origin, npub, signer, { expected -> if (active != expected) throw IdentityError.SignerMismatch }, tower, store, makeKey = { "ob_key_%04d".format(++counter) },
    )

    init { tower.newInvite(INVITE); tower.returnAllowlist = setOfNotNull(returnUrl) }
}

private suspend fun OnboardingController.fill(name: String = "Studio", funding: Int = 2100, invite: String? = INVITE): OnboardingSnapshot {
    edit(name = name, fundingCents = funding, invite = invite?.let(OnboardingInviteCode::of))
    return confirmReview()
}

class OnboardingTests {
    // MARK: wire, signing and secrecy

    @Test fun everyMutationCarriesAPayloadHashOfTheExactBodyAndReadsDoNot() = runBlocking {
        val r = Rig(); val c = r.controller()
        c.load(); c.fill(); c.continueToPayment()
        val id = c.snapshot().intent!!.id
        r.tower.pay(id); c.provision()
        val mutations = r.tower.mutations()
        assertTrue(mutations.size >= 4)            // account, intent, checkout, provision
        assertTrue(mutations.all { it.signer == NostrKeys.publicKeyHex(r.secret) })
        assertTrue(r.tower.log.filter { it.method == "GET" && it.path != "/.well-known/nstack.json" }.all { it.signer != null })
        assertEquals("{}", mutations.first { it.path.endsWith("/account") }.body)
        assertEquals("{}", mutations.first { it.path.endsWith("/provision") }.body)
        assertEquals(0, r.tower.log.count { it.path == "/.well-known/nstack.json" && it.signer != null })
    }

    @Test fun inviteIsOnlyInTheCreateBodyNeverUrlsLogsOrToString() = runBlocking {
        val r = Rig(); val c = r.controller()
        c.load(); c.fill(); c.continueToPayment()
        assertTrue(r.tower.log.none { it.url.contains(INVITE) || (it.authorization ?: "").contains(INVITE) })
        assertEquals(1, r.tower.log.count { it.body.contains(INVITE) })
        assertEquals("/api/v4/onboarding/intents", r.tower.log.first { it.body.contains(INVITE) }.path)
        assertFalse(OnboardingInviteCode.of(INVITE).toString().contains("AbCd"))
        assertNull("secret leaves the draft once the server holds the claim", r.store.inner.load(r.origin.url, r.npub)!!.invite)
        val draftWithInvite = OnboardingDraft(origin = "o", signerNpub = "s", invite = INVITE, createKey = "ob_key_0001", checkoutKey = "ob_key_0002")
        assertFalse(draftWithInvite.toString().contains("AbCd"))
    }

    @Test fun inviteAndLinkParsing() {
        assertEquals(INVITE, OnboardingInviteInput.parse(" $INVITE ")!!.code.raw)
        assertNull(OnboardingInviteInput.parse(INVITE)!!.suggestedOrigin)
        val link = OnboardingInviteInput.parse("https://tower.example.com/join?invite=$INVITE")!!
        assertEquals("https://tower.example.com", link.suggestedOrigin.toString())
        assertEquals(INVITE, OnboardingInviteInput.parse("https://tower.example.com:8443/#invite=$INVITE")!!.code.raw)
        for (bad in listOf("http://tower.example.com/?invite=$INVITE", "https://u:p@tower.example.com/?invite=$INVITE", "https://tower.example.com/?invite=x",
            "https://tower.example.com/?invite=$INVITE&invite=$INVITE", "inv_ab", "inv_bad char!!", "", "https://tower.example.com/")) assertNull(bad, OnboardingInviteInput.parse(bad))
    }

    @Test fun descriptorOriginMismatchSendsNothingSigned() = runBlocking {
        val r = Rig(); r.tower.descriptorEndpoint = "https://evil.example.com"
        val c = r.controller()
        val s = c.load()
        assertTrue((s.phase as OnboardingPhase.Unavailable).why is OnboardingUnavailability.OriginRejected)
        assertEquals(0, r.tower.signedCount())
    }

    @Test fun fipsOriginIsRefusedWithoutAnyRequest() = runBlocking {
        val r = Rig()
        val fips = TowerOrigin("http://${r.npub}.fips:8080")
        val c = OnboardingController(fips, r.npub, r.signer, {}, r.tower, r.store)
        assertTrue((c.load().phase as OnboardingPhase.Unavailable).why is OnboardingUnavailability.OriginRejected)
        assertTrue(r.tower.log.isEmpty())
    }

    @Test fun missingRouterAndRolloutDisabledAreVisibleStates() = runBlocking {
        val missing = Rig().also { it.tower.routerMissing = true }
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.BackendMissing), missing.controller().load().phase)

        val off = Rig().also { it.tower.rolloutEnabled = false }
        val c = off.controller(); c.load(); c.fill()
        val s = c.continueToPayment()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.RolloutDisabled), s.phase)
        assertTrue("progress is kept", off.store.inner.load(off.origin.url, off.npub)!!.reviewedDigest != null)
        assertTrue(off.tower.intents.isEmpty())
    }

    // MARK: signer binding

    @Test fun differentUnlockedIdentityGetsSignerMismatchAndSignsNothing() = runBlocking {
        val r = Rig(); r.active = "npub1someoneelse"
        val s = r.controller().load()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals(0, r.tower.signedCount())
    }

    @Test fun lockedIdentityIsReportedAsLocked() = runBlocking {
        val r = Rig()
        val c = OnboardingController(r.origin, r.npub, r.signer, { throw IdentityError.Locked }, r.tower, r.store)
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.IdentityLocked), c.load().phase)
        assertEquals(0, r.tower.signedCount())
    }

    @Test fun signerChangedDuringAnAwaitCannotSignTheBody() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill()
        // The wallet now signs as someone else: the returned event's key is checked against the draft's signer.
        val other = ByteArray(32) { 0x55 }
        r.signerOverride = EventSigner { NostrKeys.sign(it, other) }
        val before = r.tower.mutations().size
        val s = c.continueToPayment()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals(before, r.tower.mutations().size)
        assertTrue(r.tower.intents.isEmpty())
    }

    @Test fun identitySwitchedBetweenStepsStopsBeforeEveryLaterStep() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        r.tower.pay(c.snapshot().intent!!.id)
        r.active = "npub1other"
        val before = r.tower.log.size
        for (s in listOf(c.provision(), c.refresh(), c.cancelSetup(), c.continueToPayment())) assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals(before, r.tower.log.size)
    }

    @Test fun draftsAreIsolatedPerSigner() = runBlocking {
        val r = Rig(); val a = r.controller(); a.load(); a.fill("Alpha")
        val otherSecret = ByteArray(32) { 0x44 }
        val otherNpub = NostrKeys.npub(NostrKeys.publicKeyHex(otherSecret))!!
        r.active = otherNpub
        val b = OnboardingController(r.origin, otherNpub, EventSigner { NostrKeys.sign(it, otherSecret) }, { if (r.active != it) throw IdentityError.SignerMismatch }, r.tower, r.store)
        assertEquals("", b.snapshot().draft.workspaceName)
        assertEquals("Alpha", r.controller(r.npub).snapshot().draft.workspaceName)
    }

    // MARK: happy path and honest landing

    @Test fun fullPathIsCreateCheckoutPayProvisionAndNothingFundsByRedirect() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load()
        assertEquals(OnboardingPhase.Form, c.snapshot().phase)
        assertEquals(OnboardingPhase.Review, c.fill().phase)
        val paying = c.continueToPayment()
        assertEquals(OnboardingPhase.AwaitingPayment, paying.phase)
        assertTrue(paying.checkoutUrl!!.startsWith("https://checkout.stripe.com/"))
        val id = paying.intent!!.id
        // Forged / unrelated / cancelled returns never fund and never reach provisioning.
        for (url in listOf("https://return.example.com/paid?status=success", "https://evil.example.com/paid", null)) {
            val s = c.paymentReturned(url)
            assertFalse(s.intent!!.fundingConfirmed)
        }
        assertEquals(OnboardingFailure.Kind.FUNDING_REQUIRED, c.provision().failure?.kind)
        assertEquals(0, r.tower.requests("/api/v4/onboarding/intents/$id/provision").size)
        r.tower.pay(id)
        val returned = c.paymentReturned("https://return.example.com/paid")
        assertEquals(OnboardingPhase.ReadyToProvision, returned.phase)
        val done = c.provision()
        assertEquals(OnboardingPhase.Provisioned, done.phase)
        assertNotNull(done.landing!!.workspaceId)
        assertEquals(1, r.tower.requests("/api/v4/onboarding/intents/$id/provision").size)
    }

    @Test fun selectedAgentIsShownAsNotRunningAndNothingSendsOnItsBehalf() = runBlocking {
        val r = Rig(); r.tower.offers.add(FakeOnboardingTower.Offer("11111111-1111-4111-8111-111111111111", "helper", 100, 500))
        val c = r.controller(); c.load()
        assertEquals(1, c.snapshot().offers.size)
        c.edit(name = "Studio", fundingCents = 2100, agentOfferId = c.snapshot().offers[0].id, invite = OnboardingInviteCode.of(INVITE))
        c.confirmReview(); c.continueToPayment()
        val s = c.snapshot()
        assertEquals(OnboardingAgentState.SELECTED_NOT_PROVISIONED, s.intent!!.agentState)
        assertEquals(100, s.intent!!.agent!!.dailyCapCents)
        assertTrue(s.agentNotRunning)
        r.tower.pay(s.intent!!.id); val done = c.provision()
        assertFalse(done.landing!!.agentRunning); assertFalse(done.landing!!.generalReady)
    }

    @Test fun emptyCatalogueMeansNoAgentChoicesAndUnknownAgentFailsClosed() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load()
        assertTrue(c.snapshot().offers.isEmpty())
        c.edit(name = "Studio", agentOfferId = "22222222-2222-4222-8222-222222222222", invite = OnboardingInviteCode.of(INVITE))
        assertEquals(OnboardingFailure.Kind.AGENT_UNAVAILABLE, c.confirmReview().failure?.kind)
        assertTrue(r.tower.mutations().isEmpty())
    }

    @Test fun paymentOptionsAreHonest() = runBlocking {
        val gated = Rig(returnUrl = null).controller()
        val options = gated.snapshot().paymentOptions
        assertFalse(options.first { it.method == OnboardingPaymentOption.Method.CARD }.available)
        assertNotNull(options.first { it.method == OnboardingPaymentOption.Method.CARD }.unavailableReason)
        assertFalse(options.first { it.method == OnboardingPaymentOption.Method.LIGHTNING }.available)
        // Without an approved return address checkout is refused locally: no checkout request is ever sent.
        val r = Rig(returnUrl = null); val c = r.controller(); c.load(); c.fill()
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, s.failure?.kind)
        assertNull(s.checkoutUrl)
        assertEquals(0, r.tower.log.count { it.path.endsWith("/checkout") })
        assertTrue(listOf("http://return.example.com/paid", "https://return.example.com/paid?x=1", "https://u:p@return.example.com/paid", "https://return.example.com/paid#f").all { OnboardingController.approvedReturn(it) == null })
        assertNotNull(OnboardingController.approvedReturn("https://return.example.com/paid"))
        assertNull("production never accepts plain http, even loopback", OnboardingController.approvedReturn("http://localhost:3000/paid"))
        assertNotNull(OnboardingController.approvedReturn("http://localhost:3000/paid", allowLoopback = true))
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, Rig().also { it.tower.stripeBlocked = true }.controller().let { it.load(); it.fill(); it.continueToPayment().failure?.kind })
    }

    @Test fun serverGatesAreClassified() = runBlocking {
        val full = Rig(); full.tower.invites[INVITE]!!.also { inv -> repeat(inv.max) { inv.seats["o$it"] = "reserved" } }
        val c = full.controller(); c.load(); c.fill()
        assertEquals(OnboardingFailure.Kind.INVITE_FULL, c.continueToPayment().failure?.kind)
        val bad = Rig(); val d = bad.controller(); d.load(); d.fill(invite = "inv_NotARealOne123")
        assertEquals(OnboardingFailure.Kind.INVITE_INVALID, d.continueToPayment().failure?.kind)
        // The server lists an address it will not actually accept: its own refusal is a clear, friendly state and nothing opens.
        val notAllowed = Rig().also { it.tower.configReturnUrls = listOf("https://return.example.com/other") }; val e = notAllowed.controller(); e.load(); e.fill()
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, e.continueToPayment().failure?.kind)
        assertNull(e.snapshot().checkoutUrl)
    }

    @Test fun checkoutThatIsNotExactlyTheBoundTestOrderFailsClosed() = runBlocking {
        for (tweak in listOf<(FakeOnboardingTower) -> Unit>({ it.checkoutUrlOverride = "https://evil.example.com/pay" }, { it.checkoutUrlOverride = "http://checkout.stripe.com/x" },
            { it.checkoutUrlOverride = "https://stripe.com.evil.example/x" }, { it.checkoutModeOverride = "live" }, { it.checkoutAmountDelta = 1 })) {
            val r = Rig(); tweak(r.tower)
            val c = r.controller(); c.load(); c.fill()
            val s = c.continueToPayment()
            assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, s.failure?.kind)
            assertNull(s.checkoutUrl)
            assertNull("no order binding is recorded for an unusable checkout", r.store.inner.load(r.origin.url, r.npub)!!.checkoutOrderId)
        }
    }

    @Test fun reversedFundingBlocksProvisioningAndSaysSo() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val id = c.snapshot().intent!!.id; r.tower.pay(id); r.tower.intents.getValue(id).reversed = true
        val s = c.provision()
        assertEquals(OnboardingFailure.Kind.FUNDING_REVERSED, s.failure?.kind)
        assertEquals(0, r.tower.requests("/api/v4/onboarding/intents/$id/provision").size)
        assertFalse(s.intent!!.fundingConfirmed)
    }

    // MARK: stale revision, locks, edits

    @Test fun staleRevisionLoadsLatestAndForcesAReview() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val id = c.snapshot().intent!!.id
        c.reopenForEditing(); c.edit(name = "Renamed"); c.confirmReview()
        r.tower.intents.getValue(id).revision += 3                      // changed elsewhere
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.STALE_REVISION, s.failure?.kind)
        assertEquals(OnboardingPhase.AwaitingPayment, s.phase)         // order binding is still held; edit/review restarts
        assertNull(c.snapshot().draft.reviewedDigest)
    }

    @Test fun fundingIsLockedOnceCheckoutExists() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        c.reopenForEditing(); c.edit(fundingCents = 5000); c.confirmReview()
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, s.failure?.kind)
        assertEquals(2100, r.tower.intents.values.single().funding)
        assertEquals(0, r.tower.log.count { it.path.endsWith("/options") })
    }

    @Test fun nameChangeBeforeCheckoutIsPushedWithTheCurrentRevision() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        // Setup with no checkout order yet: a new controller from a draft that never reached checkout.
        val r2 = Rig(returnUrl = null); val d = r2.controller(); d.load(); d.fill(); d.continueToPayment()
        d.edit(name = "Second name"); d.confirmReview(); d.continueToPayment()
        assertEquals("Second name", r2.tower.intents.values.single().name)
        assertEquals(2, r2.tower.intents.values.single().revision)
    }

    // MARK: durable keys, ambiguity and relaunch

    @Test fun lostCreateAnswerIsReplayedWithTheSameKeyAndMakesOneIntent() = runBlocking {
        val r = Rig(); r.tower.loseAnswer("/intents")
        val c = r.controller(); c.load(); c.fill()
        val first = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.NETWORK, first.failure?.kind)
        assertEquals(1, r.tower.intents.size)                           // the server did create it
        assertEquals(OnboardingPhase.Review, first.phase)
        assertNotNull("key and invite survived for the replay", r.store.inner.load(r.origin.url, r.npub)!!.invite)
        val relaunched = r.controller(); relaunched.load()
        val second = relaunched.continueToPayment()
        assertEquals(1, r.tower.intents.size)
        assertEquals(OnboardingPhase.AwaitingPayment, second.phase)
        assertEquals(1, r.tower.log.map { it.body }.filter { it.contains("client_request_key") }.map { Regex("\"client_request_key\":\"([^\"]+)\"").find(it)!!.groupValues[1] }.toSet().size)
    }

    @Test fun lostCheckoutAnswerReusesTheCheckoutKeyAndNeverMakesASecondOrder() = runBlocking {
        val r = Rig(); r.tower.loseAnswer("/checkout")
        val c = r.controller(); c.load(); c.fill()
        assertEquals(OnboardingFailure.Kind.NETWORK, c.continueToPayment().failure?.kind)
        assertEquals(1, r.tower.orders.size)
        assertNull(c.snapshot().checkoutUrl)
        val again = r.controller().also { it.load() }.continueToPayment()
        assertEquals(1, r.tower.orders.size)
        assertEquals(OnboardingPhase.AwaitingPayment, again.phase)
        assertNotNull(again.checkoutUrl)
    }

    @Test fun networkFailureBeforeProcessingThenRelaunchUsesTheSameKeys() = runBlocking {
        val r = Rig(); r.tower.failBefore("/intents")
        val c = r.controller(); c.load(); c.fill()
        val keyBefore = c.snapshot().draft.createKey
        assertEquals(OnboardingFailure.Kind.NETWORK, c.continueToPayment().failure?.kind)
        val again = r.controller(); again.load()
        assertEquals(keyBefore, again.snapshot().draft.createKey)
        assertEquals(OnboardingPhase.AwaitingPayment, again.continueToPayment().phase)
        assertEquals(1, r.tower.intents.size)
    }

    @Test fun resumeWithoutADraftAdoptsTheServersActiveSetup() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val fresh = Rig(r.tower, FlakyStore()); val d = fresh.controller()
        val s = d.load()
        assertEquals(r.tower.intents.keys.single(), s.intent!!.id)
        assertEquals("Studio", s.draft.workspaceName)
    }

    @Test fun relaunchAfterPaymentResumesToReadyWithoutASecondCheckout() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        r.tower.pay(c.snapshot().intent!!.id)
        val again = r.controller(); val s = again.load()
        assertEquals(OnboardingPhase.ReadyToProvision, s.phase)
        assertEquals(1, r.tower.log.count { it.path.endsWith("/checkout") })
    }

    @Test fun otherIdentitysIntentIsNeverAdoptedAndKeysRotate() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val oldKey = c.snapshot().draft.createKey
        r.tower.intents.values.single().let { r.tower.intents.remove(it.id); r.tower.intents[it.id] = FakeOnboardingTower.Intent(it.id, "npub1other", it.key, it.digest, it.invite, it.name, it.funding, null, null) }
        val s = r.controller().also { it.load() }
        assertNull(s.snapshot().intent)
        assertNotEquals(oldKey, s.snapshot().draft.createKey)
        assertNotNull(s.snapshot().notice)
    }

    @Test fun provisioningFailureKeepsPaymentAndResumesWithoutAnotherCheckout() = runBlocking {
        val r = Rig(); r.tower.provisionFailures = 1
        val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val id = c.snapshot().intent!!.id; r.tower.pay(id)
        val failed = c.provision()
        assertEquals(OnboardingFailure.Kind.PROVISIONING_FAILED, failed.failure?.kind)
        assertTrue(failed.failure!!.retryable)
        assertTrue(failed.intent!!.fundingConfirmed)
        val done = r.controller().also { it.load() }.provision()
        assertEquals(OnboardingPhase.Provisioned, done.phase)
        assertEquals(1, r.tower.log.count { it.path.endsWith("/checkout") })
    }

    @Test fun concurrentProvisionTapsIssueOneRequest() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val id = c.snapshot().intent!!.id; r.tower.pay(id)
        List(6) { async { c.provision() } }.awaitAll()
        assertEquals(1, r.tower.requests("/api/v4/onboarding/intents/$id/provision").size)
    }

    @Test fun cancelRotatesKeysAndTheNextSetupIsANewIntent() = runBlocking {
        val r = Rig(returnUrl = null); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val keys = c.snapshot().draft.createKey to c.snapshot().draft.checkoutKey
        val s = c.cancelSetup()
        assertEquals(OnboardingPhase.Cancelled, s.phase)
        assertNull(s.draft.intentId)
        assertNotEquals(keys.first, s.draft.createKey); assertNotEquals(keys.second, s.draft.checkoutKey)
    }

    @Test fun cancelWithAnOutstandingCheckoutIsRefusedByTheServerAndSaidSo() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.CHECKOUT_OUTSTANDING, c.cancelSetup().failure?.kind)
        r.tower.expiredOrders.addAll(r.tower.orders.keys)
        assertEquals(OnboardingPhase.Cancelled, c.cancelSetup().phase)
    }

    @Test fun sameNameRetryAfterLostAnswerWithDifferentKeyAdoptsTheExistingSetup() = runBlocking {
        val r = Rig(); r.tower.loseAnswer("/intents")
        val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        // The person edits the funding choice before retrying: a different body, so a different key; the first attempt is found by name.
        c.reopenForEditing(); c.edit(fundingCents = 5000); c.confirmReview()
        c.continueToPayment()
        assertEquals(1, r.tower.intents.size)
    }

    // MARK: adversarial save failures

    @Test fun failedSaveBeforeAnythingIsSentSendsNothing() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill()
        val before = r.tower.mutations().size
        r.store.failWhen = { true }
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure?.kind)
        assertEquals(before, r.tower.mutations().size)
        assertNull(s.checkoutUrl)
    }

    @Test fun failedSaveOfEditAndReviewDoesNotChangeMemoryOrTheDraftOnDisk() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        val onDisk = r.store.inner.load(r.origin.url, r.npub)
        r.store.failWhen = { true }
        val edited = c.edit(name = "Changed")
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, edited.failure?.kind)
        assertEquals("Studio", edited.draft.workspaceName)
        val reopened = c.reopenForEditing()
        assertEquals(onDisk!!.reviewedDigest, reopened.draft.reviewedDigest)
        val reviewed = c.confirmReview()
        assertEquals(onDisk.reviewedDigest, reviewed.draft.reviewedDigest)
        val optionsBefore = r.tower.log.count { it.path.endsWith("/options") }
        val checkoutsBefore = r.tower.log.count { it.path.endsWith("/checkout") }
        c.continueToPayment()
        assertEquals(optionsBefore, r.tower.log.count { it.path.endsWith("/options") })
        assertEquals(checkoutsBefore, r.tower.log.count { it.path.endsWith("/checkout") })
        assertEquals(onDisk, r.store.inner.load(r.origin.url, r.npub))
    }

    @Test fun existingIntentOptionsUpdateIsPersistedBeforeTheRequest() = runBlocking {
        val r = Rig(returnUrl = null); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        c.edit(name = "Second name"); c.confirmReview()
        r.store.failWhen = { true }
        val options = r.tower.log.count { it.path.endsWith("/options") }
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.continueToPayment().failure?.kind)
        assertEquals(options, r.tower.log.count { it.path.endsWith("/options") })
        assertEquals("Studio", r.tower.intents.values.single().name)
    }

    @Test fun failureJustBeforeTheOptionsRequestSendsNoUpdate() = runBlocking {
        val r = Rig(returnUrl = null); val c = r.controller(); c.load(); c.fill(); c.continueToPayment()
        c.edit(name = "Second name"); c.confirmReview()
        var calls = 0
        r.store.failWhen = { ++calls >= 2 }                          // first save (start of pay) ok, the one before options fails
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.continueToPayment().failure?.kind)
        assertEquals(0, r.tower.log.count { it.path.endsWith("/options") })
    }

    @Test fun failureBeforeCheckoutKeySaveSendsNoCheckout() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill()
        var calls = 0
        // saves: start of pay, createDigest, adopt(intent), then the pre-checkout persist
        r.store.failWhen = { ++calls == 4 }
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure?.kind)
        assertEquals(0, r.tower.log.count { it.path.endsWith("/checkout") })
        assertEquals(1, r.tower.intents.size)
        // The same controller retries: no second intent, one order.
        val healed = c.continueToPayment()
        assertEquals(1, r.tower.intents.size); assertEquals(1, r.tower.orders.size)
        assertNotNull(healed.checkoutUrl)
    }

    @Test fun failedBindingSaveAfterCheckoutNeverOpensTheBrowserAndHealsWithTheSameOrder() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill()
        r.store.failWhen = { it.checkoutOrderId != null }
        val s = c.continueToPayment()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure?.kind)
        assertNull("the order binding is not durable, so the checkout link is withheld", s.checkoutUrl)
        assertEquals(1, r.tower.orders.size)
        r.store.failWhen = { false }
        val healed = c.continueToPayment()
        assertEquals(1, r.tower.orders.size)
        assertNotNull(healed.checkoutUrl)
        assertEquals(r.tower.orders.keys.single(), r.store.inner.load(r.origin.url, r.npub)!!.checkoutOrderId)
    }

    @Test fun failedSaveAfterCreateAnswerDoesNotMakeASecondIntentOnRetry() = runBlocking {
        val r = Rig(); val c = r.controller(); c.load(); c.fill()
        r.store.failWhen = { it.intentId != null }                  // the write that records the intent id fails
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.continueToPayment().failure?.kind)
        assertEquals(1, r.tower.intents.size)
        r.store.failWhen = { false }
        val relaunched = r.controller(); relaunched.load()          // adopts the server's setup
        relaunched.continueToPayment()
        assertEquals(1, r.tower.intents.size)
    }

    // MARK: small contract pieces

    @Test fun fundingAndNameRules() {
        assertEquals(2100, OnboardingFunding.cents("21")); assertEquals(2150, OnboardingFunding.cents("21.5")); assertEquals(10000, OnboardingFunding.cents(" 100.00 "))
        for (bad in listOf("20.99", "-21", "21.555", "1e3", "21,00", "", "abc", "10000.01", "0021x")) assertNull(bad, OnboardingFunding.cents(bad))
        assertEquals("US$21", OnboardingFunding.label(2100)); assertEquals("US$21.50", OnboardingFunding.label(2150))
        assertEquals("Studio", OnboardingName.normalized("  Studio ")); assertNull(OnboardingName.normalized("   ")); assertNull(OnboardingName.normalized("x".repeat(81)))
    }

    @Test fun checkoutAndReturnAddressPolicies() {
        assertNotNull(OnboardingController.safeCheckoutUrl("https://checkout.stripe.com/c/pay/cs_test_1#frag"))
        for (bad in listOf("http://checkout.stripe.com/x", "https://evil.com/stripe.com", "https://checkout.stripe.com.evil.com/", java.net.URI("https", "u:p", "checkout.stripe.com", -1, "/", null, null).toASCIIString(), "javascript:alert(1)", "")) assertNull(bad, OnboardingController.safeCheckoutUrl(bad))
        assertTrue(OnboardingController.sameAddress("https://Return.example.com/paid", "https://return.example.com/paid"))
        assertFalse(OnboardingController.sameAddress("https://return.example.com/other", "https://return.example.com/paid"))
        assertFalse(OnboardingController.sameAddress("https://return.example.com:8443/paid", "https://return.example.com/paid"))
    }

    @Test fun intentPathsAcceptOnlyLowercaseUuids() {
        assertNotNull(OnboardingAPI.intentPath("0d9a4b1e-1a3c-4c7e-9a64-5f0e9d2a7c11"))
        for (bad in listOf("../x", "0D9A4B1E-1A3C-4C7E-9A64-5F0E9D2A7C11", "abc", "0d9a4b1e-1a3c-4c7e-9a64-5f0e9d2a7c11/../x")) {
            try { OnboardingAPI.intentPath(bad); throw AssertionError("accepted $bad") } catch (_: studio.otherstuff.ambulando.core.transport.TowerError) {}
        }
    }

    @Test fun fileStoreRoundTripsPerOriginAndSignerAndSurvivesCorruption() {
        val dir = studio.otherstuff.ambulando.core.support.tempDir()
        val store = FileOnboardingDraftStore(dir)
        val d = OnboardingDraft(origin = "https://a.example.com", signerNpub = "npub1a", workspaceName = "W", invite = INVITE, createKey = "ob_key_0001", checkoutKey = "ob_key_0002")
        store.save(d)
        assertEquals(d, store.load("https://a.example.com", "npub1a"))
        assertNull(store.load("https://b.example.com", "npub1a")); assertNull(store.load("https://a.example.com", "npub1b"))
        assertTrue(dir.listFiles()!!.none { it.name.contains("npub1a") || it.name.contains("example") })
        dir.listFiles()!!.forEach { it.writeText("{not json") }
        assertNull(store.load("https://a.example.com", "npub1a"))
        store.save(d); store.clear("https://a.example.com", "npub1a"); assertNull(store.load("https://a.example.com", "npub1a"))
    }
}
