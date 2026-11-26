package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.identitybackup.IdentityBackupGate
import studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.isUUID
import java.security.SecureRandom
import java.util.Base64

sealed interface OnboardingUnavailability {
    data class OriginRejected(val reason: String) : OnboardingUnavailability
    data object BackendMissing : OnboardingUnavailability
    data object RolloutDisabled : OnboardingUnavailability
    data object SignerRefused : OnboardingUnavailability
    data object SignerMismatch : OnboardingUnavailability
    data object IdentityLocked : OnboardingUnavailability
}

sealed interface OnboardingPhase {
    data object Checking : OnboardingPhase
    data class Unavailable(val why: OnboardingUnavailability) : OnboardingPhase
    data object Form : OnboardingPhase
    data object Review : OnboardingPhase
    data object AwaitingPayment : OnboardingPhase
    data object ReadyToProvision : OnboardingPhase
    data object Provisioning : OnboardingPhase
    data object Provisioned : OnboardingPhase
    data object Cancelled : OnboardingPhase
}

data class OnboardingPaymentOption(val method: Method, val available: Boolean, val label: String, val unavailableReason: String?) {
    enum class Method { CARD, LIGHTNING }
}

/** Only what the server's recorded steps prove: selecting an agent proves nothing. */
data class OnboardingLanding(val workspaceId: String?, val generalReady: Boolean, val agentTemplate: String?, val agentRunning: Boolean)

data class OnboardingSnapshot(
    val phase: OnboardingPhase = OnboardingPhase.Checking,
    val draft: OnboardingDraft,
    val intent: OnboardingIntent? = null,
    val account: OnboardingAccount? = null,
    val offers: List<OnboardingAgentOffer> = emptyList(),
    val failure: OnboardingFailure? = null,
    val notice: String? = null,
    /** Transient: the Stripe test-mode page to open. Never persisted; a relaunch asks again with the same operation key. */
    val checkoutUrl: String? = null,
    val paymentOptions: List<OnboardingPaymentOption> = emptyList(),
    /** NStack's latest word on this setup's Lightning quote. Transient: a relaunch asks NStack again. The invoice is only ever from here. */
    val quote: TopupQuote? = null,
    val landing: OnboardingLanding? = null,
    val busy: Boolean = false,
    /** What the deployment reported it can do now; null until read (or when the NStack predates the route). */
    val config: OnboardingConfig? = null,
    /** A key this app generated has no confirmed hosted copy yet: payment stays off until it has. */
    val backupRequired: Boolean = false,
    val identityOrigin: IdentityOrigin = IdentityOrigin.EXISTING,
) {
    val minFundingCents: Int get() = config?.minFundingCents ?: OnboardingFunding.MINIMUM_CENTS
    val maxFundingCents: Int get() = config?.maxFundingCents ?: OnboardingFunding.MAXIMUM_CENTS

    /** The person picks a first request themselves; the app never sends paid work on their behalf. */
    val agentNotRunning: Boolean get() = intent?.agentState == OnboardingAgentState.SELECTED_NOT_PROVISIONED

    /** The method this setup is bound to (card or Lightning) until NStack has resolved the attempt; null while nothing was requested. */
    val boundMethod: OnboardingPaymentOption.Method? get() = draft.method

    /** NStack's canonical outstanding payment for this intent, when it reports one (an older NStack reports nothing and nothing is inferred). */
    val paymentAttempt: OnboardingPaymentAttempt? get() = intent?.takeIf { it.status == OnboardingIntentStatus.OPEN }?.paymentAttempt

    /**
     * NStack's attempt cannot be resumed here (ambiguous, awaiting support) or disagrees with what this device holds: nothing new may be
     * started and the person is told to switch method (an explicit release) or contact support.
     */
    val supportHold: Boolean get() = paymentAttempt?.let { it.kind == OnboardingPaymentAttempt.Kind.SUPPORT || draft.conflictsWith(it, intent!!.fundingCents) } == true

    /** An explicit "switch method" is offered while an unfinished payment holds the setup to one method. */
    val canReleasePayment: Boolean get() = intent?.status == OnboardingIntentStatus.OPEN && (draft.attemptOpen || paymentAttempt != null)
}

/**
 * Durable, resumable controller for the invited-onboarding wizard of one (Tower origin, signer) pair.
 *
 * Rules it enforces: nothing signed is sent until the Tower's own descriptor confirms the origin; the draft and every server
 * read are scoped to this signer (a different unlocked identity gets SignerMismatch, never this state); money is "in" only when
 * the server reports a verified funded intent (a redirect, return URL or flag never funds); provisioning is refused locally until
 * funding is confirmed; operation keys are stable and every key/binding is durable before the request that depends on it.
 * Operations serialise on one mutex and an identical operation requested twice runs once.
 */
class OnboardingController(
    private val origin: TowerOrigin,
    private val signerNpub: String,
    signer: EventSigner,
    private val requireSigner: suspend (String) -> Unit,
    private val transport: HttpTransport,
    private val store: OnboardingDraftStore,
    /** Plain-http loopback return addresses are accepted only by tests; production allows https only. */
    private val allowLoopbackReturn: Boolean = false,
    private val makeKey: () -> String = randomKey,
    private val identityGate: IdentityBackupGate = IdentityBackupGate.EXISTING,
) {
    private val expectedHex = NostrKeys.publicKeyHex(signerNpub) ?: ""
    private val bound = BoundSigner(signer, signerNpub, expectedHex, requireSigner)
    private val api = OnboardingAPI(TowerClient(origin, transport, bound))
    /** Chosen from the authenticated config of the validated NStack: a valid member of its own approved list. Null disables card checkout. */
    private var returnUrl: String? = null
    private val gate = Mutex()
    private val inflight = HashMap<String, CompletableDeferred<OnboardingSnapshot>>()
    private var blockedPayment = false
    /** True only while the person's explicit release is being applied. */
    private var releasing = false
    /** Which method the PAYMENT_UNAVAILABLE failure belongs to, so one method's outage never switches the other off. */
    private var failureMethod: OnboardingPaymentOption.Method? = null
    private var state: OnboardingSnapshot
    private val flow: MutableStateFlow<OnboardingSnapshot>

    init {
        val loaded = store.load(origin.url, signerNpub)
        val draft = loaded?.migrated() ?: OnboardingDraft(origin = origin.url, signerNpub = signerNpub, createKey = makeKey(), checkoutKey = makeKey(), methodTracked = true)
        // A draft from before payment methods were tracked is bound conservatively in memory at once and written back when it can be.
        if (loaded != null && draft != loaded) runCatching { store.save(draft) }
        state = OnboardingSnapshot(draft = draft, paymentOptions = options(null, null, false, draft.method), backupRequired = identityGate.origin.needsBackup && !identityGate.locallyConfirmed(), identityOrigin = identityGate.origin)
        flow = MutableStateFlow(state)
    }

    val snapshots: StateFlow<OnboardingSnapshot> get() = flow
    fun snapshot(): OnboardingSnapshot = state

    companion object {
        val randomKey: () -> String = {
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            "ob_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** The server accepts only an exact allowlist member; the client also refuses anything but plain https (or loopback http). */
        fun approvedReturn(text: String, allowLoopback: Boolean = false): String? {
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            val loopback = allowLoopback && url.host in setOf("localhost", "127.0.0.1")
            return text.takeIf { url.isHttps || (url.scheme == "http" && loopback) }
        }

        const val CARD_UNAVAILABLE = "Card payments are unavailable right now. Your setup is saved; try again later or contact your inviter."

        const val OTHER_METHOD_OPEN = "A payment is already under way with the other method. Check it, or cancel this setup once it has finished, before choosing again."

        /**
         * Payment methods from what the deployment reported ([config] null = not read yet), the address chosen for card returns, and the
         * method this setup is already bound to ([bound]: the other one stays off until NStack has resolved that attempt).
         * Lightning is offered only when the deployment itself says it can issue invoices now.
         */
        fun options(config: OnboardingConfig?, returnUrl: String?, paymentBlocked: Boolean, bound: OnboardingPaymentOption.Method? = null, lightningBlocked: Boolean = false): List<OnboardingPaymentOption> {
            val card = when {
                bound == OnboardingPaymentOption.Method.LIGHTNING -> OnboardingPaymentOption(OnboardingPaymentOption.Method.CARD, false, "Card (test mode)", OTHER_METHOD_OPEN)
                returnUrl == null || config?.cardAvailable != true -> OnboardingPaymentOption(OnboardingPaymentOption.Method.CARD, false, "Card (test mode)", CARD_UNAVAILABLE)
                paymentBlocked -> OnboardingPaymentOption(OnboardingPaymentOption.Method.CARD, false, "Card (test mode)",
                    "This payment method is currently unavailable. Check the status again later.")
                else -> OnboardingPaymentOption(OnboardingPaymentOption.Method.CARD, true, "Card (Stripe test mode; no real money)", null)
            }
            val lightning = when {
                bound == OnboardingPaymentOption.Method.CARD -> OnboardingPaymentOption(OnboardingPaymentOption.Method.LIGHTNING, false, "Lightning", OTHER_METHOD_OPEN)
                config == null || !config.onboardingEnabled || !config.lightningAvailable -> OnboardingPaymentOption(OnboardingPaymentOption.Method.LIGHTNING, false, "Lightning", "Lightning is unavailable right now.")
                lightningBlocked -> OnboardingPaymentOption(OnboardingPaymentOption.Method.LIGHTNING, false, "Lightning", "This payment method is currently unavailable. Check the status again later.")
                else -> OnboardingPaymentOption(OnboardingPaymentOption.Method.LIGHTNING, true, "Lightning (bitcoin)", null)
            }
            return listOf(card, lightning)
        }

        /** Stripe-hosted https only; never a link the Tower (or a man in the middle) could use to send the person elsewhere. */
        fun safeCheckoutUrl(text: String): String? {
            val url = text.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val host = url.host.lowercase()
            return if (host == "stripe.com" || host.endsWith(".stripe.com")) url.toString() else null
        }

        fun intentDigest(i: OnboardingIntent) = OnboardingDraft.digest(i.workspaceName, i.fundingCents, i.agent?.id, i.agent?.dailyCapCents)

        fun sameAddress(a: String, b: String): Boolean {
            val x = a.toHttpUrlOrNull() ?: return false
            val y = b.toHttpUrlOrNull() ?: return false
            return x.scheme == y.scheme && x.host == y.host && x.port == y.port && x.encodedPath == y.encodedPath && x.username.isEmpty() && x.password.isEmpty()
        }
    }

    // MARK: Loading and resume

    /** Verifies the origin, reads the account and catalogue, then resumes the server's record of this signer's intent. */
    suspend fun load(): OnboardingSnapshot = coalesced("load") { doLoad() }

    private suspend fun doLoad(): OnboardingSnapshot {
        state = state.copy(phase = OnboardingPhase.Checking, failure = null)
        when (val mismatch = signerProblem()) { null -> {} else -> return unavailable(mismatch) }
        if (origin.isFips) return unavailable(OnboardingUnavailability.OriginRejected("Invited onboarding needs an HTTPS NStack address."))
        try { TowerAPI(TowerClient(origin, transport, null)).descriptor() }
        catch (e: CancellationException) { throw e }
        catch (e: TowerError) { return unavailable(OnboardingUnavailability.OriginRejected(e.message ?: "The address is not an NStack.")) }
        catch (e: Exception) { return failed(OnboardingFailure(OnboardingFailure.Kind.NETWORK)) }
        try {
            readConfig()?.let { return it }
            state = state.copy(account = api.account())
            state = state.copy(offers = try { api.agentOffers() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() })
            resumeIntent()
            pollQuote()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return failed(e) }
        return settle()
    }

    /**
     * Reads what this NStack can do now. A 404 means it predates the route: nothing is guessed, card stays off with the friendly
     * explanation. When onboarding is switched off the wizard says so instead of letting a mutation fail later.
     */
    private suspend fun readConfig(): OnboardingSnapshot? {
        val config = try { api.config() }
        catch (f: OnboardingFailure) { if (f.kind == OnboardingFailure.Kind.BACKEND_MISSING) null else throw f }
        // The NStack's own operator allowlist is the authority; this app only insists the member is a plain https address (no credentials, query or fragment).
        returnUrl = config?.takeIf { it.cardAvailable && it.cardMode == "test" }?.cardReturnUrls?.firstOrNull { approvedReturn(it, allowLoopbackReturn) != null }
        state = state.copy(config = config, paymentOptions = options(config, returnUrl, false, state.draft.method))
        if (config != null && !config.onboardingEnabled) return unavailable(OnboardingUnavailability.RolloutDisabled).also { finalize() }
        return null
    }

    private suspend fun resumeIntent() {
        val id = state.draft.intentId
        if (id != null) {
            try { adopt(api.intent(id)) }
            catch (f: OnboardingFailure) {
                if (f.kind != OnboardingFailure.Kind.NOT_FOUND) throw f
                // Not this signer's intent (or removed): never reuse it. Form choices stay; keys rotate so nothing is replayed.
                dropIntent("The earlier setup could not be found for this identity, so a new one will be created.")
            }
        } else {
            api.intents().firstOrNull { it.status in ACTIVE }?.let { adopt(it) }
        }
    }

    // MARK: Form (each is durable before it takes effect)

    suspend fun edit(
        name: String? = null, fundingCents: Int? = null, agentOfferId: String? = null, clearAgent: Boolean = false,
        agentDailyCapCents: Int? = null, invite: OnboardingInviteCode? = null,
    ): OnboardingSnapshot = gate.withLock {
        var d = state.draft
        if (name != null) d = d.copy(workspaceName = name)
        if (fundingCents != null) d = d.copy(fundingCents = fundingCents)
        if (clearAgent) d = d.copy(agentOfferId = null, agentDailyCapCents = null)
        else if (agentOfferId != null) d = d.copy(agentOfferId = agentOfferId, agentDailyCapCents = if (agentOfferId != d.agentOfferId) null else d.agentDailyCapCents)
        if (agentDailyCapCents != null) d = d.copy(agentDailyCapCents = agentDailyCapCents)
        if (invite != null && d.intentId == null) d = d.copy(invite = invite.raw)
        commit(d)
        settle()
    }

    /** Back from the review screen to editing. */
    suspend fun reopenForEditing(): OnboardingSnapshot = gate.withLock { commit(state.draft.copy(reviewedDigest = null)); settle() }

    /** Validates the choices locally; nothing is sent. The review screen is the gate in front of every side effect. */
    suspend fun confirmReview(): OnboardingSnapshot = gate.withLock {
        state = state.copy(failure = null)
        validateForm()?.let { state = state.copy(failure = it); return@withLock settle() }
        var d = state.draft
        val offerId = d.agentOfferId
        if (offerId != null && d.agentDailyCapCents == null) d = d.copy(agentDailyCapCents = state.offers.firstOrNull { it.id == offerId }?.defaultDailyCapCents)
        commit(d.copy(reviewedDigest = d.copy(agentDailyCapCents = d.agentDailyCapCents).formDigest))
        settle()
    }

    private fun validateForm(): OnboardingFailure? {
        val d = state.draft
        if (OnboardingName.normalized(d.workspaceName) == null || !OnboardingFunding.valid(d.fundingCents, state.minFundingCents, state.maxFundingCents)) return OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "invalid_form")
        if (d.intentId == null && d.invite == null) return OnboardingFailure(OnboardingFailure.Kind.INVITE_INVALID)
        val offerId = d.agentOfferId
        if (offerId != null) {
            val offer = state.offers.firstOrNull { it.id == offerId } ?: return OnboardingFailure(OnboardingFailure.Kind.AGENT_UNAVAILABLE)
            val cap = d.agentDailyCapCents ?: offer.defaultDailyCapCents
            if (cap !in 1..offer.maxDailyCapCents) return OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "invalid_agent_daily_cap")
        }
        return null
    }

    // MARK: Create / update, then pay

    /** After review: make sure the server holds this intent, then ask for the test-mode checkout. Safe to repeat after any failure or relaunch. */
    suspend fun continueToPayment(method: OnboardingPaymentOption.Method? = null): OnboardingSnapshot = coalesced("pay:${method?.name}") { doPay(method) }

    private suspend fun doPay(requested: OnboardingPaymentOption.Method?): OnboardingSnapshot {
        state = state.copy(failure = null, notice = null, checkoutUrl = null, quote = null)
        failureMethod = null
        signerProblem()?.let { return unavailable(it) }
        // One method per setup: while a payment of the other method may be payable (or its answer was lost) nothing of this one is sent.
        val bound = state.draft.method
        val method = requested ?: bound ?: OnboardingPaymentOption.Method.CARD
        if (bound != null && bound != method) return settle(OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "method_locked"))
        // NStack's own canonical attempt (possibly started on another device) is never answered with a second, independent payment.
        state.paymentAttempt?.let { hint ->
            if (hint.method == null) return settle(OnboardingFailure(OnboardingFailure.Kind.PAYMENT_ATTEMPT_AMBIGUOUS, code = "payment_attempt_ambiguous"))
            // `resume: support` closes every payment action, for both methods, until an explicit release or NStack's own resolution.
            if (state.supportHold || hint.method != method || state.draft.conflictsWith(hint, state.intent!!.fundingCents)) return settle(OnboardingFailure(OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING, code = "payment_attempt_outstanding"))
        }
        // Resuming the one payment NStack already holds creates nothing, so it needs no fresh review (a new device never reviewed this setup).
        if (!resumes(method) && (state.draft.reviewedDigest != state.draft.formDigest || validateForm() != null)) return settle(OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "review_required"))
        try {
            // A key generated by this app may not start payment until NStack itself confirms a hosted, password-encrypted copy.
            if (identityGate.origin.needsBackup && !identityGate.confirmedOnServer()) {
                state = state.copy(backupRequired = true)
                return settle(OnboardingFailure(OnboardingFailure.Kind.BACKUP_REQUIRED))
            }
            persist()                 // keys, form and bindings on disk before any account, create, update or checkout request
            ensureIntent()
            val intent = state.intent ?: return settle()
            if (intent.fundingConfirmed) return settle()
            failureMethod = method
            when (method) {
                OnboardingPaymentOption.Method.CARD -> requestCheckout(intent)
                OnboardingPaymentOption.Method.LIGHTNING -> requestQuote(intent)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return failed(e) }
        return settle()
    }

    /** NStack's own canonical attempt is of this [method] and fully described: asking again returns that same payment. */
    private fun resumes(method: OnboardingPaymentOption.Method): Boolean {
        val hint = state.paymentAttempt ?: return false
        return state.draft.intentId != null && hint.method == method && !state.supportHold
    }

    private suspend fun ensureIntent() {
        var d = state.draft
        val name = OnboardingName.normalized(d.workspaceName)!!
        val existing = state.intent
        if (existing != null && d.intentId != null) { pushOptions(existing, name); return }
        val invite = d.invite?.let(OnboardingInviteCode::of) ?: throw OnboardingFailure(OnboardingFailure.Kind.INVITE_INVALID)
        val cap = d.agentOfferId?.let { id -> d.agentDailyCapCents ?: state.offers.firstOrNull { it.id == id }?.defaultDailyCapCents }
        val digest = Sha256Hex.of("${invite.raw}\n$name\n${d.fundingCents}\n${d.agentOfferId ?: "-"}\n${cap?.toString() ?: "-"}\n${identityGate.origin.wire}")
        // A different body needs a different key; the same body keeps its key across retries and relaunches.
        if (d.createDigest != null && d.createDigest != digest) {
            // The earlier attempt may or may not have reached the server: look for it by name before creating a second one.
            api.intents().firstOrNull { it.status in ACTIVE && it.workspaceName.equals(name, ignoreCase = true) }?.let { adopt(it); return }
            d = d.copy(createKey = makeKey())
        }
        state = state.copy(draft = d.copy(createDigest = digest)); persist()
        // Account before workspace: idempotent, opens the owner account with no promotional credit.
        state = state.copy(account = api.openAccount())
        try {
            adopt(api.createIntent(OnboardingCreateRequest(state.draft.createKey, invite, name, state.draft.fundingCents, state.draft.agentOfferId, cap, identityGate.origin)))
        } catch (f: OnboardingFailure) {
            if (f.kind != OnboardingFailure.Kind.NAME_TAKEN) throw f
            // Possibly our own earlier create whose answer was lost: adopt it rather than failing.
            val mine = api.intents().firstOrNull { it.status in ACTIVE && it.workspaceName.equals(name, ignoreCase = true) } ?: throw f
            adopt(mine)
        }
    }

    private suspend fun pushOptions(intent: OnboardingIntent, name: String) {
        val d = state.draft
        var update = OnboardingOptionsUpdate(intent.revision)
        var changed = false
        if (name != intent.workspaceName) { update = update.copy(workspaceName = name); changed = true }
        if (d.fundingCents != intent.fundingCents) {
            if (intent.status != OnboardingIntentStatus.OPEN || d.attemptOpen) throw OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "funding_locked")
            update = update.copy(fundingCents = d.fundingCents); changed = true
        }
        if (d.agentOfferId != intent.agent?.id || (d.agentOfferId != null && d.agentDailyCapCents != null && d.agentDailyCapCents != intent.agent?.dailyCapCents)) {
            update = if (d.agentOfferId == null) update.copy(clearAgent = true) else update.copy(agentOfferId = d.agentOfferId, agentDailyCapCents = d.agentDailyCapCents)
            changed = true
        }
        if (!changed) return
        persist()
        try { adopt(api.updateIntent(intent.id, update)) }
        catch (f: OnboardingFailure) {
            if (f.kind != OnboardingFailure.Kind.STALE_REVISION) throw f
            // Someone else moved the revision: take the server's latest and make the person look again.
            adopt(api.intent(intent.id))
            state = state.copy(draft = state.draft.copy(reviewedDigest = null)); persist()
            throw f
        }
    }

    private suspend fun requestCheckout(intent: OnboardingIntent) {
        if (intent.status != OnboardingIntentStatus.OPEN) throw OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "intent_not_open")
        val returnUrl = returnUrl ?: throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "no_return_url")
        if (!resumes(OnboardingPaymentOption.Method.CARD) && state.draft.reviewedDigest != intentDigest(intent)) throw OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "review_required")
        // Checkout key, review binding and the card method are durable before the request that can create a payable order.
        val first = state.draft.method == null
        bind(state.draft.copy(method = OnboardingPaymentOption.Method.CARD))
        val checkout = try { api.checkout(intent.id, state.draft.checkoutKey, returnUrl) }
        catch (f: OnboardingFailure) { if (first) releaseIfRefused(f, CHECKOUT_REFUSED_BEFORE_ORDER); refusedByOutstandingAttempt(f, first, intent); throw f }
        val url = safeCheckoutUrl(checkout.checkoutUrl)
        if (checkout.mode != "test" || checkout.amountCents != intent.fundingCents || url == null) {
            throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_checkout")
        }
        // A different key may return the order NStack already holds (reused): it must be the one this setup is bound to.
        if (state.draft.checkoutOrderId != null && state.draft.checkoutOrderId != checkout.orderId) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_checkout")
        bind(state.draft.copy(checkoutOrderId = checkout.orderId))   // the binding must be durable before the browser is opened
        state = state.copy(checkoutUrl = url)
    }

    /**
     * Lightning invoice for the open intent. The method, quote key, intent and amount are durable BEFORE the request; the quote id is
     * durable before the invoice shows. A lost reply, a restart or a failed save is only ever answered by replaying the same key.
     */
    private suspend fun requestQuote(intent: OnboardingIntent) {
        if (intent.status != OnboardingIntentStatus.OPEN) throw OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "intent_not_open")
        var d = state.draft
        if (d.quoteId != null) { pollQuote(); return }       // already bound: NStack re-reads its provider, nothing new is requested
        val config = state.config
        if (config == null || !config.onboardingEnabled || !config.lightningAvailable) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "lightning_off")
        if (d.reviewedDigest != intentDigest(intent)) throw OnboardingFailure(OnboardingFailure.Kind.VALIDATION, code = "review_required")
        val first = !d.quoteSubmitted
        if (first) {
            d = d.copy(method = OnboardingPaymentOption.Method.LIGHTNING, quoteKey = d.quoteKey ?: makeKey(), quoteIntentId = intent.id, quoteAmountCents = intent.fundingCents, quoteSubmitted = true)
            bind(d)
        } else if (d.quoteIntentId != intent.id || d.quoteAmountCents != intent.fundingCents || d.quoteKey == null) {
            throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_quote")
        }
        val quote = try { api.intentQuote(intent.id, d.quoteKey!!) }
        catch (f: OnboardingFailure) { if (first) releaseIfRefused(f, QUOTE_REFUSED_BEFORE_QUOTE); refusedByOutstandingAttempt(f, first, intent); throw f }
        if (!quoteMatches(quote, d, null)) throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_quote")
        bind(d.copy(quoteId = quote.id))                     // durable before the invoice shows
        requireSigner(signerNpub)                            // the identity may have changed during the request: show nothing then
        state = state.copy(quote = quote)
    }

    /** True only for NStack's quote of exactly this intent and amount (and, once bound, this quote id). */
    private fun quoteMatches(quote: TopupQuote, d: OnboardingDraft, boundId: String?): Boolean =
        isUUID(quote.id) && quote.id == quote.id.lowercase() &&
            quote.intentId != null && quote.intentId == d.quoteIntentId && quote.intentId == d.intentId &&
            quote.amountCents == d.quoteAmountCents && (boundId == null || quote.id == boundId)

    /**
     * Re-reads the bound quote through NStack (which polls its own provider). Only NStack's intent decides funding; the quote's state
     * only decides what to show, and only a server-reported expired or cancelled quote on a still-open, unfunded intent frees the key.
     */
    private suspend fun pollQuote() {
        val d = state.draft
        val quoteId = d.quoteId ?: return
        val intentId = d.intentId ?: return
        val known = state.intent
        if (known != null && (known.status != OnboardingIntentStatus.OPEN || known.fundingConfirmed)) return
        failureMethod = OnboardingPaymentOption.Method.LIGHTNING
        val reply = api.intentQuoteRefresh(intentId, quoteId)
        requireSigner(signerNpub)                            // identity is checked again after the await, before anything is applied
        if (state.draft != d) return                          // the draft moved while waiting: this answer is stale
        if (!quoteMatches(reply.quote, d, quoteId)) { state = state.copy(quote = null); throw OnboardingFailure(OnboardingFailure.Kind.PAYMENT_UNAVAILABLE, code = "unexpected_quote") }
        var intent = reply.intent
        state = state.copy(quote = reply.quote)               // before the intent is adopted: reconciliation reads what NStack says of the quote
        if (intent != null) { if (intent.id != intentId) throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED); adopt(intent) }
        if (reply.quote.state == "expired" || reply.quote.state == "cancelled") {
            if (intent == null) { intent = api.intent(intentId); adopt(intent) }
            if (intent.status == OnboardingIntentStatus.OPEN && !intent.funded && !intent.fundingConfirmed && state.draft.quoteId == quoteId) {
                bind(state.draft.withoutPaymentAttempt(state.draft.checkoutKey))
                state = state.copy(quote = null, notice = "That Lightning invoice ended and nothing was paid. You can ask for a new invoice or choose card.")
            }
        }
    }

    /**
     * Frees the method only when NStack refused the FIRST request with a code it gives before it creates anything. Any other answer
     * (and any failure to save the release) keeps the method, key and amount: replaying the same key is always safe.
     */
    private fun releaseIfRefused(f: OnboardingFailure, codes: Set<String>) {
        if (f.kind != OnboardingFailure.Kind.BACKEND_MISSING && (f.code == null || f.code !in codes)) return
        val d = state.draft
        if (d.checkoutOrderId != null || d.quoteId != null) return
        try { bind(d.withoutPaymentAttempt(d.checkoutKey)) } catch (_: OnboardingFailure) { /* stays bound */ }
    }

    /**
     * NStack refused a request because another payment of this intent is outstanding. Nothing was created by the refused request, so a
     * binding made just for it is dropped and NStack's own attempt is adopted instead; a refusal of a replay only adopts it.
     */
    private fun refusedByOutstandingAttempt(f: OnboardingFailure, first: Boolean, intent: OnboardingIntent) {
        if (f.kind != OnboardingFailure.Kind.PAYMENT_ATTEMPT_OUTSTANDING && f.kind != OnboardingFailure.Kind.PAYMENT_ATTEMPT_AMBIGUOUS) return
        try {
            if (first) { val d = state.draft; bind(d.withoutPaymentAttempt(d.checkoutKey)) }
            f.attempt?.let { applyAttempt(it, intent) }
        } catch (_: OnboardingFailure) { /* stays as saved: the refusal itself already blocks anything new */ }
    }

    /**
     * Adopts NStack's canonical attempt into the durable draft so a new device resumes it with no local replay key. A conflicting,
     * ambiguous or support-held attempt only locks the method it names; it is never overwritten, never retried with a new key.
     */
    private fun applyAttempt(hint: OnboardingPaymentAttempt, intent: OnboardingIntent) {
        var d = state.draft
        if (d.intentId != intent.id || d.conflictsWith(hint, intent.fundingCents)) return
        when (hint.method) {
            OnboardingPaymentOption.Method.CARD -> if (hint.orderId != null && (d.method != OnboardingPaymentOption.Method.CARD || d.checkoutOrderId != hint.orderId)) {
                d = d.copy(method = OnboardingPaymentOption.Method.CARD, checkoutOrderId = hint.orderId); bind(d)
            }
            OnboardingPaymentOption.Method.LIGHTNING -> if (hint.quoteId != null && isUUID(hint.quoteId) && hint.quoteId == hint.quoteId.lowercase()) {
                if (d.method != OnboardingPaymentOption.Method.LIGHTNING || d.quoteId != hint.quoteId) {
                    d = d.copy(method = OnboardingPaymentOption.Method.LIGHTNING, quoteIntentId = intent.id, quoteAmountCents = intent.fundingCents, quoteSubmitted = true, quoteId = hint.quoteId); bind(d)
                }
                hint.quote?.takeIf { quoteMatches(it, d, hint.quoteId) }?.let { state = state.copy(quote = it) }
            }
            null -> {}
        }
    }

    /**
     * Brings the durable draft in line with the intent NStack just reported. A hint is adopted; an empty one on an ordinary read frees
     * NOTHING: a request this device already submitted may still be in flight or read stale, so even a binding with no order or quote id
     * keeps its method and key (replaying that key is always safe). Only the person's explicit release answered with an empty attempt
     * ([releasing]) or a provider-verified end of the bound quote ([pollQuote]) frees a binding. An absent field is an older NStack: unknown.
     */
    private fun reconcileAttempt(intent: OnboardingIntent) {
        if (!intent.paymentAttemptKnown || intent.status != OnboardingIntentStatus.OPEN || intent.funded) return
        val hint = intent.paymentAttempt
        if (hint != null) { applyAttempt(hint, intent); return }
        if (!releasing) return
        val d = state.draft
        if (d.intentId != intent.id || !d.attemptOpen) return
        // A quote NStack calls settled or held while the intent is still unfunded is money in limbo, not "nothing outstanding": it stays for support.
        if (state.quote?.state in setOf("settled", "late_payment", "mismatch")) return
        val hadInvoice = d.quoteId != null
        bind(d.withoutPaymentAttempt(makeKey()))
        state = state.copy(quote = null, notice = if (hadInvoice) "That Lightning invoice ended and nothing was paid. You can ask for a new invoice or choose card." else "The earlier payment attempt is over. You can choose a method again.")
    }

    /** The draft is durable first; memory changes only when the write succeeded. */
    private fun bind(draft: OnboardingDraft) {
        try { store.save(draft) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }
        state = state.copy(draft = draft)
    }

    // MARK: Payment return and refresh

    /**
     * A return to the app. The address only decides whether the app bothers to ask: anything other than the configured return
     * address is ignored, and even the real one funds nothing. Funding is read from the server.
     */
    suspend fun paymentReturned(url: String?): OnboardingSnapshot {
        val approved = state.config?.cardReturnUrls.orEmpty().filter { approvedReturn(it, allowLoopbackReturn) != null }
        if (url == null || approved.none { sameAddress(url, it) }) {
            return gate.withLock { state = state.copy(notice = "Ignored a payment return that did not match this app's approved address."); settle() }
        }
        gate.withLock { state = state.copy(notice = null) }
        return refresh()
    }

    /** Re-reads the intent from the server (foreground, return from payment, relaunch). */
    suspend fun refresh(): OnboardingSnapshot = coalesced("refresh") { doRefresh() }

    private suspend fun doRefresh(): OnboardingSnapshot {
        signerProblem()?.let { return unavailable(it) }
        val id = state.draft.intentId ?: return settle()
        try { adopt(api.intent(id)); pollQuote() } catch (e: CancellationException) { throw e } catch (e: Exception) { return failed(e) }
        return settle()
    }

    // MARK: Provision, cancel, finish

    /** Only after the server reports verified funding. Repeating it (double tap, relaunch, after a failed step) is safe. */
    suspend fun provision(): OnboardingSnapshot = coalesced("provision") { doProvision() }

    private suspend fun doProvision(): OnboardingSnapshot {
        state = state.copy(failure = null)
        signerProblem()?.let { return unavailable(it) }
        val id = state.draft.intentId ?: return settle(OnboardingFailure(OnboardingFailure.Kind.FUNDING_REQUIRED))
        // The local view may be stale after a relaunch: confirm funding from the server before asking it to build anything.
        try { adopt(api.intent(id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { return failed(e) }
        val intent = state.intent
        if (intent == null || !intent.fundingConfirmed) {
            return settle(OnboardingFailure(if (intent?.fundingReversed == true) OnboardingFailure.Kind.FUNDING_REVERSED else OnboardingFailure.Kind.FUNDING_REQUIRED))
        }
        if (intent.status == OnboardingIntentStatus.PROVISIONED) return settle()
        try { adopt(api.provision(id)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            runCatching { api.intent(id) }.getOrNull()?.let { runCatching { adopt(it) } }
            return failed(e)
        }
        return settle()
    }

    /**
     * The person's explicit "switch payment method": NStack asks its provider to settle the unfinished payment (paid funds the setup, open
     * is cancelled). Only an answer showing NStack holds no unfinished payment frees this device to choose again; nothing else does, and
     * the app never does this by itself.
     */
    suspend fun releasePayment(): OnboardingSnapshot = coalesced("release") { doRelease() }

    private suspend fun doRelease(): OnboardingSnapshot {
        state = state.copy(failure = null, notice = null, checkoutUrl = null)
        signerProblem()?.let { return unavailable(it) }
        val id = state.draft.intentId ?: return settle()
        try {
            val released = api.releasePaymentAttempt(id)
            requireSigner(signerNpub)
            releasing = true
            try { adopt(released) } finally { releasing = false }
            if (released.paymentAttemptKnown && released.paymentAttempt != null && released.status == OnboardingIntentStatus.OPEN) {
                state = state.copy(notice = "NStack could not yet confirm that payment has ended, so it stays as it is. Do not pay again; check later or contact support.")
            }
        } catch (e: CancellationException) { throw e }
        catch (f: OnboardingFailure) {
            if (f.kind == OnboardingFailure.Kind.BACKEND_MISSING) return settle(OnboardingFailure(OnboardingFailure.Kind.NOT_EDITABLE, code = "release_unsupported"))
            return failed(f)
        }
        catch (e: Exception) { return failed(e) }
        return settle()
    }

    suspend fun cancelSetup(): OnboardingSnapshot = coalesced("cancel") { doCancel() }

    private suspend fun doCancel(): OnboardingSnapshot {
        state = state.copy(failure = null)
        signerProblem()?.let { return unavailable(it) }
        val id = state.draft.intentId ?: return settle()
        try { adopt(api.cancel(id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { return failed(e) }
        return settle()
    }

    /** The person has seen the result: forget the local draft (the server keeps the record). */
    suspend fun finish(): OnboardingSnapshot = gate.withLock {
        store.clear(state.draft.origin, signerNpub)
        state = state.copy(draft = OnboardingDraft(origin = state.draft.origin, signerNpub = signerNpub, createKey = makeKey(), checkoutKey = makeKey(), methodTracked = true), intent = null, checkoutUrl = null, quote = null)
        settle()
    }

    // MARK: Internals

    private suspend fun signerProblem(): OnboardingUnavailability? = try { requireSigner(signerNpub); null }
    catch (e: IdentityError) { if (e == IdentityError.Locked) OnboardingUnavailability.IdentityLocked else OnboardingUnavailability.SignerMismatch }

    /** The same operation requested twice runs once; every caller gets that result. Different operations serialise. */
    private suspend fun coalesced(name: String, work: suspend () -> OnboardingSnapshot): OnboardingSnapshot {
        val mine = CompletableDeferred<OnboardingSnapshot>()
        val running = synchronized(inflight) { inflight[name] ?: run { inflight[name] = mine; null } }
        if (running != null) return running.await()
        try {
            val result = gate.withLock {
                state = state.copy(busy = true); flow.value = state
                try { work() } finally { state = state.copy(busy = false) }
            }
            flow.value = state
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            flow.value = state.copy(busy = false)
            throw e
        } finally { synchronized(inflight) { inflight.remove(name) } }
    }

    private fun adopt(intent: OnboardingIntent) {
        var d = state.draft
        if (intent.status == OnboardingIntentStatus.CANCELLED) {
            d = d.withoutPaymentAttempt(makeKey()).copy(intentId = null, intentRevision = null, reviewedDigest = null, createKey = makeKey(), createDigest = null)
        } else {
            d = d.copy(
                intentId = intent.id, intentRevision = intent.revision,
                invite = null,                      // the server holds the claim now; the secret is no longer needed on this device
                workspaceName = intent.workspaceName, fundingCents = intent.fundingCents,
                agentOfferId = intent.agent?.id, agentDailyCapCents = intent.agent?.dailyCapCents,
            )
            // A review survives only if the server's choices are exactly the reviewed ones.
            if (d.reviewedDigest != d.formDigest) d = d.copy(reviewedDigest = null)
        }
        state = state.copy(intent = intent, draft = d, quote = if (intent.status == OnboardingIntentStatus.CANCELLED) null else state.quote); persist()
        if (intent.status != OnboardingIntentStatus.CANCELLED) reconcileAttempt(intent)
    }

    private fun dropIntent(note: String) {
        state = state.copy(
            draft = state.draft.withoutPaymentAttempt(makeKey()).copy(intentId = null, intentRevision = null, reviewedDigest = null, createKey = makeKey(), createDigest = null),
            intent = null, quote = null, notice = note,
        )
        persist()
    }

    /** A failed write is never ignored: operation keys and server bindings must survive relaunch before anything further is sent. */
    private fun persist() {
        try { store.save(state.draft) } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE) }
    }

    /** Local edits are durable first: memory changes only if the write succeeded, so memory and disk never diverge. */
    private fun commit(draft: OnboardingDraft) {
        try { store.save(draft); state = state.copy(draft = draft) }
        catch (e: Exception) { state = state.copy(failure = OnboardingFailure(OnboardingFailure.Kind.PERSISTENCE)) }
    }

    private fun unavailable(why: OnboardingUnavailability): OnboardingSnapshot {
        state = state.copy(phase = OnboardingPhase.Unavailable(why)); flow.value = state; return state
    }

    private fun failed(error: Exception): OnboardingSnapshot {
        if (error is IdentityError) return unavailable(if (error == IdentityError.Locked) OnboardingUnavailability.IdentityLocked else OnboardingUnavailability.SignerMismatch)
        val failure = error as? OnboardingFailure ?: OnboardingFailure(if (error is TowerError) OnboardingFailure.Kind.MALFORMED else OnboardingFailure.Kind.NETWORK)
        return settle(failure)
    }

    /** Derives phase, payment options and landing from the draft and the server's intent, recording any failure. */
    private fun settle(failure: OnboardingFailure? = null): OnboardingSnapshot {
        if (failure != null) state = state.copy(failure = failure)
        var blocked = false
        when (state.failure?.kind) {
            OnboardingFailure.Kind.BACKEND_MISSING -> return unavailable(OnboardingUnavailability.BackendMissing).also { finalize() }
            OnboardingFailure.Kind.ROLLOUT_DISABLED -> return unavailable(OnboardingUnavailability.RolloutDisabled).also { finalize() }
            OnboardingFailure.Kind.SIGNER_REFUSED -> return unavailable(OnboardingUnavailability.SignerRefused).also { finalize() }
            OnboardingFailure.Kind.PAYMENT_UNAVAILABLE -> blocked = true
            else -> {}
        }
        val lightningBlocked = blocked && failureMethod == OnboardingPaymentOption.Method.LIGHTNING
        blocked = blocked && !lightningBlocked
        blockedPayment = blocked
        val d = state.draft
        val intent = state.intent
        val reviewed = d.reviewedDigest != null && d.reviewedDigest == d.formDigest
        val phase = when (intent?.status) {
            OnboardingIntentStatus.CANCELLED -> OnboardingPhase.Cancelled
            OnboardingIntentStatus.PROVISIONED -> OnboardingPhase.Provisioned
            OnboardingIntentStatus.PROVISIONING -> OnboardingPhase.Provisioning
            OnboardingIntentStatus.FUNDED -> if (intent.fundingConfirmed) OnboardingPhase.ReadyToProvision else OnboardingPhase.AwaitingPayment
            OnboardingIntentStatus.OPEN -> if (d.attemptOpen || state.checkoutUrl != null || intent.paymentAttempt != null) OnboardingPhase.AwaitingPayment else if (reviewed) OnboardingPhase.Review else OnboardingPhase.Form
            null -> if (reviewed) OnboardingPhase.Review else OnboardingPhase.Form
        }
        state = state.copy(phase = phase, paymentOptions = options(state.config, returnUrl, blocked, d.method, lightningBlocked), backupRequired = identityGate.origin.needsBackup && !identityGate.locallyConfirmed(), identityOrigin = identityGate.origin)
        return finalize()
    }

    private fun finalize(): OnboardingSnapshot {
        val i = state.intent
        state = state.copy(landing = if (i != null && i.status == OnboardingIntentStatus.PROVISIONED) OnboardingLanding(i.workspaceId, i.hasGeneral, i.agent?.template, i.hasAgentDm) else null)
        flow.value = state
        return state
    }
}

private val ACTIVE = setOf(OnboardingIntentStatus.OPEN, OnboardingIntentStatus.FUNDED, OnboardingIntentStatus.PROVISIONING)

/**
 * Refusals NStack gives to the FIRST request before it writes any order or quote (tower `intentForCheckout` and `createHostingCheckout`'s
 * shape and return-address checks). Never applied to a replay, and `stripe_unconfigured` is absent: it follows the order insert.
 */
private val CHECKOUT_REFUSED_BEFORE_ORDER = setOf("invalid_checkout", "return_url_not_allowed", "invalid_return_url", "intent_not_found", "intent_not_open", "identity_backup_required", "onboarding_rollout_disabled")

/**
 * Refusals NStack gives to the FIRST quote request before a quote row exists (`issueLightningQuote`: rollout, shape, intent, open, backup).
 * `lightning_unavailable` is deliberately absent: it also follows the insert (provider failure while issuing), so it never proves nothing exists.
 */
private val QUOTE_REFUSED_BEFORE_QUOTE = setOf("invalid_quote_request", "intent_not_found", "intent_not_open", "identity_backup_required", "onboarding_rollout_disabled")

/**
 * Every request is bound to one npub, checked when signing starts (the vault must be unlocked as exactly that identity) and again
 * on the event that comes back, so an identity change during any await can never sign this draft's body as someone else.
 */
internal class BoundSigner(
    private val inner: EventSigner, private val npub: String, private val expectedHex: String, private val require: suspend (String) -> Unit,
) : EventSigner {
    override suspend fun sign(template: NostrEventTemplate): NostrEvent {
        if (expectedHex.isEmpty()) throw IdentityError.SignerMismatch
        require(npub)
        val event = inner.sign(template)
        if (event.pubkey != expectedHex) throw IdentityError.SignerMismatch
        return event
    }
}
