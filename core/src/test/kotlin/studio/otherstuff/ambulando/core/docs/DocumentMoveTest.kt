package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.*

class DocumentMoveTest {
    private val w = StoreFixtures.W
    private val doc = "00000000-0000-4000-8000-0000000000d1"
    private val from = StoreFixtures.channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "Source")
    private val to = StoreFixtures.channel(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, "Destination")
    private val at = "2026-10-30T00:00:00Z"

    private fun row(channel: String, scope: String, version: Int) = mapOf("id" to doc, "workspace_id" to w, "channel_id" to channel, "scope_id" to scope,
        "title" to "Plan", "row_version" to version, "created_at" to at, "updated_at" to at)

    private fun draft(connection: ConnectionRecord, base: Int, state: DocDraft.State = DocDraft.State.EDITING) =
        DocDraft(connection.id, w, doc, "Plan", "text".toByteArray(), "text/markdown", base, "sha", "obj", state)

    private suspend fun <T> fixture(handler: (HttpRequest) -> Pair<Int, Any?>, block: suspend (WorkspaceSync, ConnectionRecord, WorkspaceRecord, StubTransport, LocalStore) -> T): T {
        val store = StoreFixtures.store(); val engine = SyncEngine()
        val connection = StoreFixtures.connect(store); val workspace = StoreFixtures.workspace(w, "Synthetic")
        val transport = StubTransport(handler)
        store.writer.upsertDocs(w, listOf(DocRecord.parse(Fixtures.obj(row(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, 4)))))
        try { return block(WorkspaceSync(store, engine, makeIdentity(), transport), connection, workspace, transport, store) } finally { engine.close(); store.close() }
    }

    @Test fun movesThroughTheTypedRouteUpdatesTheCacheAndCarriesADraftAlong() = runBlocking {
        fixture({ request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v4/flightdeck-pg/workspaces/$w/docs/$doc/move", pathOf(request.url))
            val body = parseJsonObject(request.body!!, "move")
            assertEquals(setOf("destination_channel_id", "destination_scope_id", "row_version"), body.keys)
            assertEquals(Fixtures.CHANNEL_2, body["destination_channel_id"]!!.jsonPrimitive.content)
            assertEquals(4, body["row_version"]!!.jsonPrimitive.content.toInt())
            200 to mapOf("doc" to row(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, 5))
        }) { sync, connection, workspace, _, store ->
            store.writer.saveDocDraft(draft(connection, 4))
            val result = sync.moveDocument(connection, workspace, doc, to, 4) as DocMoveResult.Moved
            assertEquals(Fixtures.CHANNEL_2, result.doc.channelId)
            assertNull(result.draftNote)
            assertEquals(Fixtures.CHANNEL_2, store.reader.doc(w, doc)?.channelId)
            assertEquals("the draft's base follows the document", 5, store.reader.docDraft(DocDraft.key(connection.id, w, doc))?.baseRowVersion)
        }
    }

    @Test fun staleDeniedAndSameDestinationAreReportedWithoutGuessing() = runBlocking {
        var reply: Pair<Int, Any?> = 409 to mapOf("code" to "stale_row_version")
        fixture({ reply }) { sync, connection, workspace, _, store ->
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.Stale)
            reply = 409 to mapOf("code" to "same_destination")
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.SameDestination)
            reply = 403 to mapOf("error" to "forbidden")
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.Denied)
            assertEquals("nothing moved in the cache", Fixtures.CHANNEL_1, store.reader.doc(w, doc)?.channelId)
        }
    }

    @Test fun aLostAnswerThatDidMoveItIsConfirmedByReadingTheDocumentBack() = runBlocking {
        fixture({ request -> if (request.method == "POST") 502 to null else 200 to mapOf("doc" to row(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, 5)) }) { sync, connection, workspace, _, store ->
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.Moved)
            assertEquals(Fixtures.CHANNEL_2, store.reader.doc(w, doc)?.channelId)
        }
    }

    @Test fun aLostAnswerWhereTowerStillHasItInPlaceIsOnlyThenReportedAsNotMoved() = runBlocking {
        fixture({ request -> if (request.method == "POST") 502 to null else 200 to mapOf("doc" to row(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, 4)) }) { sync, connection, workspace, _, store ->
            val result = sync.moveDocument(connection, workspace, doc, to, 4)
            assertTrue("still in place is only claimed after Tower says so: $result", result is DocMoveResult.NotMoved)
            assertEquals(Fixtures.CHANNEL_1, store.reader.doc(w, doc)?.channelId)
        }
    }

    @Test fun anUnreadableReadBackIsUnknownNeverSuccess() = runBlocking {
        fixture({ if (it.method == "POST") 502 to null else 500 to null }) { sync, connection, workspace, _, _ ->
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.Unknown)
        }
    }

    @Test fun unsavedEditsOnAnOlderVersionOrBeingSavedStopTheMoveBeforeAnythingIsSent() = runBlocking {
        fixture({ 200 to mapOf("doc" to row(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, 5)) }) { sync, connection, workspace, transport, store ->
            store.writer.saveDocDraft(draft(connection, 3))
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.NotMoved)
            store.writer.saveDocDraft(draft(connection, 4, DocDraft.State.SAVING))
            assertTrue(sync.moveDocument(connection, workspace, doc, to, 4) is DocMoveResult.NotMoved)
            assertEquals(0, transport.recorded.size)
        }
    }

    @Test fun consequencesNameAccessScopeAndPrivateConversations() {
        val plain = DocMovePlan.consequences(from, to, null)
        assertFalse(plain.touchesPrivateConversation)
        assertTrue(plain.lines.any { it.startsWith("Access follows Destination") })
        assertTrue(plain.lines.any { it.contains("changes scope") })
    }
}
