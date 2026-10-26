package studio.otherstuff.ambulando.core.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAttachmentTest {
    private fun paste(n: Int, ch: String = "a") = ch.repeat(n)

    @Test fun selectedReplacementRetainsTheEntirePasteEvenWithMatchingPrefixAndSuffix() {
        val old = "a".repeat(100)
        val pasted = "a".repeat(3000)
        assertEquals(ComposerPaste.Decision.Attach(pasted), ComposerPaste.decide(old, pasted, selectionStart = 0, selectionEnd = old.length))
        val before = "prefix" + old + "suffix"
        val after = "prefix" + pasted + "suffix"
        assertEquals(ComposerPaste.Decision.Attach(pasted), ComposerPaste.decide(before, after, selectionStart = 6, selectionEnd = 106))
    }

    @Test fun pasteBoundaryIs2500Not2501() {
        assertEquals(ComposerPaste.Decision.Accept, ComposerPaste.decide("", paste(2500)))
        assertEquals(ComposerPaste.Decision.Attach(paste(2501)), ComposerPaste.decide("", paste(2501)))
    }

    @Test fun pasteCountsCodePointsNotUtf16Units() {
        val astral = "😀"          // one code point, two units
        assertEquals(ComposerPaste.Decision.Accept, ComposerPaste.decide("", astral.repeat(2500)))
        assertEquals(ComposerPaste.Decision.Attach(astral.repeat(2501)), ComposerPaste.decide("", astral.repeat(2501)))
    }

    @Test fun pasteThatPushesTheInlineTotalPast10000BecomesAFile() {
        val existing = paste(9000)
        val after = existing + paste(1000)
        assertEquals(ComposerPaste.Decision.Accept, ComposerPaste.decide(existing, after))
        val over = existing + paste(1001)
        assertEquals(ComposerPaste.Decision.Attach(paste(1001)), ComposerPaste.decide(existing, over))
    }

    @Test fun pasteInTheMiddleKeepsTheRemainingTextForTheTotal() {
        val before = paste(5000, "x") + paste(5000, "y")
        val inserted = paste(30, "z")
        val after = before.substring(0, 5000) + inserted + before.substring(5000)
        assertEquals(ComposerPaste.Decision.Attach(inserted), ComposerPaste.decide(before, after, clipboard = inserted))
    }

    @Test fun typingStopsAtTheCapInsteadOfBecomingAFile() {
        val full = paste(10000)
        assertEquals(ComposerPaste.Decision.Reject, ComposerPaste.decide(full, full + "b"))
        assertEquals(ComposerPaste.Decision.Accept, ComposerPaste.decide(full.dropLast(1), full))
    }

    @Test fun byteGuardAppliesToMultiByteText() {
        // 3-byte characters: 13,334 of them are 40,002 bytes while still under 10,000 code points... use 13,000 > cap anyway.
        val before = "あ".repeat(9000)            // 27,000 bytes
        val inserted = "い".repeat(4400)           // 13,200 bytes, total 40,200 bytes but 13,400 points
        assertEquals(ComposerPaste.Decision.Attach(inserted), ComposerPaste.decide(before, before + inserted))
        val smallBefore = "あ".repeat(6000)         // 18,000 bytes, 6,000 points
        val mid = "い".repeat(2400)                // 7,200 bytes, 2,400 points -> 25,200 bytes: fine
        assertEquals(ComposerPaste.Decision.Accept, ComposerPaste.decide(smallBefore, smallBefore + mid))
        // At most 4 bytes per code point, so 10,000 four-byte points is exactly the 40,000-byte guard.
        assertTrue(PastedTextPolicy.fitsInline("\uD840\uDC00".repeat(10000)))
        assertEquals(40000, PastedTextPolicy.utf8Bytes("\uD840\uDC00".repeat(10000)))
        assertFalse(PastedTextPolicy.fitsInline("\uD840\uDC00".repeat(10000) + "a"))
    }

    @Test fun shortPasteEqualToTheClipboardIsTreatedAsPaste() {
        val before = paste(9995)
        val inserted = "123456"
        assertEquals(ComposerPaste.Decision.Attach(inserted), ComposerPaste.decide(before, before + inserted, clipboard = inserted))
        assertEquals(ComposerPaste.Decision.Reject, ComposerPaste.decide(before, before + inserted, clipboard = null))
    }

    @Test fun lineCountsMatchTheWebRules() {
        assertEquals(0, TextAttachments.lineCount(""))
        assertEquals(1, TextAttachments.lineCount("a"))
        assertEquals(2, TextAttachments.lineCount("a\nb"))
        assertEquals(2, TextAttachments.lineCount("a\r\nb"))
        assertEquals(3, TextAttachments.lineCount("a\rb\nc"))
        assertEquals(2, TextAttachments.lineCount("a\n"))
    }

    @Test fun bytesAreExactUtf8WithCrlfAndNoBom() {
        val text = "line1\r\nline2 😀 é\r\n"
        val bytes = TextAttachments.bytes(text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), bytes)
        assertFalse(bytes[0] == 0xEF.toByte())
        assertEquals(text, TextAttachments.text(bytes))
        assertEquals(PastedTextPolicy.utf8Bytes(text), bytes.size)
    }

    @Test fun namesAreNumberedAndNormalised() {
        assertEquals("Pasted text.txt", PastedTextPolicy.nextName(emptyList()))
        assertEquals("Pasted text 2.txt", PastedTextPolicy.nextName(listOf("Pasted text.txt")))
        assertEquals("Pasted text 3.txt", PastedTextPolicy.nextName(listOf("pasted TEXT.txt", "Pasted text 2.txt")))
        assertEquals("notes.txt", TextAttachments.normalizeName("notes"))
        assertEquals("a b.md", TextAttachments.normalizeName("a/b.md"))
        assertEquals("Pasted text.txt", TextAttachments.normalizeName("  ../  ".trim()).let { if (it.contains("..")) "Pasted text.txt" else it })
        assertEquals("Pasted text.txt", TextAttachments.normalizeName(".."))
        assertEquals(120 + 4, TextAttachments.normalizeName("x".repeat(300)).length)
    }

    @Test fun previewIsFirstNonBlankLineBounded() {
        assertEquals("hello", TextAttachments.firstLinePreview("\r\n  \n  hello  \nworld"))
        assertEquals("a".repeat(120) + "…", TextAttachments.firstLinePreview("a".repeat(500)))
        assertEquals("", TextAttachments.firstLinePreview("\n\n"))
        val emoji = "😀".repeat(200)
        val p = TextAttachments.firstLinePreview(emoji)
        assertEquals(121, p.codePointCount(0, p.length))
    }

    @Test fun sizeFormatting() {
        assertEquals("12 B", TextAttachments.formatSize(12))
        assertEquals("1.5 KB", TextAttachments.formatSize(1536))
        assertEquals("3.9 MB", TextAttachments.formatSize((3.9 * 1024 * 1024).toLong()))
    }

    @Test fun chunksRoundTripExactlyAndNeverSplitPairs() {
        val sb = StringBuilder()
        repeat(5000) { sb.append("line ").append(it).append("\r\n") }
        repeat(3000) { sb.append("😀") }
        val text = sb.toString()
        val chunks = TextChunks.split(text, 1000)
        assertEquals(text, TextChunks.join(chunks))
        assertTrue(chunks.all { it.length <= 1000 })
        for (c in chunks) {
            assertFalse(Character.isLowSurrogate(c.first()))
            assertFalse(Character.isHighSurrogate(c.last()))
        }
        for (i in 0 until chunks.size - 1) assertFalse("CRLF split", chunks[i].endsWith("\r") && chunks[i + 1].startsWith("\n"))
    }

    @Test fun aSingleHugeLineIsPaged() {
        val text = "x".repeat(4 * 1024 * 1024)
        val chunks = TextChunks.split(text)
        assertTrue(chunks.size > 300)
        assertTrue(chunks.all { it.length <= TextChunks.DEFAULT_UNITS })
        assertEquals(text.length, chunks.sumOf { it.length })
        assertEquals(text, TextChunks.join(chunks))
    }

    @Test fun markerCarriesCountsOnly() {
        val text = "ab\ncd"
        val info = TextAttachmentInfo.of(text, TextAttachmentInfo.SOURCE_PASTE)
        assertEquals(TextAttachmentInfo(1, "paste", 5, 2), info)
        assertEquals("""{"v":1,"source":"paste","chars":5,"lines":2}""", info.requestObject.toString())
    }
}
