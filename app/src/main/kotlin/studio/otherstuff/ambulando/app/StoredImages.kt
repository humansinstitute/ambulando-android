package studio.otherstuff.ambulando.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import java.io.File

@Composable internal fun storedRichActions(model: AppModel, allowRead: Boolean = true): RichActions {
    val services = LocalAppServices.current
    return RichActions(services.openMention, { url -> if (allowRead || !url.startsWith("storage://")) services.openUrl(url) }, { id, alt -> StoredImage(model, id, alt, allowRead) })
}

@Composable internal fun StoredImage(model: AppModel, id: String, alt: String, allowRead: Boolean) {
    val w = model.workspace ?: return
    val context = LocalContext.current
    val cache = remember { FileCache(File(context.cacheDir, "AmbulandoFiles")) }
    val local by remember(w.id, id) { model.store.liveQuery({ it.workspaceId == w.id && it.table == StoreTable.FILES }) { cache.attachment(w.id, id) } }.collectAsState(null)
    var bitmap by remember(w.id, id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(w.id, id) { if (allowRead && !w.accessRemoved) model.sync.downloadAttachment(model.connection!!, w, id, cache) }
    LaunchedEffect(local) { local?.let { file -> bitmap = withContext(Dispatchers.IO) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } } }
    bitmap?.let { Image(it, alt, Modifier.fillMaxWidth().heightIn(max = 400.dp)) } ?: Text(if (alt.isBlank()) "Image not cached" else "$alt · image not cached")
}

/** Decode and project potentially large rich documents away from the input/render thread. */
@Composable internal fun RichDocument(model: AppModel, body: studio.otherstuff.ambulando.core.transport.DocBodyRecord, allowRead: Boolean) {
    var blocks by remember(body) { mutableStateOf(emptyList<studio.otherstuff.ambulando.core.docs.RenderBlock>()) }
    LaunchedEffect(body) { blocks = withContext(Dispatchers.Default) {
        studio.otherstuff.ambulando.core.docs.DocumentRender.blocks(studio.otherstuff.ambulando.core.docs.DocumentBody(body.bytes, body.contentType))
    } }
    studio.otherstuff.ambulando.app.ui.rich.RichBlocks(blocks, actions = storedRichActions(model, allowRead))
}
