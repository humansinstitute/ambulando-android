package studio.otherstuff.ambulando.app.ui.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The open section and each section's stack of records, as saved per connection and workspace so a restart (or Android
 * ending the process) returns to the screens the person was on. Only routes of the same workspace are restored.
 */
object RouteState {
    data class Snapshot(val section: String?, val stacks: Map<String, List<WorkRoute>>)

    fun encode(section: String, stacks: Map<String, List<WorkRoute>>): String = buildJsonObject {
        put("section", section)
        put("stacks", buildJsonObject { stacks.filterValues { it.isNotEmpty() }.forEach { (key, routes) -> put(key, JsonArray(routes.map(::encode))) } })
    }.toString()

    fun decode(text: String?, workspaceId: String): Snapshot? {
        val root = runCatching { Json.parseToJsonElement(text ?: return null).jsonObject }.getOrNull() ?: return null
        val stacks = (root["stacks"] as? JsonObject)?.mapValues { (_, routes) ->
            (routes as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::route) }.filter { it.workspaceId == workspaceId }
        }.orEmpty()
        return Snapshot((root["section"] as? JsonPrimitive)?.contentOrNull, stacks)
    }

    private fun encode(route: WorkRoute): JsonObject = buildJsonObject {
        put("w", route.workspaceId)
        when (route) {
            is WorkRoute.Task -> { put("t", "task"); put("a", route.taskId) }
            is WorkRoute.Doc -> { put("t", "doc"); put("a", route.docId) }
            is WorkRoute.File -> { put("t", "file"); put("a", route.fileId) }
            is WorkRoute.Thread -> { put("t", "thread"); put("a", route.threadId); route.channelId?.let { put("b", it) } }
            is WorkRoute.Channel -> { put("t", "channel"); put("a", route.channelId) }
            is WorkRoute.Canvas -> { put("t", "canvas"); put("a", route.canvasId) }
            is WorkRoute.Board -> { put("t", "board"); put("a", route.canvasId); put("b", route.napletId) }
            is WorkRoute.ContextComponent -> { put("t", "context"); put("a", route.scopeId); put("b", route.componentId) }
            // Opening publisher HTML is an explicit act; it is not reopened by a restart.
            is WorkRoute.HtmlArtefact -> { put("t", "none") }
        }
    }

    private fun route(o: JsonObject): WorkRoute? {
        fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
        val w = s("w") ?: return null
        val a = s("a"); val b = s("b")
        return when (s("t")) {
            "task" -> a?.let { WorkRoute.Task(w, it) }
            "doc" -> a?.let { WorkRoute.Doc(w, it) }
            "file" -> a?.let { WorkRoute.File(w, it) }
            "thread" -> a?.let { WorkRoute.Thread(w, b, it) }
            "channel" -> a?.let { WorkRoute.Channel(w, it) }
            "canvas" -> a?.let { WorkRoute.Canvas(w, it) }
            "board" -> if (a != null && b != null) WorkRoute.Board(w, a, b) else null
            "context" -> if (a != null && b != null) WorkRoute.ContextComponent(w, a, b) else null
            else -> null
        }
    }
}
