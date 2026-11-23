package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

class SystemFlowTests {
    private fun starter() = SystemFlow.parse(ArtefactTemplates.source("system-flow", "system", "default", "Plan").toByteArray())
    private fun linked(): JsonObject = buildJsonObject {
        put("schemaVersion", 3); put("title", "Collection"); put("rootDiagramId", "root")
        put("diagrams", JsonArray(listOf(JsonObject(starter().filterKeys { it != "schemaVersion" } + ("id" to JsonPrimitive("root"))))))
        put("whiteboards", JsonArray(listOf(buildJsonObject { put("id", "board"); put("title", "Before / after"); put("currentSceneId", "before"); put("proposedSceneId", "after") })))
        val scene = Json.parseToJsonElement("""{"elements":[],"appState":{"zoom":{"value":1}},"files":{}}""")
        put("scenes", buildJsonObject { put("before", scene); put("after", scene) })
    }
    @Test fun typedEditPreservesAllLinkedSourceAndStyleFields() {
        val original = SystemFlow.validate(linked())
        val nodes = SystemFlow.items(original, 0, "nodes")
        val edited = SystemFlow.updateItems(original, 0, "nodes", nodes.mapIndexed { i, n ->
            if (i == 0) JsonObject(n + ("data" to JsonObject(n["data"]!!.jsonObject + ("label" to JsonPrimitive("Edited"))))) else n
        })
        assertEquals(original["scenes"], edited["scenes"]); assertEquals(original["whiteboards"], edited["whiteboards"])
        assertEquals(nodes[1], SystemFlow.items(edited, 0, "nodes")[1])
        assertEquals(edited, SystemFlow.parse(edited.toString().toByteArray()))
        val v2 = JsonObject(original.filterKeys { it !in setOf("scenes", "whiteboards") } + ("schemaVersion" to JsonPrimitive(2)))
        assertEquals(v2, SystemFlow.validate(v2))
    }
    @Test fun rejectsMissingScenesDanglingEdgesInvalidParentsAndUnknownExecutableFields() {
        fun rejected(value: JsonElement) { assertThrows(IllegalArgumentException::class.java) { SystemFlow.validate(value) } }
        rejected(JsonObject(linked() - "scenes"))
        rejected(JsonObject(starter() + ("script" to JsonPrimitive("fetch()"))))
        rejected(JsonObject(starter() + ("edges" to JsonArray(listOf(SystemFlow.edge("bad", "start", "missing"))))))
        val nodes = SystemFlow.items(starter(), 0, "nodes")
        rejected(JsonObject(starter() + ("nodes" to JsonArray(listOf(JsonObject(nodes[0] + ("parentId" to JsonPrimitive("action"))), nodes[1])))))
        rejected(JsonObject(linked() + ("scenes" to JsonObject(emptyMap()))))
    }
    @Test fun templatesAreControlledAndHtmlRemainsLocalWithoutFormsOrNetwork() {
        assertEquals("brief", ArtefactTemplates.descriptor("document", "brief", "paper")["template_id"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { ArtefactTemplates.source("html", "remote", "default", "x") }
        val html = ArtefactTemplates.source("html", "explorer", "paper", "<Title>")
        assertTrue(html.contains("&lt;Title&gt;")); assertTrue(html.contains("type=\"range\"")); assertFalse(html.contains("<form")); assertFalse(html.contains("https://"))
    }
    @Test fun draftHistoryConflictAndRevocationUseOriginalBaseAndKeepFullSource() = runBlocking {
        val dir = Files.createTempDirectory("flow-draft").toFile()
        try {
            val draft = java.io.File(dir, "draft.json")
            val server = Fake(starter()); val controller = SystemFlowController(server, draft)
            controller.open(); val base = controller.state.value.base
            controller.edit { JsonObject(it + ("title" to JsonPrimitive("Mine"))) }
            assertTrue(draft.isFile)
            val reopened = SystemFlowController(server, draft); reopened.open()
            assertEquals("Mine", reopened.state.value.flow!!["title"]!!.jsonPrimitive.content)
            server.append(JsonObject(starter() + ("title" to JsonPrimitive("Theirs"))))
            reopened.save(); assertTrue(reopened.state.value.message.contains("newer version")); assertEquals(base, reopened.state.value.base); assertTrue(draft.isFile)
            reopened.open("v1"); assertFalse(reopened.state.value.dirty); assertNotNull(reopened.state.value.viewing)
            reopened.edit { JsonObject(it + ("title" to JsonPrimitive("Forbidden"))) }; assertEquals("Plan", reopened.state.value.flow!!["title"]!!.jsonPrimitive.content)
            reopened.open(); assertTrue(reopened.state.value.dirty)
            reopened.discard(); reopened.open(); reopened.edit { JsonObject(it + ("title" to JsonPrimitive("Saved"))) }; reopened.save()
            assertFalse(draft.exists()); assertFalse(reopened.state.value.dirty); assertEquals(3, reopened.state.value.versions.size)
            reopened.edit { JsonObject(it + ("title" to JsonPrimitive("Keep on denial"))) }; server.write = false; reopened.save()
            assertFalse(reopened.state.value.canWrite); assertTrue(draft.isFile)
        } finally { dir.deleteRecursively() }
    }
    private class Fake(initial: JsonObject) : SystemFlowController.Transport {
        var write = true; val content = linkedMapOf<String, JsonObject>()
        init { append(initial) }
        fun append(f: JsonObject) { content["v${content.size + 1}"] = f }
        private fun versions() = content.entries.mapIndexed { i, e -> FileVersionRecord(e.key, "file", (i + 1).toLong(), "object", e.value.toString().length.toLong(), ArtefactType.SYSTEM_FLOW.mime, "hash", null, null, null, "") }
        override suspend fun head() = write to FileVersionList("file", content.keys.last(), versions())
        override suspend fun bytes(version: FileVersionRecord) = content.getValue(version.id).toString().toByteArray()
        override suspend fun save(base: String, bytes: ByteArray): VersionOutcome {
            if (base != content.keys.last()) return VersionOutcome.Conflict(null, versions().last(), "stale_base_version")
            append(SystemFlow.parse(bytes))
            // Saved.file is irrelevant to this controller; use a valid synthetic File row.
            val f = FileRecord.parse(buildJsonObject {
                put("id", "11111111-1111-4111-8111-111111111111"); put("workspace_id", "22222222-2222-4222-8222-222222222222"); put("display_name", "Flow"); put("channel_id", "33333333-3333-4333-8333-333333333333")
            })
            return VersionOutcome.Saved(LineageFile(f, content.keys.last()), content.keys.last(), false)
        }
    }
}
