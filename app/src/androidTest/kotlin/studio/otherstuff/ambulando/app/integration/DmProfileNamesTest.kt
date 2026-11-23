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
            val otherPublicKey = studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(java.util.UUID.randomUUID().toString().toByteArray())
            val other = NostrKeys.npub(otherPublicKey)!!
            val scope = model.channels.first { it.id == SampleIds.GENERAL }.scopeId
            model.sampleTower!!.administration.viewer = studio.otherstuff.ambulando.core.sample.SampleViewer.ADMIN
            val dm = runBlocking {
                studio.otherstuff.ambulando.core.management.ScopeChannelManager(model.store, model.identity, model.workspaceTransport)
                    .createDirectMessage(connection, model.workspace!!, scope, other)
            }
            val id = dm.id
            // The sample surface authority has a separate record registry from the sample admin API.
            model.sampleTower!!.surfaces.seedRecord(id, "channel", dm.name, id, scope)
            compose.waitUntil(20_000) { model.channels.any { it.id == id } }
            compose.runOnIdle { model.selectContext(scope, id); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #$other").fetchSemanticsNodes().isNotEmpty() }
            fun profile(name: String, at: Long) = runBlocking {
                model.store.writer.recordProfileCheck(connection.id, listOf(ProfileRecord(otherPublicKey, other,
                    displayName = name, eventId = at.toString().padStart(64, '0'), eventCreatedAt = at,
                    status = ProfileRecord.Status.FOUND, checkedAt = Instant.now())), mapOf(otherPublicKey to other), Instant.now())
            }
            profile("Profile arrival", 100)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #Profile arrival").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("#Profile arrival").onFirst().assertIsDisplayed()
            profile("Profile updated", 200)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Message #Profile updated").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Synthetic DM Deck activity")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, id, 20).any { it.message.body == "Synthetic DM Deck activity" } } }

            compose.onNodeWithTag("chat.composer").performTextReplacement("Keep this DM draft")
            profile("Newest profile", 300)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("#Newest profile").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Keep this DM draft")
            compose.onNodeWithTag("context.bar").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Newest profile").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.runOnIdle { model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.DECK }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.page.inbox").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            fun heading(label: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText("Inbox: #$label").fetchSemanticsNodes().isNotEmpty() }
            heading("Newest profile")
            runBlocking { model.store.writer.clearProfiles(connection.id) }
            heading(other)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Chat · #$other").fetchSemanticsNodes().isNotEmpty() }
            fun pinStep(block: () -> Unit) {
                try { block() } catch (failure: Throwable) {
                    val pins = runBlocking { model.store.reader.pinnedItems(connection.id, SampleIds.STUDIO) }
                    val writes = runBlocking { model.store.reader.workspaceWrites(connection.id, SampleIds.STUDIO) }
                    java.io.File(context.getExternalFilesDir(null), "dm-pin-evidence.txt").writeText(
                        "target=$id\ncontext=${model.context}\npins=" + pins.map { it.pin.resourceId to it.title } +
                        "\nwrites=" + writes.map { listOf(it.kind, it.targetId, it.state, it.lastError) } +
                        "\nmergedTree=" + compose.onRoot().printToString() +
                        "\nunmergedTree=" + compose.onRoot(useUnmergedTree = true).printToString())
                    throw failure
                }
            }
            // Pin while unnamed, then verify that this existing pin changes with the profile.
            compose.onNodeWithContentDescription("Pin #$other").performClick()
            pinStep { compose.waitUntil(20_000) { runBlocking { model.store.reader.pinnedItems(connection.id, SampleIds.STUDIO).any { it.pin.resourceId == id } } } }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.page.pinned").fetchSemanticsNodes().isNotEmpty() }
            profile("Deck arrival", 400)
            heading("Deck arrival")
            val snapshot = runBlocking { studio.otherstuff.ambulando.app.ui.deck.DeckInbox.load(
                model.store, SampleIds.STUDIO, viewer, model.context, listOf(id)) }
            assertEquals("Deck arrival", snapshot.channelLabels[id])
            assertEquals(model.channels.first { it.id == SampleIds.GENERAL }.name, snapshot.channelLabels[SampleIds.GENERAL])
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Chat · #Deck arrival").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.pinned").performClick()
            pinStep { compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("deck.card.pinned")))
                .performScrollToNode(hasTestTag("pin.row.$id")) }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Deck arrival") and hasAnyAncestor(hasTestTag("pin.row.$id")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            profile("Deck renamed", 500)
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Deck renamed") and hasAnyAncestor(hasTestTag("pin.row.$id")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            heading("Deck renamed")
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Chat · #Deck renamed").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.pinned").performClick()
            compose.onNodeWithTag("pin.drag.$id").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Keep this DM draft")
            // Labels are display-only: stored participants, raw title, pin target and draft remain intact.
            assertEquals(id, model.context.channelId)
            val stored = runBlocking { model.store.reader.channels(SampleIds.STUDIO).first { it.id == id } }
            assertEquals("DM: $other", stored.name)
            assertEquals(listOf(viewer, other).sorted(), SidebarNavigation.participants(NavChannel(id, scope, stored.name, stored.kind, raw = stored.raw)))
        }
    }
}
