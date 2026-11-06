package studio.otherstuff.ambulando.app

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import java.io.File
import java.util.UUID

/** Test-APK-only fake Tower and identity; production environment, no launchable sample mode or live writes. */
@RunWith(AndroidJUnit4::class)
class FamilyConversationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private class Memory : VaultSecretStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(data: ByteArray) { bytes = data.copyOf() }
    }
    @Test fun familyListKeyboardDraftAndOfflineRetryUseCanonicalConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "family-integration-${UUID.randomUUID()}").also { it.mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
        }
        val identity = IdentityService(File(root, "identity-vault"), Memory())
        runBlocking { identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 77 }), "246810") }
        val tower = SampleTower(File(root, "fake-tower"))
        runBlocking { tower.prepare((identity.state() as IdentityState.Unlocked).npub) }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport = object : studio.otherstuff.ambulando.core.transport.HttpTransport {
            override suspend fun send(request: studio.otherstuff.ambulando.core.transport.HttpRequest): studio.otherstuff.ambulando.core.transport.HttpResponse {
                val result = tower.send(request)
                requests += "${request.method} ${java.net.URI(request.url).path} ${result.status}"
                return result
            }
        }
        lateinit var model: AppModel
        runBlocking(Dispatchers.Main) {
            model = AppModel(AppEnvironment.Production(isolated), isolated, AppSessionDependencies(identity, transport, tower.events))
            model.catalog.writer.setState(studio.otherstuff.ambulando.core.profiles.ProfileSync.PREFERENCE_KEY, "off")
            model.connect(tower.origin.url)
        }
        assertFalse(model.environment.isSample)
        compose.waitUntil(30_000) { model.entries.size == 2 && model.enabledEntries.isNotEmpty() && model.enabledEntries.all { it.runtime?.canSend == true } }
        runBlocking(Dispatchers.Main) { model.openWorkspace(model.entries.first { it.workspace.id == SampleIds.STUDIO }).join() }
        try { compose.waitUntil(30_000) { model.notice != null || model.active != null && model.channels.any { it.id == SampleIds.GENERAL } } }
        catch (e: Throwable) { throw AssertionError("Family setup: identity=${model.identityState}, active=${model.activeBoundaryId}, notice=${model.notice}, requests=$requests", e) }
        assertNull("Workspace failed: ${model.notice}; identity=${model.identityState}; active=${model.activeBoundaryId}; channels=${model.channels.map { it.id }}", model.notice)
        val binding = runBlocking(Dispatchers.Main) {
            val c = model.connection!!; val w = model.workspace!!
            val channel = model.channels.first { it.id == SampleIds.GENERAL }
            val thread = model.towerAPI(c).threads(w.id, w.appNpub, channel.id).items.first { !it.archived && !it.deleted }
            FamilyBinding.of(c, w, channel, thread).also {
                model.store.writer.upsertThreads(w.id, listOf(thread))
                model.catalog.writer.setState(FamilyBinding.KEY, it.encode())
            }
        }
        var mounted by mutableStateOf(true)
        compose.activity.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AmbulandoTheme(model.appearance) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Text("Synthetic test · no live messages", Modifier.padding(8.dp), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        if (mounted) Box(Modifier.weight(1f)) { GrandparentApp(model) }
                    }
                }
            }
        } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { runBlocking { model.store.reader.threadMessageCount(binding.workspaceId, binding.threadId) > 0 } }
        compose.onNodeWithText("Tasks").assertDoesNotExist()
        val draft = "Hello family! This is a long draft to check that large text and the keyboard leave room to read our conversation. We will keep this draft safely on this device."
        compose.onNodeWithTag("chat.composer").performClick().performTextInput(draft)
        val target = ChatTarget(binding.connectionId, binding.workspaceId, binding.channelId, binding.threadId)
        compose.waitUntil(15_000) { runBlocking { model.store.reader.draft(target)?.text == draft } }
        compose.onNodeWithTag("chat.messages").assertIsDisplayed()
        capture(base, "family-chat-phone-keyboard-large-text.png")
        // Rebuild the actual screen from the persisted catalog binding, without force-stopping/relaunching the app.
        compose.runOnIdle { mounted = false }; compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.composer").assertTextContains(draft)
        assertEquals(binding, runBlocking { FamilyBinding.decode(model.catalog.reader.state(FamilyBinding.KEY)) })
        tower.offline = true
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(15_000) { runBlocking { model.store.reader.outbox(target).any { it.state == OutboxState.RETRYING } } }
        val request = runBlocking { model.store.reader.outbox(target).first() }
        assertEquals(binding.threadId, request.threadId)
        assertNotNull(request.request)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(draft).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(draft).performScrollTo().assertIsDisplayed()
        capture(base, "family-chat-offline-outbox.png")
        tower.offline = false
        runBlocking(Dispatchers.Main) { model.outbox.kick(model.connection!!, force = true) }
        compose.waitUntil(30_000) { runBlocking { model.store.reader.outbox(target).isEmpty() && model.store.reader.sentMessage(binding.workspaceId, request.id) != null } }
        val sent = runBlocking { model.store.reader.sentMessage(binding.workspaceId, request.id) }!!
        assertEquals(binding.channelId, sent.channelId)
        assertEquals(binding.threadId, sent.threadId)
        assertEquals(draft, sent.body)
        val canonical = runBlocking {
            studio.otherstuff.ambulando.core.transport.TowerAPI(tower.client(identity)).messagesPage(
                binding.workspaceId, binding.appNpub, binding.channelId, binding.threadId, cursor = null, effectiveTranscript = true)
        }
        assertTrue("A separate canonical reader sees the same reply", canonical.messages.any { it.id == sent.id && it.body == draft })
        runBlocking(Dispatchers.Main) { model.foreground(false).join(); model.lock().join() }
    }
    private fun capture(context: Context, name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrandparentChatReview")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
