package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import java.time.LocalDate

/** Deliberately small, versioned File capability. No caller-selected paths or signing operations. */
object FileNapplet {
    const val MAX_BYTES = 1024 * 1024
    private fun exact(o: JsonObject, vararg keys: String) { require(o.keys == keys.toSet()) }
    private fun string(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid string")
    private fun uuid(s: String) = require(isUUID(s))
    fun binding(metadata: JsonObject, manifest: JsonObject): String {
        exact(manifest, "version", "app_id", "bindings")
        require(manifest["version"] == JsonPrimitive(1) && string(manifest, "app_id") == "daily-tracker")
        val declarations = manifest.getValue("bindings").jsonObject
        exact(declarations, "tracker")
        val declaration = declarations.getValue("tracker").jsonObject
        exact(declaration, "format", "operations", "privacy")
        require(string(declaration, "format") == "daily-tracker-v1" && string(declaration, "privacy") == "self")
        require(declaration["operations"] == JsonArray(listOf(JsonPrimitive("read"), JsonPrimitive("save"))))
        require(metadata["html_artifact"] == JsonPrimitive(true))
        require(metadata["artefact"] == ArtefactType.HTML.metadata()["artefact"])
        val n = metadata.getValue("napplet").jsonObject
        exact(n, "version", "app_id", "bindings")
        require(n["version"] == JsonPrimitive(1) && string(n, "app_id") == "daily-tracker")
        val bindings = n.getValue("bindings").jsonObject
        exact(bindings, "tracker")
        val tracker = bindings.getValue("tracker").jsonObject
        exact(tracker, "file_id")
        return string(tracker, "file_id").also(::uuid)
    }
    /** Validates without normalizing away false, zero, missing answers or local calendar dates. */
    fun model(value: JsonObject, previous: JsonObject? = null): JsonObject {
        require(value.toString().toByteArray().size <= MAX_BYTES)
        exact(value, "format", "metrics", "days")
        require(string(value, "format") == "daily-tracker-v1")
        val metrics = value.getValue("metrics").jsonArray
        require(metrics.size <= 200)
        val types = mutableMapOf<String, String>()
        metrics.forEach {
            val m = it.jsonObject
            exact(m, "id", "label", "type", "unit", "archived")
            val id = string(m, "id"); uuid(id)
            val type = string(m, "type"); require(type in setOf("boolean", "number"))
            require(types.put(id, type) == null)
            val label = string(m, "label"); val unit = string(m, "unit")
            require(label.length in 1..120 && label.any { !it.isWhitespace() && it != '\uFEFF' } && unit.length <= 24)
            require((label + unit).none { it.code < 32 || it.code == 127 })
            require((m["archived"] as? JsonPrimitive)?.booleanOrNull != null && !(m["archived"] as JsonPrimitive).isString)
        }
        val days = value.getValue("days").jsonObject
        require(days.size <= 36600)
        days.forEach { (date, answers) ->
            require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && LocalDate.parse(date).toString() == date && LocalDate.parse(date).year >= 1)
            answers.jsonObject.forEach { (id, answer) ->
                val p = answer as? JsonPrimitive ?: error("Invalid answer")
                require(!p.isString)
                when (types[id]) {
                    "boolean" -> require(p.booleanOrNull != null)
                    "number" -> require(p.doubleOrNull?.isFinite() == true)
                    else -> error("Unknown metric")
                }
            }
        }
        previous?.let { old ->
            val used = old.getValue("days").jsonObject.values.flatMap { it.jsonObject.keys }.toSet()
            old.getValue("metrics").jsonArray.forEach {
                val m = it.jsonObject; val id = string(m, "id")
                // Used metrics remain available for historical review; archive instead of deleting.
                if (id in used) require(types[id] == string(m, "type"))
            }
        }
        return value
    }
    fun request(raw: String, session: String): JsonObject {
        require(raw.toByteArray().size <= MAX_BYTES + 1024)
        val o = Json.parseToJsonElement(raw).jsonObject
        val operation = string(o, "operation")
        val common = arrayOf("type", "version", "session", "requestId", "operation")
        when (operation) {
            "read" -> exact(o, *common, "binding")
            "save" -> { exact(o, *common, "binding", "baseVersionId", "value"); uuid(string(o, "baseVersionId")); model(o.getValue("value").jsonObject) }
            "dirty" -> { exact(o, *common, "dirty"); require(o["dirty"] == JsonPrimitive(true) || o["dirty"] == JsonPrimitive(false)) }
            else -> error("Unknown operation")
        }
        require(string(o, "type") == "wingman-napplet-data" && o["version"] == JsonPrimitive(1))
        require(string(o, "session") == session && string(o, "requestId").matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        if (operation != "dirty") require(string(o, "binding") == "tracker")
        return o
    }
}
