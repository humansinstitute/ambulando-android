package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentAuthorTest {
    private val w = "11111111-1111-1111-1111-111111111111"
    private val actor = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
    private val members = listOf(MemberRecord(w, actor.lowercase(), "npub1author", "human", "Author"), MemberRecord("other", actor.lowercase(), "npub1wrong", "human", "Other"))
    private fun comment(sender: String?, raw: Map<String, String> = emptyMap()) = CommentRecord(w, "c", CommentRecord.Parent.DOC, "d", body = "", senderNpub = sender,
        createdAt = "", raw = JsonObject(raw.mapValues { JsonPrimitive(it.value) }))

    @Test fun recordedCreatorWins() = assertEquals("npub1sender", comment("npub1sender", mapOf("created_by_actor_id" to actor)).authorNpub(members))

    @Test fun actorIdOnlyResolvesToAMemberOfTheSameWorkspace() =
        assertEquals("npub1author", comment(null, mapOf("created_by_actor_id" to actor)).authorNpub(members))

    @Test fun unresolvedStaysUnknown() {
        assertNull(comment(null).authorNpub(members))
        assertNull(comment(null, mapOf("created_by_actor_id" to "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")).authorNpub(members))
        assertNull(comment(null, mapOf("updated_by_actor_id" to actor)).authorNpub(members))
    }
}
