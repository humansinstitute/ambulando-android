package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.rich.InlineText
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.docs.MarkdownBlocks
import studio.otherstuff.ambulando.core.docs.RenderBlock
import studio.otherstuff.ambulando.core.store.MentionCandidate

@RunWith(RobolectricTestRunner::class)
class FileMentionPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingFileInsertsAtCaretAndReloadedChipOpensNativeFileWhileDocsStillWork() {
        val file = Mention(MentionType.FILE, "file-uuid", "System plan")
        val doc = Mention(MentionType.DOC, "doc-uuid", "Project notes")
        var wire by mutableStateOf("")
        var opened: Pair<String, String>? = null
        compose.setContent {
            AmbulandoTheme {
                Column {
                    MentionLookahead(listOf(MentionCandidate(file, "System Flow"), MentionCandidate(doc, "Document")), onPick = { picked ->
                        val inserted = ComposerInsert.mention(TextFieldValue("Read @sy later", TextRange(8)), picked)
                        assertEquals("Read ${picked.visible} later", inserted.text)
                        wire = MentionCodec.encode(inserted.text, listOf(picked)).body
                    })
                    if (wire.isNotEmpty()) {
                        Text(InlineText.annotated((MarkdownBlocks.parse(wire).single() as RenderBlock.Paragraph).runs, Amb.colors,
                            RichActions(openMention = { kind, id -> opened = kind to id })))
                    }
                }
            }
        }
        compose.onNodeWithTag("chat.mention.file.file-uuid").performClick()
        assertEquals("Read @[System plan](mention:file:file-uuid) later", wire)
        compose.onNodeWithText("Read System plan later").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("file" to "file-uuid", opened) }
        compose.onNodeWithTag("chat.mention.doc.doc-uuid").performClick()
        assertEquals("Read @[Project notes](mention:doc:doc-uuid) later", wire)
        compose.onNodeWithText("Read Project notes later").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("doc" to "doc-uuid", opened) }
    }
}
