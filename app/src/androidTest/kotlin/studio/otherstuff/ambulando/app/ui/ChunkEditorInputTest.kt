package studio.otherstuff.ambulando.app.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ChunkedText
import studio.otherstuff.ambulando.app.TextSheet
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.TextChunks

/**
 * Drives the real text field through its real InputConnection (what a keyboard uses), not the semantics shortcut, and checks the
 * exact content of the whole document after each sequence. Each sequence runs twice: crossing the page size (the page simply
 * grows) and crossing the editing cap (the page has to be split under the field).
 */
@RunWith(AndroidJUnit4::class)
class ChunkEditorInputTest {
    @get:Rule val compose = createComposeRule()

    private val limit = TextChunks.DEFAULT_UNITS
    private val cap = ChunkedText.EDIT_CAP
    private lateinit var doc: ChunkedText
    private lateinit var expected: StringBuilder
    private lateinit var ic: InputConnection

    private fun open(text: String) {
        doc = if (text.length > limit) ChunkedText(listOf(text)) else ChunkedText.of(text)   // one big page, as an edit leaves it
        expected = StringBuilder(text)
        compose.setContent {
            AmbulandoTheme { TextSheet("t.txt", null, doc, false, null, "", true, {}, {}, {}, footer = { }) }
        }
        compose.onNodeWithTag("text.editor").performClick()
        compose.waitForIdle()
        connect()
    }

    private fun connect() {
        compose.waitForIdle()
        val view = compose.onNodeWithTag("text.editor").fetchSemanticsNode().root as View
        compose.runOnUiThread { ic = view.onCreateInputConnection(EditorInfo())!! }
    }

    private fun ui(block: () -> Unit) { compose.runOnUiThread(block); compose.waitForIdle() }

    private fun pageStart(): Int = doc.chunks.take(doc.page).sumOf { it.length }
    private fun shown(): String = doc.chunks[doc.page]

    private fun assertDoc(label: String) {
        compose.waitForIdle()
        val actual = doc.text()
        val e = expected.toString()
        if (actual != e) {
            val i = actual.zip(e).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(actual.length, e.length) else it }
            fun win(t: String) = t.substring(maxOf(0, i - 6), minOf(t.length, i + 40))
            fail("$label: document differs at $i (actual ${actual.length} chars, expected ${e.length}); pages ${doc.chunks.map { it.length }} on page ${doc.page}; actual[$i-6..]='${win(actual)}' expected='${win(e)}'")
        }
    }

    /** Types [s] at local offset [at] of the page the field shows; the model takes it at the same place in the document. */
    private fun type(s: String, at: Int) {
        val base = pageStart()
        ui { ic.setSelection(at, at); ic.commitText(s, 1) }
        expected.insert(base + at, s)
        connect()                               // a re-split gives the page a new field, so the keyboard asks for a new connection
    }

    private val small = "0123456789".repeat(5)
    private val paste = "0123456789".repeat(500)

    private fun filled(target: Int, unit: String = "abcdefghij"): String = buildString { while (length < target) append(unit) }.take(target)

    private fun longInsert(base: String, ins: String = small) {
        open(base)
        type(ins, base.length)
        assertDoc("long insert")
    }

    private fun cutOffTypedAgain(base: String, ins: String = small) {
        open(base)
        type(ins, base.length)
        val tail = doc.chunks.last()
        type(tail.take(8), shown().length)
        assertDoc("cut-off characters typed")
        type(tail.take(8), shown().length)
        assertDoc("cut-off characters typed twice")
    }

    private fun typingContinuesAfterTheEditAtTheCaret(base: String, ins: String = small) {
        open(base)
        type(ins, base.length)
        // The caret is after what was just typed, wherever the page now ends: more keys follow it, in order.
        repeat(40) { n -> type(('a' + n % 26).toString(), shown().length) }
        assertDoc("keys after the insert, in order")
    }

    private fun backToBack(base: String) {
        open(base)
        ui { ic.setSelection(base.length, base.length) }
        compose.runOnUiThread { repeat(60) { n -> ic.commitText(('a' + n % 26).toString(), 1) } }
        expected.append(buildString { repeat(60) { n -> append('a' + n % 26) } })
        assertDoc("back-to-back keystrokes")
    }

    private fun composition(base: String) {
        open(base)
        // A keyboard delivers a composition's steps in one burst on the UI thread.
        ui { ic.setSelection(base.length, base.length); ic.setComposingText("ab", 1); ic.setComposingText("abcdefghijklmnopqrstuvwxyz", 1); ic.finishComposingText() }
        expected.append("abcdefghijklmnopqrstuvwxyz")
        assertDoc("composition then finish")
        connect()
        ui { ic.finishComposingText() }                 // a repeated echo from the keyboard
        assertDoc("repeated finish")
    }

    private fun unicode(base: String, emoji: Int = 12) {
        open(base)
        type("😀".repeat(emoji), base.length)
        assertDoc("emoji across the edge")
        type("é日本語العربية", minOf(3, shown().length))
        assertDoc("mixed unicode")
    }

    private fun selectionReplaced(base: String) {
        open(base)
        ui { ic.setSelection(10, 40); ic.commitText("Q".repeat(60), 1) }
        expected.replace(10, 40, "Q".repeat(60))
        assertDoc("selection replaced")
    }

    private fun deleting(base: String, ins: String = small) {
        open(base)
        type(ins, base.length)
        val end = shown().length
        val start = pageStart()
        ui { ic.setSelection(end, end); ic.deleteSurroundingText(5, 0) }
        expected.delete(start + end - 5, start + end)
        assertDoc("delete after the insert")
    }

    private fun undo(base: String, ins: String = small) {
        open(base)
        type(ins, base.length)
        val edited = expected.toString()
        compose.onNodeWithTag("text.editor").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        compose.waitForIdle()
        val actual = doc.text()
        assertTrue("undo left neither the original nor the edited text: ${actual.length} chars (base ${base.length}, edited ${edited.length}); pages ${doc.chunks.map { it.length }}",
            actual == base || actual == edited)
        // Whatever undo did, typing afterwards still lands once and in place.
        expected.setLength(0); expected.append(actual)
        connect()
        type("ZZ", 0)
        assertDoc("typing after undo")
    }

    // ---- crossing the page size: the page grows, nothing is split under the field
    @Test fun nearLimit_longInsert() = longInsert(filled(limit - 10))
    @Test fun nearLimit_cutOffTypedAgain() = cutOffTypedAgain(filled(limit - 10))
    @Test fun nearLimit_keysContinueInOrder() = typingContinuesAfterTheEditAtTheCaret(filled(limit - 10))
    @Test fun nearLimit_backToBack() = backToBack(filled(limit - 10))
    @Test fun nearLimit_composition() = composition(filled(limit - 10))
    @Test fun nearLimit_unicode() = unicode(filled(limit - 10))
    @Test fun nearLimit_selection() = selectionReplaced(filled(limit - 10))
    @Test fun nearLimit_delete() = deleting(filled(limit - 10))
    @Test fun nearLimit_undo() = undo(filled(limit - 10))

    // ---- typing past the cap: still no split under the keyboard
    @Test fun pastCap_longInsert() = longInsert(filled(cap - 10))
    @Test fun pastCap_keysContinueInOrder() = typingContinuesAfterTheEditAtTheCaret(filled(cap - 10))
    @Test fun pastCap_backToBack() = backToBack(filled(cap - 10))
    @Test fun pastCap_composition() = composition(filled(cap - 10))
    @Test fun pastCap_undo() = undo(filled(cap - 10))

    // ---- a paste that takes the page past the cap: split under the field, the editor moves to the page that holds the caret
    @Test fun paste_longInsert() = longInsert(filled(cap - 10), paste)
    @Test fun paste_cutOffTypedAgain() = cutOffTypedAgain(filled(cap - 10), paste)
    @Test fun paste_keysContinueInOrder() = typingContinuesAfterTheEditAtTheCaret(filled(cap - 10), paste)
    @Test fun paste_unicode() = unicode(filled(cap - 10), 3000)
    @Test fun paste_delete() = deleting(filled(cap - 10), paste)
    @Test fun paste_undo() = undo(filled(cap - 10), paste)

    /** Control: the same composing sequence against a bare text field with plain state, to tell the keyboard model from the editor. */
    @Test fun controlCompositionOnABareTextField() {
        var v by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("hello"))
        compose.setContent { androidx.compose.material3.OutlinedTextField(v, { v = it }, modifier = androidx.compose.ui.Modifier.testTag("bare")) }
        compose.onNodeWithTag("bare").performClick(); compose.waitForIdle()
        val view = compose.onNodeWithTag("bare").fetchSemanticsNode().root as View
        compose.runOnUiThread { ic = view.onCreateInputConnection(EditorInfo())!! }
        ui { ic.setSelection(5, 5); ic.setComposingText("ab", 1); ic.setComposingText("abcdef", 1); ic.finishComposingText() }
        assertEquals("helloabcdef", v.text)
    }

    @Test fun aHugePasteIsSplitAndTheEditorFollowsTheCaretToItsEnd() {
        val base = filled(2_000)
        open(base)
        val paste = filled(100_000, "0123456789\n")
        type(paste, 1_000)
        assertDoc("huge paste")
        type("END", shown().length)
        assertDoc("typing after the paste goes after it")
        assertTrue(doc.chunks.size > 1)
    }

    @Test fun leavingTheEditorRestoresBoundedPagesWithTheSameText() {
        val base = filled(limit - 10)
        open(base)
        type("0123456789".repeat(100), base.length)
        assertTrue("the page grew while editing", doc.chunks[0].length > limit)
        doc.normalize()
        assertTrue(doc.chunks.all { it.length <= limit })
        assertDoc("after normalize")
    }

    @Test fun fullDocumentExactAcrossPagesAfterManyEdits() {
        val unit = "é 😀 日本語 العربية ‏"
        val text = buildString { while (length < 40_000) append(unit) }
        open(text)
        type("XYZ", 5)
        type("🙂", 100)
        assertDoc("multi-page edits")
        assertEquals(text.length + 5, doc.text().length)
    }
}
