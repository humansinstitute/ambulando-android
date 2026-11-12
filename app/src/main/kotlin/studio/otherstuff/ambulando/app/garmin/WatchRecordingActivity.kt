package studio.otherstuff.ambulando.app.garmin

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import studio.otherstuff.ambulando.app.*

/** Visible, opaque recording screen; the microphone starts only after this window has focus. */
class WatchRecordingActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var attempted = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        model = (application as AmbulandoApplication).model(AppEnvironment.select(applicationContext, intent))
        setContent {
            val sessions = model.garmin.sessions
            val s = sessions.firstOrNull { it.session == intent.getStringExtra("session") }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("Ambulando watch recorder", style = MaterialTheme.typography.headlineMedium)
                Text(s?.destination?.label ?: "Request unavailable")
                Text(s?.status ?: "error", style = MaterialTheme.typography.headlineSmall)
                Text(s?.detail ?: "Return to Ambulando and retry")
                Text("Bluetooth headset only. Finish on watch sends to the bound conversation.")
                Button(onClick = { finish() }) { Text("Return to phone") }
            } } }
        }
    }
    override fun onResume() { super.onResume(); model.foreground(true) }
    override fun onPause() { model.foreground(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || attempted || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        attempted = true
        val session = intent.getStringExtra("session") ?: return
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) return
        if (model.garmin.canOpenRecordingScreen()) model.garmin.confirm(session)
    }
}
