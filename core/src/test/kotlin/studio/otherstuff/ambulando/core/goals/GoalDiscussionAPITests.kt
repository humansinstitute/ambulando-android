package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.otherstuff.ambulando.core.chat.messagesPage
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.chat.threads
import studio.otherstuff.ambulando.core.chat.updateThreadMetadata
import studio.otherstuff.ambulando.core.chat.ThreadArtefactMembershipSync
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI
import java.util.UUID

/** Synthetic contract checks; these do not establish live ACLs, dispatch or native navigation. */
class GoalDiscussionAPITests {
    @get:Rule val temporary = TemporaryFolder()
    private val w = SampleIds.STUDIO
    private val scope = SampleIds.SCOPE_AMBULANDO
    private val channel = SampleIds.GENERAL
    private fun requestId() = UUID.randomUUID().toString()

    private suspend fun goal(h: SampleHarness, initial: Goal = Goal.create("A purposeful discussion", "Keep the useful outputs connected")): GoalDocument {
        val space = h.api.createGoalsSpace(w, h.app, TestKeys.NPUB, null, emptyList())
        return h.api.createGoal(w, h.app, space.id, initial)
    }

    private fun wrapped(h: SampleHarness, intercept: suspend (HttpRequest, String) -> HttpResponse?): TowerAPI =
        TowerAPI(TowerClient(h.tower.origin, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse =
                intercept(request, URI(request.url).path) ?: h.tower.send(request)
        }, SampleSigners.primary))

    @Test fun defaultAndIndependentPinsPersistThroughFileVersionsAndRelaunch() = runBlocking {
        val dir = temporary.newFolder("goal-persistence")
        val h = SampleHarness(SampleTower(dir))
        val original = goal(h)
        val file = GoalLink(GoalLinkType.FILE, SampleIds.BOARD_FILE, SampleIds.DESIGN)
        val doc = GoalLink(GoalLinkType.DOC, SampleIds.CANVAS_DOC, channel)
        h.api.editGoal(w, h.app, original.id, listOf(GoalEdit.SetDefaultChannel(null, channel), GoalEdit.Pin(file), GoalEdit.Pin(doc)))
        val reopened = SampleHarness(SampleTower(dir)).api.readGoal(w, h.app, original.id)
        assertEquals(channel, reopened.goal.defaultChannelId)
        assertEquals(listOf(file, doc), reopened.goal.pinnedRefs)
        assertTrue(reopened.goal.links.isEmpty())
        assertNotEquals(original.versionId, reopened.versionId)
        h.api.editGoal(w, h.app, original.id, listOf(GoalEdit.Unpin(GoalLinkType.FILE, file.recordId)))
        assertEquals(listOf(doc), h.api.readGoal(w, h.app, original.id).goal.pinnedRefs)
    }

    @Test fun discussionIsPersistedOnThreadWithoutMessageOrGoalRewrite() = runBlocking {
        val dir = temporary.newFolder("discussion-persistence")
        val h = SampleHarness(SampleTower(dir))
        val g = goal(h, Goal.create("Purpose", "A shared aim").copy(defaultChannelId = channel))
        val id = requestId()
        val thread = h.api.createGoalDiscussion(w, h.app, g.id, channel, scope, channel, id)
        assertEquals(channel, thread.channelId)
        assertEquals("Purpose", thread.title)
        assertNull(thread.sourceMessageId)
        val metadata = thread.raw.obj("metadata")!!
        assertEquals(g.id, GoalAssociation.read(metadata))
        assertEquals(id, metadata.string("ambulando_discussion_request_id"))
        assertTrue(h.api.messagesPage(w, h.app, channel, thread.id, null).messages.isEmpty())
        assertEquals(g.versionId, h.api.readGoal(w, h.app, g.id).versionId)
        assertTrue(h.api.readGoal(w, h.app, g.id).goal.links.isEmpty())
        val again = SampleHarness(SampleTower(dir))
        assertEquals(g.id, GoalAssociation.read(again.api.thread(w, again.app, thread.id).raw.obj("metadata")!!))
        assertTrue(again.api.threads(w, again.app, channel).items.any { it.id == thread.id })
    }

    @Test fun changedDefaultAndArchivedGoalRefuseBeforeCreatingThread() = runBlocking {
        val h = SampleHarness()
        val g = goal(h, Goal.create("Purpose", "Aim").copy(defaultChannelId = channel))
        var creates = 0
        val api = wrapped(h) { request, path ->
            if (request.method == "POST" && path.endsWith("/threads")) creates++
            null
        }
        h.api.editGoal(w, h.app, g.id, listOf(GoalEdit.SetDefaultChannel(channel, SampleIds.DESIGN)))
        val changed = runCatching { api.createGoalDiscussion(w, h.app, g.id, channel, scope, channel, requestId()) }.exceptionOrNull()
        assertTrue(changed is GoalError.Conflict)
        h.api.editGoal(w, h.app, g.id, listOf(GoalEdit.SetStatus(GoalStatus.ACTIVE, GoalStatus.ARCHIVED)))
        val archived = runCatching { api.createGoalDiscussion(w, h.app, g.id, channel, scope, SampleIds.DESIGN, requestId()) }.exceptionOrNull()
        assertTrue(archived is IllegalStateException)
        assertEquals(0, creates)
    }

    @Test fun missingChannelWrongScopeAndCrossWorkspaceNeverFallBack() = runBlocking {
        val h = SampleHarness()
        val missing = "11111111-1111-4111-8111-111111111111"
        val g = goal(h, Goal.create("Purpose", "Aim").copy(defaultChannelId = missing))
        var creates = 0
        val api = wrapped(h) { request, path ->
            if (request.method == "POST" && path.endsWith("/threads")) creates++
            null
        }
        assertTrue(runCatching { api.createGoalDiscussion(w, h.app, g.id, missing, scope, missing, requestId()) }.isFailure)
        assertTrue(runCatching { api.createGoalDiscussion(w, h.app, g.id, channel, SampleIds.SCOPE_OPS, missing, requestId()) }.isFailure)
        assertTrue(runCatching { api.createGoalDiscussion(SampleIds.LAB, h.app, g.id, SampleIds.NOTES, SampleIds.SCOPE_RESEARCH, missing, requestId()) }.isFailure)
        assertEquals(0, creates)
        assertEquals(missing, h.api.readGoal(w, h.app, g.id).goal.defaultChannelId)
    }

    @Test fun deletedScopeOrChannelAndDeniedChannelReadRefuseWithoutRoutingElsewhere() = runBlocking {
        val h = SampleHarness()
        val g = goal(h)
        var creates = 0
        suspend fun checkUnavailable(area: String, status: Int? = null) {
            val api = wrapped(h) { request, path ->
                if (request.method == "POST" && path.endsWith("/threads")) creates++
                if (request.method == "GET" && path.endsWith(area)) {
                    if (status != null) HttpResponse(status, "{}".toByteArray())
                    else {
                        val response = h.tower.send(request)
                        val json = parseJsonObject(response.body, "fixture")
                        val rows = json.getValue(area).jsonArray.map { row -> JsonObject(row.jsonObject + ("record_state" to JsonPrimitive("deleted"))) }
                        HttpResponse(response.status, JsonObject(json + (area to JsonArray(rows))).toString().toByteArray())
                    }
                } else null
            }
            assertTrue(runCatching { api.createGoalDiscussion(w, h.app, g.id, channel, scope, null, requestId()) }.isFailure)
        }
        checkUnavailable("scopes")
        checkUnavailable("channels")
        checkUnavailable("channels", 403)
        assertEquals(0, creates)
    }

    @Test fun firstReplyAndUnrelatedMetadataUpdatePreserveAssociation() = runBlocking {
        val h = SampleHarness()
        val g = goal(h)
        val created = h.api.createGoalDiscussion(w, h.app, g.id, channel, scope, null, requestId())
        val text = "What would progress look like this week?"
        val sent = h.send(channel, h.messageBody(channel, text, requestId(), thread = created.id))
        assertTrue(sent.status in 200..299)
        val replied = h.api.thread(w, h.app, created.id)
        assertEquals(g.id, GoalAssociation.read(replied.raw.obj("metadata")!!))
        val metadata = replied.raw.obj("metadata")!!
        val updated = h.api.updateThreadMetadata(w, h.app, replied.id, ThreadArtefactMembershipSync.Row(replied.rowVersion, metadata),
            JsonObject(metadata + ("future_feature" to buildJsonObject { put("keep", true) })))
        assertEquals(g.id, GoalAssociation.read(updated.raw.obj("metadata")!!))
        assertEquals(JsonPrimitive(true), updated.raw.obj("metadata")!!.obj("future_feature")!!["keep"])
        val stale = runCatching {
            h.api.updateThreadMetadata(w, h.app, replied.id, ThreadArtefactMembershipSync.Row(replied.rowVersion, metadata), JsonObject(emptyMap()))
        }.exceptionOrNull()
        assertTrue(stale is TowerError.Http && stale.status == 409)
        assertEquals(g.id, GoalAssociation.read(h.api.thread(w, h.app, created.id).raw.obj("metadata")!!))
        val messages = h.api.messagesPage(w, h.app, channel, created.id, null).messages
        assertEquals(listOf(text), messages.map { it.body })
        assertEquals(GoalAssociation.metadata(g.id), h.api.threadGoalMetadata(w, h.app, created.id))
    }

    @Test fun inheritedOutputMetadataContainsOnlyNativeGoalReference() = runBlocking {
        val h = SampleHarness()
        val g = goal(h, Goal.create("Private aim", "No body snapshot in metadata").copy(pinnedRefs = listOf(GoalLink(GoalLinkType.FILE, SampleIds.BOARD_FILE))))
        val created = h.api.createGoalDiscussion(w, h.app, g.id, channel, scope, null, requestId())
        val inherited = h.api.threadGoalMetadata(w, h.app, created.id)
        assertEquals(setOf(GoalAssociation.KEY), inherited.keys)
        assertEquals(setOf("version", "goal_file_id"), inherited.getValue(GoalAssociation.KEY).jsonObject.keys)
        assertFalse(inherited.toString().contains("Private aim"))
        assertFalse(inherited.toString().contains("pinned_refs"))
        val wrongKind = wrapped(h) { request, path ->
            if (request.method == "GET" && path.endsWith("/files/${g.id}")) {
                val response = h.tower.send(request)
                val json = parseJsonObject(response.body, "fixture")
                val file = JsonObject(json.getValue("file").jsonObject + ("content_type" to JsonPrimitive("text/plain")))
                HttpResponse(response.status, JsonObject(json + ("file" to file)).toString().toByteArray())
            } else null
        }
        assertTrue(runCatching { wrongKind.threadGoalMetadata(w, h.app, created.id) }.exceptionOrNull() is GoalError.Invalid)
    }

    @Test fun explicitGoalMentionDoesNotBecomePrimaryAndMismatchedWorkspaceIsRejected() = runBlocking {
        val h = SampleHarness()
        val g = goal(h)
        val ordinary = h.exchange("POST", h.base(w) + "/channels/$channel/threads", mapOf("title" to "Several possible goals", "metadata" to emptyMap<String, Any>()))
        assertTrue(ordinary.status in 200..299)
        val id = ordinary.json().getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(h.send(channel, h.messageBody(channel, "Consider @[Purpose](mention:file:${g.id})", requestId(), thread = id)).status in 200..299)
        assertTrue(h.api.threadGoalMetadata(w, h.app, id).isEmpty())
        val mismatch = wrapped(h) { request, path ->
            if (request.method == "GET" && path.endsWith("/threads/$id")) {
                val response = h.tower.send(request)
                val json = parseJsonObject(response.body, "fixture")
                val thread = JsonObject(json.getValue("thread").jsonObject + ("workspace_id" to JsonPrimitive(SampleIds.LAB)))
                HttpResponse(response.status, JsonObject(json + ("thread" to thread)).toString().toByteArray())
            } else null
        }
        assertTrue(runCatching { mismatch.threadGoalMetadata(w, h.app, id) }.isFailure)
    }

    @Test fun deniedGoalRetainsReferenceButMissingGoalAndDeniedThreadRefuse() = runBlocking {
        val h = SampleHarness()
        val g = goal(h)
        val created = h.api.createGoalDiscussion(w, h.app, g.id, channel, scope, null, requestId())
        fun failRead(suffix: String, status: Int) = wrapped(h) { request, path ->
            if (request.method == "GET" && path.endsWith(suffix)) HttpResponse(status, "{}".toByteArray()) else null
        }
        assertEquals(GoalAssociation.metadata(g.id), failRead("/files/${g.id}", 403).threadGoalMetadata(w, h.app, created.id))
        val missing = runCatching { failRead("/files/${g.id}", 404).threadGoalMetadata(w, h.app, created.id) }.exceptionOrNull()
        assertTrue(missing is TowerError.Http && missing.status == 404)
        val deniedThread = runCatching { failRead("/threads/${created.id}", 403).threadGoalMetadata(w, h.app, created.id) }.exceptionOrNull()
        assertTrue(deniedThread is TowerError.Http && deniedThread.status == 403)
    }

    @Test fun timeoutAfterServerCommitRecoversOriginalWithoutRepeatingCreate() = runBlocking {
        val h = SampleHarness()
        val g = goal(h)
        val request = requestId()
        var creates = 0
        val lostReply = wrapped(h) { sent, path ->
            if (sent.method == "POST" && path.endsWith("/threads")) {
                creates++
                assertEquals(201, h.tower.send(sent).status)
                throw java.io.IOException("Synthetic response lost after server commit")
            }
            null
        }
        assertTrue(runCatching { lostReply.createGoalDiscussion(w, h.app, g.id, channel, scope, null, request) }.isFailure)
        val recovered = h.api.findGoalDiscussion(w, h.app, channel, g.id, request)!!
        assertEquals(g.id, GoalAssociation.read(recovered.raw.obj("metadata")!!))
        assertEquals(recovered.id, h.api.findGoalDiscussion(w, h.app, channel, g.id, request)?.id)
        assertEquals(1, creates)
        assertEquals(1, h.api.threads(w, h.app, channel).items.count { it.raw.obj("metadata")?.string("ambulando_discussion_request_id") == request })
        assertNull(h.api.findGoalDiscussion(w, h.app, SampleIds.DESIGN, g.id, request))
    }
}
