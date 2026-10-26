package studio.otherstuff.ambulando.core.integration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.*

/** Real database, scheduler, signing and request replay against a synthetic Tower. */
class ConnectedWorkflowTest {
    @Test fun queuedSignedMessageSurvivesStoreReopenAndSendsExactlyOnce() = runBlocking {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val directory = tempDir()
        val engine = SyncEngine()
        var store = LocalStore(directory)
        try {
            val sync = WorkspaceSync(store, engine, identity, tower)
            val connection = sync.connect(tower.origin)
            sync.discover(connection)
            withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val target = ChatTarget(connection.id, workspace.id, SampleIds.GENERAL)
            store.writer.saveDraft(target, Draft("durable synthetic send", emptyList()))
            // Enqueue without starting the sender: emulate process exit between durable commit and network send.
            val queued = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, "durable synthetic send", emptyList(), emptyList())
            assertNull(store.reader.draft(target))
            store.writer.markOutbox(queued.id, OutboxState.SENDING)
            store.close()
            store = LocalStore(directory)
            store.writer.resetInterruptedSends(); store.writer.resetInterruptedWrites()
            assertEquals(queued.id, store.reader.outbox(target).single().id)
            val sender = OutboxSender(store, engine, identity, tower)
            sender.kick(connection)
            withTimeout(15_000) { engine.waitUntilIdle() }
            assertTrue(store.reader.outbox(target).isEmpty())
            val messages = store.reader.latestMessages(workspace.id, SampleIds.GENERAL, 500)
            assertEquals(1, messages.count { it.clientRequestId == queued.id && it.body == "durable synthetic send" })
            sender.kick(connection, force = true)
            withTimeout(15_000) { engine.waitUntilIdle() }
            assertEquals(1, store.reader.latestMessages(workspace.id, SampleIds.GENERAL, 500).count { it.clientRequestId == queued.id })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun acceptedWriteWithLostResponseUsesDurableReplayAndCreatesOneMessage() = runBlocking {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val engine = SyncEngine()
        val store = LocalStore(tempDir())
        var sends = 0
        val bodies = mutableListOf<ByteArray>()
        val observed = studio.otherstuff.ambulando.core.transport.ObservedTransport(object : studio.otherstuff.ambulando.core.transport.HttpTransport {
            override suspend fun send(request: studio.otherstuff.ambulando.core.transport.HttpRequest): studio.otherstuff.ambulando.core.transport.HttpResponse {
                val response = tower.send(request)
                if (request.method == "POST" && request.url.substringBefore('?').endsWith("/messages")) {
                    sends++; bodies.add(request.body!!.copyOf())
                    if (sends == 1) throw java.net.SocketTimeoutException("lost response after acceptance")
                }
                return response
            }
        }, studio.otherstuff.ambulando.core.transport.DiagnosticsLog())
        try {
            val sync = WorkspaceSync(store, engine, identity, observed)
            val connection = sync.connect(tower.origin)
            sync.discover(connection); withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val sender = OutboxSender(store, engine, identity, observed)
            sender.send("Lost-response replay", emptyList(), ChatTarget(connection.id, workspace.id, SampleIds.GENERAL), connection, workspace)
            withTimeout(15_000) { engine.waitUntilIdle() }
            assertEquals(2, sends)
            assertArrayEquals(bodies[0], bodies[1])
            assertTrue(store.reader.outbox(connection.id, connection.signerNpub).isEmpty())
            assertEquals(1, store.reader.latestMessages(workspace.id, SampleIds.GENERAL, 500).count { it.body == "Lost-response replay" })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun accessRemovedDocumentNeverSchedulesAnotherBodyRead() = runBlocking {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val engine = SyncEngine()
        val store = LocalStore(tempDir())
        try {
            val sync = WorkspaceSync(store, engine, identity, tower)
            val connection = sync.connect(tower.origin)
            sync.discover(connection); withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            sync.refreshDocs(connection, workspace); withTimeout(15_000) { engine.waitUntilIdle() }
            val doc = store.reader.docs(workspace.id).first()
            sync.refreshDoc(connection, workspace, doc.id); withTimeout(15_000) { engine.waitUntilIdle() }
            val body = store.reader.docBody(workspace.id, doc.id)!!
            store.writer.upsertDocs(workspace.id, emptyList(), completeChannel = doc.channelId)
            assertTrue(store.reader.doc(workspace.id, doc.id)!!.accessRemoved)
            tower.offline = true
            sync.refreshDoc(connection, workspace, doc.id, force = true)
            withTimeout(15_000) { engine.waitUntilIdle() }
            assertFalse(engine.status.value.errors.containsKey(WorkspaceSync.docKey(workspace.id, doc.id)))
            assertArrayEquals(body.bytes, store.reader.docBody(workspace.id, doc.id)!!.bytes)
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
    @Test fun accessRemovalKeepsQueuedTextButNeverAttemptsSend() = runBlocking {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val engine = SyncEngine()
        val store = LocalStore(tempDir())
        try {
            val sync = WorkspaceSync(store, engine, identity, tower)
            val connection = sync.connect(tower.origin)
            sync.discover(connection); withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val target = ChatTarget(connection.id, workspace.id, SampleIds.GENERAL)
            val queued = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, "retained unsent text", emptyList(), emptyList())
            store.writer.markAccessRemoved(workspace.id, AccessTarget.Channel(SampleIds.GENERAL))
            tower.offline = true
            OutboxSender(store, engine, identity, tower).kick(connection)
            withTimeout(15_000) { engine.waitUntilIdle() }
            val kept = store.reader.outboxItem(queued.id)!!
            assertEquals("retained unsent text", kept.body)
            assertEquals(OutboxState.FAILED, kept.state)
            assertEquals("access_removed", kept.lastCode)
            assertEquals(0, kept.attempts)
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

}
