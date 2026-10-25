package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAvatars
import studio.otherstuff.ambulando.app.ui.shell.InitialsAvatar
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.surfaces.LauncherLogoSource

/**
 * Loads a workspace storage object (`storage://<uuid>`) as an image through a signed download into the file cache.
 * Provided by the app model; without it storage logos fall through to the next candidate.
 */
fun interface StorageLogoLoader {
    suspend fun image(objectId: String): ImageBitmap?
}

val LocalStorageLogoLoader = staticCompositionLocalOf<StorageLogoLoader?> { null }

/**
 * A launcher logo (iOS `LauncherLogoView`): the first candidate that loads — storage objects through the signed file
 * cache, public HTTPS images through the credential-free avatar cache — otherwise the title's initials.
 */
@Composable
fun LauncherLogo(sources: List<LauncherLogoSource>, title: String, size: Dp = 38.dp, modifier: Modifier = Modifier) {
    val avatars = LocalAvatars.current
    val storage = LocalStorageLogoLoader.current
    var image by remember(sources) {
        mutableStateOf(sources.firstNotNullOfOrNull { (it as? LauncherLogoSource.Web)?.let { web -> avatars?.memoryImage(web.url.toString()) } })
    }
    LaunchedEffect(sources, avatars, storage) {
        if (image != null) return@LaunchedEffect
        for (source in sources) {
            val loaded = when (source) {
                is LauncherLogoSource.Web -> avatars?.image(source.url.toString())
                is LauncherLogoSource.Storage -> storage?.image(source.objectId)
            }
            if (loaded != null) { image = loaded; break }
        }
    }
    Box(modifier.size(size).clip(CircleShape).border(2.dp, Amb.colors.border, CircleShape)) {
        val loaded = image
        if (loaded != null) Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).testTag("launcher.logo"))
        else InitialsAvatar(title, size)
    }
}
