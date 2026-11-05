package studio.otherstuff.ambulando.app.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType

// The rows the iPhone's inset-grouped lists draw (Docs, Files, Toolbox, Agents, Context): a rounded white section on the recessed
// surface, hairline dividers inset from the leading edge, a 44dp minimum row and an optional trailing chevron.

/** A section header in the grouped-list style (small, secondary, above the card). */
@Composable internal fun GroupedHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = AmbType.footnote, color = Amb.colors.mutedForeground, modifier = modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
}

/** A section footer in the grouped-list style. */
@Composable internal fun GroupedFooter(text: String, modifier: Modifier = Modifier, color: Color = Amb.colors.mutedForeground) {
    Text(text, fontSize = AmbType.caption, color = color, modifier = modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
}

/**
 * One row of a grouped section. [first] and [last] round the card's corners; every row but the last carries the inset divider.
 * A null [onClick] draws a plain (not tappable) row.
 */
@Composable internal fun GroupedRow(
    first: Boolean, last: Boolean, onClick: (() -> Unit)? = null, enabled: Boolean = true, chevron: Boolean = false,
    tag: String? = null, alpha: Float = 1f, content: @Composable RowScope.() -> Unit,
) {
    val amb = Amb.colors
    val r = Amb.radiusPanel
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(amb.card, shape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Amb.touchTarget)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
                Row(Modifier.weight(1f).alpha(alpha), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
            if (chevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = amb.border)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = amb.border)
    }
}

/** Adds a header, a footer and a card of rows to a lazy list, rounding only the first and last row. */
internal fun <T> LazyListScope.groupedSection(
    header: String?, items: List<T>, key: (T) -> Any, footer: String? = null, footerColor: @Composable () -> Color = { Amb.colors.mutedForeground },
    row: @Composable (item: T, first: Boolean, last: Boolean) -> Unit,
) {
    if (items.isEmpty() && footer == null) return
    if (header != null) item(key = "header:$header") { GroupedHeader(header) }
    items.forEachIndexed { index, value -> item(key = key(value)) { row(value, index == 0, index == items.lastIndex) } }
    if (footer != null) item(key = "footer:${header ?: footer.take(24)}") { GroupedFooter(footer, color = footerColor()) }
}

/** The leading glyph of a grouped row: an icon in a fixed-width frame. */
@Composable internal fun RowGlyph(icon: ImageVector, tint: Color = Amb.colors.primary, width: Dp = 24.dp, size: Dp = 20.dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size), tint = tint) }
}

/** Title over a caption, as the Docs, Files and Toolbox rows set them (subheadline semibold over caption). */
@Composable internal fun RowTitleCaption(
    title: String, caption: String?, modifier: Modifier = Modifier, titleWeight: FontWeight = FontWeight.SemiBold, titleLines: Int = 2, captionLines: Int = 2,
) {
    val amb = Amb.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = AmbType.subheadline, fontWeight = titleWeight, color = amb.foreground, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        caption?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = captionLines, overflow = TextOverflow.Ellipsis) }
    }
}

/** The recessed surface a grouped list sits on. */
@Composable internal fun GroupedSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Amb.colors.canvas), content = content)
}

/** A rounded white card holding rows that are drawn by hand (the Context outline), with the same corner as a section. */
@Composable internal fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(Amb.colors.card, RoundedCornerShape(Amb.radiusPanel))) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) { content() }
    }
}

/** The bordered filter field above a list (the Swift lists' `.searchable` drawer, drawn inline). */
@Composable internal fun FilterField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, tag: String = "list.filter") {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusControl)
    androidx.compose.foundation.text.BasicTextField(
        value, onChange, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = amb.foreground, fontSize = AmbType.body),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(amb.primary),
        modifier = modifier.heightIn(min = Amb.touchTarget).testTag(tag),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(amb.muted, shape).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = amb.mutedForeground)
                Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(placeholder, fontSize = AmbType.body, color = amb.mutedForeground); inner() }
            }
        },
    )
}
