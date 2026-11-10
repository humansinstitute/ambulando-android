package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.onboarding.ImportIdentityScreen
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

@RunWith(RobolectricTestRunner::class)
class IdentityCreationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createRequiresMatchingPINExplainsRecoveryAndBlocksRepeatedTaps() {
        var calls = 0
        val finish = CompletableDeferred<Unit>()
        compose.setContent { AmbulandoTheme { ImportIdentityScreen(null, { _, _ -> error("Import called") }, {
            calls++; finish.await()
        }) } }
        compose.onNodeWithTag("identity.mode").performClick()
        compose.onNodeWithTag("create.recovery").assertTextContains("no key export or recovery", substring = true)
        compose.onNodeWithTag("import.nsec").assertDoesNotExist()
        compose.onNodeWithTag("import.submit").assertIsNotEnabled()
        compose.onNodeWithTag("import.pin").performTextInput("246810")
        compose.onNodeWithTag("import.confirm").performTextInput("246811")
        compose.onNodeWithTag("import.submit").assertIsNotEnabled()
        compose.onNodeWithTag("import.confirm").performTextReplacement("246810")
        compose.onNodeWithTag("import.submit").performScrollTo().performClick()
        compose.onNodeWithTag("import.submit").assertIsNotEnabled()
        compose.onNodeWithTag("identity.mode").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); finish.complete(Unit) }
    }

    @Test fun failureIsVisibleAndExistingImportRemainsAvailable() {
        compose.setContent { AmbulandoTheme { ImportIdentityScreen(null, { _, _ -> }, { throw java.io.IOException("private detail") }) } }
        compose.onNodeWithTag("identity.mode").performClick()
        compose.onNodeWithTag("import.pin").performTextInput("246810")
        compose.onNodeWithTag("import.confirm").performTextInput("246810")
        compose.onNodeWithTag("import.submit").performScrollTo().performClick()
        compose.onNodeWithText("Could not save your identity securely.", substring = true).assertExists()
        compose.onNodeWithText("private detail", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("identity.mode").performScrollTo().performClick()
        compose.onNodeWithTag("import.nsec").assertExists()
    }
}
