package studio.otherstuff.ambulando.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.rich.*
import studio.otherstuff.ambulando.app.ui.theme.*
import studio.otherstuff.ambulando.core.docs.*

@RunWith(AndroidJUnit4::class)
class MessageLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightLinks() = checkLinks(AppearancePreference.LIGHT, AmbLight)
    @Test fun darkLinks() = checkLinks(AppearancePreference.DARK, AmbDark)

    private fun checkLinks(appearance: AppearancePreference, colors: AmbColors) {
        val opened = mutableListOf<String>()
        val actions = RichActions(openUrl = { opened += it }, openMention = { kind, id -> opened += "$kind:$id" })
        val body = "Plain text\n\nhttps://example.com/raw\n\n[Named link](https://example.com/named)\n\n[File](storage://12345678-1234-1234-1234-123456789def)\n\n[Email](mailto:hello@example.com)\n\n@[Agent](mention:agent:agent-id)\n\n`https://example.com/code`"
        val blocks = MarkdownBlocks.parse(body)
        val runs = blocks.filterIsInstance<RenderBlock.Paragraph>().flatMap { it.runs }
        val annotated = InlineText.annotated(runs, colors, actions)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(5, links.size)
        links.take(4).forEach {
            val style = requireNotNull(it.item.styles?.style)
            assertEquals(colors.link, style.color)
            assertTrue(requireNotNull(style.textDecoration).contains(TextDecoration.Underline))
        }
        assertEquals(colors.primary, links.last().item.styles?.style?.color)
        // Accessible ink on all surfaces used by message paragraphs, quotes and internal-link highlights.
        listOf(colors.background, colors.canvas, colors.muted, colors.accent).forEach { surface ->
            val a = colors.link.luminance(); val b = surface.luminance()
            assertTrue("Link contrast must reach 4.5:1", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
        }
        val formatted = InlineText.annotated(listOf(InlineRun("Formatted", bold = true, code = true, strike = true, link = "https://example.com")), colors, actions)
        val style = requireNotNull(formatted.getLinkAnnotations(0, formatted.length).single().item.styles?.style)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertTrue(requireNotNull(style.textDecoration).contains(TextDecoration.LineThrough))
        assertTrue(requireNotNull(style.textDecoration).contains(TextDecoration.Underline))
        val docId = "12345678-1234-1234-1234-123456789abc"
        val internal = InlineText.annotated(listOf(
            InlineRun("Document", strike = true, link = "wiki:$docId"),
            InlineRun("Wiki", wiki = WikiTarget(documentId = docId, title = "Wiki")),
        ), colors, actions)
        internal.getLinkAnnotations(0, internal.length).forEach {
            assertEquals(colors.link, it.item.styles?.style?.color)
            assertTrue(requireNotNull(it.item.styles?.style?.textDecoration).contains(TextDecoration.Underline))
            it.item.linkInteractionListener?.onClick(it.item)
        }
        assertEquals(listOf("document:$docId", "document:$docId"), opened)
        opened.clear()
        compose.setContent {
            AmbulandoTheme(appearance) {
                Column(Modifier.background(colors.background)) { RichBlocks(blocks, actions = actions) }
            }
        }
        listOf("https://example.com/raw", "Named link", "File", "Email", "@Agent").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed().performFirstLinkClick()
        }
        compose.waitForIdle()
        assertEquals(listOf("https://example.com/raw", "https://example.com/named", "storage://12345678-1234-1234-1234-123456789def", "mailto:hello@example.com", "agent:agent-id"), opened)
        compose.onNodeWithText("https://example.com/code").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "message-links-${appearance.name}.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
