package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.*
import java.time.Instant

class BoardAuthorNamesTests {
    private val hex = "ab".repeat(32)
    private val npub = NostrKeys.npub(hex)!!
    private fun people(name: String) = PeopleDirectory(emptyList(), emptyMap(), listOf(
        ProfileRecord(hex, npub, displayName = name, status = ProfileRecord.Status.FOUND, checkedAt = Instant.EPOCH)))
    private fun load(key: String) = buildJsonObject { put("type", "load"); put("key", key) }.toString()
    private fun request(key: String, values: List<String> = listOf(npub)) = buildJsonObject {
        put("type", "author-identities"); put("key", key); put("identities", JsonArray(values.map(::JsonPrimitive)))
    }.toString()
    private fun names(projection: BoardAuthorNames, people: PeopleDirectory?) =
        Json.parseToJsonElement(projection.message(people)!!).jsonObject["names"]!!.jsonObject

    @Test fun lateNamesRenameAndRemovalReplaceWithoutChangingIdentity() {
        val projection = BoardAuthorNames()
        assertFalse(projection.request(request("doc")))
        projection.sent(load("doc"))
        assertTrue(projection.request(request("doc")))
        assertTrue(names(projection, PeopleDirectory()).isEmpty())
        assertEquals("Alice", names(projection, people("Alice"))[npub]!!.jsonPrimitive.content)
        assertEquals("<Renamed & safe>", names(projection, people("<Renamed & safe>"))[npub]!!.jsonPrimitive.content)
        assertTrue(names(projection, people("Workspace Creator")).isEmpty())
        assertTrue(names(projection, people(npub)).isEmpty())
        val other = NostrKeys.npub("cd".repeat(32))!!
        assertTrue(projection.request(request("doc", listOf(other))))
        assertTrue(names(projection, people("Alice")).isEmpty())
    }

    @Test fun staleViewsClearsAndBoundsNeverReuseAnotherDocumentsNames() {
        val projection = BoardAuthorNames()
        projection.sent(load("doc")); assertTrue(projection.request(request("doc")))
        projection.sent(load("history"))
        assertFalse(projection.request(request("doc")))
        assertTrue(names(projection, people("Alice")).isEmpty())
        assertFalse(projection.request(request("history", List(2001) { npub })))
        assertTrue(projection.request(request("history", listOf(npub.dropLast(1) + (if (npub.last() == 'x') "q" else "x"), npub.take(16), "Alice"))))
        assertTrue(names(projection, people("Alice")).isEmpty())
        projection.sent("""{"type":"clear"}""")
        assertNull(projection.message(people("Alice")))
        assertFalse(projection.request(request("history")))
    }
}
