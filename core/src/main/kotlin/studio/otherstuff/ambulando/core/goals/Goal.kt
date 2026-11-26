package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A personal goal: name, description, optional date-only start/target, an ordered checkpoint timeline and links to
 * existing records. Stored as the JSON content of one ordinary File (`application/vnd.ambulando.goal+json`).
 * Links are references only: titles, locations and access are always read from the source record, never copied here.
 * Progress is checkpoints reached; nothing is inferred from linked tasks.
 */
data class Goal(
    val name: String,
    val description: String,
    val startDate: String? = null,
    val targetDate: String? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val checkpoints: List<GoalCheckpoint> = emptyList(),
    val links: List<GoalLink> = emptyList(),
    /** Unknown top-level fields from a newer writer, written back untouched. */
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val reachedCount: Int get() = checkpoints.count { it.reached }

    /** The first checkpoint not yet reached while the goal is active; shown as "Next". */
    val nextCheckpointId: String? get() = if (status == GoalStatus.ACTIVE) checkpoints.firstOrNull { !it.reached }?.id else null

    fun encode(): String = Json.encodeToString(JsonObject.serializer(), toJson())

    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> if (k !in KNOWN) put(k, v) }
        put("schema", SCHEMA); put("schema_version", VERSION)
        put("name", name); put("description", description)
        startDate?.let { put("start_date", it) }; targetDate?.let { put("target_date", it) }
        put("status", status.wire)
        put("checkpoints", JsonArray(checkpoints.map { it.toJson() }))
        put("links", JsonArray(links.map { it.toJson() }))
    }

    companion object {
        const val SCHEMA = "ambulando.goal"
        const val VERSION = 1
        const val MIME = "application/vnd.ambulando.goal+json"
        const val MAX_BYTES = 256 * 1024
        const val MAX_NAME = 120
        const val MAX_DESCRIPTION = 2000
        const val MAX_CHECKPOINTS = 100
        const val MAX_LINKS = 500
        private val KNOWN = setOf("schema", "schema_version", "name", "description", "start_date", "target_date", "status", "checkpoints", "links")

        /** Reads stored bytes. An unsupported or malformed goal is refused, never partially shown or rewritten. */
        fun parse(bytes: ByteArray): Goal {
            if (bytes.size > MAX_BYTES) throw GoalError.Invalid("This goal is too large to read.")
            val root = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject } catch (_: Exception) { null }
                ?: throw GoalError.Invalid("This goal could not be read.")
            if (root.strict("schema") != SCHEMA) throw GoalError.Invalid("This is not a goal.")
            if ((root["schema_version"] as? JsonPrimitive)?.intOrNull != VERSION) throw GoalError.Unsupported
            val goal = Goal(
                name = root.strict("name") ?: "", description = root.strict("description") ?: "",
                startDate = root.strict("start_date"), targetDate = root.strict("target_date"),
                status = GoalStatus.of(root.strict("status")) ?: throw GoalError.Invalid("Unknown goal status."),
                checkpoints = root.strictArr("checkpoints").map { GoalCheckpoint.parse(it) },
                links = root.strictArr("links").map { GoalLink.parse(it) },
                extra = JsonObject(root.filterKeys { it !in KNOWN }),
            )
            return goal.validated()
        }

        fun create(name: String, description: String, startDate: String? = null, targetDate: String? = null): Goal =
            Goal(name.trim(), description.trim(), startDate.blankToNull(), targetDate.blankToNull()).validated()

        fun parseDate(value: String): LocalDate = try {
            LocalDate.parse(value).also { if (it.toString() != value) throw GoalError.Invalid("Dates use the form 2026-12-18.") }
        } catch (_: DateTimeParseException) { throw GoalError.Invalid("Dates use the form 2026-12-18.") }
    }

    /** Throws [GoalError.Invalid] with a user-readable message when a rule is broken. */
    fun validated(): Goal {
        if (name.isBlank()) throw GoalError.Invalid("A goal needs a name.")
        if (description.isBlank()) throw GoalError.Invalid("A goal needs a description.")
        if (name.length > MAX_NAME) throw GoalError.Invalid("The name is too long.")
        if (description.length > MAX_DESCRIPTION) throw GoalError.Invalid("The description is too long.")
        val start = startDate?.let(::parseDate); val target = targetDate?.let(::parseDate)
        if (start != null && target != null && target.isBefore(start)) throw GoalError.Invalid("The target date is before the start date.")
        if (checkpoints.size > MAX_CHECKPOINTS) throw GoalError.Invalid("Too many checkpoints.")
        if (checkpoints.map { it.id }.toSet().size != checkpoints.size) throw GoalError.Invalid("Checkpoint identities repeat.")
        checkpoints.forEach { it.validate() }
        if (links.size > MAX_LINKS) throw GoalError.Invalid("Too many linked records.")
        if (links.map { it.type to it.recordId }.toSet().size != links.size) throw GoalError.Invalid("A record is linked twice.")
        links.forEach { it.validate() }
        return this
    }
}

enum class GoalStatus(val wire: String) {
    ACTIVE("active"), COMPLETED("completed"), ARCHIVED("archived");
    companion object { fun of(wire: String?): GoalStatus? = entries.firstOrNull { it.wire == wire } }
}

data class GoalCheckpoint(
    val id: String,
    val title: String,
    val date: String? = null,
    val description: String? = null,
    val reached: Boolean = false,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    internal fun validate() {
        if (!ID.matches(id)) throw GoalError.Invalid("A checkpoint identity is invalid.")
        if (title.isBlank()) throw GoalError.Invalid("A checkpoint needs a title.")
        if (title.length > 120) throw GoalError.Invalid("A checkpoint title is too long.")
        if ((description?.length ?: 0) > 500) throw GoalError.Invalid("A checkpoint description is too long.")
        date?.let(Goal::parseDate)
    }

    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> if (k !in KNOWN) put(k, v) }
        put("id", id); put("title", title)
        date?.let { put("date", it) }; description?.let { put("description", it) }
        put("reached", reached)
    }

    companion object {
        private val KNOWN = setOf("id", "title", "date", "description", "reached")
        internal val ID = Regex("[A-Za-z0-9_-]{1,64}")
        fun parse(value: JsonElement): GoalCheckpoint {
            val o = value as? JsonObject ?: throw GoalError.Invalid("A checkpoint could not be read.")
            return GoalCheckpoint(
                id = o.strict("id") ?: "", title = o.strict("title") ?: "", date = o.strict("date"), description = o.strict("description"),
                reached = o.strictBool("reached") ?: false, extra = JsonObject(o.filterKeys { it !in KNOWN }),
            )
        }
    }
}

enum class GoalLinkType(val wire: String) {
    CHAT("chat"), TASK("task"), DOC("doc"), FILE("file");
    companion object { fun of(wire: String?): GoalLinkType? = entries.firstOrNull { it.wire == wire } }
}

/** A reference to an existing record. Identity is (type, recordId); [channelId] only helps find a chat's channel. */
data class GoalLink(
    val type: GoalLinkType,
    val recordId: String,
    val channelId: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    internal fun validate() {
        if (!UUID.matches(recordId)) throw GoalError.Invalid("A linked record identity is invalid.")
        if (channelId != null && !UUID.matches(channelId)) throw GoalError.Invalid("A linked channel identity is invalid.")
    }

    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> if (k !in KNOWN) put(k, v) }
        put("type", type.wire); put("record_id", recordId); channelId?.let { put("channel_id", it) }
    }

    companion object {
        private val KNOWN = setOf("type", "record_id", "channel_id")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun parse(value: JsonElement): GoalLink {
            val o = value as? JsonObject ?: throw GoalError.Invalid("A link could not be read.")
            return GoalLink(
                GoalLinkType.of(o.strict("type")) ?: throw GoalError.Invalid("A link has an unknown type."),
                o.strict("record_id")?.lowercase() ?: "", o.strict("channel_id")?.lowercase(), JsonObject(o.filterKeys { it !in KNOWN }),
            )
        }
    }
}

sealed class GoalError(message: String) : Exception(message, null, false, false) {
    class Invalid(message: String) : GoalError(message)
    /** A newer app version wrote this goal; it is left exactly as stored. */
    data object Unsupported : GoalError("This goal was saved by a newer version of the app. Update to open it.")
    /** An edit's expectation no longer holds because the goal changed elsewhere. */
    class Conflict(message: String) : GoalError(message)
}

/**
 * A known field of the wrong type is an error, never "absent" (JSON null is absent): a goal is rewritten whole, so
 * reading a damaged field as empty would silently erase it, or every checkpoint and link, on the next save.
 */
private fun <T> JsonObject.typed(key: String, read: (JsonElement) -> T?): T? {
    val value = this[key]?.takeIf { it !is JsonNull } ?: return null
    return read(value) ?: throw GoalError.Invalid("The goal’s “$key” has an unexpected form, so it was not opened.")
}
internal fun JsonObject.strict(key: String): String? = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
internal fun JsonObject.strictBool(key: String): Boolean? = typed(key) { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }
internal fun JsonObject.strictArr(key: String): JsonArray = typed(key) { it as? JsonArray } ?: JsonArray(emptyList())
private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
