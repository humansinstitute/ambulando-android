package studio.otherstuff.ambulando.app

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.app.ui.work.*
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
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val actions = rememberWriteActions(model)
    val startUpload = {
        uploadChannel = model.context.channelId?.takeIf { id -> model.channels.any { it.id == id && !it.accessRemoved } }
        if (uploadChannel == null) choosingChannel = true else picker.launch(arrayOf("*/*"))
    }
    val channelNames = remember(model.channels) { model.channels.associate { it.id to it.name } }
    val loadedRows = rows
    if (fileId != null) {
        key(model.activeBoundaryId, fileId) {
            FileDetail(model, loadedRows.firstOrNull(), channelNames, cache, onPreview = { file, type -> preview = file to type })
        }
    } else GroupedSurface(Modifier.fillMaxSize()) {
        ListHeaderBar {
            Spacer(Modifier.weight(1f))
            IconButton(enabled = !w.accessRemoved, onClick = startUpload, modifier = Modifier.testTag("files.upload")) {
                Icon(Icons.Filled.AddCircle, "Upload", Modifier.size(28.dp), tint = Amb.colors.primary)
            }
        }
        val shownFolders = folders.filter { it.parentFolderId == folder && !it.deleted }
        val shownFiles = loadedRows.filter { it.folderId == folder || model.context.channelId == null }
        val byChannel = shownFiles.groupBy { it.channelId }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groupedSection("Uploading", pending, key = { "upload:${it.id}" }) { item, first, last ->
                GroupedRow(first, last) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp), tint = Amb.colors.primary)
                            Text(item.string("display_name") ?: "File", fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground)
                        }
                        WriteStatusLine(item, "Upload", actions)
                    }
                }
            }
            if (folder != null) item(key = "up") {
                Spacer(Modifier.height(12.dp))
                GroupedRow(first = true, last = true, onClick = { folder = folders.firstOrNull { it.id == folder }?.parentFolderId }, tag = "files.up") {
                    Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(20.dp), tint = Amb.colors.primary)
                    Text("Up a folder", fontSize = AmbType.body, color = Amb.colors.foreground)
                }
            }
            if (shownFolders.isNotEmpty()) item(key = "folders-gap") { Spacer(Modifier.height(12.dp)) }
            groupedSection(null, shownFolders, key = { "folder:${it.id}" }) { item, first, last ->
                GroupedRow(first, last, onClick = { folder = item.id }, chevron = true, tag = "files.folder") {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(22.dp), tint = Amb.colors.primary)
                    Text(item.title, fontSize = AmbType.body, color = Amb.colors.foreground)
                }
            }
            byChannel.entries.sortedBy { channelNames[it.key] ?: "" }.forEach { (channelId, files) ->
                groupedSection("#" + (channelNames[channelId] ?: "channel"), files, key = { it.id }) { file, first, last ->
                    GroupedRow(first, last, onClick = { services.open(studio.otherstuff.ambulando.app.ui.nav.WorkRoute.File(w.id, file.id)) }, chevron = true, tag = "files.row") {
                        Icon(fileIcon(file.contentType), null, Modifier.size(22.dp), tint = Amb.colors.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(file.displayName, fontSize = AmbType.subheadline, color = Amb.colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val caption = listOf(byteLabel(file.sizeBytes), studio.otherstuff.ambulando.app.ui.chat.ChatTime.label(file.updatedAt)).filter { it.isNotBlank() }.joinToString(" · ")
                            if (caption.isNotEmpty()) Text(caption, fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                            if (file.accessRemoved) AccessRemovedNote()
                        }
                    }
                }
            }
            if (shownFiles.isEmpty() && shownFolders.isEmpty() && pending.isEmpty()) item(key = "empty") {
                EmptyNote("No files", Icons.Outlined.Folder, if (services.unlocked) "Files in this scope appear here. Tap + to upload." else "Unlock to load files.")
            }
        }
    }
    preview?.let { (file, type) -> FilePreview(file, type) { preview = null } }
}

@Composable internal fun FilePreview(file: File, type: String, done: () -> Unit) {
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

/** The icon the iPhone's Files rows draw for a content type. */
internal fun fileIcon(contentType: String?): androidx.compose.ui.graphics.vector.ImageVector {
    val type = contentType?.lowercase() ?: return Icons.Outlined.InsertDriveFile
    return when {
        type.startsWith("image/") -> Icons.Outlined.Image
        type.startsWith("video/") -> Icons.Outlined.Movie
        type.startsWith("audio/") -> Icons.Outlined.GraphicEq
        type.contains("pdf") -> Icons.Outlined.PictureAsPdf
        type.contains("csv") || type.contains("spreadsheet") -> Icons.Outlined.TableChart
        type.startsWith("text/") -> Icons.Outlined.Article
        else -> Icons.Outlined.InsertDriveFile
    }
}

/** One file: its details, then Preview and Share once downloaded, or Download (iOS `FileDetail`). */
@Composable private fun FileDetail(model: AppModel, file: studio.otherstuff.ambulando.core.transport.FileRecord?, channelNames: Map<String, String>, cache: FileCache, onPreview: (File, String) -> Unit) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val context = LocalContext.current
    val amb = Amb.colors
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    if (file == null) { GroupedSurface(Modifier.fillMaxSize()) { EmptyNote("File", Icons.Outlined.InsertDriveFile, "This file is not on this device.") }; return }
    val local by remember(file) { model.store.liveQuery({ it.workspaceId == w.id }) { cache.cached(file) } }.collectAsState(null)
    var downloading by remember(file.id) { mutableStateOf(false) }
    var failure by remember(file.id) { mutableStateOf<String?>(null) }
    val sync = model.sync
    val save = rememberFileSave(file.displayName, attachmentMimeType(file.displayName, file.contentType)) {
        sync.downloadForExport(c, w, file, cache)
    }
    GroupedSurface(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        GroupedRow(first = true, last = false) {
            Icon(fileIcon(file.contentType), null, Modifier.size(34.dp), tint = amb.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.displayName, fontSize = AmbType.body, fontWeight = FontWeight.SemiBold, color = amb.foreground)
                val caption = listOfNotNull(file.contentType, byteLabel(file.sizeBytes).takeIf { it.isNotBlank() }).joinToString(" · ")
                if (caption.isNotEmpty()) Text(caption, fontSize = AmbType.footnote, color = amb.mutedForeground)
            }
        }
        DetailValue("Channel", "#" + (channelNames[file.channelId] ?: "channel"), last = false)
        DetailValue("Updated", studio.otherstuff.ambulando.app.ui.chat.ChatTime.label(file.updatedAt), last = file.description.isNullOrBlank())
        file.description?.takeIf { it.isNotBlank() }?.let { GroupedRow(first = false, last = true) { Text(it, fontSize = AmbType.footnote, color = amb.foreground) } }
        if (file.accessRemoved) AccessRemovedNote(Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
        Spacer(Modifier.height(16.dp))
        val disabled = file.accessRemoved || w.accessRemoved
        GroupedRow(first = true, last = true, onClick = save.start,
            enabled = !disabled && !save.busy && (local != null || services.unlocked), tag = "files.save") {
            Icon(Icons.Outlined.Download, null, Modifier.size(20.dp), tint = amb.primary)
            Text(if (save.busy) "Saving…" else "Save to Files", color = amb.primary)
        }
        save.message?.let { GroupedFooter(it) }
        Spacer(Modifier.height(12.dp))
        if (local == null) {
            GroupedRow(first = true, last = true, onClick = {
                downloading = true; failure = null
                model.act {
                    try { model.sync.download(c, w, file, cache) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failure = e.message ?: "The download failed." }
                    finally { downloading = false }
                }
            }, enabled = !disabled && !downloading && services.unlocked, tag = "files.download") {
                if (downloading) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Downloading…", color = amb.mutedForeground) }
                else { Icon(Icons.Outlined.Download, null, Modifier.size(20.dp), tint = amb.primary); Text("Download", color = amb.primary) }
            }
        } else {
            GroupedRow(first = true, last = false, onClick = { onPreview(local!!, attachmentMimeType(file.displayName, file.contentType)) }, tag = "files.preview") {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(20.dp), tint = amb.primary); Text("Preview", color = amb.primary)
            }
            GroupedRow(first = false, last = true, onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", local!!)
                val send = Intent(Intent.ACTION_SEND).setType(attachmentMimeType(file.displayName, file.contentType)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share ${file.displayName}"))
            }, tag = "files.share") { Icon(Icons.Outlined.Share, null, Modifier.size(20.dp), tint = amb.primary); Text("Share", color = amb.primary) }
        }
        if (local == null && !services.unlocked) GroupedFooter("Unlock to download.")
        failure?.let { GroupedFooter(it, color = amb.destructive) }
        GroupedFooter("Downloads are checked against the file's checksum and kept in this app's cache.")
    }
}

@Composable private fun DetailValue(label: String, value: String, last: Boolean) {
    GroupedRow(first = false, last = last) {
        Text(label, fontSize = AmbType.body, color = Amb.colors.foreground, modifier = Modifier.weight(1f))
        Text(value, fontSize = AmbType.body, color = Amb.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
