package studio.otherstuff.ambulando.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Bech32

class MentionTest {
    /** The public NIP-19 test vector npub and a synthetic one (fixed bytes, not a real key). */
    private val vectorNpub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val agentNpub = Bech32.encode("npub", ByteArray(32) { 0x22 })
    private val member = Mention(MentionType.PERSON, vectorNpub, "Member Alpha")
    private val bot = Mention(MentionType.AGENT, agentNpub, "Agent")

    @Test fun visibleAndStructuredComeFromOneIdentity() {
        val (body, mentions) = MentionCodec.encode("Hi @Member Alpha and @Agent, ship it", listOf(member, bot))
        assertEquals("Hi @[Member Alpha](mention:person:$vectorNpub) and @[Agent](mention:agent:$agentNpub), ship it", body)
        assertEquals(listOf(member, bot), mentions)
        assertEquals(listOf(mapOf("type" to "person", "npub" to vectorNpub, "label" to "Member Alpha"),
            mapOf("type" to "agent", "npub" to agentNpub, "label" to "Agent")), mentions.map { it.requestObject })
        // Every structured entry has its exact visible token in the body, and vice versa.
        for (mention in mentions) assertTrue(body.contains(mention.token))
        assertEquals(mentions, MentionCodec.segments(body).mapNotNull { (it as? BodySegment.MentionSegment)?.mention })
    }

    @Test fun editedAwayMentionIsNotSent() {
        val (body, mentions) = MentionCodec.encode("Hi Member", listOf(member))
        assertEquals("Hi Member", body)
        assertTrue(mentions.isEmpty())
        val (prefix, prefixMentions) = MentionCodec.encode("@Member Alphabet is here", listOf(member))
        assertEquals("a longer word is not a mention", "@Member Alphabet is here", prefix)
        assertTrue(prefixMentions.isEmpty())
    }

    @Test fun longestLabelWinsAndOrderFollowsSelection() {
        val short = Mention(MentionType.PERSON, agentNpub, "Member")
        val (body, mentions) = MentionCodec.encode("@Member Alpha and @Member", listOf(short, member))
        assertEquals("@[Member Alpha](mention:person:$vectorNpub) and @[Member](mention:person:$agentNpub)", body)
        assertEquals(listOf(short, member), mentions)
    }

    @Test fun decodeRoundTripAndRecordReferences() {
        val body = "See @[Plan](mention:document:00000000-0000-4000-8000-000000000001) with @[Agent](mention:agent:$agentNpub)"
        assertEquals("See Plan with @Agent", MentionCodec.plain(body))
        val (text, mentions) = MentionCodec.decode(body)
        assertEquals(listOf(bot), mentions)
        assertEquals(body, MentionCodec.encode(text, mentions).body)
    }

    @Test fun labelsCannotBreakTokens() {
        val odd = Mention(MentionType.PERSON, vectorNpub, "Eve [admin](x)")
        assertEquals("Eve admin x", odd.label)
        assertEquals(listOf(BodySegment.MentionSegment(odd)), MentionCodec.segments(odd.token))
        assertEquals("an empty label falls back to the npub", vectorNpub, Mention(MentionType.PERSON, vectorNpub, " [] ").label)
        assertEquals(80, Mention(MentionType.PERSON, vectorNpub, "x".repeat(200)).label.length)
    }

    @Test fun pickerQuery() {
        assertEquals("me", MentionCodec.activeQuery("hello @me"))
        assertEquals("", MentionCodec.activeQuery("@"))
        assertNull(MentionCodec.activeQuery("mail me at a@b"))
        assertNull(MentionCodec.activeQuery("hello"))
        assertEquals("hello @Member Alpha ", MentionCodec.complete("hello @me", member))
    }
}
