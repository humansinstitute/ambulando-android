package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.deleteDocDraftIfUnchanged
import studio.otherstuff.ambulando.core.store.doc
import studio.otherstuff.ambulando.core.store.docBody
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.store.insertDocDraftIfAbsent
import studio.otherstuff.ambulando.core.store.loadDocumentReview
import studio.otherstuff.ambulando.core.store.replaceDocDraftIfUnchanged
import studio.otherstuff.ambulando.core.store.saveDocDraft
import studio.otherstuff.ambulando.core.store.saveDocumentReview
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import java.time.Instant
import java.util.UUID

/** How the editor page is driven. The controller never touches a WebView itself. */
interface DocumentPage {
    /** The page's current document (rich: editor-state JSON text; Markdown: text), or null when it cannot answer now. */
    suspend fun snapshot(): String?
    fun load(payload: String)
    fun setEditable(editable: Boolean)

    /**
     * Gives the page's unresolved links titled [title] the created page's id (one [occurrence], or every match when null). Returns
     * how many references were bound; -1 when the references changed since the request (none bound); null when the page cannot answer.
     */
    suspend fun wikiBind(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): Int? = null

    /** Puts a link at [range] (the typed `[[` span) or at the selection. [documentId] null makes a title-only link. Null: the page cannot answer. */
    suspend fun wikiInsert(documentId: String?, title: String, range: WikiRange?): WikiPlacement? = null

    /** The selection as the toolbar link command needs it. */
    suspend fun wikiSelection(): WikiSelection? = null

    /** Current titles of the pages links point at (id → title), so a renamed page shows its new name. */
    fun wikiTitles(titles: Map<String, String>) {}
}

/** A span of the editor document, in the page's own positions. */
data class WikiRange(val from: Int, val to: Int)

/** Which same-title title-only reference a placed link is (reading order; -1 for a bound link) and how many there are now. */
data class WikiPlacement(val occurrence: Int, val count: Int)

data class WikiSelection(val range: WikiRange, val text: String)

/**
 * One open document: working copy, durable draft, edit authority, reconciliation with newer shared versions and the
 * explicit three-way merge. It mirrors the iPhone/Mac editor model so both platforms keep the same promises:
 *
 *  - the working copy is never replaced by a newer download; a persistent notice offers review;
 *  - every destructive step (accept latest, restore, takeover) first preserves the work in the review journal;
 *  - merging needs a verified retained common base and explicit resolution of overlaps;
 *  - applying a merge makes a durable *local* draft; only an explicit Save publishes, with the normal lease, expected
 *    row version and body-hash checks;
 *  - cancellation, offline errors, backgrounding, an expired lease or lost access never retire the draft.
 *
 * Not thread-safe: call it from one dispatcher (the main thread in the app). Its suspension points interleave the way the
 * iPhone model's do, and every compound step re-checks [editRevision] after a suspension so a newer edit always wins.
 */
class DocumentEditController(
    val doc: DocRecord,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
    private val store: LocalStore,
    private val authority: DocumentAuthority,
    private val scope: CoroutineScope,
    private val isUnlocked: () -> Boolean = { true },
    /** True while pasted uploads still have placeholders in the text. */
    private val uploadsPending: () -> Boolean = { false },
    private val newSessionId: () -> String = { UUID.randomUUID().toString().lowercase() },
    private val now: () -> Instant = Instant::now,
    private val reviewDebounceMillis: Long = 200,
    private val draftDebounceMillis: Long = 500,
    private val renewMillis: Long = 60_000,
    private val autosaveMillis: Long = 60_000,
) {
    enum class Tone { INFO, SUCCESS, WARNING, ERROR }

    sealed class Mode {
        data object Loading : Mode()
        data object Rich : Mode()
        data object Markdown : Mode()
        data class Unavailable(val reason: String) : Mode()
    }

    /** The version the working copy is based on. */
    data class Base(val rowVersion: Int, val sha: String, val storageObject: String)

    /** Everything a screen shows, as one immutable value. */
    data class State(
        val mode: Mode,
        val title: String,
        val status: String?,
        val tone: Tone,
        val saving: Boolean,
        val leaseState: String,
        val dirty: Boolean,
        val pageReady: Boolean,
        val takingOver: Boolean,
        val canTakeover: Boolean,
        val concurrentEditing: Boolean,
        val lastSyncedAt: Instant?,
        val reconciling: Boolean,
        val permissionDenied: Boolean,
        val authorityLost: Boolean,
        val remoteVersion: PreservedDocumentSnapshot?,
        val localBaseVersion: Int?,
        val mergeConflicts: List<DocumentMerge.Conflict>,
        val mergeReady: Boolean,
        val mergeText: String,
        val mergeTitle: String,
        val choices: Map<String, String>,
        val retained: List<PreservedDocumentSnapshot>,
        val previousPreviews: List<PreservedMergePreview>,
        val canMerge: Boolean,
        val previewIsStale: Boolean,
        val pendingMergedSave: Boolean,
        val hasReviewWork: Boolean,
        val recoveries: List<DocumentRecovery>,
        val followingRemote: Boolean,
        val editRevision: Long,
    )

    var mode: Mode = Mode.Loading
        private set
    var title: String = doc.title
        private set
    private var content = ""
    var status: String? = null
        private set
    var statusTone: Tone = Tone.INFO
        private set
    var saving = false
        private set
    var leaseState = "Checking the edit lease…"
        private set
    var dirty = false
        private set
    var pageReady = false
        private set
    var takingOver = false
        private set
    var canTakeover = false
        private set
    var concurrentEditing = false
        private set
    var lastSyncedAt: Instant? = null
        private set
    var reconciling = false
        private set
    var permissionDenied = false
        private set
    var authorityLost = false
        private set
    var remoteVersion: PreservedDocumentSnapshot? = null
        private set
    var journal = DocumentReviewJournal()
        private set
    var mergeConflicts: List<DocumentMerge.Conflict> = emptyList()
        private set
    var mergeReady = false
        private set
    var mergeText = ""
        private set
    var mergeTitle = ""
        private set
    var recoveries: List<DocumentRecovery> = emptyList()
        private set
    var editRevision = 0L
        private set

    /** The page's latest text (rich: editor-state JSON; Markdown: text), including upload placeholders the page has reported. */
    val currentContent: String get() = content
    val followingRemote: Boolean get() = authorityLost && !dirty
    val localBaseVersion: Int? get() = base?.rowVersion
    val hasReviewWork: Boolean get() = remoteVersion != null || journal.retained.isNotEmpty() || journal.previewText != null
    val draftKey: String get() = DocDraft.key(connection.id, workspace.id, doc.id)
    val connectionId: String get() = connection.id
    val workspaceId: String get() = workspace.id

    private var editorSessionId = newSessionId()

    /** This editor's session at Tower; it changes only through an explicit takeover or version choice. */
    val sessionId: String get() = editorSessionId
    private var observedLeaseId: String? = null
    private var body: DocumentBody? = null
    private var sourceBytes: ByteArray? = null
    private var original: JsonValue? = null
    private var payload: String? = null
    private var base: Base? = null
    private var loading = true
    private var closed = false
    private var active = true
    private var previewGeneration = 0L
    private var restoringPreview = false
    private var debounce: Job? = null
    private var reviewPersistence: Job? = null
    private var renewJob: Job? = null
    private var autosave: Job? = null
    private var baselineBytes: ByteArray? = null
    private var baselineTitle: String? = null
    var page: DocumentPage? = null

    private val flow = MutableStateFlow(snapshotState())
    val state: StateFlow<State> get() = flow

    private fun target() = DocSaveTarget(connection.id, workspace.id, workspace.appNpub)

    private fun snapshotState() = State(
        mode, title, status, statusTone, saving, leaseState, dirty, pageReady, takingOver, canTakeover, concurrentEditing, lastSyncedAt, reconciling,
        permissionDenied, authorityLost, remoteVersion, base?.rowVersion, mergeConflicts, mergeReady, mergeText, mergeTitle, journal.choices.toMap(),
        journal.retained.toList(), journal.previousPreviews.toList(), canMerge, previewIsStale, journal.pendingMergedSave, hasReviewWork, recoveries,
        followingRemote, editRevision,
    )

    private fun publish() { flow.value = snapshotState() }

    private fun setStatus(text: String?, tone: Tone) { status = text; statusTone = tone; publish() }

    // MARK: Load

    /** A saved draft wins over the cached body: unsaved work is never replaced by a newer download. */
    suspend fun load() {
        val draft = store.reader.docDraft(draftKey)
        val cached = store.reader.docBody(workspace.id, doc.id)
        val currentDoc = store.reader.doc(workspace.id, doc.id)
        val source = draft?.let { it.body to it.contentType } ?: cached?.let { it.bytes to it.contentType }
        if (source == null) {
            mode = Mode.Unavailable("This document's text is not on this device yet. Go back, unlock and open it again.")
            loading = false
            publish()
            return
        }
        val decoded = withContext(Dispatchers.Default) { DocumentBody(source.first, source.second) }
        body = decoded; sourceBytes = source.first
        journal = store.loadDocumentReview(draftKey)
        // The draft row carries its own hold, so a missing, stale or reset journal can never release a merged draft to autosave.
        if (draft?.state == DocDraft.State.HELD && !journal.pendingMergedSave) {
            journal.pendingMergedSave = true
            runCatching { store.writer.saveDocumentReview(journal, draftKey) }
        }
        if (draft != null) {
            base = Base(draft.baseRowVersion, draft.baseSha256, draft.baseStorageObjectId); dirty = true; status = draft.lastError
        } else if (cached != null) base = Base(cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId)
        if (draft?.state == DocDraft.State.RECOVERY || draft?.state == DocDraft.State.CONFLICT) statusTone = Tone.WARNING
        title = draft?.title ?: currentDoc?.title ?: doc.title
        if (draft != null) {
            val restored = PreservedDocumentSnapshot(draft.title, draft.body, draft.contentType, draft.baseRowVersion, draft.baseSha256, draft.baseStorageObjectId)
            journal.commonBase(restored)?.let { journal.retainBase(it) }
        }
        journal.base?.let { retained ->
            if (retained.rowVersion == base?.rowVersion && retained.sha256 == base?.sha && retained.storageObjectId == base?.storageObject && retained.verified) {
                baselineBytes = retained.bytes; baselineTitle = retained.title
            }
        }
        if (draft == null) { baselineBytes = cached?.bytes; baselineTitle = title }
        else if (cached?.docRowVersion == draft.baseRowVersion.toLong()) { baselineBytes = cached.bytes; baselineTitle = currentDoc?.title }

        when (val editing = decoded.editing) {
            DocumentBody.Editing.ProseMirror -> {
                original = decoded.editorState
                val state = decoded.editorState ?: JsonValue.obj("type" to JsonValue.Str("doc"), "content" to JsonValue.Arr(emptyList()))
                content = state.serializedString()
                payload = payload("mode" to JsonValue.Str("rich"), "state" to state)
                mode = Mode.Rich
            }
            DocumentBody.Editing.Markdown, DocumentBody.Editing.MarkdownModel -> {
                content = decoded.markdown
                payload = payload("mode" to JsonValue.Str("markdown"), "markdown" to JsonValue.Str(decoded.markdown))
                mode = Mode.Markdown
            }
            is DocumentBody.Editing.ReadOnly -> mode = Mode.Unavailable(editing.reason)
        }
        if (cached != null && cached.docRowVersion == base?.rowVersion?.toLong() && cached.sha256 == base?.sha) {
            journal.retainBase(PreservedDocumentSnapshot.of(cached, baselineTitle ?: title))
            runCatching { store.writer.saveDocumentReview(journal, draftKey) }
        }
        restorePreview()
        loading = false
        publish()
        payload?.let { page?.load(it) }
    }

    // MARK: Page

    fun pageAttached(page: DocumentPage) {
        this.page = page
        // A page created again opens on the latest edit, not the stored text.
        if (content.isNotEmpty()) rebuildPayloadFromContent()
        payload?.let { page.load(it) }
    }

    private fun rebuildPayloadFromContent() {
        if (content.isEmpty()) return
        when (mode) {
            Mode.Rich -> JsonValue.parseOrNull(content.toByteArray())?.let { payload = payload("mode" to JsonValue.Str("rich"), "state" to it) }
            Mode.Markdown -> payload = payload("mode" to JsonValue.Str("markdown"), "markdown" to JsonValue.Str(content))
            else -> {}
        }
    }

    fun pageLoaded(ok: Boolean, message: String?) {
        pageReady = ok
        if (!ok) setStatus(message?.let { "The editor could not open this document: $it" } ?: "The editor could not open this document.", Tone.ERROR)
        else {
            page?.setEditable(!followingRemote && !permissionDenied && isUnlocked())
            publish()
        }
    }

    /** The page's process ended; it reloads and gets the latest edit back (the draft, or what it last reported). */
    fun pageTerminated() {
        pageReady = false
        val decoded = body ?: return
        if (content.isNotEmpty()) rebuildPayloadFromContent()
        else if (decoded.editing == DocumentBody.Editing.ProseMirror) decoded.editorState?.let { payload = payload("mode" to JsonValue.Str("rich"), "state" to it) }
        payload?.let { page?.load(it) }
        publish()
    }

    fun pageChanged(next: String) {
        if (closed || loading || next == content) return
        content = next
        markDirty()
    }

    fun pageError(message: String) = setStatus(message, Tone.ERROR)

    fun setTitle(value: String) {
        if (value == title) return
        title = value
        if (!loading) markDirty() else publish()
    }

    // MARK: Authority

    /** Acquires this session's lease once at opening. False: not possible now (locked, denied); the document stays readable. */
    suspend fun prepareOpening(): Boolean {
        if (!isUnlocked()) return false
        return try {
            val lease = authority.acquire(doc.id, editorSessionId)
            concurrentEditing = lease.documentEditProtocol == studio.otherstuff.ambulando.core.transport.CONCURRENT_DOCUMENT_EDIT_PROTOCOL
            publish()
            true
        } catch (e: TowerError.Http) {
            if (e.status == 403) { permissionDenied = true; setStatus("Read only — you do not have permission to edit this document.", Tone.WARNING); false } else true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
    }

    /** Keeps this session's lease and the autosave running until [close]. */
    fun holdLease() {
        if (!isUnlocked()) { leaseState = "Editing on this device. Unlock to save."; publish(); return }
        renewJob?.cancel()
        startAutosave()
        renewJob = scope.launch {
            try {
                reconcileRemote()
                if (authorityLost) { inspectAuthority(); return@launch }
                val lease = authority.acquire(doc.id, editorSessionId)
                concurrentEditing = lease.documentEditProtocol == studio.otherstuff.ambulando.core.transport.CONCURRENT_DOCUMENT_EDIT_PROTOCOL
                leaseState = "You are editing (lease held)."
                recoveries = runCatching { authority.recoveries(doc.id) }.getOrDefault(emptyList())
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                leaseState = (e as? UserFacingError)?.userMessage ?: "Could not take the edit lease; your edits stay on this device."
                if (e is TowerError.Http && e.status == 403) {
                    permissionDenied = true; page?.setEditable(false)
                    leaseState = "Read only — you do not have permission to edit this document."
                }
                if (e is LeaseException) { authorityLost = true; publish(); inspectAuthority(); reconcileRemote() }
                publish()
                return@launch
            }
            while (isActive) {
                delay(renewMillis)
                try {
                    authority.renew(doc.id, editorSessionId)
                } catch (e: LeaseException) {
                    if (authority.resumeUnchangedSession(doc.id, editorSessionId)) { leaseState = "You are editing (lease held)."; publish(); continue }
                    authorityLost = true
                    leaseState = "Editing authority ended. Your draft stays on this device."
                    publish()
                    inspectAuthority()
                    reconcileRemote()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    leaseState = "Could not renew while offline; your draft stays on this device."
                    publish()
                }
            }
        }
    }

    private fun startAutosave() {
        if (!active || autosave != null) return
        autosave = scope.launch {
            while (isActive) {
                delay(autosaveMillis)
                if (!active) return@launch
                reconcileRemote()
                if (canAutosave()) save()
            }
        }
    }

    private fun canAutosave() = dirty && !journal.pendingMergedSave && remoteVersion == null && journal.previewText == null && !authorityLost && !saving && !takingOver

    /** Background and foreground. Local durability comes first; the cloud flush is best effort. */
    suspend fun setActive(value: Boolean) {
        if (closed) return
        active = value
        if (value) { startAutosave(); scope.launch { reconcileRemote() } }
        else { autosave?.cancel(); autosave = null; flush() }
    }

    suspend fun flush() {
        checkpoint()
        persistReview()
        if (canAutosave()) save()
    }

    private suspend fun inspectAuthority() {
        try {
            val inspected = authority.inspect(doc.id)
            if (inspected == null) { canTakeover = false; publish(); return }
            observedLeaseId = inspected.leaseId
            concurrentEditing = inspected.concurrentSupported
            canTakeover = inspected.supported && !inspected.concurrentSupported
            if (inspected.leaseId != null && !canTakeover && !concurrentEditing) {
                leaseState = "Another editor is open. This server does not support safe takeover yet."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            canTakeover = false
        }
        publish()
    }

    /**
     * Explicit confirmation. Divergent local work is first preserved as a Tower recovery (the existing recovery-body save),
     * then authority is taken from the inspected lease and the latest body is loaded. Any failure keeps the local draft and
     * asks for a fresh decision; authority is never taken back automatically.
     */
    suspend fun takeover() {
        if (saving || takingOver || reconciling || !canTakeover) return
        val expected = observedLeaseId
        takingOver = true; publish()
        try {
            renewJob?.cancel()
            if (!checkpoint()) return
            val revision = editRevision
            if (dirty) {
                val draft = persistDraft(DocDraft.State.EDITING) ?: return
                val result = authority.save(draft, editorSessionId, asRecovery = true)
                if (result !is DocSaveResult.Recovery || result.id == null || revision != editRevision) {
                    setStatus("Takeover paused. Your local draft must be preserved as a cloud recovery first.", Tone.WARNING)
                    return
                }
            }
            val recovery = store.reader.docDraft(draftKey)
            val newSession = newSessionId()
            try {
                authority.takeover(doc.id, newSession, expected)
                if (revision != editRevision) throw LeaseException.Invalid("draft_changed_during_takeover")
                if (recovery != null) {
                    // The draft is also in Tower's recovery, but that copy is only reachable by id: keep a local one in Preserved drafts first.
                    journal.preserve(PreservedDocumentSnapshot(recovery.title, recovery.body, recovery.contentType, recovery.baseRowVersion, recovery.baseSha256, recovery.baseStorageObjectId))
                    store.writer.saveDocumentReview(journal, draftKey)
                    if (!store.writer.deleteDocDraftIfUnchanged(recovery)) throw LeaseException.Invalid("local_draft_changed")
                }
                if (revision != editRevision) throw LeaseException.Invalid("draft_changed_during_takeover")
                editorSessionId = newSession
                authorityLost = false; canTakeover = false; observedLeaseId = null
                if (!loadLatestPage()) {
                    authorityLost = true
                    authority.release(doc.id, newSession)
                    checkpoint()
                    throw LeaseException.Invalid("latest_page_not_loaded")
                }
                lastSyncedAt = now()
                setStatus("Latest cloud version loaded. Earlier local edits are preserved in recovery history.", Tone.SUCCESS)
                page?.setEditable(true)
                holdLease()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                authorityLost = true
                authority.release(doc.id, newSession)
                persistDraft(DocDraft.State.EDITING)
                setStatus("Takeover did not finish: ${e.message}. Your recovery draft is retained.", Tone.WARNING)
                inspectAuthority()
            }
        } finally {
            takingOver = false; publish()
        }
    }

    // MARK: Remote head

    /** Refresh only the comparison target. A newer shared save never replaces a working draft. */
    suspend fun reconcileRemote() {
        if (loading || saving || takingOver || reconciling || !active || !isUnlocked()) return
        reconciling = true; publish()
        try {
            if (!checkpoint()) return
            val fresh = authority.latest(doc.id) ?: return
            observeRemote(fresh.doc, fresh.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remoteFailure(e)
        } finally {
            reconciling = false; publish()
        }
    }

    private fun observeRemote(record: DocRecord, cached: DocBodyRecord) {
        val current = base
        if (cached.docRowVersion == current?.rowVersion?.toLong() && cached.sha256 == current.sha && cached.storageObjectId == current.storageObject) return
        remoteVersion?.let { if (it.rowVersion > cached.docRowVersion) return }
        remoteVersion = PreservedDocumentSnapshot.of(cached, record.title)
        setStatus("Newer version available — Review changes. Your local draft is preserved.", Tone.WARNING)
    }

    private fun remoteFailure(error: Throwable) {
        if (error is TowerError.Http && (error.status == 403 || error.status == 404 || error.status == 410)) {
            permissionDenied = true
            page?.setEditable(false)
            setStatus(
                if (error.status == 403) "Document access was denied. Your local work remains on this device."
                else "This document is no longer available. Your local work remains on this device.",
                Tone.WARNING,
            )
        } else setStatus("Could not check the latest version. Your draft and review work remain on this device; reconnect to retry.", Tone.WARNING)
    }

    /** Loads the cached canonical document into the page. Only when there is no local draft to lose. */
    private suspend fun loadLatestPage(): Boolean {
        val revision = editRevision
        return try {
            val cached = store.reader.docBody(workspace.id, doc.id) ?: return false
            val record = store.reader.doc(workspace.id, doc.id) ?: return false
            if (cached.docRowVersion != record.rowVersion || store.reader.docDraft(draftKey) != null) return false
            val decoded = DocumentBody(cached.bytes, cached.contentType)
            val nextContent: String
            val nextPayload: String
            val nextMode: Mode
            when (decoded.editing) {
                DocumentBody.Editing.ProseMirror -> {
                    val state = decoded.editorState ?: return false
                    nextContent = state.serializedString(); nextMode = Mode.Rich
                    nextPayload = payload("mode" to JsonValue.Str("rich"), "state" to state)
                }
                DocumentBody.Editing.Markdown, DocumentBody.Editing.MarkdownModel -> {
                    nextContent = decoded.markdown; nextMode = Mode.Markdown
                    nextPayload = payload("mode" to JsonValue.Str("markdown"), "markdown" to JsonValue.Str(decoded.markdown))
                }
                is DocumentBody.Editing.ReadOnly -> return false
            }
            if (revision != editRevision) return false
            loading = true
            body = decoded; sourceBytes = cached.bytes; original = decoded.editorState; content = nextContent; title = record.title
            base = Base(cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId)
            baselineBytes = cached.bytes; baselineTitle = record.title
            payload = nextPayload; mode = nextMode; dirty = false
            loading = false
            page?.load(nextPayload)
            journal.retainBase(PreservedDocumentSnapshot.of(cached, record.title))
            store.writer.saveDocumentReview(journal, draftKey)
            publish()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // MARK: Saved versions

    var reviewedHead: DocBodyRecord? = null
        private set
    private var reviewedTitle = ""

    /** Saved previous versions (Tower's recovery copies). The latest head is re-read first so the choice is against what is current. */
    suspend fun openVersions(): List<DocumentRecovery>? {
        if (saving || takingOver) return null
        return try {
            val fresh = authority.latest(doc.id) ?: return null
            reviewedHead = fresh.body; reviewedTitle = fresh.doc.title
            recoveries = authority.recoveries(doc.id)
            publish()
            recoveries
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus("Saved versions are unavailable while offline. Your draft is retained.", Tone.WARNING)
            null
        }
    }

    /** Text of the latest head ([recovery] null) or a stored previous version, for a read-only preview. */
    suspend fun previewVersion(recovery: DocumentRecovery?): String? {
        val head = reviewedHead ?: return null
        return try {
            if (recovery != null) {
                val previous = authority.recoveryBody(doc.id, recovery.id)
                (recovery.title ?: "Previous saved version") + "\n\n" + DocumentBody(previous.bytes, previous.contentType).markdown
            } else reviewedTitle + "\n\n" + DocumentBody(head.bytes, head.contentType).markdown
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus("Could not load that saved version. No versions were changed.", Tone.WARNING)
            null
        }
    }

    /** Keep ([promote]) or dismiss one stored version. A stale choice fails and retains both versions. */
    suspend fun resolveVersion(recovery: DocumentRecovery, promote: Boolean): Boolean {
        val head = reviewedHead
        if (head == null || saving || takingOver || dirty) {
            setStatus("Preserve current edits before choosing a saved version.", Tone.WARNING); return false
        }
        saving = true; publish()
        val choiceRevision = editRevision
        val choiceSession = if (authorityLost) newSessionId() else editorSessionId
        try {
            authority.resolveRecovery(doc.id, recovery.id, promote, head, choiceSession)
            authority.latest(doc.id)
            recoveries = authority.recoveries(doc.id)
            if (promote) {
                if (choiceRevision != editRevision) {
                    authorityLost = true; renewJob?.cancel()
                    authority.release(doc.id, choiceSession)
                    checkpoint()
                    setStatus("Selected version kept. New local edits are retained separately on this device.", Tone.WARNING)
                    return true
                }
                editorSessionId = choiceSession
                authorityLost = false; canTakeover = false; observedLeaseId = null
                if (!loadLatestPage()) {
                    authorityLost = true
                    authority.release(doc.id, choiceSession)
                    checkpoint()
                    setStatus("Version kept, but the page could not refresh. Current local text is retained.", Tone.WARNING)
                    return true
                }
                page?.setEditable(true)
                holdLease()
            }
            setStatus(if (promote) "Selected version kept. Other saved versions remain in history." else "Previous version dismissed. Latest saved document is unchanged.", Tone.SUCCESS)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (choiceSession != editorSessionId) authority.release(doc.id, choiceSession)
            setStatus("The choice could not be applied. Another editor may be active or the latest version changed. Take over if needed, then reopen Saved versions. Both versions are retained.", Tone.WARNING)
            return false
        } finally {
            saving = false; publish()
        }
    }

    // MARK: Preserved drafts and explicit remote review

    private suspend fun workingSnapshot(): PreservedDocumentSnapshot? {
        if (!checkpoint()) return null
        val current = base ?: return null
        val decoded = body ?: return null
        if (dirty) {
            val draft = store.reader.docDraft(draftKey) ?: return null
            return PreservedDocumentSnapshot(draft.title, draft.body, draft.contentType, draft.baseRowVersion, draft.baseSha256, draft.baseStorageObjectId)
        }
        return PreservedDocumentSnapshot(title, sourceBytes ?: ByteArray(0), decoded.contentType, current.rowVersion, current.sha, current.storageObject)
    }

    /** Opens review: refreshes the comparison target and restores any stored preview. */
    suspend fun openReview(): Boolean {
        if (saving || takingOver) return false
        reconcileRemote()
        if (!checkpoint()) return false
        restorePreview()
        publish()
        return true
    }

    private fun scheduleReviewPersistence() {
        previewGeneration++
        reviewPersistence?.cancel()
        reviewPersistence = scope.launch {
            delay(reviewDebounceMillis)
            persistReview()
        }
    }

    /** Stores the journal (and the edited preview). False: it could not be stored and the person is told. */
    suspend fun persistReview(): Boolean {
        if (closed) return false
        if (mergeReady) { journal.previewText = mergeText; journal.previewTitle = mergeTitle }
        return try {
            store.writer.saveDocumentReview(journal, draftKey)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus("Review work could not be stored. Keep this page open and copy the preview before leaving.", Tone.ERROR)
            false
        }
    }

    /** The person edits the merged preview text/title: persisted (debounced) so cancellation, relaunch or backgrounding keep it. */
    fun editPreview(text: String? = null, title: String? = null) {
        if (restoringPreview) return
        if (text != null) mergeText = text
        if (title != null) mergeTitle = title
        publish()
        scheduleReviewPersistence()
    }

    private fun restorePreview() {
        restoringPreview = true
        try {
            mergeReady = journal.previewText != null
            mergeText = journal.previewText ?: ""; mergeTitle = journal.previewTitle ?: ""
            val b = journal.mergeBase; val l = journal.mergeLocal; val r = journal.mergeLatest
            if (b != null && l != null && r != null) {
                runCatching { DocumentMerge.merge(b, l, r, choices()) }.getOrNull()?.let { mergeConflicts = it.conflicts }
            }
        } finally {
            restoringPreview = false
        }
    }

    private fun choices(): Map<String, DocumentMerge.Choice> =
        journal.choices.mapNotNull { (k, v) -> DocumentMerge.Choice.from(v)?.let { k to it } }.toMap()

    val localReviewText: String get() = title + "\n\n" + draftText
    val remoteReviewText: String get() = remoteVersion?.let { it.title + "\n\n" + it.document.markdown } ?: "Latest shared version has not changed."

    /** Whether the retained common base verifies against the working copy's base. */
    val canMerge: Boolean get() {
        val b = journal.base ?: return false
        val current = base ?: return false
        return b.rowVersion == current.rowVersion && b.sha256 == current.sha && b.storageObjectId == current.storageObject && b.verified
    }

    val previewIsRich: Boolean get() = journal.mergeLatest?.document?.editing == DocumentBody.Editing.ProseMirror

    val readableMergePreview: String get() {
        if (previewIsRich) JsonValue.parseOrNull(mergeText.toByteArray())?.let { return ProseMirrorMarkdown.markdown(it) }
        return mergeText
    }

    fun readablePreviousPreview(preview: PreservedMergePreview): String {
        if (preview.head.document.editing == DocumentBody.Editing.ProseMirror) {
            val state = JsonValue.parseOrNull(preview.text.toByteArray()) ?: return "An incomplete preview is preserved. Copy it for recovery."
            return ProseMirrorMarkdown.markdown(state)
        }
        return preview.text
    }

    val previewIsStale: Boolean get() {
        val preview = journal.mergeLatest ?: return false
        val remote = remoteVersion ?: return false
        return preview.rowVersion != remote.rowVersion || preview.sha256 != remote.sha256
    }

    /**
     * No network mutation or lease acquisition: accepting an existing version never publishes a version. The replaced
     * working copy, any preview and incomplete preview text are kept in the journal first, and the head is fetched again.
     */
    suspend fun acceptLatest() {
        val reviewed = remoteVersion
        if (saving || takingOver || reconciling || reviewed == null) return
        reconciling = true; publish()
        try {
            val originalCopy = workingSnapshot() ?: return
            val revision = editRevision
            try {
                journal.preserve(originalCopy)
                preserveRawPreview()
                runCatching { encodedPreview() }.getOrNull()?.let { journal.preserve(it) }
                store.writer.saveDocumentReview(journal, draftKey)
                val fresh = authority.latest(doc.id) ?: return
                if (!reviewed.matches(fresh.body) || reviewed.title != fresh.doc.title) {
                    observeRemote(fresh.doc, fresh.body)
                    setStatus("The shared version changed again. Review it before accepting. Your work is retained.", Tone.WARNING); return
                }
                if (revision != editRevision || fresh.body.docRowVersion < (base?.rowVersion ?: 0)) return
                val next = PreservedDocumentSnapshot.of(fresh.body, fresh.doc.title)
                if (!next.document.isEditable) throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
                val draft = store.reader.docDraft(draftKey)
                if (draft != null) {
                    if (!draft.body.contentEquals(originalCopy.bytes) || draft.title != originalCopy.title || !store.writer.deleteDocDraftIfUnchanged(draft)) return
                    if (revision != editRevision) { checkpoint(); return }
                }
                install(next, dirty = false)
                remoteVersion = null
                journal.retainBase(next); journal.pendingMergedSave = false
                // The accepted page edits and saves normally; the replaced preview is an accessible archived copy.
                journal.previewText = null; journal.previewTitle = null
                mergeReady = false; mergeConflicts = emptyList()
                store.writer.saveDocumentReview(journal, draftKey)
                setStatus("Latest shared version accepted. Previous local work remains in Preserved drafts. No version was created.", Tone.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remoteFailure(e)
            }
        } finally {
            reconciling = false; publish()
        }
    }

    /** Common base is mandatory. No two-way comparison is ever promoted to an automatic merge. */
    suspend fun beginMerge() {
        val b = journal.base
        val r = remoteVersion
        val l = if (canMerge) workingSnapshot() else null
        if (!canMerge || b == null || r == null || l == null) {
            setStatus("Common saved base unavailable. Manual review only; copy the versions and resolve them explicitly.", Tone.WARNING); return
        }
        if (closed) return
        journal.preserve(l)
        // Keep even incomplete/invalid source edits before deliberately starting another merge.
        preserveRawPreview()
        runCatching { encodedPreview() }.getOrNull()?.let { journal.preserve(it) }
        journal.reviewOriginal = l
        journal.mergeBase = b; journal.mergeLocal = l; journal.mergeLatest = r; journal.choices = mutableMapOf()
        generatePreview()
    }

    suspend fun resolveMerge(conflict: String, choice: DocumentMerge.Choice) {
        journal.choices[conflict] = choice.raw
        generatePreview()
    }

    private suspend fun generatePreview() {
        val b = journal.mergeBase; val l = journal.mergeLocal; val r = journal.mergeLatest
        if (b == null || l == null || r == null) return
        previewGeneration++
        val generation = previewGeneration
        try {
            val chosen = choices()
            val result = withContext(Dispatchers.Default) { DocumentMerge.merge(b, l, r, chosen) }
            if (closed || generation != previewGeneration) return
            val decoded = DocumentBody(result.bytes, result.contentType)
            restoringPreview = true
            mergeTitle = result.title
            mergeText = decoded.editorState?.serializedString() ?: decoded.markdown
            mergeConflicts = result.conflicts; mergeReady = true
            journal.previewText = mergeText; journal.previewTitle = mergeTitle
            restoringPreview = false
            publish()
            persistReview()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            restoringPreview = false
            setStatus("These stored formats cannot be merged safely. Manual review only; original versions are retained.", Tone.WARNING)
        }
    }

    private suspend fun encodedPreview(): PreservedDocumentSnapshot {
        val target = journal.mergeLatest
        if (!mergeReady || target == null) throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
        val template = target.document
        val encoded: EncodedBody
        if (template.editing == DocumentBody.Editing.ProseMirror) {
            val state = JsonValue.parseOrNull(mergeText.toByteArray()) ?: throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
            DocumentBody.checkEdited(state, template.editorState)
            // Rebuild from the structurally merged envelope, preserving its unknown metadata too.
            val b = journal.mergeBase; val l = journal.mergeLocal
            encoded = if (b != null && l != null) {
                val result = DocumentMerge.merge(b, l, target, choices())
                DocumentBody(result.bytes, result.contentType).encoded(state)
            } else template.encoded(state)
        } else {
            val b = journal.mergeBase; val l = journal.mergeLocal
            encoded = if (b != null && l != null) {
                val result = DocumentMerge.merge(b, l, target, choices())
                DocumentBody(result.bytes, result.contentType).encoded(mergeText)
            } else template.encoded(mergeText)
        }
        return PreservedDocumentSnapshot(mergeTitle, encoded.bytes, encoded.contentType, target.rowVersion, target.sha256, target.storageObjectId)
    }

    private fun preserveRawPreview() {
        val head = journal.mergeLatest
        if (!mergeReady || head == null) return
        if (journal.previousPreviews.none { it.text == mergeText && it.title == mergeTitle && it.head == head }) {
            journal.previousPreviews.add(PreservedMergePreview(mergeTitle, mergeText, head))
        }
    }

    /** The shared version moved while review was open: rebase the preview onto the new head, with the previous head as the next common base. */
    suspend fun reconcilePreview() {
        val oldHead = journal.mergeLatest
        val latest = remoteVersion
        if (mergeConflicts.isNotEmpty() || oldHead == null || latest == null) return
        try {
            val preview = encodedPreview()
            preserveRawPreview()
            journal.preserve(preview)
            journal.mergeBase = oldHead; journal.mergeLocal = preview; journal.mergeLatest = latest; journal.choices = mutableMapOf()
            generatePreview()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus("Preview is invalid. Correct it or copy it before reconciling. Your original draft is retained.", Tone.WARNING)
        }
    }

    /**
     * Explicit authority action. A concurrent-edit server needs only a fresh session; an exclusive-lease server preserves the
     * working copy in a Tower recovery and uses the expected-lease takeover (which may create an authority-barrier version).
     */
    suspend fun takeOverForMerge() {
        if (!(mergeReady || concurrentEditing) || takingOver || saving) return
        if (!checkpoint()) return
        persistReview()
        takingOver = true; publish()
        renewJob?.cancel()
        val session = newSessionId()
        try {
            val inspection = authority.inspect(doc.id)
            concurrentEditing = inspection?.concurrentSupported == true
            if (inspection?.concurrentSupported != true && dirty) {
                val draft = persistDraft(DocDraft.State.EDITING)
                val result = draft?.let { authority.save(it, editorSessionId, asRecovery = true) }
                if (result !is DocSaveResult.Recovery || result.id == null) {
                    setStatus("Legacy takeover paused until the local draft is preserved in cloud recovery. Your draft and preview remain on this device.", Tone.WARNING)
                    return
                }
            }
            if (closed) return
            if (inspection?.concurrentSupported == true) {
                authority.acquire(doc.id, session)
            } else if (inspection?.leaseId != null) {
                if (!inspection.supported) throw LeaseException.Invalid("takeover_not_available")
                val fresh = authority.takeover(doc.id, session, inspection.leaseId)
                observeRemote(fresh.doc, fresh.body)
            } else authority.acquire(doc.id, session)
            if (closed) { authority.release(doc.id, session); return }
            val previous = editorSessionId
            editorSessionId = session; authorityLost = false
            authority.release(doc.id, previous)
            leaseState = "You are editing (lease held)."
            setStatus(
                if (concurrentEditing) "Concurrent editing ready. Review the preview, then edit the merged result and Save."
                else "Editing authority acquired. Refresh and reconcile the preview before using it. Tower may create an authority-barrier version.",
                Tone.WARNING,
            )
            holdLease()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Could not acquire editing authority. Original draft and merge preview are retained: ${e.message}", Tone.WARNING)
        } finally {
            takingOver = false; publish()
        }
    }

    /**
     * Applies the resolved preview as a durable *local* draft after re-checking the reviewed head and the working copy. It
     * never publishes: the normal Save keeps its fresh preflight, lease and server compare-and-set.
     */
    suspend fun useMergePreview(): Boolean {
        val target = journal.mergeLatest
        if (!mergeReady || mergeConflicts.isNotEmpty() || saving || takingOver || reconciling || target == null) return false
        reconciling = true; publish()
        try {
            val originalCopy = workingSnapshot() ?: return false
            val revision = editRevision
            val preview = encodedPreview()
            val reviewed = journal.reviewOriginal
            if (reviewed != null && (!reviewed.bytes.contentEquals(originalCopy.bytes) || reviewed.title != originalCopy.title)) {
                journal.preserve(originalCopy); journal.preserve(preview); preserveRawPreview()
                store.writer.saveDocumentReview(journal, draftKey)
                setStatus("Your local document changed while review was open. Start Merge my changes again; original and preview work are preserved.", Tone.WARNING)
                return false
            }
            journal.preserve(originalCopy); journal.preserve(preview)
            store.writer.saveDocumentReview(journal, draftKey)
            val fresh = authority.latest(doc.id)
            if (fresh != null && (!target.matches(fresh.body) || target.title != fresh.doc.title)) {
                observeRemote(fresh.doc, fresh.body)
                setStatus("Shared version changed during review. Reconcile preview with latest; original and merged work are preserved.", Tone.WARNING)
                return false
            }
            if (revision != editRevision) return false
            val draft = DocDraft(connection.id, workspace.id, doc.id, preview.title, preview.bytes, preview.contentType,
                target.rowVersion, target.sha256, target.storageObjectId, DocDraft.State.HELD)
            val current = store.reader.docDraft(draftKey)
            val heldBefore = journal.pendingMergedSave
            journal.pendingMergedSave = true // Held in memory before the text can exist, so autosave never sees it unheld.
            val stored = try {
                if (current != null) {
                    current.body.contentEquals(originalCopy.bytes) && current.title == originalCopy.title && store.writer.replaceDocDraftIfUnchanged(current, draft)
                } else store.writer.insertDocDraftIfAbsent(draft)
            } catch (e: Throwable) { journal.pendingMergedSave = heldBefore; throw e }
            if (!stored) { journal.pendingMergedSave = heldBefore; return false }
            if (revision != editRevision) { checkpoint(); return false }
            install(preview, dirty = true)
            baselineBytes = target.bytes; baselineTitle = target.title
            journal.retainBase(target); journal.previewText = null; journal.previewTitle = null; journal.pendingMergedSave = true
            mergeReady = false; remoteVersion = null
            store.writer.saveDocumentReview(journal, draftKey)
            setStatus("Merged draft ready. Review it in the editor, then Save to publish the next version. Editing authority is still required.", Tone.INFO)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Merge preview could not be applied: ${e.message}. Both drafts are retained.", Tone.WARNING)
            return false
        } finally {
            reconciling = false; publish()
        }
    }

    /** Restores one preserved copy as the local draft (never published). The copy being replaced is preserved first. */
    suspend fun restorePreserved(snapshot: PreservedDocumentSnapshot): Boolean {
        if (saving || takingOver) return false
        val current = workingSnapshot() ?: return false
        journal.preserve(current)
        val revision = editRevision
        try {
            store.writer.saveDocumentReview(journal, draftKey)
            if (revision != editRevision) return false
            val draft = DocDraft(connection.id, workspace.id, doc.id, snapshot.title, snapshot.bytes, snapshot.contentType,
                snapshot.rowVersion, snapshot.sha256, snapshot.storageObjectId, DocDraft.State.HELD)
            val saved = store.reader.docDraft(draftKey)
            val heldBefore = journal.pendingMergedSave
            journal.pendingMergedSave = true
            val stored = try {
                if (saved != null) {
                    revision == editRevision && saved.body.contentEquals(current.bytes) && saved.title == current.title &&
                        store.writer.replaceDocDraftIfUnchanged(saved, draft)
                } else revision == editRevision && store.writer.insertDocDraftIfAbsent(draft)
            } catch (e: Throwable) { journal.pendingMergedSave = heldBefore; throw e }
            if (!stored) { journal.pendingMergedSave = heldBefore; return false }
            if (revision != editRevision) { checkpoint(); return false }
            journal.commonBase(snapshot)?.let { journal.retainBase(it) }
            install(snapshot, dirty = true)
            // A restored copy may be an old merge preview: like a merged draft, only an explicit Save publishes it.
            journal.pendingMergedSave = true
            store.writer.saveDocumentReview(journal, draftKey)
            reconcileRemote()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus("Preserved draft could not be restored. Current work is retained.", Tone.WARNING)
            return false
        }
    }

    private fun install(snapshot: PreservedDocumentSnapshot, dirty: Boolean) {
        val decoded = snapshot.document
        loading = true
        body = decoded; sourceBytes = snapshot.bytes; original = decoded.editorState
        title = snapshot.title; content = decoded.editorState?.serializedString() ?: decoded.markdown
        mode = if (decoded.editorState == null) Mode.Markdown else Mode.Rich
        base = Base(snapshot.rowVersion, snapshot.sha256, snapshot.storageObjectId)
        baselineBytes = snapshot.bytes; baselineTitle = snapshot.title
        this.dirty = dirty; editRevision++
        payload = decoded.editorState?.let { payload("mode" to JsonValue.Str("rich"), "state" to it) }
            ?: payload("mode" to JsonValue.Str("markdown"), "markdown" to JsonValue.Str(content))
        loading = false
        publish()
        payload?.let { page?.load(it) }
    }

    // MARK: Draft persistence

    private fun markDirty() {
        if (closed) return
        editRevision++
        dirty = true
        if (!saving && statusTone != Tone.WARNING && statusTone != Tone.ERROR) { status = "Saving locally…"; statusTone = Tone.INFO }
        publish()
        debounce?.cancel()
        debounce = scope.launch {
            delay(draftDebounceMillis)
            persistDraft(DocDraft.State.EDITING)
        }
    }

    /** Stores the page's current text as the durable draft. True when nothing is unsaved or the draft was written. */
    suspend fun checkpoint(): Boolean {
        if (closed) return false
        if (pageReady) page?.snapshot()?.let { if (it != content) pageChanged(it) }
        if (!dirty) return true
        return persistDraft(DocDraft.State.EDITING) != null
    }

    /** Encodes the current edit (off the main thread) and stores it as the durable draft. */
    suspend fun persistDraft(state: DocDraft.State): DocDraft? = persistSnapshot(state)?.first

    private suspend fun persistSnapshot(draftState: DocDraft.State): Pair<DocDraft, Long>? {
        if (pageReady) page?.snapshot()?.let { if (it != content) pageChanged(it) }
        val decoded = body
        val current = base
        if (closed || decoded == null || current == null || !dirty) return null
        val revision = editRevision
        val currentMode = mode; val currentContent = content; val currentOriginal = original; val currentTitle = title; val bytesNow = sourceBytes
        val encoded: Result<EncodedBody> = withContext(Dispatchers.Default) {
            runCatching {
                when (currentMode) {
                    Mode.Markdown -> {
                        if (currentContent == decoded.markdown && bytesNow != null) EncodedBody(bytesNow, decoded.contentType)
                        else decoded.encoded(currentContent)
                    }
                    Mode.Rich -> {
                        val state = JsonValue.parseOrNull(currentContent.toByteArray()) ?: throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
                        DocumentBody.checkEdited(state, currentOriginal)
                        if (state == decoded.editorState && bytesNow != null) EncodedBody(bytesNow, decoded.contentType) else decoded.encoded(state)
                    }
                    else -> throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
                }
            }
        }
        val bytes = encoded.getOrElse { error ->
            if (error is CancellationException) throw error
            setStatus((error as? UserFacingError)?.userMessage ?: "This edit could not be encoded without loss, so it was not saved.", Tone.ERROR)
            return null
        }
        // Encoding can yield while typing or another save advances the base. The newer checkpoint wins.
        if (closed || revision != editRevision || base != current) return null
        val draft = DocDraft(connection.id, workspace.id, doc.id, currentTitle, bytes.bytes, bytes.contentType,
            current.rowVersion, current.sha, current.storageObject,
            if (draftState == DocDraft.State.EDITING && journal.pendingMergedSave) DocDraft.State.HELD else draftState)
        try {
            store.writer.saveDocDraft(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Your edits could not be stored on this device: ${e.message}", Tone.ERROR)
            return null
        }
        if (!saving && statusTone != Tone.WARNING && statusTone != Tone.ERROR) setStatus("Saved locally; waiting to sync.", Tone.INFO)
        return draft to revision
    }

    /** Saves through Tower (lease, expected version). True when Tower made it the canonical version. */
    suspend fun save(): Boolean {
        if (closed || saving || takingOver || reconciling || permissionDenied) return false
        saving = true; publish()
        try {
            if (!checkpoint()) return false
            // Opening review is not authority to publish an unresolved/stale draft.
            try {
                val fresh = authority.latest(doc.id)
                if (fresh != null) {
                    observeRemote(fresh.doc, fresh.body)
                    val retained = store.reader.docDraft(draftKey)
                    if (retained != null && retained.title == fresh.doc.title && Sha256Hex.of(retained.body) == fresh.body.sha256) {
                        // A previous successful response may have been lost: the saver acknowledges the identical canonical snapshot
                        // without another mutation or forced review.
                        remoteVersion = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remoteFailure(e); return false
            }
            if (remoteVersion != null || journal.previewText != null) {
                setStatus("Review the newer version or finish the merge preview before saving. Your work is retained.", Tone.WARNING)
                return false
            }
            if (authorityLost) {
                setStatus("Saved locally; editing authority ended. Use Review changes to preserve and reconcile your work, then explicitly acquire editing authority.", Tone.WARNING)
                inspectAuthority()
                return false
            }
            debounce?.cancel()
            page?.snapshot()?.let { if (it != content && !loading) pageChanged(it) }
            if (uploadsPending()) { setStatus("Wait for pasted items to finish uploading, or remove them.", Tone.WARNING); return false }
            val (draft, submittedRevision) = persistSnapshot(DocDraft.State.EDITING) ?: run {
                if (!dirty) setStatus("No changes to save.", Tone.INFO)
                return !dirty
            }
            setStatus("Saving…", Tone.INFO)
            val result = authority.save(draft, editorSessionId)
            if (closed) return false // The saver already finished its exact durable snapshot; no retired-model writes.
            return when (result) {
                is DocSaveResult.Saved -> {
                    val saved = result.body
                    base = Base(saved.docRowVersion, saved.sha256, saved.storageObjectId)
                    baselineBytes = saved.bytes; baselineTitle = draft.title
                    journal.retainBase(PreservedDocumentSnapshot(draft.title, saved.bytes, saved.contentType, saved.docRowVersion, saved.sha256, saved.storageObjectId))
                    journal.pendingMergedSave = false
                    runCatching { store.writer.saveDocumentReview(journal, draftKey) }
                    lastSyncedAt = runCatching { Instant.parse(saved.fetchedAt) }.getOrDefault(now())
                    dirty = editRevision != submittedRevision
                    if (dirty) {
                        persistDraft(DocDraft.State.EDITING)
                        setStatus("Latest edits saved locally; earlier changes synced.", Tone.INFO)
                    } else setStatus("Saved.", Tone.SUCCESS)
                    !dirty
                }
                is DocSaveResult.Recovery -> {
                    runCatching { authority.latest(doc.id) }.getOrNull()?.let { observeRemote(it.doc, it.body) }
                    setStatus(result.message, Tone.WARNING)
                    false
                }
                is DocSaveResult.LeaseHeld -> {
                    authorityLost = true
                    setStatus(result.message, Tone.WARNING)
                    inspectAuthority()
                    false
                }
                is DocSaveResult.NotSaved -> {
                    if (authority.sessionLost(doc.id, editorSessionId)) {
                        authorityLost = !authority.resumeUnchangedSession(doc.id, editorSessionId)
                    }
                    setStatus(result.message, Tone.WARNING)
                    inspectAuthority()
                    false
                }
            }
        } finally {
            saving = false; publish()
        }
    }

    /** Drops the local draft (the person chose the stored version over their unsaved edit). */
    suspend fun discardDraft() {
        debounce?.cancel()
        editRevision++ // Fence encoders already in flight before the explicit discard.
        store.reader.docDraft(draftKey)?.let { store.writer.deleteDocDraftIfUnchanged(it) }
        dirty = false
        if (journal.pendingMergedSave) { journal.pendingMergedSave = false; persistReview() }
        publish()
    }

    /** The edit as text, for "Copy my text" when a save could not go through. */
    val draftText: String get() = when (mode) {
        Mode.Markdown -> content.ifEmpty { body?.markdown ?: "" }
        Mode.Rich -> {
            val state = if (content.isEmpty()) body?.editorState else JsonValue.parseOrNull(content.toByteArray())
            state?.let { ProseMirrorMarkdown.markdown(it) } ?: ""
        }
        else -> ""
    }

    /** Binds a created or chosen page into this open page and makes the result a durable draft before the creation write is retired. */
    suspend fun bindWiki(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): WikiEditorBinding {
        val open = page
        if (!pageReady || open == null) return WikiEditorBinding.FAILED
        val count = open.wikiBind(title, documentId, occurrence, expectedCount) ?: return WikiEditorBinding.FAILED
        if (count > 0) markDirty()
        return WikiEditorBinding.outcome(count, checkpoint())
    }

    /** Places a link in the open page (null when the page is not ready). The caller [checkpoint]s before relying on it. */
    suspend fun insertWikiLink(documentId: String?, title: String, range: WikiRange?): WikiPlacement? {
        val open = page
        if (!pageReady || open == null) return null
        val placed = open.wikiInsert(documentId, title, range) ?: return null
        markDirty()
        return placed
    }

    /** The document moved: its drafts and the next save are based on the version the move produced. */
    fun rebase(rowVersion: Int) {
        base?.let { base = it.copy(rowVersion = rowVersion); publish() }
    }

    /** Ends the session: stops renewing and releases the lease. Callers checkpoint and persist review before closing. */
    suspend fun close() {
        closed = true
        editRevision++
        previewGeneration++
        debounce?.cancel(); reviewPersistence?.cancel(); renewJob?.cancel(); autosave?.cancel(); autosave = null
        active = false
        try { authority.release(doc.id, editorSessionId) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private fun payload(vararg fields: Pair<String, JsonValue>): String = JsonValue.Obj(fields.toList()).serializedString()
}
