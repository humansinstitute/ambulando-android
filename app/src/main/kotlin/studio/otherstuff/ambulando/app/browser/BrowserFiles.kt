package studio.otherstuff.ambulando.app.browser

import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** A picker result belongs only to the document that requested it, and is completed exactly once. */
class BrowserFileSelection(val tab: BrowserTab, val params: WebChromeClient.FileChooserParams, private val callback: ValueCallback<Array<Uri>>, val directory: Boolean = false) {
    private val document = tab.documentId
    val relativePaths = mutableListOf<String>()
    private var completed = false
    fun finish(uris: Array<Uri>?) {
        if (completed) return
        completed = true
        if (relativePaths.isNotEmpty() && uris != null && document == tab.documentId) {
            tab.webView.evaluateJavascript("window.__ambulandoDirectoryPaths=" + org.json.JSONArray(relativePaths).toString()) {
                callback.onReceiveValue(if (document == tab.documentId) uris else null)
            }
        } else callback.onReceiveValue(if (document == tab.documentId) uris else null)
        if (uris == null || document != tab.documentId) cleanup()
    }
    var captureUri: Uri? = null
        private set
    private var captureFile: java.io.File? = null
    val capture: Boolean get() = params.isCaptureEnabled && params.acceptTypes.any { it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/") }
    fun cleanup() { captureFile?.delete(); captureFile = null; captureUri = null }
    fun intent(context: android.content.Context? = null): Intent {
        if (directory || params.mode == 2) return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (capture && context != null) {
            val mime = params.acceptTypes.firstOrNull().orEmpty()
            if (mime.startsWith("audio/")) return Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            val directory = java.io.File(context.cacheDir, "AmbulandoFiles").apply { mkdirs() }
            val file = java.io.File.createTempFile("browser-capture-", if (mime.startsWith("video/")) ".mp4" else ".jpg", directory)
            captureFile = file
            captureUri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
            return Intent(if (mime.startsWith("video/")) android.provider.MediaStore.ACTION_VIDEO_CAPTURE else android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, captureUri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.map { value ->
            if (value.startsWith(".")) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1)) ?: "*/*" else value
        }.filter { it.matches(Regex("[a-zA-Z0-9!#$&^_.+*-]+/[a-zA-Z0-9!#$&^_.+*-]+")) }
        if (types.size == 1) type = types.first() else if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

data class BrowserDownload(val tab: BrowserTab, val document: Long, val url: String?, val bytes: ByteArray?, val filename: String, val mime: String, val file: java.io.File? = null) {
    @Volatile private var valid = true
    fun invalidate() { valid = false }
    val current: Boolean get() = valid && tab.documentId == document
}

/** One bounded transfer; cancelling disconnects its socket and removes the unfinished document. */
class BrowserFileTransfer(val request: BrowserDownload) {
    @Volatile var cancelled = false
        private set
    @Volatile var connection: HttpURLConnection? = null
    var received by mutableStateOf(0)
    var total by mutableStateOf(-1L)
    fun cancel() { cancelled = true; request.invalidate(); connection?.disconnect() }
    fun check() { check(!cancelled && request.current) { "Download cancelled." } }
}

object BrowserFilePolicy {
    const val MAX_BYTES = 1024 * 1024 * 1024
    const val GENERATED_MAX_BYTES = 256 * 1024 * 1024
    fun filename(value: String?): String = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("").trim().take(160).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
    fun https(url: String): Boolean = runCatching { val u = URI(url); u.scheme == "https" && u.host != null && u.userInfo == null }.getOrDefault(false)

    /** No Tower signer or identity headers. Cookies are read separately for each destination, including redirects. */
    fun fetchTo(url: String, transfer: BrowserFileTransfer, cookies: (String) -> String?, output: java.io.OutputStream, userAgent: String? = null, setCookie: (String, String) -> Unit = { _, _ -> }) {
        var next = url
        repeat(6) {
            transfer.check()
            require(https(next)) { "Only HTTPS downloads are supported." }
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            transfer.connection = connection
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            cookies(next)?.let { connection.setRequestProperty("Cookie", it) }
            try {
                val status = connection.responseCode
                connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach { setCookie(next, it) }
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Download redirect has no destination.")).toString()
                } else {
                    check(status in 200..299) { "Download failed (HTTP $status)." }
                    require(connection.contentLengthLong <= MAX_BYTES) { "Download exceeds the 1 GB limit." }
                    transfer.total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            transfer.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(transfer.received.toLong() + count <= MAX_BYTES) { "Download exceeds the 1 GB limit." }
                            output.write(buffer, 0, count); transfer.received += count
                        }
                        transfer.check()
                        check(transfer.total < 0 || transfer.received.toLong() == transfer.total) { "Download ended before all bytes arrived." }
                    }
                    return
                }
            } finally { transfer.connection = null; connection.disconnect() }
        }
        error("Too many download redirects.")
    }
}

/** Native Android picker/save UI, kept outside the page; cancellation never sends a stale result to a new origin. */
@Composable
internal fun BrowserFileFlows(browser: BrowserModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activePicker by remember { mutableStateOf<BrowserFileSelection?>(null) }
    var activeSave by remember { mutableStateOf<BrowserDownload?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = activePicker; activePicker = null
        val data = result.data
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
            data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray() }
                ?: data?.data?.let { arrayOf(it) } ?: request?.captureUri?.let { arrayOf(it) }
        } else null
        scope.launch(Dispatchers.Main.immediate) {
            val selected = if (request != null && (request.directory || request.params.mode == 2) && uris?.size == 1) runCatching {
                withContext(Dispatchers.IO) { treeFiles(context, uris[0], request.relativePaths) }
            }.getOrElse { request.tab.refusal = it.message; null } else uris
            request?.finish(selected?.filter { it.scheme == "content" }?.toTypedArray())
            if (uris == null) request?.cleanup()
            if (browser.fileSelection === request) browser.fileSelection = null
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = activeSave; activeSave = null
        if (browser.download === request) browser.download = null
        if (uri == null) request?.file?.delete()
        if (uri != null && (request == null || !request.current)) { request?.file?.delete(); runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        if (request != null && uri != null && request.current) scope.launch(Dispatchers.Main.immediate) {
            val transfer = BrowserFileTransfer(request)
            browser.fileTransfer = transfer
            request.tab.refusal = null
            val result = runCatching {
                // Snapshot browser-profile cookies on the WebView thread, never the app's Tower credentials.
                val userAgent = request.tab.webView.settings.userAgentString
                transfer.total = request.file?.length() ?: request.bytes?.size?.toLong() ?: -1
                val cookieManager = androidx.webkit.ProfileStore.getInstance().getProfile(studio.otherstuff.ambulando.app.web.WebProfiles.BROWSER)?.cookieManager
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        when {
                            request.file != null -> request.file.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { transfer.check(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); transfer.received += count }
                            }
                            request.bytes != null -> { transfer.check(); output.write(request.bytes) }
                            else -> BrowserFilePolicy.fetchTo(request.url!!, transfer, { cookieManager?.getCookie(it) }, output, userAgent) { url, cookie -> cookieManager?.setCookie(url, cookie) }
                        }
                        transfer.check()
                    } ?: error("Cannot open the selected destination.")
                }
            }
            request.file?.delete()
            if (browser.fileTransfer === transfer) browser.fileTransfer = null
            request.tab.refusal = if (transfer.cancelled) "Download cancelled." else result.fold({ "Saved ${request.filename}." }, { it.message ?: "Download failed." })
            if (result.isFailure) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val request = activePicker
        if (allowed && request != null && browser.fileSelection === request) {
            runCatching { pick.launch(request.intent(context)) }.onFailure { request.finish(null); request.cleanup(); activePicker = null; browser.fileSelection = null; request.tab.refusal = "Capture app unavailable." }
        } else { request?.finish(null); request?.cleanup(); if (browser.fileSelection === request) browser.fileSelection = null; activePicker = null }
    }
    LaunchedEffect(browser.fileSelection) {
        browser.fileSelection?.let { request ->
            if (activePicker != null && activePicker !== request) {
                request.finish(null); browser.fileSelection = null
                request.tab.refusal = "Finish or cancel the previous picker first."
                return@LaunchedEffect
            }
            activePicker = request
            runCatching {
                if (request.capture && request.params.acceptTypes.any { it.startsWith("image/") || it.startsWith("video/") } && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    capturePermission.launch(android.Manifest.permission.CAMERA)
                else pick.launch(request.intent(context))
            }.onFailure { request.finish(null); request.cleanup(); activePicker = null; browser.fileSelection = null; request.tab.refusal = "File picker unavailable." }
        }
    }
    LaunchedEffect(browser.download) {
        browser.download?.let { request ->
            if (activeSave != null && activeSave !== request) {
                request.file?.delete(); browser.download = null
                request.tab.refusal = "Finish or cancel the previous save picker first."
                return@LaunchedEffect
            }
            activeSave = request
            runCatching { save.launch(request.filename) }.onFailure { request.file?.delete(); activeSave = null; browser.download = null; request.tab.refusal = "Save picker unavailable." }
        }
    }
    DisposableEffect(browser) { onDispose { activePicker?.finish(null); browser.cancelFileRequests() } }
}

/** Bounded disk spool, acknowledged in small chunks to avoid buffering page exports in either process. */
internal class BrowserGenerated(context: android.content.Context, val tab: BrowserTab, private val name: String, private val mime: String) {
    val document = tab.documentId
    private val file = java.io.File.createTempFile("browser-export-", ".part", context.cacheDir)
    private val output = file.outputStream()
    private var size = 0L
    fun append(bytes: ByteArray) {
        check(tab.documentId == document && bytes.size <= 32768)
        require(size + bytes.size <= BrowserFilePolicy.GENERATED_MAX_BYTES) { "Page export exceeds 256 MB." }
        output.write(bytes); size += bytes.size
    }
    fun finish(): BrowserDownload { output.close(); return BrowserDownload(tab, document, null, null, name, mime, file) }
    fun cancel() { runCatching { output.close() }; file.delete() }
}

/** Folder selection is read-only and bounded; no persistable provider access is retained. */
private fun treeFiles(context: android.content.Context, tree: Uri, paths: MutableList<String>): Array<Uri> {
    val result = mutableListOf<Uri>()
    fun visit(id: String, depth: Int, path: String) {
        require(depth <= 16 && result.size < 1000) { "Choose a folder with fewer than 1000 files and 16 levels." }
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = cursor.getString(0)
                if (cursor.getString(1) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) visit(child, depth + 1, path + "/" + cursor.getString(2))
                else { require(result.size < 1000) { "Choose a folder with fewer than 1000 files." }; result += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, child); paths += path + "/" + cursor.getString(2) }
            }
        }
    }
    val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, root)
    val name = context.contentResolver.query(document, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "folder" } ?: "folder"
    visit(root, 0, name)
    return result.toTypedArray()
}
