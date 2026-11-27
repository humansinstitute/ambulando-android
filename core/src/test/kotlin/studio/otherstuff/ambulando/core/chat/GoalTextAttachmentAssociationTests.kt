package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.otherstuff.ambulando.core.goals.Goal
import studio.otherstuff.ambulando.core.goals.GoalAssociation
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.net.URI

/** First text edit creates a native File; later edits keep that File's original provenance. Synthetic routes only. */
class GoalTextAttachmentAssociationTests {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = Fixtures.WORKSPACE_ID
    private val channel = Fixtures.CHANNEL_1
    private val app = Fixtures.APP_NPUB
    private val messageId = "11111111-1111-4111-8111-111111111111"
    private val threadId = "22222222-2222-4222-8222-222222222222"
    private val goalId = "33333333-3333-4333-8333-333333333333"
    private val otherGoalId = "44444444-4444-4444-8444-444444444444"

    /** Adds authoritative message/thread/goal reads around the unchanged text-file transport fixture. */
    private inner class SourceTower : HttpTransport {
        val files = FakeTextTower()
        val requests = mutableListOf<HttpRequest>()
        val savedMetadata = mutableMapOf<String, JsonObject>()
        var primaryGoal: String? = goalId
        var originThread: String? = threadId
        var originWorkspace = workspace
        var originChannel = channel
        var messageStatus = 200
        var threadStatus = 200
        var goalStatus = 200
        val sourceMessageMetadata = buildJsonObject { put("future_message_field", "untouched") }
        val sourceBody = "Consider @[Another goal](mention:file:$otherGoalId)"
        private val goalBytes = Goal.create("The authoritative aim", "Only the File reference travels").encode().toByteArray()
        private val goalVersion = "55555555-5555-4555-8555-555555555555"
        private val goalObject = "66666666-6666-4666-8666-666666666666"

        fun primaryMetadata() = primaryGoal?.let { id -> buildJsonObject {
            put(GoalAssociation.KEY, buildJsonObject {
                put("version", 1); put("goal_file_id", id)
                put("future_provenance", buildJsonObject { put("keep", true) })
            })
        } } ?: JsonObject(emptyMap())

        private fun json(status: Int, value: JsonObject) = HttpResponse(status, value.toString().toByteArray())
        private fun error(status: Int) = json(status, buildJsonObject { put("code", "source_unavailable") })

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val path = URI(request.url).path
            val base = TowerAPI.base(workspace)
            if (request.method == "GET" && path == "$base/messages/$messageId") {
                if (messageStatus != 200) return error(messageStatus)
                return json(200, buildJsonObject { put("message", buildJsonObject {
                    put("id", messageId); put("workspace_id", originWorkspace); put("channel_id", originChannel)
                    originThread?.let { put("thread_id", it) }
                    put("body", sourceBody); put("metadata", sourceMessageMetadata); put("row_version", 7)
                }) })
            }
            if (request.method == "GET" && path == "$base/threads/$threadId") {
                if (threadStatus != 200) return error(threadStatus)
                return json(200, buildJsonObject { put("thread", buildJsonObject {
                    put("id", threadId); put("workspace_id", workspace); put("channel_id", channel)
                    put("title", "Original goal discussion"); put("metadata", primaryMetadata()); put("row_version", 3)
                }) })
            }
            val goal = listOf(goalId, otherGoalId).firstOrNull { path == "$base/files/$it" || path == "$base/files/$it/versions/$goalVersion/object" }
            if (request.method == "GET" && goal != null) {
                if (goalStatus != 200) return error(goalStatus)
                if (path.endsWith("/object")) return HttpResponse(200, goalBytes)
                return json(200, buildJsonObject { put("file", buildJsonObject {
                    put("id", goal); put("workspace_id", workspace); put("channel_id", channel)
                    put("display_name", "Authoritative.goal.json"); put("content_type", Goal.MIME)
                    put("metadata", buildJsonObject { put("goal", buildJsonObject { put("schema_version", Goal.VERSION) }) })
                    put("current_version", buildJsonObject {
                        put("id", goalVersion); put("file_id", goal); put("storage_object_id", goalObject)
                        put("sha256_hex", Sha256Hex.of(goalBytes)); put("size_bytes", goalBytes.size)
                    })
                }) })
            }
            val response = files.send(request)
            val root = runCatching { Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return response
            if (request.method == "POST" && path == "$base/channels/$channel/files" && response.status in 200..201) {
                val id = root.obj("file")?.string("id")!!
                val supplied = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject.obj("metadata")!!
                savedMetadata.putIfAbsent(id, supplied)
            }
            fun originalMetadata(row: JsonObject) = savedMetadata[row.string("id")]?.let { JsonObject(row + ("metadata" to it)) } ?: row
            return json(response.status, JsonObject(root.mapValues { (key, value) -> when {
                key == "file" && value is JsonObject -> originalMetadata(value)
                key == "files" && value is JsonArray -> JsonArray(value.map { originalMetadata(it.jsonObject) })
                else -> value
            } }))
        }
    }

    private data class Rig(val tower: SourceTower, val api: TowerAPI, val service: TextAttachmentService, val attachment: MessageAttachment)
    private suspend fun rig(): Rig {
        val tower = SourceTower()
        val original = "The original immutable text"
        val originalBytes = TextAttachments.bytes(original)
        val objectId = tower.files.putObject(originalBytes)
        val root = temporary.newFolder()
        val service = TextAttachmentService(TextEditStore(File(root, "edits")), TextCache(File(root, "cache")))
        return Rig(tower, TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), tower, makeIdentity())), service,
            MessageAttachment("file", objectId, "Discussion text.txt", TextAttachments.CONTENT_TYPE, originalBytes.size.toLong(), Sha256Hex.of(originalBytes), TextAttachmentInfo.of(original, "paste")))
    }

    private fun Rig.begin(text: String, file: String? = null, version: String? = null) = service.beginEdit(
        Fixtures.newId(), "connection", workspace, channel, app, messageId, attachment, attachment.filename, text, file, version)

    @Test fun firstNativeFileInheritsCanonicalGoalAndThreadWithoutChangingTheSentSource() = runBlocking {
        val rig = rig()
        val originalBytes = rig.tower.files.objects.getValue(rig.attachment.storageObjectId).bytes!!.copyOf()
        val saved = rig.service.save(rig.api, rig.begin("An edited decision").id)
        assertEquals(TextEditState.SAVED, saved.state)
        val metadata = rig.tower.savedMetadata.getValue(saved.fileId!!)
        assertEquals(goalId, GoalAssociation.read(metadata))
        assertEquals(threadId, metadata.string("source_thread_id"))
        assertEquals(GoalAssociation.metadata(goalId)[GoalAssociation.KEY], metadata[GoalAssociation.KEY])
        assertNotNull("extra source association fields stay on the source", rig.tower.primaryMetadata().obj(GoalAssociation.KEY)?.get("future_provenance"))
        val lineage = metadata.obj("text_attachment_lineage")!!
        assertEquals(messageId, lineage.string("origin_message_id"))
        assertEquals(rig.attachment.storageObjectId, lineage.string("origin_storage_object_id"))
        assertArrayEquals(originalBytes, rig.tower.files.objects.getValue(rig.attachment.storageObjectId).bytes)
        assertEquals("Consider @[Another goal](mention:file:$otherGoalId)", rig.tower.sourceBody)
        assertEquals(JsonPrimitive("untouched"), rig.tower.sourceMessageMetadata["future_message_field"])
        assertFalse(rig.tower.requests.any { it.method != "GET" && (URI(it.url).path.endsWith("/messages/$messageId") || URI(it.url).path.endsWith("/threads/$threadId")) })
    }

    @Test fun explicitGoalMentionWithoutPrimaryAssociationDoesNotInferAGoal() = runBlocking {
        val rig = rig(); rig.tower.primaryGoal = null
        val saved = rig.service.save(rig.api, rig.begin("An ordinary thread edit").id)
        assertEquals(TextEditState.SAVED, saved.state)
        val metadata = rig.tower.savedMetadata.getValue(saved.fileId!!)
        assertNull(GoalAssociation.read(metadata))
        assertEquals(threadId, metadata.string("source_thread_id"))
        assertFalse(rig.tower.requests.any { URI(it.url).path.endsWith("/files/$otherGoalId") })
    }

    @Test fun missingOriginalMessageKeepsSupportedEditWithoutInventingReferences() = runBlocking {
        val rig = rig(); rig.tower.messageStatus = 404
        val saved = rig.service.save(rig.api, rig.begin("Original message no longer found").id)
        assertEquals(TextEditState.SAVED, saved.state)
        val metadata = rig.tower.savedMetadata.getValue(saved.fileId!!)
        assertNull(GoalAssociation.read(metadata)); assertNull(metadata.string("source_thread_id"))
        assertFalse(rig.tower.requests.any { URI(it.url).path.endsWith("/threads/$threadId") })
    }

    @Test fun unreadableOrMismatchedOriginalSourceKeepsTheEditLocal() = runBlocking {
        for (failure in listOf("message-denied", "thread-denied", "message-workspace", "message-channel")) {
            val rig = rig()
            when (failure) {
                "message-denied" -> rig.tower.messageStatus = 403
                "thread-denied" -> rig.tower.threadStatus = 403
                "message-workspace" -> rig.tower.originWorkspace = Fixtures.WORKSPACE_ID_2
                "message-channel" -> rig.tower.originChannel = Fixtures.CHANNEL_2
            }
            val edit = rig.begin("Keep $failure locally")
            val saved = rig.service.save(rig.api, edit.id)
            assertEquals(failure, TextEditState.PENDING, saved.state)
            assertEquals("Keep $failure locally", rig.service.edits.text(saved))
            assertTrue(rig.tower.files.files.isEmpty())
        }
    }

    @Test fun laterVersionsAndExistingCanonicalLineageNeverReassignOrStripUnknownMetadata() = runBlocking {
        val rig = rig()
        val first = rig.service.save(rig.api, rig.begin("First edit").id)
        val fileId = first.fileId!!
        val initial = rig.tower.savedMetadata.getValue(fileId)
        val lineage = initial.obj("text_attachment_lineage")!!
        val withFutureFields = JsonObject(initial + mapOf(
            "another_app" to buildJsonObject { put("preserve", true) },
            "text_attachment_lineage" to JsonObject(lineage + ("future_lineage" to JsonPrimitive("keep"))),
        ))
        rig.tower.savedMetadata[fileId] = withFutureFields
        rig.tower.primaryGoal = otherGoalId
        rig.tower.messageStatus = 403 // Existing versions do not need a new origin read to keep their File identity.
        val sourceReads = rig.tower.requests.count { URI(it.url).path.endsWith("/messages/$messageId") }
        val second = rig.service.save(rig.api, rig.begin("Second edit", fileId, first.baseVersionId).id)
        assertEquals(TextEditState.SAVED, second.state)
        assertEquals(fileId, second.fileId)
        assertEquals(withFutureFields, rig.tower.savedMetadata[fileId])
        assertEquals(goalId, GoalAssociation.read(rig.tower.savedMetadata.getValue(fileId)))
        val staleFirst = rig.service.save(rig.api, rig.begin("Another first edit attempt").id)
        assertEquals(TextEditState.CONFLICT, staleFirst.state)
        assertEquals(fileId, staleFirst.fileId)
        assertEquals(sourceReads, rig.tower.requests.count { URI(it.url).path.endsWith("/messages/$messageId") })
        assertEquals(1, rig.tower.files.files.size)
        assertEquals(withFutureFields, rig.tower.savedMetadata[fileId])
    }

    @Test fun creationMergesUnknownCallerMetadataButProtectsTheCanonicalLineage() = runBlocking {
        val rig = rig()
        val objectId = rig.tower.files.putObject(TextAttachments.bytes("A new edited object"))
        val lineage = TextLineage(messageId, rig.attachment.storageObjectId, rig.attachment.filename)
        val extra = buildJsonObject {
            put("another_app", buildJsonObject { put("value", 7) })
            put("text_attachment_lineage", buildJsonObject { put("origin_message_id", otherGoalId) })
        }
        val outcome = rig.api.createLineageFile(workspace, app, channel, objectId, rig.attachment.filename, lineage, extra)
        assertTrue(outcome is VersionOutcome.Saved)
        val metadata = rig.tower.savedMetadata.values.single()
        assertEquals(extra["another_app"], metadata["another_app"])
        assertEquals(lineage.requestObject, metadata["text_attachment_lineage"])
        assertEquals(otherGoalId, extra.obj("text_attachment_lineage")!!.string("origin_message_id"))
    }
}
