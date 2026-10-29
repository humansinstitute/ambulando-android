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
class BrowserFileSelection(val tab: BrowserTab, val params: WebChromeClient.FileChooserParams, private val callback: ValueCallback<Array<Uri>>) {
    private val document = tab.documentId
    private var completed = false
    fun finish(uris: Array<Uri>?) {
        if (completed) return
        completed = true
        callback.onReceiveValue(if (document == tab.documentId) uris else null)
    }
    fun intent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
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

data class BrowserDownload(val tab: BrowserTab, val document: Long, val url: String?, val bytes: ByteArray?, val filename: String, val mime: String) {
    val current: Boolean get() = tab.documentId == document
}

/** One bounded transfer; cancelling disconnects its socket and removes the unfinished document. */
class BrowserFileTransfer(val request: BrowserDownload) {
    @Volatile var cancelled = false
        private set
    @Volatile var connection: HttpURLConnection? = null
    var received by mutableStateOf(0)
    var total by mutableStateOf(-1L)
    fun cancel() { cancelled = true; connection?.disconnect() }
    fun check() { check(!cancelled && request.current) { "Download cancelled." } }
}

object BrowserFilePolicy {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun filename(value: String?): String = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("").trim().take(160).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
    fun https(url: String): Boolean = runCatching { val u = URI(url); u.scheme == "https" && u.host != null && u.userInfo == null }.getOrDefault(false)

    /** No Tower signer or identity headers. Cookies are read separately for each destination, including redirects. */
    fun fetch(url: String, transfer: BrowserFileTransfer, cookies: (String) -> String?): ByteArray {
        var next = url
        repeat(6) {
            transfer.check()
            require(https(next)) { "Only HTTPS downloads are supported." }
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            transfer.connection = connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            cookies(next)?.let { connection.setRequestProperty("Cookie", it) }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Download redirect has no destination.")).toString()
                } else {
                    check(status in 200..299) { "Download failed (HTTP $status)." }
                    require(connection.contentLengthLong <= MAX_BYTES) { "Download exceeds the 16 MB limit." }
                    transfer.total = connection.contentLengthLong
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            transfer.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_BYTES) { "Download exceeds the 16 MB limit." }
                            output.write(buffer, 0, count); transfer.received = output.size()
                        }
                        transfer.check()
                        output.toByteArray()
                    }
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
                ?: data?.data?.let { arrayOf(it) }
        } else null
        request?.finish(uris?.filter { it.scheme == "content" }?.toTypedArray())
        if (browser.fileSelection === request) browser.fileSelection = null
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = activeSave; activeSave = null
        if (browser.download === request) browser.download = null
        if (uri != null && (request == null || !request.current)) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        if (request != null && uri != null && request.current) scope.launch {
            val transfer = BrowserFileTransfer(request)
            browser.fileTransfer = transfer
            request.tab.refusal = null
            val result = runCatching {
                // Snapshot browser-profile cookies on the WebView thread, never the app's Tower credentials.
                val cookieManager = androidx.webkit.ProfileStore.getInstance().getProfile(studio.otherstuff.ambulando.app.web.WebProfiles.BROWSER)?.cookieManager
                withContext(Dispatchers.IO) {
                    val bytes = request.bytes ?: BrowserFilePolicy.fetch(request.url!!, transfer) { cookieManager?.getCookie(it) }
                    transfer.check()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot open the selected destination.")
                }
            }
            if (browser.fileTransfer === transfer) browser.fileTransfer = null
            request.tab.refusal = if (transfer.cancelled) "Download cancelled." else result.fold({ "Saved ${request.filename}." }, { it.message ?: "Download failed." })
            if (result.isFailure) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }
    LaunchedEffect(browser.fileSelection) {
        browser.fileSelection?.let { request ->
            activePicker = request
            runCatching { pick.launch(request.intent()) }.onFailure { request.finish(null); browser.fileSelection = null; request.tab.refusal = "File picker unavailable." }
        }
    }
    LaunchedEffect(browser.download) {
        browser.download?.let { request ->
            activeSave = request
            runCatching { save.launch(request.filename) }.onFailure { browser.download = null; request.tab.refusal = "Save picker unavailable." }
        }
    }
    DisposableEffect(browser) { onDispose { activePicker?.finish(null); browser.cancelFileRequests() } }
}
