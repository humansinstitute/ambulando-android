package studio.otherstuff.ambulando.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDiscoveryEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedSectionsCannotRefreshUntilVisibleAndResumed() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var active by mutableStateOf(false)
        var revision by mutableIntStateOf(0)
        var starts = 0
        var cancellations = 0
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AppDiscoveryEffect(active,revision) {
                    assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    starts++
                    try { awaitCancellation() } finally { cancellations++ }
                }
            }
        }
        compose.runOnIdle { assertEquals(0,starts); active = true }
        compose.runOnIdle { assertEquals(1,starts); active = false }
        compose.runOnIdle { assertEquals(1,cancellations); revision++ }
        compose.runOnIdle { assertEquals(1,starts); owner.registry.currentState = Lifecycle.State.CREATED; active = true }
        compose.runOnIdle { assertEquals(1,starts); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2,starts); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(2,cancellations); revision++ }
        compose.runOnIdle { assertEquals(2,starts); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(3,starts); owner.registry.currentState = Lifecycle.State.STARTED }
        // A paused but still STARTED activity must cancel before ON_STOP arrives.
        compose.runOnIdle { assertEquals(3,cancellations); revision++ }
        compose.runOnIdle { assertEquals(3,starts); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(4,starts) }
    }
}
