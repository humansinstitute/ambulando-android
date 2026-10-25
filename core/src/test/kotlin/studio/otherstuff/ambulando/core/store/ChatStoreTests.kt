package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.ThreadRecord

class ChatStoreTests {
    private class Harness(val store: LocalStore, val connection: ConnectionRecord) {
        fun target(thread: String? = null) = ChatTarget(connection.id, W, Fixtures.CHANNEL_1, thread)
        suspend fun enqueue(body: String, thread: String? = null, signer: String = TestKeys.NPUB) =
            store.writer.enqueueSend(target(thread), connection.origin, signer, Fixtures.APP_NPUB, body, emptyList())
    }

    private suspend fun harness(dir: java.io.File = tempDir()): Harness {
        val store = StoreFixtures.store(dir)
        val connection = StoreFixtures.connect(store)
        store.writer.replaceWorkspaces(connection.id, listOf(StoreFixtures.workspace(W, "Studio")))
        return Harness(store, connection)
    }

    @Test fun draftsArePerTargetAndSurviveRelaunch() = runBlocking {
        val dir = tempDir()
        val channel: ChatTarget
        val thread: ChatTarget
        harness(dir).let { h ->
            channel = h.target()
            thread = h.target("00000000-0000-4000-8000-0000000000f1")
            h.store.writer.saveDraft(channel, Draft("channel text", emptyList()))
            h.store.writer.saveDraft(thread, Draft("reply @Me", listOf(Mention(MentionType.PERSON, TestKeys.NPUB, "Me"))))
            h.store.close()
        }
        StoreFixtures.store(dir).use { store ->
            assertEquals("channel text", store.reader.draft(channel)?.text)
            val restored = store.reader.draft(thread)!!
            assertEquals("reply @Me", restored.text)
            assertEquals(listOf(Mention(MentionType.PERSON, TestKeys.NPUB, "Me")), restored.mentions)
            assertNotNull(restored.updatedAt)
            assertNull(store.reader.draft(ChatTarget(channel.connectionId, W, Fixtures.CHANNEL_2)))
            assertNull("another connection never sees it", store.reader.draft(channel.copy(connectionId = "other")))
            store.writer.saveDraft(channel, Draft("   ", emptyList()))
            assertNull("a blank draft is cleared", store.reader.draft(channel))
        }
    }

    @Test fun replaceDraftTokenEditsOnlyThatTargetsDraft() = runBlocking {
        harness().store.use { store ->
            val h = Harness(store, store.reader.connections().first())
            store.writer.saveDraft(h.target(), Draft("look [upload:1]", emptyList()))
            assertTrue(store.writer.replaceDraftToken(h.target(), "[upload:1]", "![photo](storage://x)"))
            assertEquals("look ![photo](storage://x)", store.reader.draft(h.target())?.text)
            assertFalse(store.writer.replaceDraftToken(h.target(), "[upload:1]", "again"))
            assertFalse(store.writer.replaceDraftToken(h.target("00000000-0000-4000-8000-0000000000f1"), "look", "x"))
        }
    }

    @Test fun enqueueMovesTheDraftIntoTheOutboxInOneTransaction() = runBlocking {
        val h = harness()
        h.store.use { store ->
            store.writer.saveDraft(h.target(), Draft("hello", emptyList()))
            val item = h.enqueue("hello")
            assertNull(store.reader.draft(h.target()))
            assertEquals(OutboxState.AWAITING_SIGNATURE, item.state)
            assertTrue(item.createThread)
            assertEquals(item, store.reader.outboxItem(item.id))
            assertEquals(listOf("hello"), store.reader.outbox(h.target()).map { it.body })
            assertTrue("replies are listed under their thread only", store.reader.outbox(h.target("00000000-0000-4000-8000-0000000000f1")).isEmpty())
        }
    }

    @Test fun outboxStateMachineFreezesOnceAndRetriesFromFailure() = runBlocking {
        val h = harness()
        h.store.use { store ->
            val item = h.enqueue("frozen")
            val attachments = listOf(PendingAttachment(filename = "a.png", mimeType = "image/png", sizeBytes = 3, sha256 = "00", staged = "s1"))
            store.writer.updateOutboxAttachments(item.id, attachments)
            assertEquals(attachments, store.reader.outboxItem(item.id)?.attachments)
            assertTrue(store.writer.freezeOutbox(item.id, "{}".toByteArray()))
            assertFalse("a frozen row keeps its original request", store.writer.freezeOutbox(item.id, "{\"x\":1}".toByteArray()))
            val frozen = store.reader.outboxItem(item.id)!!
            assertArrayEquals("{}".toByteArray(), frozen.request)
            assertEquals(OutboxState.QUEUED, frozen.state)
            // Attachments are never changed after the request is frozen.
            store.writer.updateOutboxAttachments(item.id, emptyList())
            assertEquals(attachments, store.reader.outboxItem(item.id)?.attachments)

            store.writer.markOutbox(item.id, OutboxState.SENDING)
            store.writer.markOutbox(item.id, OutboxState.FAILED, status = 422, code = "invalid", error = "Refused", countAttempt = true)
            val failed = store.reader.outboxItem(item.id)!!
            assertEquals(listOf<Any?>(OutboxState.FAILED, 1, 422, "invalid", "Refused"),
                listOf(failed.state, failed.attempts, failed.lastStatus, failed.lastCode, failed.lastError))
            store.writer.retryOutbox(item.id)
            assertEquals("signed rows go back to the queue", OutboxState.QUEUED, store.reader.outboxItem(item.id)?.state)
            // An unsigned failed row goes back to signing.
            val unsigned = h.enqueue("unsigned")
            store.writer.markOutbox(unsigned.id, OutboxState.FAILED)
            store.writer.retryOutbox(unsigned.id)
            assertEquals(OutboxState.AWAITING_SIGNATURE, store.reader.outboxItem(unsigned.id)?.state)
        }
    }

    @Test fun interruptedSendIsQueuedAgainAfterRelaunch() = runBlocking {
        val dir = tempDir()
        val h = harness(dir)
        val item = h.enqueue("in flight")
        h.store.writer.freezeOutbox(item.id, "{}".toByteArray())
        h.store.writer.markOutbox(item.id, OutboxState.SENDING)
        h.store.close()
        StoreFixtures.store(dir).use { store ->
            store.writer.resetInterruptedSends()
            assertEquals(OutboxState.QUEUED, store.reader.outboxItem(item.id)?.state)
        }
    }

    @Test fun discardRestoresTheDraftButNeverWhileSending() = runBlocking {
        val h = harness()
        h.store.use { store ->
            val body = "hi @[Me](mention:person:${TestKeys.NPUB})"
            val item = h.enqueue(body)
            store.writer.markOutbox(item.id, OutboxState.SENDING)
            store.writer.discardOutbox(item.id, restoreDraft = true)
            assertNotNull("a sending row is not discarded", store.reader.outboxItem(item.id))
            store.writer.markOutbox(item.id, OutboxState.FAILED)
            store.writer.discardOutbox(item.id, restoreDraft = true)
            assertNull(store.reader.outboxItem(item.id))
            val draft = store.reader.draft(h.target())!!
            assertEquals("hi @Me", draft.text)
            assertEquals(listOf(TestKeys.NPUB), draft.mentions.map { it.npub })
        }
    }

    @Test fun acknowledgementAndSyncedCopyRetireTheOutboxRow() = runBlocking {
        val h = harness()
        h.store.use { store ->
            val first = h.enqueue("first")
            val thread = "00000000-0000-4000-8000-0000000000f2"
            val ack = message("00000000-0000-4000-8000-0000000000a1", "first", "2026-10-01T00:00:00.000Z", thread = thread,
                source = "00000000-0000-4000-8000-0000000000a1", clientRequestId = first.id)
            store.writer.acknowledgeSend(first.id, ack, ThreadRecord(W, thread, Fixtures.CHANNEL_1, ack.id, "first", updatedAt = ack.createdAt))
            assertNull(store.reader.outboxItem(first.id))
            assertEquals("first", store.reader.thread(W, thread)?.title)
            // A synced copy carrying this sender's client_request_id retires the row (no duplicate message).
            val second = h.enqueue("second")
            store.writer.upsertMessages(listOf(message("00000000-0000-4000-8000-0000000000a2", "second", "2026-10-01T00:00:01.000Z",
                clientRequestId = second.id.uppercase())))
            assertNull(store.reader.outboxItem(second.id))
            // Another signer's copy never retires it.
            val third = h.enqueue("third")
            store.writer.upsertMessages(listOf(message("00000000-0000-4000-8000-0000000000a3", "third", "2026-10-01T00:00:02.000Z",
                sender = TestKeys.syntheticNpub(0x33), clientRequestId = third.id)))
            assertNotNull(store.reader.outboxItem(third.id))
        }
    }

    @Test fun rowsAreScopedToConnectionAndSigner() = runBlocking {
        val h = harness()
        h.store.use { store ->
            val other = StoreFixtures.connect(store, origin = "https://other.example.com")
            store.writer.enqueueSend(ChatTarget(other.id, W, Fixtures.CHANNEL_1), other.origin, TestKeys.NPUB, Fixtures.APP_NPUB, "foreign", emptyList())
            h.enqueue("other signer", signer = TestKeys.syntheticNpub(0x34))
            h.enqueue("mine")
            assertEquals(listOf("mine"), store.reader.outbox(h.connection.id, TestKeys.NPUB).map { it.body })
            assertEquals(2, store.reader.outboxCount(h.connection.id))
        }
    }

    @Test fun reconcileNeverTouchesMessagesAfterTheCoveredRange() = runBlocking {
        StoreFixtures.store().use { store ->
            val early = message("m1", "early", "2026-10-01T00:00:00.000Z")
            val acknowledged = message("m2", "acknowledged later", "2026-10-02T00:00:00.000Z")
            store.writer.upsertMessages(listOf(early, acknowledged))
            val flagged = store.writer.reconcileMessages(W, Fixtures.CHANNEL_1, null, null, MessageKey("2026-10-01T12:00:00.000Z", "z"), emptySet())
            assertEquals(1, flagged)
            assertEquals(false, store.reader.message(W, "m2")?.accessRemoved)
            assertEquals(true, store.reader.message(W, "m1")?.accessRemoved)
        }
    }

    @Test fun channelSyncCompleteOnlyMovesForward() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.saveChannelSync(W, Fixtures.CHANNEL_1, "c1", MessageKey("2026-10-01T00:00:00.000Z", "m1"), complete = true)
            store.writer.saveChannelSync(W, Fixtures.CHANNEL_1, "c2", null, complete = false)
            val state = store.reader.channelSync(W, Fixtures.CHANNEL_1)!!
            assertEquals("c2", state.resumeCursor)
            assertNull(state.resumeAfterId)
            assertTrue(state.complete)
        }
    }

    /** Tower threads without a source message still appear in the channel, represented by their earliest stored message. */
    @Test fun threadWithoutSourceAppearsByEarliestMessage() = runBlocking {
        StoreFixtures.store().use { store ->
            val normalRoot = Fixtures.newId()
            val normalThread = Fixtures.newId()
            val sourceless = Fixtures.newId()
            store.writer.upsertMessages(listOf(
                message(normalRoot, "normal root", "2026-10-24T01:00:00.000Z", thread = normalThread, source = normalRoot),
                message(Fixtures.newId(), "normal reply", "2026-10-24T01:01:00.000Z", thread = normalThread, source = normalRoot),
                message(Fixtures.newId(), "sourceless first", "2026-10-24T02:00:00.000Z", thread = sourceless),
                message(Fixtures.newId(), "sourceless second", "2026-10-24T02:01:00.000Z", thread = sourceless),
                message(Fixtures.newId(), "plain", "2026-10-24T03:00:00.000Z"),
            ))
            val feed = store.reader.channelFeed(W, Fixtures.CHANNEL_1, 50)
            assertEquals(listOf("normal root", "sourceless first", "plain"), feed.map { it.message.body })
            assertEquals(1, feed.first { it.message.body == "sourceless first" }.replyCount)
            assertEquals("replies stay inside their thread", 1, feed.first { it.message.body == "normal root" }.replyCount)
            assertEquals(listOf("normal root", "normal reply"), store.reader.threadMessages(W, normalThread, 10).map { it.body })
            val threads = store.reader.channelThreads(W, Fixtures.CHANNEL_1)
            assertEquals(setOf(normalThread, sourceless), threads.map { it.id }.toSet())
            assertTrue(threads.all { it.replyCount == 1 })
        }
    }

    @Test fun threadsOfAnotherWorkspaceAreRejectedAndMembersReplaced() = runBlocking {
        StoreFixtures.store().use { store ->
            val t = ThreadRecord(StoreFixtures.W2, "th", Fixtures.CHANNEL_1, null, "foreign", updatedAt = "2026-10-01T00:00:00Z")
            assertEquals(0, store.writer.upsertThreads(W, listOf(t)))
            assertNull(store.reader.thread(W, "th"))
            val member = studio.otherstuff.ambulando.core.transport.MemberRecord(W, "a1", TestKeys.NPUB, "human", "Me")
            store.writer.replaceMembers(W, listOf(member))
            store.writer.replaceMembers(W, listOf(member.copy(displayName = "Renamed")))
            assertEquals(listOf("Renamed"), store.reader.members(W).map { it.displayName })
        }
    }
}
