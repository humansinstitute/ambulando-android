package studio.otherstuff.ambulando.app.integration

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.chat.threadMenu
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** Dedicated emulator only. Every record and identity is synthetic. */
@RunWith(AndroidJUnit4::class)
class ThreadMenuFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun rootMenuCopiesCanonicalReferenceCreatesSourceLinkedTaskAndConfirmsDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val text = "Synthetic thread menu ${System.nanoTime()}"
            compose.onNodeWithTag("chat.composer").performTextReplacement(text)
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == text } } }
            val root = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.message.body == text }.message }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.thread.${root.threadId}"))
            compose.onNodeWithTag("chat.thread.${root.threadId}").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.ref").assertIsDisplayed()
            compose.waitForIdle()
            captureTextReview("android-build10-thread-menu")
            compose.onNodeWithTag("thread.menu.ref").performClick()
            // The app must have focus when Android reads its clipboard.
            compose.runOnIdle {
                assertEquals("@[Chat thread](mention:chat:${SampleIds.GENERAL}#${root.id})",
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.copy").performClick()
            compose.runOnIdle { assertEquals(text, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.getitdone").performClick()
            val title = "Deliver menu check ${System.nanoTime()}"
            compose.onNodeWithTag("getitdone.title").performTextReplacement(title)
            compose.onNodeWithTag("getitdone.extra").performTextReplacement("Keep the source")
            captureTextReview("android-build10-get-it-done")
            compose.onNodeWithTag("getitdone.create").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.tasks(SampleIds.STUDIO).any { it.title == title } } }
            val task = runBlocking { model.store.reader.tasks(SampleIds.STUDIO).first { it.title == title } }
            assertEquals("ready", task.state)
            assertEquals(root.threadId, task.threadId)
            assertTrue(task.description!!.contains("mention:chat:${SampleIds.GENERAL}#${root.id}"))
            assertTrue(task.description!!.contains("Keep the source"))
            assertTrue(task.raw.toString().contains("source_links"))

            // Reopening retains the thread and draft, then an own root can be deleted only after confirmation.
            compose.onNodeWithTag("chat.composer").performTextReplacement("Retained thread draft")
            compose.onNodeWithTag("thread.close").performClick()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.thread.${root.threadId}"))
            compose.onNodeWithTag("chat.thread.${root.threadId}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retained thread draft").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.delete").performClick()
            assertNotNull(runBlocking { model.store.reader.message(SampleIds.STUDIO, root.id) })
            compose.onNodeWithTag("thread.delete.confirm").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.threadMenu(SampleIds.STUDIO, root.threadId!!)!!.root == null } }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.delete").assertDoesNotExist()
            compose.onNodeWithTag("thread.menu.getitdone").assertDoesNotExist()
            scenario.recreate()
        }
    }
}
