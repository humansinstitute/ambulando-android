package studio.otherstuff.ambulando.core.sync

/** Only active recovery of failed keys shows Reconnecting; unrelated work and delayed jobs never spin forever. */
fun connectionStatus(failing: Boolean, snapshot: SyncSnapshot): String = when {
    snapshot.connectionFailures.any { it in snapshot.running || it in snapshot.queued } -> "Reconnecting"
    failing || snapshot.connectionFailures.isNotEmpty() -> "Connection lost"
    else -> "Connected"
}
