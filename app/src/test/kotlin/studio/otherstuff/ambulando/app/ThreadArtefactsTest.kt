package studio.otherstuff.ambulando.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.store.ChatTarget
import java.util.UUID

class ThreadArtefactsTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun reopeningSameRecordRetainsItsCallbacksAndPinnedRevision() {
        val state = ThreadArtefacts(); val id = id()
        state.open("file", id, "Board")
        val tab = state.tabs.single()
        tab.pinnedRevision = id(); tab.viewport = "{\"zoom\":2}"
        state.chatPosition = ChatPosition("message", 42, false)
        tab.open = false
        state.open("file", id)
        assertSame(tab, state.tabs.single())
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertEquals(tab.pinnedRevision, restored.tabs.single().pinnedRevision)
        assertEquals(tab.viewport, restored.tabs.single().viewport)
        assertEquals(id, restored.selected)
        assertEquals(state.chatPosition, restored.chatPosition)
    }
    @Test fun hiddenSelectedReferenceAndRemoveDetachStillSaveButFailuresPreventEnqueue() = runBlocking {
        val state = ThreadArtefacts(); val record = id()
        state.open("file", record, "Board"); state.shown = false
        val ref = ArtefactChatReference("file", record, id(), true, "a".repeat(64))
        var saves = 0
        val tab = state.tabs.single()
        tab.prepare = { saves++; ref }; tab.validate = { it == ref }
        val target = ChatTarget(id(), id(), id(), id())
        val captured = state.prepare(target)!!
        assertTrue(state.busy)
        assertTrue(captured.contains("\"workspaceVisible\":false"))
        state.busy = false; state.discuss = false
        assertNull(state.prepare(target)); assertEquals(2, saves)
        state.busy = false; state.discuss = true
        tab.validate = { false }
        assertTrue(runCatching { state.prepare(target) }.isFailure)
        assertFalse(state.busy)
        assertNotNull(state.failure)
        tab.prepare = { error("Offline: retry save") }
        assertTrue(runCatching { state.prepare(target) }.isFailure)
        assertEquals("Board", tab.title)
        assertTrue(tab.open)
    }
}
