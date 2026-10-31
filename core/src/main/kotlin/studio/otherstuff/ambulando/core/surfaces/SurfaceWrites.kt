package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.isAccessRemoved
import studio.otherstuff.ambulando.core.store.FlaggedTable

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.AccessTarget
import studio.otherstuff.ambulando.core.store.task
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.canvasSnapshot
import studio.otherstuff.ambulando.core.store.markAccessRemoved
import studio.otherstuff.ambulando.core.store.replaceCanvases
import studio.otherstuff.ambulando.core.store.saveCanvasSnapshot
import studio.otherstuff.ambulando.core.store.applyTaskRelationshipPage
import studio.otherstuff.ambulando.core.store.beginTaskRelationshipRead
import studio.otherstuff.ambulando.core.store.failTaskRelationshipRead
import studio.otherstuff.ambulando.core.store.upsertTasks
import studio.otherstuff.ambulando.core.transport.task
import studio.otherstuff.ambulando.core.transport.DocumentBodyError
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.channelTasks
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.store

// Write backends for the surface editors (canvas, Excalidraw board, task tree), bound by the app to one connection and
// workspace. Every write is signed by that connection's own unlocked identity on its origin with the workspace's
// `app_npub`; reads may use the background reader of the same identity.

/** Tower's refusal as the editors' [TowerHttpFailure], so their own classification applies. */
private inline fun <T> surfaceHttp(block: () -> T): T = try {
    block()
} catch (e: TowerError.Http) {
    throw SurfaceHttpException(e.status, e.code)
}

/**
 * [CanvasEditBackend] over the signed Tower client and the local store. Keyed refreshes after a write are queued by the
 * read-side sync ([refreshCanvases] / [refreshCanvas], forced at user priority).
 *
 * Android: a canvas Tower no longer shows this viewer (`404 canvas_unavailable`) is flagged `access_removed` in the
 * store (its snapshot stays readable, labelled), not deleted.
 */
class StoreCanvasEditBackend(
    private val store: LocalStore,
    private val identity: IdentityService,
    transport: HttpTransport,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
    private val refreshList: suspend () -> Unit = {},
    private val refreshOne: suspend (String) -> Unit = {},
) : CanvasEditBackend {
    private val client = TowerClient(TowerOrigin(connection.origin), transport, identity)
    private val api = TowerAPI(client)

    override suspend fun requireSigner() = identity.requireSigner(connection.signerNpub)

    /** Creates an empty board File in `channelId` (see [createBoardFile]); the caller then adds it to a canvas. */
    suspend fun createBoard(channelId: String, title: String): String {
        requireSigner()
        if (workspace.accessRemoved) throw SurfaceHttpException(403, "access_removed")
        return surfaceHttp { api.createBoardFile(workspace.id, workspace.appNpub, channelId, title) }
    }

    override suspend fun exchange(method: String, path: String, body: ByteArray): Pair<Int, ByteArray> {
        val parts = path.split('/')
        val canvas = parts.indexOf("canvases").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) }
        if (workspace.accessRemoved || canvas?.let { store.reader.isAccessRemoved(workspace.id, FlaggedTable.CANVASES, it) } == true) throw SurfaceHttpException(403, "access_removed")
        val reply = client.exchange(method, path, workspace.appNpub, body)
        return reply.status to reply.body
    }

    override suspend fun reloadList() {
        store.writer.replaceCanvases(workspace.id, api.canvases(workspace.id, workspace.appNpub))
    }

    override suspend fun reloadSnapshot(canvasId: String) {
        val (canvas, snapshot) = api.canvasSnapshot(workspace.id, workspace.appNpub, canvasId)
        store.writer.saveCanvasSnapshot(snapshot, canvas)
    }

    override suspend fun hasStoredSnapshot(canvasId: String): Boolean = store.reader.canvasSnapshot(workspace.id, canvasId) != null

    override suspend fun removeStoredCanvas(canvasId: String) {
        store.writer.markAccessRemoved(workspace.id, AccessTarget.Canvas(canvasId.lowercase()))
    }

    override suspend fun refreshCanvases() = refreshList()

    override suspend fun refreshCanvas(canvasId: String) = refreshOne(canvasId)

    /** The editor for this workspace over this backend. */
    fun editor(): CanvasEditor = CanvasEditor(workspace.id, this)
}

// MARK: Board versions

/** A board File's versions, newest first, and its current version. */
suspend fun TowerAPI.boardVersions(workspaceId: String, appNpub: String, fileId: String): Pair<String?, List<BoardVersion>> {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions", query = listOf("limit" to "100"),
        appNpub = appNpub), "board versions")
    return BoardRequests.parseVersions(o, fileId)
}

/** Exact bytes of one board version; checked against `sha256` when it is known. */
suspend fun TowerAPI.boardVersionBytes(workspaceId: String, appNpub: String, fileId: String, versionId: String, sha256: String?): ByteArray {
    val path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions/" + TowerAPI.uuid(versionId) + "/object"
    val bytes = client.request(path = path, appNpub = appNpub)
    if (bytes.size > BoardScene.MAX_BYTES) throw BoardScene.SceneException.TooLarge("This board is larger than the 24 MB limit.")
    if (!sha256.isNullOrEmpty() && Sha256Hex.of(bytes) != sha256) throw DocumentBodyError.Integrity
    return bytes
}

/**
 * Creates a new, empty Excalidraw board as a private File in `channelId` (version 1), as the web's "Create board": the
 * scene is uploaded first, then registered as `<title>.excalidraw` with `metadata.kind = excalidraw_board`. Returns the
 * new file id. Only people who can read the channel can open the board.
 */
suspend fun TowerAPI.createBoardFile(workspaceId: String, appNpub: String, channelId: String, title: String): String {
    val name = JsonFields.prefix(title.trim(), CanvasEditor.MAX_TITLE).ifEmpty { "Untitled board" }
    val bytes = BoardScene.serialize(BoardScene.empty())
    val objectId = store(workspaceId, appNpub, bytes, BoardScene.CONTENT_TYPE, BoardScene.fileName(name), null) { }
    val body = jsonObjectOf("storage_object_id" to objectId, "display_name" to BoardScene.fileName(name),
        "metadata" to jsonObjectOf("kind" to "excalidraw_board", "title" to name))
    val o = obj(client.request(method = "POST", path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/files", appNpub = appNpub,
        jsonBody = jsonBody(body)), "board file")
    return (o["file"] as? JsonObject)?.string("id")?.let { JsonFields.lowerUuid(it) } ?: throw TowerError.Malformed("board file")
}

/** Renames a board File (metadata only: no new version). Tower must confirm the exact name. */
suspend fun TowerAPI.renameBoardFile(workspaceId: String, appNpub: String, fileId: String, name: String): String {
    val o = obj(client.request(method = "PATCH", path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId), appNpub = appNpub,
        jsonBody = jsonBody(BoardRequests.renameBody(name))), "board rename")
    if (!BoardRequests.confirmRename(o, fileId, name)) throw TowerError.Malformed("board rename")
    return name
}

/** Publishes `bytes` as the board's next version against `baseVersionId` (409 `stale_base_version` when someone saved first). */
suspend fun TowerAPI.createBoardVersion(
    workspaceId: String, appNpub: String, fileId: String, baseVersionId: String, bytes: ByteArray, title: String, summary: String,
): BoardVersion {
    val objectId = store(workspaceId, appNpub, bytes, BoardScene.CONTENT_TYPE, BoardScene.fileName(title), null) { }
    TowerAPI.uuid(baseVersionId)
    val body = BoardRequests.createVersionBody(baseVersionId, objectId, summary, newRequestId())
    val o = obj(client.request(method = "POST", path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions", appNpub = appNpub,
        jsonBody = jsonBody(body)), "board version")
    val version = (o["version"] as? JsonObject)?.let(BoardVersion::parse) ?: throw TowerError.Malformed("board version")
    return if (version.sha256.isEmpty()) version.copy(sha256 = Sha256Hex.of(bytes)) else version
}

/**
 * Tower access for one board of `workspace`, as `connection`'s identity. Reads use the identity (or its background
 * reader); a save or rename requires the unlocked signer of this connection.
 */
class TowerBoardTransport(
    private val identity: IdentityService,
    transport: HttpTransport,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
    fileId: String,
    private val title: String,
    transfers: ObjectTransfers? = null,
) : BoardController.Transport {
    private val fileId = fileId.lowercase()
    private val api = TowerAPI(TowerClient(TowerOrigin(connection.origin), transport, identity), transfers)

    private suspend fun requireSigner() {
        try {
            identity.requireSigner(connection.signerNpub)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BoardEditError.Locked()
        }
    }

    override suspend fun versions(): Pair<String?, List<BoardVersion>> = surfaceHttp { api.boardVersions(workspace.id, workspace.appNpub, fileId) }

    override suspend fun read(versionId: String, sha256: String?): ByteArray =
        surfaceHttp { api.boardVersionBytes(workspace.id, workspace.appNpub, fileId, versionId, sha256) }

    override suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String): BoardVersion {
        requireSigner()
        return surfaceHttp { api.createBoardVersion(workspace.id, workspace.appNpub, fileId, baseVersionId, bytes, title, summary) }
    }

    override suspend fun canSign(): Boolean = runCatching { identity.requireSigner(connection.signerNpub) }.isSuccess

    override suspend fun rename(name: String): String {
        requireSigner()
        return surfaceHttp { api.renameBoardFile(workspace.id, workspace.appNpub, fileId, name) }
    }
}

// MARK: Task tree

/** `GET /tasks/:t/relationships?limit&cursor` for one workspace; refusals surface as [TowerHttpFailure]. */
class TowerTaskRelationshipsReader(private val api: TowerAPI, private val workspaceId: String, private val appNpub: String, private val limit: Int = 100) :
    TaskTreeService.RelationshipsReader {
    override suspend fun page(taskId: String, cursor: String?): TaskTreeService.RelationshipsPage {
        val query = mutableListOf("limit" to limit.coerceIn(1, 100).toString())
        cursor?.let { query.add("cursor" to it) }
        val data = surfaceHttp {
            api.client.request(path = TowerAPI.base(workspaceId) + "/tasks/" + TowerAPI.uuid(taskId) + "/relationships", query = query, appNpub = appNpub)
        }
        val o = parseJsonObjectOrNull(data) ?: throw TowerError.Malformed("task relationships")
        return TaskTreeService.parseRelationships(o, workspaceId, taskId, cursor)
    }
}

/**
 * Task tree reads and writes with the store: relationship reads are stored like other task reads; creation is one
 * confirmed request with a stable `client_request_id` (the caller keeps it for a retry), so a retry after an uncertain
 * answer finds the same task instead of creating another.
 */
/** How one call to [TaskTreeWrites.loadRelationships] ended. */
enum class TaskRelationshipOutcome {
    /** The last page was read: the confirmed membership was replaced. */
    COMPLETE,
    /** The page budget ran out: more pages remain and the next call resumes. */
    PARTIAL,
    /** A newer read of the same parent replaced this one. */
    SUPERSEDED,
}

class TaskTreeWrites(
    private val store: LocalStore,
    private val identity: IdentityService,
    private val transport: HttpTransport,
) {
    private fun api(connection: ConnectionRecord) = TowerAPI(TowerClient(TowerOrigin(connection.origin), transport, identity))

    companion object {
        /** Pages (of 100) read per call for one parent: 1,000 children before the list resumes on the next call. */
        const val RELATIONSHIP_PAGE_BUDGET = 10
        private val REFUSALS = setOf(401, 403, 404)
    }

    /** The authorized tasks reachable from `root` (and `upstream` context tasks), bounded like the web's tree. */
    suspend fun load(
        connection: ConnectionRecord, workspace: WorkspaceRecord, root: String, upstream: List<String> = emptyList(), limit: Int = 100,
    ): TaskTreeService.Loaded {
        val loaded = TaskTreeService.load(TowerTaskRelationshipsReader(api(connection), workspace.id, workspace.appNpub), root, upstream, limit)
        val records = loaded.rows.mapNotNull { runCatching { TaskRecord.parse(it) }.getOrNull() }.filter { it.workspaceId == workspace.id }
        if (records.isNotEmpty()) store.writer.upsertTasks(workspace.id, records)
        return loaded
    }

    /**
     * Reads one parent's direct-child list page by page into the store (bounded by [pageBudget] pages per call; a longer list
     * resumes at its stored cursor on the next call). Each page's tasks, children and read state are stored together, so a
     * failure part-way keeps what was read, and only a read that reaches its last page replaces the confirmed membership.
     * Descendants are not read here: each expanded child loads its own list. Throws [TaskTreeError.Unavailable] when Tower
     * refuses the parent (recorded as restricted) and rethrows other failures after recording them.
     */
    suspend fun loadRelationships(
        connection: ConnectionRecord, workspace: WorkspaceRecord, parentId: String, pageBudget: Int = RELATIONSHIP_PAGE_BUDGET,
        reader: TaskTreeService.RelationshipsReader? = null,
    ): TaskRelationshipOutcome {
        val parent = parentId.lowercase()
        val source = reader ?: TowerTaskRelationshipsReader(api(connection), workspace.id, workspace.appNpub)
        val begin = store.writer.beginTaskRelationshipRead(workspace.id, parent)
        var cursor = begin.cursor
        val resumed = cursor != null
        val seen = HashSet<String>()
        var pages = 0
        while (pages < pageBudget) {
            val fromCursor = cursor
            val page = try {
                source.page(parent, cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = (e as? TowerHttpFailure)?.status ?: (e as? TowerError.Http)?.status
                if (status in REFUSALS) {
                    store.writer.failTaskRelationshipRead(workspace.id, parent, begin.generation, restricted = true)
                    throw TaskTreeError.Unavailable
                }
                // A stored cursor Tower no longer accepts restarts the read instead of retrying it forever.
                store.writer.failTaskRelationshipRead(workspace.id, parent, begin.generation, restricted = false, restart = resumed && pages == 0 && status != null && status < 500)
                throw e
            }
            val records = (listOf(page.task) + page.tasks).mapNotNull { runCatching { TaskRecord.parse(it) }.getOrNull() }.filter { it.workspaceId == workspace.id }
            val children = page.tasks.mapNotNull { TaskTreeNode.fromRow(it) }.filter { it.parentId == parent && it.id != parent }.map { it.id }
            if (!store.writer.applyTaskRelationshipPage(workspace.id, parent, begin.generation, records, children, page.unavailable, page.next, fromCursor = fromCursor)) return TaskRelationshipOutcome.SUPERSEDED
            pages += 1
            cursor = page.next
            if (cursor == null) break
            if (!seen.add(cursor)) {
                store.writer.failTaskRelationshipRead(workspace.id, parent, begin.generation, restricted = false, failure = "repeated cursor", restart = true)
                throw TowerError.Malformed("task relationships repeated cursor")
            }
        }
        if (cursor != null) return TaskRelationshipOutcome.PARTIAL
        loadNamedParent(connection, workspace, parent)
        return TaskRelationshipOutcome.COMPLETE
    }

    /** Stores the task this one names as parent through the normal authorized read, so the parent link can open. Best effort. */
    private suspend fun loadNamedParent(connection: ConnectionRecord, workspace: WorkspaceRecord, taskId: String) {
        try {
            val task = store.reader.task(workspace.id, taskId) ?: return
            val parent = TaskHierarchy.parentId(task) ?: return
            if (store.reader.task(workspace.id, parent) != null) return
            store.writer.upsertTasks(workspace.id, listOf(api(connection).task(workspace.id, workspace.appNpub, parent)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The parent stays labelled unavailable.
        }
    }

    /** Tasks of a channel to choose a root from. */
    suspend fun channelTasks(connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String): List<TaskRecord> {
        val tasks = api(connection).channelTasks(workspace.id, workspace.appNpub, channelId)
        store.writer.upsertTasks(workspace.id, tasks)
        return tasks
    }

    /** Creates a task in `channelId`: a child of `parent`, or the successor of `predecessor` (in its parent), or a base task. */
    suspend fun create(
        connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, scopeId: String?, title: String,
        parent: String? = null, predecessor: TaskTreeNode? = null, requestId: String,
    ): TaskRecord {
        val body = TaskTreeService.createBody(title, parent, predecessor, requestId)
        try {
            identity.requireSigner(connection.signerNpub)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TaskTreeError.Locked
        }
        val reply = api(connection).client.exchange("POST", TowerAPI.base(workspace.id) + "/channels/" + TowerAPI.uuid(channelId) + "/tasks",
            workspace.appNpub, JsonFields.encodeBytes(body))
        val row = TaskTreeService.confirmCreated(reply.status, parseJsonObjectOrNull(reply.body), channelId, scopeId)
        val task = runCatching { TaskRecord.parse(row) }.getOrNull() ?: throw TaskTreeError.Failed("The task was not confirmed. Retry the same title.")
        store.writer.upsertTasks(workspace.id, listOf(task))
        return task
    }
}
