package studio.otherstuff.ambulando.core.notifications

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.support.NotificationFixtures
import java.time.Instant

class PushHintTest {
    private val workspace = "00000000-0000-4000-8000-000000000001"
    private val item = "00000000-0000-4000-8000-000000000002"
    private val body = """{"fd":{"v":1,"origin":"https://tower.example","workspace_id":"$workspace","notification_id":"$item","change_seq":42,"tower_service_npub":"pinned","task_id":"untrusted-ignored","title":"private-ignored"}}"""
    @Test fun matchesOnlyStoredOriginServiceAndWorkspace() {
        val hint = PushHint.parse(body.toByteArray())!!
        val c = ConnectionRecord("saved", "https://tower.example", "actor", "pinned", "now")
        assertTrue(hint.matches(c, workspace))
        assertFalse(hint.matches(c.copy(origin = "https://evil.example"), workspace))
        assertFalse(hint.matches(c.copy(towerNpub = "other"), workspace))
        assertFalse(hint.matches(c, item))
        assertEquals(item, hint.notificationId)
    }
    @Test fun rejectsInvalidUrlsVersionsIdsAndSequencesWithoutThrowing() {
        for (origin in listOf("http://tower.example", "https://user@tower.example", "https://tower.example/path", "https://tower.example?url=x", "https://tower.example#x", "javascript:alert(1)")) {
            assertNull(origin, PushHint.parse(body.replace("https://tower.example", origin).toByteArray()))
        }
        assertNull(PushHint.parse(body.replace("\"v\":1", "\"v\":2").toByteArray()))
        assertNull(PushHint.parse(body.replace(workspace, "../../escape").toByteArray()))
        assertNull(PushHint.parse(body.replace("\"change_seq\":42", "\"change_seq\":0").toByteArray()))
        assertNull(PushHint.parse(ByteArray(4096)))
        assertNull(PushHint.parse(byteArrayOf(0xff.toByte())))
    }
    @Test fun freshReadSuppressesTombstonesReadItemsLostAccessExpiredItemsAndWrongWorkspace() {
        val item = NotificationFixtures.item(1, 42)
        val hint = PushHint("https://tower.example", null, item.workspaceId, item.id, 42)
        assertTrue(PushValidation.presentable(hint, item))
        assertFalse(PushValidation.presentable(hint, null))
        assertFalse(PushValidation.presentable(hint, item.copy(accessible = false)))
        assertFalse(PushValidation.presentable(hint, item.copy(status = "revoked")))
        assertFalse(PushValidation.presentable(hint, item.copy(workspaceId = this.item)))
        assertFalse(PushValidation.presentable(hint, item.copy(changeSeq = 41)))
        assertFalse(PushValidation.presentable(hint, item.copy(state = item.state.copy(read = true))))
        assertFalse(PushValidation.presentable(hint, item.copy(expiresAt = "2020-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")))
        assertFalse(PushValidation.presentable(hint, item.copy(expiresAt = "invalid")))
    }
}
