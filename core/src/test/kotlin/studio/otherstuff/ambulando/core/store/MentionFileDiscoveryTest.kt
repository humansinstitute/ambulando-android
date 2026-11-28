package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

class MentionFileDiscoveryTest {
    @Test fun coldSearchFindsWhiteboardOnLaterPageAndRevocationRemovesCandidates() = runBlocking {
        val h = SampleHarness(); val w = SampleIds.STUDIO
        val channel = UUID.randomUUID().toString(); val id = UUID.randomUUID().toString()
        var denied = false; var pages = 0
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (denied) return HttpResponse(403, "{}".toByteArray())
                pages++
                val second = request.url.contains("cursor=next")
                val files = if (second) listOf(mapOf("id" to id, "workspace_id" to w, "channel_id" to channel, "display_name" to "Ambulando Scratch", "content_type" to "application/vnd.excalidraw+json"))
                    else (1..200).map { mapOf("id" to UUID.randomUUID().toString(), "workspace_id" to w, "channel_id" to channel, "display_name" to "Other $it") }
                return HttpResponse(200, jsonObjectOf("files" to files, "next_cursor" to if (second) null else "next").toString().toByteArray())
            }
        }
        val api = TowerAPI(TowerClient(h.client.origin, transport, SampleSigners.primary))
        StoreFixtures.store().use { store ->
            assertTrue(store.reader.mentionCandidates(w, "Scratch").isEmpty())
            store.refreshMentionFiles(api, w, h.app, listOf(channel)) { true }
            assertEquals(2, pages)
            val found = store.reader.mentionCandidates(w, "Scratch").single()
            assertEquals("@[Ambulando Scratch](mention:file:$id)", found.mention.token)
            assertTrue(store.reader.mentionCandidates(StoreFixtures.W2, "Scratch").isEmpty())
            denied = true
            store.refreshMentionFiles(api, w, h.app, listOf(channel)) { true }
            assertTrue(store.reader.mentionCandidates(w, "Scratch").isEmpty())
        }
    }

    @Test fun changedBoundaryCannotStoreNetworkResults() = runBlocking {
        val h = SampleHarness(); val channel = UUID.randomUUID().toString(); var current = true
        val api = TowerAPI(TowerClient(h.client.origin, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { current = false; return HttpResponse(200, "{\"files\":[]}".toByteArray()) }
        }, SampleSigners.primary))
        StoreFixtures.store().use { store ->
            assertTrue(runCatching { store.refreshMentionFiles(api, SampleIds.STUDIO, h.app, listOf(channel)) { current } }.isFailure)
        }
    }
}
