package studio.otherstuff.ambulando.app.ui.nav

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import studio.otherstuff.ambulando.app.media.AvatarStore
import studio.otherstuff.ambulando.app.ui.shell.InitialsAvatar
import studio.otherstuff.ambulando.core.profiles.PersonDisplay

/**
 * A record opened from Deck, Search, a mention, a pin or another section. Routes carry their workspace so a restored or
 * stale route can never open a record under a different workspace.
 */
@Immutable
sealed interface WorkRoute {
    val workspaceId: String

    data class Task(override val workspaceId: String, val taskId: String) : WorkRoute
    data class Doc(override val workspaceId: String, val docId: String) : WorkRoute
    data class File(override val workspaceId: String, val fileId: String) : WorkRoute
    data class Thread(override val workspaceId: String, val channelId: String?, val threadId: String) : WorkRoute
    data class Channel(override val workspaceId: String, val channelId: String) : WorkRoute
    data class Canvas(override val workspaceId: String, val canvasId: String) : WorkRoute
    /** An Excalidraw board on a canvas, full screen. */
    data class Board(override val workspaceId: String, val canvasId: String, val napletId: String) : WorkRoute
    data class ContextComponent(override val workspaceId: String, val scopeId: String, val componentId: String) : WorkRoute
    /** Publisher-owned HTML (a canvas naplet) in the labelled sandboxed view. */
    data class HtmlArtefact(override val workspaceId: String, val canvasId: String, val napletId: String, val title: String) : WorkRoute
}

/** The conversation shown in the focused thread view over the current section (the web's thread modal). */
@Immutable
data class ThreadFocus(val workspaceId: String, val channelId: String?, val threadId: String)

/** What every workspace screen is bound to: the connection, the workspace (with its `app_npub`) and the shared context. */
@Immutable
data class ScreenScope(
    val connectionId: String,
    val workspaceId: String,
    val appNpub: String,
    val signerNpub: String,
    /** The sidebar's Scope (null for Home) … */
    val scopeId: String?,
    /** … and Channel (null for "all channels"). */
    val channelId: String?,
)

/** App-wide services the screens draw with, provided once by the shell. */
class AppServices(
    val avatars: AvatarStore?,
    /** HTTPS links open as in-app browser tabs; other schemes are handed to Android by the shell. */
    val openUrl: (String) -> Unit,
    /** Opens a record in its section (or on the current stack inside Deck, Agents, Context and Toolbox). */
    val open: (WorkRoute) -> Unit,
    /** Opens a conversation in the focused thread view. */
    val focusThread: (ThreadFocus) -> Unit,
    /** A mention or record reference (`mention:<kind>:<id>`). */
    val openMention: (kind: String, id: String) -> Unit,
    /** Whether the identity is unlocked (writes and signed downloads need it). */
    val unlocked: Boolean,
    /** Sample-data mode: screens show the sample label. */
    val sample: Boolean,
)

val LocalAppServices = staticCompositionLocalOf<AppServices> { error("AppServices not provided") }

/** The avatar cache for [PersonAvatar]; null (initials only) in previews and tests that do not provide one. */
val LocalAvatars = staticCompositionLocalOf<AvatarStore?> { null }

/**
 * A person's avatar: the cached public-profile picture when one has loaded, initials otherwise (never a blank). Pictures
 * load off the main thread through the credential-free avatar cache.
 */
@Composable
fun PersonAvatar(person: PersonDisplay, size: Dp = 34.dp, avatars: AvatarStore? = LocalAvatars.current) {
    val url = person.avatarURL?.toString()
    var image by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { avatars?.memoryImage(it) }) }
    LaunchedEffect(url, avatars) {
        if (url != null && avatars != null && image == null) image = avatars.image(url)
    }
    Box(Modifier.size(size).clip(CircleShape)) {
        val loaded = image
        if (loaded != null) Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).testTag("avatar.image"))
        else InitialsAvatar(person.name, size)
    }
}
