package studio.otherstuff.ambulando.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** APK identity must not depend on a server's generic/incorrect content type. */
internal fun attachmentMimeType(name: String, supplied: String?): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension == "apk") return "application/vnd.android.package-archive"
    val type = supplied?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (type != null && type != "application/octet-stream" && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(type)) return type
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

internal fun saveFileIntent(name: String, mime: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)

/** Stream the verified bytes unchanged; null and failed providers are errors, never a successful save. */
internal fun copySavedFile(source: File, open: () -> OutputStream?) {
    source.inputStream().use { input ->
        (open() ?: error("Cannot open the selected destination.")).use { output -> input.copyTo(output) }
    }
}

internal data class FileSaveAction(val busy: Boolean, val message: String?, val start: () -> Unit)

/** The save route only creates a document. It never sends ACTION_VIEW, ACTION_SEND or an install intent. */
@Composable
internal fun rememberFileSave(name: String, mime: String, load: suspend () -> File): FileSaveAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentLoad by rememberUpdatedState(load)
    var picking by rememberSaveable(name) { mutableStateOf(false) }
    var transferring by remember(name) { mutableStateOf(false) }
    val busy = picking || transferring
    var message by rememberSaveable(name) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picking = false
        val uri: Uri? = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri == null) { message = "Save cancelled." }
        else scope.launch {
            transferring = true
            try {
                val source = currentLoad()
                withContext(Dispatchers.IO) { copySavedFile(source) { context.contentResolver.openOutputStream(uri, "wt") } }
                message = "Saved $name."
            } catch (e: Exception) {
                // A failed provider can leave a partial document. Best-effort removal never hides the original error.
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                if (e is CancellationException) { message = "Save cancelled."; throw e }
                message = "Could not save: ${e.message ?: "destination unavailable"}"
            } finally { transferring = false }
        }
    }
    return FileSaveAction(busy, message) {
        if (!picking && !transferring) {
            picking = true; message = null
            try { picker.launch(saveFileIntent(name, mime)) }
            catch (_: Exception) { picking = false; message = "Save picker unavailable." }
        }
    }
}
