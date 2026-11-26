package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.UUID

/** Synthetic source/server only. Run on the verified task-owned AVD, never an installed user profile. */
@RunWith(AndroidJUnit4::class)
class ThreadArtefactMembershipFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun emptyBarTopIconThreeTypesIndependentStoreAndSharedRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.active?.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val server = requireNotNull(model.sampleTower)
            val html = server.work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, "Thread membership.html", "<h1>Shared attachment</h1>".toByteArray(), "text/html")
            val htmlObject = server.work.files().first { it["id"]?.jsonPrimitive?.content == html }.getValue("storage_object_id").jsonPrimitive.content
            check(server.work.externalFileVersion(html, htmlObject))
            val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39))
            // Each instrumentation run starts from an explicit synthetic server baseline, not a stale local cache.
            check(model.sampleTower != null)
            server.seedLongConversation(SampleIds.thread(39), 75)
            runBlocking {
                val fixtureApi = model.towerAPI(model.connection!!); val fixtureWorkspace = model.workspace!!
                val row = fixtureApi.thread(fixtureWorkspace.id, fixtureWorkspace.appNpub, SampleIds.thread(39))
                val base = ThreadArtefactMembershipSync.Row(row.rowVersion, row.raw.getValue("metadata").jsonObject)
                fixtureApi.updateThreadMetadata(fixtureWorkspace.id, fixtureWorkspace.appNpub, row.id, base,
                    ThreadArtefactMembership.metadata(base.metadata, emptyList()))
            }

            runBlocking { model.store.writer.setState("thread.artefacts.${target.draftKey}", null) }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat.inbox.row.${target.threadId}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.row.${target.threadId}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("thread.artefacts.open")).fetchSemanticsNodes().isNotEmpty() }
            val state = model.threadArtefacts.getValue(target.draftKey)
            compose.waitUntil(20_000) { state.membershipReady }
            compose.onNodeWithTag("thread.artefacts").assertDoesNotExist()
            compose.onNodeWithTag("thread.artefacts.open").assertContentDescriptionEquals("Thread artefacts (0)").assertIsDisplayed()
            fun add(id: String, search: String, expected: Int) {
                compose.onNodeWithTag("thread.artefacts.open").performClick()
                compose.onNodeWithText("+ Browse").performClick()
                compose.onNodeWithTag("thread.artefacts.search").performTextInput(search)
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("thread.artefacts.row.$id")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("thread.artefacts.row.$id").performClick()
                compose.waitUntil(30_000) { state.members.size == expected && !state.busy }
                val back = when (id) { SampleIds.CANVAS_DOC -> "doc.editor.back"; SampleIds.BOARD_FILE -> "board.back"; else -> "artefacts.back" }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag(back).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(back).performClick()
            }
            add(SampleIds.CANVAS_DOC, "Build 7 launch", 1)
            add(SampleIds.BOARD_FILE, "Architecture", 2)
            add(html, "Thread membership", 3)
            compose.onNodeWithTag("thread.artefacts").assertDoesNotExist()
            compose.onNodeWithTag("thread.artefacts.open").assertContentDescriptionEquals("Thread artefacts (3)").assertIsDisplayed()
            val secondDir = File(context.cacheDir, "thread-membership-second-${UUID.randomUUID()}")
            val second = LocalStore(secondDir)
            try {
                val api = model.towerAPI(model.connection!!); val w = model.workspace!!
                fun readSecond(): List<ThreadArtefactMember> = runBlocking {
                    val canonical = api.thread(w.id, w.appNpub, target.threadId!!)
                    second.writer.upsertThreads(w.id, listOf(canonical))
                    ThreadArtefactMembership.read(second.reader.thread(w.id, canonical.id)!!.raw.getValue("metadata").jsonObject)!!
                }
                assertEquals(setOf(SampleIds.CANVAS_DOC, SampleIds.BOARD_FILE, html), readSecond().map { it.id }.toSet())
                // Recreate a closed, fresh panel from the independent client's canonical SQLite row.
                compose.waitUntil(30_000) { runBlocking { state.tabs.filter { it.open }.all { it.checkpoint?.invoke() == true } } }
                compose.onNodeWithTag("thread.artefacts.open").performClick()
                repeat(3) {
                    val count = state.tabs.count { it.open }
                    compose.onAllNodes(hasText("Close view") and isEnabled())[0].performScrollTo().performClick()
                    compose.waitUntil(15_000) { state.tabs.count { it.open } == count - 1 }
                }
                compose.onNodeWithText("Done").performClick()
                val freshRow = runBlocking { second.reader.thread(w.id, target.threadId!!)!! }
                compose.runOnIdle { state.tabs.clear(); state.members = emptyList(); state.membershipReady = false; state.membershipVersion = 0; state.selected = null; state.discuss = false; state.shown = false }
                compose.waitForIdle()
                compose.runOnIdle { state.reconcile(ThreadArtefactMembershipSync.Row(freshRow.rowVersion, freshRow.raw.getValue("metadata").jsonObject)) }
                compose.waitUntil(30_000) { state.tabs.size == 3 && state.tabs.all { !it.open && !it.title.startsWith("Loading") && !it.title.startsWith("Unavailable") } }
                compose.onNodeWithTag("thread.artefacts.open").performClick()
                compose.onNodeWithText("Build 7 launch notes").assertExists()
                compose.onNodeWithText("Architecture sketch.excalidraw").assertExists()
                compose.onNodeWithText("Thread membership.html").assertExists()
                val listBitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "thread-membership-fresh-list.png").outputStream().use { listBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; listBitmap.recycle()
                compose.onNodeWithText("Done").performClick()
                // Local selection and exact revisions stay local when another client removes an association.
                val previousSelection = state.selected
                runBlocking {
                    val canonical = api.thread(w.id, w.appNpub, target.threadId!!)
                    val base = ThreadArtefactMembershipSync.Row(canonical.rowVersion, canonical.raw.getValue("metadata").jsonObject)
                    api.updateThreadMetadata(w.id, w.appNpub, canonical.id, base,
                        ThreadArtefactMembership.metadata(base.metadata, readSecond().filter { it.id != SampleIds.BOARD_FILE }))
                }
                compose.waitUntil(20_000) { state.members.size == 2 }
                assertEquals(previousSelection, state.selected)
                assertEquals(2, readSecond().size)
                // Close local views before shared removal; closing never changes membership.
                compose.waitUntil(30_000) { state.tabs.filter { it.open }.all { it.checkpoint != null } }
                compose.onNodeWithTag("thread.artefacts.open").performClick()
                for (id in listOf(SampleIds.CANVAS_DOC, html)) {
                    compose.onNodeWithTag("thread.artefacts.remove.$id").performScrollTo().performClick()
                    compose.waitUntil(20_000) { state.members.none { it.id == id } && !state.busy }
                }
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithTag("thread.artefacts").assertDoesNotExist()
                compose.onNodeWithTag("thread.artefacts.open").assertContentDescriptionEquals("Thread artefacts (0)").assertIsDisplayed()
                assertTrue(readSecond().isEmpty())
                assertNotNull(runBlocking { api.doc(w.id, w.appNpub, SampleIds.CANVAS_DOC) })
                assertNotNull(runBlocking { api.client.request(path = TowerAPI.base(w.id) + "/files/$html", appNpub = w.appNpub) })
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitForIdle()
                compose.onNodeWithTag("thread.artefacts").assertDoesNotExist()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "thread-membership-empty.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            } finally { second.close(); secondDir.deleteRecursively() }
        }
    }
}
