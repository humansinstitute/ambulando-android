package studio.otherstuff.ambulando.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileSaveUiTest {
    @get:Rule val compose = createComposeRule()
    private var loads = 0
    private val launches = mutableListOf<Intent>()
    private fun screen(result: ActivityResult, unavailable: Boolean = false) {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches += input as Intent
                if (unavailable) throw ActivityNotFoundException()
                dispatchResult(code, result)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val action = rememberFileSave("installer.apk", attachmentMimeType("installer.apk", null)) {
                    loads++; error("Authenticated retrieval failed")
                }
                Button(onClick = action.start, enabled = !action.busy) { Text("Save to Files") }
                action.message?.let { Text(it) }
            }
        }
    }
    @Test fun cancellingPickerDoesNotRetrieveOrOpenFileAndAllowsRetry() {
        screen(ActivityResult(Activity.RESULT_CANCELED, null))
        compose.onNodeWithText("Save to Files").performClick()
        compose.onNodeWithText("Save cancelled.").assertExists()
        assertEquals(0, loads)
        compose.onNodeWithText("Save to Files").performClick()
        assertEquals(2, launches.size)
        assertTrue(launches.all { it.action == Intent.ACTION_CREATE_DOCUMENT })
    }
    @Test fun missingPickerIsVisibleAndDoesNotDownload() {
        screen(ActivityResult(Activity.RESULT_CANCELED, null), unavailable = true)
        compose.onNodeWithText("Save to Files").performClick()
        compose.onNodeWithText("Save picker unavailable.").assertExists()
        assertEquals(0, loads)
    }
    @Test fun retrievalFailureIsVisibleAndDoesNotLaunchPreview() {
        screen(ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/document/output"))))
        compose.onNodeWithText("Save to Files").performClick()
        compose.waitUntil(10_000) { loads == 1 }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Could not save: Authenticated retrieval failed")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Could not save: Authenticated retrieval failed").assertExists()
        assertEquals(1, launches.size)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launches.single().action)
    }
}
