package studio.otherstuff.ambulando.app.integration

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReminderListDeviceTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : VaultSecretStore {
        private var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(bytes: ByteArray) { data = bytes.copyOf() }
    }
    @Test fun sidebarNavigationDoesNotAwaitSlowTransport() = scenario(false)
    @Test fun offlineNavigationAndPendingWriteRollback() = scenario(true)
    private fun scenario(offline: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "reminders-device-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val context = object : ContextWrapper(base) { override fun getCacheDir() = File(root, "cache").also { it.mkdirs() } }
        val identity = IdentityService(File(root, "vault"), Memory())
        val npub = runBlocking { identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 29 }), "123456").npub!! }
        val tower = SampleTower(File(root, "tower")).also { it.prepare(npub) }
        val scope = "88888888-8888-4888-8888-888888888888"
        val board = "99999999-9999-4999-8999-999999999999"
        val source = "77777777-7777-4777-8777-777777777777"
        val savedId = "66666666-6666-4666-8666-666666666666"
        var ready = false; var attempts = 0; var creations = 0; var frozen: JsonObject? = null
        var saved: JsonObject? = null
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = java.net.URI(request.url).path
                fun reply(body: JsonObject) = HttpResponse(200, body.toString().toByteArray())
                if (ready && offline) throw java.io.IOException("Offline test transport")
                if (ready && request.method == "GET") delay(3_000)
                if (ready && path.endsWith("/scopes")) return reply(jsonObjectOf("scopes" to listOf(mapOf("id" to scope, "workspace_id" to tower.workspacesForTest(), "kind" to "dm"))))
                if (ready && path.endsWith("/scopes/$scope/channels")) return reply(jsonObjectOf("channels" to listOf(mapOf("id" to board, "scope_id" to scope, "workspace_id" to tower.workspacesForTest(), "kind" to "dm", "participant_npubs" to listOf(npub)))))
                if (ready && path.endsWith("/messages/$source")) return reply(jsonObjectOf("message" to mapOf("id" to source, "workspace_id" to tower.workspacesForTest(), "channel_id" to board, "body" to "**Read** [article](https://example.com)")))
                if (ready && path.endsWith("/channels/$board/tasks") && request.method == "POST") {
                    attempts++
                    val body = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
                    if (frozen == null) {
                        frozen = body; creations++
                        saved = JsonObject(body + mapOf("id" to JsonPrimitive(savedId), "workspace_id" to JsonPrimitive(tower.workspacesForTest()), "channel_id" to JsonPrimitive(board)))
                        throw java.io.IOException("Response lost after persistence")
                    }
                    Assert.assertEquals(frozen, body)
                    return reply(jsonObjectOf("task" to saved, "replayed" to true))
                }
                val response = tower.send(request)
                if (path.endsWith("/me")) {
                    val o = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
                    val actor = o.obj("actor") ?: JsonObject(emptyMap())
                    return reply(JsonObject(o + ("actor" to JsonObject(actor + ("kind" to JsonPrimitive("human"))))))
                }
                return response
            }
        }
        lateinit var model: AppModel
        compose.runOnUiThread { model = AppModel(AppEnvironment.Sample(context), context, AppSessionDependencies(identity, transport, tower.events), restoreInteractiveIdentity = false) }
        try {
            compose.waitUntil(30_000) { model.active?.validated == true }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == tower.workspacesForTest() }) }
            compose.waitUntil(10_000) { model.workspace?.id == tower.workspacesForTest() && model.active?.validated == true }
            runBlocking { model.sharedEngine.waitUntilIdle() }
            val w = model.workspace!!.id
            Assert.assertEquals(tower.workspacesForTest(), w)
            val cached = TaskRecord.parse(jsonObjectOf("id" to savedId, "workspace_id" to w, "scope_id" to SampleIds.SCOPE_AMBULANDO, "channel_id" to SampleIds.GENERAL, "title" to "Cached assigned reminder", "state" to "ready", "assigned_to_npub" to npub))
            runBlocking {
                model.store.writer.upsertScopes(w, listOf(ScopeRecord(w, scope, "Private", kind = "dm", updatedAt = "")), complete = false)
                model.store.writer.upsertChannels(w, scope, listOf(ChannelRecord(w, board, scope, "Note to Self", kind = "dm", updatedAt = "", raw = jsonObjectOf("participant_npubs" to listOf(npub)))), complete = false)
            }
            runBlocking { model.store.writer.upsertTasks(w, (0 until 600).map { cached.copy(id = java.util.UUID.nameUUIDFromBytes("unassigned-$it".toByteArray()).toString(), title = "Unassigned $it", assignedToNpub = null, updatedAt = "2026-11-28T01:00:00Z") } + cached + cached.copy(id = source, channelId = board, scopeId = scope, title = "Personal reminder")) }
            compose.setContent { AmbulandoTheme { AmbulandoApp(model) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sidebar.open").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sidebar.open").performClick()
            compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("sidebar"))).performScrollToNode(hasContentDescription("Reminders in ${model.workspace!!.label}"))
            ready = true
            val start = System.nanoTime()
            compose.onNodeWithContentDescription("Reminders in ${model.workspace!!.label}").performClick()
            var navigationFailure: Throwable? = null
            try {
                compose.waitUntil(750) { compose.onAllNodesWithTag("reminders.list").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) { navigationFailure = failure
            } finally {
                println("REMINDERS_NAV elapsedMs=" + (System.nanoTime()-start)/1_000_000 +
                    " sidebarVisible=" + compose.onAllNodesWithContentDescription("Reminders in ${model.workspace!!.label}").fetchSemanticsNodes().isNotEmpty())
            }
            if (navigationFailure != null) {
                compose.waitUntil(10_000) { model.section == studio.otherstuff.ambulando.app.ui.shell.AppSection.TASKS }
                println("REMINDERS_BASELINE actualDestinationMs=" + (System.nanoTime()-start)/1_000_000)
                throw navigationFailure!!
            }
            compose.onNodeWithTag("reminders.list").assertIsDisplayed()
            compose.waitUntil(750) { compose.onAllNodesWithTag("reminder.$source").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder.$source").assertIsDisplayed()
            val evidence = File(base.filesDir, "reminder-list-evidence").also { it.mkdirs() }
            File(evidence, "list-$offline.png").outputStream().use { out -> InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            println("REMINDERS_CACHED populatedMs=" + (System.nanoTime()-start)/1_000_000 + " tasks=602 offline=$offline")
            compose.onNodeWithTag("reminders.rows").performScrollToNode(hasTestTag("reminder.$savedId"))
            compose.onNodeWithTag("reminder.$savedId").assertIsDisplayed()
            if (offline) {
                val write = runBlocking { model.store.writer.enqueueWrite(model.connection!!, model.workspace!!, studio.otherstuff.ambulando.app.ui.tasks.TaskWrites.state(savedId, TaskState.DONE)) }
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminder.$savedId").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("reminders.rows").performScrollToNode(hasText("No other saved open tasks assigned to you."))
                compose.onNodeWithText("No other saved open tasks assigned to you.").assertIsDisplayed()
                runBlocking { model.store.writer.markWrite(write.id, WriteState.FAILED, error = "Test rejected change") }
                compose.waitUntil(2_000) { compose.onAllNodesWithText("No other saved open tasks assigned to you.").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminder.$savedId").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Test rejected change", substring = true).assertExists()
                compose.onNodeWithTag("reminders.rows").performScrollToNode(hasTestTag("reminder.$savedId"))
                compose.onNodeWithTag("reminder.$savedId").performClick()
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("task.title").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("task.title").assertTextEquals("Cached assigned reminder")
                compose.onNodeWithTag("task.assignee").performScrollTo().performClick()
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("task.assignee.search").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("task.assignee.search").assertExists()
                File(evidence, "assignee.png").outputStream().use { out -> InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                compose.onNodeWithTag("task.assignee.cancel").performClick()
                compose.onNodeWithTag("task.assignee").performScrollTo().performClick()
                compose.onNodeWithTag("task.assignee.option.unassigned").performClick()
                compose.onNodeWithTag("task.assignee.save").performClick()
                compose.waitUntil(2_000) { compose.onAllNodesWithContentDescription("Assignee: Unassigned").fetchSemanticsNodes().isNotEmpty() }
                val assignment = runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, w, savedId).last { it.kind == WriteKind.TASK_ASSIGN } }
                compose.onNodeWithTag("nav.back").performClick()
                compose.onNodeWithTag("reminders.list").assertIsDisplayed()
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminders.loading").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminder.$savedId").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("reminders.rows").performScrollToNode(hasText("No other saved open tasks assigned to you."))
                compose.onNodeWithText("No other saved open tasks assigned to you.").assertIsDisplayed()
                runBlocking { model.store.writer.markWrite(assignment.id, WriteState.FAILED, error = "Test assignment rejected") }
                compose.waitUntil(2_000) { compose.onAllNodesWithText("No other saved open tasks assigned to you.").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("reminders.rows").performScrollToNode(hasTestTag("reminder.$savedId"))
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminder.$savedId").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Test assignment rejected", substring = true).assertExists()
                runBlocking { model.store.writer.markAccessRemoved(w, AccessTarget.Task(savedId)) }
                compose.waitUntil(2_000) { compose.onAllNodesWithTag("reminder.$savedId").fetchSemanticsNodes().isEmpty() }
                compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id != w }) }
                compose.waitUntil(5_000) { model.workspace?.id != w }
                compose.onNodeWithTag("reminders.list").assertDoesNotExist()
            }
        } finally {
            ready = false
            compose.runOnUiThread { (AppModel::class.java.getDeclaredField("viewModelScope").apply { isAccessible = true }.get(model) as CoroutineScope).cancel() }
            runBlocking { model.runtimes.close() }; model.sharedEngine.close(); model.catalog.close()
            root.deleteRecursively()
        }
    }
    private fun SampleTower.workspacesForTest() = studio.otherstuff.ambulando.core.sample.SampleIds.STUDIO
}
