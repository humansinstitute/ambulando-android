package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.pathOf

/** The document edit contract on the wire: protocol selection, session echoes, inspection and the explicit takeover request. */
class EditLeaseProtocolTest {
    private val docId = "00000000-0000-4000-8000-0000000000a3"
    private val session = "00000000-0000-4000-8000-0000000000f1"
    private val leaseId = "00000000-0000-4000-8000-0000000000ee"
    private val other = "00000000-0000-4000-8000-0000000000ef"

    private fun api(handler: (HttpRequest) -> Pair<Int, Any?>): Pair<TowerAPI, StubTransport> {
        val transport = StubTransport(handler)
        return runBlocking { TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity())) to transport }
    }

    private fun lease(sessionEcho: String? = session, marker: String? = DOCUMENT_EDIT_PROTOCOL) = 200 to buildMap<String, Any?> {
        put("lease", buildMap<String, Any?> {
            put("id", leaseId); put("lease_token", "tok"); put("entity_id", docId); put("expires_at", "soon")
            if (sessionEcho != null) put("editor_session_id", sessionEcho)
        })
        if (marker != null) put("document_edit_protocol", marker)
    }

    @Test fun anExclusiveAcquireSendsTheSessionAndProtocolAndKeepsTheAcknowledgement() = runBlocking {
        val (api, transport) = api { lease() }
        val acquired = api.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "document", docId, session)
        assertEquals(EditLease(leaseId, "tok", "soon", DOCUMENT_EDIT_PROTOCOL, session), acquired)
        val body = parseJsonObject(transport.recorded.last().body!!, "lease")
        assertEquals(DOCUMENT_EDIT_PROTOCOL, body.string("document_edit_protocol"))
        assertEquals(session, body.string("editor_session_id"))
        assertNull("no takeover unless asked", body["takeover"])
    }

    @Test fun aConcurrentAcquireMustBeAcknowledgedForThatExactSession() = runBlocking {
        val good = api { lease(marker = CONCURRENT_DOCUMENT_EDIT_PROTOCOL) }.first
        val ok = good.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "document", docId, session, documentProtocol = CONCURRENT_DOCUMENT_EDIT_PROTOCOL)
        assertEquals(CONCURRENT_DOCUMENT_EDIT_PROTOCOL, ok.documentEditProtocol)
        // A server that answers with the exclusive marker (or another session) is never treated as a concurrent grant.
        for (reply in listOf(lease(marker = DOCUMENT_EDIT_PROTOCOL), lease(marker = null), lease(sessionEcho = other, marker = CONCURRENT_DOCUMENT_EDIT_PROTOCOL), lease(sessionEcho = null, marker = CONCURRENT_DOCUMENT_EDIT_PROTOCOL))) {
            val bad = api { reply }.first
            try {
                bad.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "document", docId, session, documentProtocol = CONCURRENT_DOCUMENT_EDIT_PROTOCOL)
                fail("must not accept an unacknowledged concurrent protocol")
            } catch (e: LeaseError.Invalid) {
                assertEquals("concurrent_protocol_not_acknowledged", e.code)
            }
        }
    }

    @Test fun renewingAConcurrentLeaseChecksTheSessionEchoToo() = runBlocking {
        val held = EditLease(leaseId, "tok", "soon", CONCURRENT_DOCUMENT_EDIT_PROTOCOL, session)
        val good = api { 200 to mapOf("document_edit_protocol" to CONCURRENT_DOCUMENT_EDIT_PROTOCOL, "lease" to mapOf("lease_token" to "tok2", "expires_at" to "later", "editor_session_id" to session)) }.first
        assertEquals("tok2", good.renewLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, held).token)
        val bad = api { 200 to mapOf("lease" to mapOf("lease_token" to "tok2", "expires_at" to "later", "editor_session_id" to other)) }.first
        try { bad.renewLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, held); fail() } catch (e: LeaseError.Invalid) { assertEquals("concurrent_protocol_not_acknowledged", e.code) }
        val gone = api { 409 to mapOf("code" to "editor_superseded") }.first
        try { gone.renewLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, held); fail() } catch (e: LeaseError.Invalid) { assertEquals("editor_superseded", e.code) }
    }

    @Test fun takeoverNamesTheExactObservedLeaseAndNeedsTheExclusiveProtocolBack() = runBlocking {
        val (api, transport) = api { lease() }
        api.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "document", docId, session, takeoverLeaseId = other)
        val body = parseJsonObject(transport.recorded.last().body!!, "lease")
        assertEquals("true", body["takeover"].toString())
        assertEquals(other, body.string("expected_lease_id"))
        assertEquals(DOCUMENT_EDIT_PROTOCOL, body.string("document_edit_protocol"))
        val unsupported = api { lease(marker = null) }.first
        try { unsupported.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "document", docId, session, takeoverLeaseId = other); fail() }
        catch (e: LeaseError.Invalid) { assertEquals("takeover_not_supported", e.code) }
    }

    @Test fun inspectionReportsTheHolderIdentityAndWhichProtocolsAreAdvertised() = runBlocking {
        val (api, transport) = api { request ->
            assertTrue(pathOf(request.url).endsWith("/edit-leases"))
            200 to mapOf(
                "document_edit_protocol" to DOCUMENT_EDIT_PROTOCOL, "document_edit_protocols" to listOf(DOCUMENT_EDIT_PROTOCOL, CONCURRENT_DOCUMENT_EDIT_PROTOCOL),
                "lease" to mapOf("id" to leaseId, "holder_actor_npub" to Fixtures.APP_NPUB, "lease_token" to "never exposed"),
            )
        }
        val inspected = api.inspectDocumentEdit(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, docId)
        assertEquals(DocumentEditInspection(true, leaseId, Fixtures.APP_NPUB, true), inspected)
        val query = transport.recorded.last().url
        assertTrue(query.contains("entity_type=document") && query.contains("entity_id=$docId"))
        val plain = api { 200 to mapOf("document_edit_protocol" to DOCUMENT_EDIT_PROTOCOL) }.first.inspectDocumentEdit(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, docId)
        assertEquals(DocumentEditInspection(true, null, null, false), plain)
        assertFalse("an unrecognised marker is not the session protocol", api { 200 to mapOf("document_edit_protocol" to "other") }.first
            .inspectDocumentEdit(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, docId).supported)
    }
}
