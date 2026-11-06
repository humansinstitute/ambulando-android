package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the dedicated emulator's empty production install. No identity or sample mode. */
@RunWith(AndroidJUnit4::class)
class FamilyOnboardingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun familyIdentityAndAccessibleSignInAreVisibleWithoutAmbulandoNavigation() {
        compose.onNodeWithText("Grandparent Chat").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithTag("import.nsec").performClick()
        compose.onNodeWithText("Tasks").assertDoesNotExist()
        compose.onNodeWithText("Deck").assertDoesNotExist()
        val context = compose.activity.applicationContext
        assertEquals("studio.otherstuff.grandparentchat.android", context.packageName)
        assertEquals("Grandparent Chat", context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
