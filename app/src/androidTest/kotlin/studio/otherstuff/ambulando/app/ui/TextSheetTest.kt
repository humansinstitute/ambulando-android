package studio.otherstuff.ambulando.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ChunkedText
import studio.otherstuff.ambulando.app.TextSheet
import studio.otherstuff.ambulando.app.captureTextReview
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.TextAttachments
import studio.otherstuff.ambulando.core.chat.TextChunks

/** The text modal must stay responsive with the original 3.94 MB log and with a single pathological line. */
@RunWith(AndroidJUnit4::class)
class TextSheetTest {
    @get:Rule val compose = createComposeRule()

    /** A log of about 3.94 MB: many ordinary lines, CRLF endings, a few long ones. */
    private fun log(): String {
        val sb = StringBuilder(4_200_000)
        var i = 0
        while (sb.length < 3_940_000) {
            sb.append("2026-10-26T10:").append(i % 60).append(":00.000Z INFO worker-").append(i % 17).append(" request ").append(i).append(" completed in ").append(i % 997).append("ms\r\n")
            if (i % 5000 == 0) sb.append("x".repeat(20_000)).append("\r\n")
            i++
        }
        return sb.toString()
    }

    private fun show(text: String, editing: Boolean): ChunkedText {
        val doc = ChunkedText.of(text)
        compose.setContent {
            AmbulandoTheme { TextSheet("Pasted text.txt", null, doc, false, null, "Sent", editing, {}, {}, {}, footer = { }) }
        }
        return doc
    }

    @Test fun the394MbLogOpensInReadModeQuickly() {
        val text = log()
        assertTrue(text.length in 3_900_000..4_100_000)
        val start = System.nanoTime()
        val doc = show(text, editing = false)
        compose.onNodeWithTag("text.viewer").assertIsDisplayed()
        captureTextReview("large-log-reader")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("opening took $elapsedMs ms", elapsedMs < 5_000)
        // Bounded: only a handful of chunks are composed at once, never the whole log.
        assertTrue(doc.chunks.size > 300)
        assertTrue(doc.chunks.all { it.length <= TextChunks.DEFAULT_UNITS })
        compose.onNodeWithTag("text.viewer").performScrollToIndex(doc.chunks.size - 1)
        compose.waitForIdle()
        assertEquals(text, doc.text())                         // nothing lost or altered
        compose.onNodeWithTag("text.stats").assertTextContains("lines", substring = true)
    }

    @Test fun aSingleHugeUnicodeLineEditsPageByPageWithoutLoss() {
        val unit = "é 😀 日本語 العربية ‏"
        val text = buildString { while (length < 4_000_000) append(unit) }
        val doc = show(text, editing = true)
        compose.onNodeWithTag("text.editor").assertIsDisplayed()
        captureTextReview("large-unicode-editor")
        compose.onNodeWithTag("text.page").assertTextContains("Page 1 of", substring = true)
        compose.onNodeWithTag("text.next").performClick()
        compose.onNodeWithTag("text.page").assertTextContains("Page 2 of", substring = true)
        compose.onNodeWithTag("text.editor").performTextInput("INSERTED")
        compose.waitForIdle()
        assertTrue(doc.dirty)
        val expected = TextChunks.join(doc.chunks.toList())
        assertTrue(expected.contains("INSERTED"))
        assertEquals(text.length + "INSERTED".length, expected.length)
        // The bytes of the whole document are still exact UTF-8 after paging and editing.
        assertEquals(TextAttachments.bytes(expected).size.toLong(), doc.bytes)
    }
}
