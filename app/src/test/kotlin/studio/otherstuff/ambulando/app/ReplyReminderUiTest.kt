package studio.otherstuff.ambulando.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.store.message
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReplyReminderUiTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : VaultSecretStore {
        private var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(bytes: ByteArray) { data = bytes.copyOf() }
    }
    @Test fun replyMenuPreservesSelectedBodyAndSourceWhileHeaderSavesRoot() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("reminders-ui").toFile()
        val base = RuntimeEnvironment.getApplication() as Context
        val context = object : ContextWrapper(base) { override fun getCacheDir() = File(root, "cache").also { it.mkdirs() } }
        val identity = IdentityService(File(root, "vault"), Memory())
        val npub = runBlocking { identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 29 }), "123456").npub!! }
        val tower = SampleTower(File(root, "tower")).also { it.prepare(npub) }
        val scope = "88888888-8888-4888-8888-888888888888"
        val board = "99999999-9999-4999-8999-999999999999"
        val source = studio.otherstuff.ambulando.core.sample.SampleIds.message(100)
        val savedId = "66666666-6666-4666-8666-666666666666"
        var ready = false; var attempts = 0; var creations = 0; var frozen: JsonObject? = null
        var saved: JsonObject? = null
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = java.net.URI(request.url).path
                fun reply(body: JsonObject) = HttpResponse(200, body.toString().toByteArray())
                if (ready && path.endsWith("/scopes")) return reply(jsonObjectOf("scopes" to listOf(mapOf("id" to scope, "workspace_id" to tower.workspacesForTest(), "kind" to "dm"))))
                if (ready && path.endsWith("/scopes/$scope/channels")) return reply(jsonObjectOf("channels" to listOf(mapOf("id" to board, "scope_id" to scope, "workspace_id" to tower.workspacesForTest(), "kind" to "dm", "participant_npubs" to listOf(npub)))))
                if (ready && path.endsWith("/channels/$board/tasks") && request.method == "POST") {
                    attempts++
                    val body = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
                    if (frozen == null) {
                        frozen = body; creations++
                        saved = JsonObject(body + mapOf("id" to JsonPrimitive(savedId), "workspace_id" to JsonPrimitive(tower.workspacesForTest()), "channel_id" to JsonPrimitive(board)))
                        if (creations == 1) throw java.io.IOException("Response lost after persistence")
                        return reply(jsonObjectOf("task" to saved))
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
            var opened: WorkRoute? = null
            compose.setContent { AmbulandoTheme { CompositionLocalProvider(LocalAppServices provides AppServices(null, {}, { opened = it }, {}, { _, _ -> }, unlocked = true, sample = true)) {
                ConnectedChat(model, studio.otherstuff.ambulando.core.sample.SampleIds.thread(39), channelOverride = studio.otherstuff.ambulando.core.sample.SampleIds.GENERAL)
            } } }
            ready = true
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("message.$source.actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("message.$source.actions"))
            compose.onNodeWithText("Looks right on my phone").assertIsDisplayed()
            compose.onNodeWithTag("message.$source.actions").performClick()
            compose.onNodeWithTag("message.$source.menu.remind").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Response lost after persistence").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                Assert.assertEquals("Looks right on my phone\n\nSource: @[Original message](mention:chat:${studio.otherstuff.ambulando.core.sample.SampleIds.GENERAL}#$source)", frozen!!.string("description"))
                Assert.assertEquals(source, frozen!!.obj("metadata")!!.obj("ambulando_reminder")!!.string("message_id"))
            }
            compose.onNodeWithText("Reminder saved").assertDoesNotExist()
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithTag("message.$source.actions").performClick()
            compose.onNodeWithTag("message.$source.menu.remind").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Reminder saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder.openTask").performClick()
            compose.runOnIdle { Assert.assertEquals(WorkRoute.Task(w, savedId), opened); Assert.assertEquals(2, attempts); Assert.assertEquals(1, creations) }
            // The real header caller intentionally saves the root, with its own journal entry.
            compose.runOnIdle { frozen = null }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.remind").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Reminder saved").fetchSemanticsNodes().isNotEmpty() }
            val rootMessage = runBlocking { model.store.reader.message(w, studio.otherstuff.ambulando.core.sample.SampleIds.message(39)) }!!
            compose.runOnIdle {
                Assert.assertEquals(2, creations)
                Assert.assertEquals(rootMessage.body + "\n\nSource: @[Original message](mention:chat:${rootMessage.channelId}#${rootMessage.id})", frozen!!.string("description"))
                Assert.assertEquals(rootMessage.id, frozen!!.obj("metadata")!!.obj("ambulando_reminder")!!.string("message_id"))
            }
        } finally {
            ready = false
            compose.runOnUiThread { (AppModel::class.java.getDeclaredField("viewModelScope").apply { isAccessible = true }.get(model) as CoroutineScope).cancel() }
            runBlocking { model.runtimes.close() }; model.sharedEngine.close(); model.catalog.close()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }
    private fun SampleTower.workspacesForTest() = studio.otherstuff.ambulando.core.sample.SampleIds.STUDIO
}
