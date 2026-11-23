package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.store.*
import java.time.Instant

class AgentProgressTests {
    private val thread = "00000000-0000-4000-8000-000000000010"
    private val otherThread = "00000000-0000-4000-8000-000000000011"
    private val now = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private fun row(sequence: Long = 1, state: String = "working", extras: Map<String, Any?> = emptyMap()) = AgentActivityRecord.parse(Fixtures.obj(mapOf(
        "id" to "00000000-0000-4000-8000-000000000012", "workspace_id" to Fixtures.WORKSPACE_ID,
        "channel_id" to Fixtures.CHANNEL_1, "thread_id" to thread, "activity_id" to "activity", "turn_id" to "turn",
        "visibility" to "user_visible", "sequence" to sequence, "state" to state,
        "lease_expires_at" to "2026-10-01T00:03:00Z", "created_at" to "2026-10-01T00:00:00Z"
    ) + extras))
    private fun entry(sequence: Int, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "activity_id" to "activity", "turn_id" to "turn", "sequence" to sequence, "body" to "Update $sequence",
        "visibility" to "user_visible", "state" to "working"
    ) + extra

    @Test fun publicHistoryRequiresMatchingTurnAndActivityAndOrdersDeduplicatedSequences() {
        val activity = row(extras = mapOf("commentary_history" to listOf(entry(4), entry(2), entry(4),
            entry(1, mapOf("visibility" to "internal")), entry(3, mapOf("turn_id" to "other")),
            entry(5, mapOf("activity_id" to "foreign")), entry(6, mapOf("state" to "completed")))))
        assertEquals(listOf(2L, 4L), activity.commentary.map { it.sequence })
    }
    @Test fun terminalWinsReplayAndOlderPagesMergeWithoutReplacingCurrentState() {
        val finished = row(10, "completed", mapOf("commentary_history" to listOf(entry(8)), "commentary_next_before_sequence" to 8))
        val older = row(3, extras = mapOf("commentary_history" to listOf(entry(2)), "commentary_next_before_sequence" to null))
        val merged = mergeAgentProgress(finished, older)
        assertEquals("completed", merged.state)
        assertEquals(listOf(2L, 8L), merged.commentary.map { it.sequence })
        assertNull(merged.historyBefore)
        assertEquals(merged.raw, mergeAgentProgress(merged, older).raw)
        assertEquals(merged.raw, mergeAgentProgress(older, finished).raw)
    }
    @Test fun higherLeaseEpochCanRecoverButSameEpochExpiryCannotResurrect() {
        val expired = row(4, extras = mapOf("lease_epoch" to 1, "expired_at" to "2026-10-01T00:01:00Z"))
        val heartbeat = row(4, extras = mapOf("lease_epoch" to 1, "lease_sequence" to 9))
        assertEquals("Stopped responding", mergeAgentProgress(expired, heartbeat).progressStatus(now))
        val recovered = row(4, extras = mapOf("lease_epoch" to 2, "lease_sequence" to 1))
        assertEquals("Working", mergeAgentProgress(expired, recovered).progressStatus(now))
        assertEquals("Stopped responding", recovered.progressStatus(now + 195_000))
        assertEquals("Completed", row(state = "completed").progressStatus(now + 900_000))
        assertEquals("Failed", row(state = "failed").progressStatus(now))
    }
    @Test fun serverClockAndMissingLeaseNeverPromoteRetainedHistoryToWorking() {
        assertEquals("Working", row(extras = mapOf("android_server_time" to "2026-10-01T00:00:00Z", "android_received_at" to now + 900_000)).progressStatus(now + 900_000))
        assertEquals("Stopped responding", row(extras = mapOf("lease_expires_at" to null)).progressStatus(now))
        assertEquals("Message received", row(state = "accepted").progressStatus(now))
        assertEquals("Waiting for you", row(state = "waiting").progressStatus(now))
        assertEquals("Reviewing", row(state = "reviewing", extras = mapOf("label" to "Reviewing")).progressStatus(now))
        assertEquals("Queued · position 2", row(state = "queued", extras = mapOf("queue_position" to 2)).progressStatus(now))
    }
    @Test fun mismatchedThreadOrTurnCannotMergeIntoLifecycle() {
        val original = row()
        assertEquals(original, mergeAgentProgress(original, row(10, extras = mapOf("thread_id" to otherThread))))
        assertEquals(original, mergeAgentProgress(original, row(10, extras = mapOf("turn_id" to "other"))))
    }
    @Test fun triggerAssociationRejectsDifferentWorkspaceChannelThreadAndInheritedFork() {
        val message = StoreFixtures.message("00000000-0000-4000-8000-000000000031", "Trigger", "2026-10-01T00:00:00Z", thread = thread)
        val activity = row(extras = mapOf("trigger_message_id" to message.id))
        assertTrue(activity.belongsToTrigger(message, thread))
        assertFalse(activity.belongsToTrigger(message, otherThread))
        assertFalse(activity.belongsToTrigger(message.copy(workspaceId = Fixtures.WORKSPACE_ID_2), thread))
        assertFalse(activity.belongsToTrigger(message.copy(channelId = Fixtures.CHANNEL_2), thread))
        assertFalse(activity.belongsToTrigger(message.copy(effectiveThreadId = otherThread), otherThread))
    }
    @Test fun retainedEarlierOrExpiredRunsNeverKeepDeckWorkingBadgeAlive() {
        val old = row(extras = mapOf("id" to "00000000-0000-4000-8000-000000000021", "activity_id" to "old"))
        assertEquals(setOf("thread:$thread"), studio.otherstuff.ambulando.core.deck.DeckActivity.workingKeys(listOf(old), now))
        val done = row(state = "completed", extras = mapOf("activity_id" to "new", "created_at" to "2026-10-01T00:01:00Z"))
        assertTrue(studio.otherstuff.ambulando.core.deck.DeckActivity.workingKeys(listOf(old, done), now).isEmpty())
        assertTrue(studio.otherstuff.ambulando.core.deck.DeckActivity.workingKeys(listOf(old), now + 195_000).isEmpty())
    }
    @Test fun currentSelectionDoesNotResurrectOlderWorkingOrDisplaceActiveWithQueue() {
        val old = row(extras = mapOf("id" to "00000000-0000-4000-8000-000000000021", "activity_id" to "old"))
        val completed = row(state = "completed", extras = mapOf("activity_id" to "new", "created_at" to "2026-10-01T00:01:00Z"))
        assertEquals(setOf(completed.id), currentAgentProgress(listOf(old, completed), now))
        val queued = row(state = "queued", extras = mapOf("id" to "00000000-0000-4000-8000-000000000022", "activity_id" to "queue", "created_at" to "2026-10-01T00:02:00Z"))
        assertEquals(setOf(old.id, queued.id), currentAgentProgress(listOf(old, queued), now))
    }
    @Test fun completedPublicHistorySurvivesDatabaseReopen() = runBlocking {
        val dir = studio.otherstuff.ambulando.core.support.tempDir()
        StoreFixtures.store(dir).use { store ->
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1,
                listOf(row(10, "completed", mapOf("commentary_history" to listOf(entry(3))))))
        }
        StoreFixtures.store(dir).use { store ->
            val restored = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single()
            assertEquals("Completed", restored.progressStatus(now))
            assertEquals(listOf("Update 3"), restored.commentary.map { it.body })
        }
    }
    @Test fun rollingRecoveryKeepsCompletedHistoryAndExactThreadSelection() = runBlocking {
        StoreFixtures.store().use { store ->
            val original = row(10, "completed", mapOf("commentary_history" to listOf(entry(8))))
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, listOf(original))
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, emptyList())
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, listOf(row(2)))
            assertEquals("completed", store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single().state)
            assertTrue(store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, otherThread).isEmpty())
            assertEquals(listOf(8L), store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single().commentary.map { it.sequence })
        }
    }
}
