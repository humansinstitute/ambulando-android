package studio.otherstuff.ambulando.core.discovery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.io.IOException
import java.util.UUID

fun interface PrivateIndexRelay {
    suspend fun query(url: String, filter: JsonObject): List<NostrEvent>
    suspend fun publish(url: String, event: NostrEvent): Boolean = false
}

/** One bounded public-relay query; URLs in relay events are never followed by this transport. */
class OkHttpPrivateIndexRelay(private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()) : PrivateIndexRelay {
    override suspend fun query(url: String, filter: JsonObject): List<NostrEvent> = withTimeout(12_000) {
        require(url.startsWith("wss://"))
        val answer = CompletableDeferred<List<NostrEvent>>()
        val sub = UUID.randomUUID().toString()
        val events = ArrayList<NostrEvent>()
        var bytes = 0
        val socket = http.newWebSocket(Request.Builder().url("https://" + url.removePrefix("wss://")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(sub), filter)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                bytes += text.length
                if (text.length > 131072 || bytes > 4 * 1024 * 1024) { answer.completeExceptionally(IOException("Discovery relay exceeded its response budget")); return }
                val msg = runCatching { WireJson.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                if (msg.getOrNull(1) != JsonPrimitive(sub)) return
                when ((msg.firstOrNull() as? JsonPrimitive)?.content) {
                    "EVENT" -> {
                        runCatching { WireJson.decodeFromJsonElement(NostrEvent.serializer(), msg[2]) }.getOrNull()?.let { events += it }
                        if (events.size >= PrivateWorkspaceIndex.LIMIT) answer.completeExceptionally(IOException("Discovery relay answer was capped"))
                    }
                    "EOSE" -> answer.complete(events.toList())
                    "CLOSED" -> answer.completeExceptionally(IOException("Discovery relay refused the query"))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { answer.completeExceptionally(IOException("Discovery relay unavailable")) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (!answer.isCompleted) answer.completeExceptionally(IOException("Discovery relay closed before completion"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!answer.isCompleted) answer.completeExceptionally(IOException("Discovery relay closed before completion")) }
        })
        try { answer.await() } finally {
            socket.send(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(sub))).toString())
            socket.close(1000, "done"); socket.cancel()
        }
    }
    override suspend fun publish(url: String, event: NostrEvent): Boolean = withTimeout(12_000) {
        require(url.startsWith("wss://"))
        val answer = CompletableDeferred<Boolean>()
        val socket = http.newWebSocket(Request.Builder().url("https://" + url.removePrefix("wss://")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JsonArray(listOf(JsonPrimitive("EVENT"),event.toJsonObject())).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 131072) { answer.complete(false); return }
                val msg = runCatching { WireJson.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                if (msg.getOrNull(0) == JsonPrimitive("OK") && msg.getOrNull(1) == JsonPrimitive(event.id)) {
                    answer.complete((msg.getOrNull(2) as? JsonPrimitive)?.booleanOrNull == true)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { answer.complete(false) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code,null); answer.complete(false) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { answer.complete(false) }
        })
        try { answer.await() } finally { socket.close(1000,"done"); socket.cancel() }
    }

}
