package studio.otherstuff.ambulando.app.fips

import android.app.PendingIntent
import android.net.Uri

/** Process-only capability. Never persisted, logged, or launched by a transport/background caller. */
internal data class ConsentProvider(val uri: Uri, val packageName: String, val certificate: String, val uid: Int, val callerUid: Int)
internal class PendingConsent(
    val provider: ConsentProvider,
    val endpoint: String,
    val requestId: String,
    val intent: PendingIntent,
) {
    private var handedOff = false
    @Synchronized fun take(current: ConsentProvider, origin: String, id: String, immutable: Boolean): PendingIntent {
        check(current == provider && origin == endpoint && id == requestId && id.isNotBlank()) { "Consent binding changed" }
        check(intent.creatorPackage == current.packageName && intent.creatorUid == current.uid && immutable) { "Untrusted consent" }
        check(!handedOff) { "Consent already handed off" }
        handedOff = true
        return intent
    }
}
