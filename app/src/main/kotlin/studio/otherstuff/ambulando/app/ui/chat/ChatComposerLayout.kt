package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout

/**
 * Reserve the editor before measuring suggestions and recording review. On a short screen with the IME open,
 * details scroll in the remaining space instead of squeezing the editor and its controls to zero height.
 */
@Composable
internal fun ChatComposerLayout(
    header: @Composable () -> Unit,
    messages: @Composable () -> Unit,
    details: @Composable () -> Unit,
    composer: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxSize().imePadding()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val headerPlaceable = subcompose("header") { Box(Modifier.fillMaxWidth()) { header() } }.single().measure(loose)
        val editor = subcompose("composer") { Box(Modifier.fillMaxWidth()) { composer() } }.single()
            .measure(loose.copy(maxHeight = (constraints.maxHeight - headerPlaceable.height).coerceAtLeast(0)))
        val remaining = (constraints.maxHeight - headerPlaceable.height - editor.height).coerceAtLeast(0)
        val detail = subcompose("details") {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { details() }
        }.single().measure(loose.copy(maxHeight = remaining))
        val feed = subcompose("messages") { Box(Modifier.fillMaxSize()) { messages() } }.single()
            .measure(loose.copy(maxHeight = (remaining - detail.height).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            headerPlaceable.placeRelative(0, 0)
            feed.placeRelative(0, headerPlaceable.height)
            detail.placeRelative(0, headerPlaceable.height + feed.height)
            editor.placeRelative(0, constraints.maxHeight - editor.height)
        }
    }
}
