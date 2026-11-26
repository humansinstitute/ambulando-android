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
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.time.Instant
import java.util.UUID

private const val INVITE = "inv_AbCdEfGh12345678"
private val LN = OnboardingPaymentOption.Method.LIGHTNING
private val CARD = OnboardingPaymentOption.Method.CARD
private val NOW = Instant.parse("2026-11-25T00:00:00Z")

/** Lets a test act at the moment a request reaches NStack ([before]) or after NStack answered but before the app sees it ([after]). */
private class Hooked(val inner: HttpTransport) : HttpTransport {
    var before: suspend (HttpRequest) -> Unit = {}
    var after: suspend (HttpRequest) -> Unit = {}
    override suspend fun send(request: HttpRequest): HttpResponse { before(request); return inner.send(request).also { after(request) } }
}

private class LnRig(secretByte: Int = 0x33) {
    val tower = FakeOnboardingTower().also { it.lightningConfigured = true; it.newInvite(INVITE) }
    val net = Hooked(tower)
    val store = FlakyStore()
    val secret = ByteArray(32) { secretByte.toByte() }
    val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!
    val hex = NostrKeys.publicKeyHex(secret)
    var active = npub
    var n = 0
    /** What the durable store held when each quote request reached NStack. */
    val atQuote = ArrayList<OnboardingDraft?>()
    val atCheckout = ArrayList<OnboardingDraft?>()

    init {
        net.before = { r ->
            if (r.method == "POST" && r.url.endsWith("/lightning/quote")) atQuote.add(store.inner.load(tower.origin, npub))
            if (r.method == "POST" && r.url.endsWith("/checkout")) atCheckout.add(store.inner.load(tower.origin, npub))
        }
    }

    fun controller(s: OnboardingDraftStore = store) = OnboardingController(
        TowerOrigin(tower.origin), npub, { NostrKeys.sign(it, secret) }, { if (active != it) throw IdentityError.SignerMismatch }, net, s,
        makeKey = { "ob_key_%04d".format(++n) },
    )

    fun saved() = store.inner.load(tower.origin, npub)!!
    fun quotePosts() = tower.mutations().filter { it.path.endsWith("/lightning/quote") }
    fun checkoutPosts() = tower.mutations().filter { it.path.endsWith("/checkout") }
}

private suspend fun OnboardingController.ready(funding: Int = 2100): OnboardingController {
    load(); edit(name = "Studio", fundingCents = funding, invite = OnboardingInviteCode.of(INVITE)); confirmReview(); return this
}

private fun raw(status: Int, body: String) = HttpResponse(status, body.toByteArray())

class OnboardingLightningTests {
    // MARK: the happy path, its wire shape and its ordering

    @Test fun anInvoiceIsAskedForWithASignedExactBodyAfterMethodKeyIntentAndAmountAreDurable() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val s = c.continueToPayment(LN)
        assertNull(s.failure)
        val post = r.quotePosts().single()
        assertEquals(r.hex, post.signer)                                       // strict NIP-98: the fake rejects an unsigned or unhashed body
        assertEquals("/api/v4/onboarding/intents/${s.intent!!.id}/lightning/quote", post.path)
        assertEquals("""{"idempotency_key":"${s.draft.quoteKey}"}""", post.body)   // the amount is the server's, never sent
        val seen = r.atQuote.single()!!
        assertEquals(LN, seen.method); assertTrue(seen.quoteSubmitted); assertNull(seen.quoteId)
        assertEquals(s.draft.quoteKey, seen.quoteKey); assertEquals(s.intent!!.id, seen.quoteIntentId); assertEquals(2100, seen.quoteAmountCents)
        val q = s.quote!!
        assertEquals(q.id, r.saved().quoteId); assertEquals(TopupQuote.Phase.PAYABLE, q.phase(NOW)); assertNotNull(q.invoiceSafe)
        assertEquals(OnboardingPhase.AwaitingPayment, s.phase); assertFalse(s.intent!!.fundingConfirmed)
        assertEquals("no card checkout was ever asked for", 0, r.checkoutPosts().size)
        assertEquals("no provisioning or agent start", 0, r.tower.mutations().count { it.path.endsWith("/provision") })
    }

    @Test fun theQuoteIdIsDurableBeforeTheInvoiceShowsAndAFailedSaveOnlyEverReplaysTheSameKey() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.store.failWhen = { it.quoteId != null }
        val bad = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, bad.failure!!.kind)
        assertNull("no invoice without a durable quote binding", bad.quote)
        assertEquals(1, r.tower.quotes.size)
        val key = c.snapshot().draft.quoteKey
        r.store.failWhen = { false }
        val ok = c.continueToPayment(LN)
        assertNull(ok.failure); assertNotNull(ok.quote)
        assertEquals("the replay of the same key returned the same quote", 1, r.tower.quotes.size)
        assertEquals(key, ok.draft.quoteKey)
    }

    @Test fun nothingIsSentWhenTheMethodAndKeyCannotBeSavedFirst() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.store.failWhen = { it.method == LN }
        val s = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure!!.kind)
        assertEquals(0, r.quotePosts().size)
        assertNull(s.draft.method); assertNull(r.saved().method)
        r.store.failWhen = { it.quoteSubmitted }
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.continueToPayment(LN).failure!!.kind)
        assertEquals(0, r.quotePosts().size)
    }

    @Test fun aFailedStepStopsTheStepsThatDependOnIt() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.failBefore("/account")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.continueToPayment(LN).failure!!.kind)
        assertEquals(0, r.quotePosts().size); assertTrue(r.tower.intents.isEmpty()); assertNull(c.snapshot().draft.method)
    }

    @Test fun lightningIsNeverRequestedWhenTheDeploymentDoesNotOfferIt() = runBlocking {
        val r = LnRig(); r.tower.lightningConfigured = false
        val c = r.controller().ready()
        assertFalse(c.snapshot().paymentOptions.single { it.method == LN }.available)
        val s = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, s.failure!!.kind)
        assertEquals(0, r.quotePosts().size)
        assertNull("nothing was bound for an unavailable method", s.draft.method)
    }

    // MARK: lost replies, restarts and replays

    @Test fun aLostReplyFreezesTheMethodAndKeyUntilTheSameKeyReplaysAfterARelaunch() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.loseAnswer("/lightning/quote")
        val lost = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.NETWORK, lost.failure!!.kind)
        val key = lost.draft.quoteKey!!
        assertTrue(lost.draft.quoteSubmitted); assertNull(lost.draft.quoteId)
        assertEquals(OnboardingPhase.AwaitingPayment, lost.phase)
        val card = c.continueToPayment(CARD)
        assertEquals("method_locked", card.failure!!.code); assertEquals(0, r.checkoutPosts().size)
        assertFalse(card.paymentOptions.single { it.method == CARD }.available)
        c.edit(fundingCents = 5000); c.confirmReview()
        assertEquals("funding_locked", c.continueToPayment(LN).failure!!.code)
        assertEquals("no second quote was asked for", 1, r.quotePosts().size)
        c.edit(fundingCents = 2100); c.confirmReview()
        val relaunched = r.controller(); relaunched.load()
        assertEquals(key, relaunched.snapshot().draft.quoteKey)
        val s = relaunched.continueToPayment(LN)
        assertNotNull(s.quote); assertEquals(key, s.draft.quoteKey)
        assertEquals(1, r.tower.quotes.size)
        assertEquals(setOf(key), r.quotePosts().map { Regex("\"idempotency_key\":\"([^\"]+)\"").find(it.body)!!.groupValues[1] }.toSet())
    }

    @Test fun aRefusedReplayProvesNothingAboutTheLostFirstRequest() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.loseAnswer("/lightning/quote")
        c.continueToPayment(LN)
        val key = c.snapshot().draft.quoteKey
        r.tower.rolloutEnabled = false                                   // the replay is refused with a code that is "before creation" on a first request
        val refused = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.ROLLOUT_DISABLED, refused.failure!!.kind)
        val d = c.snapshot().draft
        assertEquals(LN, d.method); assertTrue(d.quoteSubmitted); assertEquals(key, d.quoteKey)
        assertEquals(LN, r.saved().method); assertTrue(r.saved().quoteSubmitted)
        r.tower.rolloutEnabled = true
        assertEquals("method_locked", c.continueToPayment(CARD).failure!!.code)
        val s = c.continueToPayment(LN)
        assertNotNull(s.quote); assertEquals(1, r.tower.quotes.size); assertEquals(key, s.draft.quoteKey)
    }

    @Test fun aRefusalOfTheFirstRequestThatNStackGivesBeforeCreatingAnythingFreesTheMethod() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.net.before = { if (it.method == "POST" && it.url.endsWith("/lightning/quote")) r.tower.rolloutEnabled = false }
        val refused = c.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.ROLLOUT_DISABLED, refused.failure!!.kind)
        assertTrue(r.tower.quotes.isEmpty())
        assertNull(c.snapshot().draft.method); assertFalse(c.snapshot().draft.quoteSubmitted); assertNull(c.snapshot().draft.quoteKey)
        r.net.before = {}; r.tower.rolloutEnabled = true
        assertNull("a method that never reached NStack can be switched", c.continueToPayment(CARD).failure)
        assertEquals(1, r.tower.orders.size)
    }

    @Test fun aGenericUnavailableAnswerNeverProvesNoQuoteExistsEvenOnTheFirstRequest() = runBlocking {
        // lightning_unavailable can follow the quote insert (the provider failed while issuing).
        val after = LnRig(); val c = after.controller().ready()
        after.tower.intentQuoteFailsAfterInsert = true
        val failed = c.continueToPayment(LN)
        assertEquals("lightning_unavailable", failed.failure!!.code)
        assertEquals(1, after.tower.quotes.size)
        assertEquals(LN, failed.draft.method); assertTrue(failed.draft.quoteSubmitted)
        assertFalse(failed.paymentOptions.single { it.method == CARD }.available)
        assertEquals("method_locked", c.continueToPayment(CARD).failure!!.code)
        after.tower.intentQuoteFailsAfterInsert = false
        val s = c.continueToPayment(LN)
        assertNotNull(s.quote); assertEquals("the same key returned the same quote", 1, after.tower.quotes.size)
        // and the same answer from a server that never inserted anything is indistinguishable, so it is kept too
        val before = LnRig(); val d = before.controller().ready()
        before.tower.lightningConfigured = false
        assertEquals("lightning_unavailable", d.continueToPayment(LN).failure!!.code)
        assertEquals(LN, d.snapshot().draft.method); assertTrue(d.snapshot().draft.quoteSubmitted)
        before.tower.lightningConfigured = true
        assertNotNull(d.continueToPayment(LN).quote); assertEquals(1, before.tower.quotes.size)
    }

    @Test fun unknownOrMalformedAnswersKeepTheMethodAndKey() = runBlocking {
        val answers = listOf(
            raw(400, """{"code":"something_new"}"""), raw(418, """{"code":"teapot"}"""), raw(500, "oops"), raw(502, """{"code":"lightning_quote_invalid"}"""),
            raw(200, "not json"), raw(201, """{"quote":{"id":"x"}}"""), raw(409, """{"code":"idempotency_conflict"}"""),
        )
        for (answer in answers) {
            val r = LnRig(); val c = r.controller().ready()
            r.tower.intentQuoteRaw = { answer }
            val s = c.continueToPayment(LN)
            assertNotNull(answer.status.toString(), s.failure)
            assertEquals(answer.status.toString(), LN, s.draft.method)
            assertTrue(answer.status.toString(), s.draft.quoteSubmitted); assertNull(s.quote)
            val key = s.draft.quoteKey
            r.tower.intentQuoteRaw = null
            val again = c.continueToPayment(LN)
            assertNotNull(answer.status.toString(), again.quote); assertEquals(key, again.draft.quoteKey)
            assertEquals(1, r.tower.quotes.size)
        }
    }

    @Test fun aQuoteForAnotherIntentOrAmountIsNeverShownAndTheMethodStaysBound() = runBlocking {
        val amount = LnRig(); amount.tower.intentQuoteAmountDelta = 1
        val a = amount.controller().ready().continueToPayment(LN)
        assertEquals("unexpected_quote", a.failure!!.code); assertNull(a.quote); assertNull(a.draft.quoteId); assertEquals(LN, a.draft.method)
        val intent = LnRig(); intent.tower.intentQuoteIntentIdOverride = UUID.randomUUID().toString()
        val b = intent.controller().ready().continueToPayment(LN)
        assertEquals("unexpected_quote", b.failure!!.code); assertNull(b.quote); assertNull(b.draft.quoteId); assertTrue(b.draft.quoteSubmitted)
        assertEquals("a refresh that disagrees is never shown either", 0, intent.tower.intentRefreshBodies.size)
    }

    @Test fun relaunchRestoresTheInvoiceFromNStackWithoutAskingForANewOne() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val first = c.continueToPayment(LN).quote!!
        val relaunched = r.controller()
        assertNull("the invoice is never stored on the device", relaunched.snapshot().quote)
        val s = relaunched.load()
        assertEquals(first.id, s.quote!!.id); assertEquals(first.invoice, s.quote!!.invoice)
        assertEquals(1, r.quotePosts().size)
        assertEquals(listOf("{}"), r.tower.intentRefreshBodies)
        assertEquals(OnboardingPhase.AwaitingPayment, s.phase)
    }

    // MARK: only NStack's intent is money

    @Test fun onlyAServerRefreshOfTheProviderFundsTheIntentAndNothingProvisionsItself() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val id = c.continueToPayment(LN).quote!!.id
        r.tower.providerSays(id, "settled")
        assertFalse("the provider paying means nothing until NStack is asked", c.snapshot().intent!!.fundingConfirmed)
        val ignored = c.paymentReturned("lightning:lnbc1whatever")
        assertFalse(ignored.intent!!.fundingConfirmed); assertNotNull(ignored.notice)
        val after = c.refresh()
        assertEquals("{}", r.tower.intentRefreshBodies.single())
        assertTrue(after.intent!!.fundingConfirmed); assertEquals(OnboardingPhase.ReadyToProvision, after.phase)
        assertEquals(0, r.tower.mutations().count { it.path.endsWith("/provision") })
        assertEquals(OnboardingPhase.Provisioned, c.provision().phase)
    }

    @Test fun aQuoteSaidToBeSettledWithoutFundedIntentCreditsNothing() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val id = c.continueToPayment(LN).quote!!.id
        r.tower.quotes.getValue(id).state = "settled"                      // a hint on the quote only; the intent is not funded
        val s = c.refresh()
        assertEquals("settled", s.quote!!.state); assertFalse(s.intent!!.fundingConfirmed)
        assertEquals(OnboardingFailure.Kind.FUNDING_REQUIRED, c.provision().failure!!.kind)
        assertEquals(0, r.tower.mutations().count { it.path.endsWith("/provision") })
    }

    @Test fun theDeviceClockOnlyChangesWhatIsShownNeverTheBoundAttempt() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val s = c.continueToPayment(LN); val id = s.quote!!.id
        r.tower.quotes.getValue(id).expiresAt = "2020-01-01T00:00:00.000Z"
        val late = c.refresh()
        assertEquals(TopupQuote.Phase.EXPIRED, late.quote!!.phase(NOW))
        assertEquals(LN, late.draft.method); assertEquals(s.draft.quoteKey, late.draft.quoteKey); assertEquals(id, late.draft.quoteId)
        assertEquals("method_locked", c.continueToPayment(CARD).failure!!.code)
        assertEquals("an expired-looking invoice does not start a second one", 1, r.tower.quotes.size)
        assertEquals(1, r.quotePosts().size)
    }

    @Test fun onlyAServerReportedExpiryFreesTheKeyAndANewInvoiceUsesANewKey() = runBlocking {
        for (ended in listOf("expired", "cancelled")) {
            val r = LnRig(); val c = r.controller().ready()
            val first = c.continueToPayment(LN); val oldKey = first.draft.quoteKey
            r.tower.providerSays(first.quote!!.id, ended)
            val s = c.refresh()
            assertNull(ended, s.draft.method); assertNull(ended, s.draft.quoteKey); assertNull(s.draft.quoteId); assertNull(s.quote)
            assertFalse(s.draft.attemptOpen); assertNull(r.saved().method)
            val next = c.continueToPayment(LN)
            assertNotEquals(ended, oldKey, next.draft.quoteKey)
            assertNotEquals(ended, first.quote!!.id, next.quote!!.id)
            assertEquals(2, r.tower.quotes.size)
        }
        val r = LnRig(); val c = r.controller().ready()
        val id = c.continueToPayment(LN).quote!!.id
        r.tower.providerSays(id, "cancelled"); c.refresh()
        assertNull("after a verified end the other method is allowed", c.continueToPayment(CARD).failure)
    }

    @Test fun anExpiredQuoteNeverFreesTheKeyOnceNStackSaysTheIntentIsFunded() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val first = c.continueToPayment(LN); val id = first.quote!!.id
        r.tower.providerSays(id, "expired")
        // the intent is funded (by a payment NStack recorded) between the app's read of it and the refresh answer
        r.net.before = { if (it.url.endsWith("/refresh")) r.tower.pay(first.intent!!.id) }
        val s = c.refresh()
        assertTrue(s.intent!!.fundingConfirmed)
        assertEquals(LN, s.draft.method); assertEquals(first.draft.quoteKey, s.draft.quoteKey); assertEquals(id, s.draft.quoteId)
        assertEquals(LN, r.saved().method)
    }

    @Test fun aPaymentAfterExpiryOrAMismatchIsHeldForSupportAndNeverFreesAnything() = runBlocking {
        for (held in listOf("late_payment", "mismatch")) {
            val r = LnRig(); val c = r.controller().ready()
            val first = c.continueToPayment(LN); val id = first.quote!!.id
            r.tower.providerSays(id, held)
            val s = c.refresh()
            assertEquals(held, s.quote!!.state); assertEquals(held, TopupQuote.Phase.SUPPORT, s.quote!!.phase(NOW))
            assertEquals(held, LN, s.draft.method); assertEquals(held, first.draft.quoteKey, s.draft.quoteKey); assertEquals(id, s.draft.quoteId)
            assertTrue(held, s.supportHold)                                  // NStack's resume:support closes every payment action
            val posts = r.tower.mutations().size
            val again = c.continueToPayment(LN)
            assertEquals(held, OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING, again.failure!!.kind)
            assertEquals(held, posts, r.tower.mutations().size)             // never asks for another invoice, never re-issues, never pays
            assertEquals(held, id, again.draft.quoteId)
            assertEquals(held, "method_locked", c.continueToPayment(CARD).failure!!.code)
            val cancel = c.cancelSetup()
            assertEquals(held, OnboardingFailure.Kind.CHECKOUT_OUTSTANDING, cancel.failure!!.kind)
            assertEquals(held, OnboardingIntentStatus.OPEN, cancel.intent!!.status); assertEquals(held, LN, cancel.draft.method)
        }
    }

    @Test fun cancellingLetsNStackVerifyTheInvoiceFirstAndOnlyThenForgetsTheAttempt() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val first = c.continueToPayment(LN)
        val s = c.cancelSetup()
        assertEquals(OnboardingPhase.Cancelled, s.phase)
        assertNull(s.draft.method); assertNull(s.draft.quoteKey); assertNull(s.draft.quoteId); assertNull(s.draft.intentId); assertNull(s.quote)
        assertNotEquals(first.draft.createKey, s.draft.createKey)
        // a payment that landed before the cancel funds the intent instead of cancelling it
        val paid = LnRig(); val d = paid.controller().ready()
        val q = d.continueToPayment(LN).quote!!
        paid.tower.providerSays(q.id, "settled")
        val result = d.cancelSetup()
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, result.failure!!.kind)
        assertTrue(d.refresh().intent!!.fundingConfirmed)
    }

    // MARK: one method per setup

    @Test fun aCardAttemptBlocksLightningAndALightningAttemptBlocksCard() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val paid = c.continueToPayment(CARD)
        assertNotNull(paid.checkoutUrl)
        assertEquals(CARD, r.atCheckout.single()!!.method)               // bound before the request
        val s = c.continueToPayment(LN)
        assertEquals("method_locked", s.failure!!.code); assertEquals(0, r.quotePosts().size)
        assertFalse(s.paymentOptions.single { it.method == LN }.available)
        assertEquals(OnboardingController.OTHER_METHOD_OPEN, s.paymentOptions.single { it.method == LN }.unavailableReason)
        val replay = c.continueToPayment()                              // no method asked for: the bound one is replayed
        assertNotNull(replay.checkoutUrl); assertEquals(1, r.tower.orders.size)
    }

    @Test fun aLostCheckoutAnswerBindsCardAndARefusedCardReplayDoesNotFreeIt() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.loseAnswer("/checkout")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.continueToPayment(CARD).failure!!.kind)
        assertEquals(CARD, c.snapshot().draft.method); assertEquals(1, r.tower.orders.size)
        val key = c.snapshot().draft.checkoutKey
        val allowed = r.tower.returnAllowlist
        r.tower.returnAllowlist = emptySet()                              // the operator removed the address: a replay is refused before any lookup
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, c.continueToPayment(CARD).failure!!.kind)
        assertEquals(CARD, c.snapshot().draft.method); assertEquals(key, c.snapshot().draft.checkoutKey)
        assertEquals("method_locked", c.continueToPayment(LN).failure!!.code)
        assertEquals(0, r.quotePosts().size)
        r.tower.returnAllowlist = allowed
        assertNotNull(c.continueToPayment(CARD).checkoutUrl); assertEquals(1, r.tower.orders.size)
    }

    @Test fun aCardRefusalBeforeAnyOrderOnTheFirstRequestFreesTheMethod() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.returnAllowlist = emptySet()                              // config was read earlier, so the app still tries once
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, c.continueToPayment(CARD).failure!!.kind)
        assertTrue(r.tower.orders.isEmpty()); assertNull(c.snapshot().draft.method)
        r.tower.returnAllowlist = setOf("https://return.example.com/paid")
        assertNull(c.continueToPayment(LN).failure)
    }

    @Test fun anOldCardDraftThatMayHaveRequestedCheckoutStaysBoundToCard() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.tower.loseAnswer("/checkout"); c.continueToPayment(CARD)
        val modern = r.saved()
        assertEquals(CARD, modern.method)
        // a draft saved by a build that did not track the method
        r.store.inner.save(modern.copy(method = null, methodTracked = false, checkoutOrderId = null))
        val relaunched = r.controller()
        assertEquals(CARD, relaunched.snapshot().draft.method)
        assertEquals(OnboardingController.OTHER_METHOD_OPEN, relaunched.snapshot().paymentOptions.single { it.method == LN }.unavailableReason)
        relaunched.load()
        assertEquals("method_locked", relaunched.continueToPayment(LN).failure!!.code)
        assertEquals(0, r.quotePosts().size)
        assertEquals(CARD, r.saved().method); assertTrue(r.saved().methodTracked)
        assertNotNull(relaunched.continueToPayment().checkoutUrl); assertEquals(1, r.tower.orders.size)
        // a draft that never got past the form is not bound
        r.store.inner.save(OnboardingDraft(origin = r.tower.origin, signerNpub = r.npub, createKey = "k1234567", checkoutKey = "k7654321"))
        assertNull(r.controller().snapshot().draft.method)
        val legacyJson = """{"origin":"o","signerNpub":"n","createKey":"k","checkoutKey":"c"}"""
        val decoded = WireJson.decodeFromString(OnboardingDraft.serializer(), legacyJson)
        assertFalse(decoded.methodTracked); assertNull(decoded.method); assertNull(decoded.quoteKey); assertFalse(decoded.quoteSubmitted)
    }

    @Test fun concurrentRequestsForTheSameOrDifferentMethodsCreateOnePayableAttempt() = runBlocking {
        val same = LnRig(); val c = same.controller().ready()
        listOf(async { c.continueToPayment(LN) }, async { c.continueToPayment(LN) }, async { c.continueToPayment(LN) }).awaitAll()
        assertEquals(1, same.tower.quotes.size); assertEquals(1, same.quotePosts().size)
        val mixed = LnRig(); val d = mixed.controller().ready()
        listOf(async { d.continueToPayment(LN) }, async { d.continueToPayment(CARD) }).awaitAll()
        assertEquals(1, mixed.tower.quotes.size + mixed.tower.orders.size)
    }

    // MARK: identity

    @Test fun anIdentitySwapBeforeTheRequestSendsNothing() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.active = "npub1someoneelse"
        val s = c.continueToPayment(LN)
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals(0, r.tower.mutations().size)
        assertNull(r.store.inner.load(r.tower.origin, r.npub)?.method)
    }

    @Test fun anIdentitySwapDuringTheQuoteRequestKeepsTheBindingButShowsNoInvoice() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        r.net.after = { if (it.url.endsWith("/lightning/quote")) r.active = "npub1someoneelse" }
        val s = c.continueToPayment(LN)
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertNull(s.quote)
        assertNotNull("the answer is kept for this signer", r.saved().quoteId)
        r.active = r.npub; r.net.after = {}
        val back = c.load()
        assertEquals(r.saved().quoteId, back.quote!!.id); assertEquals(1, r.tower.quotes.size)
    }

    @Test fun anIdentitySwapDuringARefreshAppliesNothingFromThatAnswer() = runBlocking {
        val r = LnRig(); val c = r.controller().ready()
        val id = c.continueToPayment(LN).quote!!.id
        r.tower.providerSays(id, "expired")
        r.net.after = { if (it.url.endsWith("/refresh")) r.active = "npub1someoneelse" }
        val s = c.refresh()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals("the answer was not applied, so nothing was freed", LN, r.saved().method)
        assertNotNull(r.saved().quoteKey)
    }

    // MARK: pure display rules

    @Test fun anInvoiceStillBeingIssuedOrWithAnUnknownStateIsNeverPayable() {
        val base = TopupQuote("id", "open", 2100, 1000, "lnbc1abcdefghijklmnopqrstuvwxyz", null, Instant.parse("2026-11-25T01:00:00Z"))
        assertEquals(TopupQuote.Phase.PAYABLE, base.phase(NOW))
        assertEquals(TopupQuote.Phase.PREPARING, base.copy(state = "issuing", invoice = null, amountSats = null, expiresAt = null).phase(NOW))
        for (state in listOf("late_payment", "mismatch", "unheard_of", "")) assertEquals(state, TopupQuote.Phase.SUPPORT, base.copy(state = state).phase(NOW))
        for (state in listOf("settled", "expired", "cancelled")) assertEquals(state, TopupQuote.Phase.ENDED, base.copy(state = state).phase(NOW))
    }
}

/** NStack's canonical `payment_attempt`: one outstanding payment per intent across devices, keys and methods. */
class OnboardingPaymentAttemptTests {
    private fun LnRig.newDevice() = controller(FlakyStore())

    @Test fun aNewDeviceResumesTheLightningInvoiceWithoutTheOriginalKeyOrAReview() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val first = a.continueToPayment(LN)
        val b = r.newDevice()
        val s = b.load()
        assertEquals(LN, s.draft.method); assertEquals(first.quote!!.id, s.draft.quoteId); assertNull("the key never leaves the first device", s.draft.quoteKey)
        assertEquals(first.quote!!.invoice, s.quote!!.invoice); assertEquals(OnboardingPhase.AwaitingPayment, s.phase)
        assertEquals("nothing new was requested", 1, r.quotePosts().size)
        assertEquals("method_locked", b.continueToPayment(CARD).failure!!.code); assertEquals(0, r.checkoutPosts().size)
        val again = b.continueToPayment(LN)
        assertNull(again.failure); assertEquals(first.quote!!.id, again.quote!!.id); assertEquals(1, r.quotePosts().size)
        assertEquals(1, r.tower.quotes.size)
    }

    @Test fun aCheckoutAnswerNamingADifferentOrderThanTheBoundOneIsNeverFollowed() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val first = a.continueToPayment(CARD)
        r.tower.checkoutReplyOrderId = UUID.randomUUID().toString()
        val s = a.continueToPayment(CARD)
        assertEquals("unexpected_checkout", s.failure!!.code); assertNull(s.checkoutUrl)
        assertEquals(first.draft.checkoutOrderId, s.draft.checkoutOrderId)
    }

    @Test fun aNewDeviceResumesTheCardOrderWithAFreshKeyAndNeverCreatesASecondOrOtherMethod() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val paid = a.continueToPayment(CARD)
        val b = r.newDevice(); val loaded = b.load()
        assertEquals(CARD, loaded.draft.method); assertEquals(paid.draft.checkoutOrderId, loaded.draft.checkoutOrderId)
        assertNotEquals(paid.draft.checkoutKey, loaded.draft.checkoutKey)
        assertEquals("method_locked", b.continueToPayment(LN).failure!!.code); assertEquals(0, r.quotePosts().size)
        val resumed = b.continueToPayment(CARD)
        assertNull(resumed.failure); assertNotNull(resumed.checkoutUrl)
        assertEquals("the fresh key was answered with the same order", 1, r.tower.orders.size)
        assertEquals(paid.draft.checkoutOrderId, resumed.draft.checkoutOrderId)
    }

    @Test fun aStaleDeviceAskingForTheOtherMethodIsRefusedByNStackAndAdoptsTheRealAttempt() = runBlocking {
        val r = LnRig()
        // the first device stops after creating the intent; the second takes a copy of that state while nothing is outstanding
        val a = r.controller().ready()
        val allowed = r.tower.returnAllowlist; r.tower.returnAllowlist = emptySet()
        a.continueToPayment(CARD)
        r.tower.returnAllowlist = allowed
        val store2 = FlakyStore().also { it.inner.save(r.saved()) }
        val b = r.controller(store2); b.load()
        assertNull(b.snapshot().draft.method); assertNull(b.snapshot().paymentAttempt)
        val order = a.continueToPayment(CARD).draft.checkoutOrderId           // meanwhile, the first device pays by card
        val refused = b.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING, refused.failure!!.kind)
        assertTrue("no quote was ever created", r.tower.quotes.isEmpty())
        assertEquals(CARD, refused.draft.method); assertEquals(order, refused.draft.checkoutOrderId)
        assertNull(refused.draft.quoteKey); assertFalse(refused.draft.quoteSubmitted)
    }

    @Test fun aFreshKeyForTheSameMethodReturnsTheOutstandingQuote() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val allowed = r.tower.returnAllowlist; r.tower.returnAllowlist = emptySet(); a.continueToPayment(CARD); r.tower.returnAllowlist = allowed
        val b = r.controller(FlakyStore().also { it.inner.save(r.saved()) }); b.load()
        val q = a.continueToPayment(LN).quote!!
        val s = b.continueToPayment(LN)                                          // a different key; NStack returns the one outstanding quote (200, reused)
        assertNull(s.failure); assertEquals(q.id, s.quote!!.id); assertEquals(q.id, s.draft.quoteId)
        assertEquals(1, r.tower.quotes.size)
    }

    @Test fun ambiguousLegacyAttemptsStopEverythingUntilAnExplicitReleaseLetsNStackSettleThem() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val order = a.continueToPayment(CARD).draft.checkoutOrderId
        val intentId = a.snapshot().intent!!.id
        r.tower.quotes["legacy"] = FakeOnboardingTower.Quote(UUID.randomUUID().toString(), r.npub, "legacy_key_1", 2100, intentId)
        val b = r.newDevice(); val s = b.load()
        assertEquals("ambiguous", s.paymentAttempt!!.state); assertTrue(s.supportHold); assertNull(s.draft.method)
        val posts = r.tower.mutations().size
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_AMBIGUOUS, b.continueToPayment(CARD).failure!!.kind)
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_AMBIGUOUS, b.continueToPayment(LN).failure!!.kind)
        assertEquals("nothing was sent", posts, r.tower.mutations().size)
        val released = b.releasePayment()
        assertNull(released.paymentAttempt); assertFalse(released.supportHold); assertFalse(r.tower.outstandingOrders(r.tower.intents.getValue(intentId)).any { it.id == order })
        assertEquals("a new device with nothing outstanding must review before it pays", "review_required", b.continueToPayment(LN).failure!!.code)
        b.confirmReview()
        assertNull(b.continueToPayment(LN).failure)
    }

    @Test fun aServerRefusalNamingAnAmbiguousAttemptIsAdoptedAsSupportAndNeverRetried() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val allowed = r.tower.returnAllowlist; r.tower.returnAllowlist = emptySet(); a.continueToPayment(CARD); r.tower.returnAllowlist = allowed
        val b = r.controller(FlakyStore().also { it.inner.save(r.saved()) }); b.load()
        val intent = r.tower.intents.values.single()
        r.tower.orders["o1"] = FakeOnboardingTower.Order("o1", intent.id, "k_legacy_1", 2100, "https://return.example.com/paid")
        r.tower.quotes["q1"] = FakeOnboardingTower.Quote(UUID.randomUUID().toString(), r.npub, "k_legacy_2", 2100, intent.id)
        val refused = b.continueToPayment(LN)
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_AMBIGUOUS, refused.failure!!.kind)
        assertNull(refused.draft.method); assertFalse(refused.draft.quoteSubmitted)
        assertEquals("no quote beyond the legacy row", 1, r.tower.quotes.size)
    }

    @Test fun anExplicitReleaseFreesTheMethodOnlyWhenNStackVerifiesTheEnd() = runBlocking {
        // provider-open invoice: cancelled, so the method is free and the key rotates
        val r = LnRig(); val a = r.controller().ready()
        val first = a.continueToPayment(LN)
        val freed = a.releasePayment()
        assertNull(freed.draft.method); assertNull(freed.draft.quoteKey); assertNull(freed.quote); assertNull(freed.paymentAttempt)
        assertNull(a.continueToPayment(CARD).failure)
        // a payment that landed funds the intent instead
        val paid = LnRig(); val b = paid.controller().ready(); val q = b.continueToPayment(LN).quote!!
        paid.tower.providerSays(q.id, "settled")
        val funded = b.releasePayment()
        assertTrue(funded.intent!!.fundingConfirmed); assertEquals(OnboardingPhase.ReadyToProvision, funded.phase)
        assertEquals(0, paid.tower.mutations().count { it.path.endsWith("/provision") })
        // a held payment cannot be verified: everything stays
        val held = LnRig(); val c = held.controller().ready(); val hq = c.continueToPayment(LN)
        held.tower.providerSays(hq.quote!!.id, "late_payment")
        val stays = c.releasePayment()
        assertEquals(LN, stays.draft.method); assertEquals(hq.draft.quoteKey, stays.draft.quoteKey); assertNotNull(stays.paymentAttempt); assertNotNull(stays.notice)
        assertEquals("method_locked", c.continueToPayment(CARD).failure!!.code)
        assertEquals(1, r.quotePosts().size); assertNotNull(first.quote)
    }

    @Test fun anOrdinaryEmptyHintNeverFreesASubmittedAttemptEvenWithoutAnArtefactId() = runBlocking {
        // The first request has not reached NStack when another read happens (delayed, in flight): the read says "nothing", the key stays.
        val r = LnRig(); val a = r.controller().ready()
        r.tower.failBefore("/lightning/quote")
        assertEquals(OnboardingFailure.Kind.NETWORK, a.continueToPayment(LN).failure!!.kind)
        val key = a.snapshot().draft.quoteKey!!
        val relaunched = r.controller(); val s = relaunched.load()
        assertTrue(s.intent!!.paymentAttemptKnown); assertNull(s.paymentAttempt)
        assertEquals(LN, s.draft.method); assertEquals(key, s.draft.quoteKey); assertTrue(s.draft.quoteSubmitted)
        assertEquals(LN, r.saved().method); assertEquals(key, r.saved().quoteKey)
        assertEquals("method_locked", relaunched.continueToPayment(CARD).failure!!.code)
        assertEquals("no alternative payment was started", 0, r.checkoutPosts().size)
        assertEquals(1, r.quotePosts().size)
        // the delayed request now lands: the same key answers it, once
        val s2 = relaunched.continueToPayment(LN)
        assertNotNull(s2.quote); assertEquals(key, s2.draft.quoteKey); assertEquals(1, r.tower.quotes.size)
        // the same holds for card
        val c = LnRig(); val d = c.controller().ready()
        c.tower.failBefore("/checkout")
        d.continueToPayment(CARD)
        val ckey = d.snapshot().draft.checkoutKey
        val again = c.controller(); again.load()
        assertEquals(CARD, again.snapshot().draft.method); assertEquals(ckey, again.snapshot().draft.checkoutKey)
        assertEquals("method_locked", again.continueToPayment(LN).failure!!.code); assertEquals(0, c.quotePosts().size)
    }

    @Test fun onlyAnExplicitReleaseAnsweredWithNoAttemptFreesASubmittedBindingAndRotatesItsKeys() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        r.tower.failBefore("/lightning/quote")
        a.continueToPayment(LN)
        val key = a.snapshot().draft.quoteKey
        val freed = r.controller().let { it.load(); it.releasePayment() }
        assertNull(freed.draft.method); assertNull(freed.draft.quoteKey); assertFalse(freed.draft.quoteSubmitted)
        assertNull(r.saved().method)
        val next = r.controller().also { it.load(); it.confirmReview() }
        assertNull("after the release the other method is allowed", next.continueToPayment(CARD).failure)
        assertEquals(1, r.tower.orders.size); assertTrue(key != null)
        // a binding that names a quote stays when NStack merely lists nothing
        val k = LnRig(); val c = k.controller().ready(); val q = c.continueToPayment(LN)
        k.tower.quotes.getValue(q.quote!!.id).state = "settled"                      // no longer listed, yet the intent is not funded
        val again = k.controller().also { it.load() }
        assertEquals(LN, again.snapshot().draft.method); assertEquals(q.quote!!.id, again.snapshot().draft.quoteId)
    }

    @Test fun anOlderNStackWithoutTheFieldIsUnknownNeverProofAndCannotRelease() = runBlocking {
        val r = LnRig(); r.tower.legacyNoAttemptField = true
        val a = r.controller().ready()
        r.tower.failBefore("/lightning/quote")
        a.continueToPayment(LN)
        val key = a.snapshot().draft.quoteKey
        val relaunched = r.controller(); val s = relaunched.load()
        assertEquals(LN, s.draft.method); assertEquals(key, s.draft.quoteKey); assertTrue(s.draft.quoteSubmitted)
        assertFalse(s.intent!!.paymentAttemptKnown)
        val release = relaunched.releasePayment()
        assertEquals("release_unsupported", release.failure!!.code)
        assertFalse("a missing route is not a missing onboarding backend", release.phase is OnboardingPhase.Unavailable)
        assertEquals(LN, release.draft.method)
        assertEquals("method_locked", relaunched.continueToPayment(CARD).failure!!.code)
    }

    @Test fun anAttemptThatContradictsThisDevicesBindingHoldsEverythingForSupport() = runBlocking {
        val r = LnRig(); val a = r.controller().ready()
        val first = a.continueToPayment(LN)
        val saved = r.saved()
        r.store.inner.save(saved.copy(quoteId = UUID.randomUUID().toString()))        // this device believes in a different quote
        val b = r.controller(); val s = b.load()
        assertTrue(s.supportHold)
        val posts = r.tower.mutations().size
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING, b.continueToPayment(LN).failure!!.kind)
        assertEquals("method_locked", b.continueToPayment(CARD).failure!!.code)
        assertEquals(posts, r.tower.mutations().size)
        assertEquals(1, r.tower.quotes.size); assertNotNull(first.quote)
    }

    @Test fun aHintThatCannotBeSavedStillBlocksEveryNewPayment() = runBlocking {
        val r = LnRig(); val a = r.controller().ready(); a.continueToPayment(LN)
        val flaky = FlakyStore().also { it.failWhen = { d -> d.method != null } }
        val b = r.controller(flaky)
        val s = b.load()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure!!.kind)
        assertNull(s.draft.method)
        val posts = r.tower.mutations().size
        assertEquals(OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING, b.continueToPayment(CARD).failure!!.kind)
        assertEquals(posts, r.tower.mutations().size)
        assertEquals(0, r.checkoutPosts().size)
    }

    @Test fun anIdentitySwapDuringReleaseAppliesNothing() = runBlocking {
        val r = LnRig(); val a = r.controller().ready(); a.continueToPayment(LN)
        r.net.after = { if (it.url.endsWith("/payment-attempt/release")) r.active = "npub1someoneelse" }
        val s = a.releasePayment()
        assertEquals(OnboardingPhase.Unavailable(OnboardingUnavailability.SignerMismatch), s.phase)
        assertEquals(LN, r.saved().method); assertNotNull(r.saved().quoteKey)
        r.active = r.npub; r.net.after = {}
        assertEquals("the release body is the signed empty object", "{}", r.tower.mutations().last { it.path.endsWith("/payment-attempt/release") }.body)
    }
}
