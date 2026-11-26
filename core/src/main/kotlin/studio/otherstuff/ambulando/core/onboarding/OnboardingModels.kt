package studio.otherstuff.ambulando.core.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleInt
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

/**
 * Wire types of NStack `/api/v4/onboarding` (tower docs/contract/onboarding-v1.md). Decoding tolerates added fields and
 * fails closed ([OnboardingFailure] MALFORMED) on a missing or unrecognised field that a decision depends on.
 * Money is integer US cents exactly as the server holds it.
 */
data class OnboardingAccount(val accountId: String, val currency: String, val balanceUsd: String, val paidUsd: String, val promoUsd: String) {
    companion object {
        fun parse(o: JsonObject) = OnboardingAccount(
            o.req("account_id"), o.string("currency") ?: "USD", o.string("balance_usd") ?: "0", o.string("paid_usd") ?: "0", o.string("promo_usd") ?: "0",
        )
    }
}

data class OnboardingAgentOffer(
    val id: String, val template: String, val modelRef: String, val rateVersion: String, val defaultDailyCapCents: Int, val maxDailyCapCents: Int,
) {
    companion object {
        fun parse(o: JsonObject) = OnboardingAgentOffer(
            o.req("id"), o.req("template"), o.string("model_ref") ?: "", o.string("rate_version") ?: "",
            o.reqInt("default_daily_cap_cents"), o.reqInt("max_daily_cap_cents"),
        )
    }
}

data class OnboardingSelectedAgent(val id: String, val template: String, val dailyCapCents: Int) {
    companion object {
        fun parse(o: JsonObject) = OnboardingSelectedAgent(o.req("id"), o.req("template"), o.reqInt("daily_cap_cents"))
    }
}

enum class OnboardingAgentState { NONE, SELECTED_NOT_PROVISIONED, PROVISIONED }

enum class OnboardingIntentStatus { OPEN, FUNDED, PROVISIONING, PROVISIONED, CANCELLED }

data class OnboardingIntent(
    val id: String,
    val status: OnboardingIntentStatus,
    val revision: Int,
    val workspaceName: String,
    val agent: OnboardingSelectedAgent?,
    val agentState: OnboardingAgentState,
    val fundingCents: Int,
    val funded: Boolean,
    val fundingReversed: Boolean,
    val steps: Set<String>,
    val lastError: String?,
    val workspaceId: String?,
    val provisionedAt: String?,
    val introEndsAt: String?,
    /** False when the NStack predates the field: then nothing is known about outstanding payments and no local attempt may be cleared on that basis. */
    val paymentAttemptKnown: Boolean = false,
    /** NStack's canonical outstanding payment attempt for this intent (owner-only); null with [paymentAttemptKnown] means none is outstanding. */
    val paymentAttempt: OnboardingPaymentAttempt? = null,
) {
    /** Money moved and verified by the server (not a redirect, not a client flag) and not since reversed. */
    val fundingConfirmed: Boolean
        get() = funded && !fundingReversed && status in setOf(OnboardingIntentStatus.FUNDED, OnboardingIntentStatus.PROVISIONING, OnboardingIntentStatus.PROVISIONED)

    /** Backend evidence only: the intent's own recorded steps. Selecting an agent proves nothing. */
    val hasGeneral: Boolean get() = "general" in steps
    val hasAgentDm: Boolean get() = agentState == OnboardingAgentState.PROVISIONED || "agent" in steps

    companion object {
        fun parse(o: JsonObject): OnboardingIntent {
            val status = when (o.req("status")) {
                "open" -> OnboardingIntentStatus.OPEN
                "funded" -> OnboardingIntentStatus.FUNDED
                "provisioning" -> OnboardingIntentStatus.PROVISIONING
                "provisioned" -> OnboardingIntentStatus.PROVISIONED
                "cancelled" -> OnboardingIntentStatus.CANCELLED
                else -> throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            }
            val agentState = when (o.string("agent_state") ?: "none") {
                "none" -> OnboardingAgentState.NONE
                "selected_not_provisioned" -> OnboardingAgentState.SELECTED_NOT_PROVISIONED
                "provisioned" -> OnboardingAgentState.PROVISIONED
                else -> throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            }
            return OnboardingIntent(
                id = o.req("id"), status = status, revision = o.reqInt("revision"), workspaceName = o.req("workspace_name"),
                agent = o.obj("agent")?.let(OnboardingSelectedAgent::parse), agentState = agentState,
                fundingCents = o.reqInt("funding_cents"), funded = o.bool("funded") ?: false, fundingReversed = o.bool("funding_reversed") ?: false,
                steps = o.obj("steps")?.keys ?: emptySet(), lastError = o.string("last_error"), workspaceId = o.string("workspace_id"),
                provisionedAt = o.string("provisioned_at"), introEndsAt = o.string("intro_ends_at"),
                paymentAttemptKnown = o.containsKey("payment_attempt"),
                paymentAttempt = (o["payment_attempt"] as? JsonObject)?.let(OnboardingPaymentAttempt::parse),
            )
        }
    }
}

/**
 * `intent.payment_attempt`: the one outstanding payment of an intent as NStack holds it, so any device can resume after an identity
 * restore without the original replay key. It carries ids and a quote view only; anything unknown or inconsistent reads as [Kind.SUPPORT],
 * never as "free to start another payment".
 */
data class OnboardingPaymentAttempt(
    val method: OnboardingPaymentOption.Method?, val state: String, val orderId: String?, val quoteId: String?, val amountCents: Int?, val quote: TopupQuote?, val count: Int,
) {
    enum class Kind { CARD, LIGHTNING, SUPPORT }

    /** What the app may do with it: resume the one payment it names, or stop and show support. Ambiguous or unrecognised is support. */
    val kind: Kind get() = when {
        state == "ambiguous" || method == null || count != 1 -> Kind.SUPPORT
        state == "needs_support" -> Kind.SUPPORT
        state != "open" && state != "issuing" -> Kind.SUPPORT
        method == OnboardingPaymentOption.Method.CARD && orderId != null -> Kind.CARD
        method == OnboardingPaymentOption.Method.LIGHTNING && quoteId != null -> Kind.LIGHTNING
        else -> Kind.SUPPORT
    }

    companion object {
        fun parse(o: JsonObject): OnboardingPaymentAttempt {
            val method = when (o.string("method")) {
                "card" -> OnboardingPaymentOption.Method.CARD
                "lightning" -> OnboardingPaymentOption.Method.LIGHTNING
                else -> null
            }
            return OnboardingPaymentAttempt(
                method, o.string("state") ?: "ambiguous", o.string("order_id")?.takeIf { it.isNotEmpty() }, o.string("quote_id")?.takeIf { it.isNotEmpty() },
                flexibleInt(o["amount_cents"]), (o["quote"] as? JsonObject)?.let { runCatching { TopupQuote.parse(it) }.getOrNull() }, flexibleInt(o["count"]) ?: 0,
            )
        }
    }
}

data class OnboardingCheckout(val orderId: String, val checkoutUrl: String, val mode: String, val amountCents: Int) {
    companion object {
        fun parse(o: JsonObject) = OnboardingCheckout(o.req("order_id"), o.req("checkout_url"), o.req("mode"), o.reqInt("amount_cents"))
    }
}

/**
 * `GET /api/v4/onboarding/config`: what this deployment can do right now (tower onboarding-v1 "Deployment configuration for
 * clients"). Operator configuration only. Anything missing or out of its expected shape reads as "not available".
 */
data class OnboardingConfig(
    val onboardingEnabled: Boolean,
    val minFundingCents: Int, val maxFundingCents: Int, val topupMinCents: Int, val topupMaxCents: Int,
    val cardAvailable: Boolean, val cardMode: String?, val cardReturnUrls: List<String>,
    val lightningAvailable: Boolean,
) {
    companion object {
        fun parse(o: JsonObject): OnboardingConfig {
            if ((flexibleInt(o["version"]) ?: 0) != 1) throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            val funding = o.obj("funding") ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            val card = o.obj("card") ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            val lightning = o.obj("lightning")
            val min = funding.reqInt("min_cents"); val max = funding.reqInt("max_cents")
            if (min < 1 || max < min) throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            val urls = (card["return_urls"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            return OnboardingConfig(
                o.bool("onboarding_enabled") ?: false, min, max, flexibleInt(funding["topup_min_cents"]) ?: min, flexibleInt(funding["topup_max_cents"]) ?: max,
                card.bool("available") ?: false, card.string("mode"), urls.take(16), lightning?.bool("available") ?: false,
            )
        }
    }
}

/**
 * A top-up Lightning quote as NStack reports it (`POST /topups/lightning/quote`, `.../refresh`, and `pending_lightning` in `GET /topups`).
 * Everything here is the server's word; the invoice is shown only when [invoiceSafe] says it is plain bech32 text.
 */
data class TopupQuote(
    val id: String, val state: String, val amountCents: Int, val amountSats: Long?, val invoice: String?, val paymentHash: String?,
    val expiresAt: java.time.Instant?, val intentId: String? = null,
) {
    /** [PREPARING]: NStack holds the quote but the provider has not issued the invoice yet; nothing is payable. */
    enum class Phase { PAYABLE, EXPIRED, SUPPORT, ENDED, PREPARING }

    val terminal: Boolean get() = state in TERMINAL
    /** Only a plain lower/upper-case bech32 invoice is ever displayed, copied or handed to a wallet. */
    val invoiceSafe: String? get() = invoice?.takeIf { INVOICE.matches(it) }

    /** Display state. The device clock only decides what to *show*; it never marks anything paid or ended. */
    fun phase(now: java.time.Instant): Phase = when {
        terminal -> Phase.ENDED
        state == "issuing" -> Phase.PREPARING
        state != "open" || invoiceSafe == null || amountSats == null || amountSats < 1 || expiresAt == null -> Phase.SUPPORT
        !now.isBefore(expiresAt) -> Phase.EXPIRED
        else -> Phase.PAYABLE
    }

    companion object {
        val TERMINAL = setOf("settled", "expired", "cancelled")
        private val INVOICE = Regex("^[a-zA-Z]{2}[a-zA-Z0-9]{18,3998}$")
        private val HASH = Regex("^[0-9a-f]{64}$")

        fun parse(o: JsonObject, idKey: String = "id"): TopupQuote = TopupQuote(
            id = o.req(idKey), state = o.req("state"), amountCents = o.reqInt("amount_cents"),
            amountSats = (o["amount_sats"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull(),
            invoice = o.string("invoice")?.takeIf { it.isNotEmpty() },
            paymentHash = o.string("payment_hash")?.takeIf { HASH.matches(it) },
            expiresAt = o.string("expires_at")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
            intentId = o.string("intent_id"),
        )
    }
}

/**
 * A Lightning quote from `POST /intents/{id}/lightning/quote` or its `.../refresh` (which also returns NStack's current intent: the
 * only place funding can come from). Parsed with [TopupQuote]; the intent beside it is the server's word, never the quote's state.
 */
data class IntentQuoteRefresh(val quote: TopupQuote, val intent: OnboardingIntent?)

/** `GET /topups`: what repeat top-ups have credited and what is still waiting on a provider. Only credited rows are money. */
data class OnboardingTopups(
    val credited: List<Credited>, val pendingCheckoutOrderIds: Set<String>, val pendingLightningCount: Int,
    val pendingLightning: List<TopupQuote> = emptyList(),
    /**
     * NStack lists at most 20 waiting orders. True only when the list holds every waiting order, so only then does an order's
     * absence mean it is over. Absent (an older NStack) or anything but a boolean true reads as incomplete.
     */
    val pendingCheckoutComplete: Boolean = false,
) {
    data class Credited(val settlementId: String, val method: String, val amountCents: Int, val amountUsd: String?, val settledAt: String?)

    companion object {
        fun parse(o: JsonObject): OnboardingTopups {
            val lightning = o["pending_lightning"] as? JsonArray ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
            return OnboardingTopups(
                (o["credited"] as? JsonArray ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)).objectsOrFail().map {
                    Credited(it.req("settlement_id"), it.string("method") ?: "", it.reqInt("amount_cents"), it.string("amount_usd"), it.string("settled_at"))
                },
                (o["pending_checkout"] as? JsonArray ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)).objectsOrFail().map { it.req("order_id") }.toSet(),
                lightning.size,
                lightning.objectsOrFail().mapNotNull { runCatching { TopupQuote.parse(it, "quote_id") }.getOrNull() },
                o.bool("pending_checkout_complete") == true,
            )
        }
    }
}

internal fun JsonObject.req(key: String): String = string(key)?.takeIf { it.isNotEmpty() } ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
internal fun JsonObject.reqInt(key: String): Int = flexibleInt(this[key]) ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED)
internal fun JsonArray.objectsOrFail(): List<JsonObject> = map { it as? JsonObject ?: throw OnboardingFailure(OnboardingFailure.Kind.MALFORMED) }

/** Funding choices: US$21 is the server minimum; presets 21 / 50 / 100 or a custom amount of at least US$21. */
object OnboardingFunding {
    const val MINIMUM_CENTS = 2100
    const val MAXIMUM_CENTS = 1_000_000
    val PRESETS_CENTS = listOf(2100, 5000, 10000)

    fun valid(cents: Int) = cents in MINIMUM_CENTS..MAXIMUM_CENTS
    fun valid(cents: Int, min: Int, max: Int) = cents in min..max

    private val DOLLARS = Regex("^[0-9]{1,5}(\\.[0-9]{1,2})?$")

    /** Exact conversion of "21", "21.5" or "21.50" to cents. Signs, separators and more than two decimals are refused. */
    fun cents(fromDollars: String, min: Int = MINIMUM_CENTS, max: Int = MAXIMUM_CENTS): Int? {
        val text = fromDollars.trim()
        if (!DOLLARS.matches(text)) return null
        val parts = text.split('.')
        val fraction = (parts.getOrNull(1) ?: "0").padEnd(2, '0')
        val cents = parts[0].toInt() * 100 + fraction.toInt()
        return cents.takeIf { valid(it, min, max) }
    }

    fun label(cents: Int): String = if (cents % 100 == 0) "US$${cents / 100}" else "US$%d.%02d".format(cents / 100, cents % 100)
}

object OnboardingName {
    /** The server requires a name equal to its own trim, 1..80 characters. */
    fun normalized(raw: String): String? = raw.trim().takeIf { it.length in 1..80 }
}
