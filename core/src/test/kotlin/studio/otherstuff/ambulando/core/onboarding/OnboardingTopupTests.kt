package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File
import java.io.IOException
import java.time.Instant
import studio.otherstuff.ambulando.core.transport.TowerOrigin as Origin

private const val INVITE = "inv_AbCdEfGh12345678"

private class FlakyTopupStore(val inner: TopupDraftStore = MemoryTopupDraftStore()) : TopupDraftStore {
    var failWhen: (TopupDraft) -> Boolean = { false }
    override fun load(origin: String, signerNpub: String) = inner.load(origin, signerNpub)
    override fun save(draft: TopupDraft) { if (failWhen(draft)) throw IOException("disk full"); inner.save(draft) }
    override fun clear(origin: String, signerNpub: String) = inner.clear(origin, signerNpub)
}

private class TopupRig(val tower: FakeOnboardingTower = FakeOnboardingTower(), val store: FlakyTopupStore = FlakyTopupStore(), provisioned: Boolean = true) {
    val secret = ByteArray(32) { 0x29 }
    val npub = NostrKeys.npub(NostrKeys.publicKeyHex(secret))!!
    var active = npub
    var n = 0
    init {
        tower.newInvite(INVITE)
        if (provisioned) provision()
    }
    private fun provision() = runBlocking {
        val c = OnboardingController(TowerOrigin(tower.origin), npub, { NostrKeys.sign(it, secret) }, {}, tower, MemoryOnboardingDraftStore())
        c.load(); c.edit(name = "Studio", fundingCents = 2100, invite = OnboardingInviteCode.of(INVITE)); c.confirmReview(); c.continueToPayment()
        tower.pay(c.snapshot().intent!!.id); c.provision()
        assertEquals(OnboardingIntentStatus.PROVISIONED, c.snapshot().intent!!.status)
        tower.log.clear()
    }
    fun controller(s: TopupDraftStore = store) = TopupController(TowerOrigin(tower.origin), npub, { NostrKeys.sign(it, secret) }, { if (active != it) throw IdentityError.SignerMismatch }, tower, s, makeKey = { "tp_key_%04d".format(++n) }, now = { Instant.parse("2026-11-25T00:00:00Z") })
}

class OnboardingTopupTests {
    @Test fun repeatTopUpAsksForACheckoutWithNoIntentWorkspaceOrSeat() = runBlocking {
        val r = TopupRig(); val c = r.controller()
        c.load(); c.setAmount(5000)
        val s = c.startCheckout()
        assertNull(s.failure); assertNotNull(s.checkoutUrl)
        val checkout = r.tower.mutations().single()
        assertEquals("/api/v4/onboarding/topups/checkout", checkout.path)
        assertTrue(checkout.body.contains("\"amount_cents\":5000"))
        assertEquals(NostrKeys.publicKeyHex(r.secret), checkout.signer)
        assertEquals("no new setup state", 1, r.tower.intents.size)
        assertEquals(0, r.tower.log.count { it.path.endsWith("/intents") && it.method == "POST" })
        assertEquals(s.draft.orderId, r.tower.topupOrders.keys.single())
    }

    @Test fun aRedirectNeverCreditsAndOnlyNStacksCreditedListCounts() = runBlocking {
        val r = TopupRig(); val c = r.controller()
        c.load(); c.setAmount(2100); c.startCheckout()
        val after = c.refresh()                                 // came back from the page, nothing paid yet
        assertTrue(after.awaiting); assertTrue(after.topups!!.credited.isEmpty())
        r.tower.creditTopup(after.draft.orderId!!)
        val done = c.refresh()
        assertEquals(1, done.topups!!.credited.size); assertEquals(2100, done.topups!!.credited.single().amountCents)
        assertNull("finished order gives the next top-up a fresh key", done.draft.orderId)
        assertEquals("tp_key_0002", done.draft.key)
    }

    @Test fun relaunchAndLostAnswersReplayTheSameKeyAndOrder() = runBlocking {
        val r = TopupRig(); val c = r.controller()
        c.load(); c.setAmount(2500)
        r.tower.loseAnswer("/topups/checkout")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.startCheckout().failure!!.kind)
        assertNull(c.snapshot().draft.orderId)
        val relaunched = r.controller()                          // new process: key and amount came from disk
        assertEquals(2500, relaunched.snapshot().draft.amountCents)
        relaunched.load()
        val s = relaunched.startCheckout()
        assertNotNull(s.checkoutUrl)
        assertEquals("one order despite the lost answer", 1, r.tower.topupOrders.size)
        assertEquals(2, r.tower.mutations().count { it.path.endsWith("/topups/checkout") })
    }

    @Test fun failedSavesStopBeforeTheRequestAndBeforeTheBrowser() = runBlocking {
        val r = TopupRig(); val c = r.controller()
        c.load()
        r.store.failWhen = { true }
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.startCheckout().failure!!.kind)
        assertEquals(0, r.tower.mutations().size)
        r.store.failWhen = { it.orderId != null }                // the order binding cannot be saved: no page may open
        val s = c.startCheckout()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, s.failure!!.kind)
        assertNull(s.checkoutUrl); assertNull(s.draft.orderId)
        r.store.failWhen = { false }
        assertNotNull(c.startCheckout().checkoutUrl)
        assertEquals("the same order is reused after the failed binding", 1, r.tower.topupOrders.size)
    }

    @Test fun anAccountThatIsNotSetUpYetIsToldSoAndNothingIsRequested() = runBlocking {
        val r = TopupRig(provisioned = false); val c = r.controller()
        r.tower.accounts.add(r.npub)
        val s = c.load()
        assertEquals(OnboardingFailure.Kind.ACCOUNT_NOT_READY, s.failure!!.kind)
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, c.startCheckout().failure!!.kind)
        assertEquals(0, r.tower.mutations().size)
    }

    @Test fun cardOffOrSwitchedOffExplainsInCustomerWordsAndSendsNothing() = runBlocking {
        val off = TopupRig().also { it.tower.cardConfigured = false }
        val c = off.controller(); c.load()
        assertEquals(OnboardingController.CARD_UNAVAILABLE, c.snapshot().unavailable)
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, c.startCheckout().failure!!.kind)
        assertEquals(0, off.tower.mutations().size)
        val disabled = TopupRig(); val d = disabled.controller(); disabled.tower.rolloutEnabled = false; d.load()
        assertTrue(d.snapshot().unavailable!!.contains("switched off"))
    }

    @Test fun anIdentitySwapSendsNothingAndAmountsOutsideNStacksBoundsAreRefused() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load()
        c.setAmount(100)
        assertEquals(OnboardingFailure.Kind.VALIDATION, c.startCheckout().failure!!.kind)
        c.setAmount(2100)
        r.active = "npub1someoneelse"
        assertEquals(OnboardingFailure.Kind.SIGNER_REFUSED, c.startCheckout().failure!!.kind)
        assertEquals(0, r.tower.mutations().size)
    }

    @Test fun aTruncatedWaitingListThatOmitsTheOrderIsNotProofItIsOver() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2500); c.startCheckout()
        val d = c.snapshot().draft
        r.tower.truncatedOut.add(d.orderId!!)                    // order exists and is waiting, but fell off the 20-row page
        val s = c.refresh()
        assertEquals(d, s.draft); assertTrue(s.awaiting); assertTrue(s.locked)
        assertNotNull(s.notice); assertTrue(s.notice!!.contains("unknown"))
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(9900).failure!!.kind)
        r.tower.log.clear()
        val again = c.startCheckout()                            // same key, amount and order: never a fresh payable attempt
        assertEquals(d.key, again.draft.key); assertEquals(d.orderId, again.draft.orderId)
        assertEquals(1, r.tower.topupOrders.size)
        assertEquals(d, r.controller().snapshot().draft)
    }

    @Test fun anOlderNStackWithoutTheCompleteFlagIsTreatedAsIncomplete() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2100); c.startCheckout()
        r.tower.omitPendingCompleteFlag = true
        r.tower.endTopup(c.snapshot().draft.orderId!!)           // really over, but this server cannot say the list is whole
        val s = c.refresh()
        assertFalse(s.topups!!.pendingCheckoutComplete)
        assertNotNull(s.draft.orderId); assertTrue(s.awaiting); assertNotNull(s.notice)
    }

    @Test fun aCompletePageThatOmitsTheOrderClosesItAndStartsAFreshKey() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2100); c.startCheckout()
        val old = c.snapshot().draft
        r.tower.endTopup(old.orderId!!)
        val s = c.refresh()
        assertTrue(s.topups!!.pendingCheckoutComplete)
        assertNull(s.draft.orderId); assertFalse(s.draft.inFlight); assertFalse(s.awaiting)
        assertTrue(s.draft.key != old.key)
    }

    @Test fun anOpenOrderKeepsItsAmountUntilItIsOver() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2100); c.startCheckout()
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(9900).failure!!.kind)
        assertEquals(2100, c.snapshot().draft.amountCents)
        r.tower.endTopup(c.snapshot().draft.orderId!!)           // expired without payment
        c.refresh()
        assertNull(c.snapshot().draft.orderId)
        assertEquals(9900, c.setAmount(9900).draft.amountCents)
        assertFalse(c.snapshot().awaiting)
    }

    // ---- the attempt guard: no second payable attempt while NStack has not resolved the first ----

    @Test fun aLostCheckoutAnswerFreezesAmountMethodAndKeyUntilTheSameKeyReplays() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2500)
        r.tower.loseAnswer("/topups/checkout")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.startCheckout().failure!!.kind)
        val frozen = c.snapshot().draft
        assertTrue("submitted is durable before the answer", frozen.submitted && frozen.orderId == null)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(9900).failure!!.kind)
        assertEquals(frozen, c.snapshot().draft)
        r.tower.lightningConfigured = true; c.load()
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.startLightning().failure!!.kind)
        assertEquals(0, r.tower.log.count { it.path.contains("lightning") && it.method == "POST" })
        val s = c.startCheckout()
        assertNotNull(s.checkoutUrl); assertEquals(frozen.key, s.draft.key)
        assertEquals(1, r.tower.topupOrders.size)
        assertEquals(setOf(frozen.key), r.tower.mutations().filter { it.path.endsWith("/topups/checkout") }.map { Regex("\"idempotency_key\":\"([^\"]+)\"").find(it.body)!!.groupValues[1] }.toSet())
    }

    @Test fun aFailedOrderSaveNeverOpensAFreshAttemptEvenAfterEditAndRelaunch() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2500)
        r.store.failWhen = { it.orderId != null }
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.startCheckout().failure!!.kind)
        assertEquals(1, r.tower.topupOrders.size)
        val key = c.snapshot().draft.key
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(5000).failure!!.kind)
        val relaunched = r.controller()
        assertTrue(relaunched.snapshot().draft.submitted); assertEquals(key, relaunched.snapshot().draft.key)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, relaunched.setAmount(5000).failure!!.kind)
        r.store.failWhen = { false }; relaunched.load()
        val s = relaunched.startCheckout()
        assertNotNull(s.checkoutUrl); assertEquals(key, s.draft.key); assertEquals(1, r.tower.topupOrders.size)
    }

    @Test fun anAttemptNStackNeverSawIsFreedButOnlyOnADefiniteRefusal() = runBlocking {
        val r = TopupRig(); r.tower.configReturnUrls = listOf("https://not-allowlisted.example.com/x")
        val c = r.controller(); c.load()
        val s = c.startCheckout()                                    // 400 return_url_not_allowed: provably no order
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, s.failure!!.kind)
        assertFalse(s.draft.submitted); assertTrue(r.tower.topupOrders.isEmpty())
        assertEquals(5000, c.setAmount(5000).draft.amountCents)
        val ambiguous = TopupRig(); val d = ambiguous.controller(); d.load()
        ambiguous.tower.failBefore("/topups/checkout")
        assertEquals(OnboardingFailure.Kind.NETWORK, d.startCheckout().failure!!.kind)
        assertTrue("a network error says nothing about what NStack did", d.snapshot().draft.submitted)
    }

    @Test fun aRefusedReplayOfALostCheckoutNeverFreesTheAttemptOrAllowsANewKey() = runBlocking {
        val r = TopupRig(); val c = r.controller(); c.load(); c.setAmount(2500)
        r.tower.loseAnswer("/topups/checkout")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.startCheckout().failure!!.kind)      // the order WAS created; only the answer was lost
        val frozen = c.snapshot().draft
        assertEquals(1, r.tower.topupOrders.size)
        val allowed = r.tower.returnAllowlist
        r.tower.returnAllowlist = emptySet()                                                // the operator drops the address: the replay is refused before any order lookup
        val refused = c.startCheckout()
        assertEquals("return_url_not_allowed", refused.failure!!.code)
        assertEquals("the first request's marker survives the refused replay", frozen, refused.draft)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(5000).failure!!.kind)
        r.tower.lightningConfigured = true; c.load()
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.startLightning().failure!!.kind)
        val relaunched = r.controller()
        assertEquals(frozen, relaunched.snapshot().draft)
        r.tower.returnAllowlist = allowed; relaunched.load()
        val s = relaunched.startCheckout()
        assertNotNull(s.checkoutUrl); assertEquals(frozen.key, s.draft.key); assertEquals(1, r.tower.topupOrders.size)
    }

    @Test fun aRefusedReplayOfALostQuoteNeverFreesTheAttemptAndAGenericUnavailableNeverDoes() = runBlocking {
        val r = TopupRig().also { it.tower.lightningConfigured = true }; val c = r.controller(); c.load(); c.setAmount(2500)
        r.tower.loseAnswer("/topups/lightning/quote")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.startLightning().failure!!.kind)
        val frozen = c.snapshot().draft
        r.tower.rolloutEnabled = false                                                      // a code that is "before creation" on a first request
        assertEquals(OnboardingFailure.Kind.ROLLOUT_DISABLED, c.startLightning().failure!!.kind)
        assertEquals(frozen, c.snapshot().draft)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(9900).failure!!.kind)
        r.tower.rolloutEnabled = true
        val s = c.startLightning()
        assertNotNull(s.quote); assertEquals(frozen.key, s.draft.key); assertEquals(1, r.tower.quotes.size)
        // a first request answered with the generic unavailable code keeps the attempt: that code can follow the quote insert
        val u = TopupRig().also { it.tower.lightningConfigured = true }; val d = u.controller(); d.load()
        u.tower.lightningConfigured = false
        assertEquals("lightning_unavailable", d.startLightning().failure!!.code)
        assertTrue(d.snapshot().draft.submitted); assertEquals(TopupMethod.LIGHTNING, d.snapshot().draft.method)
    }

    @Test fun anUnreadableSavedAttemptFailsClosedAndIsNeverOverwritten() = runBlocking {
        val dir = File.createTempFile("topup", "d").also { it.delete(); it.mkdirs() }
        try {
            val r = TopupRig(); val fileStore = FileTopupDraftStore(dir)
            val c = r.controller(fileStore); c.load(); c.setAmount(2500)
            r.tower.loseAnswer("/topups/checkout"); c.startCheckout()
            val saved = dir.listFiles()!!.single()
            saved.writeText("{ this is not a draft")                  // torn or corrupt
            val before = saved.readBytes(); r.tower.log.clear()
            val relaunched = r.controller(fileStore)
            assertEquals("draft_unreadable", relaunched.snapshot().failure!!.code)
            assertTrue(relaunched.snapshot().unreadable)
            for (op in listOf<suspend () -> TopupSnapshot>({ relaunched.load() }, { relaunched.setAmount(5000) }, { relaunched.startCheckout() }, { relaunched.startLightning() }, { relaunched.refresh() }))
                assertEquals("draft_unreadable", op().failure?.code)
            assertTrue("no request of any kind", r.tower.log.isEmpty())
            assertTrue("the unreadable file is left exactly as found", before.contentEquals(saved.readBytes()))
            assertEquals(1, dir.listFiles()!!.size)
            // a draft that parses but belongs to somebody else is just as unusable
            saved.writeText(WireJsonForTest.encode(TopupDraft(origin = "https://other.example.com", signerNpub = r.npub, key = "k_other_01")))
            assertTrue(r.controller(fileStore).snapshot().unreadable)
            // absent is not unreadable
            saved.delete()
            assertFalse(r.controller(fileStore).snapshot().unreadable)
        } finally { dir.deleteRecursively() }
    }

    // ---- Lightning ----

    private fun lightningRig() = TopupRig().also { it.tower.lightningConfigured = true }

    @Test fun lightningAsksForOneSignedQuoteAndShowsTheInvoiceOnlyAfterTheQuoteIsDurable() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load(); c.setAmount(5000)
        r.store.failWhen = { it.quoteId != null }                    // the quote binding cannot be saved: no invoice may show
        val failed = c.startLightning()
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, failed.failure!!.kind)
        assertNull(failed.quote)
        assertEquals(1, r.tower.quotes.size)
        assertTrue(c.snapshot().draft.submitted && c.snapshot().draft.method == TopupMethod.LIGHTNING)
        r.store.failWhen = { false }
        val s = c.startLightning()
        assertNull(s.failure)
        assertEquals("replay of the same key returns the same quote", 1, r.tower.quotes.size)
        val q = s.quote!!
        assertEquals(q.id, s.draft.quoteId); assertEquals(TopupQuote.Phase.PAYABLE, q.phase(Instant.parse("2026-11-25T00:00:00Z")))
        assertEquals(5000, q.amountCents); assertNotNull(q.invoiceSafe)
        val post = r.tower.mutations().first { it.path.endsWith("/topups/lightning/quote") }
        assertEquals(NostrKeys.publicKeyHex(r.secret), post.signer)
        assertTrue(post.body.contains("\"amount_cents\":5000") && post.body.contains("\"idempotency_key\":\"${s.draft.key}\""))
        assertEquals(0, r.tower.mutations().count { it.path.endsWith("/topups/checkout") })
        assertEquals("no workspace, intent or agent", 1, r.tower.intents.size)
    }

    @Test fun nothingIsSentWhenTheAttemptCannotBeSavedFirst() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load()
        r.store.failWhen = { true }
        assertEquals(OnboardingFailure.Kind.PERSISTENCE, c.startLightning().failure!!.kind)
        assertEquals(0, r.tower.mutations().size)
        assertFalse(c.snapshot().draft.submitted)
    }

    @Test fun aLostQuoteAnswerReplaysTheSameKeyAndFreezesTheMethodUntilThen() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load(); c.setAmount(2500)
        r.tower.loseAnswer("/topups/lightning/quote")
        assertEquals(OnboardingFailure.Kind.NETWORK, c.startLightning().failure!!.kind)
        val key = c.snapshot().draft.key
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(9900).failure!!.kind)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.startCheckout().failure!!.kind)
        assertEquals(0, r.tower.mutations().count { it.path.endsWith("/topups/checkout") })
        val relaunched = r.controller(); relaunched.load()
        assertEquals(key, relaunched.snapshot().draft.key)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, relaunched.startCheckout().failure!!.kind)
        val s = relaunched.startLightning()
        assertNotNull(s.quote); assertEquals(key, s.draft.key); assertEquals(1, r.tower.quotes.size)
    }

    @Test fun relaunchRestoresTheInvoiceFromNStackAndPaymentCreditsOnlyThroughTheServerList() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load(); c.setAmount(2100); c.startLightning()
        val id = c.snapshot().draft.quoteId!!
        val back = r.controller(); val s = back.load()
        assertEquals(id, s.quote!!.id); assertTrue(s.awaitingLightning); assertTrue(s.locked)
        assertTrue("a refresh asked NStack to re-poll", r.tower.refreshBody == "{}")
        assertTrue(s.topups!!.credited.isEmpty())
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, back.setAmount(5000).failure!!.kind)
        r.tower.payQuote(id)
        val done = back.refresh()
        assertEquals("lightning", done.topups!!.credited.single().method)
        assertNull(done.quote); assertFalse(done.locked)
        assertEquals("tp_key_0002", done.draft.key)
        assertEquals(5000, back.setAmount(5000).draft.amountCents)
    }

    @Test fun anExpiredOrCancelledQuoteOnlyFreesTheAttemptOnceNStackSaysSo() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load(); c.startLightning()
        val q = r.tower.quotes.values.single()
        q.expiresAt = "2026-11-24T00:00:00.000Z"                    // past by the device clock, still open on the server
        val still = c.refresh()
        assertEquals(TopupQuote.Phase.EXPIRED, still.quote!!.phase(Instant.parse("2026-11-25T00:00:00Z")))
        assertTrue("the device clock never releases the attempt", still.locked)
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.setAmount(5000).failure!!.kind)
        r.tower.setQuoteState(q.id, "expired")
        val over = c.refresh()
        assertFalse(over.locked); assertNull(over.quote); assertTrue(over.topups!!.credited.isEmpty())
    }

    @Test fun aLatePaymentOrMismatchStaysLockedAsASupportCase() = runBlocking {
        val r = lightningRig(); val c = r.controller(); c.load(); c.startLightning()
        val q = r.tower.quotes.values.single()
        for (state in listOf("late_payment", "mismatch")) {
            r.tower.setQuoteState(q.id, state)
            val s = c.refresh()
            assertTrue(state, s.locked); assertEquals(TopupQuote.Phase.SUPPORT, s.quote!!.phase(Instant.parse("2026-11-25T00:00:00Z")))
            assertTrue(s.topups!!.credited.isEmpty())
        }
        assertEquals(OnboardingFailure.Kind.NOT_EDITABLE, c.startCheckout().failure!!.kind)
    }

    @Test fun aQuoteForTheWrongAmountIsNeverShownAndTheAttemptStaysLocked() = runBlocking {
        val r = lightningRig(); r.tower.topupQuoteAmountDelta = 1
        val c = r.controller(); c.load(); c.setAmount(2500)
        val s = c.startLightning()
        assertEquals("unexpected_quote", s.failure!!.code)
        assertNull(s.quote); assertNull(s.draft.quoteId); assertTrue(s.locked)
    }

    @Test fun lightningOffOrASwappedIdentityOrAFullQuoteListSendNothingUnsafe() = runBlocking {
        val off = TopupRig(); val c = off.controller(); c.load()
        assertFalse(c.snapshot().lightningOffered)
        assertEquals(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, c.startLightning().failure!!.kind)
        assertEquals(0, off.tower.mutations().size)
        val r = lightningRig(); val d = r.controller(); d.load(); r.active = "npub1someoneelse"
        assertEquals(OnboardingFailure.Kind.SIGNER_REFUSED, d.startLightning().failure!!.kind)
        assertEquals(0, r.tower.mutations().size)
        r.active = r.npub
        for (i in 1..5) r.tower.quotes["q$i"] = FakeOnboardingTower.Quote("q$i", r.npub, "other_key_$i", 2100)
        val full = r.controller(); full.load()
        val s = full.startLightning()
        assertEquals(OnboardingFailure.Kind.TOO_MANY_QUOTES, s.failure!!.kind)
        assertFalse("a refusal before any quote exists frees the key", s.draft.submitted)
    }

    @Test fun anInvoiceThatIsNotPlainBech32IsNeverOfferedToTheCopyOrWalletButtons() {
        val base = TopupQuote("id", "open", 2100, 1000, "lnbc1abcdefghijklmnopqrstuvwxyz", null, Instant.parse("2026-11-25T01:00:00Z"))
        val now = Instant.parse("2026-11-25T00:00:00Z")
        assertEquals(TopupQuote.Phase.PAYABLE, base.phase(now))
        for (bad in listOf("lnbc1abc def", "lnbc1abc?amount=5&x=y", "lightning:lnbc1abcdefghijklmnopqrst", "javascript:alert(1)abcdefghijk", "lnbc1abc\n", "ln")) {
            assertNull(bad, base.copy(invoice = bad).invoiceSafe); assertEquals(TopupQuote.Phase.SUPPORT, base.copy(invoice = bad).phase(now))
        }
        assertEquals(TopupQuote.Phase.SUPPORT, base.copy(expiresAt = null).phase(now))
        assertEquals(TopupQuote.Phase.SUPPORT, base.copy(amountSats = 0).phase(now))
        assertEquals(TopupQuote.Phase.EXPIRED, base.phase(Instant.parse("2026-11-25T01:00:00Z")))
        assertEquals(TopupQuote.Phase.ENDED, base.copy(state = "settled").phase(now))
    }
}

private object WireJsonForTest { fun encode(d: TopupDraft) = studio.otherstuff.ambulando.core.nostr.WireJson.encodeToString(TopupDraft.serializer(), d) }
