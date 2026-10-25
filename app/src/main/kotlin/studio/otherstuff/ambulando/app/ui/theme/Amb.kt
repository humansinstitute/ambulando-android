package studio.otherstuff.ambulando.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ambulando "perspective passage" tokens, copied from the web client's `ambulando-tokens.css` (light / dark) and
 * applied the way its `styles.css` does: pages, header, chat feed, sidebar and section bar on `--card`.
 */
@Immutable
data class AmbColors(
    /** Page surface (`--card`): white in light mode. */
    val background: Color,
    /** `--background`: the recessed grey kept for grouped forms and nested cards. */
    val canvas: Color,
    val foreground: Color,
    val card: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val primary: Color,
    val primaryForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveBackground: Color,
    val success: Color,
    val successBackground: Color,
    val warning: Color,
    val warningBackground: Color,
    val info: Color,
    val infoBackground: Color,
) {
    /** Selected row (`.sidebar-scope-channel-row.active`): muted fill, bold primary text. */
    val selectedFill get() = muted
    val selectedForeground get() = primary
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

val AmbLight = AmbColors(
    background = c(0xFFFFFF), canvas = c(0xF6F6FA), foreground = c(0x10162B), card = c(0xFFFFFF),
    muted = c(0xEEEFF5), mutedForeground = c(0x596174), border = c(0xD7D9E2),
    primary = c(0x2B1B3C), primaryForeground = c(0xFFFFFF), accent = c(0xEEE8FA), accentForeground = c(0x2B1B3C),
    destructive = c(0xB42318), destructiveBackground = c(0xFEECEB), success = c(0x25613E), successBackground = c(0xE6F2E9),
    warning = c(0x805400), warningBackground = c(0xFFF3D6), info = c(0x215C85), infoBackground = c(0xE8F1F9),
)

val AmbDark = AmbColors(
    background = c(0x1A2238), canvas = c(0x10162B), foreground = c(0xF5F3FC), card = c(0x1A2238),
    muted = c(0x26314A), mutedForeground = c(0xBDC4D6), border = c(0x48546D),
    primary = c(0xCBB8FF), primaryForeground = c(0x20123C), accent = c(0x302447), accentForeground = c(0xE5DAFF),
    destructive = c(0xFCA59D), destructiveBackground = c(0x411710), success = c(0xA4D1B5), successBackground = c(0x203D2C),
    warning = c(0xF3CF80), warningBackground = c(0x40331C), info = c(0xADD2F0), infoBackground = c(0x20384C),
)

object Amb {
    /** Around a document's white page: faint lavender in both appearances. */
    val paperSurround = c(0xF5F3FB)
    /** Ink on the white document page in both appearances. */
    val paperInk = c(0x10162B)
    val brandOrange = c(0xBF4D0D)
    val brandNavy = c(0x10162B)

    val radiusPanel = 10.dp    // --radius-panel 0.625rem
    val radiusControl = 6.dp   // --radius-control 0.375rem
    val touchTarget = 44.dp    // --touch-target 2.75rem

    val colors: AmbColors @Composable get() = LocalAmbColors.current
}

val LocalAmbColors = staticCompositionLocalOf { AmbLight }

enum class AppearancePreference { SYSTEM, LIGHT, DARK }

@Composable
fun AmbulandoTheme(appearance: AppearancePreference = AppearancePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppearancePreference.SYSTEM -> isSystemInDarkTheme()
        AppearancePreference.LIGHT -> false
        AppearancePreference.DARK -> true
    }
    val amb = if (dark) AmbDark else AmbLight
    val scheme = if (dark) {
        darkColorScheme(
            primary = amb.primary, onPrimary = amb.primaryForeground, secondary = amb.primary, onSecondary = amb.primaryForeground,
            background = amb.background, onBackground = amb.foreground, surface = amb.card, onSurface = amb.foreground,
            surfaceVariant = amb.muted, onSurfaceVariant = amb.mutedForeground, outline = amb.border, outlineVariant = amb.border,
            error = amb.destructive, onError = amb.primaryForeground, errorContainer = amb.destructiveBackground,
            secondaryContainer = amb.muted, onSecondaryContainer = amb.primary, primaryContainer = amb.accent,
            onPrimaryContainer = amb.accentForeground, surfaceContainer = amb.card, surfaceContainerHigh = amb.card,
            surfaceContainerLow = amb.card, surfaceContainerLowest = amb.card, surfaceContainerHighest = amb.muted,
        )
    } else {
        lightColorScheme(
            primary = amb.primary, onPrimary = amb.primaryForeground, secondary = amb.primary, onSecondary = amb.primaryForeground,
            background = amb.background, onBackground = amb.foreground, surface = amb.card, onSurface = amb.foreground,
            surfaceVariant = amb.muted, onSurfaceVariant = amb.mutedForeground, outline = amb.border, outlineVariant = amb.border,
            error = amb.destructive, onError = amb.primaryForeground, errorContainer = amb.destructiveBackground,
            secondaryContainer = amb.muted, onSecondaryContainer = amb.primary, primaryContainer = amb.accent,
            onPrimaryContainer = amb.accentForeground, surfaceContainer = amb.card, surfaceContainerHigh = amb.card,
            surfaceContainerLow = amb.card, surfaceContainerLowest = amb.card, surfaceContainerHighest = amb.muted,
        )
    }
    CompositionLocalProvider(LocalAmbColors provides amb) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Editorial serif heading (`--font-editorial`). */
val EditorialStyle @Composable get() = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
val CodeStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val amb = Amb.colors
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Amb.touchTarget),
        shape = RoundedCornerShape(Amb.radiusControl),
        colors = ButtonDefaults.buttonColors(
            containerColor = amb.primary, contentColor = amb.primaryForeground,
            disabledContainerColor = amb.primary.copy(alpha = 0.4f), disabledContentColor = amb.primaryForeground,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

fun Modifier.ambCard(colors: AmbColors): Modifier = this
    .background(colors.card, RoundedCornerShape(Amb.radiusPanel))
    .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(Amb.radiusPanel))
    .padding(16.dp)

enum class NoticeTone { INFO, WARNING, ERROR, SUCCESS }

/** Inline notice using the semantic status palettes. */
@Composable
fun Notice(tone: NoticeTone, text: String, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val (fg, bg, icon) = when (tone) {
        NoticeTone.INFO -> Triple(amb.info, amb.infoBackground, Icons.Outlined.Info)
        NoticeTone.WARNING -> Triple(amb.warning, amb.warningBackground, Icons.Outlined.WarningAmber)
        NoticeTone.ERROR -> Triple(amb.destructive, amb.destructiveBackground, Icons.Outlined.ErrorOutline)
        NoticeTone.SUCCESS -> Triple(amb.success, amb.successBackground, Icons.Outlined.CheckCircle)
    }
    Row(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(Amb.radiusControl)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Text(text, color = fg, style = MaterialTheme.typography.bodySmall)
    }
}
