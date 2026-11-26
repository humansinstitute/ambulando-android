package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.serialization.json.*
import java.util.UUID

class ThreadArtefactTitleTest {
    @Test fun freshIndependentStoreResolvesReadableTitlesThroughSyncWithoutBodiesAndDeniedSourceDoesNotBlockOthers() = runBlocking {
        val serverDir = tempDir(); val firstDir = tempDir(); val secondDir = tempDir()
        val tower = SampleTower(serverDir).also { it.prepare(TestKeys.NPUB) }
        val identity = makeIdentity(); val engine = SyncEngine()
        val first = LocalStore(firstDir); val second = LocalStore(secondDir)
        val requests = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { synchronized(requests) { requests.add(pathOf(request.url)) }; return tower.send(request) }
        }
        try {
            val sync = WorkspaceSync(first, engine, identity, transport)
            val connection = sync.connect(tower.origin)
            sync.discover(connection); withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = first.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val html = tower.work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, "Readable HTML title.html", "<p>Private body</p>".toByteArray(), "text/html")
            val api = sync.api(connection)
            val thread = api.thread(workspace.id, workspace.appNpub, SampleIds.thread(39))
            val members = listOf(ThreadArtefactMember("doc", SampleIds.CANVAS_DOC), ThreadArtefactMember("file", SampleIds.BOARD_FILE), ThreadArtefactMember("file", html), ThreadArtefactMember("file", UUID.randomUUID().toString()))
            val base = ThreadArtefactMembershipSync.Row(thread.rowVersion, thread.raw.getValue("metadata").jsonObject)
            api.updateThreadMetadata(workspace.id, workspace.appNpub, thread.id, base, ThreadArtefactMembership.metadata(base.metadata, members))
            assertNull(second.reader.thread(workspace.id, thread.id))
            val secondSync = WorkspaceSync(second, engine, identity, transport)
            val canonical = secondSync.api(connection).thread(workspace.id, workspace.appNpub, thread.id)
            second.writer.upsertThreads(workspace.id, listOf(canonical))
            val discovered = ThreadArtefactMembership.read(second.reader.thread(workspace.id, thread.id)!!.raw.getValue("metadata").jsonObject)!!
            synchronized(requests) { requests.clear() }
            val titles = discovered.map { runCatching { secondSync.threadArtefactTitle(connection, workspace, it) }.getOrNull() }
            assertEquals(listOf("Build 7 launch notes", "Architecture sketch.excalidraw", "Readable HTML title.html", null), titles)
            assertNull(second.reader.docBody(workspace.id, SampleIds.CANVAS_DOC))
            assertNotNull(second.reader.doc(workspace.id, SampleIds.CANVAS_DOC))
            assertNotNull(second.reader.file(workspace.id, html))
            assertTrue(synchronized(requests) { requests.none { it.endsWith("/body") || it.endsWith("/object") || it.contains("/versions") || it.contains("/storage/") } })
        } finally { engine.cancelAll(); engine.close(); first.close(); second.close(); serverDir.deleteRecursively(); firstDir.deleteRecursively(); secondDir.deleteRecursively() }
    }
}
