package studio.otherstuff.ambulando.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.CanvasBottomBar
import studio.otherstuff.ambulando.app.ChatFollowsNewest
import studio.otherstuff.ambulando.app.ChatPosition
import studio.otherstuff.ambulando.app.ui.rich.ImageLightbox
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.store.ChatTarget

/** Reported overnight interactions on Android: composer edits, opening chats at their newest message, the image overlay, canvas bar. */
@RunWith(RobolectricTestRunner::class)
class OvernightFeedbackTest {
    @get:Rule val compose = createComposeRule()

    private val target = ChatTarget("c", "w", "ch", null)

    @Test fun sendingReturnsToNewestEvenWhenReadingOlderHistory() {
        var sent by mutableStateOf(0L)
        val rows = (0 until 60).map { "m$it" }
        compose.setContent {
            AmbulandoTheme {
                val state = remember { LazyListState() }
                androidx.compose.foundation.layout.Column {
                    ChatFollowsNewest(state, target, true, rows, ChatPosition("m20", 0, false), sent) {}
                    LazyColumn(Modifier.height(300.dp), state = state) {
                        items(rows, key = { it }) { Text(it, Modifier.height(40.dp).testTag("sent.$it")) }
                    }
                    androidx.compose.material3.TextButton(onClick = { sent++ }, modifier = Modifier.testTag("send")) { Text("Send") }
                }
            }
        }
        compose.onNodeWithTag("sent.m20").assertIsDisplayed()
        compose.onNodeWithTag("send").performClick()
        compose.onNodeWithTag("sent.m59").assertIsDisplayed()
    }

    @Test fun imagePlaceholdersGoWhereTheCaretIsOnTheirOwnLines() {
        val token = UploadToken.make(UploadToken.Kind.IMAGE, "u1")
        val inserted = ComposerInsert.placeholders(TextFieldValue("Before after", TextRange(7)), listOf(token))
        assertEquals("Before \n$token\nafter", inserted.text)
        assertEquals("the caret follows the placeholder", inserted.text.indexOf(token) + token.length + 1, inserted.selection.start)
        assertEquals(token, ComposerInsert.placeholders(TextFieldValue(""), listOf(token)).text)
        assertEquals("a selection is replaced", "x\n$token", ComposerInsert.placeholders(TextFieldValue("xyz", TextRange(1, 3)), listOf(token)).text.let { it })
    }

    @Test fun atStartsALookaheadAndAMentionCompletesAtTheCaret() {
        assertEquals("hi @", ComposerInsert.at(TextFieldValue("hi", TextRange(2))).text)
        assertEquals("hi @", ComposerInsert.at(TextFieldValue("hi ", TextRange(3))).text)
        val doc = Mention(MentionType.DOC, "d1", "Road plan")
        val done = ComposerInsert.mention(TextFieldValue("see @roa and more", TextRange(8)), doc)
        assertEquals("see @Road plan and more", done.text)
        assertEquals("see @Road plan ".length, done.selection.start)
    }

    @Test fun selectedMentionsAreDrawnAsPillsWithoutMovingTheCaret() {
        val doc = Mention(MentionType.DOC, "d1", "Plan")
        val style = SpanStyle(background = androidx.compose.ui.graphics.Color.Red)
        val shown = MentionPills(listOf(doc), style).filter(AnnotatedString("Read @Plan and @Plans"))
        assertEquals("only the whole visible label is a pill", listOf(5 to 10), shown.text.spanStyles.map { it.start to it.end })
        assertEquals(7, shown.offsetMapping.originalToTransformed(7))
    }

    @Test fun aConversationIsVisibleAtItsNewestMessageOnFirstLayoutAndFollowsOnlyAtTheBottom() {
        var rows by mutableStateOf((0 until 60).map { "m$it" })
        lateinit var state: LazyListState
        compose.setContent {
            AmbulandoTheme {
                state = remember { LazyListState() }
                ChatFollowsNewest(state, target, loaded = true, keys = rows, saved = null) {}
                LazyColumn(Modifier.height(300.dp).testTag("list"), state = state) {
                    items(rows, key = { it }) { Text(it, Modifier.fillMaxWidth().height(40.dp).testTag("row.$it")) }
                }
            }
        }
        compose.onNodeWithTag("row.m59").assertIsDisplayed()
        rows = rows + "m60"
        compose.onNodeWithTag("row.m60").assertIsDisplayed()
        // Reading back: a new message must not pull the reader away.
        compose.onNodeWithTag("list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val reading = state.firstVisibleItemIndex
        rows = rows + "m61"
        compose.waitForIdle()
        assertEquals(reading, state.firstVisibleItemIndex)
        compose.onNodeWithTag("row.m61").assertIsNotDisplayed()
    }

    @Test fun returningToAConversationRestoresWhereTheReaderWas() {
        val rows = (0 until 60).map { "m$it" }
        compose.setContent {
            AmbulandoTheme {
                val state = remember { LazyListState() }
                ChatFollowsNewest(state, target, loaded = true, keys = rows, saved = ChatPosition("m20", 0, follow = false)) {}
                LazyColumn(Modifier.height(300.dp), state = state) {
                    items(rows, key = { it }) { Text(it, Modifier.fillMaxWidth().height(40.dp).testTag("row.$it")) }
                }
            }
        }
        compose.onNodeWithTag("row.m20").assertIsDisplayed()
        compose.onNodeWithTag("row.m59").assertIsNotDisplayed()
    }

    @Test fun theImageOverlayShowsTheImageAndClosesOrShares() {
        var closed = false
        var shared = false
        val image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent { AmbulandoTheme { ImageLightbox(image, "photo.png", onDismiss = { closed = true }, onShare = { shared = true }) } }
        compose.onNodeWithTag("image.lightbox.image").assertIsDisplayed()
        compose.onNodeWithTag("image.lightbox.share").performClick()
        compose.onNodeWithTag("image.lightbox.close").performClick()
        assertTrue(shared && closed)
    }

    @Test fun theCanvasBarCarriesExitFitAndEdit() {
        val events = mutableListOf<String>()
        compose.setContent { AmbulandoTheme { CanvasBottomBar("Plan", false, onExit = { events += "exit" }, onFit = { events += "fit" }, onToggleEdit = { events += "edit" }) } }
        compose.onNodeWithTag("canvas.fit").performClick()
        compose.onNodeWithTag("canvas.edit").performClick()
        compose.onNodeWithTag("canvas.exit").performClick()
        assertEquals(listOf("fit", "edit", "exit"), events)
    }
}
