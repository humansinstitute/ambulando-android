package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature
import studio.otherstuff.ambulando.core.docs.DocumentSaver
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.store.isChannelAccessRemoved
import studio.otherstuff.ambulando.core.store.isAccessRemoved
import studio.otherstuff.ambulando.core.store.FlaggedTable
import studio.otherstuff.ambulando.core.store.OutboxItem
import studio.otherstuff.ambulando.core.store.OutboxState
import studio.otherstuff.ambulando.core.store.PendingAttachment
import studio.otherstuff.ambulando.core.store.acknowledgeSend
import studio.otherstuff.ambulando.core.store.discardOutbox
import studio.otherstuff.ambulando.core.store.draft
import studio.otherstuff.ambulando.core.store.editDraftAttachments
import studio.otherstuff.ambulando.core.store.enqueueSend
import studio.otherstuff.ambulando.core.store.freezeOutbox
import studio.otherstuff.ambulando.core.store.markOutbox
import studio.otherstuff.ambulando.core.store.outbox
import studio.otherstuff.ambulando.core.store.outboxItem
import studio.otherstuff.ambulando.core.store.retryOutbox
import studio.otherstuff.ambulando.core.store.thread
import studio.otherstuff.ambulando.core.store.updateOutboxAttachments
import studio.otherstuff.ambulando.core.sync.SyncBackoff
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.transport.FileTransferError
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.PreparedObject
import studio.otherstuff.ambulando.core.transport.ThreadRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.store
import studio.otherstuff.ambulando.core.transport.string
import java.io.File

/** How a send attempt ended. Retryable outcomes resend the same frozen request later; permanent ones wait for the user. */
sealed class SendOutcome {
    data class Accepted(val replayed: Boolean) : SendOutcome()
    data class Retryable(val status: Int?, val code: String?, val message: String) : SendOutcome()
    data class Permanent(val status: Int, val code: String?, val message: String) : SendOutcome()
}

object OutboxRequest {
    /** Tower trims the body before verifying the kind 33358 content, so the client signs the trimmed text. */
    fun normalizedBody(text: String): String = text.trim()

    /**
     * Builds the exact JSON bytes for `POST /channels/:c/messages`, including the kind 33358 instruction signature over
     * `body` with the workspace, channel and (for replies) thread tags. These bytes are frozen and reused for every retry.
     */
    suspend fun build(item: OutboxItem, signer: EventSigner, inheritedAgentRecipient: String? = null): ByteArray {
        val signed = MessageInstructionSignature.sign(item.body, item.workspaceId, item.channelId, item.threadId, signer = signer)
        if (signed.signerNpub != item.signerNpub) throw IdentityError.SignerMismatch
        val o = LinkedHashMap<String, Any?>()
        o["body"] = item.body
        o["client_request_id"] = item.id
        o["message_signature"] = signed.requestObject
        if (item.threadId != null) o["thread_id"] = item.threadId else o["create_thread"] = true
        if (item.mentions.isNotEmpty()) o["mentions"] = item.mentions.map { it.requestObject }
        val wire = item.attachments.mapNotNull { it.wire }
        if (wire.size != item.attachments.size || !item.attachments.all { it.complete }) throw FileTransferError.NotAttachable
        // Storage links in the body are attachments too (the web's chat storage attachment merge).
        val attachments = StorageMarkdown.merged(wire, item.body)
        val metadata = linkedMapOf<String, Any?>()
        if (attachments.isNotEmpty()) metadata["attachments"] = attachments.map { it.requestObject }
        if (item.threadId != null && item.mentions.isEmpty() && inheritedAgentRecipient != null &&
            studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(inheritedAgentRecipient)) {
            metadata["agent_direct_recipient_npub"] = inheritedAgentRecipient
        }
        if (metadata.isNotEmpty()) o["metadata"] = metadata
        return jsonBody(jsonObjectOf(*o.toList().toTypedArray()))
    }

    /**
     * Classifies Tower's answer. Codes follow the route: `idempotency_conflict` (409), `thread_not_found` /
     * `channel_not_found` (404), `validation_error` (400) and authorization denials (403) are permanent.
     */
    fun classify(status: Int, body: ByteArray, attempts: Int): SendOutcome {
        val o = parseJsonObjectOrNull(body)
        if (status in 200..299) return SendOutcome.Accepted(o?.bool("replayed") == true)
        val code = o?.string("code")
        val field = o?.obj("details")?.objects("fields")?.firstOrNull()?.string("message")
        return when {
            status == 409 && code == "idempotency_conflict" -> SendOutcome.Permanent(
                status, code, "Tower already has a different message with this request. Edit and send it as a new message.",
            )
            status == 404 && code == "thread_not_found" -> SendOutcome.Permanent(status, code, "This thread no longer exists.")
            status == 404 && code == "channel_not_found" -> SendOutcome.Permanent(status, code, "This channel no longer exists.")
            status == 403 -> SendOutcome.Permanent(status, code, "You do not have permission to post here.")
            status == 401 && attempts < 3 -> SendOutcome.Retryable(status, code, "Tower did not accept the signature; retrying with a fresh one.")
            status == 401 -> SendOutcome.Permanent(status, code, "Tower did not accept this identity's signature.")
            status == 408 || status == 425 || status == 429 || status in 500..599 ->
                SendOutcome.Retryable(status, code, "Tower is unavailable (HTTP $status); will retry.")
            else -> SendOutcome.Permanent(status, code, field?.let { "Tower rejected the message: $it" } ?: "Tower rejected the message (HTTP $status).")
        }
    }
}

/**
 * Sends the durable outbox through [SyncEngine] at `USER` priority (ahead of catch-up, never in its slots).
 *
 * - Only rows for this connection, origin and the unlocked signer are sent; each request is built from the row, never
 *   from what the screen currently shows.
 * - A row is signed once (explicit unlock required) and its exact bytes are frozen; each attempt gets a fresh NIP-98
 *   header over those bytes. Tower's `client_request_id` replay makes a resend after a lost response safe.
 * - Order is kept per conversation: after a retryable failure later rows of that conversation wait for the next pass.
 * - A synced copy carrying the row's `client_request_id` and signer retires the row (the store does this on write).
 */
class OutboxSender(
    val store: LocalStore,
    val engine: SyncEngine,
    val identity: IdentityService,
    val transport: HttpTransport,
    /** Directory holding staged attachment bytes (privacy-stripped images) until their message is accepted. */
    val staging: File? = null,
    /** Out-of-process transfers for presigned attachment uploads (null: in-process). */
    val transfers: ObjectTransfers? = null,
    private val random: () -> Double = { Math.random() },
) {
    companion object {
        fun key(connection: ConnectionRecord): String = "outbox:${connection.id}"

        /** User-facing text for a transport failure (shared with the write queue and document saves). */
        fun networkMessage(error: Throwable): String = DocumentSaver.networkMessage(error)

        /** Reads Tower's message (and thread) from a 200/201 reply. A new conversation's message is its thread's source. */
        fun acknowledgement(body: ByteArray, item: OutboxItem): Pair<MessageRecord, ThreadRecord?>? {
            val o = parseJsonObjectOrNull(body) ?: return null
            val row = o.obj("message") ?: return null
            val message = runCatching { MessageRecord.parse(row) }.getOrNull() ?: return null
            if (message.workspaceId != item.workspaceId || message.channelId != item.channelId) return null
            val thread = o.obj("thread")?.let { runCatching { ThreadRecord.parse(it) }.getOrNull() }
            var source = message.threadSourceId
            if (source == null && thread != null && thread.id == message.threadId) source = thread.sourceMessageId ?: (if (item.createThread) message.id else null)
            if (source == null && item.createThread && message.threadId != null) source = message.id
            val resolved = message.copy(
                threadSourceId = source, senderNpub = message.senderNpub ?: item.signerNpub, clientRequestId = message.clientRequestId ?: item.id,
            )
            return resolved to thread
        }
    }

    /**
     * Writes privacy-stripped image bytes into the staging directory and returns the attachment to send with a message.
     * (The app strips image metadata before handing the bytes over.)
     */
    fun stage(bytes: ByteArray, filename: String, mimeType: String = "image/png"): PendingAttachment {
        val directory = staging ?: throw FileTransferError.NotAttachable
        directory.mkdirs()
        val attachment = PendingAttachment(filename = filename, mimeType = mimeType, sizeBytes = bytes.size.toLong(), sha256 = Sha256Hex.of(bytes),
            staged = "")
        val named = attachment.copy(staged = "attachment-${attachment.id}")
        File(directory, named.staged).writeBytes(bytes)
        return named
    }

    /**
     * Stages a text attachment (pasted or edited) as its exact UTF-8 bytes in the staging directory. The file is
     * written and flushed before the caller records it in the draft, so a stored draft never names missing bytes.
     */
    fun stageText(text: String, filename: String, source: String): PendingAttachment {
        val directory = staging ?: throw FileTransferError.NotAttachable
        val bytes = TextAttachments.bytes(text)
        if (bytes.size > TextAttachments.MAX_BYTES) throw FileTransferError.TextTooLarge
        directory.mkdirs()
        val base = PendingAttachment(
            filename = TextAttachments.normalizeName(filename), mimeType = TextAttachments.CONTENT_TYPE, sizeBytes = bytes.size.toLong(),
            sha256 = Sha256Hex.of(bytes), staged = "", kind = "file", text = TextAttachmentInfo.of(text, source),
        )
        val named = base.copy(staged = "attachment-${base.id}")
        val target = File(directory, named.staged)
        val temp = File(directory, named.staged + ".tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(target)) { temp.delete(); throw FileTransferError.NotAttachable }
        return named
    }

    /** The staged text of an attachment (bounded by [TextAttachments.MAX_BYTES]); null when its bytes are gone. */
    fun readStagedText(attachment: PendingAttachment): String? {
        val directory = staging ?: return null
        val file = File(directory, attachment.staged)
        if (!file.isFile || file.length() > TextAttachments.MAX_BYTES) return null
        val bytes = file.readBytes()
        if (Sha256Hex.of(bytes) != attachment.sha256) return null
        return TextAttachments.text(bytes)
    }

    /**
     * Adds pasted text to one composer's durable attachments. The bytes are staged first, then the draft row is updated in
     * a single writer step that also picks the next free `Pasted text N.txt` name. Returns the stored attachment.
     */
    suspend fun addDraftText(target: ChatTarget, text: String, source: String = TextAttachmentInfo.SOURCE_PASTE): PendingAttachment {
        val staged = stageText(text, "${PastedTextPolicy.BASE_NAME}.${PastedTextPolicy.EXTENSION}", source)
        var stored = staged
        try {
            store.writer.editDraftAttachments(target) { list ->
                stored = staged.copy(filename = PastedTextPolicy.nextName(list.map { it.filename }))
                list + stored
            }
        } catch (e: Throwable) { removeStaged(listOf(staged)); throw e }
        return stored
    }

    /** Removes one staged attachment from the composer and deletes its bytes. */
    suspend fun removeDraftText(target: ChatTarget, id: String) {
        var removed: PendingAttachment? = null
        store.writer.editDraftAttachments(target) { list -> list.filter { if (it.id == id) { removed = it; false } else true } }
        removed?.let { removeStaged(listOf(it)) }
    }

    /** Saves an edited composer attachment: new exact bytes (and name) replace the old ones in place. */
    suspend fun replaceDraftText(target: ChatTarget, id: String, text: String, filename: String): PendingAttachment? {
        val old = store.reader.draft(target)?.attachments?.firstOrNull { it.id == id } ?: return null
        val fresh = stageText(text, filename, old.text?.source ?: TextAttachmentInfo.SOURCE_PASTE)
        var replaced: PendingAttachment? = null
        try {
            store.writer.editDraftAttachments(target) { list ->
                list.map { if (it.id == id) fresh.copy(id = id).also { n -> replaced = n } else it }
            }
        } catch (e: Throwable) { removeStaged(listOf(fresh)); throw e }
        if (replaced == null) { removeStaged(listOf(fresh)); return null }
        removeStaged(listOf(old))
        return replaced
    }

    /** Deletes the staged bytes of attachments the composer dropped (never those of a queued message). */
    fun dropStaged(attachments: List<PendingAttachment>) = removeStaged(attachments)

    /** Composes a message into the outbox (clearing that draft) and starts sending if unlocked. */
    suspend fun send(
        text: String, mentions: List<Mention>, target: ChatTarget, connection: ConnectionRecord, workspace: WorkspaceRecord,
        attachments: List<PendingAttachment> = emptyList(),
    ): OutboxItem? {
        if (target.connectionId != connection.id || target.workspaceId != workspace.id) throw TowerError.UnsafePath
        val encoded = MentionCodec.encode(text, mentions)
        val body = OutboxRequest.normalizedBody(encoded.body)
        if (body.isEmpty() && attachments.isEmpty()) return null
        // Staged bytes must still be on the device before the message is accepted into the outbox.
        if (attachments.any { it.staged.isEmpty() || !File(staging ?: throw FileTransferError.NotAttachable, it.staged).isFile }) throw FileTransferError.NotAttachable
        // A pasted item still uploading would otherwise be sent as its placeholder text.
        if (UploadToken.containsAny(body)) throw ComposerUploadException(ComposerUploadError.UNRESOLVED)
        val item = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, body, encoded.mentions, attachments)
        kick(connection)
        return item
    }

    /** Removes an unsent message and its staged attachment bytes. */
    suspend fun discard(id: String, restoreDraft: Boolean) {
        val item = store.reader.outboxItem(id)
        store.writer.discardOutbox(id, restoreDraft)
        // Text attachments of a restored draft keep their staged bytes; everything else is removed with the row.
        if (store.reader.outboxItem(id) == null) removeStaged(item?.attachments?.filter { !(restoreDraft && it.isText) } ?: emptyList())
    }

    private fun removeStaged(attachments: List<PendingAttachment>) {
        val directory = staging ?: return
        for (a in attachments) if (a.staged.isNotEmpty()) File(directory, a.staged).delete()
    }

    /** Uploads a row's staged images (prepare → upload → complete, resumable), recording progress on the row. */
    private suspend fun uploadAttachments(item: OutboxItem, api: TowerAPI): OutboxItem {
        if (item.attachments.all { it.complete }) return item
        val directory = staging ?: throw FileTransferError.NotAttachable
        val attachments = item.attachments.toMutableList()
        for (index in attachments.indices) {
            val a = attachments[index]
            if (a.complete) continue
            val file = File(directory, a.staged)
            if (!file.isFile) throw FileTransferError.NotAttachable
            val bytes = file.readBytes()
            if (Sha256Hex.of(bytes) != a.sha256) throw FileTransferError.Integrity
            val previous = a.objectId?.let { id -> a.uploadURL?.let { PreparedObject(id, it) } }
            val current = attachments.toList()
            val objectId = api.store(item.workspaceId, item.appNpub, bytes, a.mimeType, a.filename, previous) { fresh ->
                val next = current.toMutableList()
                next[index] = next[index].copy(objectId = fresh.objectId, uploadURL = fresh.uploadURL)
                store.writer.updateOutboxAttachments(item.id, next)
            }
            attachments[index] = a.copy(objectId = objectId, complete = true)
            store.writer.updateOutboxAttachments(item.id, attachments)
        }
        return OutboxItem(item.id, item.connectionId, item.origin, item.signerNpub, item.workspaceId, item.appNpub, item.channelId, item.threadId,
            item.createThread, item.body, item.mentions, item.request, item.state, item.attempts, item.lastStatus, item.lastCode, item.lastError,
            item.createdAt, attachments)
    }

    /** User Retry: back to the queue (or to signing, if never signed), sent at once. */
    suspend fun retry(id: String, connection: ConnectionRecord) {
        store.writer.retryOutbox(id)
        kick(connection, force = true)
    }

    /** Schedules a pass over this connection's outbox (after unlock, on reconnect, or as the automatic retry). */
    suspend fun kick(connection: ConnectionRecord, force: Boolean = false, delayMillis: Long? = null) {
        engine.enqueue(key(connection), SyncPriority.USER, force, delayMillis) { drain(connection) }
    }

    /** One pass over the outbox. Locked or mismatched identity: nothing is signed or sent; rows simply wait. */
    internal suspend fun drain(connection: ConnectionRecord) {
        val state = identity.state()
        if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) return
        val npub = state.npub
        val client = TowerClient(TowerOrigin(connection.origin), transport, identity)
        val blocked = HashSet<String>()     // conversations waiting behind a retryable failure
        var nextRetry: Int? = null
        fun scheduleRetry(attempts: Int) { nextRetry = minOf(nextRetry ?: Int.MAX_VALUE, attempts) }
        for (item in store.reader.outbox(connection.id, npub)) {
            currentCoroutineContext().ensureActive()
            if (item.origin != connection.origin || item.signerNpub != npub) continue
            if (store.reader.workspaces(connection.id).any { it.id == item.workspaceId && it.accessRemoved } ||
                store.reader.isChannelAccessRemoved(item.workspaceId, item.channelId) ||
                item.threadId?.let { store.reader.isAccessRemoved(item.workspaceId, FlaggedTable.THREADS, it) } == true) {
                store.writer.markOutbox(item.id, OutboxState.FAILED, code = "access_removed", error = "No longer available to you. Your unsent message is kept on this device.")
                continue
            }
            val conversation = item.target.draftKey
            if (item.state == OutboxState.FAILED || item.state == OutboxState.SENDING) continue
            if (conversation in blocked) continue
            var request = item.request
            if (request == null) {
                identity.requireSigner(item.signerNpub)
                val ready = try {
                    uploadAttachments(item, TowerAPI(client, transfers))
                } catch (e: Throwable) {
                    if (e is CancellationException || !currentCoroutineContext().isActive) throw cancellation(e)
                    val permanent = e is FileTransferError
                    store.writer.markOutbox(
                        item.id, if (permanent) OutboxState.FAILED else OutboxState.RETRYING,
                        error = "Attachment upload: " + networkMessage(e), countAttempt = true,
                    )
                    blocked.add(conversation)
                    if (!permanent) scheduleRetry(item.attempts + 1)
                    continue
                }
                val inherited = ready.threadId?.let { store.reader.thread(ready.workspaceId, it)?.raw?.obj("metadata")?.string("inherited_agent_recipient_npub") }
                val built = OutboxRequest.build(ready, identity, inherited)
                store.writer.freezeOutbox(item.id, built)
                request = store.reader.outboxItem(item.id)?.request
            }
            if (request == null) continue
            store.writer.markOutbox(item.id, OutboxState.SENDING, item.lastStatus, item.lastCode, item.lastError)
            val reply = try {
                client.exchange("POST", TowerAPI.base(item.workspaceId) + "/channels/" + TowerAPI.uuid(item.channelId) + "/messages", item.appNpub, request)
            } catch (e: Throwable) {
                if (e is CancellationException || !currentCoroutineContext().isActive) {
                    // Interrupted (lock, workspace removal, app suspension): the request may have arrived; replay is safe.
                    withContext(NonCancellable) {
                        runCatching { store.writer.markOutbox(item.id, OutboxState.QUEUED, item.lastStatus, item.lastCode, item.lastError) }
                    }
                    throw cancellation(e)
                }
                store.writer.markOutbox(item.id, OutboxState.RETRYING, error = networkMessage(e), countAttempt = true)
                blocked.add(conversation)
                scheduleRetry(item.attempts + 1)
                continue
            }
            when (val outcome = OutboxRequest.classify(reply.status, reply.body, item.attempts + 1)) {
                is SendOutcome.Accepted -> {
                    val ack = acknowledgement(reply.body, item)
                    if (ack == null) {
                        store.writer.markOutbox(item.id, OutboxState.RETRYING, error = "Tower's reply could not be read; will check again.", countAttempt = true)
                        blocked.add(conversation)
                        scheduleRetry(item.attempts + 1)
                        continue
                    }
                    store.writer.acknowledgeSend(item.id, ack.first, ack.second)
                    removeStaged(item.attachments)
                }
                is SendOutcome.Retryable -> {
                    store.writer.markOutbox(item.id, OutboxState.RETRYING, outcome.status, outcome.code, outcome.message, countAttempt = true)
                    blocked.add(conversation)
                    scheduleRetry(item.attempts + 1)
                }
                is SendOutcome.Permanent ->
                    store.writer.markOutbox(item.id, OutboxState.FAILED, outcome.status, outcome.code, outcome.message, countAttempt = true)
            }
        }
        nextRetry?.let { kick(connection, delayMillis = SyncBackoff.delayMillis(it, random())) }
    }

    private fun cancellation(e: Throwable): CancellationException = e as? CancellationException ?: CancellationException("cancelled").also { it.initCause(e) }
}
