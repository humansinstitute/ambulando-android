package studio.otherstuff.ambulando.app

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import studio.otherstuff.ambulando.app.media.readBounded
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.app.media.ImagePrivacy
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import java.io.File
import java.util.UUID

@Composable internal fun NativeFiles(model: AppModel, fileId: String? = null) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val context = LocalContext.current
    val cache = remember(model.activeBoundaryId) { model.fileCache() }
    var uploadChannel by remember { mutableStateOf<String?>(null) }
    var choosingChannel by remember { mutableStateOf(false) }
    val pending by remember(c.id, w.id) { model.store.liveQuery({ it.table == StoreTable.WRITES }) { model.store.reader.workspaceWrites(c.id, w.id).filter { it.kind == WriteKind.FILE_UPLOAD } } }.collectAsState(emptyList())
    var folder by remember(w.id, model.context) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<File, String>?>(null) }
    val folders by remember(w.id, model.context.channelId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.context.channelId?.let { model.store.reader.folders(w.id, it) } ?: emptyList() } }.collectAsState(emptyList())
    val rows by remember(w.id, model.context, fileId) { model.store.liveQuery({ it.workspaceId == w.id }) { if (fileId == null) model.store.reader.files(w.id, model.context) else listOfNotNull(model.store.reader.file(w.id, fileId)) } }.collectAsState(emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val channel = uploadChannel
        if (uri != null && channel != null) model.act {
            val payload = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                var name = "upload"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name }
                val type = resolver.getType(uri) ?: "application/octet-stream"
                val bytes = resolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error("Could not open file")
                require(bytes.size <= 32 * 1024 * 1024) { "Choose a file smaller than 32 MB" }
                val image = type.startsWith("image/")
                val prepared = if (image) ImagePrivacy.strippedPng(bytes) else bytes
                val safeName = if (image) name.substringBeforeLast('.', name) + ".png" else name
                val staged = "upload-${UUID.randomUUID()}"
                model.writes.staging.mkdirs(); File(model.writes.staging, staged).writeBytes(prepared)
                buildJsonObject { put("channel_id", channel); put("staged", staged); put("content_type", if (image) "image/png" else type); put("file_name", safeName); put("display_name", safeName) }
            }
            model.writes.submit(WriteIntent(WriteKind.FILE_UPLOAD, null, payload), c, w)
        }
    }
    if (choosingChannel) AlertDialog(onDismissRequest = { choosingChannel = false }, title = { Text("Upload to channel") }, text = { Column { model.channels.filter { !it.accessRemoved }.forEach { row -> TextButton(onClick = { uploadChannel = row.id; choosingChannel = false; picker.launch(arrayOf("*/*")) }) { Text(row.name) } } } }, confirmButton = { TextButton(onClick = { choosingChannel = false }) { Text("Cancel") } })
    Column {
        pending.forEach { item -> Row { Text(item.string("display_name") ?: "Uploading file"); Text(item.lastError ?: item.state.wire)
            TextButton(onClick = { model.act { model.writes.retry(item.id, c) } }) { Text("Retry") }
            TextButton(onClick = { model.act { model.store.writer.discardWrite(item.id) } }) { Text("Discard") }
        } }
        TextButton(enabled = !w.accessRemoved, onClick = { uploadChannel = model.context.channelId?.takeIf { id -> model.channels.any { it.id == id && !it.accessRemoved } }; if (uploadChannel == null) choosingChannel = true else picker.launch(arrayOf("*/*")) }) { Text("Upload file") }
        if (folder != null) TextButton(onClick = { folder = folders.firstOrNull { it.id == folder }?.parentFolderId }) { Text("Up a folder") }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(folders.filter { it.parentFolderId == folder && !it.deleted }, key = { "folder:${it.id}" }) { item -> TextButton(onClick = { folder = item.id }) { Text("Folder: ${item.title}") } }
            items(rows.filter { fileId != null || it.folderId == folder || model.context.channelId == null }, key = { it.id }) { file ->
            Text(file.displayName)
            Text(file.contentType ?: "File", style = MaterialTheme.typography.labelSmall)
            if (file.accessRemoved) Text("No longer available to you")
            val local by remember(file) { model.store.liveQuery({ it.workspaceId == w.id }) { cache.cached(file) } }.collectAsState(null)
            if (local == null) TextButton(enabled = !file.accessRemoved && !w.accessRemoved, onClick = { model.act { model.sync.download(c, w, file, cache) } }) { Text("Download") }
            else Row {
                TextButton(onClick = { preview = local!! to (file.contentType ?: "application/octet-stream") }) { Text("Preview") }
                TextButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", local!!)
                val send = Intent(Intent.ACTION_SEND).setType(file.contentType ?: "application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share ${file.displayName}"))
            }) { Text("Share") }
            }
        } }
    }
    preview?.let { (file, type) -> FilePreview(file, type) { preview = null } }
}

@Composable private fun FilePreview(file: File, type: String, done: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(file) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) { withContext(Dispatchers.IO) {
        if (type.startsWith("image/")) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            bitmap = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { it.asImageBitmap() }
        } else if (type.startsWith("text/") || type.contains("json")) text = if (file.length() > 1024 * 1024) "Text preview is limited to 1 MB. Open this file in an installed viewer." else file.inputStream().use { it.readBounded(1024 * 1024).toString(Charsets.UTF_8) }
    } }
    AlertDialog(onDismissRequest = done, title = { Text(file.name) }, text = {
        bitmap?.let { androidx.compose.foundation.Image(it, file.name, Modifier.fillMaxWidth().heightIn(max = 400.dp)) }
            ?: text?.let { androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(it) } }
            ?: Text("Open this file with an installed viewer.")
    }, confirmButton = { TextButton(onClick = {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Open file")) }
    }) { Text("Open in…") } }, dismissButton = { TextButton(onClick = done) { Text("Done") } })
}
