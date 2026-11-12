package studio.otherstuff.ambulando.app.garmin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import studio.otherstuff.ambulando.app.*

/** Visible, opaque recording screen; the microphone starts only after this window has focus. */
class WatchRecordingActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var attempted = false
    private var manualRequested = false
    private var explanation by mutableStateOf<String?>(null)
    @Suppress("InvalidFragmentVersionForActivityResult")
    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.isNotEmpty() && result.values.all { it }) { manualRequested = true; window.decorView.post { startWhenVisible() } }
        else explanation = "Allow microphone, nearby devices and notifications in Android Settings, then retry Start."
    }
    private fun startWhenVisible() {
        if (!hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (manualRequested && !model.garmin.enabled) {
            manualRequested = false
            intent.getStringExtra("session")?.let { model.garmin.confirm(it, this, manual = true) }
            return
        }
        if (attempted) return
        attempted = true
        if (model.garmin.enabled) intent.getStringExtra("session")?.let { model.garmin.confirm(it, this) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        model = (application as AmbulandoApplication).model(AppEnvironment.select(applicationContext, intent))
        setContent {
            val draft by model.voice.state.collectAsState()
            val sessions = model.garmin.sessions
            val s = sessions.firstOrNull { it.session == intent.getStringExtra("session") }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("Ambulando Voice", style = MaterialTheme.typography.headlineMedium)
                Text(s?.destination?.label ?: "Request unavailable")
                Text(s?.status ?: "error", style = MaterialTheme.typography.headlineSmall)
                Text(s?.detail ?: "Return to Ambulando and retry")
                Text("Bluetooth headset only. Stop on watch sends to the saved conversation.")
                if (s?.status == "waiting" && draft == null) {
                    if (model.garmin.enabled) Text("Opening the headset microphone automatically. No phone tap is required.")
                    else Button(onClick = {
                        val required = buildList {
                            add(android.Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.Manifest.permission.BLUETOOTH_CONNECT)
                            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                        if (required.isEmpty()) { manualRequested = true; startWhenVisible() } else permissions.launch(required.toTypedArray())
                    }) { Text("Start headset recording") }
                    explanation?.let { Text(it) }
                    Button(onClick = { model.garmin.dismiss(s.session) }) { Text("Cancel request") }
                }
                if (s?.status in setOf("cancelled", "error", "sent")) Text("Try Record again on the watch. Any retained audio stays in its original conversation.")
                Button(onClick = { finish() }) { Text("Return to phone") }
            } } }
        }
    }
    override fun onResume() { super.onResume(); model.foreground(true); window.decorView.post { startWhenVisible() } }
    override fun onPause() { if (attempted && model.garmin.enabled) intent.getStringExtra("session")?.let { model.garmin.screenHidden(it) }; model.foreground(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startWhenVisible() else if (attempted && model.garmin.enabled) intent.getStringExtra("session")?.let { model.garmin.screenHidden(it) }
    }
}
