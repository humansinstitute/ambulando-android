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
    @Test fun closedCacheDoesNotCrowdOutOpenReferencesAndOpenLimitFailsBeforeAdding() {
        val state = ThreadArtefacts()
        repeat(40) { state.open("doc", id()); state.tabs.last().open = false }
        val open = id(); state.open("file", open)
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertTrue(restored.tabs.any { it.id == open && it.open })
        assertEquals(open, restored.selected)
        repeat(31) { restored.open("doc", id()) }
        val count = restored.tabs.size
        assertTrue(runCatching { restored.open("doc", id()) }.isFailure)
        assertEquals(count, restored.tabs.size)
    }
    @Test fun remoteRemovalRetainsRecoveryAndPinButExcludesFutureSendAndNeverReattachesCache() = runBlocking {
        val state = ThreadArtefacts(); val record = id()
        state.open("doc", record, "Draft")
        val tab = state.tabs.single(); tab.pinnedRevision = "1"; tab.viewport = "zoom"
        var saves = 0; tab.prepare = { saves++; error("must not publish") }
        state.reconcile(ThreadArtefactMembershipSync.Row(2, ThreadArtefactMembership.metadata(kotlinx.serialization.json.JsonObject(emptyMap()), emptyList())))
        assertNull(state.prepare(ChatTarget(id(), id(), id(), id())))
        assertEquals(0, saves); assertTrue(tab.open); assertFalse(tab.attached)
        assertEquals("1", tab.pinnedRevision); assertEquals("zoom", tab.viewport)
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertFalse(restored.tabs.single().attached)
        assertTrue(restored.members.isEmpty())
    }
    @Test fun freshPhoneMembershipIsLazyAndUnreadableUnopenedMembersDoNotBlockPlainSend() = runBlocking {
        val state = ThreadArtefacts(); val doc = ThreadArtefactMember("doc", id()); val privateFile = ThreadArtefactMember("file", id())
        state.reconcile(ThreadArtefactMembershipSync.Row(3, ThreadArtefactMembership.metadata(kotlinx.serialization.json.JsonObject(emptyMap()), listOf(doc, privateFile))))
        assertEquals(2, state.members.size); assertTrue(state.tabs.none { it.open })
        state.refreshMembership = { error("ordinary chat needs no source/context fetch") }
        assertNull(state.prepare(ChatTarget(id(), id(), id(), id())))
    }
    @Test fun removalObservedDuringPreparationCannotEnterNewFrozenMessage() = runBlocking {
        val state = ThreadArtefacts(); val record = id(); state.open("file", record)
        val tab = state.tabs.single()
        val ref = ArtefactChatReference("file", record, id(), false, "b".repeat(64))
        tab.prepare = {
            state.reconcile(ThreadArtefactMembershipSync.Row(3, ThreadArtefactMembership.metadata(kotlinx.serialization.json.JsonObject(emptyMap()), emptyList())))
            ref
        }
        tab.validate = { true }
        assertTrue(runCatching { state.prepare(ChatTarget(id(), id(), id(), id())) }.isFailure)
        assertFalse(state.busy); assertFalse(tab.attached); assertNotNull(state.failure)
    }
    @Test fun dirtyDetachedRecoveryRemainsHeldAfterReattachUntilAcknowledgedExplicitSourceSave() = runBlocking {
        val state = ThreadArtefacts(); val record = id(); state.open("doc", record)
        val tab = state.tabs.single(); tab.sourceDirty = { true }
        val metadata = kotlinx.serialization.json.JsonObject(emptyMap())
        state.reconcile(ThreadArtefactMembershipSync.Row(2, ThreadArtefactMembership.metadata(metadata, emptyList())))
        state.reconcile(ThreadArtefactMembershipSync.Row(3, ThreadArtefactMembership.metadata(metadata, listOf(ThreadArtefactMember("doc", record)))))
        assertTrue(tab.attached); assertTrue(tab.publicationHeld); assertFalse(tab.automaticPublicationAllowed)
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertTrue(restored.tabs.single().publicationHeld)
        var saves = 0
        tab.prepare = { saves++; ArtefactChatReference("doc", record, "1", false, "c".repeat(64)) }; tab.validate = { true }
        val target = ChatTarget(id(), id(), id(), id())
        assertTrue(runCatching { state.prepare(target) }.isFailure); assertEquals(0, saves)
        tab.saveSource = { tab.sourceSaved(); true }
        assertTrue(tab.saveSource!!.invoke()); assertFalse(tab.publicationHeld)
        assertNotNull(state.prepare(target)); assertEquals(1, saves)
    }
    @Test fun moreThan128ClosedRowsNeverDropClosedAttachedPinOrDetachedRecoveryHold() {
        val state = ThreadArtefacts()
        repeat(140) { state.open("doc", id()); state.tabs.last().also { it.open = false; it.attached = false } }
        val pin = id(); state.open("file", pin); state.tabs.last().also { it.pinnedRevision = id(); it.open = false; it.attached = true }
        val held = id(); state.open("doc", held); state.tabs.last().also { it.recoveryHeld = true; it.attached = false; it.open = false }
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertEquals(142, restored.tabs.size)
        assertNotNull(restored.tabs.first { it.id == pin }.pinnedRevision)
        assertTrue(restored.tabs.first { it.id == pin }.attached)
        assertTrue(restored.tabs.first { it.id == held }.recoveryHeld)
        assertFalse(restored.tabs.first { it.id == held }.automaticPublicationAllowed)
        assertEquals(0, restored.tabs.count { it.open })
    }
    @Test fun legacyMigrationIsOneTimeEvenIfAnotherWriterLaterDropsTheMetadataKey() {
        val state = ThreadArtefacts(); val record = id(); state.open("doc", record)
        assertEquals(1, state.legacyMigrationRefs.size)
        state.reconcile(ThreadArtefactMembershipSync.Row(2, ThreadArtefactMembership.metadata(kotlinx.serialization.json.JsonObject(emptyMap()), listOf(ThreadArtefactMember("doc", record)))))
        val restored = ThreadArtefacts().also { it.restore(state.snapshot()) }
        assertTrue(restored.membershipObserved)
        assertTrue(restored.legacyMigrationRefs.isEmpty())
        restored.reconcile(ThreadArtefactMembershipSync.Row(3, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(restored.members.isEmpty()); assertTrue(restored.legacyMigrationRefs.isEmpty())
        assertFalse(restored.tabs.single().attached)
    }
    @Test fun offlineReopenRetainsLastAcknowledgedMembershipWithoutClaimingFreshSync() {
        val record = id(); val metadata = ThreadArtefactMembership.metadata(kotlinx.serialization.json.JsonObject(emptyMap()), listOf(ThreadArtefactMember("doc", record)))
        val state = ThreadArtefacts().also { it.restore(null, ThreadArtefactMembershipSync.Row(4, metadata)) }
        assertEquals(listOf(record), state.members.map { it.id })
        assertFalse(state.membershipReady); assertTrue(state.membershipObserved)
        assertTrue(state.tabs.none { it.open }); assertTrue(state.legacyMigrationRefs.isEmpty())
        state.reconcile(ThreadArtefactMembershipSync.Row(5, ThreadArtefactMembership.metadata(metadata, emptyList())))
        assertTrue(state.members.isEmpty()); assertTrue(state.membershipReady)
    }
}
