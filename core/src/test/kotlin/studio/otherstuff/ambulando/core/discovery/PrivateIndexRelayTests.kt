package studio.otherstuff.ambulando.core.discovery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*
import java.util.concurrent.CopyOnWriteArrayList

/** Production WebSocket framing against an explicitly routed disposable local server; no public relay traffic. */
class PrivateIndexRelayTests {
    private val fixture = WireJson.parseToJsonElement(checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/workspace-self-index-v1.json")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    private val events = fixture["events"]!!.jsonArray.take(2).map { WireJson.decodeFromJsonElement(NostrEvent.serializer(),it) }
    private fun client(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()).build())
    }.build()

    @Test fun queryUntilEoseAndExactIdAckWireProtocol() = runBlocking {
        val server = MockWebServer(); server.start()
        val filters = CopyOnWriteArrayList<JsonObject>()
        val sent = CopyOnWriteArrayList<NostrEvent>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket,text: String) {
                    val frame = WireJson.parseToJsonElement(text).jsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "REQ" -> {
                            val sub = frame[1]; val f = frame[2].jsonObject; filters += f
                            val answer = if (f.containsKey("ids")) sent.toList() else events
                            answer.forEach { webSocket.send(JsonArray(listOf(JsonPrimitive("EVENT"),sub,it.toJsonObject())).toString()) }
                            webSocket.send(JsonArray(listOf(JsonPrimitive("EOSE"),sub)).toString())
                        }
                        "EVENT" -> {
                            val e = WireJson.decodeFromJsonElement(NostrEvent.serializer(),frame[1]); sent += e
                            webSocket.send(JsonArray(listOf(JsonPrimitive("OK"),JsonPrimitive(e.id),JsonPrimitive(true),JsonPrimitive(""))).toString())
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,null) }
            }).build()
        }
        try {
            val relay = OkHttpPrivateIndexRelay(client(server))
            val filter = PrivateWorkspaceIndex.filter(events.first().pubkey)
            assertEquals(events,relay.query("wss://fixture.example",filter))
            assertFalse(filters.first().containsKey("#app_pub"))
            assertTrue(relay.publish("wss://fixture.example",events.first()))
            assertEquals(listOf(events.first()),relay.query("wss://fixture.example",buildJsonObject { put("ids",JsonArray(listOf(JsonPrimitive(events.first().id)))) }))
            assertTrue(runCatching { relay.query("ws://fixture.example",filter) }.isFailure)
        } finally { server.close() }
    }

    @Test fun cappedRelayAnswerFailsInsteadOfReturningPartialSuccess() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket,text: String) {
                val frame = WireJson.parseToJsonElement(text).jsonArray
                if (frame[0] != JsonPrimitive("REQ")) return
                repeat(PrivateWorkspaceIndex.LIMIT) { webSocket.send(JsonArray(listOf(JsonPrimitive("EVENT"),frame[1],events.first().toJsonObject())).toString()) }
                webSocket.send(JsonArray(listOf(JsonPrimitive("EOSE"),frame[1])).toString())
            }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,null) }
        }).build())
        try {
            val relay = OkHttpPrivateIndexRelay(client(server))
            assertTrue(runCatching { relay.query("wss://fixture.example",PrivateWorkspaceIndex.filter(events.first().pubkey)) }.isFailure)
        } finally { server.close() }
    }
}
