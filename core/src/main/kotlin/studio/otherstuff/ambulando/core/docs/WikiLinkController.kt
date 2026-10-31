package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import java.util.UUID

/** Documents, channels and names as this device knows them, for labels and search. Only readable, saved rows are here. */
data class WikiContext(
    val docs: List<DocRecord> = emptyList(),
    val channels: List<ChannelRecord> = emptyList(),
    val scopes: List<ScopeRecord> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val pending: List<WriteItem> = emptyList(),
    val viewerNpub: String? = null,
) {
    fun channel(id: String): ChannelRecord? = channels.firstOrNull { it.id == id }

    private fun nav(channel: ChannelRecord) = NavChannel(channel.id, channel.scopeId, channel.name, channel.kind, channel.position, channel.raw)

    fun channelLabel(id: String): String {
        val channel = channel(id) ?: return "Unknown channel"
        val label = SidebarNavigation.label(nav(channel), viewerNpub, names)
        return if (SidebarNavigation.isDirectMessageChannel(nav(channel))) label else "#$label"
    }

    fun scopeLabel(id: String?): String = scopes.firstOrNull { it.id == id }?.name ?: "Unknown scope"

    /** "Scope / #channel", the place shown with every result. */
    fun place(doc: DocRecord): String = "${scopeLabel(doc.scopeId ?: channel(doc.channelId)?.scopeId)} / ${channelLabel(doc.channelId)}"

    fun placeOfChannel(id: String): String = "${scopeLabel(channel(id)?.scopeId)} / ${channelLabel(id)}"

    /** "note" for a private note-to-self conversation, otherwise "document". */
    fun kindWord(channelId: String): String {
        val channel = channel(channelId) ?: return "document"
        val nav = nav(channel)
        return if (SidebarNavigation.isDirectMessageChannel(nav) && SidebarNavigation.participants(nav).size == 1) "note" else "document"
    }

    fun titleCount(title: String): Int = docs.count { !it.archived && WikiLinks.normalized(it.title) == WikiLinks.normalized(title) }
}

/** What is typed after `[[` in the editor page, and the span it will replace. */
data class WikiQuery(val text: String, val range: WikiRange)

/**
 * The open editors, so a page created in the background can put its link into an editor that is showing the source (the source
 * must never be rewritten behind an open editor's back). Keyed by connection too, so a background creation can never bind into an
 * editor of another account or backend.
 */
object WikiEditors {
    private val open = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<DocumentEditController>>()
    private fun key(connectionId: String, workspaceId: String, docId: String) = "$connectionId:$workspaceId:$docId"
    fun register(editor: DocumentEditController) { open[key(editor.connectionId, editor.workspaceId, editor.doc.id)] = java.lang.ref.WeakReference(editor) }
    fun unregister(editor: DocumentEditController) {
        val k = key(editor.connectionId, editor.workspaceId, editor.doc.id)
        if (open[k]?.get() === editor) open.remove(k)
    }
    fun editor(connectionId: String, workspaceId: String, docId: String): DocumentEditController? = open[key(connectionId, workspaceId, docId)]?.get()
}

/**
 * Screens that asked for a page to be created, by request id, so the page can be opened for the person when it exists and only if
 * they are still on the screen that asked.
 */
object WikiRequests {
    private class Box(val controller: java.lang.ref.WeakReference<WikiLinkController>, val visit: WikiVisit)
    private val requests = java.util.concurrent.ConcurrentHashMap<String, Box>()
    fun register(request: String, controller: WikiLinkController) { requests[request] = Box(java.lang.ref.WeakReference(controller), controller.visit()) }
    fun created(request: String?, doc: DocRecord, connectionId: String) {
        if (request == null) return
        val box = requests.remove(request) ?: return
        box.controller.get()?.openCreated(doc, connectionId, box.visit)
    }
}

/** What the surrounding screen provides to one document screen's wiki behaviour. */
interface WikiEnvironment {
    suspend fun loadContext(): WikiContext
    fun visit(): WikiVisit
    /** Keeps the person's unsaved text safe before the screen is left for a linked page. False: it could not be stored. */
    suspend fun beforeLeaving(): Boolean
    fun navigate(documentId: String)
    /** Queues a write for this connection and workspace; false when the device is not connected to the workspace. */
    suspend fun submit(intent: WriteIntent): Boolean
    /** Stages created-page bytes for the durable upload; the staged name, or null if they could not be stored. */
    suspend fun stage(bytes: ByteArray): String?
    /** Binds a reference in a source with no open editor, through the draft-first save path. Null: it could not be done. */
    suspend fun bindInDraft(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): WikiBindOutcome?
}

/**
 * One document screen's wiki behaviour. It reads the local store (never waits on sync to open a saved page), keeps the source
 * document's draft safe before leaving, and makes every change to links through the editor or the draft path. A link grants no
 * access: a bound id that is not among the readable saved pages is shown and opened by id (so Tower decides) and is never
 * replaced by a title match or a new page.
 */
class WikiLinkController(val sourceDocId: String, private val env: WikiEnvironment, private val newRequestId: () -> String = { UUID.randomUUID().toString().lowercase() }) {
    sealed class Pick {
        /** An existing unresolved reference (a click): choosing or creating binds exactly that one. */
        data class Reference(val target: WikiTarget) : Pick()
        /** A new link at the typed `[[` range, or at the selection when the range is null. */
        data class Insert(val range: WikiRange?) : Pick()
    }

    sealed class Sheet {
        data class Create(val target: WikiTarget, val range: WikiRange?) : Sheet()
        /** Pages already share this title: choose one (nothing is picked or created arbitrarily). [reason] explains why. */
        data class Choose(val pick: Pick, val title: String, val matches: List<DocRecord>, val reason: String) : Sheet()
        data class Picker(val pick: Pick, val query: String) : Sheet()
    }

    data class State(val context: WikiContext, val sheet: Sheet?, val banner: String?, val bannerIsProblem: Boolean, val query: WikiQuery?, val pendingOpen: String? = null)

    private var context = WikiContext()
    private var sheet: Sheet? = null
    private var banner: String? = null
    private var bannerIsProblem = false
    private var query: WikiQuery? = null
    /** A page just created for the visible screen, waiting for the screen to open it. */
    var pendingOpen: Pair<String, WikiVisit>? = null
        private set
    private val flow = MutableStateFlow(State(context, null, null, false, null))
    val state: StateFlow<State> get() = flow
    private fun publish() { flow.value = State(context, sheet, banner, bannerIsProblem, query, pendingOpen?.first) }

    /** The open editor showing the source, when there is one. */
    var editor: DocumentEditController? = null

    fun visit(): WikiVisit = env.visit()

    val sourceChannelId: String? get() = context.docs.firstOrNull { it.id == sourceDocId }?.channelId

    fun dismissSheet() { sheet = null; publish() }
    fun show(text: String, problem: Boolean) { banner = text; bannerIsProblem = problem; publish() }
    fun clearBanner() { banner = null; publish() }

    suspend fun refresh() {
        val next = try { env.loadContext() } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        if (next != context) { context = next; publish() }
    }

    // MARK: Messages from the editor page

    /** A link was opened in the page (`activate`), or the page reported a problem with one. */
    suspend fun pageWiki(action: String?, documentId: String?, title: String, occurrence: Int, count: Int, range: WikiRange?, message: String?) {
        when (action) {
            "activate" -> activate(WikiTarget(documentId?.takeIf { it.isNotEmpty() }, title, occurrence, count), range)
            "error" -> if (message != null) show(message, true)
        }
    }

    /** `[[` typing: the query text and the span it replaces, or null when the person is no longer typing one. */
    fun pageWikiQuery(active: Boolean, text: String, from: Int?, to: Int?) {
        query = if (active && from != null && to != null) WikiQuery(text, WikiRange(from, to)) else null
        publish()
    }

    // MARK: Opening

    /**
     * Opens a page by its stable id: the saved copy shows at once and refreshes behind it; a page not saved here is fetched by id and
     * shows why when it cannot be opened (deleted, or not shared with this account).
     */
    suspend fun openPage(id: String, from: WikiVisit? = null) {
        val start = from ?: env.visit()
        if (!start.visible || start != env.visit()) return
        val result = WikiNavigation.open(id, start, { env.visit() }, { env.beforeLeaving() }, { env.navigate(it) })
        if (result == WikiNavigation.Result.DraftNotStored && env.visit() == start) {
            show("Your edits could not be stored on this device, so the other page was not opened. Nothing was lost; they are still here.", true)
        }
    }

    /** A creation callback retains the visit that requested it, including account and workspace transitions. */
    fun openCreated(doc: DocRecord, connectionId: String, origin: WikiVisit) {
        val now = env.visit()
        if (origin != now || !origin.visible || sheet != null || origin.connectionId != connectionId) {
            show("“${doc.title}” was created and linked.", false)
            return
        }
        show("Created “${doc.title}”.", false)
        pendingOpen = doc.id to origin
        publish()
    }


    suspend fun openPendingCreated() {
        val (id, origin) = pendingOpen ?: return
        pendingOpen = null
        publish()
        openPage(id, origin)
    }

    /** A tapped or clicked reference. */
    suspend fun activate(target: WikiTarget, range: WikiRange? = null) {
        target.documentId?.let { openPage(it); return }
        val origin = env.visit()
        refresh()
        if (env.visit() != origin) return
        when (val resolved = WikiLinks.resolve(target, context.docs, sourceChannelId)) {
            is WikiResolution.Available -> {
                // A title that resolves to one page becomes a stable link to it, so renaming or moving the page later cannot turn this link
                // into an offer to create a duplicate. Opening goes ahead whether or not that could be saved.
                bind(target.title, resolved.doc.id, target.occurrence, target.count.takeIf { it > 0 })
                openPage(resolved.doc.id, origin)
            }
            is WikiResolution.Unresolved -> { sheet = Sheet.Create(target, range); publish() }
            is WikiResolution.Ambiguous -> {
                sheet = Sheet.Choose(Pick.Reference(target), target.title, resolved.matches, "Several pages are called “${target.title}”. Choose the one this link means.")
                publish()
            }
            is WikiResolution.Unavailable -> openPage(resolved.documentId)
        }
    }

    // MARK: Choosing and creating

    /** Links to an existing page the person chose. */
    suspend fun choose(doc: DocRecord, pick: Pick, openAfter: Boolean) {
        sheet = null; publish()
        val origin = env.visit()
        when (pick) {
            is Pick.Insert -> {
                val open = editor
                if (open == null || open.insertWikiLink(doc.id, doc.title, pick.range) == null) {
                    show("The link could not be added because the editor is not ready. Try again.", true); return
                }
                query = null; publish()
                open.checkpoint()
            }
            is Pick.Reference -> {
                if (!bind(pick.target.title, doc.id, pick.target.occurrence, pick.target.count.takeIf { it > 0 })) {
                    show("The link could not be saved yet. The page was not changed.", true); return
                }
            }
        }
        if (openAfter) openPage(doc.id, origin)
    }

    /**
     * Creates a page in [channelId] and links it. The creation is one durable write that carries the link to put into this document,
     * so a retry, relaunch or lost answer finds the page already made and never makes a second one.
     */
    suspend fun createAndLink(rawTitle: String, channelId: String, pick: Pick) {
        val title = rawTitle.trim()
        if (title.isEmpty()) return
        sheet = null; publish()
        val origin = env.visit()
        refresh()
        if (env.visit() != origin) return
        when (val existing = WikiCreate.existing(title, channelId, context.docs, context.pending)) {
            is WikiCreate.Existing.Page -> {
                show("“$title” already exists in ${context.placeOfChannel(channelId)}, so it was linked instead of creating another.", false)
                choose(existing.doc, pick, openAfter = false)
                return
            }
            is WikiCreate.Existing.Several -> {
                sheet = Sheet.Choose(pick, title, existing.docs,
                    "Several pages in ${context.placeOfChannel(channelId)} are already called “$title”. Choose one to link; no new page is made.")
                publish()
                return
            }
            is WikiCreate.Existing.Pending -> {
                show("“$title” is already being created in ${context.placeOfChannel(channelId)}.", false)
                return
            }
            null -> {}
        }
        var occurrence: Int? = null
        var count: Int? = null
        when (pick) {
            is Pick.Reference -> { occurrence = pick.target.occurrence; count = pick.target.count.takeIf { it > 0 } }
            is Pick.Insert -> {
                val placed = editor?.insertWikiLink(null, title, pick.range)
                if (placed == null) { show("The page was not created because the editor is not ready. Try again.", true); return }
                occurrence = placed.occurrence.takeIf { it >= 0 }
                count = if (placed.occurrence >= 0) placed.count else null
                query = null; publish()
            }
        }
        // The text with the new link in it is stored before anything is asked of Tower.
        val open = editor
        if (open != null && !open.checkpoint()) {
            show("Your edits could not be stored on this device, so the page was not created. Nothing was lost; try again.", true); return
        }
        if (env.visit() != origin) return
        val created = DocumentBody.newDocument("")
        val staged = env.stage(created.bytes)
        if (staged == null) { show("The new page could not be prepared on this device. Nothing was created.", true); return }
        val request = newRequestId()
        val payload = buildJsonObject {
            put("channel_id", channelId); put("title", title); put("staged", staged); put("content_type", created.contentType)
            put("file_name", title.take(60) + ".document.json")
            WikiCreate.linkPayload(sourceDocId, title, occurrence, count, request).forEach { (k, v) -> put(k, v) }
        }
        WikiRequests.register(request, this)
        if (env.submit(WriteIntent(WriteKind.DOC_CREATE, channelId, payload))) {
            show("Creating the ${context.kindWord(channelId)} “$title” in ${context.placeOfChannel(channelId)}. It opens when it is ready; until then it is listed below.", false)
            refresh()
        } else {
            show("The page was not created because this device is not connected to the workspace.", true)
        }
    }

    /** Binds a title-only reference to a page: in the open editor when there is one, otherwise through the saved draft path. */
    private suspend fun bind(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): Boolean {
        val open = editor
        if (open != null) {
            return when (open.bindWiki(title, documentId, occurrence, expectedCount)) {
                WikiEditorBinding.BOUND, WikiEditorBinding.NOTHING_TO_BIND -> true
                WikiEditorBinding.NO_LONGER_REQUESTED -> {
                    show("The text changed, so the link was not changed automatically. Choose the page again from the link picker.", true); false
                }
                WikiEditorBinding.FAILED -> false
            }
        }
        val outcome = try { env.bindInDraft(title, documentId, occurrence, expectedCount) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return false
        return when (outcome) {
            WikiBindOutcome.AlreadyBound, WikiBindOutcome.Saved -> true
            WikiBindOutcome.NoLongerRequested -> {
                show("The text changed, so the link was not changed automatically. Choose the page again from the link picker.", true); false
            }
            is WikiBindOutcome.KeptInDraft -> { show("The link is kept as an unsaved edit on this device: ${outcome.message}", false); true }
            WikiBindOutcome.SourceNotSaved -> false
        }
    }

    // MARK: Toolbar and typing

    /** "Link to doc or note": opens the picker at the editor's selection. */
    suspend fun openToolbarPicker() {
        refresh()
        val selection = editor?.page?.wikiSelection()
        sheet = Sheet.Picker(Pick.Insert(selection?.range), selection?.text?.trim() ?: "")
        publish()
    }

    /** The `[[` panel's "Create “q” or search more…" row, and the panel's own result choice, open the same picker at the typed span. */
    suspend fun openPickerForQuery() {
        refresh()
        val typed = query ?: return
        sheet = Sheet.Picker(Pick.Insert(typed.range), typed.text.trim())
        publish()
    }

    /** Search for the `[[` panel: this channel first, every channel and scope on request. */
    fun panelResults(text: String, everywhere: Boolean): List<DocRecord> =
        WikiLinks.search(text, context.docs, sourceChannelId, sourceDocId, everywhere)
}
