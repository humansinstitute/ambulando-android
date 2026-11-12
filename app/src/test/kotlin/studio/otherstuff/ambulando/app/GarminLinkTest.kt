package studio.otherstuff.ambulando.app

import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.garmin.*

class GarminLinkTest {
    private class Port : GarminTransport {
        lateinit var ready: () -> Unit
        lateinit var stopped: (String) -> Unit
        lateinit var event: (IQDevice.IQDeviceStatus) -> Unit
        var known = listOf(IQDevice(71, "Watch"))
        var current = CONNECTED
        var queries = 0; var listens = 0; var registers = 0; var clears = 0; var shutdowns = 0
        var error: String? = null
        override fun initialize(ready: () -> Unit, stopped: (String) -> Unit) { this.ready = ready; this.stopped = stopped }
        override fun knownDevices(): List<IQDevice> { queries++; error?.let { throw IllegalStateException(it) }; return known }
        override fun status(device: IQDevice) = current
        override fun listen(device: IQDevice, changed: (IQDevice.IQDeviceStatus) -> Unit) { listens++; event = changed; changed(current) }
        override fun register(device: IQDevice) { registers++ }
        override fun clearListeners() { clears++ }
        override fun shutdown() { shutdowns++ }
    }
    @Test fun waitsForReadyHydratesUnknownAndDoesNotDuplicateListeners() {
        val p = Port(); val l = GarminLink({ p }, { 0 }, {})
        assertEquals(UNKNOWN, p.known.single().status)
        l.bind(71); l.connect(); l.connect()
        assertEquals(0, p.queries)
        assertEquals(GarminLink.Phase.CONNECTING, l.phase)
        p.ready()
        assertEquals(CONNECTED, l.devices.single().status)
        assertEquals(UNKNOWN, p.known.single().status)
        assertTrue(l.message.contains("listener ready"))
        repeat(3) { l.refresh() }
        assertEquals(1, p.listens); assertEquals(1, p.registers)
    }
    @Test fun savedOfflineBindingRestoresAndRenewsRegistrationWithoutFalseReady() {
        val p = Port(); p.current = NOT_CONNECTED
        val l = GarminLink({ p }, { 0 }, {})
        l.bind(71); p.ready()
        assertEquals(71L, l.bound); assertEquals(0, p.registers)
        assertTrue(l.message.contains("disconnected")); assertFalse(l.message.contains("listener ready"))
        p.event(CONNECTED); assertEquals(1, p.registers)
        p.event(NOT_CONNECTED); assertTrue(l.message.contains("disconnected"))
        p.event(CONNECTED); assertEquals(2, p.registers)
        assertEquals(71L, l.bound)
    }
    @Test fun disappearanceAndDisableRejectOldCallbacksAndKeepBindingUntilExplicitDisable() {
        val p = Port(); val l = GarminLink({ p }, { 0 }, {})
        l.bind(71); p.ready(); val old = p.event
        p.known = emptyList(); l.connect()
        assertEquals(71L, l.bound); assertNull(l.active); assertTrue(l.message.contains("unavailable"))
        old(CONNECTED); assertNull(l.active)
        p.known = listOf(IQDevice(71, "Watch")); l.connect(); assertEquals(2, p.listens)
        l.bind(null); old(CONNECTED)
        assertNull(l.bound); assertNull(l.active); assertFalse(l.message.contains("listener ready"))
    }
    @Test fun timeoutErrorAndRetryIgnoreLateCallbacksAndRecoverSavedBinding() {
        val first = Port(); val second = Port(); var attempts = 0; var clock = 0L
        val l = GarminLink({ if (attempts++ == 0) first else second }, { clock }, {})
        l.bind(71); clock = 20_001; l.tick()
        assertEquals(GarminLink.Phase.ERROR, l.phase); assertTrue(l.message.contains("did not respond"))
        l.connect(); first.ready(); assertEquals(0, first.queries)
        second.ready(); assertEquals(71L, l.active!!.deviceIdentifier)
        first.stopped("old disconnect"); assertEquals(GarminLink.Phase.READY, l.phase)
        second.error = "Service unavailable"; l.connect()
        assertEquals(GarminLink.Phase.ERROR, l.phase); assertTrue(l.message.contains("Service unavailable"))
        assertEquals(71L, l.bound); assertTrue(l.devices.isEmpty())
    }
    @Test fun listenerReadinessDoesNotClaimWatchCommunicationAndTimesOutHonestly() {
        val p = Port(); var clock = 0L; val l = GarminLink({ p }, { clock }, {})
        l.bind(71); p.ready()
        assertTrue(l.message.contains("awaiting watch message"))
        clock = 20_001; l.tick(); assertTrue(l.message.contains("No wrist application receipt"))
        l.received(72); assertTrue(l.message.contains("No wrist application receipt"))
        l.received(71); assertTrue(l.message.contains("wrist application receipt confirmed"))
        p.event(NOT_CONNECTED); p.event(CONNECTED)
        assertTrue(l.message.contains("awaiting watch message"))
    }

    @Test fun saveAllowsKnownOfflineWatchButExplainsMissingWatchOrStaleDestination() {
        val channel = studio.otherstuff.ambulando.core.transport.ChannelRecord("workspace", "channel", "scope", "DM", updatedAt = "")
        val device = IQDevice(71, "Watch")
        for (status in listOf(UNKNOWN, NOT_CONNECTED, CONNECTED)) {
            device.status = status
            assertEquals(device, validateWatchSelection("workspace", listOf(channel), "channel", listOf(device), 71).second)
        }
        for (channels in listOf(emptyList(), listOf(channel.copy(accessRemoved = true)), listOf(channel.copy(workspaceId = "other")))) {
            val error = assertThrows(IllegalArgumentException::class.java) { validateWatchSelection("workspace", channels, "channel", listOf(device), 71) }
            assertTrue(error.message!!.contains("destination"))
        }
        val error = assertThrows(IllegalArgumentException::class.java) { validateWatchSelection("workspace", listOf(channel), "channel", emptyList(), 71) }
        assertTrue(error.message!!.contains("watch"))
    }
    @Test fun explicitReconnectRenewsPossiblySilentlyFailedRemoteRegistration() {
        val p = Port(); val l = GarminLink({ p }, { 0 }, {})
        l.bind(71); p.ready()
        // Garmin registerForAppEvents can return normally despite a remote Binder failure.
        assertEquals(1, p.registers)
        l.connect()
        assertEquals(2, p.registers); assertEquals(1, p.listens)
        assertTrue(l.message.contains("awaiting watch message"))
        l.received(71); assertTrue(l.message.contains("wrist application receipt confirmed"))
    }
}
