package studio.otherstuff.ambulando.app

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FamilyIdentityTest {
    @Test fun independentPackageAndProductionStorageIgnoreSampleAndRoutingExtras() {
        assertTrue(BuildConfig.GRANDPARENT_CHAT)
        assertEquals("studio.otherstuff.grandparentchat.android", BuildConfig.APPLICATION_ID)
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://tower.invalid/another-thread"))
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .putExtra("thread_id", "another-thread")
        val environment = AppEnvironment.select(context, intent, instrumentationFlag = true)
        assertFalse(environment.isSample)
        assertEquals(context.noBackupFilesDir, environment.baseDir)
        assertEquals(".grandparent", environment.aliasSuffix)
        assertTrue(environment.restoresNavigation(intent))
    }
}
