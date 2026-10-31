package studio.otherstuff.ambulando.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.TextChunks

class ChunkPageEditTest {
    private val unit = "é 😀 日本語 العربية ‏"
    private fun fullPages(): String = buildString { while (length < 3 * TextChunks.DEFAULT_UNITS + 500) append(unit) }

    /** What the field reports after [typed] is inserted at the start of the page, caret after it. */
    private fun typedAtStart(shown: TextFieldValue, typed: String) = TextFieldValue(typed + shown.text, TextRange(typed.length))

    @Test fun anEditOnAFullPageIsSplitAndNothingIsLost() {
        val text = fullPages(); val doc = ChunkedText.of(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val shownNow = edit.apply(typedAtStart(shown, "INSERTED"), shown)
        assertEquals(TextChunks.DEFAULT_UNITS, shownNow.text.length)
        assertEquals(text.length + 8, TextChunks.join(doc.chunks.toList()).length)
        assertTrue(doc.dirty)
    }

    // The field's input buffer keeps the longer text until it is composed again, and an input-method event in that gap
    // reports it a second time. Seen on the emulator as the text sheet's `INSERTED` appearing twice.
    @Test fun theLongerTextReportedAgainAfterASplitIsNotInsertedTwice() {
        val text = fullPages(); val doc = ChunkedText.of(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val reported = typedAtStart(shown, "INSERTED")
        val first = edit.apply(reported, shown)
        val again = edit.apply(reported, first)
        assertEquals(text.length + 8, TextChunks.join(doc.chunks.toList()).length)
        assertEquals("the field still shows the page, not the longer buffer", doc.chunks[1], again.text)
        assertEquals(first.text, again.text)
    }

    @Test fun aLaterDifferentEditIsStillApplied() {
        val text = fullPages(); val doc = ChunkedText.of(text); val edit = ChunkPageEdit(doc, 1)
        var shown = TextFieldValue(doc.chunks[1])
        shown = edit.apply(typedAtStart(shown, "INSERTED"), shown)
        shown = edit.apply(typedAtStart(shown, "MORE"), shown)
        assertEquals(text.length + 8 + 4, TextChunks.join(doc.chunks.toList()).length)
    }

    // The echo check must not swallow a person who really types the same thing twice.
    @Test fun theSameTextTypedAgainIsAppliedAgain() {
        val text = fullPages(); val doc = ChunkedText.of(text); val edit = ChunkPageEdit(doc, 1)
        var shown = TextFieldValue(doc.chunks[1])
        shown = edit.apply(typedAtStart(shown, "INSERTED"), shown)
        shown = edit.apply(typedAtStart(shown, "INSERTED"), shown)
        assertEquals(text.length + 16, TextChunks.join(doc.chunks.toList()).length)
    }

    @Test fun aCaretMoveAndACompositionOnAnUnsplitPageAreLeftAlone() {
        val doc = ChunkedText.of("hello world"); val edit = ChunkPageEdit(doc, 0)
        val shown = TextFieldValue("hello world", TextRange(5))
        val moved = edit.apply(TextFieldValue("hello world", TextRange(2)), shown)
        assertEquals(TextRange(2), moved.selection)
        assertTrue(!doc.dirty)
        val composing = TextFieldValue("hello worldか", TextRange(12), TextRange(11, 12))
        val next = edit.apply(composing, moved)
        assertEquals(composing, next)
        assertEquals("hello worldか", doc.chunks[0])
    }

    @Test fun theCaretStaysOnAWholeCharacterAfterASplit() {
        val text = fullPages(); val doc = ChunkedText.of(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val atEnd = TextFieldValue("😀".repeat(2) + shown.text, TextRange(2 * "😀".length + shown.text.length))
        val next = edit.apply(atEnd, shown)
        assertTrue(next.selection.end <= next.text.length)
        assertEquals(text.length + 4, TextChunks.join(doc.chunks.toList()).length)
    }
}
