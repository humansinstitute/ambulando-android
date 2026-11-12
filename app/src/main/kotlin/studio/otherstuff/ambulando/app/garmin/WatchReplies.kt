package studio.otherstuff.ambulando.app.garmin

/** One application exchange at a time: SDK 2.2 has only one send callback slot per app/device.
 * A missing SDK callback freezes this lane until its callback drains or the process restarts, rather than relabelling a late callback.
 * Application receipts, never SDK success, prove that the wrist applied a reply. */
internal class WatchReplies(
    private val now: () -> Long,
    private val nonce: () -> String,
    private val send: (Long, Map<String, Any>, (Boolean) -> Unit) -> Unit,
    private val status: (String) -> Unit,
    private val confirmed: (Long) -> Unit,
    private val applied: (Long, Map<String, Any>) -> Unit = { _, _ -> },
) {
    private data class Flight(val device: Long, val packet: Map<String, Any>, val started: Long, val receiptRequired: Boolean,
        var attempts: Int = 0, var callbackPending: Boolean = false, var received: Boolean = false)
    private var current: Flight? = null
    private var queued: Triple<Long, Map<String, Any>, Boolean>? = null
    private var generation = 0
    private var draining: Flight? = null
    fun reset(transportClosed: Boolean = false) {
        generation++
        if (transportClosed) draining = null
        else current?.takeIf { it.callbackPending }?.let { draining = it }
        current = null; queued = null
    }
    fun offer(device: Long, packet: Map<String, Any>, receiptRequired: Boolean = true) {
        if (draining != null) {
            queued = Triple(device, packet, receiptRequired)
            status("Waiting for prior SDK callback; restart app if it never arrives. Wrist unconfirmed")
            return
        }
        val f = current
        if (f != null) {
            if (f.device == device && f.packet.filterKeys { it != "n" } == packet) return
            queued = Triple(device, packet, receiptRequired) // Coalesce status updates; journal remains the authority.
            return
        }
        current = Flight(device, packet + ("n" to nonce()), now(), receiptRequired)
        transmit()
    }
    private fun transmit() {
        val f = current ?: return
        if (f.callbackPending) return
        f.attempts++; f.callbackPending = true
        val token = generation
        status("Phone reply queued; wrist unconfirmed")
        try {
            send(f.device, f.packet) { accepted ->
                if (draining === f) { draining = null; advance(); return@send }
                if (generation != token || current !== f || !f.callbackPending) return@send
                f.callbackPending = false
                if (f.received) { advance(); return@send }
                if (!f.receiptRequired) { status("Garmin ${if (accepted) "accepted" else "rejected"} legacy reply; wrist unconfirmed"); advance(); return@send }
                status(if (accepted) "Garmin accepted reply; wrist unconfirmed" else "Garmin rejected reply; wrist unconfirmed")
            }
        } catch (_: Exception) { f.callbackPending = false; status("Reply failed; wrist unconfirmed") }
    }
    fun receipt(device: Long, m: Map<*, *>): Boolean {
        val f = current ?: return false
        if (!f.receiptRequired) return false
        val build = integer(m["b"]) ?: return false
        if (device != f.device || m["n"] != f.packet["n"] || m["s"] != f.packet["s"] ||
            integer(m["r"]) != f.packet["r"] || build < 38 || f.received) return false
        f.received = true
        confirmed(device)
        applied(device, f.packet)
        status("Wrist confirmed phone ${f.packet["b"]} / watch $build, revision ${f.packet["r"]}")
        if (!f.callbackPending) advance()
        return true
    }
    private fun integer(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }
    fun tick() {
        val f = current ?: return
        val age = now() - f.started
        if (age >= 21_000) {
            status(if (f.received) "Wrist confirmed; SDK callback still pending" else "Wrist receipt timed out; check watch diagnostics. Restart app if SDK callback is missing.")
            if (!f.callbackPending) advance()
        } else if (!f.received && f.attempts < 3 && age >= f.attempts * 7_000L) transmit()
    }
    private fun advance() {
        current = null
        queued?.also { queued = null; offer(it.first, it.second, it.third) }
    }
}
