package studio.otherstuff.ambulando.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.TextChunks

class ChunkPageEditTest {
    private val unit = "é 😀 日本語 العربية ‏"
    private val cap = ChunkedText.EDIT_CAP
    /** A paste-sized insert: more than the editor takes as typing, so it splits a page that it takes past the cap. */
    private val ins = "INSERTED".repeat(625)
    private fun fullPages(): String = buildString { while (length < 12_000 + cap + 3_000) append(unit) }
    private fun cut(t: String, i: Int) = if (i in 1 until t.length && Character.isLowSurrogate(t[i])) i - 1 else i
    /** Three pages as an edit leaves them: a full one, the one being edited just under the cap, and the rest. */
    private fun pagesOf(t: String): ChunkedText {
        val a = cut(t, 12_000); val b = cut(t, a + cap - 10)
        return ChunkedText(listOf(t.substring(0, a), t.substring(a, b), t.substring(b)))
    }

    /** What the field reports after [typed] is inserted at the start of the page, caret after it. */
    private fun typedAtStart(shown: TextFieldValue, typed: String) = TextFieldValue(typed + shown.text, TextRange(typed.length))

    @Test fun anEditOnAFullPageIsSplitAndNothingIsLost() {
        val text = fullPages(); val doc = pagesOf(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val shownNow = edit.apply(typedAtStart(shown, ins), shown)
        assertTrue(shownNow.text.length in TextChunks.DEFAULT_UNITS - 1..TextChunks.DEFAULT_UNITS)
        assertEquals(text.length + ins.length, TextChunks.join(doc.chunks.toList()).length)
        assertTrue(doc.dirty)
    }

    // The field's input buffer keeps the longer text until it is composed again, and an input-method event in that gap
    // reports it a second time. Seen on the emulator as the text sheet's `INSERTED` appearing twice.
    @Test fun theLongerTextReportedAgainAfterASplitIsNotInsertedTwice() {
        val text = fullPages(); val doc = pagesOf(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val reported = typedAtStart(shown, ins)
        val first = edit.apply(reported, shown)
        val again = edit.apply(reported, first)
        assertEquals(text.length + ins.length, TextChunks.join(doc.chunks.toList()).length)
        assertEquals("the field still shows the page, not the longer buffer", doc.chunks[1], again.text)
        assertEquals(first.text, again.text)
    }

    @Test fun aLaterDifferentEditIsStillApplied() {
        val text = fullPages(); val doc = pagesOf(text); val edit = ChunkPageEdit(doc, 1)
        var shown = TextFieldValue(doc.chunks[1])
        shown = edit.apply(typedAtStart(shown, ins), shown)
        shown = edit.apply(typedAtStart(shown, "MORE"), shown)
        assertEquals(text.length + ins.length + 4, TextChunks.join(doc.chunks.toList()).length)
    }

    // The echo check must not swallow a person who really types the same thing twice.
    @Test fun theSameTextTypedAgainIsAppliedAgain() {
        val text = fullPages(); val doc = pagesOf(text); val edit = ChunkPageEdit(doc, 1)
        var shown = TextFieldValue(doc.chunks[1])
        shown = edit.apply(typedAtStart(shown, ins), shown)
        shown = edit.apply(typedAtStart(shown, ins), shown)
        assertEquals(text.length + 2 * ins.length, TextChunks.join(doc.chunks.toList()).length)
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
        val text = fullPages(); val doc = pagesOf(text); val edit = ChunkPageEdit(doc, 1)
        val shown = TextFieldValue(doc.chunks[1])
        val atEnd = TextFieldValue("😀".repeat(2500) + shown.text, TextRange(5000))
        val next = edit.apply(atEnd, shown)
        assertTrue(next.selection.end <= next.text.length)
        assertEquals(text.length + 5000, TextChunks.join(doc.chunks.toList()).length)
    }

    private fun joined(doc: ChunkedText) = TextChunks.join(doc.chunks.toList())
    private fun noLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false; i++ }
            else if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    /** A page split by ins at the start of page 1; returns the edit, the field now, and the stale buffer the input method still holds. */
    private class Split(val original: String, val doc: ChunkedText, val edit: ChunkPageEdit, val shown: TextFieldValue, val stale: TextFieldValue, val before: List<String>)
    private fun splitPage(): Split {
        val original = fullPages(); val doc = pagesOf(original); val before = doc.chunks.toList(); val edit = ChunkPageEdit(doc, 1)
        val start = TextFieldValue(doc.chunks[1])
        val stale = typedAtStart(start, ins)
        return Split(original, doc, edit, edit.apply(stale, start), stale, before)
    }

    @Test fun theSplitKeepsEveryCharacterInOrderAndPageTwoStartsWithTheCutOffTail() {
        val s = splitPage()
        assertEquals(s.before[0] + ins + s.before[1] + s.before.drop(2).joinToString(""), joined(s.doc))
        val parts = s.doc.chunks.size - s.before.size + 1
        assertEquals(s.stale.text, s.doc.chunks.subList(1, 1 + parts).joinToString(""))
        assertTrue(s.doc.chunks.size > s.before.size)
    }

    // The defect this guards: the next input is exactly the characters that were just cut off the page. Once the field shows
    // the page again that is a person typing, not the input method echoing, and must not be dropped.
    @Test fun typingExactlyTheCutOffCharactersAfterTheFieldShowsThePageIsApplied() {
        val s = splitPage()
        s.edit.fieldShows(s.shown)
        val cut = s.stale.text.drop(s.shown.text.length)
        assertTrue(cut.isNotEmpty())
        val before = joined(s.doc).length
        val next = s.edit.apply(TextFieldValue(s.shown.text + cut, TextRange(s.shown.text.length + cut.length)), s.shown)
        assertEquals(before + cut.length, joined(s.doc).length)
        assertTrue(joined(s.doc).contains(s.shown.text + cut))
        assertTrue(next.selection.end <= next.text.length)
    }

    @Test fun repeatingTheCutOffCharactersTwiceAppliesBoth() {
        val s = splitPage()
        s.edit.fieldShows(s.shown)
        val cut = s.stale.text.drop(s.shown.text.length)
        val before = joined(s.doc).length
        var shown = s.shown
        repeat(2) {
            shown = s.edit.apply(TextFieldValue(shown.text + cut, TextRange(shown.text.length + cut.length)), shown)
            s.edit.fieldShows(shown)
        }
        assertEquals(before + 2 * cut.length, joined(s.doc).length)
    }

    // The real echo: the input method reports its stale buffer, once or several times, before the field has been composed.
    @Test fun anInputMethodEchoBeforeTheFieldShowsThePageIsDroppedEveryTime() {
        val s = splitPage()
        val expected = joined(s.doc)
        repeat(3) {
            val shown = s.edit.apply(s.stale.copy(composition = TextRange(0, 3)), s.shown)
            assertEquals(s.shown.text, shown.text)
        }
        assertEquals(expected, joined(s.doc))
    }

    // Real input in the same gap is made against the stale buffer; it must land once, where it was typed, with nothing duplicated.
    @Test fun aKeystrokeInTheGapIsAppliedOnceOnTopOfTheSplit() {
        val s = splitPage()
        val expected = joined(s.doc)
        val typed = TextFieldValue(s.stale.text.substring(0, 20) + "X" + s.stale.text.substring(20), TextRange(21))
        val shown = s.edit.apply(typed, s.shown)
        assertEquals(expected.substring(0, expected.indexOf(s.shown.text) + 20) + "X" + expected.substring(expected.indexOf(s.shown.text) + 20), joined(s.doc))
        assertEquals(1, joined(s.doc).length - expected.length)
        assertTrue(shown.selection.end <= shown.text.length)
    }

    @Test fun anEchoAfterAKeystrokeInTheGapIsStillDroppedAndTheGapClosesWhenTheFieldShowsThePage() {
        val s = splitPage()
        val typed = TextFieldValue(s.stale.text + "Z", TextRange(s.stale.text.length + 1))
        val first = s.edit.apply(typed, s.shown)
        val after = joined(s.doc)
        s.edit.apply(typed, first)
        assertEquals(after, joined(s.doc))
        s.edit.fieldShows(first)
        val again = s.edit.apply(TextFieldValue(first.text + "Z", TextRange(first.text.length + 1)), first)
        assertEquals(after.length + 1, joined(s.doc).length)
        assertTrue(again.text.isNotEmpty())
    }

    @Test fun theGapStaysOpenUntilTheFieldShowsTheReturnedPage() {
        val s = splitPage()
        s.edit.fieldShows(TextFieldValue(s.stale.text))            // a composition with the longer text is not the page
        val before = joined(s.doc)
        s.edit.apply(s.stale, s.shown)
        assertEquals(before, joined(s.doc))
    }

    @Test fun anEditInTheGapNeverLeavesALoneSurrogateOrCutsACharacter() {
        val s = splitPage()                                            // the page now starts with INSERTED then 😀-bearing text
        val at = s.stale.text.indexOf("😀")
        assertTrue(at > 0 && at + 1 < s.shown.text.length)
        // replace the emoji by another one: the shared high surrogate must not leave the edit half-applied
        val swapped = s.stale.text.replaceRange(at, at + 2, "😁")
        s.edit.apply(TextFieldValue(swapped, TextRange(at + 2)), s.shown)
        assertTrue(noLoneSurrogate(joined(s.doc)))
        assertEquals(s.original.length + ins.length, joined(s.doc).length)
        assertTrue(joined(s.doc).contains("😁"))
    }

    @Test fun anEchoCaretIsClampedToThePageAndLandsOnAWholeCharacter() {
        val s = splitPage()
        val pair = s.shown.text.indexOf("😀")
        assertTrue(pair > 0)
        // the stale buffer reports a caret between the two halves of an emoji, and another past the end of the page
        val inside = s.edit.apply(s.stale.copy(selection = TextRange(pair + 1)), s.shown)
        assertEquals(pair, inside.selection.end)
        val past = s.edit.apply(s.stale.copy(selection = TextRange(s.stale.text.length)), s.shown)
        assertTrue(past.selection.end <= past.text.length)
        assertTrue(noLoneSurrogate(past.text.substring(0, past.selection.end)))
    }

    @Test fun anotherPageStartsWithNoGapAndAppliesTheFirstEdit() {
        val s = splitPage()
        val next = ChunkPageEdit(s.doc, 2)
        val shown = TextFieldValue(s.doc.chunks[2])
        val before = joined(s.doc).length
        next.apply(typedAtStart(shown, "NEXT"), shown)
        assertEquals(before + 4, joined(s.doc).length)
    }

    // Reverting an edit (what undo reports) on a page that was not split is an ordinary edit and restores the text exactly.
    @Test fun undoOnAnUnsplitPageRestoresTheTextExactly() {
        val doc = ChunkedText.of("héllo 😀 world"); val edit = ChunkPageEdit(doc, 0)
        var shown = TextFieldValue(doc.chunks[0], TextRange(0))
        shown = edit.apply(TextFieldValue("ab" + shown.text, TextRange(2)), shown)
        assertEquals("abhéllo 😀 world", doc.text())
        shown = edit.apply(TextFieldValue("héllo 😀 world", TextRange(0)), shown)
        assertEquals("héllo 😀 world", doc.text())
        assertEquals("abhéllo 😀 world".length - 2, doc.text().length)
    }

    @Test fun aSelectionReplacedByTypingKeepsTheCaretAfterTheNewText() {
        val doc = ChunkedText.of("one two three"); val edit = ChunkPageEdit(doc, 0)
        val shown = TextFieldValue("one two three", TextRange(4, 7))
        val next = edit.apply(TextFieldValue("one 2 three", TextRange(5)), shown)
        assertEquals("one 2 three", doc.text())
        assertEquals(TextRange(5), next.selection)
    }

    // ---- the page grows while it is typed on; only a paste-sized edit past the cap is split

    @Test fun typingNeverSplitsThePageEvenPastTheCap() {
        val doc = ChunkedText(listOf("a".repeat(cap - 2))); val edit = ChunkPageEdit(doc, 0)
        var shown = TextFieldValue(doc.chunks[0], TextRange(cap - 2))
        repeat(50) { n ->
            shown = edit.apply(TextFieldValue(shown.text + "k", TextRange(shown.text.length + 1)), shown)
            assertEquals(cap - 2 + n + 1, shown.text.length)
        }
        assertEquals(1, doc.chunks.size)
        assertEquals(0, doc.rewrites)
        assertEquals(cap - 2 + 50, doc.text().length)
    }

    @Test fun aPasteOverTheCapIsSplitAndTheEditorMovesToThePageThatHoldsTheCaret() {
        val doc = ChunkedText(listOf("a".repeat(cap - 10))); val edit = ChunkPageEdit(doc, 0)
        val shown = TextFieldValue(doc.chunks[0], TextRange(cap - 10))
        val pasted = "p".repeat(20_000)
        edit.apply(TextFieldValue(shown.text + pasted, TextRange(shown.text.length + pasted.length)), shown)
        assertTrue(doc.chunks.size > 1)
        assertEquals(1, doc.rewrites)
        assertEquals(shown.text + pasted, doc.text())
        assertEquals(doc.chunks.size - 1, doc.page)
        assertEquals(doc.chunks.last().length, doc.caretHint)
    }

    @Test fun normalizeSplitsOnlyOversizePagesAndKeepsTheTextAndCounts() {
        val text = "line\r\n".repeat(9_000)
        val doc = ChunkedText(listOf(text)); val bytes = doc.bytes; val lines = doc.lines
        doc.normalize()
        assertTrue(doc.chunks.size > 1)
        assertTrue(doc.chunks.all { it.length <= TextChunks.DEFAULT_UNITS })
        assertEquals(text, doc.text())
        assertEquals(bytes, doc.bytes)
        assertEquals(lines, doc.lines)
    }
}
