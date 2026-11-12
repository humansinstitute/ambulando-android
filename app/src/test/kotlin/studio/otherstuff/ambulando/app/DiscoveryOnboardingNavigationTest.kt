package studio.otherstuff.ambulando.app

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.onboarding.DiscoveryOnboardingFrame
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

@RunWith(RobolectricTestRunner::class)
class DiscoveryOnboardingNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerIsBelowStatusInsetAndReturnsToChooser() {
        val manual = mutableStateOf(true)
        var topInset = 0f
        compose.setContent {
            topInset = with(LocalDensity.current) { 48.dp.toPx() }
            AmbulandoTheme {
                DiscoveryOnboardingFrame(manual.value,{ manual.value = false },WindowInsets(top = 48.dp,bottom = 24.dp)) {
                    Text(if (manual.value) "Manual entry" else "Choose a Tower",Modifier.testTag("route.content"))
                }
            }
        }
        assertTrue(compose.onNodeWithTag("discovery.manual.back").fetchSemanticsNode().boundsInRoot.top >= topInset)
        compose.onNodeWithTag("discovery.manual.back").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose a Tower").assertIsDisplayed()
        compose.onNodeWithTag("discovery.manual.back").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("route.content").fetchSemanticsNode().boundsInRoot.top >= topInset)
    }

    @Test fun systemBackOnActualHostDispatcherReturnsManualRouteToChooser() {
        val manual = mutableStateOf(true)
        lateinit var owner: OnBackPressedDispatcherOwner
        compose.setContent {
            // Activity 1.13 BackHandler uses the host NavigationEvent dispatcher, which its own Back dispatcher bridges.
            owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            AmbulandoTheme {
                DiscoveryOnboardingFrame(manual.value,{ manual.value = false },WindowInsets()) {
                    Text(if (manual.value) "Manual entry" else "Choose a Tower")
                }
            }
        }
        compose.runOnIdle { owner.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Choose a Tower").assertIsDisplayed()
        compose.onNodeWithTag("discovery.manual.back").assertDoesNotExist()
        compose.runOnIdle { assertFalse(manual.value) }
    }

    @Test fun containingScreenInsetsAreConsumedWithoutDoublingTheHeaderOffset() {
        var topInset = 0f
        compose.setContent {
            topInset = with(LocalDensity.current) { 48.dp.toPx() }
            val insets = WindowInsets(top = 48.dp)
            AmbulandoTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                    DiscoveryOnboardingFrame(true,{},insets) { Text("Manual entry") }
                }
            }
        }
        assertEquals(topInset,compose.onNodeWithTag("discovery.manual.back").fetchSemanticsNode().boundsInRoot.top,1f)
    }
}
