package studio.otherstuff.ambulando.app

import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.rich.InlineText
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.app.ui.theme.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.docs.MarkdownBlocks
import studio.otherstuff.ambulando.core.docs.RenderBlock
import studio.otherstuff.ambulando.core.transport.FileRecord
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ThreadFileMentionRoutingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun genericFilePillUsesAuthorizedNativeReaderWithoutAttachingOrClaimingContext() {
        val id = UUID.randomUUID().toString(); val w = UUID.randomUUID().toString(); val channel = UUID.randomUUID().toString()
        val file = FileRecord(w, id, null, channel, null, "object", "Source plan.pdf", contentType = "application/pdf", sizeBytes = 1, sha256 = null, createdAt = "2026-11-26", updatedAt = "2026-11-26")
        val state = ThreadArtefacts()
        state.reconcile(ThreadArtefactMembershipSync.Row(1, ThreadArtefactMembership.metadata(JsonObject(emptyMap()), emptyList())))
        var opened: Pair<String, String>? = null; var resolutions = 0
        state.requestOpen = { _, _, _ -> fail("A generic File must not become an editor attachment") }
        compose.setContent {
            val scope = rememberCoroutineScope()
            AmbulandoTheme {
                val wire = "Open @[Source plan](mention:file:$id)"
                Text(InlineText.annotated((MarkdownBlocks.parse(wire).single() as RenderBlock.Paragraph).runs, Amb.colors,
                    RichActions(openMention = { kind, record -> scope.launch {
                        routeThreadReference(kind, record, state, file = { source -> assertEquals(id, source); resolutions++; file }, native = { k, nativeId -> opened = k to nativeId })
                    } })))
            }
        }
        compose.onNodeWithText("Open Source plan").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals("file" to id, opened); assertEquals(1, resolutions)
            assertTrue(state.members.isEmpty()); assertTrue(state.tabs.isEmpty()); assertFalse(state.discuss)
        }
    }
}
