package studio.otherstuff.ambulando.app.integration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry

/** Drives real system permission screens on the isolated emulator package. No shell grants/appops/CDM. */
internal object WatchNativeConsent {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
    fun waitFor(label: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) { if (condition()) return; Thread.sleep(150) }
        error("Native consent UI: $label; ${tree()}")
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    fun tree(): String = nodes(instrumentation.uiAutomation.rootInActiveWindow).joinToString(" | ") { "${it.viewIdResourceName}:${it.text}:${it.contentDescription}" }
    fun click(vararg labels: String): Boolean {
        val node = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { n -> labels.any { n.text?.toString()?.equals(it, true) == true || n.viewIdResourceName?.endsWith(it) == true } } ?: return false
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    fun grant(activity: ComponentActivity) {
        check(context.packageName.endsWith(".watchtest")) { "Native consent tests require isolatedWatchTest=true" }
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.runOnMainSync { activity.requestPermissions(permissions.toTypedArray(), 917) }
        waitFor("ordinary runtime grants") {
            if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) true
            else { click("While using the app", "Allow", "permission_allow_foreground_only_button", "permission_allow_button"); false }
        }
    }
    fun overlay(activity: ComponentActivity, allow: Boolean) {
        if (Settings.canDrawOverlays(context) == allow) return
        instrumentation.runOnMainSync { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
        waitFor("overlay screen switch") {
            if (Settings.canDrawOverlays(context) == allow) true
            else { if (!click("Allow display over other apps", "switch_widget", "android:id/switch_widget")) click("Ambulando"); false }
        }
        repeat(3) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings") {
                shell("input keyevent KEYCODE_BACK")
                Thread.sleep(400)
            }
        }
        waitFor("return to foreground setup") { activity.hasWindowFocus() && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        Thread.sleep(300)
    }
    fun blockWatchChannel(activity: ComponentActivity) {
        instrumentation.runOnMainSync {
            activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, studio.otherstuff.ambulando.app.garmin.WatchControlService.CHANNEL))
        }
        waitFor("native watch notification channel block") {
            if (context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(studio.otherstuff.ambulando.app.garmin.WatchControlService.CHANNEL)?.importance == android.app.NotificationManager.IMPORTANCE_NONE) true
            else { click("Show notifications"); false }
        }
        repeat(3) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings") {
                shell("input keyevent KEYCODE_BACK"); Thread.sleep(300)
            }
        }
    }

}
