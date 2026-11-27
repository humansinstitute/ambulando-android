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
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemindersUiTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : VaultSecretStore {
        private var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(bytes: ByteArray) { data = bytes.copyOf() }
    }
    @Test fun selectedMessageSaveOpensOnlyPersistedPersonalTaskAndRepeatedTapReplays() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("reminders-ui").toFile()
        val base = RuntimeEnvironment.getApplication() as Context
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
            val message = MessageRecord.parse(jsonObjectOf("id" to source, "workspace_id" to w, "channel_id" to board, "body" to "**Read** [article](https://example.com)"))
            var opened: WorkRoute? = null
            compose.setContent { AmbulandoTheme { CompositionLocalProvider(LocalAppServices provides AppServices(null, {}, { opened = it }, {}, { _, _ -> }, unlocked = true, sample = true)) {
                MessageMenu(model, message, source, null, null, true, "test", "Message actions", conversationThreadId = null)
            } } }
            ready = true
            compose.onNodeWithTag("test.actions").performClick()
            compose.onNodeWithTag("test.menu.remind").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Response lost after persistence").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reminder saved").assertDoesNotExist()
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithTag("test.actions").performClick()
            compose.onNodeWithTag("test.menu.remind").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Reminder saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder.openTask").performClick()
            compose.runOnIdle { Assert.assertEquals(WorkRoute.Task(w, savedId), opened); Assert.assertEquals(2, attempts); Assert.assertEquals(1, creations) }
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
