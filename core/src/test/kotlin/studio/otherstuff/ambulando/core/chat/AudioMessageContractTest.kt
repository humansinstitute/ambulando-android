package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** Independent model of Tower's message route and exclusive active storage link contract, not SampleTower.
 * This is client contract evidence; Tower's own PostgreSQL fixture and live checks remain separate.
 */
class AudioMessageContractTest {
    private class Contract : HttpTransport {
        val links = mutableSetOf<String>()
        val receipts = mutableMapOf<String, Pair<String, ByteArray>>()
        var creations = 0
        var loseResponse = false
        override suspend fun send(request: HttpRequest): HttpResponse {
            val auth = decodeAuthorization(request)
            assertTrue(SignatureVerifier.verify(auth))
            assertEquals(27235, auth.kind)
            assertTrue(auth.tags.contains(listOf("u", request.url)))
            assertTrue(auth.tags.contains(listOf("method", "POST")))
            assertTrue(auth.tags.contains(listOf("payload", Sha256Hex.of(request.body!!))))
            val o = parseJsonObjectOrNull(request.body!!)!!
            val signature = o.obj("message_signature")!!.obj("nostr_event")!!
            val event = WireJson.decodeFromString(NostrEvent.serializer(), signature.toString())
            if (!SignatureVerifier.verify(event) || event.content != o.string("body") ||
                !event.tags.contains(listOf("channel_id", request.url.substringAfter("/channels/").substringBefore('/')))) {
                return HttpResponse(400, """{"code":"validation_error"}""".toByteArray())
            }
            assertEquals(auth.pubkey, event.pubkey)
            assertTrue(event.tags.contains(listOf("workspace_id", Fixtures.WORKSPACE_ID)))
            assertTrue(event.tags.contains(listOf("body_sha256", Sha256Hex.of(event.content))))
            assertEquals(33358, event.kind)
            val thread = o.string("thread_id")
            if (thread != null) assertTrue(event.tags.contains(listOf("thread_id", thread)))
            else assertTrue(o.bool("create_thread") == true)
            val attachments = o.obj("metadata")?.objects("attachments") ?: emptyList()
            val objects = attachments.map { it.string("storage_object_id")!! }
            val hash = Sha256Hex.of(jsonBody(jsonObjectOf("body" to o.string("body"), "thread" to thread,
                "create" to o.bool("create_thread"), "objects" to objects)))
            val id = o.string("client_request_id")!!
            receipts[id]?.let {
                return if (it.first == hash) HttpResponse(200, it.second)
                else HttpResponse(409, """{"code":"idempotency_conflict"}""".toByteArray())
            }
            if (objects.any { it in links }) return HttpResponse(409, """{"code":"message_attachment_conflict"}""".toByteArray())
            links.addAll(objects); creations++
            val response = """{"created":true,"message":{"id":"${UUID.randomUUID()}"}}""".toByteArray()
            receipts[id] = hash to response
            if (loseResponse) { loseResponse = false; throw java.io.IOException("synthetic lost response after commit") }
            return HttpResponse(201, response)
        }
    }

    @Test fun newDmRootReplyAndRegularChannelSignAndReplayWithoutDuplicatingStorageOrMessage() = runBlocking {
        val identity = makeIdentity()
        for ((channel, thread) in listOf(Fixtures.CHANNEL_1 to null, Fixtures.CHANNEL_1 to Fixtures.newId(), Fixtures.CHANNEL_2 to null)) {
            val contract = Contract()
            val objectId = Fixtures.newId()
            val attachment = PendingAttachment(filename = "Voice.m4a", mimeType = "audio/mp4", sizeBytes = 10,
                sha256 = "0".repeat(64), staged = "", objectId = objectId, complete = true, kind = "file")
            val item = OutboxItem(Fixtures.newId(), "c", Fixtures.ORIGIN, TestKeys.NPUB, Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB,
                channel, thread, thread == null, StorageMarkdown.file(objectId, attachment.filename), emptyList(), null,
                OutboxState.QUEUED, 0, null, null, null, "now", listOf(attachment))
            val frozen = OutboxRequest.build(item, identity)
            val client = TowerClient(TowerOrigin(Fixtures.ORIGIN), contract, identity)
            val path = TowerAPI.base(item.workspaceId) + "/channels/$channel/messages"
            // The old File-first sequence really conflicts under this independent contract.
            contract.links.add(objectId)
            val rejected = client.exchange("POST", path, item.appNpub, frozen)
            assertEquals(409, rejected.status)
            assertEquals("message_attachment_conflict", (OutboxRequest.classify(409, rejected.body, 1) as SendOutcome.Permanent).code)
            assertEquals(0, contract.creations)
            // Prior-build recovery uses the existing File link; the same key can commit after the rolled-back409.
            val fileReference = parseJsonObjectOrNull(frozen)!!.toMutableMap().also { it.remove("metadata") }
            contract.loseResponse = true
            val recovered = jsonBody(kotlinx.serialization.json.JsonObject(fileReference))
            try { client.exchange("POST", path, item.appNpub, recovered); fail("response must be lost") } catch (_: java.io.IOException) { }
            assertEquals(200, client.exchange("POST", path, item.appNpub, recovered).status)
            assertEquals(1, contract.creations)
            assertEquals(setOf(objectId), contract.links) // original File association was never duplicated
            contract.receipts.clear(); contract.creations = 0
            contract.links.clear() // fresh upload has no File registration
            contract.loseResponse = true
            try { client.exchange("POST", path, item.appNpub, frozen); fail("response must be lost") } catch (_: java.io.IOException) { }
            assertEquals(200, client.exchange("POST", path, item.appNpub, frozen).status)
            assertEquals(1, contract.creations)
            assertEquals(setOf(objectId), contract.links)
            // Same request id with different signed message remains a conflict, never success.
            val altered = parseJsonObjectOrNull(frozen)!!.toMutableMap()
            val signed = studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature.sign("different", item.workspaceId, channel, thread, signer = identity)
            altered["body"] = kotlinx.serialization.json.JsonPrimitive("different")
            altered["message_signature"] = signed.requestObject
            assertEquals(409, client.exchange("POST", path, item.appNpub, jsonBody(kotlinx.serialization.json.JsonObject(altered))).status)
            // Reusing a signature for changed body fails validation before an idempotent acceptance.
            altered["body"] = kotlinx.serialization.json.JsonPrimitive("tampered")
            assertEquals(400, client.exchange("POST", path, item.appNpub, jsonBody(kotlinx.serialization.json.JsonObject(altered))).status)
        }
    }
}
