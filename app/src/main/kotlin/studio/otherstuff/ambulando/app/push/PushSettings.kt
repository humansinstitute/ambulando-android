package studio.otherstuff.ambulando.app.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.otherstuff.ambulando.app.AppModel

@Composable internal fun PushSettings(model: AppModel) {
    val context = LocalContext.current
    val push = model.androidPush ?: return
    val c = model.connection ?: return
    val w = model.workspace ?: return
    var revision by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(push.state.all()) }
    var permitted by remember { mutableStateOf(push.permissionGranted()) }
    var backgroundRead by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { permitted = push.permissionGranted(); revision++ } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(c.id, w.id, revision) {
        // Only observes local registration state while this settings screen is visible; this is not a delivery poller.
        while (true) { rows = push.state.all(); backgroundRead = model.identity.backgroundReadEnabled(); kotlinx.coroutines.delay(1000) }
    }
    fun enable() { context.activity()?.let { activity -> model.act { push.enable(model, activity); revision++ } } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted && push.permissionGranted()
        if (permitted) enable()
    }
    val row = rows.firstOrNull { it.connectionId == c.id && it.workspaceId == w.id && it.signerNpub == c.signerNpub }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Phone notifications", style = MaterialTheme.typography.titleSmall)
        Text(if (model.environment.isSample) "Push is unavailable in sample data." else when {
            !permitted -> "Android notifications are blocked. Your inbox still works."
            row == null || !row.enabled -> "Off for this workspace on this phone."
            row.leaseExpiresAt?.let { runCatching { !java.time.Instant.parse(it).isAfter(java.time.Instant.now()) }.getOrDefault(true) } == true -> "Registration lease expired. Unlock and retry setup."
            row.ready && !backgroundRead -> "Registered. Locked and cold-launch alerts need Refresh while locked below."
            else -> row.message
        }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("push.status"))
        if (!model.environment.isSample) {
            Text("Google-free UnifiedPush requires a distributor such as ntfy and a configured NStack server. Alerts contain no private message text. Unlock to register or renew; receiving push does not unlock your identity.", style = MaterialTheme.typography.bodySmall)
            Text("For alerts while locked or after Android closes the app, turn on Background → Refresh while locked. Lock now revokes that read permission and pauses those alerts until you unlock and enable it again. Without it, the inbox catches up when opened.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !permitted) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enable()
                }, modifier = Modifier.testTag("push.enable")) { Text(if (row?.enabled == true) "Retry setup" else "Set up push") }
                if (row?.enabled == true) TextButton(onClick = { model.act { push.disable(model, c.id, w.id); revision++ } }, modifier = Modifier.testTag("push.disable")) { Text("Turn off") }
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Android notification settings") }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
