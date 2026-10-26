package studio.otherstuff.ambulando.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedTextPolicyTest {
    private val emoji = "😀"

    @Test fun thresholdBoundaryCountsCodePoints() {
        assertFalse(PastedTextPolicy.shouldAttach("a".repeat(2500), ""))
        assertTrue(PastedTextPolicy.shouldAttach("a".repeat(2501), ""))
        // 2500 emoji are 5000 UTF-16 units but exactly 2500 code points: stays inline unless a cap trips.
        assertEquals(2500, PastedTextPolicy.codePoints(emoji.repeat(2500)))
        assertTrue(PastedTextPolicy.shouldAttach(emoji.repeat(2501), ""))
    }

    @Test fun totalCapForcesAttachment() {
        assertFalse(PastedTextPolicy.shouldAttach("a".repeat(2000), "b".repeat(8000)))
        assertTrue(PastedTextPolicy.shouldAttach("a".repeat(2001), "b".repeat(8000)))
        assertTrue(PastedTextPolicy.shouldAttach("a", "b".repeat(10000)))
    }

    @Test fun utf8GuardForcesAttachment() {
        val wide = "中".repeat(2400) // 3 bytes each: 7200 bytes
        assertEquals(7200, PastedTextPolicy.utf8Bytes(wide))
        assertEquals(4, PastedTextPolicy.utf8Bytes(emoji))
        assertFalse(PastedTextPolicy.shouldAttach(wide, "中".repeat(5000))) // 22,200 bytes
        assertTrue(PastedTextPolicy.shouldAttach(wide, "中".repeat(8000))) // 31,200 + 7,200 > 40,000
        assertTrue(PastedTextPolicy.fitsInline("a".repeat(10000)))
        assertFalse(PastedTextPolicy.fitsInline("a".repeat(10001)))
        assertFalse(PastedTextPolicy.fitsInline(emoji.repeat(10001)))
        assertTrue(PastedTextPolicy.fitsInline(emoji.repeat(10000))) // exactly 40,000 bytes
        assertFalse(PastedTextPolicy.fitsInline(emoji.repeat(9999) + "\u4e2d\u4e2d")) // 40,002 bytes
    }

    @Test fun namesAreNumberedAndSkipTakenOnes() {
        assertEquals("Pasted text.txt", PastedTextPolicy.nextName(emptyList()))
        assertEquals("Pasted text 2.txt", PastedTextPolicy.nextName(listOf("Pasted text.txt")))
        assertEquals("Pasted text 3.txt", PastedTextPolicy.nextName(listOf("pasted text.txt", "Pasted text 2.txt")))
        assertEquals("Pasted text.txt", PastedTextPolicy.nextName(listOf("Pasted text 2.txt")))
    }

    @Test fun insertionKeepsRemainingTextAndSelectionReplacement() {
        val ins = PastedTextPolicy.insertion("hello world", "hello BIG world")!!
        assertEquals(6, ins.start)
        assertEquals("BIG ", ins.inserted)
        assertEquals("hello world", ins.remaining)
        val replaced = PastedTextPolicy.insertion("keep SELECTED end", "keep X end")!!
        assertEquals("X", replaced.inserted)
        assertEquals("keep  end", replaced.remaining)
        assertNull(PastedTextPolicy.insertion("same", "same"))
        assertNull(PastedTextPolicy.insertion("abc", "ac"))
    }

    @Test fun insertionNeverSplitsSurrogatePairsAndKeepsCrlf() {
        val ins = PastedTextPolicy.insertion("a$emoji", "a$emoji$emoji")!!
        assertEquals(emoji, ins.inserted)
        val crlf = PastedTextPolicy.insertion("x", "x\r\ny\r\n")!!
        assertEquals("\r\ny\r\n", crlf.inserted)
        assertEquals(7, PastedTextPolicy.utf8Bytes("a\r\n$emoji"))
    }
}
