package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import studio.otherstuff.ambulando.app.ui.onboarding.ImportIdentityScreen
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.identitybackup.BackupFailure

/** Identity entry screen: creating states the backup requirement honestly; recovery needs address, username, password and PIN. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdentityRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val recovered = ArrayList<List<String>>()

    private fun show(fail: Boolean = false) = compose.setContent {
        AmbulandoTheme {
            ImportIdentityScreen(null, onImport = { _, _ -> }, onCreate = { },
                onRecover = { a, u, p, pin -> recovered.add(listOf(a, u, p, pin)); if (fail) throw BackupFailure(BackupFailure.Kind.WRONG_PASSWORD) })
        }
    }

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun type(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performTextInput(text) }

    @Test fun creatingExplainsTheMandatoryBackupAndNoResetBeforeAnythingIsMade() {
        show(); tap("identity.mode")
        compose.onNodeWithTag("create.recovery").assertExists()
        compose.onNodeWithText("no password reset", substring = true).assertExists()
        compose.onNodeWithText("back it up to NStack", substring = true).assertExists()
    }

    @Test fun recoveryNeedsEveryFieldAndPassesThemOnWithoutKeepingThePassword() {
        show(); tap("identity.recover.mode")
        compose.onNodeWithTag("import.submit").assertIsNotEnabled()
        type("recover.address", "https://nstack.example.com"); type("recover.username", "alice"); type("recover.password", "correct horse battery staple 42")
        type("import.pin", "135790"); type("import.confirm", "135790")
        compose.onNodeWithTag("import.submit").assertIsEnabled()
        tap("import.submit")
        compose.waitUntil(10_000) { recovered.isNotEmpty() }
        assertEquals(listOf("https://nstack.example.com", "alice", "correct horse battery staple 42", "135790"), recovered.single())
        // After the attempt the password is gone from the field.
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("import.submit").assertIsNotEnabled(); true }.getOrDefault(false) }
    }

    @Test fun aWrongPasswordIsAFriendlyErrorAndLeavesTheScreenUsable() {
        show(fail = true); tap("identity.recover.mode")
        type("recover.address", "https://nstack.example.com"); type("recover.username", "alice"); type("recover.password", "nope")
        type("import.pin", "135790"); type("import.confirm", "135790")
        tap("import.submit")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Wrong password, or the backup was altered.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasTestTag("recover.address")).fetchSemanticsNodes().isNotEmpty())
    }
}
