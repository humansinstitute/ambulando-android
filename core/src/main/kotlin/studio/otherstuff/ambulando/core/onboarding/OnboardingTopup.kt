package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.isUUID
import java.io.File
import java.time.Instant

enum class TopupMethod { CARD, LIGHTNING }

/**
 * One repeat top-up in flight for exactly one (origin, signer). The replay key, amount and method are fixed from the moment
 * [submitted] is durable (before any request) until NStack itself has resolved the attempt: a lost reply or a failed save can
 * therefore only ever be answered by replaying the same key, never by starting a second payable attempt.
 */
@Serializable
data class TopupDraft(
    val version: Int = 1,
    val origin: String,
    val signerNpub: String,
    val amountCents: Int = OnboardingFunding.MINIMUM_CENTS,
    val key: String,
    val returnUrl: String? = null,
    /** Set only once NStack has answered the checkout; the browser is opened after this is durable. */
    val orderId: String? = null,
    val method: TopupMethod? = null,
    /** Durable before the payment request is sent. Cleared only when NStack provably created nothing, or when the attempt is over. */
    val submitted: Boolean = false,
    /** Set only once NStack has answered the quote; the invoice is shown after this is durable. */
    val quoteId: String? = null,
) {
    /** A draft from before [method] existed that already carried a return address or order is a card attempt. */
    val attemptMethod: TopupMethod? get() = method ?: if (returnUrl != null || orderId != null) TopupMethod.CARD else null
    /** Amount, method and key may not change while this is true. */
    val inFlight: Boolean get() = submitted || orderId != null || quoteId != null || returnUrl != null
}

/** The saved draft exists but cannot be trusted. Distinct from "none saved": the caller must fail closed. */
class TopupDraftUnreadable(cause: Throwable? = null) : Exception("topup draft unreadable", cause)

interface TopupDraftStore {
    /** Null only when nothing was ever saved. Throws [TopupDraftUnreadable] for anything present that cannot be read back. */
    fun load(origin: String, signerNpub: String): TopupDraft?
    /** Throws when not durably written; nothing further may then be sent. */
    fun save(draft: TopupDraft)
    fun clear(origin: String, signerNpub: String)
}

class FileTopupDraftStore(private val directory: File) : TopupDraftStore {
    private fun file(o: String, s: String) = File(directory, Sha256Hex.of("topup-draft-v1\n$o\n$s") + ".json")
    override fun load(origin: String, signerNpub: String): TopupDraft? {
        val f = file(origin, signerNpub)
        if (!f.exists()) return null
        val draft = try { WireJson.decodeFromString(TopupDraft.serializer(), f.readBytes().toString(Charsets.UTF_8)) } catch (e: Exception) { throw TopupDraftUnreadable(e) }
        if (draft.version != 1 || draft.origin != origin || draft.signerNpub != signerNpub) throw TopupDraftUnreadable()
        return draft
    }
    override fun save(draft: TopupDraft) { AtomicFiles.write(file(draft.origin, draft.signerNpub), WireJson.encodeToString(TopupDraft.serializer(), draft).toByteArray(Charsets.UTF_8)) }
    override fun clear(origin: String, signerNpub: String) { file(origin, signerNpub).delete() }
}

class MemoryTopupDraftStore : TopupDraftStore {
    private val drafts = HashMap<String, TopupDraft>()
    @Synchronized override fun load(origin: String, signerNpub: String) = drafts["$origin\n$signerNpub"]
    @Synchronized override fun save(draft: TopupDraft) { drafts["${draft.origin}\n${draft.signerNpub}"] = draft }
    @Synchronized override fun clear(origin: String, signerNpub: String) { drafts.remove("$origin\n$signerNpub") }
}

data class TopupSnapshot(
    val draft: TopupDraft,
    val config: OnboardingConfig? = null,
    val topups: OnboardingTopups? = null,
    /** Why the person cannot add credit right now, in customer words; null when they can. */
    val unavailable: String? = null,
    val checkoutUrl: String? = null,
    /** The server's latest word on this attempt's Lightning quote; never a local paid hint. */
    val quote: TopupQuote? = null,
    val failure: OnboardingFailure? = null,
    val notice: String? = null,
    val busy: Boolean = false,
) {
    val minCents: Int get() = config?.topupMinCents ?: OnboardingFunding.MINIMUM_CENTS
    val maxCents: Int get() = config?.topupMaxCents ?: OnboardingFunding.MAXIMUM_CENTS
    /** A payment page was requested and NStack has not yet reported it settled or ended. */
    val awaiting: Boolean get() = draft.orderId != null && topups?.let { draft.orderId in it.pendingCheckoutOrderIds || !it.pendingCheckoutComplete } != false
    /** A Lightning request is open on NStack and has not ended. */
    val awaitingLightning: Boolean get() = draft.attemptMethod == TopupMethod.LIGHTNING && draft.inFlight && quote?.terminal != true
    /** Amount and method are frozen: a payment attempt exists that NStack has not yet resolved. */
    val locked: Boolean get() = draft.inFlight
    /** The saved attempt cannot be read: nothing may be started. */
    val unreadable: Boolean get() = failure?.code == "draft_unreadable"
    val cardOffered: Boolean get() = unavailable == null && (draft.attemptMethod ?: TopupMethod.CARD) == TopupMethod.CARD
    val lightningOffered: Boolean get() = config?.let { it.onboardingEnabled && it.lightningAvailable } == true && (draft.attemptMethod ?: TopupMethod.LIGHTNING) == TopupMethod.LIGHTNING
}

/**
 * Repeat account top-up (tower onboarding-v1 "Repeat account top-ups"): `GET /topups`, `POST /topups/checkout`,
 * `POST /topups/lightning/quote` and `.../quotes/{id}/refresh`. After setup is complete a person can add credit with no new
 * intent, workspace, invitation or introduction. Same safety rules as the wizard: the replay key, amount and method are durable
 * *as submitted* before the request, the order or quote is durable before the browser or invoice shows, identity is checked before
 * every signature, an unreadable saved attempt fails closed, and only NStack's own credited list counts as money (a redirect, a
 * wallet hand-off or a quote state funds nothing). Starting a top-up never starts, wakes or charges an agent.
 */
class TopupController(
    private val origin: TowerOrigin,
    private val signerNpub: String,
    signer: EventSigner,
    requireSigner: suspend (String) -> Unit,
    private val transport: HttpTransport,
    private val store: TopupDraftStore,
    private val makeKey: () -> String = OnboardingController.randomKey,
    private val allowLoopbackReturn: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    private val bound = BoundSigner(signer, signerNpub, NostrKeys.publicKeyHex(signerNpub) ?: "", requireSigner)
    private val api = OnboardingAPI(TowerClient(origin, transport, bound))
    private val require = requireSigner
    private val gate = Mutex()
    private var returnUrl: String? = null
    private val unreadable: Boolean
    private var state: TopupSnapshot
    private val flow: MutableStateFlow<TopupSnapshot>

    init {
        var bad = false
        val saved = try { store.load(origin.url, signerNpub) } catch (e: CancellationException) { throw e } catch (e: Exception) { bad = true; null }
        unreadable = bad
        state = TopupSnapshot(
            saved ?: TopupDraft(origin = origin.url, signerNpub = signerNpub, key = makeKey()),
            failure = if (bad) OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE, code = "draft_unreadable") else null,
        )
        flow = MutableStateFlow(state)
    }

    val snapshots: StateFlow<TopupSnapshot> get() = flow
    fun snapshot() = state

    private fun refused(): TopupSnapshot { flow.value = state; return state }

    suspend fun load(): TopupSnapshot = gate.withLock {
        if (unreadable) return@withLock refused()
        state = state.copy(failure = null, busy = true); flow.value = state
        try {
            require(signerNpub)
            val config = try { api.config() } catch (f: OnboardingFailure) { if (f.kind == OnboardingFailure.Kind.BACKEND_MISSING) null else throw f }
            returnUrl = config?.takeIf { it.cardAvailable && it.cardMode == "test" }?.cardReturnUrls?.firstOrNull { OnboardingController.approvedReturn(it, allowLoopbackReturn) != null }
            val why = when {
                config == null || !config.onboardingEnabled -> "Adding credit is switched off on this NStack right now. Try again later."
                returnUrl == null -> OnboardingController.CARD_UNAVAILABLE
                else -> null
            }
            state = state.copy(config = config, unavailable = why)
            var list = api.topups()
            val quoteId = state.draft.quoteId
            if (quoteId != null) {
                // NStack re-polls its provider; whatever it answers is the only state shown. A failed poll keeps the listed copy.
                state = state.copy(quote = list.pendingLightning.firstOrNull { it.id == quoteId } ?: state.quote)
                val polled = api.topupQuoteRefresh(quoteId)
                if (polled.id != quoteId) throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
                state = state.copy(quote = polled)
                if (polled.terminal) list = api.topups()
            }
            state = state.copy(topups = list)
            settle(list)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        finish()
    }

    /** The attempt is over (credited, expired, cancelled or refused) once NStack no longer lists it as waiting: the next one gets a fresh key. */
    private fun settle(list: OnboardingTopups) {
        val d = state.draft
        // A truncated waiting list proves nothing by omission: only a complete list lets an absent order count as over.
        val cardOver = d.orderId != null && d.orderId !in list.pendingCheckoutOrderIds && list.pendingCheckoutComplete
        if (d.orderId != null && d.orderId !in list.pendingCheckoutOrderIds && !list.pendingCheckoutComplete) {
            state = state.copy(notice = "NStack could not show every waiting payment, so this one's status is unknown. Your amount and payment stay as they are; check again shortly.")
        }
        val lightningOver = d.quoteId != null && state.quote?.terminal == true
        if (cardOver || lightningOver) {
            val fresh = TopupDraft(origin = d.origin, signerNpub = d.signerNpub, amountCents = d.amountCents, key = makeKey())
            try {
                store.save(fresh)
                state = state.copy(draft = fresh, checkoutUrl = null, quote = null, notice = "Your last payment is finished. NStack's credited list below is what counts.")
            } catch (e: Exception) { state = state.copy(failure = OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE)) }
        }
    }

    suspend fun setAmount(cents: Int): TopupSnapshot = gate.withLock {
        if (unreadable) return@withLock refused()
        if (cents == state.draft.amountCents) return@withLock state
        // A payment attempt keeps its amount, method and key until NStack has resolved it, even when its answer never arrived.
        if (state.draft.inFlight) { state = state.copy(failure = OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "attempt_open")); flow.value = state; return@withLock state }
        val d = state.draft.copy(amountCents = cents, key = makeKey(), returnUrl = null)
        try { store.save(d); state = state.copy(draft = d, failure = null) } catch (e: Exception) { state = state.copy(failure = OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE)) }
        flow.value = state; state
    }

    /** Asks for the test-mode payment page. Safe to repeat: the same key and amount replay the same order. */
    suspend fun startCheckout(): TopupSnapshot = gate.withLock {
        if (unreadable) return@withLock refused()
        state = state.copy(failure = null, notice = null, checkoutUrl = null, busy = true); flow.value = state
        var submitted: TopupDraft? = null
        var replay = false
        try {
            require(signerNpub)
            val cfg = state.config
            val saved = state.draft
            replay = saved.inFlight
            if (saved.attemptMethod == TopupMethod.LIGHTNING) throw OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "method_locked")
            val url = saved.returnUrl ?: returnUrl      // a replay keeps the address it was first sent with
            if (state.unavailable != null || url == null || cfg == null) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unavailable")
            val amount = saved.amountCents
            if (amount !in cfg.topupMinCents..cfg.topupMaxCents) throw OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "invalid_amount")
            val d = saved.copy(method = TopupMethod.CARD, submitted = true, returnUrl = url)
            try { store.save(d); state = state.copy(draft = d) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }   // key, amount, method, return: before the request
            submitted = d
            val checkout = api.topupCheckout(d.key, url, amount)
            val page = OnboardingController.safeCheckoutUrl(checkout.checkoutUrl)
            if (checkout.mode != "test" || checkout.amountCents != amount || page == null) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_checkout")
            val withOrder = d.copy(orderId = checkout.orderId)
            try { store.save(withOrder); state = state.copy(draft = withOrder) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }   // durable before the browser opens
            state = state.copy(checkoutUrl = page)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e); releaseIfNothingWasCreated(submitted, replay, e, CARD_REJECTED_BEFORE_ORDER) }
        finish()
    }

    /**
     * Asks NStack for a Lightning invoice, or re-reads the one already held. Safe to repeat: the same key and amount replay the
     * same quote. The invoice reaches the screen only after the quote id is durable.
     */
    suspend fun startLightning(): TopupSnapshot = gate.withLock {
        if (unreadable) return@withLock refused()
        state = state.copy(failure = null, notice = null, checkoutUrl = null, busy = true); flow.value = state
        var submitted: TopupDraft? = null
        var replay = false
        try {
            require(signerNpub)
            val cfg = state.config
            val saved = state.draft
            replay = saved.inFlight
            if (saved.attemptMethod == TopupMethod.CARD) throw OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "method_locked")
            if (cfg == null || !cfg.onboardingEnabled || !cfg.lightningAvailable) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "lightning_off")
            val amount = saved.amountCents
            if (amount !in cfg.topupMinCents..cfg.topupMaxCents) throw OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "invalid_amount")
            val d = saved.copy(method = TopupMethod.LIGHTNING, submitted = true)
            try { store.save(d); state = state.copy(draft = d) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }   // key, amount, method: before the request
            submitted = d
            val quote = api.topupQuote(d.key, amount)
            // The attempt is bound to NStack's quote only when it is for this account's top-up of exactly this amount.
            if (!isUUID(quote.id) || quote.id != quote.id.lowercase() || quote.amountCents != amount || quote.intentId != null) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_quote")
            val withQuote = d.copy(quoteId = quote.id)
            try { store.save(withQuote); state = state.copy(draft = withQuote) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }   // durable before the invoice shows
            state = state.copy(quote = quote)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e); releaseIfNothingWasCreated(submitted, replay, e, LIGHTNING_REJECTED_BEFORE_QUOTE) }
        finish()
    }

    /** A return from the payment page or wallet only prompts a re-read of NStack's lists; the return itself funds nothing. */
    suspend fun refresh(): TopupSnapshot = load()

    /**
     * Frees the attempt only when NStack answered the FIRST request with a refusal it gives before it creates anything; every other
     * failure keeps the key. A refusal of a replay ([replay]: an earlier request may already have created the order or quote) proves
     * nothing about that earlier request, so it never frees the attempt.
     */
    private fun releaseIfNothingWasCreated(submitted: TopupDraft?, replay: Boolean, e: Exception, codes: Set<String>) {
        if (replay) return
        val f = e as? OnboardingFailure ?: return
        val d = submitted ?: return
        if (state.draft != d) return   // an order or quote was already bound
        val definitive = f.kind == OnboardingFailure.Kind.BACKEND_MISSING || (f.code != null && f.code in codes)
        if (!definitive) return
        val cleared = d.copy(method = null, submitted = false, returnUrl = null)
        try { store.save(cleared); state = state.copy(draft = cleared) } catch (_: Exception) { /* stays locked: replaying the same key is always safe */ }
    }

    private fun fail(e: Exception) {
        val failure = when (e) {
            is OnboardingFailure -> e
            is IdentityError -> OnboardingFailure(OnboardingFailure.Kind.SIGNER_REFUSED)
            is TowerError -> OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            else -> OnboardingFailure(OnboardingFailure.Kind.NETWORK)
        }
        state = state.copy(failure = failure, unavailable = if (failure.kind == OnboardingFailure.Kind.ACCOUNT_NOT_READY) failure.userMessage else state.unavailable)
    }

    private fun finish(): TopupSnapshot { state = state.copy(busy = false); flow.value = state; return state }

    companion object {
        /** Refusals NStack gives before it writes any order (tower `createHostingCheckout`: shape, return address, account). */
        private val CARD_REJECTED_BEFORE_ORDER = setOf("invalid_checkout", "return_url_not_allowed", "invalid_return_url", "account_not_found", "account_not_provisioned", "onboarding_rollout_disabled")
        /** Refusals before a quote row exists (`issueTopupLightningQuote`). `lightning_unavailable` is deliberately absent: it can also follow the insert. */
        private val LIGHTNING_REJECTED_BEFORE_QUOTE = setOf("invalid_quote_request", "too_many_open_topup_quotes", "account_not_found", "account_not_provisioned", "onboarding_rollout_disabled")
    }
}
