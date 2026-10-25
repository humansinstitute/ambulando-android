package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import java.net.UnknownHostException

/**
 * SSE stand-in for sample-data mode: speaks the same frames as Tower's `GET /workspaces/:w/events/stream` (`connected`,
 * `flightdeck_pg.event` frames with ids, `: heartbeat` comments) over the [SampleTower] event log. The request must be
 * NIP-98 signed like the real route (401 otherwise); an unknown cursor is 400. New events are delivered as soon as they
 * are appended; going offline drops the connection.
 */
class SampleEventStream(
    private val tower: SampleTower,
    /** Interval between heartbeat comments (Tower sends one every 25 s). */
    private val heartbeatMillis: Long = 25_000,
) : EventStreamTransport {
    override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> {
        if (tower.offline) throw SampleOfflineException()
        val url = SampleURL.of(request) ?: throw UnknownHostException(request.url)
        if (url.host != tower.origin.host) throw UnknownHostException(url.host)
        val parts = url.path.split('/').filter { it.isNotEmpty() }
        val cursor = url.query("cursor")
        val authorized = tower.auth.verify(request) is SampleAuth.Result.Valid
        val isStream = parts.size == 7 && parts.take(4) == listOf("api", "v4", "flightdeck-pg", "workspaces") && parts[5] == "events" &&
            parts[6] == "stream" && parts[4] in setOf(SampleIds.STUDIO, SampleIds.LAB) && request.method.uppercase() == "GET"
        val start = SampleTower.eventRow(cursor)
        val status = when {
            !authorized -> 401
            !isStream -> 404
            start == null -> 400
            else -> 200
        }
        val head = HttpResponseHead(status, mapOf("Content-Type" to if (status == 200) "text/event-stream" else "application/json"))
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        if (status != 200 || start == null) {
            channel.close()
            return head to EventChunks.of(channel)
        }
        val workspace = parts[4]
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            var after: Int = start
            channel.send("event: connected\ndata: {\"cursor\":\"${cursor ?: ""}\",\"poll_ms\":1000}\n\n".toByteArray())
            while (true) {
                if (tower.offline) {
                    channel.close(SampleConnectionLostException())
                    return@launch
                }
                val seen = tower.changes.value
                for (event in tower.eventRows(workspace, after, 50)) {
                    after = event["row_version"].asInt() ?: after
                    channel.send("id: $after\nevent: flightdeck_pg.event\ndata: ${String(SampleJson.encode(event), Charsets.UTF_8)}\n\n".toByteArray())
                }
                if (tower.eventRows(workspace, after, 1).isNotEmpty()) continue
                val changed = withTimeoutOrNull(heartbeatMillis) { tower.changes.first { it != seen } }
                if (changed == null) channel.send(": heartbeat\n\n".toByteArray())
            }
        }
        channel.invokeOnClose { job.cancel() }
        val chunks = EventChunks.of(channel)
        return head to object : EventChunks {
            override suspend fun next(): ByteArray? = chunks.next()
            override fun cancel() {
                job.cancel()
                chunks.cancel()
            }
        }
    }
}
