package studio.otherstuff.ambulando.core.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.Ids
import studio.otherstuff.ambulando.core.TestKeys
import studio.otherstuff.ambulando.core.bytes
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.obj
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord

/** Deck's Inbox and Recent cards follow the web's overview projection. */
class DeckActivityTests {
    private val otherChannel = Ids.channel3

    private fun sources() = DeckActivity.Sources(
        channelScopes = mapOf(Ids.channel1 to Ids.scopeA, Ids.channel2 to Ids.scopeA, otherChannel to Ids.scopeB),
        threads = listOf(
            DeckActivity.ThreadActivity("t1", Ids.channel1, true, null, "Plan the launch\nmore", "ok", "2026-10-24T10:00:00.000Z", 3),
            DeckActivity.ThreadActivity("t2", otherChannel, true, "Ops sync", null, "done", "2026-10-24T12:00:00.000Z", 1),
        ),
        tasks = listOf(
            DeckActivity.TaskRow("k-open", Ids.scopeA, Ids.channel2, "Open task", null, "review", "2026-10-20T00:00:00.000Z", "2026-10-24T09:00:00.000Z"),
            DeckActivity.TaskRow("k-done-read", Ids.scopeA, Ids.channel1, "Finished", null, "done", "2026-10-20T00:00:00.000Z", "2026-10-24T11:00:00.000Z"),
            DeckActivity.TaskRow("k-done-unread", Ids.scopeA, Ids.channel1, "Finished unread", null, "done", "2026-10-20T00:00:00.000Z", "2026-10-24T08:00:00.000Z"),
            DeckActivity.TaskRow("k-archived", Ids.scopeA, Ids.channel1, "Archived", null, "archived", "2026-10-20T00:00:00.000Z", "2026-10-24T13:00:00.000Z"),
        ),
        taskComments = mapOf("k-open" to DeckActivity.CommentActivity(2, "2026-10-24T11:30:00.000Z")),
        docs = listOf(DeckActivity.DocRow("d1", null, Ids.channel1, "Spec", "Draft", false, "2026-10-20T00:00:00.000Z", "2026-10-23T00:00:00.000Z")),
        docComments = mapOf("d1" to DeckActivity.CommentActivity(1, "2026-10-22T00:00:00.000Z")),
        files = listOf(DeckActivity.FileRow("f1", Ids.scopeB, otherChannel, "plan.pdf", null, "application/pdf", false,
            "2026-10-21T00:00:00.000Z", "2026-10-21T00:00:00.000Z")),
        viewStates = listOf(DeckActivity.ViewStateRow("task", "k-done-unread", true), DeckActivity.ViewStateRow("thread", "t1", true)),
    )

    @Test fun homeShowsEverythingNewestFirstWithTheWebsTaskRules() {
        val items = DeckActivity.inbox(sources(), WorkContext.HOME)
        assertEquals("done tasks only while unread, archived never; comments move a task up",
            listOf("chat:t2", "task:k-open", "chat:t1", "task:k-done-unread", "document:d1", "file:f1"), items.map { it.id })
        val task = items.first { it.recordId == "k-open" }
        assertEquals("2 recent comments", task.reason)
        assertEquals("Review", task.subtitle)
        assertEquals("2026-10-24T11:30:00.000Z", task.activityAt)
        assertEquals("a conversation's first line is its title", "Plan the launch", items.first { it.recordId == "t1" }.title)
        assertTrue(items.first { it.recordId == "t1" }.isUnread)
        assertEquals("1 unresolved comment", items.first { it.recordId == "d1" }.reason)
        assertEquals("Uploaded file", items.first { it.recordId == "f1" }.reason)
        assertEquals("1 message", items.first { it.recordId == "t2" }.reason)
    }

    @Test fun scopeAndChannelNarrowTheFeed() {
        val scoped = DeckActivity.inbox(sources(), WorkContext(Ids.scopeA))
        assertEquals("a record without a scope id uses its channel's scope", setOf("k-open", "t1", "k-done-unread", "d1"), scoped.map { it.recordId }.toSet())
        assertEquals(listOf("k-open"), DeckActivity.inbox(sources(), WorkContext(Ids.scopeA, Ids.channel2)).map { it.recordId })
    }

    @Test fun typeTogglesAndSearchCombine() {
        val items = DeckActivity.inbox(sources(), WorkContext.HOME)
        assertEquals(listOf("k-open", "k-done-unread"), DeckActivity.filter(items, setOf(DeckActivity.Kind.TASK), "").map { it.recordId })
        assertEquals(listOf("t2"), DeckActivity.filter(items, DeckActivity.Kind.entries.toSet(), "  OPS   sync ").map { it.recordId })
        assertTrue("all types hidden shows nothing", DeckActivity.filter(items, emptySet(), "").isEmpty())
    }

    @Test fun recentChannelsKeepEachChannelsNewestConversationInScope() {
        val s = sources()
        val extra = DeckActivity.ThreadActivity("t3", Ids.channel1, true, "Older", null, "", "2026-10-23T00:00:00.000Z", 1)
        assertEquals(listOf("t2", "t1"), DeckActivity.recentChannels(s.threads + extra, null, s.channelScopes).map { it.threadId })
        assertEquals(listOf("t1"), DeckActivity.recentChannels(s.threads + extra, Ids.scopeA, s.channelScopes).map { it.threadId })
    }

    @Test fun workingAgentsAndMentionsAreShownAsTheWebDoes() {
        val activity = AgentActivityRecord.parse(obj("id" to Ids.uuid(1, "0008"), "workspace_id" to Ids.workspace, "channel_id" to Ids.channel1,
            "thread_id" to Ids.uuid(2, "0008"), "activity_id" to "a", "visibility" to "user_visible", "sequence" to 1, "state" to "working"))
        val s = sources().copy(threads = listOf(DeckActivity.ThreadActivity(Ids.uuid(2, "0008"), Ids.channel1, true, null,
            "## Ask @[Ann Lee](mention:person:npub1x) about @[Plan](mention:document:d1)", "ok", "2026-10-25T00:00:00.000Z", 2)),
            activities = listOf(activity))
        val chat = DeckActivity.inbox(s, WorkContext.HOME).first { it.kind == DeckActivity.Kind.CHAT }
        assertTrue(chat.isWorking)
        assertEquals("Ask @Ann Lee about Plan", chat.title)
        assertEquals("Conversation", DeckActivity.threadTitle(DeckActivity.ThreadActivity("x", Ids.channel1, false, " ", null, "", "", 1)))
        assertEquals("x".repeat(90) + "…", DeckActivity.threadTitle(DeckActivity.ThreadActivity("x", Ids.channel1, true, "x".repeat(100), null, "", "", 1)))
        assertEquals("In progress", DeckActivity.taskStateLabel("in_progress"))
        assertEquals("Cancelled", DeckActivity.taskStateLabel("cancelled"))
    }
}

class PersonalAgentLauncherTests {
    @Test fun actorSettingsOrderDeduplicationAndLaunchPolicy() {
        val data = bytes("settings" to mapOf("autopilot_agents" to listOf(
            mapOf("agent_npub" to TestKeys.npub, "url" to "https://browser.sample.invalid/agent"),
            mapOf("agent_npub" to TestKeys.npub, "url" to "https://duplicate.invalid"),
            mapOf("agent_npub" to "invalid", "url" to "https://invalid.invalid"),
        )))
        val rows = PersonalAgentLauncher.decode(data)
        assertEquals(1, rows.size)
        assertEquals("browser.sample.invalid", rows.first().safeURL?.host)
        val unsafe = bytes("settings" to mapOf("autopilot_agents" to listOf(mapOf("agent_npub" to TestKeys.npub, "url" to "http://insecure.invalid"))))
        assertNull(PersonalAgentLauncher.decode(unsafe).first().safeURL)
        assertTrue(runCatching { PersonalAgentLauncher.decode("{}".toByteArray()) }.isFailure)
        assertTrue(PersonalAgentLauncher.stateKey("first", "workspace") != PersonalAgentLauncher.stateKey("second", "workspace"))
        assertEquals(rows, PersonalAgentLauncher.decodeList(PersonalAgentLauncher.encodeList(rows)))
        assertEquals("/api/v4/flightdeck-pg/workspaces/${Ids.workspace}/me/autopilot-agents", PersonalAgentLauncher.path(Ids.workspace.uppercase()))
        assertNull(PersonalAgentLauncher.path("../x"))
    }
}
