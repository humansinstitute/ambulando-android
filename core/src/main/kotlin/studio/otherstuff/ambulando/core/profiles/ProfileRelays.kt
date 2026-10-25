package studio.otherstuff.ambulando.core.profiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where public profiles come from. Production reads kind-0 events from public relays; sample-data mode and tests
 * inject their own.
 */
fun interface ProfileSource {
    /**
     * Verified profiles for (some of) `pubkeys`, newest per key. Throws only when no source could be asked at all, so
     * a failed network round is retried later instead of being recorded as "no profile".
     */
    suspend fun profiles(pubkeys: List<String>): List<ProfileRecord>
}

/**
 * Accepts a relay event only if it is a kind-0 profile of one of the requested keys with a valid NIP-01 id and
 * BIP-340 signature and a plausible time, and keeps the newest per key.
 */
object ProfileEventFilter {
    /**
     * Profiles dated before Nostr existed, or more than this far ahead of the device clock, are refused: a far-future
     * date would otherwise pin a profile against every later, genuine update.
     */
    const val EARLIEST = 1_577_836_800L   // 2020-01-01
    const val MAX_CLOCK_SKEW_SECONDS = 15 * 60L

    fun accept(events: List<NostrEvent>, requested: Set<String>, checkedAt: Instant): List<ProfileRecord> {
        val best = LinkedHashMap<String, ProfileRecord>()
        val latest = checkedAt.epochSecond + MAX_CLOCK_SKEW_SECONDS
        for (event in events) {
            if (event.kind != 0 || event.pubkey !in requested || !isLowerHex(event.pubkey, 64)) continue
            if (event.createdAt < EARLIEST || event.createdAt > latest) continue
            if (event.content.toByteArray(Charsets.UTF_8).size > NostrProfileContent.MAX_CONTENT_BYTES) continue
            if (!SignatureVerifier.verify(event)) continue
            val record = ProfileRecord.from(event, checkedAt) ?: continue
            val current = best[event.pubkey]
            if (current != null && !record.isNewer(current)) continue
            best[event.pubkey] = record
        }
        return best.values.toList()
    }

    internal fun isLowerHex(value: String, length: Int): Boolean = value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Every relay failed to answer: the round is offline, not "these people have no profile". */
class ProfileRelaysUnreachableException : IOException("No profile relay could be reached.")

/**
 * Reads profiles from the same public relays the web client uses, never a Tower transport. One `REQ` for kind 0 per
 * relay and batch, closed at `EOSE`/`CLOSED` or the timeout; nothing is signed or published, and no credentials,
 * cookies or Tower data are sent. Frames larger than [MAX_FRAME_BYTES] end that relay's answer.
 */
class RelayProfileSource(
    relays: List<String> = RELAYS,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    client: OkHttpClient? = null,
) : ProfileSource {
    companion object {
        val RELAYS: List<String> = listOf("wss://relay.primal.net", "wss://nos.lol", "wss://purplepag.es")
        const val MAX_FRAME_BYTES = 512 * 1024
        /** Authors per `REQ` (the profile refresh batches at this size). */
        const val MAX_AUTHORS = 50

        /**
         * Per relay, including the WebSocket handshake: one public relay's handshake alone was measured at over 3 s,
         * so a 4 s budget often left only one relay answering.
         */
        val DEFAULT_TIMEOUT: Duration = 8.seconds

        /** Only `wss://` relays without embedded credentials are used. */
        internal fun acceptable(relay: String): Boolean {
            val uri = runCatching { URI(relay) }.getOrNull() ?: return false
            return uri.scheme == "wss" && uri.rawUserInfo == null && !uri.host.isNullOrEmpty()
        }
    }

    val relays: List<String> = relays.filter(::acceptable)

    private val client: OkHttpClient = (client ?: OkHttpClient()).newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun profiles(pubkeys: List<String>): List<ProfileRecord> {
        val requested = LinkedHashSet(pubkeys)
        if (requested.isEmpty()) return emptyList()
        val all = ArrayList<NostrEvent>()
        var answered = false
        for (batch in requested.chunked(MAX_AUTHORS)) {
            val results = coroutineScope { relays.map { relay -> async { query(relay, batch) } }.awaitAll() }
            if (results.any { it != null }) answered = true
            results.forEach { it?.let(all::addAll) }
        }
        // Every relay failed to answer: treat as offline, not as "these people have no profile".
        if (!answered) throw ProfileRelaysUnreachableException()
        return ProfileEventFilter.accept(all, requested, Instant.now())
    }

    /**
     * Events from one relay until its `EOSE`, `CLOSED` or the timeout (which also covers the connection handshake).
     * Null if the relay could not be reached, sent an oversized or broken stream, or did not answer in time.
     */
    internal suspend fun query(relay: String, authors: List<String>): List<NostrEvent>? {
        val subscription = "p" + UUID.randomUUID().toString().take(8).lowercase()
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
            put("authors", JsonArray(authors.map(::JsonPrimitive)))
            put("limit", authors.size * 2)
        }
        val request = JsonFields.encode(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subscription), filter)), sortKeys = false)
        val close = JsonFields.encode(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subscription))), sortKeys = false)
        val limit = authors.size * 4
        val outcome = CompletableDeferred<List<NostrEvent>?>()
        val events = ArrayList<NostrEvent>()
        val httpRequest = runCatching { Request.Builder().url(relay).build() }.getOrNull() ?: return null
        val socket = client.newWebSocket(httpRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(request)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (outcome.isCompleted) return
                if (text.length > MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
                    outcome.complete(null); return
                }
                val frame = RelayFrame.parse(text) ?: return
                if (frame.subscription != subscription) return
                when (frame) {
                    is RelayFrame.Event -> synchronized(events) {
                        events.add(frame.event)
                        if (events.size >= limit) outcome.complete(events.toList())
                    }
                    is RelayFrame.End -> outcome.complete(synchronized(events) { events.toList() })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outcome.complete(null)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                outcome.complete(null)
            }
        })
        return try {
            val result = withTimeoutOrNull(timeout) { outcome.await() }
            if (result != null) socket.send(close)
            result
        } finally {
            // Ends a connection that is still waiting; any later frame is ignored.
            outcome.complete(null)
            socket.close(1000, null)
        }
    }
}

/** One relay → client frame of interest (`EVENT`, `EOSE`, `CLOSED`). */
internal sealed interface RelayFrame {
    val subscription: String

    data class Event(override val subscription: String, val event: NostrEvent) : RelayFrame
    data class End(override val subscription: String) : RelayFrame

    companion object {
        fun parse(text: String): RelayFrame? {
            if (text.toByteArray(Charsets.UTF_8).size > RelayProfileSource.MAX_FRAME_BYTES) return null
            val array = JsonFields.parse(text) as? JsonArray ?: return null
            if (array.size < 2) return null
            val type = JsonFields.string(array[0]) ?: return null
            val subscription = JsonFields.string(array[1]) ?: return null
            return when (type) {
                "EVENT" -> {
                    val obj = array.getOrNull(2) as? JsonObject ?: return null
                    val event = runCatching { WireJson.decodeFromJsonElement(NostrEvent.serializer(), obj) }.getOrNull() ?: return null
                    Event(subscription, event)
                }
                "EOSE", "CLOSED" -> End(subscription)
                else -> null
            }
        }
    }
}
