package studio.otherstuff.ambulando.app.ui.rich

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbColors
import studio.otherstuff.ambulando.core.docs.WikiLinks
import studio.otherstuff.ambulando.core.docs.InlineRun
import studio.otherstuff.ambulando.core.docs.RenderBlock

/** How blocks are sized: chat posts and comments (`.chat-post-markdown`) or the document reader. */
enum class RichStyle { CHAT, DOCUMENT }

/** Code surfaces from the web tokens (paired background/ink, not action colours). */
private data class CodePalette(val background: Color, val foreground: Color, val toolbar: Color, val muted: Color)

@Composable
private fun codePalette(): CodePalette = if (Amb.colors == studio.otherstuff.ambulando.app.ui.theme.AmbDark) {
    CodePalette(Color(0xFF111D29), Color(0xFFE8EEF2), Color(0xFF223442), Color(0xFFB3C2C9))
} else {
    CodePalette(Color(0xFFF1F5F3), Color(0xFF16354B), Color(0xFFE5EDE8), Color(0xFF52645C))
}

/** Callbacks the renderer needs from its screen. */
class RichActions(
    /** A mention or record reference (`mention:<kind>:<id>`). */
    val openMention: (kind: String, id: String) -> Unit = { _, _ -> },
    /** An http(s)/mailto link; the screen decides (the in-app browser for HTTPS). */
    val openUrl: (String) -> Unit = {},
    /** Draws a stored image (`storage://<uuid>`); screens without a workspace get a placeholder. */
    val storageImage: (@Composable (objectId: String, alt: String) -> Unit)? = null,
)

object RichLinks {
    fun isSafeWeb(url: String): Boolean = url.substringBefore(':').lowercase() in setOf("https", "http", "mailto")

    /** Object ids are UUIDs (they become cache path components, so nothing else is accepted). */
    fun storageObjectId(source: String): String? {
        if (!source.lowercase().startsWith("storage://")) return null
        val id = source.substring("storage://".length).trim()
        return id.lowercase().takeIf { Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(it) }
    }
}

/**
 * Native rendering of Markdown/rich-document blocks: headings, paragraphs, lists, quotes, code, tables, rules and
 * images. Long code and tables scroll inside their own box; text stays selectable.
 */
@Composable
fun RichBlocks(blocks: List<RenderBlock>, modifier: Modifier = Modifier, style: RichStyle = RichStyle.CHAT, muted: Boolean = false, actions: RichActions = RichActions()) {
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (style == RichStyle.CHAT) 8.dp else 12.dp)) {
            blocks.forEach { RichBlock(it, style, muted, actions) }
        }
    }
}

@Composable
private fun RichBlock(block: RenderBlock, style: RichStyle, muted: Boolean, actions: RichActions) {
    val amb = Amb.colors
    val ink = if (muted) amb.mutedForeground else amb.foreground
    val typography = MaterialTheme.typography
    when (block) {
        is RenderBlock.Heading -> {
            val base: TextStyle = when {
                style == RichStyle.CHAT && block.level <= 1 -> typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                style == RichStyle.CHAT && block.level == 2 -> typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                style == RichStyle.CHAT -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                block.level <= 1 -> typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                block.level == 2 -> typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                else -> typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            }
            Text(InlineText.annotated(block.runs, amb, actions), style = base, color = ink)
        }
        is RenderBlock.Paragraph -> Paragraph(block.runs, ink, actions)
        is RenderBlock.Code -> CodeBlock(block.language, block.text)
        is RenderBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(amb.border, RoundedCornerShape(1.5.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { block.blocks.forEach { RichBlock(it, style, muted, actions) } }
        }
        is RenderBlock.ListBlock -> Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.widthIn(min = if (block.ordered) 22.dp else 14.dp), contentAlignment = Alignment.TopEnd) {
                        val checked = item.checked
                        when {
                            checked != null -> Icon(
                                if (checked) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = if (checked) "Done" else "Not done",
                                tint = if (checked) amb.success else amb.mutedForeground, modifier = Modifier.size(18.dp),
                            )
                            block.ordered -> Text("${block.start + index}.", color = amb.mutedForeground, style = typography.bodyLarge)
                            else -> Text("•", color = amb.mutedForeground, style = typography.bodyLarge)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { item.blocks.forEach { RichBlock(it, style, muted, actions) } }
                }
            }
        }
        is RenderBlock.Table -> TableBlock(block.rows, amb, actions)
        is RenderBlock.Image -> RichImage(block.source, block.alt, actions)
        RenderBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(amb.border))
        is RenderBlock.Preserved -> Column(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, amb.border), RoundedCornerShape(Amb.radiusControl)).padding(8.dp).testTag("doc.preserved"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${block.type} block — shown on the web; kept unchanged here", style = typography.labelSmall, color = amb.mutedForeground)
            if (block.text.isNotEmpty()) Text(block.text, style = typography.bodySmall, color = amb.mutedForeground)
        }
    }
}

/** A paragraph; inline images split it into text and image pieces. */
@Composable
private fun Paragraph(runs: List<InlineRun>, ink: Color, actions: RichActions) {
    val amb = Amb.colors
    val pieces = remember(runs) { pieces(runs) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> Text(InlineText.annotated(piece.runs, amb, actions), style = MaterialTheme.typography.bodyLarge, color = ink)
                is Piece.Img -> RichImage(piece.source, piece.alt, actions)
            }
        }
    }
}

private sealed class Piece {
    data class Text(val runs: List<InlineRun>) : Piece()
    data class Img(val alt: String, val source: String) : Piece()
}

private fun pieces(runs: List<InlineRun>): List<Piece> {
    val out = ArrayList<Piece>()
    var current = ArrayList<InlineRun>()
    for (run in runs) {
        val source = run.image
        if (source != null) {
            if (current.any { it.text.isNotBlank() }) out.add(Piece.Text(current))
            current = ArrayList()
            out.add(Piece.Img(run.text, source))
        } else current.add(run)
    }
    if (current.any { it.text.isNotBlank() } || out.isEmpty()) out.add(Piece.Text(current))
    return out
}

/** `.md-code-block`: language toolbar with Copy, horizontally scrolling monospaced body. */
@Composable
private fun CodeBlock(language: String, text: String) {
    val amb = Amb.colors
    val palette = codePalette()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(Amb.radiusPanel))
            .border(BorderStroke(1.dp, amb.border), RoundedCornerShape(Amb.radiusPanel)).testTag("rich.code"),
    ) {
        Row(
            Modifier.fillMaxWidth().background(palette.toolbar, RoundedCornerShape(topStart = Amb.radiusPanel, topEnd = Amb.radiusPanel))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.ifEmpty { "code" }.lowercase(), color = palette.muted, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Row(
                Modifier.clickable {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text))) }
                    copied = true
                }.semantics { contentDescription = if (copied) "Copied code" else "Copy code" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
                Text(if (copied) "Copied" else "Copy", color = palette.muted, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(text, color = palette.foreground, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("rich.code.text"))
        }
    }
}

/** GFM table in its own horizontal scroller with cell borders and a card-coloured header. */
@Composable
private fun TableBlock(rows: List<List<List<InlineRun>>>, amb: AmbColors, actions: RichActions) {
    val columns = rows.maxOfOrNull { it.size } ?: 0
    Box(
        Modifier.horizontalScroll(rememberScrollState()).border(BorderStroke(1.dp, amb.border))
            .semantics { contentDescription = "Table. " + rows.joinToString(". ") { r -> r.joinToString(", ") { c -> c.joinToString("") { it.text } } } }
            .testTag("rich.table"),
    ) {
        Column {
            rows.forEachIndexed { r, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (c in 0 until columns) {
                        val cell = row.getOrNull(c) ?: emptyList()
                        Box(
                            Modifier.widthIn(min = 48.dp, max = 260.dp).fillMaxHeight()
                                .background(if (r == 0) amb.card else Color.Transparent)
                                .border(BorderStroke(0.5.dp, amb.border)).padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            Text(InlineText.annotated(cell, amb, actions), color = amb.foreground,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
            }
        }
    }
}

/**
 * An image reference. Stored images load through the app's signed storage route (the screen's [RichActions.storageImage]);
 * web images are never fetched (they may track or expose the reader) and show as a link.
 */
@Composable
fun RichImage(source: String, alt: String, actions: RichActions) {
    val amb = Amb.colors
    val objectId = RichLinks.storageObjectId(source)
    when {
        objectId != null && actions.storageImage != null -> actions.storageImage.invoke(objectId, alt)
        objectId != null -> ImageNotice(if (alt.isEmpty()) "Image not available here" else "$alt — image not available here", "image.error")
        RichLinks.isSafeWeb(source) -> Row(
            Modifier.background(amb.muted, RoundedCornerShape(Amb.radiusControl)).clickable { actions.openUrl(source) }.padding(8.dp).testTag("image.remote"),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = amb.primary, modifier = Modifier.size(16.dp))
            Text(if (alt.isEmpty()) "Web image (not loaded)" else "$alt — web image (not loaded)", color = amb.primary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        else -> ImageNotice(if (alt.isEmpty()) "Image unavailable" else "Image: $alt", "image.error")
    }
}

@Composable
fun ImageNotice(text: String, tag: String) {
    val amb = Amb.colors
    Row(Modifier.testTag(tag), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Image, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(16.dp))
        Text(text, color = amb.mutedForeground, style = MaterialTheme.typography.bodySmall)
    }
}

object InlineText {
    /**
     * Styled text for runs. Mentions are tappable chips that stay selectable; only http(s)/mailto links leave the
     * app (through the screen's handler).
     */
    fun annotated(runs: List<InlineRun>, amb: AmbColors, actions: RichActions): AnnotatedString = buildAnnotatedString {
        for (run in runs) {
            var style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = if (run.code) amb.muted else Color.Unspecified,
                textDecoration = listOfNotNull(
                    TextDecoration.LineThrough.takeIf { run.strike },
                    TextDecoration.Underline.takeIf { run.underline },
                ).takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
            )
            val kind = run.mentionKind
            val id = run.mentionId
            when {
                run.mention -> {
                    val person = kind == null || kind == "person" || kind == "agent"
                    style = style.merge(SpanStyle(color = if (person) amb.primary else amb.info, background = amb.accent, fontWeight = FontWeight.Bold))
                    if (kind != null && id != null) {
                        withLink(LinkAnnotation.Clickable("mention:$kind:$id", TextLinkStyles(style)) { actions.openMention(kind, id) }) { append(run.text) }
                    } else withStyle(style) { append(run.text) }
                }
                // A stable document link opens that page by id; Tower decides whether this person may read it.
                WikiLinks.targetId(run.link) != null -> {
                    val target = WikiLinks.targetId(run.link)!!
                    style = style.merge(SpanStyle(color = amb.primary, background = amb.accent, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Clickable("wiki:$target", TextLinkStyles(style)) { actions.openMention("document", target) }) { append(run.text) }
                }
                run.link != null && (RichLinks.isSafeWeb(run.link!!) || RichLinks.storageObjectId(run.link!!) != null) -> {
                    val url = run.link!!
                    style = style.merge(SpanStyle(color = amb.primary))
                    withLink(LinkAnnotation.Clickable(url, TextLinkStyles(style)) { actions.openUrl(url) }) { append(run.text) }
                }
                run.`object` -> withStyle(style.merge(SpanStyle(color = amb.primary, background = amb.accent))) { append(run.text) }
                else -> withStyle(style) { append(run.text) }
            }
        }
    }
}
