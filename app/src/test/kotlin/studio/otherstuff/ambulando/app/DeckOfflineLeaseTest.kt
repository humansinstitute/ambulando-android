package studio.otherstuff.ambulando.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.ui.deck.DeckInboxSnapshot
import studio.otherstuff.ambulando.core.deck.DeckActivity
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import java.time.Instant

class DeckOfflineLeaseTest {
    @Test fun cachedOfflineSnapshotExpiresWithoutAnotherStoreRead() {
        val thread = "00000000-0000-4000-8000-000000000003"
        val activity = AgentActivityRecord.parse(Json.parseToJsonElement("""{
            "id":"00000000-0000-4000-8000-000000000001","workspace_id":"00000000-0000-4000-8000-000000000002",
            "channel_id":"00000000-0000-4000-8000-000000000004","thread_id":"$thread",
            "activity_id":"synthetic","visibility":"user_visible","sequence":1,"state":"working",
            "lease_expires_at":"2026-10-01T00:00:01Z"
        }""").jsonObject)
        val item = DeckActivity.Item(DeckActivity.Kind.CHAT, thread, activity.channelId, null, "Synthetic", "", "", "", false, true, null, searchText = "")
        val cached = DeckInboxSnapshot(items = listOf(item), activities = listOf(activity))
        val now = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        assertTrue(cached.itemsAt(now).single().isWorking)
        // Same cached object, no database or network refresh, at deadline plus web's grace.
        assertFalse(cached.itemsAt(now + 16_000).single().isWorking)
        assertFalse(cached.itemsAt(now + 600_000).single().isWorking)
        assertTrue(cached.items.single().isWorking)
    }
}
