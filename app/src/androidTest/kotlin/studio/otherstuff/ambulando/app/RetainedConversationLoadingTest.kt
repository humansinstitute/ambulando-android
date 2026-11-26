package studio.otherstuff.ambulando.app

import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.chat.catchUpChannel
import studio.otherstuff.ambulando.core.chat.refreshThread
import studio.otherstuff.ambulando.core.surfaces.refreshAgentActivity
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.ThreadRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import java.util.UUID

/** Disposable sample transport, but a realistic retained cache and actual Compose/store/navigation callers. */
@RunWith(AndroidJUnit4::class)
class RetainedConversationLoadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val tag = InstrumentationRegistry.getArguments().getString("visualTag") ?: "light"
        val resolver = instrumentation.targetContext.contentResolver
        val fileName = "loading86-$tag-$name.png"
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"${MediaStore.Downloads.DISPLAY_NAME} = ?",arrayOf(fileName))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,fileName)
            put(MediaStore.Downloads.MIME_TYPE,"image/png")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/loading86")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun homeScopeChannelDeckAndThreadWithRetainedHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it,true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val w = model.workspace!!.id
            compose.runOnIdle { model.refresh(force=true) }
            compose.waitUntil(30_000) { runBlocking { model.store.reader.tasks(w).isNotEmpty() && model.store.reader.docs(w).isNotEmpty() && model.store.reader.files(w).isNotEmpty() } }
            val channel = SampleIds.GENERAL
            val me = model.connection!!.signerNpub
            fun tid(i: Int) = UUID.nameUUIDFromBytes("retained-thread-$i".toByteArray()).toString()
            model.sampleTower!!.seedLongConversation(SampleIds.thread(39))
            runBlocking { model.sync.refreshThread(model.connection!!,model.workspace!!,channel,SampleIds.thread(39),force=true) }
            compose.waitUntil(30_000) { runBlocking { model.store.reader.threadMessageCount(w,SampleIds.thread(39)) } > 70 }
            model.sampleTower!!.offline = true
            runBlocking {
                model.catalog.writer.setState("settings.chat","true")
                model.store.writer.upsertThreads(w,(0 until 1200).map { i ->
                    ThreadRecord(w,tid(i),channel,null,"Retained topic $i",updatedAt="2026-11-01T00:00:00Z",raw=buildJsonObject { put("id",tid(i)) })
                })
                repeat(96) { batch ->
                    model.store.writer.upsertMessages((batch*500 until (batch+1)*500).map { n ->
                        val id = UUID.nameUUIDFromBytes("retained-message-$n".toByteArray()).toString()
                        val at = "2026-11-01T00:00:%02dZ".format(n%40)
                        val body = if(n%40 == 39) "**Retained history** reply with [documentation](https://example.com)" else "Synthetic retained reply $n"
                        MessageRecord(w,id,channel,tid(n/40),null,body,if(n%2 == 0) me else "synthetic-peer",senderLabel=if(n%2 == 0) "Sample Explorer" else "Synthetic Colleague",createdAt=at,updatedAt=at,rowVersion=1,
                            raw=buildJsonObject { put("id",id);put("body",body);put("fixture","x".repeat(1000)) })
                    })
                }
            }
            runBlocking {
                val start = System.nanoTime()
                val chat = async(start=CoroutineStart.UNDISPATCHED) { model.store.reader.conversations(w,setOf(channel),me) }
                val deck = async(start=CoroutineStart.UNDISPATCHED) { model.store.reader.deckThreads(w,listOf(channel)) }
                val rows = chat.await()
                Log.i("RetainedLoading","coldStoreSummaryMs=${(System.nanoTime()-start)/1e6} rows=${rows.size}")
                deck.await()
                Log.i("RetainedLoading","queuedStoreDeckMs=${(System.nanoTime()-start)/1e6}")
                repeat(3) {
                    val began = System.nanoTime()
                    model.store.reader.conversations(w,setOf(channel),me)
                    Log.i("RetainedLoading","warmStoreSummaryMs=${(System.nanoTime()-began)/1e6}")
                }
                model.store.writer.touch(setOf(ChangeKey(StoreTable.MESSAGES,w)))
            }
            fun timed(label: String, action: () -> Unit, ready: () -> Boolean) {
                val began = System.nanoTime()
                compose.runOnIdle(action)
                compose.waitForIdle()
                compose.waitUntil(15_000, ready)
                Log.i("RetainedLoading", "$label visibleMs=${(System.nanoTime()-began)/1e6}")
            }
            fun inboxReady() = compose.onAllNodesWithText("Retained topic", substring = true).fetchSemanticsNodes().isNotEmpty()
            timed("Home Chat", { model.selectContext(null,null);model.showChat=true;model.section=AppSection.CHAT }, ::inboxReady)
            timed("Scope Chat", { model.selectContext(SampleIds.SCOPE_AMBULANDO,null) }, ::inboxReady)
            timed("Channel Chat", { model.selectContext(SampleIds.SCOPE_AMBULANDO,channel) }, ::inboxReady)
            timed("Deck", { model.section=AppSection.DECK }, { compose.onAllNodesWithTag("chat.inbox").fetchSemanticsNodes().isEmpty() })
            timed("Warm Chat", { model.section=AppSection.CHAT }, ::inboxReady)
            Log.i("RetainedLoading","cachedMessages="+runBlocking { model.store.reader.messageCount(w) })
            if (InstrumentationRegistry.getArguments().getString("performanceOnly") == "true") return@use
            timed("Tasks retained cache", { model.section=AppSection.TASKS }, { compose.onAllNodesWithTag("tasks.list").fetchSemanticsNodes().isNotEmpty() })
            timed("Docs retained cache", { model.section=AppSection.DOCS }, { compose.onAllNodesWithTag("docs.row").fetchSemanticsNodes().isNotEmpty() })
            timed("Files retained cache", { model.section=AppSection.FILES }, { compose.onAllNodesWithTag("files.row").fetchSemanticsNodes().isNotEmpty() })
            timed("Return Chat", { model.section=AppSection.CHAT }, ::inboxReady)
            compose.onNodeWithTag("chat.inbox.search").performTextReplacement("body:\"deployment checklist\"")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat.inbox.row.${SampleIds.thread(39)}").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            model.sampleTower!!.offline = false
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(39)}").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("thread.header").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.close").assertDoesNotExist()
            repeat(3) {
                if (compose.onAllNodesWithTag("chat.loadLater").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.loadLater"))
                    compose.onNodeWithTag("chat.loadLater").performClick()
                }
            }
            // Rich incoming fixture uses an existing synthetic canonical message; no personal store is read.
            compose.waitForIdle()
            val visibleIds = compose.onAllNodes(SemanticsMatcher("visible message") {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("chat.message.") == true
            }).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("chat.message.") }
            val incoming = runBlocking { visibleIds.mapNotNull { model.store.reader.message(w,it) }.first { it.senderNpub != me } }
            val richIncoming = "Please review this handover, @[Sample Explorer](mention:person:$me). " +
                "The discussion keeps its original records and access rules while the local history grows. ".repeat(3) +
                "\n\n**Next steps:** check [the documentation](https://example.com), keep the draft, and reply in this conversation."
            runBlocking { model.store.writer.upsertMessages(listOf(incoming.copy(body=richIncoming,rowVersion=incoming.rowVersion+1,
                raw=buildJsonObject { incoming.raw.forEach { (key,value) -> put(key,value) }; put("body",richIncoming);put("row_version",incoming.rowVersion+1) }))) }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${incoming.id}"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Please review this handover",substring=true,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            capture("incoming-rich")
            val sentBody = "**Retained history** reply with [documentation](https://example.com).\n\nComposer and reactions remain usable."
            val priorSentIds = runBlocking { model.store.reader.threadMessages(w,SampleIds.thread(39),50).map { it.id }.toSet() }
            compose.onNodeWithTag("chat.composer").performTextReplacement(sentBody)
            compose.onNodeWithTag("chat.send").performClick()
            var sentId: String? = null
            compose.waitUntil(20_000) {
                sentId = runBlocking { model.store.reader.threadMessages(w,SampleIds.thread(39),50).lastOrNull { it.body == sentBody && it.id !in priorSentIds }?.id }
                sentId != null
            }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.$sentId"))
            compose.onAllNodesWithTag("reaction.quick").assertCountEquals(0)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("message.$sentId.actions"))
            compose.onNodeWithTag("message.$sentId.actions").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("message.$sentId.menu.react").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("message.$sentId.menu.react").performClick()
            compose.onNodeWithTag("reaction.quick").performClick()
            try {
                compose.waitUntil(15_000) { runBlocking { model.store.reader.reactions(w,listOf(sentId!!)).any { !it.deleted && it.emoji == "thumbs_up" } } }
            } catch (failure: Throwable) {
                val reactionState = runBlocking { model.store.reader.reactions(w,listOf(sentId!!)).map { "${it.emoji}:deleted=${it.deleted}" } }
                val writeState = runBlocking { model.store.reader.workspaceWrites(model.connection!!.id,w,sentId).map { "${it.kind}:${it.state}:attempts=${it.attempts}:error=${it.lastError}" } }
                println("Synthetic reaction diagnostic target=$sentId reactions=$reactionState writes=$writeState server="+
                    model.sampleTower!!.work.reactionRows().filter { (it["target_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == sentId }.map { it["emoji"] })
                capture("reaction-failure")
                throw failure
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reaction.thumbs_up").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reaction.add").performClick()
            compose.onNodeWithTag("reaction.pick.heart").performScrollTo().performClick()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.reactions(w,listOf(sentId!!)).count { !it.deleted } >= 2 } }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Retained draft")
            compose.onNodeWithTag("chat.composer").performClick()
            compose.waitUntil(10_000) {
                var shown = false
                scenario.onActivity { shown = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                shown
            }
            Thread.sleep(500) // Finish Android's IME animation before geometry/capture.
            compose.waitForIdle()
            var keyboardTop = 0
            scenario.onActivity { keyboardTop = it.window.decorView.height - androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom }
            val composerBottom = compose.onNodeWithTag("chat.composer.area").fetchSemanticsNode().boundsInWindow.bottom
            assertTrue("Composer must clear keyboard ($composerBottom <= $keyboardTop)",composerBottom <= keyboardTop + 2)
            capture("keyboard")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            Thread.sleep(350)
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("reaction.heart"))
            capture("thread")
            model.sampleTower!!.offline = true
            runBlocking { model.sync.refreshAgentActivity(model.connection!!,model.workspace!!,channel,force=true,threadId=SampleIds.thread(39)) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat.agent.recoveryError").fetchSemanticsNodes().isNotEmpty() }
            capture("activity-error")
            model.sampleTower!!.offline = false
            runBlocking { model.sync.refreshAgentActivity(model.connection!!,model.workspace!!,channel,force=true,threadId=SampleIds.thread(39)) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat.agent.recoveryError").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("nav.back").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat.inbox.row.${SampleIds.thread(39)}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(39)}").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Retained draft")
        }
    }
}
