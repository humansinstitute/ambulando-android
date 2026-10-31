package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature
import studio.otherstuff.ambulando.core.crypto.NIP98
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

// Shared pieces of the sample-data stand-ins for Tower. Nothing here is used by production code paths: the sample towers
// exist for deterministic tests and the clearly labelled sample-data mode of debug builds, and are never evidence of
// live Tower behaviour.

/** One mutable JSON object row (values are String, Long, Double, Boolean, null, nested maps and lists). */
typealias Row = MutableMap<String, Any?>

/** Status and body of a sample reply: a JSON-compatible value, or raw [ByteArray] bytes. */
typealias Reply = Pair<Int, Any?>

/** A fault injected into the next request whose path contains (or ends with) the match string. */
sealed class SampleFault {
    /** The request never reaches Tower (no network). */
    data object Offline : SampleFault()

    /** Tower commits the request but the response is lost (the client must replay). */
    data object DropResponseAfterCommit : SampleFault()

    /** Tower answers this status with `{code, error}` = [code]. */
    data class Status(val status: Int, val code: String) : SampleFault()
}

/** Simulated loss of network: thrown like OkHttp's connect failure, so the real retry classification runs. */
class SampleOfflineException : ConnectException("Sample data: simulated offline, nothing was sent.")

/** Simulated connection loss after Tower committed a request. */
class SampleConnectionLostException : SocketException("Sample data: the connection was lost after the request was committed.")

/** Synthetic hosts and constants of sample-data mode. Every host is under the reserved `.invalid` TLD. */
object SampleHosts {
    const val ORIGIN = "https://sample.tower.invalid"
    const val OBJECTS = "objects.sample.invalid"
    const val OBJECTS_LAB = "objects-lab.sample.invalid"
    const val BROWSER = "https://browser.sample.invalid"
    /** The synthetic Autopilot origin of managed-app activation (catalog and install-intent processing). */
    const val AUTOPILOT = "https://autopilot.sample.invalid"
    const val AVATARS = "https://avatars.sample.invalid/"
}

internal fun newUUID(): String = UUID.randomUUID().toString().lowercase()

internal fun errorReply(status: Int, code: String, extra: Map<String, Any?> = emptyMap()): Reply =
    status to (linkedMapOf<String, Any?>("code" to code, "error" to code) + extra.filterKeys { it != "code" && it != "error" })

internal fun validationError(message: String): Reply =
    errorReply(400, "validation_error", mapOf("details" to mapOf("fields" to listOf(mapOf("message" to message)))))

// MARK: JSON values

internal object SampleJson {
    /** Kotlin value of a JSON element: maps and lists are mutable copies; integral numbers are Long, others Double. */
    fun value(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.entries.associateTo(LinkedHashMap()) { it.key to value(it.value) }
        is JsonArray -> element.mapTo(ArrayList()) { value(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull()
        }
    }

    /** A request body that is a JSON object, else an empty row (as the Swift sample reads `?? [:]`). */
    fun objectOf(bytes: ByteArray?): Row {
        if (bytes == null || bytes.isEmpty()) return LinkedHashMap()
        val parsed = runCatching { TowerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return (parsed?.let { value(it) } as? Row) ?: LinkedHashMap()
    }

    fun encode(value: Any?): ByteArray = toJsonElement(value).toString().toByteArray(Charsets.UTF_8)

    /** A deep copy (rows handed out or stored are never shared). */
    fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap<String, Any?>()) { it.key.toString() to copy(it.value) }
        is List<*> -> value.mapTo(ArrayList()) { copy(it) }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    fun row(value: Any?): Row? = (value as? Map<*, *>)?.let { copy(it) as Row }

    fun jsonObject(value: Any?): JsonObject? = toJsonElement(value) as? JsonObject
}

/** `as? Int` over JSON numbers: integral Long/Int/Double values within range. */
internal fun Any?.asInt(): Int? = when (this) {
    is Int -> this
    is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
    is Double -> if (!isNaN() && Math.rint(this) == this && this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) toInt() else null
    else -> null
}

internal fun Any?.asString(): String? = this as? String

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asList(): List<Any?>? = this as? List<Any?>

/** A list of strings, or null when any element is not a string. */
internal fun Any?.asStrings(): List<String>? = (this as? List<*>)?.map { it as? String ?: return null }

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimWhitespace(): String = trim { it.isWhitespace() }

// MARK: Persistent state

/**
 * The JSON state of one sample tower: named tables of rows plus loose values, optionally persisted to [file] after every
 * change (so sample data survives an app relaunch). Callers hold their tower's lock.
 */
internal class SampleState(private val file: File?, collections: List<String>) {
    val data: MutableMap<String, Any?> = load() ?: LinkedHashMap()

    init {
        for (name in collections) if (data[name] !is Map<*, *>) data[name] = LinkedHashMap<String, Any?>()
    }

    private fun load(): MutableMap<String, Any?>? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching { SampleJson.objectOf(f.readBytes()) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tableMap(name: String): MutableMap<String, Any?> =
        (data[name] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { data[name] = it }

    /** A copy of one row. */
    fun row(name: String, id: String): Row? = SampleJson.row(tableMap(name)[id])

    /** Copies of every row, in insertion order. */
    fun rows(name: String): List<Row> = tableMap(name).values.mapNotNull { SampleJson.row(it) }

    fun ids(name: String): List<String> = tableMap(name).keys.toList()

    fun count(name: String): Int = tableMap(name).size

    /** Stores a copy of [row] (or removes the id when null). */
    fun put(name: String, id: String, row: Map<String, Any?>?) {
        val table = tableMap(name)
        if (row == null) table.remove(id) else table[id] = SampleJson.copy(row)
    }

    fun list(name: String): List<String> = data[name].asStrings() ?: emptyList()

    fun setList(name: String, values: List<String>) { data[name] = ArrayList(values) }

    /** Monotonic counter behind the deterministic sample timestamps. */
    var clock: Int
        get() = data["clock"].asInt() ?: 0
        set(value) { data["clock"] = value.toLong() }

    fun persist() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(SampleJson.encode(data))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }
}

// MARK: Requests

/** Parsed request URL: host, decoded path, and query lookups. */
internal class SampleURL(val url: HttpUrl) {
    val host: String get() = url.host
    val path: String get() = "/" + url.pathSegments.joinToString("/")
    fun query(name: String): String? = url.queryParameter(name)
    fun queryValues(name: String): List<String> = url.queryParameterValues(name).filterNotNull()

    companion object {
        fun of(request: HttpRequest): SampleURL? = request.url.toHttpUrlOrNull()?.let { SampleURL(it) }
    }
}

/** Index-cursor paging used by the sample list routes (`idx:<n>`), with Tower's `limit ≤ 200`. */
internal fun indexPage(rows: List<Any?>, key: String, url: SampleURL, extra: Map<String, Any?> = emptyMap(), hasMore: Boolean = true): Reply {
    val limit = minOf(200, url.query("limit")?.toIntOrNull() ?: 50).coerceAtLeast(1)
    val start = url.query("cursor")?.removePrefix("idx:")?.toIntOrNull() ?: 0
    val slice = rows.drop(start).take(limit)
    val more = start + slice.size < rows.size
    val out = linkedMapOf<String, Any?>(key to slice, "next_cursor" to (if (more) "idx:${start + slice.size}" else null))
    if (hasMore) out["has_more"] = more
    for ((k, v) in extra) out.putIfAbsent(k, v)
    return 200 to out
}

// MARK: NIP-98

/**
 * Tower's NIP-98 check for every signed route: a kind 27235 event with a valid BIP-340 signature, `u` equal to the exact
 * request URL, `method` equal to the request method, `created_at` within [WINDOW_SECONDS] of now, and `payload` equal to
 * the SHA-256 of the exact body bytes (absent, or the empty-body hash, when there is no body).
 */
class SampleAuth(private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    sealed class Result {
        data class Valid(val pubkey: String, val npub: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun verify(request: HttpRequest): Result {
        val header = request.header("Authorization") ?: return Result.Invalid("missing Authorization header")
        if (!header.startsWith("Nostr ")) return Result.Invalid("Authorization scheme is not Nostr")
        val json = runCatching { String(Base64.getDecoder().decode(header.removePrefix("Nostr ").trim()), Charsets.UTF_8) }.getOrNull()
            ?: return Result.Invalid("Authorization is not base64")
        val event = runCatching { WireJson.decodeFromString(NostrEvent.serializer(), json) }.getOrNull()
            ?: return Result.Invalid("Authorization is not a Nostr event")
        if (event.kind != NIP98.KIND) return Result.Invalid("event kind ${event.kind} is not ${NIP98.KIND}")
        if (!SignatureVerifier.verify(event)) return Result.Invalid("event id or signature is invalid")
        if (abs(now() - event.createdAt) > WINDOW_SECONDS) return Result.Invalid("event created_at is outside the $WINDOW_SECONDS s window")
        val u = event.tags.filter { it.firstOrNull() == "u" }
        if (u.size != 1 || u[0].getOrNull(1) != request.url) return Result.Invalid("u tag does not equal the request URL")
        val method = event.tags.filter { it.firstOrNull() == "method" }
        if (method.size != 1 || method[0].getOrNull(1) != request.method.uppercase()) return Result.Invalid("method tag does not match")
        val payloads = event.tags.filter { it.firstOrNull() == "payload" }
        if (payloads.size > 1) return Result.Invalid("more than one payload tag")
        val payload = payloads.firstOrNull()?.getOrNull(1)
        val body = request.body ?: ByteArray(0)
        if (body.isNotEmpty()) {
            if (payload == null) return Result.Invalid("payload tag missing for a request body")
            if (payload.lowercase() != Sha256Hex.of(body)) return Result.Invalid("payload tag does not match the body SHA-256")
        } else if (payload != null && payload.lowercase() != Sha256Hex.of(body)) {
            return Result.Invalid("payload tag does not match the empty body")
        }
        val npub = NostrKeys.npub(event.pubkey) ?: return Result.Invalid("invalid pubkey")
        return Result.Valid(event.pubkey.lowercase(), npub)
    }

    companion object {
        /** Tower accepts NIP-98 events within 300 s of `created_at` (and does not consume event ids). */
        const val WINDOW_SECONDS = 300L

        fun unauthorized(reason: String): Reply = 401 to linkedMapOf("code" to "unauthorized", "error" to "invalid NIP-98: $reason")
    }
}

/**
 * Tower's kind 33358 message-instruction check (`flightdeck-pg-message-signatures.ts`): the event is validly signed by the
 * request's signer, its content equals the trimmed body, `body_sha256` is the SHA-256 of that body, the protocol,
 * workspace and channel tags match, and `thread_id` matches for replies (plus `message_id`/`revision` for edits).
 * Returns null when valid, else the field message Tower reports in `validation_error`.
 */
internal object SampleMessageSignature {
    fun check(
        request: Map<String, Any?>, body: String, workspaceId: String, channelId: String, threadId: String?, signerNpub: String,
        messageId: String? = null, revision: Int? = null,
    ): String? {
        val wrapper = request["message_signature"].asMap() ?: return "message_signature is required"
        val eventObject = SampleJson.jsonObject(wrapper["nostr_event"]) ?: return "message_signature.nostr_event is required"
        val event = runCatching { WireJson.decodeFromJsonElement(NostrEvent.serializer(), eventObject) }.getOrNull()
            ?: return "message_signature.nostr_event is not a Nostr event"
        if (event.kind != MessageInstructionSignature.KIND) return "message_signature kind must be ${MessageInstructionSignature.KIND}"
        if (!SignatureVerifier.verify(event)) return "message_signature is not validly signed"
        if (event.content != body) return "message_signature does not match body"
        val sha = Sha256Hex.of(body)
        fun tag(name: String): String? = event.tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
        if (tag("protocol") != MessageInstructionSignature.PROTOCOL_NAME) return "message_signature protocol is wrong"
        if (tag("body_sha256") != sha || wrapper["body_sha256"].asString()?.let { it != sha } == true) return "message_signature body_sha256 does not match body"
        if (tag("workspace_id")?.lowercase() != workspaceId) return "message_signature workspace_id does not match"
        if (tag("channel_id")?.lowercase() != channelId) return "message_signature channel_id does not match"
        if (threadId != null && tag("thread_id")?.lowercase() != threadId) return "message_signature thread_id does not match"
        if (messageId != null && tag("message_id")?.lowercase() != messageId) return "message_signature message_id does not match the revision"
        if (revision != null && tag("revision") != revision.toString()) return "message_signature revision does not match the revision"
        val signer = NostrKeys.npub(event.pubkey) ?: return "message_signature pubkey is invalid"
        if (wrapper["signer_npub"].asString()?.let { it != signer } == true) return "message_signature signer_npub does not match the event"
        if (signer != signerNpub) return "message_signature signer is not the authenticated actor"
        return null
    }
}

/**
 * A committed change that Tower would publish as an outbox event (`serializeFlightDeckPgEvent`). Component towers report
 * them through `onChange`; [SampleTower] appends them to its event log so `/events` and the stream deliver them.
 */
data class SampleChange(
    val entityType: String,
    val entityId: String,
    val operation: String,
    val channelId: String? = null,
    val scopeId: String? = null,
    val rowVersion: Long? = null,
    /** Parent ids (`thread_id`, `task_id`, `doc_id`, `target_id`, `canvas_id`, `component_id`). */
    val payload: Map<String, String> = emptyMap(),
)
