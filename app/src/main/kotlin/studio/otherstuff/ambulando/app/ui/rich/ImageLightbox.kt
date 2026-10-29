package studio.otherstuff.ambulando.app.ui.rich

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The full-screen image overlay (the web's lightbox) for inline message images and image attachments: the image fits
 * the screen, pinches and pans to zoom, double-tap toggles zoom, and Back, the close button or a tap on the backdrop
 * dismisses it. Sharing the stored file is an explicit action here, never what a tap on the image does.
 */
@Composable
fun ImageLightbox(image: ImageBitmap?, title: String, onDismiss: () -> Unit, onShare: (() -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember(image) { mutableFloatStateOf(1f) }
        var offset by remember(image) { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).testTag("image.lightbox")
                .pointerInput(image) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) CircularProgressIndicator(color = Color.White, modifier = Modifier.testTag("image.lightbox.loading"))
            else Image(
                image, title.ifBlank { "Image" }, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(image) {
                        detectTapGestures(
                            onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                            // A tap on the image itself keeps the overlay open.
                            onTap = {},
                        )
                    }
                    .pointerInput(image) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .testTag("image.lightbox.image"),
            )
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (onShare != null) IconButton(onClick = onShare, modifier = Modifier.testTag("image.lightbox.share")) {
                    Icon(Icons.Outlined.Share, "Share image", tint = Color.White)
                }
            }
            // Close sits at the bottom, within thumb reach, above the navigation bar.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                    .background(Color.White.copy(alpha = 0.16f), MaterialTheme.shapes.extraLarge).testTag("image.lightbox.close"),
            ) { Icon(Icons.Outlined.Close, "Close image", tint = Color.White) }
        }
    }
}
