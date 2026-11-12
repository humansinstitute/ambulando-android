package studio.otherstuff.ambulando.app.garmin

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private var requestedStart = false
    private var explanation by mutableStateOf<String?>(null)
    @Suppress("InvalidFragmentVersionForActivityResult") // ComponentActivity; no Fragment integration.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.isNotEmpty() && result.values.all { it }) { explanation = null; requestedStart = true; window.decorView.post { startWhenVisible() } }
        else explanation = "Recording permission denied. Allow microphone, nearby devices and notifications in Android settings, then tap Start again."
    }
    private fun startWhenVisible() {
        if (!requestedStart || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        requestedStart = false
        intent.getStringExtra("session")?.let { model.garmin.confirm(it, this) }
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
                Text("Ambulando watch recorder", style = MaterialTheme.typography.headlineMedium)
                Text(s?.destination?.label ?: "Request unavailable")
                Text(s?.status ?: "error", style = MaterialTheme.typography.headlineSmall)
                Text(s?.detail ?: "Return to Ambulando and retry")
                Text("Bluetooth headset only. Finish on watch sends to the bound conversation.")
                explanation?.let { Text(it) }
                if (s?.status == "waiting" && draft == null) {
                    Text("Waiting for Start on this phone. Optional watch access is not required.")
                    Button(onClick = {
                        explanation = null
                        val required = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.filter { ContextCompat.checkSelfPermission(this@WatchRecordingActivity, it) != PackageManager.PERMISSION_GRANTED }
                        if (required.isEmpty()) { requestedStart = true; startWhenVisible() }
                        else permissions.launch(required.toTypedArray())
                    }) { Text("Start headset recording") }
                    Button(onClick = { model.garmin.dismiss(s.session) }) { Text("Cancel request") }
                }
                if (s?.status in setOf("cancelled", "error", "sent")) Text("Check Status on watch, then choose New to make a new request. Any retained audio stays in its original conversation.")
                Button(onClick = { finish() }) { Text("Return to phone") }
            } } }
        }
    }
    override fun onResume() { super.onResume(); model.foreground(true); window.decorView.post { startWhenVisible() } }
    override fun onPause() { model.foreground(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        startWhenVisible()
        if (!hasFocus || attempted || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        attempted = true
        val session = intent.getStringExtra("session") ?: return
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) return
        if (model.garmin.canOpenRecordingScreen()) model.garmin.confirm(session, this)
    }
}
