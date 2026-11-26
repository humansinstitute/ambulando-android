package studio.otherstuff.ambulando.core.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string

/** A classified onboarding failure. Messages never include request bodies, invite codes or response text. */
class OnboardingFailure(val kind: Kind, val status: Int? = null, val code: String? = null, val attempt: OnboardingPaymentAttempt? = null) : Exception(kind.name, null, false, false) {
    enum class Kind {
        BACKEND_MISSING, ROLLOUT_DISABLED, PAYMENT_UNAVAILABLE, SIGNER_REFUSED,
        INVITE_FULL, INVITE_INVALID, NAME_TAKEN, AGENT_UNAVAILABLE, TOO_MANY_INTENTS,
        STALE_REVISION, IDEMPOTENCY_CONFLICT, NOT_FOUND, TOO_MANY_QUOTES, PAYMENT_ATTEMPT_OUTSTANDING, PAYMENT_ATTEMPT_AMBIGUOUS,
        BACKUP_REQUIRED, ACCOUNT_NOT_READY, FUNDING_REQUIRED, FUNDING_REVERSED, CHECKOUT_OUTSTANDING, NOT_EDITABLE,
        VALIDATION, PROVISIONING_FAILED, SERVER, NETWORK, MALFORMED, PERSISTENCE,
    }

    /** Leaves the durable draft as it was and is safe to repeat with the same operation key. */
    val retryable: Boolean get() = kind in setOf(Kind.NETWORK, Kind.SERVER, Kind.PROVISIONING_FAILED)

    override fun equals(other: Any?) = other is OnboardingFailure && other.kind == kind && other.status == status && other.code == code
    override fun hashCode() = kind.hashCode() * 31 + (code?.hashCode() ?: 0)

    val userMessage: String
        get() = when (kind) {
            Kind.BACKEND_MISSING -> "This NStack does not offer invited onboarding."
            Kind.ROLLOUT_DISABLED -> "Invited onboarding is currently switched off on this NStack. Your progress is kept; check again later."
            Kind.PAYMENT_UNAVAILABLE -> "This payment method is currently unavailable. Your progress is kept; check the status again later before paying again."
            Kind.SIGNER_REFUSED -> "This NStack did not accept the signed request. Check the unlocked identity."
            Kind.INVITE_FULL -> "This invitation has no places left."
            Kind.INVITE_INVALID -> "This invitation is not valid or has been withdrawn."
            Kind.NAME_TAKEN -> "You already have a workspace or pending setup with that name."
            Kind.AGENT_UNAVAILABLE -> "That agent is no longer on offer. Choose again."
            Kind.TOO_MANY_INTENTS -> "You have too many unfinished setups. Cancel one first."
            Kind.STALE_REVISION -> "This setup changed elsewhere. The latest version has been loaded; review it and choose again."
            Kind.IDEMPOTENCY_CONFLICT -> "That request was already used with different details."
            Kind.NOT_FOUND -> "This setup was not found for the unlocked identity."
            Kind.TOO_MANY_QUOTES -> "There are already several unpaid Lightning invoices on this account. Let them finish or expire, then try again."
            Kind.PAYMENT_ATTEMPT_OUTSTANDING -> "A payment for this setup is already under way, possibly started on another device. Resume it, or switch method once NStack has finished with it. Nothing new was started."
            Kind.PAYMENT_ATTEMPT_AMBIGUOUS -> "NStack found more than one unfinished payment for this setup. Do not pay again; switch method to let NStack settle them, or contact your inviter or support."
            Kind.ACCOUNT_NOT_READY -> "Adding credit is available once your hosted workspace has been set up."
            Kind.BACKUP_REQUIRED -> "Back up your new identity to NStack first. Payment stays off until that backup is saved and checked."
            Kind.FUNDING_REQUIRED -> "Payment has not been confirmed by the server yet."
            Kind.FUNDING_REVERSED -> "The payment was refunded or reversed, so setup cannot continue."
            Kind.CHECKOUT_OUTSTANDING -> "A payment is still open. It has to finish or expire first."
            Kind.NOT_EDITABLE -> when (code) {
                "method_locked" -> "A payment is already under way with the other method. Check it, or switch method once NStack has finished with it."
                "release_unsupported" -> "This NStack cannot switch payment methods yet. Let the open payment finish or expire, or cancel this setup."
                else -> "This setup can no longer be changed."
            }
            Kind.VALIDATION -> "NStack rejected these details" + (code?.let { " (${TowerClient.summary(it)})" } ?: "") + "."
            Kind.PROVISIONING_FAILED -> "Workspace setup stopped part-way. Your payment and finished steps are kept; try again."
            Kind.SERVER -> "NStack had a problem (HTTP ${status ?: 0}). Try again shortly."
            Kind.NETWORK -> "Could not reach NStack. Your progress is saved; try again."
            Kind.MALFORMED -> "NStack sent an unexpected answer."
            Kind.PERSISTENCE -> if (code == "draft_unreadable") "This device could not read your saved payment progress, so no new payment was started. Nothing was changed. Contact your inviter or support before paying again."
            else "This device could not save your setup progress, so no new step was started. Free some space, then check the status and try again."
        }

    companion object {
        fun classify(status: Int, code: String?, attempt: OnboardingPaymentAttempt? = null): OnboardingFailure {
            val kind = when {
                (status == 404 && code == null) || status == 405 -> Kind.BACKEND_MISSING
                code == "onboarding_rollout_disabled" -> Kind.ROLLOUT_DISABLED
                code in setOf("stripe_unconfigured", "stripe_live_rollout_blocked", "lightning_unavailable", "return_url_not_allowed", "invalid_return_url") -> Kind.PAYMENT_UNAVAILABLE
                status == 401 || status == 403 -> Kind.SIGNER_REFUSED
                code == "invite_full" -> Kind.INVITE_FULL
                code == "invite_not_found" || code == "invite_revoked" -> Kind.INVITE_INVALID
                code == "workspace_name_taken" -> Kind.NAME_TAKEN
                code == "agent_offer_unavailable" -> Kind.AGENT_UNAVAILABLE
                code == "too_many_open_intents" -> Kind.TOO_MANY_INTENTS
                code == "stale_revision" -> Kind.STALE_REVISION
                code == "idempotency_conflict" -> Kind.IDEMPOTENCY_CONFLICT
                code == "intent_not_found" || code == "quote_not_found" -> Kind.NOT_FOUND
                code == "too_many_open_topup_quotes" -> Kind.TOO_MANY_QUOTES
                code == "payment_attempt_outstanding" -> Kind.PAYMENT_ATTEMPT_OUTSTANDING
                code == "payment_attempt_ambiguous" -> Kind.PAYMENT_ATTEMPT_AMBIGUOUS
                code == "account_not_found" || code == "account_not_provisioned" -> Kind.ACCOUNT_NOT_READY
                code == "identity_backup_required" -> Kind.BACKUP_REQUIRED
                code == "funding_required" -> Kind.FUNDING_REQUIRED
                code == "funding_reversed" -> Kind.FUNDING_REVERSED
                code == "checkout_outstanding" -> Kind.CHECKOUT_OUTSTANDING
                code in setOf("intent_not_editable", "funding_locked", "intent_not_open", "intent_not_cancellable", "intent_cancelled") -> Kind.NOT_EDITABLE
                status == 400 -> Kind.VALIDATION
                code == "provisioning_step_failed" -> Kind.PROVISIONING_FAILED
                else -> Kind.SERVER
            }
            return OnboardingFailure(kind, status, code, attempt)
        }
    }
}

data class OnboardingCreateRequest(
    val clientRequestKey: String, val invite: OnboardingInviteCode, val workspaceName: String, val fundingCents: Int,
    val agentOfferId: String? = null, val agentDailyCapCents: Int? = null,
    /** Sent only for a key this app generated; absent means "existing", which is the server's default and is never gated. */
    val identityOrigin: studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin = studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin.EXISTING,
)

/** Options update. Null fields are left out of the body so the server keeps them; [clearAgent] sends `agent_offer_id: null`. */
data class OnboardingOptionsUpdate(
    val expectedRevision: Int, val workspaceName: String? = null, val fundingCents: Int? = null,
    val agentOfferId: String? = null, val clearAgent: Boolean = false, val agentDailyCapCents: Int? = null,
)

/**
 * Signed client for `/api/v4/onboarding`. Every mutation carries a JSON body (at least `{}`) because the server's strict
 * NIP-98 check requires a payload hash even for bodiless actions; reads send none. The invitation code appears only in the
 * create-intent body, never in a path or query (the public `GET /invites/{code}` is deliberately not used for that reason).
 */
class OnboardingAPI(private val client: TowerClient) {
    val origin: TowerOrigin get() = client.origin

    companion object {
        const val BASE = "/api/v4/onboarding"

        fun intentPath(id: String, suffix: String = ""): String {
            if (!isUUID(id) || id != id.lowercase()) throw TowerError.UnsafePath
            return "$BASE/intents/$id$suffix"
        }
    }

    private suspend fun send(method: String, path: String, body: JsonObject? = null): ByteArray {
        val payload = if (method == "GET") null else jsonBody(body ?: JsonObject(emptyMap()))
        val exchange = try { client.exchange(method, path, null, payload) }
        catch (e: CancellationException) { throw e }
        catch (e: IdentityError) { throw e }
        catch (e: TowerError) { throw e }
        catch (e: OnboardingFailure) { throw e }
        catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.NETWORK) }
        if (exchange.status !in 200..299) {
            val error = parseJsonObjectOrNull(exchange.body)
            val attempt = (error?.get("payment_attempt") as? JsonObject)?.let { runCatching { OnboardingPaymentAttempt.parse(it) }.getOrNull() }
            throw OnboardingFailure.classify(exchange.status, error?.string("code"), attempt)
        }
        return exchange.body
    }

    private fun parse(data: ByteArray): JsonObject =
        try { parseJsonObject(data, "onboarding") } catch (e: Exception) { throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED) }

    private fun JsonObject.objectAt(key: String): JsonObject = this[key] as? JsonObject ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)

    /** Authenticated read of what this deployment can do now. A 404 means an older NStack without it: callers treat that as "unknown". */
    suspend fun config(): OnboardingConfig = OnboardingConfig.parse(parse(send("GET", "$BASE/config")))

    suspend fun account(): OnboardingAccount? {
        val o = parse(send("GET", "$BASE/account"))
        return if (o["account"] == null || o["account"] is JsonNull) null else OnboardingAccount.parse(o.objectAt("account"))
    }

    /** Idempotent: opens the owner account with no welcome or promotional credit. */
    suspend fun openAccount(): OnboardingAccount = OnboardingAccount.parse(parse(send("POST", "$BASE/account")).objectAt("account"))

    suspend fun agentOffers(): List<OnboardingAgentOffer> =
        (parse(send("GET", "$BASE/agent-offers"))["offers"] as? JsonArray ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)).objectsOrFail().map(OnboardingAgentOffer::parse)

    suspend fun intents(): List<OnboardingIntent> =
        (parse(send("GET", "$BASE/intents"))["intents"] as? JsonArray ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)).objectsOrFail().map(OnboardingIntent::parse)

    suspend fun intent(id: String): OnboardingIntent = OnboardingIntent.parse(parse(send("GET", intentPath(id))).objectAt("intent"))

    /** 201 on first creation, 200 on replay of the same key and body. */
    suspend fun createIntent(request: OnboardingCreateRequest): OnboardingIntent {
        val body = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "client_request_key" to JsonPrimitive(request.clientRequestKey), "invite_code" to JsonPrimitive(request.invite.raw),
            "workspace_name" to JsonPrimitive(request.workspaceName), "funding_cents" to JsonPrimitive(request.fundingCents),
        )
        request.agentOfferId?.let { body["agent_offer_id"] = JsonPrimitive(it) }
        request.agentDailyCapCents?.let { body["agent_daily_cap_cents"] = JsonPrimitive(it) }
        if (request.identityOrigin.needsBackup) body["identity_origin"] = JsonPrimitive(request.identityOrigin.wire)
        return OnboardingIntent.parse(parse(send("POST", "$BASE/intents", JsonObject(body))).objectAt("intent"))
    }

    suspend fun updateIntent(id: String, update: OnboardingOptionsUpdate): OnboardingIntent {
        val body = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("expected_revision" to JsonPrimitive(update.expectedRevision))
        update.workspaceName?.let { body["workspace_name"] = JsonPrimitive(it) }
        update.fundingCents?.let { body["funding_cents"] = JsonPrimitive(it) }
        if (update.clearAgent) body["agent_offer_id"] = JsonNull else update.agentOfferId?.let { body["agent_offer_id"] = JsonPrimitive(it) }
        update.agentDailyCapCents?.let { body["agent_daily_cap_cents"] = JsonPrimitive(it) }
        return OnboardingIntent.parse(parse(send("POST", intentPath(id, "/options"), JsonObject(body))).objectAt("intent"))
    }

    suspend fun cancel(id: String): OnboardingIntent = OnboardingIntent.parse(parse(send("POST", intentPath(id, "/cancel"))).objectAt("intent"))

    /** The amount is always the intent's server-held figure; [returnUrl] must be an exact member of the server's allowlist. */
    suspend fun checkout(id: String, idempotencyKey: String, returnUrl: String): OnboardingCheckout = OnboardingCheckout.parse(
        parse(send("POST", intentPath(id, "/checkout"), JsonObject(mapOf("idempotency_key" to JsonPrimitive(idempotencyKey), "return_url" to JsonPrimitive(returnUrl))))),
    )

    suspend fun topups(): OnboardingTopups = OnboardingTopups.parse(parse(send("GET", "$BASE/topups")))

    /** Repeat credit: no intent, workspace, seat or introduction. The amount is the person's, bound server-side to the order. */
    suspend fun topupCheckout(idempotencyKey: String, returnUrl: String, amountCents: Int): OnboardingCheckout = OnboardingCheckout.parse(
        parse(send("POST", "$BASE/topups/checkout", JsonObject(mapOf(
            "idempotency_key" to JsonPrimitive(idempotencyKey), "return_url" to JsonPrimitive(returnUrl), "amount_cents" to JsonPrimitive(amountCents),
        )))),
    )

    /** Lightning top-up invoice. Replaying the same key and amount returns the same quote (always 201); the amount is the person's. */
    suspend fun topupQuote(idempotencyKey: String, amountCents: Int): TopupQuote = TopupQuote.parse(
        parse(send("POST", "$BASE/topups/lightning/quote", JsonObject(mapOf("idempotency_key" to JsonPrimitive(idempotencyKey), "amount_cents" to JsonPrimitive(amountCents))))).objectAt("quote"),
    )

    /** NStack re-polls the provider itself; the body is the empty object (the signed payload hash still covers it). Nothing here is evidence. */
    suspend fun topupQuoteRefresh(quoteId: String): TopupQuote {
        if (!isUUID(quoteId) || quoteId != quoteId.lowercase()) throw TowerError.UnsafePath
        return TopupQuote.parse(parse(send("POST", "$BASE/topups/lightning/quotes/$quoteId/refresh")).objectAt("quote"))
    }

    /**
     * Lightning invoice for an open intent. The amount is the intent's server-held figure, never ours; replaying the same key returns
     * the same quote (always 201). The body is exactly `{idempotency_key}`.
     */
    suspend fun intentQuote(id: String, idempotencyKey: String): TopupQuote =
        TopupQuote.parse(parse(send("POST", intentPath(id, "/lightning/quote"), JsonObject(mapOf("idempotency_key" to JsonPrimitive(idempotencyKey))))).objectAt("quote"))

    /** NStack re-polls its provider itself; the body is the empty object (the signed payload hash still covers it). Nothing here is evidence. */
    suspend fun intentQuoteRefresh(id: String, quoteId: String): IntentQuoteRefresh {
        if (!isUUID(quoteId) || quoteId != quoteId.lowercase()) throw TowerError.UnsafePath
        val o = parse(send("POST", intentPath(id, "/lightning/quotes/$quoteId/refresh")))
        return IntentQuoteRefresh(TopupQuote.parse(o.objectAt("quote")), (o["intent"] as? JsonObject)?.let(OnboardingIntent::parse))
    }

    /**
     * Explicit owner request: NStack asks the provider to settle this intent's outstanding payment (paid funds the intent, open is cancelled).
     * Only a provider-verified end clears `payment_attempt` in the returned intent; the app never infers it.
     */
    suspend fun releasePaymentAttempt(id: String): OnboardingIntent = OnboardingIntent.parse(parse(send("POST", intentPath(id, "/payment-attempt/release"))).objectAt("intent"))

    suspend fun provision(id: String): OnboardingIntent = OnboardingIntent.parse(parse(send("POST", intentPath(id, "/provision"))).objectAt("intent"))
}
