package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.doc
import studio.otherstuff.ambulando.core.store.docBody
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.store.insertDocDraftIfAbsent
import studio.otherstuff.ambulando.core.store.replaceDocDraftIfUnchanged
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.flexibleInt

/** The extra payload keys of a "create and link" document write, and the checks made before one is queued. */
object WikiCreate {
    /** The document whose `[[Title]]` asked for the page. */
    const val SOURCE_KEY = "wiki_source_doc"
    const val TITLE_KEY = "wiki_title"
    /** Which matching reference in the source (document order) was opened; absent: every reference with that title. */
    const val OCCURRENCE_KEY = "wiki_occurrence"
    /**
     * How many title-only references with that title the source had when the page was requested. If the count has changed by the
     * time the page exists, the position of the opened reference can no longer be trusted, so nothing is bound by guessing.
     */
    const val EXPECTED_COUNT_KEY = "wiki_expected_count"
    /** The screen that asked, so the page can be opened for the person only if they are still there. */
    const val REQUEST_KEY = "wiki_request"
    /** Written once the page exists: where to find it if the link could not be added yet. */
    const val CREATED_KEY = "wiki_created_doc"
    /** Written once the link has been recorded in the source, so a retry never binds a second reference. */
    const val BOUND_KEY = "wiki_bound"

    /**
     * What already answers a request to create [title] in a channel: a saved page with that title there, or a creation still
     * waiting in the write queue. Either way no second page is made.
     */
    private val ACTIVE_STATES = setOf(WriteState.QUEUED, WriteState.SENDING, WriteState.RETRYING)

    sealed class Existing {
        data class Page(val doc: DocRecord) : Existing()
        data class Pending(val item: WriteItem) : Existing()
        /** Several saved pages already have the title there: the person picks one, nothing is chosen or created arbitrarily. */
        data class Several(val docs: List<DocRecord>) : Existing()
    }

    fun existing(title: String, channelId: String, docs: List<DocRecord>, pending: List<WriteItem>): Existing? {
        val wanted = WikiLinks.normalized(title)
        // A failed or conflicted creation is not "being created": the person can ask for the page again.
        pending.firstOrNull {
            it.kind == WriteKind.DOC_CREATE && it.state in ACTIVE_STATES && (it.targetId == channelId || it.string("channel_id") == channelId) &&
                WikiLinks.normalized(it.string("title") ?: "") == wanted
        }?.let { return Existing.Pending(it) }
        val same = docs.filter { !it.archived && it.channelId == channelId && WikiLinks.normalized(it.title) == wanted }
        return when (same.size) {
            0 -> null
            1 -> Existing.Page(same[0])
            else -> Existing.Several(same)
        }
    }

    /** Extra keys for the creation write that will link the page back into its source. */
    fun linkPayload(sourceDocId: String, title: String, occurrence: Int?, expectedCount: Int? = null, request: String? = null): JsonObject = buildJsonObject {
        put(SOURCE_KEY, sourceDocId)
        put(TITLE_KEY, title)
        occurrence?.let { put(OCCURRENCE_KEY, it) }
        expectedCount?.let { put(EXPECTED_COUNT_KEY, it) }
        request?.let { put(REQUEST_KEY, it) }
    }
}

/** The source changed while the link was being added; the caller keeps its write queued and tries again on the new text. */
sealed class WikiBindError(message: String) : Exception(message, null, false, false), UserFacingError {
    override val userMessage: String get() = message ?: ""
    data object SourceChanged : WikiBindError("The source document changed while the link was being added.")
    /** The source document is not saved on this device (or its format cannot be edited), so the link could not be added yet. */
    data object SourceUnavailable : WikiBindError("The source document is not saved on this device, so the link could not be added yet.")
    data object NotReady : WikiBindError("The page was created; its link will be added when the document is ready.")
}

sealed class WikiBindOutcome {
    /** The source already links to this page: nothing to do. */
    data object AlreadyBound : WikiBindOutcome()
    /**
     * The source's references with that title changed after the page was requested (one was removed, added or linked elsewhere),
     * so the opened reference cannot be identified; nothing was changed. The created page still exists.
     */
    data object NoLongerRequested : WikiBindOutcome()
    /** The source now holds the link and Tower has the saved document. */
    data object Saved : WikiBindOutcome()
    /** The source holds the link in its draft on this device and has not reached Tower yet (offline, lease held, newer version). */
    data class KeptInDraft(val message: String) : WikiBindOutcome()
    /** The source document is not saved on this device, so nothing could be changed here. */
    data object SourceNotSaved : WikiBindOutcome()
}

/** How a page-level binding ends: a link only counts as recorded once the text that holds it is durable. */
enum class WikiEditorBinding {
    BOUND, NOTHING_TO_BIND, NO_LONGER_REQUESTED, FAILED;

    companion object {
        /** [pageCount]: how many references the page bound (-1: they changed since the request, none bound); [stored]: whether the text that holds them is durable. */
        fun outcome(pageCount: Int, stored: Boolean): WikiEditorBinding = when {
            !stored -> FAILED
            pageCount < 0 -> NO_LONGER_REQUESTED
            pageCount == 0 -> NOTHING_TO_BIND
            else -> BOUND
        }
    }
}

/**
 * Puts a created or chosen page's id into the document that asked for it, through the same draft-first save path as editing,
 * so a failed save keeps the link instead of losing it. The source is never changed while its editor is open here; that editor
 * receives the link itself (the app checks first).
 */
object WikiBinder {
    /**
     * What the creation write asked for, applied to a source that has no open editor. A source that cannot be changed is an error
     * (the write stays queued with the page's location, never retired as if linked); a source whose references changed since the
     * request is reported, not guessed at.
     */
    suspend fun complete(
        item: WriteItem, created: DocRecord, connection: ConnectionRecord, workspace: WorkspaceRecord, store: LocalStore, authority: DocumentAuthority,
    ): WikiBindOutcome {
        val source = item.string(WikiCreate.SOURCE_KEY)
        val title = item.string(WikiCreate.TITLE_KEY)
        if (source == null || title == null) return WikiBindOutcome.AlreadyBound
        val payload = item.payloadObject
        val outcome = bind(title, created.id, flexibleInt(payload[WikiCreate.OCCURRENCE_KEY]), flexibleInt(payload[WikiCreate.EXPECTED_COUNT_KEY]),
            source, connection, workspace, store, authority)
        if (outcome == WikiBindOutcome.SourceNotSaved) throw WikiBindError.SourceUnavailable
        return outcome
    }

    suspend fun bind(
        title: String, documentId: String, occurrence: Int?, expectedCount: Int? = null, sourceDocId: String, connection: ConnectionRecord,
        workspace: WorkspaceRecord, store: LocalStore, authority: DocumentAuthority,
    ): WikiBindOutcome {
        val key = DocDraft.key(connection.id, workspace.id, sourceDocId)
        val existingDraft = store.reader.docDraft(key)
        val cached = store.reader.docBody(workspace.id, sourceDocId)
        val source = existingDraft?.let { it.body to it.contentType } ?: cached?.let { it.bytes to it.contentType } ?: return WikiBindOutcome.SourceNotSaved
        val body = DocumentBody(source.first, source.second)
        val references = WikiLinks.references(body)
        if (references.any { it.documentId == documentId }) return WikiBindOutcome.AlreadyBound
        if (expectedCount != null) {
            val current = references.count { it.documentId == null && WikiLinks.normalized(it.title) == WikiLinks.normalized(title) }
            if (current != expectedCount) return WikiBindOutcome.NoLongerRequested
        }
        val encoded: EncodedBody
        val state = body.editorState
        if (state != null) {
            val bound = WikiLinks.binding(title, documentId, state, occurrence) ?: return WikiBindOutcome.AlreadyBound
            DocumentBody.checkEdited(bound, state)
            encoded = body.encoded(bound)
        } else if (body.isEditable) {
            val bound = WikiLinks.binding(title, documentId, body.markdown, occurrence) ?: return WikiBindOutcome.AlreadyBound
            encoded = body.encoded(bound)
        } else return WikiBindOutcome.SourceNotSaved
        val draft: DocDraft = when {
            existingDraft != null -> DocDraft(existingDraft.connectionId, existingDraft.workspaceId, existingDraft.docId, existingDraft.title, encoded.bytes,
                encoded.contentType, existingDraft.baseRowVersion, existingDraft.baseSha256, existingDraft.baseStorageObjectId, existingDraft.state,
                existingDraft.lastError, existingDraft.recoveryId)
            cached != null -> {
                val doc = store.reader.doc(workspace.id, sourceDocId)
                DocDraft(connection.id, workspace.id, sourceDocId, doc?.title ?: "Untitled document", encoded.bytes, encoded.contentType,
                    cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId, DocDraft.State.EDITING)
            }
            else -> return WikiBindOutcome.SourceNotSaved
        }
        // Written only if nothing changed the draft since it was read: edits made meanwhile are never overwritten.
        val stored = if (existingDraft != null) store.writer.replaceDocDraftIfUnchanged(existingDraft, draft) else store.writer.insertDocDraftIfAbsent(draft)
        if (!stored) throw WikiBindError.SourceChanged
        // Someone's own unsaved edits are not uploaded behind their back; a clean source is saved right away.
        if (existingDraft != null) return WikiBindOutcome.KeptInDraft(existingDraft.lastError ?: "The link was added to your unsaved edits on this device.")
        return when (val result = authority.save(draft, null)) {
            is DocSaveResult.Saved -> WikiBindOutcome.Saved
            is DocSaveResult.Recovery -> WikiBindOutcome.KeptInDraft(result.message)
            is DocSaveResult.LeaseHeld -> WikiBindOutcome.KeptInDraft(result.message)
            is DocSaveResult.NotSaved -> WikiBindOutcome.KeptInDraft(result.message)
        }
    }
}

/**
 * Where a person was when they asked for something that needs a moment (storing their text, creating a page): the account, the
 * workspace, whether the source screen is showing, and a counter that changes every time that screen is left.
 */
data class WikiVisit(
    val connectionId: String?,
    val workspaceId: String,
    val visible: Boolean,
    val epoch: Int,
    val actorId: String? = null,
    val contextEpoch: Int = 0,
)

object WikiNavigation {
    enum class Result {
        Opened,
        /** The text could not be stored on the device, so the screen was not left. */
        DraftNotStored,
        /** During the wait the person left the screen, switched workspace or account, or came back to a new visit: nothing opens. */
        LeftTheScreen,
    }

    /**
     * Stores the person's text, then opens a page, but only if they are still where they asked. The check is made after the wait,
     * so a slow store can never carry someone to a page they have since walked away from.
     */
    suspend fun open(documentId: String, start: WikiVisit, now: () -> WikiVisit, checkpoint: suspend () -> Boolean, navigate: (String) -> Unit): Result {
        if (!checkpoint()) return Result.DraftNotStored
        val current = now()
        if (!current.visible || current != start) return Result.LeftTheScreen
        navigate(documentId)
        return Result.Opened
    }
}
