package studio.otherstuff.ambulando.core.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionEmojiTest {
    @Test fun matchesTheWebsSevenWireNamesInOrder() {
        assertEquals(listOf("thumbs_up", "smile", "heart", "eyes", "party", "shaka", "white_check_mark"), ReactionEmoji.entries.map { it.wire })
    }

    @Test fun storedGlyphsFromEarlierBuildsStillResolve() {
        assertEquals(ReactionEmoji.THUMBS_UP, ReactionEmoji.fromStored("thumbs_up"))
        assertEquals(ReactionEmoji.THUMBS_UP, ReactionEmoji.fromStored("👍"))
        assertEquals(ReactionEmoji.HEART, ReactionEmoji.fromStored("❤️"))
        assertEquals(null, ReactionEmoji.fromStored("🙂"))
    }
}
