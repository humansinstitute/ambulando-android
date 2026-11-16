package studio.otherstuff.ambulando.app.fips

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [31])
class PendingConsentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun intent() = PendingIntent.getActivity(context, 50, Intent("test.consent"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    private fun provider(pi: PendingIntent) = ConsentProvider(Uri.parse("content://test.provider"), pi.creatorPackage!!, "current-cert", pi.creatorUid, android.os.Process.myUid())
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun backgroundFirstRetainsOriginalCapabilityUntilExplicitSingleHandoff() {
        val pi = intent(); val p = provider(pi)
        val cached = PendingConsent(p, "exact-origin", "request-1", pi)
        val background = FipsConsentRequired({ cached.take(p, "exact-origin", "request-1", pi.isImmutable) }, {})
        // Catching in a background validation path does not consume or launch the capability.
        assertNotNull(background.connectionIssue)
        val explicit = FipsConsentRequired({ cached.take(p, "exact-origin", "request-1", pi.isImmutable) }, {})
        assertSame(pi, explicit.takeConsent())
        rejected { explicit.takeConsent() }
    }
    @Test fun changedTrustEndpointRequestOrCallerCannotConsumeOriginal() {
        val pi = intent(); val p = provider(pi)
        val cached = PendingConsent(p, "origin", "id", pi)
        listOf(p.copy(uid = p.uid + 1), p.copy(packageName = "other"), p.copy(certificate = "other"),
            p.copy(uri = Uri.parse("content://other")), p.copy(callerUid = p.callerUid + 1)).forEach {
            rejected { cached.take(it, "origin", "id", true) }
        }
        rejected { cached.take(p, "other-origin", "id", true) }
        rejected { cached.take(p, "origin", "other-id", true) }
        rejected { cached.take(p, "origin", "id", false) }
        assertSame(pi, cached.take(p, "origin", "id", true))
    }
    @Test fun forgedCreatorUidAndPackageAreRejectedEvenWhenSnapshotMatches() {
        val pi = intent(); val p = provider(pi)
        for (bad in listOf(p.copy(uid = p.uid + 1), p.copy(packageName = "other"))) {
            rejected { PendingConsent(bad, "origin", "id", pi).take(bad, "origin", "id", true) }
        }
    }
    @Test fun canceledOneShotNeedsFreshCapabilityAndNeverLaunchesItself() {
        val pi = intent(); val p = provider(pi); val cached = PendingConsent(p, "origin", "id", pi)
        cached.take(p, "origin", "id", true).cancel()
        rejected { cached.take(p, "origin", "id", true) }
        val fresh = intent()
        assertSame(fresh, PendingConsent(provider(fresh), "origin", "fresh-id", fresh).take(provider(fresh), "origin", "fresh-id", true))
    }
}
