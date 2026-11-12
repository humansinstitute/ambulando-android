package studio.otherstuff.ambulando.app.garmin

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import studio.otherstuff.ambulando.app.*

/** User-enabled listener only. This service never starts or restores a microphone. */
class WatchControlService : Service() {
    private var remote: GarminRemote? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AmbulandoApplication
        val cold = app.activeModel == null
        val model = app.model(AppEnvironment.select(this, intent), restoreInteractiveIdentity = false)
        if (cold) model.foreground(false)
        val control = model.garmin
        remote = control
        if (intent?.action == STOP) { control.disable(); stopSelf(); return START_NOT_STICKY }
        if (!control.restoreAllowed()) { control.listenerStopped(); stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Watch control", NotificationManager.IMPORTANCE_LOW))
            check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "Enable watch control notifications in Android settings." }
            val open = PendingIntent.getActivity(this, 3412, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 3413, Intent(this, WatchControlService::class.java).setAction(STOP)
                .putExtra(AppEnvironment.SAMPLE_EXTRA, model.environment.isSample), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Watch control enabled").setContentText("Record opens the headset recorder; Stop sends to saved conversation")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, "Stop watch control", stop).build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            control.listenerStarted()
            handler.removeCallbacksAndMessages(null)
            var wasEnabled = true
            val refresh = object : Runnable {
                override fun run() {
                    if (wasEnabled != control.enabled) {
                        wasEnabled = control.enabled
                        manager.notify(NOTIFICATION, NotificationCompat.Builder(this@WatchControlService, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(if (wasEnabled) "Watch control enabled" else "Watch control needs setup")
                            .setContentText(if (wasEnabled) "Record opens the headset recorder" else "Automatic recording is off. Open Ambulando Settings to enable again.")
                            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                            .addAction(0, "Stop watch control", stop).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                    }
                    handler.postDelayed(this, 1000)
                }
            }
            handler.postDelayed(refresh, 1000)
        } catch (e: Exception) {
            control.disable(e.message ?: "Watch listener could not start. Enable again in Settings.")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // Best effort only; no boot receiver and no automatic identity unlock.
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        remote?.listenerStopped()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "watch_control_v1"
        private const val NOTIFICATION = 3412
        private const val STOP = "studio.otherstuff.ambulando.watch.STOP"
    }
}
