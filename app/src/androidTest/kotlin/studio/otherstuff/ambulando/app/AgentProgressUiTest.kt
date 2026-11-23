package studio.otherstuff.ambulando.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import java.time.Instant

/** Synthetic public producer updates; no member identity, network or private device state. */
@RunWith(AndroidJUnit4::class)
class AgentProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun row(state: String = "working") = AgentActivityRecord.parse(Json.parseToJsonElement("""{
        "id":"$id","workspace_id":"00000000-0000-4000-8000-000000000002",
        "channel_id":"00000000-0000-4000-8000-000000000003","thread_id":"00000000-0000-4000-8000-000000000004",
        "activity_id":"activity","turn_id":"turn","visibility":"user_visible","sequence":4,"state":"$state",
        "lease_expires_at":"2026-10-01T00:03:00Z","commentary_next_before_sequence":2,
        "commentary_history":[
            {"turn_id":"turn","activity_id":"activity","sequence":2,"body":"First public update"},
            {"turn_id":"turn","activity_id":"activity","sequence":4,"body":"Latest public update"},
            {"turn_id":"foreign","activity_id":"activity","sequence":5,"body":"Unrelated update"}
        ]
    }""").let { it as kotlinx.serialization.json.JsonObject })

    @Test fun queuedAcceptedWaitingLeaseExpiryAndFailureUseCanonicalState() {
        val activity = mutableStateOf(row("queued"))
        val now = mutableStateOf(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())
        compose.setContent { MaterialTheme { AgentProgressCard(activity.value, now.value, true) {} } }
        compose.onNodeWithText("Agent · Queued").assertIsDisplayed()
        compose.runOnIdle { activity.value = row("accepted") }
        compose.onNodeWithText("Agent · Message received").assertIsDisplayed()
        compose.runOnIdle { activity.value = row("waiting") }
        compose.onNodeWithText("Agent · Waiting for you").assertIsDisplayed()
        compose.runOnIdle { now.value += 196_000 }
        compose.onNodeWithText("Agent · Stopped responding").assertIsDisplayed()
        compose.runOnIdle { activity.value = row("failed") }
        compose.onNodeWithText("Agent · Failed").assertIsDisplayed()
    }

    @Test fun workingLatestExpandOrderedHistoryCompleteAndReopen() {
        val activity = mutableStateOf(row())
        val visible = mutableStateOf(true)
        var loads = 0
        compose.setContent { MaterialTheme {
            if (visible.value) AgentProgressCard(activity.value, Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), true) { loads++ }
        } }
        compose.onNodeWithText("Agent · Working").assertIsDisplayed()
        compose.onNodeWithText("Latest public update").assertIsDisplayed()
        compose.onNodeWithText("First public update").assertDoesNotExist()
        compose.onNodeWithText("Unrelated update").assertDoesNotExist()
        compose.onNodeWithTag("chat.agent.history.$id").performClick()
        compose.onNodeWithTag("chat.agent.update.$id.2").assertIsDisplayed()
        compose.onNodeWithText("Load earlier updates").performClick()
        compose.runOnIdle { assertEquals(1, loads); activity.value = row("completed") }
        compose.onNodeWithText("Agent · Completed").assertIsDisplayed()
        compose.onNodeWithTag("chat.agent.update.$id.2").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("Agent · Completed").assertIsDisplayed()
        compose.onNodeWithText("Latest public update").assertIsDisplayed()
        compose.onNodeWithTag("chat.agent.history.$id").performClick()
        compose.onNodeWithTag("chat.agent.update.$id.2").assertIsDisplayed()
    }
}
