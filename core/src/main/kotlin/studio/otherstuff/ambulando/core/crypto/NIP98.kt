package studio.otherstuff.ambulando.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.util.Base64

/**
 * NIP-98 HTTP auth, matching `buildHttpAuthEvent` in the web client:
 * tags `u` (exact absolute URL), `method`, and `payload` = SHA-256 of the exact body bytes sent.
 */
object NIP98 {
    const val KIND = 27235

    fun tags(url: String, method: String, body: ByteArray?): List<List<String>> {
        val tags = mutableListOf(listOf("u", url), listOf("method", method.uppercase()))
        if (body != null) tags.add(listOf("payload", Sha256Hex.of(body)))
        return tags
    }

    /** `Nostr <base64(event JSON)>` for the `Authorization` header. A fresh event is signed on every call. */
    suspend fun authorization(url: String, method: String, body: ByteArray?, signer: EventSigner): String {
        val event = signer.sign(NostrEventTemplate(KIND, tags(url, method, body), ""))
        return "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toByteArray(Charsets.UTF_8))
    }
}

/** Signed chat instruction (`kind 33358`), matching the web client's message instruction signatures. */
object MessageInstructionSignature {
    const val KIND = 33358
    const val PROTOCOL_NAME = "flightdeck_pg_message_instruction"

    fun tags(
        body: String, workspaceId: String, channelId: String, threadId: String? = null,
        messageId: String? = null, revision: Int? = null,
    ): List<List<String>> {
        val tags = mutableListOf(listOf("protocol", PROTOCOL_NAME), listOf("body_sha256", Sha256Hex.of(body)))
        if (workspaceId.isNotEmpty()) tags.add(listOf("workspace_id", workspaceId))
        if (channelId.isNotEmpty()) tags.add(listOf("channel_id", channelId))
        if (!threadId.isNullOrEmpty()) tags.add(listOf("thread_id", threadId))
        if (!messageId.isNullOrEmpty()) tags.add(listOf("message_id", messageId))
        if (revision != null && revision > 0) tags.add(listOf("revision", revision.toString()))
        return tags
    }

    data class Signed(val event: NostrEvent, val signerNpub: String, val bodySha256: String) {
        /** `message_signature` object for the message request body. */
        val requestObject: JsonObject
            get() = JsonObject(
                linkedMapOf(
                    "version" to JsonPrimitive(1),
                    "signer_npub" to JsonPrimitive(signerNpub),
                    "body_sha256" to JsonPrimitive(bodySha256),
                    "nostr_event" to event.toJsonObject(),
                ),
            )
    }

    /**
     * Signs a send (`messageId == null`) or a revision of an existing message (`message_id` and `revision` tags,
     * where `revision` is the row version the edit will create).
     */
    suspend fun sign(
        body: String, workspaceId: String, channelId: String, threadId: String? = null,
        messageId: String? = null, revision: Int? = null, signer: EventSigner,
    ): Signed {
        val event = signer.sign(NostrEventTemplate(KIND, tags(body, workspaceId, channelId, threadId, messageId, revision), body))
        val npub = NostrKeys.npub(event.pubkey) ?: throw IdentityError.InvalidVault
        return Signed(event, npub, Sha256Hex.of(body))
    }
}
