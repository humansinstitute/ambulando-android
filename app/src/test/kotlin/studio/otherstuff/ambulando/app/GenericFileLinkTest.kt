package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test

class GenericFileLinkTest {
    @Test fun existingFileLinksCannotChangeAuthority() {
        val id="11111111-1111-4111-8111-111111111111"; val w="22222222-2222-4222-8222-222222222222"
        assertEquals(id,genericFileId("https://example.test/workspaces/$w/files/$id","https://example.test",w))
        assertEquals(id,genericFileId(id,"https://example.test",w))
        assertEquals(id,genericFileId("https://example.test/flight-deck?workspaceid=$w&nappletFile=$id","https://example.test",w))
        assertTrue(runCatching { genericFileId("https://example.test/flight-deck?workspaceid=$w&nappletFile=$id&nappletFile=$id","https://example.test",w) }.isFailure)
        listOf("https://foreign.test/files/$id","https://user@example.test/files/$id","https://example.test/workspaces/$id/files/$id","https://example.test/files/$id/extra").forEach { assertTrue(runCatching { genericFileId(it,"https://example.test",w) }.isFailure) }
    }
}
