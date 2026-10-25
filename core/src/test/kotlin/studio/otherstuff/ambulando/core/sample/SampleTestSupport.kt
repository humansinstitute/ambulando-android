package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature
import studio.otherstuff.ambulando.core.crypto.NIP98
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.Exchange
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.util.Base64

/** Signers over the public NIP-19 test key and a synthetic second key (never real identities). */
object SampleSigners {
    val primary: EventSigner = EventSigner { NostrKeys.sign(it, Hex.decode(TestKeys.SECRET_HEX)!!) }
    private val otherSecret = ByteArray(32) { 0x11 }
    val other: EventSigner = EventSigner { NostrKeys.sign(it, otherSecret) }
    val otherNpub: String = NostrKeys.npub(NostrKeys.publicKeyHex(otherSecret))!!
}

/** A tower prepared for the test identity, with a client and API over it. */
class SampleHarness(val tower: SampleTower = SampleTower(), signer: EventSigner = SampleSigners.primary, prepare: Boolean = true) {
    init { if (prepare) tower.prepare(TestKeys.NPUB) }
    val client: TowerClient = tower.client(signer)
    val api = TowerAPI(client)
    val app: String = SampleIds.APP

    fun base(w: String = SampleIds.STUDIO) = TowerAPI.base(w)

    suspend fun exchange(method: String, path: String, body: Map<String, Any?>?): Exchange =
        client.exchange(method, path, app, body?.let { jsonBody(toJsonElement(it) as JsonObject) })

    /** The exact request body the outbox sends for a chat message (kind 33358 over the trimmed body). */
    suspend fun messageBody(
        channel: String, body: String, requestId: String, thread: String? = null, workspace: String = SampleIds.STUDIO,
        signer: EventSigner = SampleSigners.primary, signedChannel: String = channel, signedBody: String = body.trim(),
    ): ByteArray {
        val signed = MessageInstructionSignature.sign(signedBody, workspace, signedChannel, thread, signer = signer)
        val o = linkedMapOf<String, Any?>("body" to body, "client_request_id" to requestId, "message_signature" to signed.requestObject)
        if (thread != null) o["thread_id"] = thread else o["create_thread"] = true
        return jsonBody(toJsonElement(o) as JsonObject)
    }

    suspend fun send(channel: String, bytes: ByteArray, workspace: String = SampleIds.STUDIO): Exchange =
        client.exchange("POST", base(workspace) + "/channels/$channel/messages", app, bytes)
}

fun Exchange.json(): JsonObject = parseJsonObject(body, "test")

/** A request signed by hand, for tampering with the NIP-98 event. */
suspend fun handSigned(
    method: String, url: String, body: ByteArray?, signedURL: String = url, signedMethod: String = method, payload: String? = null,
    createdAt: Long = System.currentTimeMillis() / 1000, kind: Int = NIP98.KIND,
): HttpRequest {
    val tags = NIP98.tags(signedURL, signedMethod, body).map { tag -> if (tag[0] == "payload" && payload != null) listOf("payload", payload) else tag }
    val event = SampleSigners.primary.sign(NostrEventTemplate(kind, tags, "", createdAt))
    val header = "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toByteArray())
    val headers = mutableMapOf("Authorization" to header)
    if (body != null) headers["Content-Type"] = "application/json"
    return HttpRequest(method, url, headers, body)
}
