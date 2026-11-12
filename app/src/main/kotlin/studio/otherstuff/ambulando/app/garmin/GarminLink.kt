package studio.otherstuff.ambulando.app.garmin

import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus

/** Main-thread lifecycle. A paired device is durable identity, not evidence of a live connection. */
internal interface GarminTransport {
    fun initialize(ready: () -> Unit, stopped: (String) -> Unit)
    fun knownDevices(): List<IQDevice>
    fun status(device: IQDevice): IQDeviceStatus
    fun listen(device: IQDevice, changed: (IQDeviceStatus) -> Unit)
    fun register(device: IQDevice)
    fun clearListeners()
    fun shutdown()
}

internal class GarminLink(
    private val factory: () -> GarminTransport,
    private val now: () -> Long,
    private val changed: () -> Unit,
) {
    enum class Phase { IDLE, CONNECTING, READY, ERROR }
    var phase = Phase.IDLE; private set
    var devices: List<IQDevice> = emptyList(); private set
    var message = "Not connected"; private set
    var bound: Long? = null; private set
    var active: IQDevice? = null; private set
    private var transport: GarminTransport? = null
    private var generation = 0
    private var deadline = 0L
    private var listening: Long? = null
    private var registered = false
    private var listenerGeneration = 0
    private var watchConfirmed = false
    private var watchDeadline = 0L

    fun connect() {
        if (phase == Phase.CONNECTING) return
        if (phase == Phase.READY) {
            // SDK 2.2 swallows remote registerApp failures. Explicit Retry must renew
            // that request even when its local listener was successfully installed.
            registered = false
            refresh()
            return
        }
        val token = ++generation
        phase = Phase.CONNECTING
        message = "Connecting to Garmin Connect…"
        deadline = now() + 20_000
        changed()
        try {
            val next = factory()
            transport = next
            next.initialize({
                if (generation == token && phase == Phase.CONNECTING) {
                    phase = Phase.READY
                    refresh()
                }
            }, { reason -> if (generation == token) fail(reason) })
        } catch (e: Exception) { fail("Garmin connection failed: ${e.message ?: e.javaClass.simpleName}. Retry connection.") }
    }

    fun tick() {
        if (phase == Phase.CONNECTING && now() >= deadline) fail("Garmin Connect did not respond. Open Garmin Connect, then retry connection.")
        if (phase == Phase.READY && registered && !watchConfirmed && now() >= watchDeadline) {
            message = "No watch message received yet. Open Ambulando Remote and choose Status or Start; check Garmin Connect, then retry."
            changed()
        }
    }

    fun bind(id: Long?) {
        if (bound != id) {
            clear()
            bound = id
        }
        if (phase == Phase.READY) refresh()
        else if (id != null) connect()
        else { message = "Watch recorder disabled. Retained drafts are kept."; changed() }
    }

    fun refresh() {
        if (phase != Phase.READY) return
        try {
            val port = checkNotNull(transport)
            // Never use the status cached in getKnownDevices() as connectivity authority.
            devices = port.knownDevices().map { device ->
                IQDevice(device.deviceIdentifier, device.friendlyName).apply { status = port.status(device) }
            }
            val device = devices.firstOrNull { it.deviceIdentifier == bound }
            if (device == null) {
                clear()
                message = if (bound == null) "Select a paired watch" else "Saved watch unavailable in Garmin Connect. Check pairing, then retry."
            } else {
                active = device
                if (listening != bound) {
                    clear()
                    active = device
                    listening = bound // SDK may call the initial status listener synchronously.
                    val token = generation
                    val listenerToken = listenerGeneration
                    port.listen(device) { status ->
                        if (token == generation && listenerToken == listenerGeneration && listening == device.deviceIdentifier && bound == device.deviceIdentifier && phase == Phase.READY) {
                            devices = devices.map { old -> IQDevice(old.deviceIdentifier, old.friendlyName).apply {
                                this.status = if (old.deviceIdentifier == device.deviceIdentifier) status else old.status
                            } }
                            try { updateStatus(device, status) }
                            catch (e: Exception) { fail("Watch listener failed: ${e.message ?: e.javaClass.simpleName}. Retry connection.") }
                            changed()
                        }
                    }
                }
                updateStatus(device, devices.first { it.deviceIdentifier == bound }.status)
            }
            changed()
        } catch (e: Exception) { fail("Garmin device query failed: ${e.message ?: e.javaClass.simpleName}. Retry connection.") }
    }

    private fun updateStatus(device: IQDevice, status: IQDeviceStatus) {
        if (status == IQDeviceStatus.CONNECTED) {
            if (!registered) {
                checkNotNull(transport).register(device)
                registered = true; watchConfirmed = false; watchDeadline = now() + 20_000
            }
            message = if (watchConfirmed) "${device.friendlyName}: watch messages received."
                else "${device.friendlyName}: phone listener ready; awaiting watch message. Open Ambulando Remote; Start or Status."
        } else {
            // A reconnect must renew the remote registration; retain local device events meanwhile.
            registered = false; watchConfirmed = false
            message = "${device.friendlyName}: ${if (status == IQDeviceStatus.NOT_CONNECTED) "disconnected" else "connection status unknown"}. Open Garmin Connect, then retry."
        }
    }

    fun received(id: Long) {
        if (phase == Phase.READY && bound == id && registered) {
            watchConfirmed = true
            message = "${active?.friendlyName ?: "Saved watch"}: watch messages received."
            changed()
        }
    }

    fun transportError(reason: String) { message = reason; changed() }

    private fun clear() {
        ++listenerGeneration
        if (listening != null) transport?.clearListeners()
        listening = null; registered = false; active = null; watchConfirmed = false
    }

    private fun fail(reason: String) {
        ++generation // Ignore callbacks from a timed-out/shut-down initialization.
        runCatching { clear() }
        runCatching { transport?.shutdown() }
        transport = null
        devices = emptyList(); active = null; listening = null; registered = false
        phase = Phase.ERROR; message = reason
        changed()
    }
}
