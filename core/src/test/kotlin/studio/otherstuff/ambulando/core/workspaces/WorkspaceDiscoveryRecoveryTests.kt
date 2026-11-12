package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

/** Real signed listing jobs and persisted catalog/preferences; recovery never depends on an active runtime. */
class WorkspaceDiscoveryRecoveryTests {
    private class ListingTransport : HttpTransport {
        @Volatile var failOrigin: String? = null
        val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequest>()
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val auth = decodeAuthorization(request)
            check(SignatureVerifier.verify(auth) && auth.tags.first { it[0] == "u" }[1] == request.url)
            val uri = URI(request.url); val origin = "${uri.scheme}://${uri.authority}"
            check(uri.path == TowerAPI.WORKSPACES_PATH)
            if (origin == failOrigin) return HttpResponse(503,"{}".toByteArray())
            val second = uri.query?.contains("cursor=next") == true
            val row = Fixtures.workspace(if (second) StoreFixtures.W2 else StoreFixtures.W, "$origin ${if (second) "extra" else "first"}")
            return HttpResponse(200,Fixtures.json(mapOf("workspaces" to listOf(row),"next_cursor" to if (second) null else "next")))
        }
    }
    @Test fun freshExplicitConnectionTransientFailureCanRetryBeforeAnyWorkspaceIsEnabled() = runBlocking {
        val identity = makeIdentity(); val catalog = StoreFixtures.store(); val engine = SyncEngine()
        val http = ListingTransport(); val sync = WorkspaceSync(catalog,engine,identity,http)
        val discovery = DiscoveryHook { c,p,f -> sync.discoverListing(c,p,f) {} }
        try {
            WorkspaceCatalog.prepareExplicitConnection(catalog)
            val connection = StoreFixtures.connect(catalog,"https://first.example")
            http.failOrigin = connection.origin
            WorkspaceCatalog.retryListings(catalog,connection.signerNpub,discovery)
            withTimeout(10_000) { engine.waitUntilIdle() }
            assertTrue(WorkspaceSync.discoverKey(connection.id) in engine.snapshot().connectionFailures)
            assertTrue(catalog.reader.workspaces(connection.id).isEmpty())
            assertTrue(WorkspaceCatalog.preferences(catalog)!!.enabled.isEmpty())
            assertNull(WorkspaceCatalog.selected(catalog))
            http.failOrigin = null
            WorkspaceCatalog.retryListings(catalog,connection.signerNpub,discovery)
            withTimeout(10_000) { engine.waitUntilIdle() }
            assertEquals(2,catalog.reader.workspaces(connection.id).size)
            assertFalse(WorkspaceSync.discoverKey(connection.id) in engine.snapshot().connectionFailures)
            assertTrue(WorkspaceCatalog.preferences(catalog)!!.enabled.isEmpty())
            assertNull(WorkspaceCatalog.selected(catalog))
        } finally { engine.close(); catalog.close() }
    }
    @Test fun retryCapturesAllOwnedConnectionsWhileAKeepsSelectionAndBWasPending() = runBlocking {
        val identity = makeIdentity(); val catalog = StoreFixtures.store(); val engine = SyncEngine()
        val http = ListingTransport(); val sync = WorkspaceSync(catalog,engine,identity,http)
        val discovery = DiscoveryHook { c,p,f -> sync.discoverListing(c,p,f) {} }
        try {
            val a = StoreFixtures.connect(catalog,"https://one.example")
            val workspace = StoreFixtures.workspace(StoreFixtures.W,"A selected")
            catalog.writer.replaceWorkspaces(a.id,listOf(workspace))
            val selected = WorkspaceBoundary.of(a,workspace).id
            val prefs = WorkspacePreferences().withEnabled(selected,true)
            WorkspaceCatalog.save(catalog,prefs); WorkspaceCatalog.saveSelected(catalog,selected)
            val b = StoreFixtures.connect(catalog,"https://two.example")
            StoreFixtures.connect(catalog,"https://another-identity.example",TestKeys.syntheticNpub(0x11))
            http.failOrigin = b.origin
            WorkspaceCatalog.retryListings(catalog,a.signerNpub,discovery)
            withTimeout(10_000) { engine.waitUntilIdle() }
            assertTrue(catalog.reader.workspaces(b.id).isEmpty())
            assertEquals(selected,WorkspaceCatalog.selected(catalog))
            http.failOrigin = null
            WorkspaceCatalog.retryListings(catalog,a.signerNpub,discovery)
            withTimeout(10_000) { engine.waitUntilIdle() }
            val aRows = catalog.reader.workspaces(a.id); val bRows = catalog.reader.workspaces(b.id)
            assertEquals(2,aRows.size); assertEquals(2,bRows.size)
            assertEquals(prefs,WorkspaceCatalog.preferences(catalog)); assertEquals(selected,WorkspaceCatalog.selected(catalog))
            assertNotEquals(WorkspaceBoundary.of(a,aRows.first()).id,WorkspaceBoundary.of(b,bRows.first()).id)
            assertFalse(http.requests.any { it.url.startsWith("https://another-identity.example") })
        } finally { engine.close(); catalog.close() }
    }
}
