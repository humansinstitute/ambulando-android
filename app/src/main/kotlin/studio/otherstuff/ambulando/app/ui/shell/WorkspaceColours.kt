package studio.otherstuff.ambulando.app.ui.shell

import androidx.compose.ui.graphics.Color
import studio.otherstuff.ambulando.core.workspaces.WorkspaceColour

/**
 * Workspace colours. Swatches keep white-text contrast above WCAG AA; the navigation palette is brighter in dark appearance
 * so dots stay visible. Colour supplements the workspace name everywhere and never decides routing.
 */
object WorkspaceTint {
    fun swatch(colour: WorkspaceColour): Color = Color(0xFF000000 or colour.rgb)

    /** Dot and title colour on navigation surfaces. */
    fun navigation(colour: WorkspaceColour, dark: Boolean): Color {
        if (!dark) return swatch(colour)
        val value = when (colour) {
            WorkspaceColour.BLUE -> 0x8CB8FF
            WorkspaceColour.GREEN -> 0x79D5A5
            WorkspaceColour.PURPLE -> 0xCAA5F5
            WorkspaceColour.RED -> 0xFF9EA8
            WorkspaceColour.SLATE -> 0xB7C8D6
        }
        return Color(0xFF000000.toInt() or value)
    }

    /** A faint tint over the surface: `amount` of the workspace colour. */
    fun blend(surface: Color, colour: Color, amount: Float = 0.12f): Color = Color(
        red = surface.red * (1 - amount) + colour.red * amount,
        green = surface.green * (1 - amount) + colour.green * amount,
        blue = surface.blue * (1 - amount) + colour.blue * amount,
        alpha = surface.alpha,
    )
}
