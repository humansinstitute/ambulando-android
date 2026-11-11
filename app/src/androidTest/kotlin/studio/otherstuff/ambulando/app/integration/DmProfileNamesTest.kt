package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.profiles.ProfileRecord
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DmProfileNamesTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun profileArrivalAndUpdateRefreshHeaderComposerAndSidebarWithoutChangingDraftOrTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val connection = model.connection!!
            runBlocking { model.store.writer.clearProfiles(connection.id) }
            val viewer = connection.signerNpub!!
            val other = NostrKeys.npub("d1".repeat(32))!!
            val scope = model.channels.first { it.id == SampleIds.GENERAL }.scopeId
            model.sampleTower!!.administration.viewer = studio.otherstuff.ambulando.core.sample.SampleViewer.ADMIN
            val dm = runBlocking {
                studio.otherstuff.ambulando.core.management.ScopeChannelManager(model.store, model.identity, model.workspaceTransport)
                    .createDirectMessage(connection, model.workspace!!, scope, other)
            }
            val id = dm.id
            compose.waitUntil(20_000) { model.channels.any { it.id == id } }
            compose.runOnIdle { model.selectContext(scope, id); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #Unknown person").fetchSemanticsNodes().isNotEmpty() }
            fun profile(name: String, at: Long) = runBlocking {
                model.store.writer.recordProfileCheck(connection.id, listOf(ProfileRecord("d1".repeat(32), other,
                    displayName = name, eventId = at.toString().padStart(64, '0'), eventCreatedAt = at,
                    status = ProfileRecord.Status.FOUND, checkedAt = Instant.now())), mapOf("d1".repeat(32) to other), Instant.now())
            }
            profile("Profile arrival", 100)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #Profile arrival").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("#Profile arrival").onFirst().assertIsDisplayed()
            profile("Profile updated", 200)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #Profile updated").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Keep this DM draft")
            profile("Newest profile", 300)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("#Newest profile").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Keep this DM draft")
            compose.onNodeWithTag("context.bar").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Newest profile").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(id, model.context.channelId)
            val stored = runBlocking { model.store.reader.channels(SampleIds.STUDIO).first { it.id == id } }
            assertEquals("DM: $other", stored.name)
            assertEquals(listOf(viewer, other).sorted(), SidebarNavigation.participants(NavChannel(id, scope, stored.name, stored.kind, raw = stored.raw)))
        }
    }
}
