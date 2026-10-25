package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.TowerTime
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.io.File
import java.net.UnknownHostException
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The in-process stand-in for Tower behind sample-data mode (debug builds only; the app gates it behind
 * `BuildConfig.DEBUG` and an explicit launch flag). Every app code path — identity vault, signing, store, sync, outbox —
 * runs for real; only the transport is replaced, so nothing leaves the device. It is **not** Tower and never evidence of
 * live behaviour: every workspace it lists is labelled "Sample", and every host is under `.invalid`.
 *
 * It serves:
 * - the public nStack descriptor and authenticated workspace discovery;
 * - scopes, channels, members, `GET /me`, scope order and scope/channel management (writes to the Operations scope are
 *   refused with 403, as for a member without Manage access);
 * - chat: forward-paged messages, threads, `POST /channels/:c/messages` with Tower's kind 33358 checks and
 *   `client_request_id` replay (200 `replayed: true`; 409 `idempotency_conflict` for a different message), message
 *   edit (author only, revision signature, `row_version`) and delete;
 * - the outbox event log (`GET /events`, `GET /sync`) and its SSE stream ([events]);
 * - tasks, documents, files, storage, leases, inbox and reactions ([work], [labWork]); Context Tree, canvases, agents,
 *   Toolbox and pins ([surfaces], [labSurfaces]); board versions ([boards]).
 *
 * Every signed request must carry a valid NIP-98 event over the exact URL, method and body ([SampleAuth]); a bad one is
 * answered 401 like Tower, so emulator tests prove the signing contract. Presigned object-store uploads must arrive
 * unsigned (any credential is recorded in `work.credentialLeaks`).
 *
 * State persists under [directory] (when given) so sample data survives a relaunch; [prepare] seeds it once.
 */
class SampleTower(
    val directory: File? = null,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Simulated network latency per request, so loading and sending states are observable. */
    @Volatile var latencyMillis: Long = 0,
) : HttpTransport {
    val origin: TowerOrigin = TowerOrigin(SampleHosts.ORIGIN)
    val auth = SampleAuth(clock)

    private val lock = Any()
    private val chat = SampleState(directory?.let { File(it, "sample-chat.json") }, listOf("posted", "revised", "requests", "events", "managed"))
    private val tick = MutableStateFlow(0L)
    private var actor = ""
    private var offlineFlag = false
    private var failScopesFlag = false
    private var rejectNext = false
    private var scopeOrderConflictFlag = false
    private var workspaceListReadCount = 0

    private fun stateFile(name: String): File? = directory?.let { File(it, name) }

    /** Tasks, documents, files, storage, inbox and reactions of the Studio workspace. */
    val work = SampleWorkTower(SampleIds.STUDIO, SampleHosts.ORIGIN, SampleHosts.OBJECTS, stateFile("sample-work.json"), auth)

    /** The Lab workspace has no tasks, documents or files (empty lists, not errors). */
    val labWork = SampleWorkTower(SampleIds.LAB, SampleHosts.ORIGIN, SampleHosts.OBJECTS_LAB, stateFile("sample-work-lab.json"), auth)

    /** Context Tree, canvases, agents, Toolbox and pins of the Studio workspace; the Lab workspace has none. */
    val surfaces = SampleSurfaceTower(SampleIds.STUDIO, stateFile("sample-surfaces.json"), auth)
    val labSurfaces = SampleSurfaceTower(SampleIds.LAB, stateFile("sample-surfaces-lab.json"), auth)

    /** Board File versions (list, read, save with a stale-base check) and their canvas projection. */
    val boards = SampleBoardTower(SampleIds.STUDIO, stateFile("sample-boards.json"), auth)

    /** SSE stand-in for `GET /workspaces/:w/events/stream`, fed from this tower's event log. */
    val events: EventStreamTransport = SampleEventStream(this)

    init {
        val work = work
        boards.objectBytes = { id -> work.completedObjectBytes(id) }
        surfaces.recordLookup = { id -> lookupRecord(id) }
        work.onChange = { emit(SampleIds.STUDIO, it) }
        labWork.onChange = { emit(SampleIds.LAB, it) }
        surfaces.onChange = { emit(SampleIds.STUDIO, it) }
        labSurfaces.onChange = { emit(SampleIds.LAB, it) }
        boards.onChange = { emit(SampleIds.STUDIO, it) }
    }

    // MARK: Toggles (the sample-data controls in Settings and UI test launch flags)

    /** Every request fails as if there were no network (open event streams drop). */
    var offline: Boolean
        get() = synchronized(lock) { offlineFlag }
        set(value) { synchronized(lock) { offlineFlag = value }; bump() }

    /** Scope lists answer HTTP 500 as Tower does for an internal error. */
    var failScopes: Boolean
        get() = synchronized(lock) { failScopesFlag }
        set(value) = synchronized(lock) { failScopesFlag = value }

    /** The next chat send is refused with `400 validation_error`. */
    var rejectNextSend: Boolean
        get() = synchronized(lock) { rejectNext }
        set(value) = synchronized(lock) { rejectNext = value }

    /** The next scope-order save meets an edit from another session (409 `scope_order_conflict`). */
    var scopeOrderConflict: Boolean
        get() = synchronized(lock) { scopeOrderConflictFlag }
        set(value) = synchronized(lock) { scopeOrderConflictFlag = value }

    /** How often the workspace list was read (the "Store workspace list again" control re-runs discovery). */
    val workspaceListReads: Int get() = synchronized(lock) { workspaceListReadCount }

    /** The npub Tower resolves as the actor; set from each request's NIP-98 signer, or by [prepare]. */
    var actorNpub: String
        get() = synchronized(lock) { actor }
        set(value) {
            synchronized(lock) { actor = value }
            if (work.actorNpub != value) work.actorNpub = value
            if (labWork.actorNpub != value) labWork.actorNpub = value
        }

    /** A signed client for this tower (the app normally builds its own through discovery). */
    fun client(signer: studio.otherstuff.ambulando.core.nostr.EventSigner?): TowerClient = TowerClient(origin, this, signer)

    /**
     * First launch: binds the sample identity's npub and seeds tasks, documents, files, Context Tree, canvases, agents,
     * Toolbox and pins once per [directory]. Options mirror the iOS launch flags.
     */
    fun prepare(actorNpub: String, docLeaseHeld: Boolean = false, docConflict: Boolean = false, boardConflict: Boolean = false) {
        this.actorNpub = actorNpub
        work.externalSaveBeforeNextDocBodySave = docConflict
        boards.externalSaveBeforeNextPublish = boardConflict
        SampleSeed.seedWork(this, docLeaseHeld)
        SampleSeed.seedSurfaces(this)
    }

    // MARK: Event log

    /** Bumps on every new event and offline change (the stream waits on it). */
    internal val changes: StateFlow<Long> get() = tick

    private fun bump() { tick.value = tick.value + 1 }

    internal fun eventRows(workspace: String, after: Int, limit: Int): List<Row> = synchronized(lock) {
        chat.rows("events").filter { it["workspace_id"] == workspace && (it["row_version"].asInt() ?: 0) > after }.take(limit)
    }

    val eventHead: Int get() = synchronized(lock) { chat.count("events") }

    private fun emit(workspace: String, change: SampleChange) {
        synchronized(lock) {
            val scope = change.scopeId ?: change.channelId?.let { channelScope(workspace, it) }
            appendEventLocked(workspace, scope, change.channelId, change.entityType, change.entityId, change.operation, change.rowVersion, change.payload)
            chat.persist()
        }
        bump()
    }

    private fun appendEventLocked(
        workspace: String, scope: String?, channel: String?, type: String, id: String, operation: String, rowVersion: Long?, payload: Map<String, String>,
    ) {
        val row = chat.count("events") + 1
        chat.put(
            "events", row.toString(),
            linkedMapOf(
                "id" to newUUID(), "cursor" to eventCursor(row), "row_version" to row.toLong(), "workspace_id" to workspace, "scope_id" to scope,
                "channel_id" to channel, "entity_type" to type, "entity_id" to id, "operation" to operation, "entity_row_version" to rowVersion,
                "payload" to payload, "event_type" to "flightdeck_pg.$type.$operation", "created_at" to TowerTime.now(),
            ),
        )
    }

    // MARK: Live updates ("another member posts while you type")

    /** Another member (Ana) posts a new conversation, as if from another device; delivered through events and the stream. */
    fun postAsMember(channel: String = SampleIds.GENERAL, body: String, workspace: String = SampleIds.STUDIO, sender: String = SampleIds.ANA,
                     label: String? = SampleContent.ANA_LABEL): String {
        val id = newUUID()
        synchronized(lock) {
            val now = TowerTime.now()
            val thread = newUUID()
            chat.put(
                "posted", id,
                linkedMapOf(
                    "id" to id, "workspace_id" to workspace, "channel_id" to channel, "thread_id" to thread, "thread_source_message_id" to id, "body" to body,
                    "client_request_id" to null, "record_state" to "active", "row_version" to 1L, "sender_npub" to sender, "created_by_actor_label" to label,
                    "created_at" to now, "updated_at" to now,
                ),
            )
            appendEventLocked(workspace, channelScope(workspace, channel), channel, "message", id, "created", 1, mapOf("thread_id" to thread))
            chat.persist()
        }
        bump()
        return id
    }

    /** Posts [count] messages from another member, one every [interval], in [scope] (cancel the job to stop). */
    fun startLive(scope: CoroutineScope, count: Int = 5, interval: Duration = 3.seconds, channel: String = SampleIds.GENERAL): Job = scope.launch {
        for (n in 1..count) {
            delay(interval)
            postAsMember(channel, "Live update $n from Ana (sample)")
        }
    }

    // MARK: Transport

    override suspend fun send(request: HttpRequest): HttpResponse {
        if (latencyMillis > 0) delay(latencyMillis)
        val (status, body) = route(request)
        return when (body) {
            is ByteArray -> HttpResponse(status, body, mapOf("Content-Type" to "application/octet-stream"))
            null -> HttpResponse(status, ByteArray(0))
            else -> HttpResponse(status, SampleJson.encode(body), mapOf("Content-Type" to "application/json"))
        }
    }

    private fun route(request: HttpRequest): Reply {
        val url = SampleURL.of(request) ?: throw UnknownHostException(request.url)
        if (offline) throw SampleOfflineException()
        if (url.host == work.objectHost || url.host == labWork.objectHost) {
            // Presigned object-store upload: never signed (the work stand-in records any credential that arrives).
            return work.handle(request) ?: labWork.handle(request) ?: errorReply(404, "not_found")
        }
        // The sample never reaches another host.
        if (url.host != origin.host || url.url.scheme != "https") throw UnknownHostException(url.host)
        if (url.path == TowerClient.DESCRIPTOR_PATH) {
            if (request.method.uppercase() != "GET") return errorReply(405, "method_not_allowed")
            return 200 to descriptor()
        }
        val signer = when (val result = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(result.reason)
            is SampleAuth.Result.Valid -> result.npub
        }
        if (actorNpub != signer) actorNpub = signer
        // Surface routes first: their ownership is exact (board versions live under `/files/:f/versions/:v/object`).
        val handled = boards.handle(request) ?: surfaces.handle(request) ?: labSurfaces.handle(request) ?: work.handle(request) ?: labWork.handle(request)
        if (handled != null) return boards.project(handled, request)
        return chatRoute(request, url, signer)
    }

    private fun descriptor(): Map<String, Any?> = linkedMapOf(
        "schema" to "org.wingman.nstack.services", "schema_version" to 1, "stack" to mapOf("tower_npub" to SampleIds.APP, "name" to "Sample Tower"),
        "services" to listOf(
            linkedMapOf(
                "id" to "tower", "kind" to "tower", "endpoint" to origin.url, "protocols" to listOf("https", "nip-98"),
                "availability" to mapOf("status" to "available (sample data on this device)"),
            ),
        ),
    )

    private fun workspace(id: String, label: String): Map<String, Any?> = linkedMapOf(
        "identity" to linkedMapOf("workspace_id" to id, "app_npub" to SampleIds.APP, "workspace_owner_npub" to SampleIds.ANA), "label" to label,
        "slug" to label.lowercase().replace(' ', '-'), "tower_base_url" to origin.url, "membership" to mapOf("role" to "member"),
    )

    private fun chatRoute(request: HttpRequest, url: SampleURL, signer: String): Reply {
        val base = "/api/v4/flightdeck-pg/workspaces"
        val method = request.method.uppercase()
        if (url.path == base) {
            if (method != "GET") return errorReply(404, "not_found")
            synchronized(lock) { workspaceListReadCount += 1 }
            return 200 to linkedMapOf(
                "workspaces" to listOf(workspace(SampleIds.STUDIO, "Sample Studio"), workspace(SampleIds.LAB, "Sample Lab")), "next_cursor" to null,
            )
        }
        if (!url.path.startsWith("$base/")) return errorReply(404, "not_found")
        val parts = url.path.removePrefix("$base/").split('/').filter { it.isNotEmpty() }
        val w = parts.firstOrNull()
        if (w != SampleIds.STUDIO && w != SampleIds.LAB) return errorReply(404, "not_found")
        val rest = parts.drop(1)
        val n = rest.size
        val head = rest.firstOrNull()
        return when {
            method == "GET" && n == 1 && head == "scopes" && failScopes -> 500 to linkedMapOf("error" to "Internal error", "code" to "internal_error", "status" to 500)
            method == "GET" && n == 1 && head == "scopes" -> synchronized(lock) { 200 to linkedMapOf("scopes" to scopeRows(w), "next_cursor" to null) }
            method == "GET" && n == 3 && head == "scopes" && rest[2] == "channels" ->
                synchronized(lock) { 200 to linkedMapOf("channels" to channelRows(w, rest[1]), "next_cursor" to null) }
            method == "GET" && n == 1 && head == "me" -> 200 to linkedMapOf(
                "actor" to linkedMapOf("actor_id" to SampleIds.ACTOR, "npub" to signer), "membership" to mapOf("role" to "member"),
                "permissions" to listOf("channel.create", "channel.manage", "channel.read", "channel.write", "scope.create", "workspace.read"),
            )
            method == "GET" && n == 2 && head == "me" && rest[1] == "scope-order" -> synchronized(lock) { 200 to mapOf("scope_order" to scopeOrder(w)) }
            (method == "POST" && n == 3 && head == "scopes" && rest[2] == "channels") || (method == "POST" && n == 1 && head == "scopes") ||
                ((method == "PATCH" || method == "DELETE") && n == 2 && (head == "scopes" || head == "channels")) ||
                (method == "POST" && n == 3 && head == "channels" && rest[2] == "reorder") ||
                (method == "PUT" && n == 2 && head == "me" && rest[1] == "scope-order") -> manage(request, w, rest)
            method == "GET" && n == 1 && head == "members" -> 200 to linkedMapOf(
                "members" to listOf(
                    member(signer, SampleContent.YOU_LABEL, "human"), member(SampleIds.ANA, SampleContent.ANA_LABEL, "human"),
                    member(SampleIds.ORBIT, SampleContent.ORBIT_LABEL, "agent"), member(SampleIds.BEA, null, "human"),
                ),
                "next_cursor" to null,
            )
            method == "GET" && n == 3 && head == "channels" && rest[2] == "messages" -> synchronized(lock) {
                val thread = url.query("thread_id")
                val all = allMessages().filter { it["workspace_id"] == w && it["channel_id"] == rest[1] && (thread == null || it["thread_id"] == thread) }
                val start = url.query("cursor")?.let { c ->
                    runCatching { String(Base64.getDecoder().decode(c), Charsets.UTF_8).toInt() }.getOrNull() ?: return errorReply(400, "invalid_cursor")
                } ?: 0
                val limit = minOf(200, url.query("limit")?.toIntOrNull() ?: 50).coerceAtLeast(1)
                val page = all.drop(start).take(limit)
                val next = if (start + page.size < all.size) Base64.getEncoder().encodeToString((start + page.size).toString().toByteArray()) else null
                200 to linkedMapOf("messages" to page, "next_cursor" to next)
            }
            method == "GET" && n == 3 && head == "channels" && rest[2] == "threads" -> synchronized(lock) {
                val roots = allMessages().filter { it["workspace_id"] == w && it["channel_id"] == rest[1] && it["id"] == it["thread_source_message_id"] }
                200 to linkedMapOf("threads" to roots.map { threadOf(it) }, "next_cursor" to null)
            }
            method == "GET" && n == 2 && head == "threads" -> synchronized(lock) {
                val root = allMessages().firstOrNull { it["workspace_id"] == w && it["thread_id"] == rest[1] && it["id"] == it["thread_source_message_id"] }
                    ?: return errorReply(404, "thread_not_found")
                200 to mapOf("thread" to threadOf(root))
            }
            method == "POST" && n == 3 && head == "channels" && rest[2] == "messages" -> post(request, w, rest[1], signer)
            method == "GET" && n == 1 && head == "events" -> {
                val cursor = url.query("cursor")
                val after = eventRow(cursor) ?: return errorReply(400, "validation_error")
                val page = eventRows(w, after, minOf(200, url.query("limit")?.toIntOrNull() ?: 50).coerceAtLeast(1))
                200 to linkedMapOf("events" to page, "next_cursor" to (page.lastOrNull()?.get("cursor") ?: cursor))
            }
            method == "GET" && n == 1 && head == "sync" ->
                200 to linkedMapOf("mode" to "snapshot", "through_cursor" to eventCursor(eventHead), "channel_bundles" to emptyList<Any>())
            method == "GET" && n == 2 && head == "messages" -> synchronized(lock) {
                allMessages().firstOrNull { it["workspace_id"] == w && it["id"] == rest[1] }?.let { 200 to mapOf("message" to it) }
                    ?: errorReply(404, "message_not_found")
            }
            (method == "PATCH" || method == "DELETE") && n == 2 && head == "messages" -> revise(request, w, rest[1], url, signer)
            else -> errorReply(404, "not_found")
        }
    }

    // MARK: Messages (lock held unless noted)

    /** Seeded history spans three days (general #1–13, #14–26, the rest), so chat shows its date separators. */
    private fun time(n: Int): String {
        val day = if (n <= 13) 21 else if (n <= 26) 22 else 23
        return String.format("2026-10-%02dT%02d:%02d:00.000Z", day, 8 + n / 60, n % 60)
    }

    /** Seeded history: general has 40 conversations (one with replies), design/deploys/notes a few each. */
    private val seededMessages: List<Row> by lazy {
        val rows = ArrayList<Row>()
        fun add(n: Int, workspace: String, channel: String, body: String, sender: String, label: String?, thread: String? = null) {
            val id = SampleIds.message(n)
            val threadId = thread ?: SampleIds.thread(n)
            val source = if (thread == null) id else SampleIds.message(thread.takeLast(12).toInt())
            rows.add(
                linkedMapOf(
                    "id" to id, "workspace_id" to workspace, "channel_id" to channel, "thread_id" to threadId, "thread_source_message_id" to source,
                    "body" to body, "client_request_id" to null, "record_state" to "active", "row_version" to 1L, "sender_npub" to sender,
                    "created_by_actor_label" to label, "created_at" to time(n), "updated_at" to time(n),
                ),
            )
        }
        val topics = listOf(
            "Walkthrough notes for the passage screens", "Theme tokens are synced with the web app", "Who owns the deploy checklist?",
            "Draft agenda for Thursday", "Sync storm results look good", "Native chat review", "Docs index needs a refresh",
            "Files upload uses storage prepare", "Inbox counts mentions and tasks", "Release notes for build 2",
        )
        val ana = SampleIds.ANA to SampleContent.ANA_LABEL
        val orbit = SampleIds.ORBIT to SampleContent.ORBIT_LABEL
        for (n in 1..40) {
            val (sender, label) = if (n % 3 != 0) ana else orbit
            var body = "${topics[(n - 1) % topics.size]} (#$n)"
            if (n == 38) body = "@[Orbit](mention:agent:${SampleIds.ORBIT}) can you check the outbox retry states?"
            add(n, SampleIds.STUDIO, SampleIds.GENERAL, body, sender, label)
        }
        listOf("Looks right on my phone", "Retry kept the same request id", "Ship it").forEachIndexed { i, text ->
            val (sender, label) = if (i % 2 == 0) orbit else ana
            add(100 + i, SampleIds.STUDIO, SampleIds.GENERAL, text, sender, label, thread = SampleIds.thread(39))
        }
        add(198, SampleIds.STUDIO, SampleIds.DESIGN, "Shared the colour swatches for the new surfaces", SampleIds.BEA, null)
        add(199, SampleIds.STUDIO, SampleIds.DESIGN, "Dark mode keeps the same purple highlight", SampleIds.BEA, null)
        add(200, SampleIds.STUDIO, SampleIds.DESIGN, "Passage mark stays on its white backing", ana.first, ana.second)
        add(202, SampleIds.STUDIO, SampleIds.DESIGN, SampleContent.RICH_MESSAGE, orbit.first, orbit.second)
        add(201, SampleIds.STUDIO, SampleIds.DEPLOYS, "Staging is green", orbit.first, orbit.second)
        add(205, SampleIds.STUDIO, SampleIds.DEPLOYS, "Handing the deploy rota over", SampleIds.FORMER, null)
        add(300, SampleIds.LAB, SampleIds.NOTES, "Lab workspace notes live here", ana.first, ana.second)
        rows
    }

    private fun allMessages(): List<Row> {
        val revised = chat.rows("revised").associateBy { it["id"] as String }
        return (seededMessages.map { SampleJson.row(it)!! } + chat.rows("posted"))
            .map { revised[it["id"]] ?: it }
            .sortedWith(compareBy({ it["created_at"] as? String ?: "" }, { it["id"] as? String ?: "" }))
    }

    private fun threadOf(root: Row): Row = linkedMapOf(
        "id" to root["thread_id"], "workspace_id" to root["workspace_id"], "channel_id" to root["channel_id"], "source_message_id" to root["id"],
        "title" to MentionCodec.plain(root["body"].asString() ?: ""), "record_state" to "active", "row_version" to 1L, "updated_at" to root["updated_at"],
    )

    private fun member(npub: String, name: String?, kind: String): Map<String, Any?> = mapOf(
        "actor" to linkedMapOf(
            "actor_id" to UUID.nameUUIDFromBytes(npub.toByteArray()).toString(), "npub" to npub, "kind" to kind, "display_name" to name,
        ),
    )

    /**
     * `POST /channels/:c/messages`: Tower trims the body, verifies the kind 33358 instruction against it and the
     * workspace/channel/thread, then replays by `client_request_id` (the hash covers channel, thread, `create_thread`,
     * body and attachments, not the signature).
     */
    private fun post(request: HttpRequest, w: String, channel: String, signer: String): Reply {
        val o = SampleJson.objectOf(request.body)
        val body = (o["body"].asString() ?: "").trimWhitespace()
        val attachments = o["metadata"].asMap()?.get("attachments").asList() ?: emptyList()
        var change: Pair<String, String>? = null
        val reply: Reply = synchronized(lock) {
            if (channelScope(w, channel) == null) return@synchronized errorReply(404, "channel_not_found")
            if (body.isEmpty() && attachments.isEmpty()) return@synchronized validationError("body must be non-empty")
            val threadRequested = o["thread_id"].asString()?.lowercase()
            SampleMessageSignature.check(o, body, w, channel, threadRequested, signer)?.let { return@synchronized validationError(it) }
            if (rejectNext) {
                rejectNext = false
                return@synchronized validationError("sample rejection requested in Settings")
            }
            val requestId = o["client_request_id"].asString()
            val hash = Sha256Hex.of(
                jsonBody(
                    toJsonElement(
                        linkedMapOf(
                            "channel_id" to channel, "thread_id" to threadRequested, "create_thread" to (o["create_thread"] == true), "body" to body,
                            "attachments" to attachments,
                        ),
                    ) as JsonObject,
                ),
            )
            if (requestId != null) {
                chat.row("requests", requestId)?.let { prior ->
                    val message = allMessages().firstOrNull { it["id"] == prior["message_id"] }
                    if (prior["hash"] != hash || message == null) return@synchronized errorReply(409, "idempotency_conflict")
                    return@synchronized 200 to linkedMapOf("message" to message, "replayed" to true, "created" to false)
                }
            }
            val source: String
            val threadId: String
            if (threadRequested != null) {
                val root = allMessages().firstOrNull { it["thread_id"] == threadRequested && it["id"] == it["thread_source_message_id"] && it["channel_id"] == channel }
                    ?: return@synchronized errorReply(404, "thread_not_found")
                threadId = threadRequested
                source = root["id"] as String
            } else {
                threadId = newUUID()
                source = ""
            }
            val id = newUUID()
            val now = TowerTime.now()
            val row: Row = linkedMapOf(
                "id" to id, "workspace_id" to w, "channel_id" to channel, "thread_id" to threadId, "thread_source_message_id" to source.ifEmpty { id },
                "body" to body, "client_request_id" to requestId, "record_state" to "active", "row_version" to 1L, "sender_npub" to signer,
                "created_by_actor_label" to SampleContent.YOU_LABEL, "created_at" to now, "updated_at" to now, "attachments" to attachments,
                "metadata" to mapOf("attachments" to attachments),
            )
            chat.put("posted", id, row)
            if (requestId != null) chat.put("requests", requestId, linkedMapOf("hash" to hash, "message_id" to id))
            appendEventLocked(w, channelScope(w, channel), channel, "message", id, "created", 1, mapOf("thread_id" to threadId))
            chat.persist()
            change = id to threadId
            val reply = LinkedHashMap(row).apply { remove("thread_source_message_id") }   // like Tower's create reply
            201 to linkedMapOf("message" to reply, "created" to true, "replayed" to false)
        }
        if (change != null) bump()
        return reply
    }

    /** Author-only edit (`row_version` and a kind 33358 revision signature with `message_id` and `revision`), and delete. */
    private fun revise(request: HttpRequest, w: String, id: String, url: SampleURL, signer: String): Reply {
        val o = SampleJson.objectOf(request.body)
        var changed = false
        val reply: Reply = synchronized(lock) {
            val row = allMessages().firstOrNull { it["workspace_id"] == w && it["id"] == id }
            if (row == null || row["record_state"] == "deleted") return@synchronized errorReply(404, "message_not_found")
            val version = row["row_version"].asInt() ?: 1
            val operation: String
            if (request.method.uppercase() == "DELETE") {
                val q = url.query("row_version")
                if (q != null && q.toIntOrNull() != version) return@synchronized errorReply(409, "stale_row_version")
                row["record_state"] = "deleted"
                row["body"] = ""
                row["row_version"] = version + 1L
                operation = "deleted"
            } else {
                if (row["sender_npub"] != signer) return@synchronized errorReply(403, "message_author_required")
                if (o["row_version"].asInt() != version) return@synchronized errorReply(409, "stale_row_version")
                val body = (o["body"].asString() ?: "").trimWhitespace()
                SampleMessageSignature.check(o, body, w, row["channel_id"] as String, null, signer, messageId = id, revision = version + 1)
                    ?.let { return@synchronized validationError(it) }
                row["body"] = body
                row["row_version"] = version + 1L
                row["updated_at"] = TowerTime.now()
                operation = "updated"
            }
            chat.put("revised", id, row)
            val channel = row["channel_id"] as String
            appendEventLocked(w, channelScope(w, channel), channel, "message", id, operation, version + 1L,
                row["thread_id"].asString()?.let { mapOf("thread_id" to it) } ?: emptyMap())
            chat.persist()
            changed = true
            200 to mapOf("message" to row)
        }
        if (changed) bump()
        return reply
    }

    // MARK: Scope and channel management (lock held for the *Rows helpers)

    @Suppress("UNCHECKED_CAST")
    private val managed: MutableMap<String, Any?> get() = chat.data["managed"] as MutableMap<String, Any?>

    private fun manageList(key: String): List<Row> = managed[key].asList()?.mapNotNull { SampleJson.row(it) } ?: emptyList()
    private fun manageMap(key: String): Map<String, Row> =
        managed[key].asMap()?.mapNotNull { (k, v) -> SampleJson.row(v)?.let { k to it } }?.toMap() ?: emptyMap()
    private val archived: Set<String> get() = (managed["archived"].asStrings() ?: emptyList()).toSet()

    private fun scope(id: String, w: String, name: String): Row = linkedMapOf(
        "id" to id, "workspace_id" to w, "name" to name, "kind" to "project", "row_version" to 1L, "updated_at" to "2026-10-01T00:00:00.000Z",
    )

    private fun channel(id: String, w: String, s: String, name: String, position: Double): Row = linkedMapOf(
        "id" to id, "workspace_id" to w, "scope_id" to s, "name" to name, "kind" to "channel", "position" to position, "row_version" to 1L,
        "updated_at" to "2026-10-01T00:00:00.000Z",
    )

    private fun scopeRows(w: String): List<Row> {
        val base = if (w == SampleIds.STUDIO) listOf(scope(SampleIds.SCOPE_AMBULANDO, w, "Ambulando"), scope(SampleIds.SCOPE_OPS, w, "Operations"))
        else listOf(scope(SampleIds.SCOPE_RESEARCH, w, "Research"))
        val edits = manageMap("scopeEdits")
        val gone = archived
        return (base + manageList("scopes").filter { it["workspace_id"] == w })
            .filter { (it["id"] as? String) !in gone }
            .map { row ->
                val id = row["id"] as String
                edits[id]?.let { row.putAll(it) }
                row["can_manage"] = id != SampleIds.SCOPE_OPS
                row
            }
    }

    private fun channelRows(w: String, s: String): List<Row> {
        val base = when (s) {
            SampleIds.SCOPE_AMBULANDO -> if (w == SampleIds.STUDIO) listOf(channel(SampleIds.GENERAL, w, s, "general", 1.0), channel(SampleIds.DESIGN, w, s, "design", 2.0)) else emptyList()
            SampleIds.SCOPE_OPS -> if (w == SampleIds.STUDIO) listOf(channel(SampleIds.DEPLOYS, w, s, "deploys", 1.0)) else emptyList()
            SampleIds.SCOPE_RESEARCH -> if (w == SampleIds.LAB) listOf(channel(SampleIds.NOTES, w, s, "notes", 1.0)) else emptyList()
            else -> emptyList()
        }
        val edits = manageMap("channelEdits")
        val gone = archived
        val created = manageList("channels").filter { it["workspace_id"] == w && it["scope_id"] == s }
        return (base + created).filter { (it["id"] as? String) !in gone }
            .map { row -> row.also { r -> edits[r["id"] as String]?.let { r.putAll(it) } } }
            .sortedWith(compareBy({ (it["position"] as? Number)?.toDouble() ?: 0.0 }, { it["id"] as? String ?: "" }))
    }

    private fun scopeOrder(w: String): Map<String, Any?> = managed["scopeOrder"].asMap()?.get(w).asMap()
        ?: linkedMapOf("scope_ids" to (if (w == SampleIds.STUDIO) listOf(SampleIds.SCOPE_OPS, SampleIds.SCOPE_AMBULANDO) else emptyList()), "row_version" to 1L)

    private fun channelScope(w: String, id: String): String? =
        scopeRows(w).map { it["id"] as String }.firstOrNull { s -> channelRows(w, s).any { it["id"] == id } }

    private fun editChannel(id: String, values: Map<String, Any?>) {
        val edits = LinkedHashMap(manageMap("channelEdits"))
        val row = edits[id] ?: LinkedHashMap()
        row.putAll(values)
        row["updated_at"] = TowerTime.now()
        edits[id] = row
        managed["channelEdits"] = edits
    }

    private fun setScopeOrder(w: String, order: Map<String, Any?>) {
        val all = LinkedHashMap(managed["scopeOrder"].asMap() ?: emptyMap())
        all[w] = order
        managed["scopeOrder"] = all
    }

    private fun manage(request: HttpRequest, w: String, rest: List<String>): Reply {
        val body = SampleJson.objectOf(request.body)
        val method = request.method.uppercase()
        val forbidden = 403 to linkedMapOf("code" to "forbidden", "error" to "Permission denied")
        var change: SampleChange? = null
        val reply: Reply = synchronized(lock) {
            val now = TowerTime.now()
            val result: Reply = when {
                method == "POST" && rest.size == 3 && rest[0] == "scopes" -> {
                    val s = rest[1]
                    if (scopeRows(w).none { it["id"] == s }) return@synchronized errorReply(404, "scope_not_found")
                    if (s == SampleIds.SCOPE_OPS) return@synchronized forbidden
                    val name = (body["name"].asString() ?: "").trimWhitespace()
                    if (name.isEmpty()) return@synchronized validationError("name must be a non-empty string")
                    if (channelRows(w, s).any { it["name"] == name }) return@synchronized errorReply(409, "duplicate_channel")
                    val row = channel(newUUID(), w, s, name, (channelRows(w, s).size + 1).toDouble())
                    row["kind"] = body["kind"] ?: "channel"
                    row["metadata"] = body["metadata"] ?: emptyMap<String, Any>()
                    row["description"] = body["description"]
                    row["participant_npubs"] = body["participant_npubs"]
                    row["updated_at"] = now
                    managed["channels"] = manageList("channels") + row
                    change = SampleChange("channel", row["id"] as String, "created", row["id"] as String, s, 1)
                    201 to mapOf("channel" to row)
                }
                method == "POST" && rest.size == 1 -> {
                    val name = (body["name"].asString() ?: "").trimWhitespace()
                    if (name.isEmpty()) return@synchronized validationError("name must be a non-empty string")
                    if (scopeRows(w).any { it["name"] == name }) return@synchronized errorReply(409, "duplicate_scope")
                    val row = scope(newUUID(), w, name)
                    row["description"] = body["description"]
                    row["updated_at"] = now
                    managed["scopes"] = manageList("scopes") + row
                    change = SampleChange("scope", row["id"] as String, "created", scopeId = row["id"] as String, rowVersion = 1)
                    201 to mapOf("scope" to row)
                }
                rest[0] == "scopes" -> {
                    val s = rest[1]
                    val row = scopeRows(w).firstOrNull { it["id"] == s } ?: return@synchronized errorReply(404, "scope_not_found")
                    if (s == SampleIds.SCOPE_OPS) return@synchronized forbidden
                    if (method == "DELETE") {
                        managed["archived"] = (archived + s).toList()
                        change = SampleChange("scope", s, "deleted", scopeId = s)
                        return@synchronized 200 to mapOf("scope" to row)
                    }
                    val edits = LinkedHashMap(manageMap("scopeEdits"))
                    edits[s] = linkedMapOf("name" to (body["name"] ?: row["name"]), "description" to body["description"], "updated_at" to now)
                    managed["scopeEdits"] = edits
                    row.putAll(edits[s]!!)
                    change = SampleChange("scope", s, "updated", scopeId = s)
                    200 to mapOf("scope" to row)
                }
                rest[0] == "channels" -> {
                    val c = rest[1]
                    val s = channelScope(w, c)
                    val row = s?.let { channelRows(w, it).firstOrNull { r -> r["id"] == c } } ?: return@synchronized errorReply(404, "channel_not_found")
                    if (s == SampleIds.SCOPE_OPS) return@synchronized forbidden
                    when (method) {
                        "DELETE" -> {
                            managed["archived"] = (archived + c).toList()
                            change = SampleChange("channel", c, "deleted", c, s)
                            200 to mapOf("channel" to row)
                        }
                        "POST" -> {
                            val siblings = channelRows(w, s).toMutableList()
                            val moved = siblings.removeAt(siblings.indexOfFirst { it["id"] == c })
                            siblings.add(((body["position"].asInt() ?: 1) - 1).coerceIn(0, siblings.size), moved)
                            siblings.forEachIndexed { i, sibling -> editChannel(sibling["id"] as String, mapOf("position" to (i + 1).toDouble())) }
                            change = SampleChange("channel", c, "updated", c, s)
                            200 to linkedMapOf("channel" to channelRows(w, s).first { it["id"] == c }, "changed" to true)
                        }
                        else -> {
                            val values = LinkedHashMap<String, Any?>()
                            body["name"].asString()?.let { name ->
                                val trimmed = name.trimWhitespace()
                                if (channelRows(w, s).any { it["name"] == trimmed && it["id"] != c }) return@synchronized errorReply(409, "duplicate_channel")
                                values["name"] = trimmed
                            }
                            body["metadata"].asMap()?.let { metadata -> values["metadata"] = LinkedHashMap(row["metadata"].asMap() ?: emptyMap()) + metadata }
                            editChannel(c, values)
                            change = SampleChange("channel", c, "updated", c, s)
                            200 to mapOf("channel" to channelRows(w, s).first { it["id"] == c })
                        }
                    }
                }
                method == "PUT" -> {
                    var order: Map<String, Any?> = scopeOrder(w)
                    var version = order["row_version"].asInt() ?: 1
                    if (scopeOrderConflictFlag) {
                        scopeOrderConflictFlag = false
                        version += 1
                        order = linkedMapOf("scope_ids" to listOf(SampleIds.SCOPE_AMBULANDO, SampleIds.SCOPE_OPS), "row_version" to version.toLong())
                        setScopeOrder(w, order)
                    }
                    if (body["expected_row_version"].asInt() != version) {
                        chat.persist()
                        return@synchronized errorReply(409, "scope_order_conflict")
                    }
                    order = linkedMapOf("scope_ids" to (body["scope_ids"].asStrings() ?: emptyList()), "row_version" to version + 1L)
                    setScopeOrder(w, order)
                    200 to mapOf("scope_order" to order)
                }
                else -> errorReply(404, "not_found")
            }
            chat.persist()
            result
        }
        change?.let { emit(w, it) }
        return reply
    }

    // MARK: Records the surfaces resolve against

    private fun lookupRecord(id: String): Map<String, Any?>? {
        work.task(id)?.let { t -> return mapOf("title" to t.str("title"), "channel_id" to t.str("channel_id"), "scope_id" to t.str("scope_id")) }
        work.doc(id)?.let { d -> return mapOf("title" to d.str("title"), "channel_id" to d.str("channel_id"), "scope_id" to d.str("scope_id")) }
        val channels = mapOf(SampleIds.GENERAL to SampleIds.SCOPE_AMBULANDO, SampleIds.DESIGN to SampleIds.SCOPE_AMBULANDO, SampleIds.DEPLOYS to SampleIds.SCOPE_OPS)
        channels[id]?.let { return mapOf("title" to "channel", "channel_id" to id, "scope_id" to it) }
        if (id.startsWith("5a00bbbb")) return mapOf("title" to "Conversation", "channel_id" to SampleIds.GENERAL, "scope_id" to SampleIds.SCOPE_AMBULANDO)
        return null
    }

    private fun JsonObject.str(key: String): String? = (SampleJson.value(this)?.asMap())?.get(key).asString()

    companion object {
        fun eventCursor(row: Int): String = Base64.getEncoder().encodeToString("sample-event:$row".toByteArray())

        /** The event row after a cursor (0 for none), or null for a cursor this tower never issued. */
        fun eventRow(cursor: String?): Int? {
            cursor ?: return 0
            val text = runCatching { String(Base64.getDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull() ?: return null
            if (!text.startsWith("sample-event:")) return null
            return text.removePrefix("sample-event:").toIntOrNull()
        }
    }
}

/** Sample tasks, documents, files, inbox items and surfaces, created once per sample directory. */
internal object SampleSeed {
    fun seedWork(tower: SampleTower, docLeaseHeld: Boolean) {
        val w = tower.work
        if (w.seeded()) return
        val me = tower.actorNpub
        val general = SampleIds.GENERAL
        val design = SampleIds.DESIGN
        val a = SampleIds.SCOPE_AMBULANDO
        val review = w.seedTask(scope = a, channel = general, title = "Review the native inbox", description = "Check read and done behave like the web inbox.",
            state = "review", priority = "rock", assignee = me, creator = SampleIds.ANA)
        w.seedTask(scope = a, channel = general, title = "Write release notes for build 3", state = "in_progress", priority = "pebble", assignee = SampleIds.ORBIT,
            creator = SampleIds.ANA)
        w.seedTask(scope = a, channel = design, title = "Passage mark spacing", description = "Keep the white backing.", state = "ready", creator = SampleIds.ORBIT)
        w.seedTask(scope = a, channel = design, title = "Dark mode contrast pass", state = "new", creator = me)
        w.seedTask(scope = SampleIds.SCOPE_OPS, channel = SampleIds.DEPLOYS, title = "Rotate staging certificates", state = "blocked", priority = "rock",
            creator = SampleIds.ORBIT)
        w.seedTask(scope = a, channel = general, title = "Archive old sync notes", state = "done", creator = SampleIds.ANA)
        w.seedTaskComment(review, "Assigned to you for the phone pass.", SampleIds.ANA)

        val plan = w.seedDoc(scope = a, channel = general, title = "Native app plan (sample)", body = SampleContent.richDocument(),
            contentType = DocumentBody.ENVELOPE_CONTENT_TYPE)
        // Debug sample switch: another member holds the plan's edit lease.
        if (docLeaseHeld) w.holdLease(plan, SampleIds.ORBIT)
        w.seedDoc(scope = a, channel = design, title = "Theme notes (Markdown)",
            body = "# Theme notes\n\nThe primary colour is **deep purple**.\n\n- Light\n- Dark".toByteArray(), contentType = "text/markdown")
        val folder = w.seedFolder(general, a, "Reports")
        w.seedFile(general, a, "sync-results.txt", "Sync storm: 20,000 rows, no dropped keystrokes.\n".toByteArray(), "text/plain")
        w.seedFile(general, a, "q3-summary.csv", "week,tasks\n1,12\n2,9\n".toByteArray(), "text/csv", folder)
        val png = SampleContent.samplePNG()
        w.seedFile(design, a, "palette.png", png, "image/png")
        // A Markdown document with a stored image (served by the sample storage route, loaded like any other object).
        val image = w.seedObject(png, "image/png", "board.png")
        val markdown = "# Release board\n\nThe palette as it ships:\n\n![Palette board](storage://$image)\n\n| Step | Owner |\n| --- | --- |\n| Build | Orbit |\n| Review | Ana |\n"
        w.seedDoc(scope = a, channel = design, title = "Release board (Markdown)", body = markdown.toByteArray(), contentType = "text/markdown")

        w.seedViewState("task", review, a, general, activity = 3, viewed = 1, attention = 3)
        w.seedViewState("document", plan, a, general, activity = 2, viewed = 1)
        w.seedViewState("thread", SampleIds.thread(39), a, general, activity = 4, viewed = 2)
        w.markSeeded()
    }

    /**
     * Sample Context Tree, canvases (with every naplet kind, a board and a locked item), agents, WApps and pins. Shapes
     * follow Tower's serializers; it is still sample data, never evidence of Tower.
     */
    fun seedSurfaces(tower: SampleTower) {
        val s = tower.surfaces
        if (s.seeded()) return
        val a = SampleIds.SCOPE_AMBULANDO
        val general = SampleIds.GENERAL
        val design = SampleIds.DESIGN
        val thread = SampleIds.thread(39)
        val notes = "# Build 7 launch notes\n\nContext Tree, Canvas, agents and the Toolbox are native on Android.\n\n- Pins sync with the web\n- Boards are read only"
        tower.work.seedDoc(id = SampleIds.CANVAS_DOC, scope = a, channel = general, title = "Build 7 launch notes", body = notes.toByteArray(),
            contentType = "text/markdown")
        tower.work.seedTask(id = SampleIds.CANVAS_TASK, scope = a, channel = general, title = "Ship build 7 to the test phone", state = "ready",
            priority = "rock", creator = SampleIds.ANA)
        s.seedRecord(SampleIds.CANVAS_DOC, "document", "Build 7 launch notes", general, a)
        s.seedRecord(SampleIds.CANVAS_TASK, "task", "Ship build 7 to the test phone", general, a)
        s.seedRecord(thread, "thread", "Native chat review", general, a)
        s.seedRecord(design, "channel", "design", design, a)
        s.seedRecord(SampleIds.LOCKED_FILE, "file", "Board-only budget", design, a)
        s.denyRecord(SampleIds.LOCKED_FILE)

        // Context Tree for the Ambulando scope.
        val root = s.seedComponent(scope = a, title = "Native Android app", sortOrder = 0)
        val sync = s.seedComponent(scope = a, title = "Sync and storage", parent = root, sortOrder = 0)
        val surfacesNode = s.seedComponent(scope = a, title = "Workspace surfaces", parent = root, sortOrder = 1)
        s.seedComponent(scope = a, title = "Release", sortOrder = 1)
        s.seedReference(sync, "doc", recordId = SampleIds.CANVAS_DOC, sortOrder = 0)
        s.seedReference(surfacesNode, "task", recordId = SampleIds.CANVAS_TASK, sortOrder = 0)
        s.seedReference(surfacesNode, "file", recordId = SampleIds.LOCKED_FILE, sortOrder = 1)
        s.seedReference(surfacesNode, "artifact", artifact = mapOf("origin" to "https://artifacts.sample.invalid", "project" to "Sample_Suite",
            "artifact" to "sample-architecture", "page" to "index.html", "version_policy" to "latest"), sortOrder = 2)

        // Canvas with every naplet kind this app draws, a board and a locked item.
        val launch = s.seedCanvas(title = "Launch board", position = 1)
        s.seedCanvas(title = "Retro", position = 2)
        s.seedNaplet(launch, "document", SampleIds.CANVAS_DOC, "Build 7 launch notes", mapOf("x" to 40, "y" to 80, "width" to 360, "height" to 260, "z" to 1),
            mapOf("title" to "Build 7 launch notes", "body" to notes, "updated_at" to "2026-10-24T09:00:00.000Z"))
        s.seedNaplet(launch, "task_list", general, "General tasks", mapOf("x" to 440, "y" to 80, "width" to 320, "height" to 260, "z" to 2),
            mapOf("tasks" to listOf(mapOf("id" to SampleIds.CANVAS_TASK, "title" to "Ship build 7 to the test phone", "state" to "ready"))))
        s.seedNaplet(launch, "chat_thread", thread, "Native chat review", mapOf("x" to 40, "y" to 380, "width" to 360, "height" to 280, "z" to 3),
            mapOf("messages" to listOf(
                mapOf("id" to SampleIds.message(100), "author" to "Orbit", "body" to "Looks right on my phone", "created_at" to "2026-10-23T09:40:00.000Z"),
                mapOf("id" to SampleIds.message(101), "author" to "Ana", "body" to "Retry kept the same request id", "created_at" to "2026-10-23T09:41:00.000Z"),
            )))
        val board = SampleContent.sampleBoard()
        val projection = s.seedBoardVersion(SampleIds.BOARD_FILE, SampleIds.BOARD_VERSION, 2, board)
        tower.boards.seed(SampleIds.BOARD_FILE, board, canWrite = true, author = SampleIds.ANA)
        s.seedNaplet(launch, "task_tree", general, "Task Tree", mapOf("x" to 40, "y" to 740, "width" to 820, "height" to 440, "z" to 9),
            mapOf("channel_id" to general, "scope_id" to a, "channel_title" to "general", "can_create" to true))
        s.seedNaplet(launch, "excalidraw", SampleIds.BOARD_FILE, "Architecture sketch", mapOf("x" to 440, "y" to 380, "width" to 420, "height" to 300, "z" to 4),
            projection)
        s.seedNaplet(launch, "document", SampleIds.LOCKED_FILE, "Budget", mapOf("x" to 900, "y" to 80, "width" to 240, "height" to 180, "z" to 5),
            mapOf("body" to "not for this viewer"))
        s.seedNaplet(launch, "html", newUUID(), "Status page", mapOf("x" to 900, "y" to 300, "width" to 260, "height" to 200, "z" to 6),
            mapOf("html" to "<h1>Status (sample)</h1><p>All green.</p><p id='bridge'></p><script>document.getElementById('bridge').textContent = " +
                "'Sandbox nostr: ' + typeof window.nostr;</script><a href='${SampleHosts.BROWSER}/naplet' target='_blank'>Open signed browser</a>",
                "version_number" to 1))
        s.seedElement(launch, "text", mapOf("x" to 40, "y" to 20, "width" to 400, "height" to 40, "z" to 7), mapOf("text" to "Build 7 launch", "fontSize" to 28))
        s.seedElement(launch, "arrow", mapOf("x" to 400, "y" to 200, "width" to 40, "height" to 10, "z" to 8), mapOf("start" to listOf(0, 5), "end" to listOf(40, 5)))
        s.seedElement(launch, "rectangle", mapOf("x" to 20, "y" to 60, "width" to 860, "height" to 640, "z" to 0), emptyMap())

        // Agents and their activity in #general.
        s.seedAgent(SampleIds.ORBIT, SampleContent.ORBIT_LABEL, sortOrder = 1)
        s.seedAgent(SampleIds.RELAY, SampleContent.RELAY_LABEL, sortOrder = 2, avatarURL = SampleHosts.AVATARS + "relay.png")
        s.seedActivity(general, a, thread, SampleIds.ORBIT, "working", "Checking the outbox retry states")
        s.seedActivity(general, a, thread, SampleIds.ORBIT, "completed", "Answered the theme question")

        // Toolbox and pins. Launcher logos: a sample image, a missing one and one that is not an image.
        s.seedWapp("Timesheets", "https://timesheets.sample.invalid/", "Log time against tasks", SampleHosts.AVATARS + "timesheets.png", 1, SampleIds.ANA)
        s.seedWapp("Legacy tool", "http://legacy.sample.invalid/", "Served without HTTPS", null, 2, SampleIds.ANA)
        s.seedWapp("Sample Artifacts", "${SampleHosts.BROWSER}/apps/artifacts", "A Nostr web app", null, 3, SampleIds.ORBIT)
        s.seedWapp("Field Notes", "https://fieldnotes.sample.invalid/", "Notes from site visits", SampleHosts.AVATARS + "broken.png", 4, SampleIds.ANA)
        s.externalPin("thread", thread, pinned = true)
        s.externalPin("channel", design, pinned = true)
        s.markSeeded()
    }
}
