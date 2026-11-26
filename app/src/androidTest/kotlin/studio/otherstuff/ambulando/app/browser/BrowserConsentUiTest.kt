package studio.otherstuff.ambulando.app.browser

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.browser.*
import studio.otherstuff.ambulando.core.nostr.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserConsentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitChoicesNameTheOperationAndAccount() {
        val context = NIP07Context(UUID.randomUUID(), 1, "https://sample.example:8443")
        val own = NostrKeys.publicKeyHex(ByteArray(32) { 1 })
        val kinds = listOf(NIP07Prompt.Kind.Cipher(own, false), NIP07Prompt.Kind.Cipher(own, true),
            NIP07Prompt.Kind.Sign(NostrEventTemplate(10318, emptyList(), "fixture", 1)), NIP07Prompt.Kind.Connect)
        val prompt = mutableStateOf(NIP07Prompt(UUID.randomUUID(), context, kinds.first(), NostrKeys.npub(own)))
        var decision: NIP07Decision? = null
        compose.setContent { AmbulandoTheme { NostrApprovalSheet(prompt.value) { decision = it } } }
        for ((index, kind) in kinds.withIndex()) {
            compose.runOnIdle { prompt.value = NIP07Prompt(UUID.randomUUID(), context, kind, NostrKeys.npub(own)); decision = null }
            compose.onNodeWithText(context.origin).assertExists()
            compose.onNodeWithText("Allow once").assertExists()
            compose.onNodeWithText("Always allow for this site").assertExists()
            compose.onNodeWithText("Deny").assertExists()
            if (index == 0) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(instrumentation.targetContext.getExternalFilesDir(null), "browser-consent.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithTag("nostr.allow").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(NIP07Decision.Allow(false), decision); decision = null }
            compose.onNodeWithTag("nostr.always").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(NIP07Decision.Allow(true), decision); decision = null }
            compose.onNodeWithTag("nostr.deny").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(NIP07Decision.Deny(false), decision) }
        }
    }
}
