package studio.otherstuff.ambulando.core.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** One request as the fake saw it. [signer] is the verified NIP-98 signer hex, null for an unsigned request. */
data class Logged(val method: String, val url: String, val body: String, val signer: String?, val authorization: String?) {
    val path: String get() = url.toHttpUrl().encodedPath
}

/**
 * An in-process NStack that re-implements the parts of the onboarding contract the client depends on, with strict NIP-98
 * verification of real signatures (url, method, and a payload hash on EVERY mutation, even bodiless ones). It does not share code with the client.
 */
class FakeOnboardingTower(val origin: String = "https://tower.example.com", var returnAllowlist: Set<String> = setOf("https://return.example.com/paid")) : HttpTransport {
    val log = ArrayList<Logged>()
    var rolloutEnabled = true
    var routerMissing = false
    var descriptorEndpoint = origin
    var stripeBlocked = false
    var checkoutUrlOverride: String? = null
    /** Answers checkout with an order id other than the one NStack holds, as a confused or hostile server might. */
    var checkoutReplyOrderId: String? = null
    var checkoutModeOverride: String? = null
    var checkoutAmountDelta = 0
    var provisionFailures = 0
    var openIntentCap = 3
    var configAvailable = true
    var configReturnUrls: List<String>? = null            // null = the allowlist
    var cardConfigured = true
    var lightningConfigured = false
    var backupThrottled = false
    var beforeBackupRead: () -> Unit = {}
    /** Emulates an NStack that predates `payment_attempt`: no field on any intent, no canonical-attempt rule, no release route. */
    var legacyNoAttemptField = false

    class Backup(val username: String, val owner: String, var ncryptsec: String, var revision: Int)
    val backups = LinkedHashMap<String, Backup>()
    val intentOrigins = HashMap<String, String>()

    /** Throw before processing (the request never arrived) / after processing (the answer was lost) for the next matching request. */
    private val dropBefore = ArrayList<String>()
    private val dropAfter = ArrayList<String>()
    fun failBefore(suffix: String) { dropBefore.add(suffix) }
    fun loseAnswer(suffix: String) { dropAfter.add(suffix) }

    class Invite(val code: String, val max: Int, var revoked: Boolean = false) { val seats = LinkedHashMap<String, String>() }
    class Intent(
        val id: String, val owner: String, val key: String, val digest: String, val invite: Invite, var name: String, var funding: Int,
        var offerId: String?, var cap: Int?,
    ) {
        var status = "open"; var revision = 1; var fundedAt: String? = null; var reversed = false
        val steps = LinkedHashSet<String>(); var workspaceId: String? = null; var lastError: String? = null
    }
    class Order(val id: String, val intentId: String, val key: String, val amount: Int, val returnUrl: String) { var paid = false }
    class Offer(val id: String, val template: String, val default: Int, val max: Int)

    val invites = HashMap<String, Invite>()
    val intents = LinkedHashMap<String, Intent>()
    val orders = LinkedHashMap<String, Order>()
    val offers = ArrayList<Offer>()
    val accounts = HashSet<String>()

    fun newInvite(code: String, max: Int = 21): Invite = Invite(code, max).also { invites[code] = it }
    fun pay(intentId: String) { intents.getValue(intentId).also { it.status = "funded"; it.fundedAt = "2026-11-25T00:00:00Z" }; orders.values.filter { it.intentId == intentId }.forEach { it.paid = true } }
    fun paidOrders(): Int = orders.values.count { it.paid }
    fun mutations() = log.filter { it.method != "GET" }
    fun signedCount() = log.count { it.signer != null }
    fun requests(path: String) = log.filter { it.path == path }

    private fun error(status: Int, code: String): HttpResponse =
        HttpResponse(status, JsonObject(mapOf("code" to JsonPrimitive(code), "error" to JsonPrimitive(code))).toString().toByteArray())
    private fun errorWith(status: Int, code: String, extra: Map<String, JsonElement>) =
        HttpResponse(status, JsonObject(mapOf("code" to JsonPrimitive(code), "error" to JsonPrimitive(code)) + extra).toString().toByteArray())
    private fun ok(status: Int, body: JsonObject) = HttpResponse(status, body.toString().toByteArray())

    override suspend fun send(request: HttpRequest): HttpResponse {
        val body = request.body?.toString(Charsets.UTF_8) ?: ""
        val url = request.url.toHttpUrl()
        val path = url.encodedPath
        val auth = request.header("Authorization")
        val signer = auth?.let { verify(request, it) }
        synchronized(log) { log.add(Logged(request.method, request.url, body, signer, auth)) }
        if (dropBefore.any { path.endsWith(it) && request.method != "GET" }) { dropBefore.removeAt(dropBefore.indexOfFirst { path.endsWith(it) }); throw IOException("offline") }
        val response = route(request, path, body, signer)
        val after = dropAfter.firstOrNull { path.endsWith(it) && request.method != "GET" }
        if (after != null) { dropAfter.remove(after); throw IOException("answer lost") }
        return response
    }

    /** Strict NIP-98: kind 27235, valid signature, exact url and method, payload tag = sha256(body) for every non-GET. */
    private fun verify(request: HttpRequest, header: String): String? {
        if (!header.startsWith("Nostr ")) return null
        val event = runCatching { WireJson.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(header.removePrefix("Nostr ")))) }.getOrNull() ?: return null
        if (event.kind != 27235 || !SignatureVerifier.verify(event)) return null
        val tags = event.tags.associate { it[0] to it[1] }
        if (tags["u"] != request.url || tags["method"] != request.method) return null
        if (request.method != "GET" && tags["payload"] != Sha256Hex.of(request.body ?: ByteArray(0))) return null
        if (request.method == "GET" && "payload" in tags) return null
        return event.pubkey
    }

    private fun route(request: HttpRequest, path: String, body: String, signerHex: String?): HttpResponse {
        if (path == "/.well-known/nstack.json") return ok(200, JsonObject(mapOf(
            "schema" to JsonPrimitive("org.wingman.nstack.services"), "schema_version" to JsonPrimitive(1),
            "services" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("tower"), "kind" to JsonPrimitive("tower"),
                "endpoint" to JsonPrimitive(descriptorEndpoint), "protocols" to JsonArray(listOf(JsonPrimitive("nip-98")))))))
        )))
        if (routerMissing) return HttpResponse(404, "Not Found".toByteArray())
        if (path.startsWith("/api/v4/identity-backup")) return backupRoute(request, path.removePrefix("/api/v4/identity-backup"), body, signerHex)
        if (signerHex == null) return error(401, "nip98_invalid")
        val owner = NostrKeys.npub(signerHex)!!
        val sub = path.removePrefix("/api/v4/onboarding")
        val json = if (body.isNotEmpty()) runCatching { WireJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return error(400, "invalid_json") else JsonObject(emptyMap())
        val gated = request.method == "POST" && (sub == "/intents" || sub.endsWith("/options") || sub.endsWith("/checkout") || sub.endsWith("/provision"))
        if (gated && !rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        return when {
            sub == "/config" && request.method == "GET" -> if (!configAvailable) error(404, "not_found") else ok(200, JsonObject(mapOf(
                "version" to JsonPrimitive(1), "onboarding_enabled" to JsonPrimitive(rolloutEnabled),
                "funding" to JsonObject(mapOf("min_cents" to JsonPrimitive(2100), "max_cents" to JsonPrimitive(1_000_000), "topup_min_cents" to JsonPrimitive(2100), "topup_max_cents" to JsonPrimitive(1_000_000))),
                "card" to JsonObject(mapOf("available" to JsonPrimitive(rolloutEnabled && cardConfigured && !(configReturnUrls ?: returnAllowlist.toList()).isEmpty()), "mode" to JsonPrimitive("test"),
                    "live_rollout_blocked" to JsonPrimitive(false), "return_urls" to JsonArray((configReturnUrls ?: returnAllowlist.toList()).map(::JsonPrimitive)))),
                "lightning" to JsonObject(mapOf("available" to JsonPrimitive(rolloutEnabled && lightningConfigured))),
            )))
            sub == "/topups" && request.method == "GET" -> topups(owner)
            sub == "/topups/checkout" && request.method == "POST" -> topupCheckout(owner, json)
            sub == "/topups/lightning/quote" && request.method == "POST" -> topupQuote(owner, json)
            request.method == "POST" && Regex("^/topups/lightning/quotes/([0-9a-f-]{36})/refresh$").matchEntire(sub) != null ->
                topupQuoteRefresh(owner, Regex("^/topups/lightning/quotes/([0-9a-f-]{36})/refresh$").matchEntire(sub)!!.groupValues[1], body)
            sub == "/account" && request.method == "GET" -> ok(200, JsonObject(mapOf("account" to if (owner in accounts) account() else JsonNull)))
            sub == "/account" -> { accounts.add(owner); ok(200, JsonObject(mapOf("account" to account()))) }
            sub == "/agent-offers" -> ok(200, JsonObject(mapOf("offers" to JsonArray(offers.map(::offerJson)))))
            sub == "/intents" && request.method == "GET" -> ok(200, JsonObject(mapOf("intents" to JsonArray(intents.values.filter { it.owner == owner }.reversed().map(::view)))))
            sub == "/intents" -> create(owner, json)
            else -> Regex("^/intents/([0-9a-f-]{36})(/[a-z]+)?$").matchEntire(sub)?.let { m ->
                val intent = intents[m.groupValues[1]]?.takeIf { it.owner == owner } ?: return error(404, "intent_not_found")
                when (m.groupValues[2]) {
                    "" -> ok(200, JsonObject(mapOf("intent" to view(intent))))
                    "/options" -> options(intent, json)
                    "/cancel" -> cancel(intent)
                    "/checkout" -> checkout(intent, json)
                    "/provision" -> provision(intent)
                    else -> error(404, "not_found")
                }
            } ?: Regex("^/intents/([0-9a-f-]{36})/lightning/quote$").matchEntire(sub)?.takeIf { request.method == "POST" }?.let { m ->
                intentQuote(owner, m.groupValues[1], json)
            } ?: Regex("^/intents/([0-9a-f-]{36})/lightning/quotes/([0-9a-f-]{36})/refresh$").matchEntire(sub)?.takeIf { request.method == "POST" }?.let { m ->
                intentQuoteRefresh(owner, m.groupValues[1], m.groupValues[2], body)
            } ?: Regex("^/intents/([0-9a-f-]{36})/payment-attempt/release$").matchEntire(sub)?.takeIf { request.method == "POST" }?.let { m ->
                releaseAttempt(owner, m.groupValues[1])
            } ?: error(404, "not_found")
        }
    }

    class TopupOrder(val id: String, val owner: String, val key: String, val amount: Int, val returnUrl: String) { var credited = false; var ended = false }
    val topupOrders = LinkedHashMap<String, TopupOrder>()
    fun creditTopup(orderId: String) { topupOrders.getValue(orderId).credited = true }
    /** Simulates NStack's 20-row cap: these waiting orders are left off the page and the page says it is incomplete. */
    val truncatedOut = HashSet<String>()
    /** Simulates an older NStack that never sends `pending_checkout_complete`. */
    var omitPendingCompleteFlag = false
    fun endTopup(orderId: String) { topupOrders.getValue(orderId).ended = true }

    /** A Lightning top-up quote as the server holds it; `state` is one of open, settled, expired, cancelled, late_payment, mismatch. */
    class Quote(val id: String, val owner: String, val key: String, val amount: Int, val intentId: String? = null) {
        /** What the (simulated) provider would report on the next refresh; the server applies it only on a refresh, as the real one does. */
        var providerState = "pending"
        var creditedLn = false; var state = "open"; var expiresAt = "2099-01-01T00:00:00.000Z"; var sats = amount * 3L
        val invoice = "lnbc" + id.replace("-", "").take(30) + "q" + "p".repeat(20)
        val hash = id.replace("-", "").repeat(2)
    }
    val quotes = LinkedHashMap<String, Quote>()
    var topupQuoteAmountDelta = 0
    var refreshBody: String? = null
    fun payQuote(id: String) { quotes.getValue(id).also { it.state = "settled"; it.creditedLn = true } }
    fun setQuoteState(id: String, state: String) { quotes.getValue(id).state = state }

    private fun quoteJson(q: Quote, idKey: String = "id") = JsonObject(buildMap {
        put(idKey, JsonPrimitive(q.id)); if (idKey == "id") put("intent_id", q.intentId?.let(::JsonPrimitive) ?: JsonNull)
        put("state", JsonPrimitive(q.state)); put("amount_cents", JsonPrimitive(q.amount + topupQuoteAmountDelta)); put("amount_sats", JsonPrimitive(q.sats))
        put("invoice", JsonPrimitive(q.invoice)); put("payment_hash", JsonPrimitive(q.hash)); put("expires_at", JsonPrimitive(q.expiresAt))
        put("rate", JsonNull)
    })

    private fun topupQuote(owner: String, b: JsonObject): HttpResponse {
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        if (owner !in accounts) return error(404, "account_not_found")
        if (!provisionedOwner(owner)) return error(409, "account_not_provisioned")
        if (!lightningConfigured) return error(503, "lightning_unavailable")
        if (b.keys.sorted() != listOf("amount_cents", "idempotency_key")) return error(400, "invalid_quote_request")
        val amount = b.getValue("amount_cents").jsonPrimitive.int
        if (amount !in 2100..1_000_000) return error(400, "invalid_quote_request")
        val key = b.getValue("idempotency_key").jsonPrimitive.content
        if (!Regex("^[a-zA-Z0-9_-]{8,100}$").matches(key)) return error(400, "invalid_quote_request")
        val prior = quotes.values.firstOrNull { it.owner == owner && it.key == key }
        if (prior != null && (prior.amount != amount || prior.intentId != null)) return error(409, "idempotency_conflict")
        if (prior == null && quotes.values.count { it.owner == owner && it.intentId == null && it.state == "open" } >= 5) return error(429, "too_many_open_topup_quotes")
        val q = prior ?: Quote(UUID.randomUUID().toString(), owner, key, amount).also { quotes[it.id] = it }
        return ok(201, JsonObject(mapOf("quote" to quoteJson(q))))
    }

    private fun topupQuoteRefresh(owner: String, id: String, body: String): HttpResponse {
        refreshBody = body
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        if (owner !in accounts) return error(404, "account_not_found")
        if (!provisionedOwner(owner)) return error(409, "account_not_provisioned")
        val q = quotes[id]?.takeIf { it.owner == owner } ?: return error(404, "quote_not_found")
        return ok(200, JsonObject(mapOf("quote" to quoteJson(q))))
    }

    // ---- initial onboarding Lightning (tower hosting-lightning.ts issueLightningQuote / resolveLightningQuote) ----

    /** The quote row is written and then the provider fails: a 503 that does NOT prove nothing exists. */
    var intentQuoteFailsAfterInsert = false
    /** Every body the intent quote/refresh routes saw, for exactness assertions. */
    val intentQuoteBodies = ArrayList<String>()
    var intentRefreshBodies = ArrayList<String>()
    var intentQuoteAmountDelta = 0
    var intentQuoteIntentIdOverride: String? = null
    var refreshUnavailable = false
    /** When set, answers the next intent quote request with this (after recording the body), without touching any quote. */
    var intentQuoteRaw: (() -> HttpResponse)? = null

    /** Simulated provider outcome, applied (and funding recorded) only when a refresh reaches the server. */
    fun providerSays(quoteId: String, state: String) { quotes.getValue(quoteId).providerState = state }
    fun quotesOf(intentId: String) = quotes.values.filter { it.intentId == intentId }

    private fun intentQuote(owner: String, intentId: String, b: JsonObject): HttpResponse {
        intentQuoteBodies.add(b.toString())
        intentQuoteRaw?.let { return it() }
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        if (b.keys.toList() != listOf("idempotency_key") || !Regex("^[a-zA-Z0-9_-]{8,100}$").matches(b.getValue("idempotency_key").jsonPrimitive.content)) return error(400, "invalid_quote_request")
        if (!lightningConfigured) return error(503, "lightning_unavailable")
        val i = intents[intentId]?.takeIf { it.owner == owner } ?: return error(404, "intent_not_found")
        val key = b.getValue("idempotency_key").jsonPrimitive.content
        val prior = quotes.values.firstOrNull { it.owner == owner && it.key == key }
        if (prior != null) return if (prior.intentId == intentId) ok(201, JsonObject(mapOf("quote" to intentQuoteJson(prior)))) else error(409, "idempotency_conflict")
        if (i.status != "open") return error(409, "intent_not_open")
        if (intentOrigins[i.id] == "new" && backups.values.none { it.owner == i.owner }) return error(409, "identity_backup_required")
        attemptRefusal(i, "lightning")?.let { return it }
        outstandingQuotes(i).singleOrNull()?.let { return ok(200, JsonObject(mapOf("quote" to intentQuoteJson(it), "reused" to JsonPrimitive(true)))) }
        val q = Quote(UUID.randomUUID().toString(), owner, key, i.funding, intentId).also { quotes[it.id] = it }
        if (intentQuoteFailsAfterInsert) return error(503, "lightning_unavailable")
        return ok(201, JsonObject(mapOf("quote" to intentQuoteJson(q))))
    }

    private fun intentQuoteJson(q: Quote): JsonObject {
        val base = quoteJson(q).toMutableMap()
        base["amount_cents"] = JsonPrimitive(q.amount + intentQuoteAmountDelta)
        intentQuoteIntentIdOverride?.let { base["intent_id"] = JsonPrimitive(it) }
        return JsonObject(base)
    }

    private fun intentQuoteRefresh(owner: String, intentId: String, quoteId: String, body: String): HttpResponse {
        intentRefreshBodies.add(body)
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        val i = intents[intentId]?.takeIf { it.owner == owner } ?: return error(404, "quote_not_found")
        val q = quotes[quoteId]?.takeIf { it.owner == owner && it.intentId == intentId } ?: return error(404, "quote_not_found")
        if (!lightningConfigured || refreshUnavailable) return error(503, "lightning_unavailable")
        if (q.state in setOf("open", "late_payment", "mismatch")) when (q.providerState) {
            "settled" -> { q.state = "settled"; q.creditedLn = true; if (i.status == "open") { i.status = "funded"; i.fundedAt = "2026-11-25T00:00:00Z"; i.revision++ } }
            "expired", "cancelled", "late_payment", "mismatch" -> q.state = q.providerState
        }
        return ok(200, JsonObject(mapOf("quote" to intentQuoteJson(q), "intent" to view(i))))
    }

    // ---- canonical outstanding payment attempt (tower onboarding-payment-attempt.ts) ----

    fun outstandingOrders(i: Intent) = orders.values.filter { it.intentId == i.id && !it.paid && it.id !in expiredOrders }
    fun outstandingQuotes(i: Intent) = quotesOf(i.id).filter { it.state in setOf("issuing", "open", "late_payment", "mismatch") }

    fun attemptJson(i: Intent): JsonElement {
        val o = outstandingOrders(i); val q = outstandingQuotes(i)
        val n = o.size + q.size
        if (n == 0) return JsonNull
        if (n > 1) return JsonObject(mapOf("method" to JsonNull, "state" to JsonPrimitive("ambiguous"), "resume" to JsonPrimitive("support"), "order_id" to JsonNull, "quote_id" to JsonNull, "amount_cents" to JsonNull,
            "created_at" to JsonPrimitive("t"), "quote" to JsonNull, "count" to JsonPrimitive(n)))
        o.firstOrNull()?.let { return JsonObject(mapOf("method" to JsonPrimitive("card"), "state" to JsonPrimitive("open"), "resume" to JsonPrimitive("checkout"), "order_id" to JsonPrimitive(it.id), "quote_id" to JsonNull,
            "amount_cents" to JsonPrimitive(it.amount), "created_at" to JsonPrimitive("t"), "quote" to JsonNull, "count" to JsonPrimitive(1))) }
        val x = q.single(); val live = x.state == "open" || x.state == "issuing"
        return JsonObject(mapOf("method" to JsonPrimitive("lightning"), "state" to JsonPrimitive(if (live) x.state else "needs_support"), "resume" to JsonPrimitive(if (live) "lightning_quote" else "support"),
            "order_id" to JsonNull, "quote_id" to JsonPrimitive(x.id), "amount_cents" to JsonPrimitive(x.amount), "created_at" to JsonPrimitive("t"), "quote" to intentQuoteJson(x), "count" to JsonPrimitive(1)))
    }

    /** The server's one-attempt rule for a request of [method]: null = free to create; else the refusal (other method or ambiguous) or the reusable same-method attempt. */
    private fun attemptRefusal(i: Intent, method: String): HttpResponse? {
        if (legacyNoAttemptField) return null
        val o = outstandingOrders(i); val q = outstandingQuotes(i)
        if (o.size + q.size > 1) return errorWith(409, "payment_attempt_ambiguous", mapOf("payment_attempt" to attemptJson(i)))
        if (o.size + q.size == 1 && (if (o.isNotEmpty()) "card" else "lightning") != method) return errorWith(409, "payment_attempt_outstanding", mapOf("payment_attempt" to attemptJson(i)))
        return null
    }

    /** What the provider does when NStack asks it to settle an intent's holds: a paid invoice funds, an open one is cancelled, held ones stay held. */
    private fun resolveHolds(i: Intent) {
        quotesOf(i.id).filter { it.state == "open" }.forEach { q ->
            when (q.providerState) {
                "settled" -> { q.state = "settled"; q.creditedLn = true; if (i.status == "open") { i.status = "funded"; i.fundedAt = "2026-11-25T00:00:00Z"; i.revision++ } }
                "late_payment", "mismatch" -> q.state = q.providerState
                else -> q.state = "cancelled"
            }
        }
        outstandingOrders(i).forEach { expiredOrders.add(it.id) }
    }

    private fun releaseAttempt(owner: String, intentId: String): HttpResponse {
        if (legacyNoAttemptField) return HttpResponse(404, "Not Found".toByteArray())
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        val i = intents[intentId]?.takeIf { it.owner == owner } ?: return error(404, "intent_not_found")
        resolveHolds(i)
        return ok(200, JsonObject(mapOf("intent" to view(i))))
    }

    private fun provisionedOwner(owner: String) = intents.values.any { it.owner == owner && it.status == "provisioned" }

    private fun topups(owner: String): HttpResponse {
        if (owner !in accounts) return error(404, "account_not_found")
        if (!provisionedOwner(owner)) return error(409, "account_not_provisioned")
        val mine = topupOrders.values.filter { it.owner == owner }
        return ok(200, JsonObject(mapOf(
            "credited" to JsonArray(quotes.values.filter { it.owner == owner && it.creditedLn && it.intentId == null }.map { JsonObject(mapOf("settlement_id" to JsonPrimitive("s-" + it.id), "method" to JsonPrimitive("lightning"), "amount_cents" to JsonPrimitive(it.amount), "amount_usd" to JsonPrimitive("%.2f".format(it.amount / 100.0)), "settled_at" to JsonPrimitive("2026-11-25T00:00:00.000Z"))) } + mine.filter { it.credited }.map { JsonObject(mapOf("settlement_id" to JsonPrimitive("s-" + it.id), "method" to JsonPrimitive("stripe"), "amount_cents" to JsonPrimitive(it.amount), "amount_usd" to JsonPrimitive("%.2f".format(it.amount / 100.0)), "settled_at" to JsonPrimitive("2026-11-25T00:00:00.000Z"))) }),
            "pending_lightning" to JsonArray(quotes.values.filter { it.owner == owner && it.intentId == null && it.state in setOf("open", "late_payment", "mismatch") }.map { quoteJson(it, "quote_id") }),
            "pending_checkout" to JsonArray(mine.filter { !it.credited && !it.ended && it.id !in truncatedOut }.map { JsonObject(mapOf("order_id" to JsonPrimitive(it.id), "amount_cents" to JsonPrimitive(it.amount), "created_at" to JsonPrimitive("t"))) }),
        ) + (if (omitPendingCompleteFlag) emptyMap() else mapOf("pending_checkout_complete" to JsonPrimitive(truncatedOut.isEmpty())))))
    }

    private fun topupCheckout(owner: String, b: JsonObject): HttpResponse {
        if (!rolloutEnabled) return error(503, "onboarding_rollout_disabled")
        if (owner !in accounts) return error(404, "account_not_found")
        if (!provisionedOwner(owner)) return error(409, "account_not_provisioned")
        if (b.keys.sorted() != listOf("amount_cents", "idempotency_key", "return_url")) return error(400, "invalid_checkout")
        val amount = b.getValue("amount_cents").jsonPrimitive.int
        if (amount !in 2100..1_000_000) return error(400, "invalid_checkout")
        val key = b.getValue("idempotency_key").jsonPrimitive.content
        val returnUrl = b.getValue("return_url").jsonPrimitive.content
        if (returnUrl !in returnAllowlist) return error(400, "return_url_not_allowed")
        val order = topupOrders.values.firstOrNull { it.owner == owner && it.key == key } ?: TopupOrder(UUID.randomUUID().toString(), owner, key, amount, returnUrl).also { topupOrders[it.id] = it }
        if (order.amount != amount || order.returnUrl != returnUrl) return error(409, "idempotency_conflict")
        return ok(201, JsonObject(mapOf("order_id" to JsonPrimitive(order.id), "checkout_url" to JsonPrimitive("https://checkout.stripe.com/c/pay/${order.id}"), "mode" to JsonPrimitive("test"), "amount_cents" to JsonPrimitive(order.amount))))
    }

    private fun account() = JsonObject(mapOf("account_id" to JsonPrimitive("acct"), "currency" to JsonPrimitive("USD"), "balance_usd" to JsonPrimitive("0.00"),
        "paid_usd" to JsonPrimitive("0.00"), "promo_usd" to JsonPrimitive("0.00"), "created_at" to JsonPrimitive("2026-11-25T00:00:00Z")))
    private fun offerJson(o: Offer) = JsonObject(mapOf("id" to JsonPrimitive(o.id), "template" to JsonPrimitive(o.template), "model_ref" to JsonPrimitive("m"), "rate_version" to JsonPrimitive("1"),
        "default_daily_cap_cents" to JsonPrimitive(o.default), "max_daily_cap_cents" to JsonPrimitive(o.max)))

    fun view(i: Intent): JsonObject {
        val offer = offers.firstOrNull { it.id == i.offerId }
        val agent: JsonElement = if (offer == null) JsonNull else JsonObject(offerJson(offer) + ("daily_cap_cents" to JsonPrimitive(i.cap)))
        return JsonObject(mapOf(
            "version" to JsonPrimitive(1), "id" to JsonPrimitive(i.id), "account_id" to JsonPrimitive("acct"), "status" to JsonPrimitive(i.status), "revision" to JsonPrimitive(i.revision),
            "workspace_name" to JsonPrimitive(i.name), "agent" to agent,
            "agent_state" to JsonPrimitive(if (i.offerId == null) "none" else if ("agent" in i.steps) "provisioned" else "selected_not_provisioned"),
            "funding_cents" to JsonPrimitive(i.funding), "return_state" to JsonNull, "funded" to JsonPrimitive(i.fundedAt != null), "funded_at" to (i.fundedAt?.let(::JsonPrimitive) ?: JsonNull),
            "funding_reversed" to JsonPrimitive(i.reversed),
            "steps" to JsonObject(i.steps.associateWith { JsonObject(mapOf("completed_at" to JsonPrimitive("2026-11-25T00:00:00Z"))) }),
            "last_error" to (i.lastError?.let(::JsonPrimitive) ?: JsonNull), "workspace_id" to (i.workspaceId?.let(::JsonPrimitive) ?: JsonNull),
            "provisioned_at" to JsonNull, "intro_ends_at" to JsonNull, "created_at" to JsonPrimitive("t"), "updated_at" to JsonPrimitive("t"),
        ) + (if (legacyNoAttemptField) emptyMap() else mapOf("payment_attempt" to attemptJson(i))))
    }

    private fun create(owner: String, b: JsonObject): HttpResponse {
        val allowed = setOf("client_request_key", "invite_code", "workspace_name", "funding_cents", "agent_offer_id", "agent_daily_cap_cents", "identity_origin")
        b["identity_origin"]?.let { if (it.jsonPrimitive.content !in setOf("new", "existing")) return error(400, "invalid_identity_origin") }
        if (!b.keys.all { it in allowed } || !b.keys.containsAll(listOf("client_request_key", "invite_code", "workspace_name", "funding_cents"))) return error(400, "invalid_body")
        val key = b.getValue("client_request_key").jsonPrimitive.content
        if (!Regex("^[a-zA-Z0-9_-]{8,100}$").matches(key)) return error(400, "invalid_client_request_key")
        val name = b.getValue("workspace_name").jsonPrimitive.content
        if (name != name.trim() || name.length !in 1..80) return error(400, "invalid_workspace_name")
        val funding = b.getValue("funding_cents").jsonPrimitive.int
        if (funding !in 2100..1_000_000) return error(400, "invalid_funding_cents")
        val digest = Sha256Hex.of(b.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" })
        intents.values.firstOrNull { it.owner == owner && it.key == key }?.let {
            return if (it.digest == digest) ok(200, JsonObject(mapOf("intent" to view(it), "replayed" to JsonPrimitive(true)))) else error(409, "idempotency_conflict")
        }
        if (intents.values.count { it.owner == owner && it.status in setOf("open", "funded", "provisioning") } >= openIntentCap) return error(409, "too_many_open_intents")
        val invite = invites[b.getValue("invite_code").jsonPrimitive.content] ?: return error(404, "invite_not_found")
        if (invite.revoked) return error(404, "invite_revoked")
        if (invite.seats[owner] == null && invite.seats.values.count { it != "released" } >= invite.max) return error(409, "invite_full")
        if (intents.values.any { it.owner == owner && it.status in setOf("open", "funded", "provisioning") && it.name.equals(name, true) }) return error(409, "workspace_name_taken")
        var offerId: String? = null; var cap: Int? = null
        b["agent_offer_id"]?.takeIf { it !is JsonNull }?.let { id ->
            val offer = offers.firstOrNull { it.id == id.jsonPrimitive.content } ?: return error(409, "agent_offer_unavailable")
            offerId = offer.id; cap = b["agent_daily_cap_cents"]?.jsonPrimitive?.int ?: offer.default
            if (cap!! !in 1..offer.max) return error(400, "invalid_agent_daily_cap")
        }
        invite.seats.putIfAbsent(owner, "reserved")
        val intent = Intent(UUID.randomUUID().toString(), owner, key, digest, invite, name, funding, offerId, cap)
        intents[intent.id] = intent
        intentOrigins[intent.id] = b["identity_origin"]?.jsonPrimitive?.content ?: "existing"
        return ok(201, JsonObject(mapOf("intent" to view(intent), "replayed" to JsonPrimitive(false))))
    }

    private fun options(i: Intent, b: JsonObject): HttpResponse {
        if (!b.keys.all { it in setOf("expected_revision", "workspace_name", "funding_cents", "agent_offer_id", "agent_daily_cap_cents") } || "expected_revision" !in b) return error(400, "invalid_body")
        if (b.getValue("expected_revision").jsonPrimitive.int != i.revision) return error(409, "stale_revision")
        if (i.status != "open" && i.status != "funded") return error(409, "intent_not_editable")
        b["funding_cents"]?.let {
            if (i.status != "open") return error(409, "funding_locked")
            if (it.jsonPrimitive.int !in 2100..1_000_000) return error(400, "invalid_funding_cents")
            i.funding = it.jsonPrimitive.int
        }
        b["workspace_name"]?.let { i.name = it.jsonPrimitive.content }
        if ("agent_offer_id" in b) {
            val v = b.getValue("agent_offer_id")
            if (v is JsonNull) { i.offerId = null; i.cap = null } else {
                val offer = offers.firstOrNull { it.id == v.jsonPrimitive.content } ?: return error(409, "agent_offer_unavailable")
                i.offerId = offer.id; i.cap = b["agent_daily_cap_cents"]?.jsonPrimitive?.int ?: offer.default
            }
        }
        i.revision++
        return ok(200, JsonObject(mapOf("intent" to view(i))))
    }

    private fun checkout(i: Intent, b: JsonObject): HttpResponse {
        if (b.keys.sorted() != listOf("idempotency_key", "return_url")) return error(400, "invalid_checkout")
        if (stripeBlocked) return error(503, "stripe_unconfigured")
        if (intentOrigins[i.id] == "new" && backups.values.none { it.owner == i.owner }) return error(409, "identity_backup_required")
        val returnUrl = b.getValue("return_url").jsonPrimitive.content
        if (returnUrl !in returnAllowlist) return error(400, "return_url_not_allowed")
        if (i.status != "open") return error(409, "intent_not_open")
        val key = b.getValue("idempotency_key").jsonPrimitive.content
        if (orders.values.none { it.key == key }) {
            attemptRefusal(i, "card")?.let { return it }
            outstandingOrders(i).singleOrNull()?.let { held ->
                return ok(200, JsonObject(mapOf("order_id" to JsonPrimitive(held.id), "checkout_url" to JsonPrimitive("https://checkout.stripe.com/c/pay/${held.id}"), "mode" to JsonPrimitive("test"), "amount_cents" to JsonPrimitive(held.amount), "reused" to JsonPrimitive(true))))
            }
        }
        val order = orders.values.firstOrNull { it.key == key } ?: Order(UUID.randomUUID().toString(), i.id, key, i.funding, returnUrl).also { orders[it.id] = it }
        if (order.amount != i.funding || order.returnUrl != returnUrl || order.intentId != i.id) return error(409, "idempotency_conflict")
        return ok(201, JsonObject(mapOf("order_id" to JsonPrimitive(checkoutReplyOrderId ?: order.id), "checkout_url" to JsonPrimitive(checkoutUrlOverride ?: "https://checkout.stripe.com/c/pay/${order.id}"),
            "mode" to JsonPrimitive(checkoutModeOverride ?: "test"), "amount_cents" to JsonPrimitive(order.amount + checkoutAmountDelta))))
    }

    private fun provision(i: Intent): HttpResponse {
        if (i.fundedAt == null) return error(402, "funding_required")
        if (i.reversed) return error(402, "funding_reversed")
        if (provisionFailures > 0) { provisionFailures--; i.lastError = "billing_link:boom"; i.steps.add("workspace"); return error(502, "provisioning_step_failed") }
        i.steps.add("workspace"); i.steps.add("billing_link"); i.workspaceId = i.workspaceId ?: UUID.randomUUID().toString(); i.status = "provisioned"; i.lastError = null
        i.invite.seats[i.owner] = "redeemed"
        return ok(200, JsonObject(mapOf("intent" to view(i))))
    }

    private fun cancel(i: Intent): HttpResponse {
        // The server asks the provider first: a paid invoice funds the intent, an open one is cancelled; held ones keep blocking.
        quotesOf(i.id).filter { it.state == "open" }.forEach { q ->
            when (q.providerState) {
                "settled" -> { q.state = "settled"; q.creditedLn = true; if (i.status == "open") { i.status = "funded"; i.fundedAt = "2026-11-25T00:00:00Z"; i.revision++ } }
                "late_payment", "mismatch" -> q.state = q.providerState
                else -> q.state = "cancelled"
            }
        }
        if (i.status != "open") return error(409, "intent_not_cancellable")
        if (quotesOf(i.id).any { it.state in setOf("issuing", "open", "late_payment", "mismatch") }) return error(409, "checkout_outstanding")
        if (orders.values.any { it.intentId == i.id && !it.paid && !expiredOrders.contains(it.id) }) return error(409, "checkout_outstanding")
        i.status = "cancelled"; i.invite.seats[i.owner] = "released"
        return ok(200, JsonObject(mapOf("intent" to view(i))))
    }

    val expiredOrders = HashSet<String>()

    // MARK: hosted identity backup (docs/contract/hosted-identity-backup-v1.md)

    private val usernameShape = Regex("^[a-z0-9][a-z0-9_-]{1,30}[a-z0-9]$")
    private val bech32 = Regex("^ncryptsec1[023456789acdefghjklmnpqrstuvwxyz]{152}$")

    private fun backupRoute(request: HttpRequest, sub: String, body: String, signerHex: String?): HttpResponse {
        if (backupThrottled) return HttpResponse(429, JsonObject(mapOf("code" to JsonPrimitive("rate_limited"))).toString().toByteArray(), mapOf("Retry-After" to "30"))
        val name = sub.removePrefix("/")
        if (name == "me") {
            if (signerHex == null || request.method != "GET") return error(401, "nip98_invalid")
            val owner = NostrKeys.npub(signerHex)!!
            val b = backups.values.firstOrNull { it.owner == owner } ?: return error(404, "backup_not_found")
            return ok(200, JsonObject(mapOf("username" to JsonPrimitive(b.username), "revision" to JsonPrimitive(b.revision.toString()), "updated_at" to JsonPrimitive("t"))))
        }
        if (!usernameShape.matches(name) || name in setOf("admin", "support", "nstack", "wingman", "root", "system")) return error(400, "invalid_username")
        if (request.method == "GET") {
            beforeBackupRead()
            val b = backups[name] ?: return error(404, "backup_not_found")
            return ok(200, JsonObject(mapOf("username" to JsonPrimitive(b.username), "ncryptsec" to JsonPrimitive(b.ncryptsec), "revision" to JsonPrimitive(b.revision.toString()), "updated_at" to JsonPrimitive("t"))))
        }
        if (request.method != "PUT") return error(405, "method_not_allowed")
        if (signerHex == null) return error(401, "nip98_invalid")
        val owner = NostrKeys.npub(signerHex)!!
        val json = runCatching { WireJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return error(400, "invalid_json")
        val text = json["ncryptsec"]?.jsonPrimitive?.content ?: return error(400, "invalid_backup")
        if (text.length != 162 || !bech32.matches(text)) return error(400, "invalid_backup")
        val expected = json["expected_revision"]?.jsonPrimitive?.content ?: return error(400, "invalid_backup")
        val current = backups[name]
        if (current != null && current.owner != owner) return error(if (expected == "0") 409 else 403, if (expected == "0") "username_unavailable" else "backup_owner_mismatch")
        if (current == null) {
            if (expected != "0") return error(409, "revision_conflict")
            if (backups.values.any { it.owner == owner }) return error(409, "identity_already_backed_up")
            backups[name] = Backup(name, owner, text, 1)
            return ok(201, JsonObject(mapOf("username" to JsonPrimitive(name), "revision" to JsonPrimitive("1"), "created" to JsonPrimitive(true))))
        }
        if (current.ncryptsec == text && expected in setOf((current.revision).toString(), (current.revision - 1).toString())) return ok(200, JsonObject(mapOf("username" to JsonPrimitive(name), "revision" to JsonPrimitive(current.revision.toString()), "created" to JsonPrimitive(false))))
        if (expected != current.revision.toString()) return HttpResponse(409, JsonObject(mapOf("code" to JsonPrimitive("revision_conflict"), "current_revision" to JsonPrimitive(current.revision.toString()))).toString().toByteArray())
        current.ncryptsec = text; current.revision++
        return ok(200, JsonObject(mapOf("username" to JsonPrimitive(name), "revision" to JsonPrimitive(current.revision.toString()), "created" to JsonPrimitive(false))))
    }
}
