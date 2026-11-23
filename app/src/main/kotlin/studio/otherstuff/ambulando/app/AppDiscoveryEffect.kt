package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retained section composition is not visibility. Refresh once per visible foreground activation. */
@Composable
internal fun AppDiscoveryEffect(active: Boolean, vararg keys: Any?, refresh: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle, *keys) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // repeatOnLifecycle launches in its caller scope; retained screen scopes may use a worker dispatcher.
            withContext(Dispatchers.Main.immediate) { refresh() }
        }
    }
}
