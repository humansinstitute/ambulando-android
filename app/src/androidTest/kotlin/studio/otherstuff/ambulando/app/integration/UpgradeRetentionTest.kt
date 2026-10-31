package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/**
 * Run by a driver after the previous build (single database) left a draft and an unsent message in the sample data and this
 * build was installed over it. Skipped unless the instrumentation argument `upgrade` is `verify`.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeRetentionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun draftAndUnsentMessageOfThePreviousBuildSurviveAndSendOnce() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("upgrade") == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            val model = app.activeModel!!
            // The workspace the old build remembered is enabled and in front; the one with nothing pending stays paused.
            compose.waitUntil(30_000) { model.activeEntry?.workspace?.id == SampleIds.STUDIO && model.active != null }
            val studio = model.activeEntry!!
            assertEquals(setOf(studio.id), model.enabledEntries.map { it.id }.toSet())
            val c = studio.connection
            assertEquals("draft from build16", runBlocking { studio.runtime!!.store.reader.draft(ChatTarget(c.id, SampleIds.STUDIO, SampleIds.GENERAL)) }?.text)
            // The unsent message was imported unchanged, and is sent exactly once after the workspace is revalidated.
            compose.waitUntil(30_000) { studio.runtime?.canSend == true }
            compose.waitUntil(30_000) { runBlocking { studio.runtime!!.store.reader.outbox(c.id, c.signerNpub) }.isEmpty() }
            assertEquals(1, model.sampleTower!!.messageBodies(SampleIds.STUDIO).count { it == "queued by build16" })
        }
    }
}
