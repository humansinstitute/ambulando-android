package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canonical Excalidraw board scenes, as the web client's board scene and board comment modules define them. A board
 * is a Tower File whose immutable versions hold this JSON. The working scene keeps every element verbatim (unknown
 * fields included, `isDeleted` tombstones, `version`/`versionNonce`), embedded image files by id and the board's
 * discussion (`wingman.comments`), so a save from this device never drops what another client wrote.
 *
 * Scenes are plain JSON objects. They are parsed and handled off the main thread only (inside [BoardController]).
 */
object BoardScene {
    const val CONTENT_TYPE = "application/vnd.excalidraw+json"
    const val FILE_EXTENSION = ".excalidraw"
    const val SOURCE = "wingman-ambulando"
    const val MAX_BYTES = 24 * 1024 * 1024
    const val MAX_ELEMENTS = 20_000
    const val MAX_FILES = 200
    const val MAX_FILE_BYTES = 6 * 1024 * 1024
    const val MAX_SUMMARY = 280
    const val MAX_TITLE = 200
    val IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/svg+xml")

    /** Board-level appState that belongs to the drawing; values equal to Excalidraw's defaults are omitted. */
    internal val appStateDefaults: List<Pair<String, JsonElement>> = listOf(
        "viewBackgroundColor" to JsonPrimitive("#ffffff"), "gridSize" to JsonPrimitive(20), "gridStep" to JsonPrimitive(5),
        "gridModeEnabled" to JsonPrimitive(false))

    sealed class SceneException(message: String) : Exception(message) {
        class Invalid(val detail: String) : SceneException("This board could not be read ($detail).") {
            override fun equals(other: Any?) = other is Invalid && other.detail == detail
            override fun hashCode() = detail.hashCode()
        }
        class TooLarge(val detail: String) : SceneException(detail) {
            override fun equals(other: Any?) = other is TooLarge && other.detail == detail
            override fun hashCode() = detail.hashCode()
        }
    }

    private fun invalid(detail: String): Nothing = throw SceneException.Invalid(detail)
    private val emptyObject = JsonObject(emptyMap())

    fun empty(): JsonObject = obj("type" to str("excalidraw"), "version" to JsonPrimitive(2), "source" to str(SOURCE),
        "elements" to JsonArray(emptyList()), "appState" to emptyObject, "files" to emptyObject)

    // MARK: Parse and normalise

    fun parse(bytes: ByteArray): JsonObject {
        if (bytes.size > MAX_BYTES) throw SceneException.TooLarge("This board is larger than the 24 MB limit.")
        val value = JsonFields.parse(bytes) ?: invalid("not JSON")
        return normalize(value)
    }

    /** Accepts a standard `.excalidraw` document, a bare `{elements, files}` scene or the `{scene: {...}}` wrapper. */
    fun normalize(input: JsonElement): JsonObject {
        var value = input as? JsonObject ?: invalid("not an object")
        val inner = value["scene"] as? JsonObject
        if (inner != null && inner["elements"] is JsonArray) {
            val merged = inner.toMutableMap()
            val wingman = value["wingman"] ?: inner["wingman"]
            if (wingman != null) merged["wingman"] = wingman else merged.remove("wingman")
            value = JsonObject(merged)
        }
        val rawElements = value["elements"] as? JsonArray ?: invalid("no elements array")
        val type = value["type"]
        if (type != null && JsonFields.string(type) != "excalidraw") invalid("type is not excalidraw")
        if (rawElements.size > MAX_ELEMENTS) throw SceneException.TooLarge("This board has too many elements.")
        val seen = HashSet<String>()
        val elements = rawElements.map { validateElement(it, seen) }
        val rawFiles = value["files"] as? JsonObject ?: emptyObject
        if (rawFiles.size > MAX_FILES) throw SceneException.TooLarge("This board has too many images.")
        val files = LinkedHashMap<String, JsonElement>()
        for ((id, file) in rawFiles) files[id] = validateFile(id, file)
        val scene = linkedMapOf<String, JsonElement>("type" to str("excalidraw"), "version" to JsonPrimitive(2),
            "source" to str(text(value["source"]).ifEmpty { SOURCE }), "elements" to JsonArray(elements),
            "appState" to pickAppState(value["appState"]), "files" to JsonObject(files))
        val wingman = value["wingman"] as? JsonObject
        if (wingman != null) {
            val metadata = pickWingman(wingman).toMutableMap()
            val comments = metadata["comments"] as? JsonArray
            if (comments != null) metadata["comments"] = JsonArray(followCommentAnchors(comments.map { it as JsonObject }, elements))
            scene["wingman"] = JsonObject(metadata)
        }
        return JsonObject(scene)
    }

    internal fun validateElement(value: JsonElement, seen: MutableSet<String>): JsonObject {
        val element = value as? JsonObject ?: invalid("element is not an object")
        val id = text(element["id"])
        if (id.isEmpty() || JsonFields.length(id) > 128) invalid("element id")
        if (!seen.add(id)) invalid("duplicate element $id")
        if (text(element["type"]).isEmpty()) invalid("element $id has no type")
        val version = safeInt(element["version"])
        if (version == null || version < 1) invalid("element $id version")
        val nonce = element["versionNonce"]
        if (nonce != null && nonce !is JsonNull && safeInt(nonce) == null) invalid("element $id versionNonce")
        val deleted = element["isDeleted"]
        if (deleted != null && !JsonFields.isBool(deleted)) invalid("element $id isDeleted")
        return element   // every field verbatim: unknown Excalidraw fields survive a save
    }

    internal fun validateFile(id: String, value: JsonElement): JsonObject {
        val file = (value as? JsonObject ?: invalid("file $id")).toMutableMap()
        if (text(file["id"]).isNotEmpty() && text(file["id"]) != id) invalid("file $id id mismatch")
        val mime = text(file["mimeType"]).lowercase()
        if (mime !in IMAGE_TYPES) invalid("file $id type")
        val dataURL = JsonFields.string(file["dataURL"]) ?: ""
        if (!dataURL.lowercase().startsWith("data:$mime")) invalid("file $id is not an embedded $mime")
        if (dataURL.toByteArray(Charsets.UTF_8).size > (MAX_FILE_BYTES * 4 + 2) / 3 + 128) throw SceneException.TooLarge("An image on this board is too large.")
        file["id"] = str(id); file["mimeType"] = str(mime); file["dataURL"] = str(dataURL)
        return JsonObject(file)
    }

    internal fun pickAppState(value: JsonElement?): JsonObject {
        val state = value as? JsonObject ?: return emptyObject
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, fallback) in appStateDefaults) {
            val v = state[key]
            if (v == null || v is JsonNull) continue
            if (!JsonFields.same(v, fallback)) out[key] = v
        }
        return JsonObject(out)
    }

    internal fun pickWingman(value: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        val summary = JsonFields.prefix(text(value["summary"]), MAX_SUMMARY)
        if (summary.isNotEmpty()) out["summary"] = str(summary)
        if (text(value["base_version_id"]).isNotEmpty()) out["base_version_id"] = str(text(value["base_version_id"]))
        value["comments"]?.let { out["comments"] = JsonArray(normalizeComments(it)) }
        return JsonObject(out)
    }

    // MARK: Serialise and compare

    /** Images referenced by any element, deleted ones included (needed to undelete or reconcile). */
    internal fun referencedFileIds(elements: List<JsonObject>): Set<String> = elements.map { text(it["fileId"]) }.filter { it.isNotEmpty() }.toSet()

    fun elements(scene: JsonObject): List<JsonObject> = (scene["elements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** Canonical bytes of one immutable version. Unreferenced images are pruned; deleted elements and their images kept. */
    fun serialize(scene: JsonObject, summary: String = "", baseVersionId: String = ""): ByteArray {
        val normalized = normalize(scene)
        val elements = elements(normalized)
        val referenced = referencedFileIds(elements)
        val files = (normalized["files"] as? JsonObject ?: emptyObject).filterKeys { it in referenced }
        val wingman = LinkedHashMap<String, JsonElement>()
        val trimmed = JsonFields.prefix(summary.trim(), MAX_SUMMARY)
        if (trimmed.isNotEmpty()) wingman["summary"] = str(trimmed)
        if (baseVersionId.isNotEmpty()) wingman["base_version_id"] = str(baseVersionId)
        if ((normalized["wingman"] as? JsonObject)?.get("comments") != null) {
            wingman["comments"] = JsonArray(followCommentAnchors(comments(normalized), elements))
        }
        val document = linkedMapOf<String, JsonElement>("type" to str("excalidraw"), "version" to JsonPrimitive(2), "source" to str(SOURCE),
            "elements" to JsonArray(elements), "appState" to (normalized["appState"] ?: emptyObject), "files" to JsonObject(files))
        if (wingman.isNotEmpty()) document["wingman"] = JsonObject(wingman)
        val bytes = JsonFields.encodeBytes(JsonObject(document))
        if (bytes.size > MAX_BYTES) throw SceneException.TooLarge("This board is larger than the 24 MB limit. Remove or shrink some images.")
        return bytes
    }

    /**
     * Change identity for dirty detection: element stamps, referenced images, board appState and discussion.
     * Selection, zoom and scroll never change it.
     */
    fun fingerprint(scene: JsonObject): String {
        val elements = elements(scene)
        val stamps = elements.map { "${text(it["id"])}:${stamp(it)}:${if (JsonFields.isTrue(it["isDeleted"])) 1 else 0}" }.sorted()
        val referenced = referencedFileIds(elements)
        val files = (scene["files"] as? JsonObject ?: emptyObject).keys.filter { it in referenced }.sorted()
        val parts = JsonArray(listOf(JsonArray(stamps.map(::str)), JsonArray(files.map(::str)), pickAppState(scene["appState"]), JsonArray(comments(scene))))
        return Sha256Hex.of(JsonFields.encodeBytes(parts))
    }

    internal fun stamp(element: JsonObject?): String {
        if (element == null) return ""
        return "${safeInt(element["version"]) ?: 0}:${safeInt(element["versionNonce"]) ?: 0}"
    }

    /** A rebased scene and how many changes were made on both sides (resolved toward the draft). */
    data class Rebase(val scene: JsonObject, val conflicts: Int)

    /**
     * Three-way rebase of a draft onto a newer saved version by element id. Elements the draft changed since `base`
     * win; otherwise the newer version wins. Elements changed on both sides are counted as conflicts and resolved
     * toward the draft, so no local edit is dropped.
     */
    fun rebase(base: JsonObject, draft: JsonObject, latest: JsonObject): Rebase {
        val before = index(elements(base)); val mine = index(elements(draft)); val theirs = index(elements(latest))
        var conflicts = 0
        val order = elements(latest).map { text(it["id"]) }.toMutableList()
        for (element in elements(draft)) if (theirs[text(element["id"])] == null) order.add(text(element["id"]))
        val merged = order.mapNotNull { id ->
            val b = before[id]; val m = mine[id]; val t = theirs[id]
            val mineChanged = m != null && stamp(m) != stamp(b)
            val theirsChanged = t != null && stamp(t) != stamp(b)
            if (mineChanged && theirsChanged && stamp(m) != stamp(t)) conflicts += 1
            if (mineChanged) m else t ?: m
        }
        val draftAppChanged = !JsonFields.same(pickAppState(draft["appState"]), pickAppState(base["appState"]))
        val files = (latest["files"] as? JsonObject ?: emptyObject) + (draft["files"] as? JsonObject ?: emptyObject)
        val discussion = mergeComments(base, draft, latest)
        conflicts += discussion.second
        val scene = obj("type" to str("excalidraw"), "version" to JsonPrimitive(2), "source" to str(SOURCE), "elements" to JsonArray(merged),
            "appState" to if (draftAppChanged) pickAppState(draft["appState"]) else pickAppState(latest["appState"]),
            "files" to JsonObject(files), "wingman" to obj("comments" to JsonArray(followCommentAnchors(discussion.first, merged))))
        return Rebase(scene, conflicts)
    }

    /**
     * An older version as a draft on top of `latest`: every element is re-stamped above the newest known version, and
     * elements only in `latest` become tombstones. Later discussion is kept.
     */
    fun restore(target: JsonObject, latest: JsonObject, nowMillis: Long = System.currentTimeMillis(), random: Random = Random.Default): JsonObject {
        val latestById = index(elements(latest))
        val targetIds = elements(target).map { text(it["id"]) }.toSet()
        fun restamp(element: JsonObject, previous: JsonObject?): MutableMap<String, JsonElement> {
            val out = element.toMutableMap()
            out["version"] = JsonPrimitive(maxOf(safeInt(element["version"]) ?: 1, safeInt(previous?.get("version")) ?: 0) + 1)
            out["versionNonce"] = JsonPrimitive(random.nextLong(0, Int.MAX_VALUE.toLong() + 1))
            out["updated"] = JsonPrimitive(nowMillis)
            return out
        }
        val restored = elements(target).map { JsonObject(restamp(it, latestById[text(it["id"])])) }.toMutableList()
        for (element in elements(latest)) {
            if (text(element["id"]) in targetIds) continue
            if (JsonFields.isTrue(element["isDeleted"])) restored.add(element)
            else restored.add(JsonObject(restamp(element, element).also { it["isDeleted"] = JsonPrimitive(true) }))
        }
        val discussion = mergeComments(emptyObject, latest, target)
        val files = (latest["files"] as? JsonObject ?: emptyObject) + (target["files"] as? JsonObject ?: emptyObject)
        return obj("type" to str("excalidraw"), "version" to JsonPrimitive(2), "source" to str(SOURCE), "elements" to JsonArray(restored),
            "appState" to pickAppState(target["appState"]), "files" to JsonObject(files),
            "wingman" to obj("comments" to JsonArray(followCommentAnchors(discussion.first, restored))))
    }

    private fun index(elements: List<JsonObject>): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (e in elements) out[text(e["id"])] = e   // last one wins
        return out
    }

    // MARK: Discussion

    const val MAX_THREADS = 1000
    const val MAX_MESSAGES = 2000
    const val MAX_COMMENT_BODY = 12_000

    fun comments(scene: JsonObject): List<JsonObject> =
        ((scene["wingman"] as? JsonObject)?.get("comments") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun withComments(scene: JsonObject, comments: List<JsonObject>): JsonObject {
        val out = scene.toMutableMap()
        val wingman = (scene["wingman"] as? JsonObject ?: emptyObject).toMutableMap()
        wingman["comments"] = JsonArray(normalizeComments(JsonArray(comments)))
        out["wingman"] = JsonObject(wingman)
        return JsonObject(out)
    }

    private val commentIdChars = Regex("^[A-Za-z0-9_-]{1,128}$")

    internal fun commentId(value: JsonElement?): Boolean = JsonFields.string(value)?.let { commentIdChars.matches(it) } ?: false

    internal fun finite(value: JsonElement?): Double? = JsonFields.finite(value)?.takeIf { abs(it) < 1e9 }

    internal fun isNpub(value: JsonElement?): Boolean {
        val s = JsonFields.string(value) ?: return false
        if (!s.startsWith("npub1") || s.length !in 6..105) return false
        return s.drop(5).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }

    fun normalizeComments(value: JsonElement): List<JsonObject> {
        val threads = value as? JsonArray
        if (threads == null || threads.size > MAX_THREADS) invalid("comment threads")
        val ids = HashSet<String>()
        return threads.map { raw ->
            val thread = raw as? JsonObject
            if (thread == null || !commentId(thread["id"])) invalid("comment id")
            val id = JsonFields.string(thread["id"])!!
            if (!ids.add(id)) invalid("comment id")
            val a = thread["anchor"] as? JsonObject ?: invalid("comment anchor")
            val x = finite(a["x"]) ?: invalid("comment anchor")
            val y = finite(a["y"]) ?: invalid("comment anchor")
            val anchor = linkedMapOf<String, JsonElement>("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))
            val element = a["element_id"]
            if (element != null && element !is JsonNull) {
                val u = finite(a["u"]); val v = finite(a["v"])
                if (!commentId(element) || u == null || v == null) invalid("comment anchor")
                anchor["element_id"] = element; anchor["u"] = JsonPrimitive(u); anchor["v"] = JsonPrimitive(v)
            }
            val resolved = thread["resolved"]
            val messages = thread["messages"] as? JsonArray
            if (resolved == null || !JsonFields.isBool(resolved) || messages == null || messages.isEmpty() || messages.size > MAX_MESSAGES) invalid("comment thread")
            val normalized = messages.map { rawMessage ->
                val m = rawMessage as? JsonObject ?: invalid("comment message")
                val mid = JsonFields.string(m["id"])
                val body = JsonFields.string(m["body"])
                val created = JsonFields.string(m["created_at"])
                if (!commentId(m["id"]) || mid == null || mid in ids || body == null || body.isBlank() || JsonFields.length(body) > MAX_COMMENT_BODY ||
                    !isNpub(m["author_npub"]) || created == null || date(created) == null) invalid("comment message")
                ids.add(mid)
                obj("id" to str(mid), "body" to str(body), "author_npub" to m.getValue("author_npub"), "created_at" to str(created))
            }
            obj("id" to str(id), "anchor" to JsonObject(anchor), "resolved" to resolved, "messages" to JsonArray(normalized))
        }
    }

    /** ISO 8601 internet date-time, with or without fractional seconds. */
    internal fun date(value: String): Instant? = runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()

    private fun rotate(x: Double, y: Double, angle: Double): Pair<Double, Double> =
        (x * cos(angle) - y * sin(angle)) to (x * sin(angle) + y * cos(angle))

    private fun num(v: JsonElement?): Double = JsonFields.finite(v) ?: 0.0

    /** Pins follow their shape: fallback coordinates are updated even when the shape has just become a tombstone. */
    internal fun followCommentAnchors(comments: List<JsonObject>, elements: List<JsonObject>): List<JsonObject> {
        val byId = index(elements)
        return comments.map { thread ->
            val anchor = (thread["anchor"] as? JsonObject)?.toMutableMap() ?: return@map thread
            val element = byId[text(anchor["element_id"])] ?: return@map thread
            val (px, py) = rotate((num(anchor["u"]) - 0.5) * num(element["width"]), (num(anchor["v"]) - 0.5) * num(element["height"]), num(element["angle"]))
            anchor["x"] = JsonPrimitive(num(element["x"]) + num(element["width"]) / 2 + px)
            anchor["y"] = JsonPrimitive(num(element["y"]) + num(element["height"]) / 2 + py)
            JsonObject(thread + ("anchor" to JsonObject(anchor)))
        }
    }

    private val towerTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * One discussion change from the editor, attributed to the signed-in viewer (the file version authenticates the
     * publisher; the author label inside the JSON is attribution).
     */
    fun applyCommentOperation(scene: JsonObject, operation: JsonObject, author: String, now: Instant = Instant.now(),
                              newId: () -> String = { UUID.randomUUID().toString().lowercase() }): JsonObject {
        if (!isNpub(JsonPrimitive(author))) invalid("an authenticated board author is required")
        val threads = normalizeComments(JsonArray(comments(scene))).toMutableList()
        fun message() = obj("id" to str(newId()), "body" to str(text(operation["body"])), "author_npub" to str(author),
            "created_at" to str(towerTime.format(now)))
        fun threadIndex(): Int = threads.indexOfFirst { JsonFields.string(it["id"]) != null && JsonFields.string(it["id"]) == JsonFields.string(operation["thread_id"]) }
        when (JsonFields.string(operation["type"])) {
            "create" -> {
                val id = if (commentId(operation["id"])) JsonFields.string(operation["id"])!! else newId()
                threads.add(obj("id" to str(id), "anchor" to (operation["anchor"] ?: emptyObject), "resolved" to JsonPrimitive(false),
                    "messages" to JsonArray(listOf(message()))))
            }
            "reply" -> {
                val i = threadIndex().takeIf { it >= 0 } ?: invalid("comment thread is unavailable")
                val existing = (threads[i]["messages"] as? JsonArray)?.toList() ?: emptyList()
                threads[i] = JsonObject(threads[i] + ("messages" to JsonArray(existing + message())))
            }
            "resolve" -> {
                val i = threadIndex()
                val resolved = operation["resolved"]
                if (i < 0 || resolved == null || !JsonFields.isBool(resolved)) invalid("comment operation")
                threads[i] = JsonObject(threads[i] + ("resolved" to resolved))
            }
            else -> invalid("comment operation")
        }
        return withComments(scene, threads)
    }

    /** Three-way merge of the discussion: messages union by id, `resolved`/`anchor` changes from the draft win. */
    internal fun mergeComments(base: JsonObject, draft: JsonObject, latest: JsonObject): Pair<List<JsonObject>, Int> {
        fun byId(list: List<JsonObject>): Map<String, JsonObject> {
            val out = LinkedHashMap<String, JsonObject>()
            for (t in list) out.putIfAbsent(text(t["id"]), t)   // first one wins
            return out
        }
        val before = byId(comments(base)); val mine = byId(comments(draft))
        val theirsList = comments(latest); val theirs = byId(theirsList)
        val order = theirsList.map { text(it["id"]) }.toMutableList()
        for (t in comments(draft)) if (theirs[text(t["id"])] == null) order.add(text(t["id"]))
        var conflicts = 0
        val merged = order.mapNotNull { id ->
            val a = before[id]; val b = mine[id]; val c = theirs[id]
            if (b == null || c == null) return@mapNotNull b ?: c
            val messages = ((c["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()).toMutableList()
            val known = HashMap<String, Int>()
            messages.forEachIndexed { i, m -> known.putIfAbsent(text(m["id"]), i) }
            for (message in (b["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()) {
                val mid = text(message["id"])
                val at = known[mid]
                if (at != null) { if (!JsonFields.same(messages[at], message)) conflicts += 1; continue }
                known[mid] = messages.size
                messages.add(message)
            }
            val out = c.toMutableMap()
            out["messages"] = JsonArray(messages)
            for (field in listOf("resolved", "anchor")) {
                if (JsonFields.same(b[field], a?.get(field))) continue
                if (!JsonFields.same(c[field], a?.get(field)) && !JsonFields.same(c[field], b[field])) conflicts += 1
                val value = b[field]
                if (value != null) out[field] = value else out.remove(field)
            }
            JsonObject(out)
        }
        val normalized = runCatching { normalizeComments(JsonArray(merged)) }.getOrDefault(merged)
        return normalized to conflicts
    }

    // MARK: Names

    private val forbiddenFileChars = Regex("[\\\\/:*?\"<>|]+")
    private val spaces = Regex("\\s+")

    fun fileName(title: String): String {
        val printable = title.trim().map { if (it.code < 32) ' ' else it }.joinToString("")
        var base = printable.replace(forbiddenFileChars, " ").replace(spaces, " ").trim()
        base = JsonFields.prefix(base, 120)
        if (base.isEmpty()) base = "Board"
        return if (base.lowercase().endsWith(FILE_EXTENSION)) base else base + FILE_EXTENSION
    }

    // MARK: Values

    internal fun text(value: JsonElement?): String = (JsonFields.string(value) ?: "").trim()

    internal fun safeInt(value: JsonElement?): Long? = JsonFields.safeInt(value)

    private fun str(value: String) = JsonPrimitive(value)

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))
}
