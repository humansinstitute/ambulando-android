package studio.otherstuff.ambulando.app.browser

import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat

class BrowserFullscreen(val tab: BrowserTab, val view: View, private val callback: WebChromeClient.CustomViewCallback) {
    private var completed = false
    fun finish() { if (!completed) { completed = true; (view.parent as? ViewGroup)?.removeView(view); callback.onCustomViewHidden() } }
}

/** One document, one decision; no permanent grants or restored permissions. */
class BrowserPermission(val tab: BrowserTab, val origin: String, val permissions: List<String>, val token: Any? = null, private val reply: (Boolean) -> Unit) {
    private val document = tab.documentId
    private var completed = false
    fun finish(allow: Boolean) { if (!completed) { completed = true; reply(allow && document == tab.documentId && origin == tab.origin) } }
}

@Composable internal fun BrowserPermissionFlow(browser: BrowserModel) {
    val context = LocalContext.current
    var active by remember { mutableStateOf<BrowserPermission?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = active; active = null
        if (request != null) {
            val allowed = if (android.Manifest.permission.ACCESS_COARSE_LOCATION in request.permissions)
                request.permissions.any { p -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
            else request.permissions.all { p -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
            request.finish(allowed && browser.permission === request)
            if (browser.permission === request) browser.permission = null
        }
    }
    browser.permission?.let { request ->
        LaunchedEffect(request, active) { if (active != null && active !== request) { request.finish(false); if (browser.permission === request) browser.permission = null } }
        AlertDialog(onDismissRequest = { request.finish(false); browser.permission = null },
            title = { Text("Website permission") },
            text = { Text("${request.origin} wants to use ${request.permissions.map { it.substringAfterLast('.').lowercase().replace('_', ' ') }.distinct().joinToString()}. Allow for this page? You can revoke Android permissions in system settings.") },
            confirmButton = { TextButton(enabled = active == null, onClick = { active = request; runCatching { launcher.launch(request.permissions.toTypedArray()) }.onFailure { request.finish(false); browser.permission = null; active = null } }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { request.finish(false); browser.permission = null }) { Text("Deny") } })
    }
}

@Composable internal fun BrowserFullscreenFlow(browser: BrowserModel) {
    browser.fullscreen?.let { full ->
        Dialog(onDismissRequest = { browser.exitFullscreen() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BackHandler { browser.exitFullscreen() }
            AndroidView(factory = { (full.view.parent as? ViewGroup)?.removeView(full.view); full.view }, modifier = Modifier.fillMaxSize())
        }
    }
}
