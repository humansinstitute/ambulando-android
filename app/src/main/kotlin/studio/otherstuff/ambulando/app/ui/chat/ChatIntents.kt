package studio.otherstuff.ambulando.app.ui.chat

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import studio.otherstuff.ambulando.app.ui.nav.ThreadFocus
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.Draft
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.PendingAttachment
import studio.otherstuff.ambulando.core.transport.MessageAttachment
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ReactionEmoji

/**
 * Everything the chat screens ask of the app: writes (the outbox, message writes, drafts), refreshes and uploads. The
 * screens never touch the network or the sync engine; the app model implements this on top of OutboxSender,
 * WriteSender, WorkspaceSync and the composer upload queue. Each method names the iOS `AppModel` / sender call it
 * mirrors. Writes are bound to the target the screen captured, never to the current selection.
 */
interface ChatIntents {
    // MARK: Composer and outbox

    /**
     * Hands composed text to the outbox (`OutboxSender.send(text:mentions:target:connection:workspace:attachments:)`),
     * which encodes the mentions, normalises the body and clears the stored draft in the same transaction. Throws when
     * the outbox write failed (for example storage full); the composer then puts the text back. Must refuse a target
     * whose connection or workspace is not the current one.
     */
    suspend fun send(target: ChatTarget, text: String, mentions: List<Mention>, attachments: List<PendingAttachment> = emptyList())

    /** Saves (or clears) the composer state for one target (`store.writer.saveDraft`). Calls arrive in order. */
    suspend fun saveDraft(target: ChatTarget, draft: Draft)

    /** User retry of a failed or retrying send (`OutboxSender.retry(id, connection:)`). */
    fun retryOutbox(id: String)

    /** Edit of a rejected send: removes it and returns its text to its composer (`outbox.discard(id, restoreDraft: true)`). */
    fun editOutbox(id: String)

    /** Drops an unsent message (`outbox.discard(id, restoreDraft: false)`). */
    fun discardOutbox(id: String)

    // MARK: Message writes (WriteSender)

    /** `submit(WriteIntent(kind: .reactionAdd, targetId: message.id, payload: [target_type: message, emoji]))`. */
    fun react(message: MessageRecord, emoji: ReactionEmoji)

    /** `submit(WriteIntent(kind: .reactionRemove, targetId: message.id, payload: [reaction_id]))`. */
    fun unreact(message: MessageRecord, reactionId: String)

    /**
     * Signed kind 33358 revision: `submit(WriteIntent(kind: .messageEdit, targetId: message.id, payload: [channel_id,
     * thread_id, body, row_version, mentions]))`. `body` is already mention-encoded and trimmed. Returns whether the write
     * was queued (the sheet stays open otherwise).
     */
    fun editMessage(message: MessageRecord, body: String, mentions: List<Mention>): Boolean

    /** `submit(WriteIntent(kind: .messageDelete, targetId: message.id, payload: [row_version]))`. */
    fun deleteMessage(message: MessageRecord)

    /** Drops an unfinished write (an unsent reaction, edit or delete) (`store.writer.discardWrite`). */
    fun discardWrite(id: String)

    /** Retries a failed write, or applies it anyway after a conflict (`store.writer.retryWrite(id, force)` + sender kick). */
    fun retryWrite(id: String, force: Boolean)

    // MARK: Reads and refreshes

    /** Marks a conversation read (`markViewed(.thread, id, in:)` when its view state is unread or needs attention). */
    fun markRead(target: ChatTarget)

    /** Catch-up for an opened channel (`openChannel(channelId, in:, force:)`); `force` is pull-to-refresh. */
    fun refreshChannel(target: ChatTarget, force: Boolean)

    /** Catch-up for an opened thread (`openThread(threadId, channelId:, in:)`). */
    fun refreshThread(target: ChatTarget)

    /** A thread known only by id (a pin, a mention): fetch its record (`ensureThread(id, in:)`). */
    fun ensureThread(workspaceId: String, threadId: String)

    /** Reactions for the messages on screen (`refreshReactions(workspace, scope:, messageIds:)`). */
    fun refreshReactions(workspaceId: String, scope: String, messageIds: List<String>)

    /** The member directory, when the mention picker opens (`refreshDirectory(workspace)`). */
    fun refreshDirectory(workspaceId: String)

    /** Opens a conversation in the focused thread view (`focusThread`). */
    fun openThread(focus: ThreadFocus)

    /** A non-image attachment: open the cached copy, or download it first (`downloadAttachment(objectId, in:)`). */
    fun openAttachment(workspaceId: String, attachment: MessageAttachment)

    // MARK: Uploads (ComposerUploads)

    /**
     * An explicit paste of images or files into one composer (`ComposerUploads.paste(providers, target:, appNpub:,
     * context:)`): uploads start for that target and the returned placeholders ([UploadToken]) go in at the caret. An
     * empty result leaves it an ordinary text paste.
     */
    fun pasteItems(target: ChatTarget, items: List<PastedItem>): List<String>

    fun retryUpload(id: String)
    fun removeUpload(id: String)

    /** Live uploads and the notice for a target (`uploads(for:)`, `notice(for:)`). */
    fun uploads(target: ChatTarget): StateFlow<ComposerUploadsState>

    /** Finished uploads land in this composer while it is shown (`ComposerUploads.attach` / `detach`). */
    fun attachComposer(host: UploadTokenHost)
    fun detachComposer(host: UploadTokenHost)

    /**
     * Stages one photo from the picker for the next message (`ImagePrivacy.stage`). `bytes` are already privacy-stripped
     * by `ImagePrivacy.strippedPng`. Returns null when staging failed.
     */
    suspend fun pickPhoto(target: ChatTarget, bytes: ByteArray, name: String, mimeType: String): PendingAttachment?

    /** A staged photo removed from the composer before sending (its staged file is deleted). */
    fun removePhoto(attachment: PendingAttachment)
}

/** One image or file from a paste (the content receiver's clip items that carry a URI). */
@Immutable
data class PastedItem(val uri: Uri, val mimeType: String?, val displayName: String? = null)

/** An item pasted into a composer while it uploads (iOS `ComposerUpload`). */
@Immutable
data class ComposerUploadRow(
    val id: String,
    val target: ChatTarget,
    val kind: UploadToken.Kind,
    val filename: String,
    val phase: Phase,
    /** False when the staged bytes are gone (only Remove is left). */
    val retryable: Boolean = true,
) {
    sealed interface Phase {
        data object Preparing : Phase
        data object Waiting : Phase
        data object Uploading : Phase
        data class Failed(val message: String) : Phase
        data class Done(val objectId: String) : Phase
    }

    val token: String get() = UploadToken.make(kind, id)
    val isActive: Boolean get() = phase == Phase.Preparing || phase == Phase.Waiting || phase == Phase.Uploading
    val isFailed: Boolean get() = phase is Phase.Failed
}

@Immutable
data class ComposerUploadsState(val uploads: List<ComposerUploadRow> = emptyList(), val notice: String? = null)

/** A live composer that finished uploads write into (iOS `UploadTokenHost`). */
interface UploadTokenHost {
    val uploadTarget: ChatTarget
    val uploadText: String
    /** Replaces the first `token` in the composer text, keeping the caret. False when the text no longer holds it. */
    fun replaceUploadToken(token: String, replacement: String): Boolean
}

/** The app's state the chat screens show beside stored rows. */
@Immutable
data class ChatStatus(
    /** Requests can be signed (identity unlocked) and a connection exists. */
    val canRead: Boolean = false,
    /** Sync job errors by key ([ChatSyncKeys]). */
    val syncErrors: Map<String, String> = emptyMap(),
)

/** Sync job keys, as the iOS sync engine names them. */
object ChatSyncKeys {
    fun messages(workspaceId: String, channelId: String) = "messages:$workspaceId:$channelId"
    fun thread(workspaceId: String, threadId: String) = "thread:$workspaceId:$threadId"
    fun threadRecord(workspaceId: String, threadId: String) = "thread-record:$workspaceId:$threadId"
    fun attachment(workspaceId: String, objectId: String) = "attachment:$workspaceId:$objectId"
}

/**
 * What every chat screen is drawn with, provided once by the shell: the local store (reads only), the intents, live
 * status and the signed storage-image renderer (null where images cannot be loaded).
 */
class ChatEnvironment(
    val store: LocalStore,
    val intents: ChatIntents,
    val status: StateFlow<ChatStatus> = MutableStateFlow(ChatStatus()),
    /** Draws a stored image (`storage://<uuid>`); show [StorageImagePlaceholder] states while it loads. */
    val storageImage: (@Composable (objectId: String, alt: String) -> Unit)? = null,
)

val LocalChatEnvironment = staticCompositionLocalOf<ChatEnvironment> { error("ChatEnvironment not provided") }
