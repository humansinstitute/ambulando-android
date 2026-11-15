package studio.otherstuff.ambulando.core.preferences

import org.junit.Assert.*
import org.junit.Test

class AttentionTypesTest {
    @Test fun defaultsCombinationsAndFutureTypes() {
        val all = AttentionTypes()
        assertTrue(AttentionTypes.TYPES.all(all::includes))
        val chats = AttentionTypes.TYPES.filter { it != "chat" }.fold(all) { p, k -> p.withEnabled(k, false) }
        assertEquals(listOf("chat"), AttentionTypes.TYPES.filter(chats::includes))
        val chatsAndDocs = chats.withEnabled("document", true)
        assertEquals(listOf("chat", "document"), AttentionTypes.TYPES.filter(chatsAndDocs::includes))
        val none = AttentionTypes.TYPES.fold(all) { p, k -> p.withEnabled(k, false) }
        assertFalse(AttentionTypes.TYPES.any(none::includes))
        assertTrue(none.includes("future-record-kind"))
        assertTrue(AttentionTypes.TYPES.fold(none) { p, k -> p.withEnabled(k, true) }.disabled.isEmpty())
    }
    @Test fun unknownFieldsAndDisabledNamesSurviveEditsButFutureSchemaIsReadOnly() {
        val p = AttentionTypes.decode("""{"version":1,"disabled":["future"],"future":{"x":true}}""")
        val changed = AttentionTypes.decode(p.withEnabled("task", false).encode())
        assertEquals(setOf("future", "task"), changed.disabled)
        assertEquals(p.raw["future"], changed.raw["future"])
        for (value in listOf("broken", "{}", """{"version":2,"disabled":["chat"]}""", """{"version":1,"disabled":[1]}""")) {
            val future = AttentionTypes.decode(value)
            assertFalse(future.supported)
            assertTrue(AttentionTypes.TYPES.all(future::includes))
            assertThrows(IllegalArgumentException::class.java) { future.withEnabled("task", false) }
        }
    }
    @Test fun targetKindsMapWithoutConfusingMentionCategoryWithRecordType() {
        for (kind in listOf("thread", "channel", "chat", "message")) assertEquals("chat", AttentionTypes.notificationType(kind))
        assertEquals("document", AttentionTypes.notificationType("doc"))
        assertEquals("other", AttentionTypes.notificationType("approval"))
        assertEquals("other", AttentionTypes.notificationType(null))
    }
    @Test fun everyCombinationFiltersOnlyAttentionSurfacesAndLeavesAllRecordNavigationAvailable() {
        for (mask in 0 until 32) {
            val p = AttentionTypes.TYPES.foldIndexed(AttentionTypes()) { index, prefs, type -> prefs.withEnabled(type, mask and (1 shl index) != 0) }
            AttentionTypes.TYPES.forEachIndexed { index, type ->
                val on = mask and (1 shl index) != 0
                val target = if (type == "chat") "thread" else type
                assertEquals(on, p.showsNotification("unread", null, target))
                assertEquals(on, p.showsNotification("inbox", "mention", target))
                assertEquals(on, p.showsNotification("inbox", "comment_tag", target))
                assertTrue(p.showsNotification("inbox", null, target))
                assertTrue(p.showsNotification("archived", null, target))
                assertEquals(on, p.showsActivity(true, type))
                assertTrue(p.showsActivity(false, type))
            }
        }
    }

}
