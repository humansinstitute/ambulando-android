package studio.otherstuff.ambulando.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import java.io.File

@Composable internal fun storedRichActions(model: AppModel, allowRead: Boolean = true, wiki: studio.otherstuff.ambulando.core.docs.WikiLinkController? = null): RichActions {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    return RichActions(services.openMention, { url -> if (allowRead || !url.startsWith("storage://")) services.openUrl(url) }, { id, alt -> StoredImage(model, id, alt, allowRead) },
        openWiki = wiki?.let { w -> { target -> scope.launch { w.activate(target) }; Unit } })
}

@Composable internal fun StoredImage(model: AppModel, id: String, alt: String, allowRead: Boolean) {
    val services = LocalAppServices.current
    val bitmap = rememberStoredBitmap(model, id, allowRead, 2048)
    bitmap?.let {
        Image(it, alt.ifBlank { "Image" }, Modifier.fillMaxWidth().heightIn(max = 400.dp)
            .clickable(onClickLabel = "View image") { services.viewImage(id, alt) }.testTag("image.stored.$id"))
    } ?: Text(if (alt.isBlank()) "Image not cached" else "$alt · image not cached")
}

/** A stored image decoded off the main thread, at most [maxSide] pixels on its long side; null until it is cached. */
@Composable internal fun rememberStoredBitmap(model: AppModel, id: String, allowRead: Boolean, maxSide: Int): ImageBitmap? {
    val w = model.workspace ?: return null
    val context = LocalContext.current
    val cache = remember { FileCache(File(context.cacheDir, "AmbulandoFiles")) }
    val local by remember(w.id, id) { model.store.liveQuery({ it.workspaceId == w.id && it.table == StoreTable.FILES }) { cache.attachment(w.id, id) } }.collectAsState(null)
    var bitmap by remember(w.id, id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(w.id, id) { if (allowRead && !w.accessRemoved) model.sync.downloadAttachment(model.connection!!, w, id, cache) }
    LaunchedEffect(local, maxSide) { local?.let { file -> bitmap = withContext(Dispatchers.IO) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } } }
    return bitmap
}

/** The full-screen overlay for one stored image, decoded at a higher resolution than the inline preview. */
@Composable internal fun StoredImageViewer(model: AppModel, id: String, title: String, onShare: (() -> Unit)?, onDismiss: () -> Unit) {
    val w = model.workspace
    val bitmap = rememberStoredBitmap(model, id, w?.accessRemoved == false, 4096)
    studio.otherstuff.ambulando.app.ui.rich.ImageLightbox(bitmap, title, onDismiss, onShare)
}

/** Decode and project potentially large rich documents away from the input/render thread. */
@Composable internal fun RichDocument(model: AppModel, body: studio.otherstuff.ambulando.core.transport.DocBodyRecord, allowRead: Boolean, wiki: studio.otherstuff.ambulando.core.docs.WikiLinkController? = null) {
    var blocks by remember(body) { mutableStateOf(emptyList<studio.otherstuff.ambulando.core.docs.RenderBlock>()) }
    LaunchedEffect(body) { blocks = withContext(Dispatchers.Default) {
        studio.otherstuff.ambulando.core.docs.DocumentRender.blocks(studio.otherstuff.ambulando.core.docs.DocumentBody(body.bytes, body.contentType))
    } }
    val state = wiki?.state?.collectAsState()?.value
    LaunchedEffect(wiki, body) { wiki?.refresh() }
    // Bound links show their page's current title (after a rename) while the stored text stays as written.
    val shown = if (state != null) remember(blocks, state.context.docs) { studio.otherstuff.ambulando.core.docs.WikiLinks.retitled(blocks, state.context.docs) } else blocks
    studio.otherstuff.ambulando.app.ui.rich.RichBlocks(shown, actions = storedRichActions(model, allowRead, wiki))
    if (wiki != null && state != null) {
        LaunchedEffect(state.pendingOpen) { if (state.pendingOpen != null) wiki.openPendingCreated() }
        WikiSheets(wiki, state, rememberCoroutineScope())
    }
}
