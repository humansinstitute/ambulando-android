package studio.otherstuff.ambulando.core.chat

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.tempDir

class TextEditStoreSafetyTest {
    private fun base() = TextEdit("edit-one", "connection-one", backendOrigin = "https://one.example", workspaceId = "workspace",
        channelId = "channel", appNpub = "app", originMessageId = "message", originObjectId = "object", originFilename = "Original.txt",
        filename = "Mine.txt", staged = "", sha256 = "", sizeBytes = 0, info = TextAttachmentInfo.of("", "edit"))

    @Test fun failedReplacementKeepsThePreviousExactBytesAfterReopen() {
        val root = tempDir()
        val firstStore = TextEditStore(root)
        val original = "\uFEFFold\r\n😀\r\n"
        val previous = firstStore.create(base(), original)
        val broken = TextEditStore(root) { throw IOException("record commit failed") }
        try { broken.create(previous, "new\r\nbytes"); fail("failure expected") } catch (_: IOException) { }
        val reopened = TextEditStore(root)
        val restored = reopened.get(previous.id)!!
        assertEquals(previous.staged, restored.staged)
        assertEquals(previous.sha256, restored.sha256)
        assertArrayEquals(TextAttachments.bytes(original), reopened.bytes(restored))
        assertEquals(original, reopened.text(restored))
    }

    @Test fun callbacksCannotResurrectRemovedOrReplaceNewerRevision() {
        val store = TextEditStore(tempDir())
        val old = store.create(base(), "old")
        val newer = store.create(old, "new")
        try { store.update(old.copy(uploaded = true)); fail("stale callback accepted") } catch (_: IllegalStateException) { }
        assertEquals(newer.staged, store.get(old.id)!!.staged)
        store.remove(old.id)
        try { store.update(newer.copy(uploaded = true)); fail("removed edit resurrected") } catch (_: IllegalStateException) { }
        assertNull(store.get(old.id))
    }

    @Test fun recoveryRequiresConnectionAndBackendIdentity() {
        val store = TextEditStore(tempDir())
        val edit = store.create(base(), "private")
        assertNotNull(store.forAttachment(edit.connectionId, edit.backendOrigin, edit.workspaceId, edit.originMessageId, edit.originObjectId))
        assertNull(store.forAttachment("other", edit.backendOrigin, edit.workspaceId, edit.originMessageId, edit.originObjectId))
        assertNull(store.forAttachment(edit.connectionId, "https://two.example", edit.workspaceId, edit.originMessageId, edit.originObjectId))
    }

    @Test fun identicalRemoteIdsDoNotShareCacheBytesOrHistoryAcrossConnections() {
        val root = tempDir()
        val key = TextCache.scopeKey("one", "https://one.example", "alice", "workspace")
        val cache = TextCache(File(root, key))
        cache.put("same-object", "private".toByteArray())
        cache.putNote("same-history", "private history")
        listOf(
            TextCache.scopeKey("two", "https://one.example", "alice", "workspace"),
            TextCache.scopeKey("one", "https://two.example", "alice", "workspace"),
            TextCache.scopeKey("one", "https://one.example", "bob", "workspace"),
            TextCache.scopeKey("one", "https://one.example", "alice", "other-workspace"),
        ).forEach { other ->
            assertNotEquals(key, other)
            assertNull(TextCache(File(root, other)).get("same-object", null))
            assertNull(TextCache(File(root, other)).getNote("same-history"))
        }
        assertEquals("private", String(TextCache(File(root, key)).get("same-object", null)!!))
    }

    @Test fun maliciousPathsCannotEscapeTheStore() {
        val store = TextEditStore(tempDir())
        try { store.create(base().copy(id = "../outside"), "private"); fail("unsafe id accepted") } catch (_: IllegalArgumentException) { }
    }
}
