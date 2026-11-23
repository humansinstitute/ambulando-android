package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.withArtefactAccess
import studio.otherstuff.ambulando.core.surfaces.fencedArtefactAPI
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.workspaces.RetainedFileAccessTransport

class ArtefactAccessTest {
    private val w = StoreFixtures.W
    private val id = "00000000-0000-4000-8000-0000000000f1"
    private fun file() = FileRecord(w,id,Fixtures.SCOPE_A,Fixtures.CHANNEL_1,null,null,"Retained.html",
        contentType="text/html",sizeBytes=10,sha256=null,createdAt="",updatedAt="")

    @Test fun revokedRecordAndChannelNeverStartServerWorkAndRetainLocalHistory() = runBlocking {
        StoreFixtures.store().use { store ->
            val workspace = StoreFixtures.workspace(w,"Workspace")
            store.writer.upsertScopes(w,listOf(StoreFixtures.scope(Fixtures.SCOPE_A,"Scope")), complete = false)
            store.writer.upsertChannels(w,Fixtures.SCOPE_A,listOf(StoreFixtures.channel(Fixtures.CHANNEL_1,Fixtures.SCOPE_A,"Channel")), complete = false)
            store.writer.upsertFiles(w,listOf(file()))
            var requests = 0
            assertEquals("received",store.reader.withArtefactAccess(workspace,id) { requests++; "received" })
            store.writer.markAccessRemoved(w,AccessTarget.File(id))
            assertTrue(runCatching { store.reader.withArtefactAccess(workspace,id) { requests++ } }.isFailure)
            assertEquals(1,requests)
            assertNotNull(store.reader.file(w,id))
            assertTrue(runCatching { store.writer.cacheVerifiedArtefact(file()) { true } }.isFailure)
            assertTrue(store.reader.file(w,id)!!.accessRemoved)
            store.writer.upsertFiles(w,listOf(file()))
            store.writer.markAccessRemoved(w,AccessTarget.Channel(Fixtures.CHANNEL_1))
            assertTrue(runCatching { store.reader.withArtefactAccess(workspace,id) { requests++ } }.isFailure)
            assertEquals(1,requests)
            assertNotNull(store.reader.file(w,id))
        }
    }
    @Test fun accessLostDuringRequestRejectsLateAuthorityWithoutDeletingHistory() = runBlocking {
        StoreFixtures.store().use { store ->
            val workspace = StoreFixtures.workspace(w,"Workspace")
            store.writer.upsertFiles(w,listOf(file()))
            val result = runCatching { store.reader.withArtefactAccess(workspace,id) {
                store.writer.markAccessRemoved(w,AccessTarget.File(id))
                "can_write:true"
            } }
            assertTrue(result.isFailure)
            assertTrue(store.reader.file(w,id)!!.accessRemoved)
            assertTrue(runCatching { store.reader.withArtefactAccess(workspace.copy(accessRemoved=true),"unknown") { error("must not request") } }.exceptionOrNull() is IllegalStateException)
        }
    }
    @Test fun suspendedFetchCannotPublishAfterWorkspaceOrBoundaryRemoval() = runBlocking {
        StoreFixtures.store().use { store ->
            val connection = StoreFixtures.connect(store)
            val workspace = StoreFixtures.workspace(w,"Workspace")
            store.writer.replaceWorkspaces(connection.id,listOf(workspace))
            store.writer.upsertFiles(w,listOf(file()))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var materialized: String? = null
            val result = async {
                runCatching {
                    val received = store.reader.withArtefactAccess(workspace,id) {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        "new authority"
                    }
                    materialized = received
                }
            }
            started.await()
            store.writer.markWorkspaceAccessRemoved(connection.id,w)
            release.complete(Unit)
            assertTrue(result.await().isFailure)
            assertNull(materialized)
            assertNotNull(store.reader.file(w,id))
            assertTrue(store.reader.workspaces(connection.id).single { it.id == w }.accessRemoved)
            // Workspace removal flags the workspace, retaining its File row unchanged.
            assertFalse(store.reader.file(w,id)!!.accessRemoved)
            // A stale boundary callback cannot authorize even an unknown record.
            assertTrue(runCatching { store.reader.withArtefactAccess(workspace,"unknown",{ false }) { "no request" } }.isFailure)
        }
    }
    @Test fun transportBlocksEveryVersionReadAndMutationAfterRevocationButAllowsRecovery() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertFiles(w,listOf(file()))
            var requests = 0
            val transport = RetainedFileAccessTransport(object : HttpTransport {
                override suspend fun send(request: HttpRequest): HttpResponse { requests++; return HttpResponse(200,byteArrayOf()) }
            },store,w)
            val url = "https://tower.example.com/api/v4/flightdeck-pg/workspaces/$w/files/$id"
            transport.send(HttpRequest("GET",url))
            store.writer.markAccessRemoved(w,AccessTarget.File(id))
            for (suffix in listOf("", "/html", "/versions", "/versions/00000000-0000-4000-8000-0000000000f2/object")) {
                assertTrue(runCatching { transport.send(HttpRequest("GET",url+suffix)) }.isFailure)
            }
            assertTrue(runCatching { transport.send(HttpRequest("POST",url+"/versions")) }.isFailure)
            assertEquals(1,requests)
            assertNotNull(store.reader.file(w,id))
            store.writer.upsertFiles(w,listOf(file()))
            transport.send(HttpRequest("GET",url))
            assertEquals(2,requests)
        }
    }
    @Test fun capturedApiRejectsUncooperativeResponseAfterBoundaryChangeAndNeverSendsAgain() = runBlocking {
        StoreFixtures.store().use { store ->
            val workspace = StoreFixtures.workspace(w,"Workspace")
            var current = true
            var requests = 0
            val api = TowerAPI(TowerClient(TowerOrigin("https://tower.example.com"),object : HttpTransport {
                override suspend fun send(request: HttpRequest): HttpResponse {
                    requests++
                    current = false
                    return HttpResponse(200,"late authority".toByteArray())
                }
            },null)).fencedArtefactAPI(store.reader,workspace,id) { current }
            val request = HttpRequest("GET","https://tower.example.com/api/v4/flightdeck-pg/workspaces/$w/files/$id")
            assertTrue(runCatching { api.client.transport.send(request) }.isFailure)
            assertEquals(1,requests)
            assertTrue(runCatching { api.client.transport.send(request) }.isFailure)
            assertEquals(1,requests)
        }
    }
    @Test fun localSearchLabelsRichFilesWithoutRawMimeAndKeepsRevokedHits() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertFiles(w,listOf(file()))
            assertEquals("HTML",store.reader.search(w,"Retained").single().snippet)
            store.writer.markAccessRemoved(w,AccessTarget.File(id))
            val hit = store.reader.search(w,"Retained").single()
            assertEquals("HTML",hit.snippet)
            assertTrue(hit.accessRemoved)
        }
    }
}
