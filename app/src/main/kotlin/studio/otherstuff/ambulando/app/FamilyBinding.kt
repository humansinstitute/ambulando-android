package studio.otherstuff.ambulando.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary

/** Product preference only. Tower remains the authority; this never grants access. */
@Serializable
data class FamilyBinding(
    val origin: String, val signerNpub: String, val connectionId: String,
    val appNpub: String, val workspaceId: String, val scopeId: String,
    val channelId: String, val threadId: String,
) {
    val boundaryId get() = WorkspaceBoundary(origin, signerNpub, appNpub, workspaceId).id
    fun encode(): String = Json.encodeToString(serializer(), this)
    fun owns(connection: ConnectionRecord, workspace: WorkspaceRecord): Boolean =
        connection.id == connectionId && WorkspaceBoundary.of(connection, workspace).id == boundaryId
    fun allows(request: HttpRequest): Boolean {
        val body = request.body?.let(::parseJsonObjectOrNull) ?: return false
        return request.method == "POST" && request.url == TowerOrigin(origin).url(TowerAPI.base(workspaceId) + "/channels/$channelId/messages") &&
            request.header(TowerClient.APP_NPUB_HEADER) == appNpub && body.string("thread_id") == threadId && body.bool("create_thread") != true
    }
    fun allows(thread: ThreadRecord): Boolean = thread.workspaceId == workspaceId && thread.channelId == channelId &&
        thread.id == threadId && !thread.archived && !thread.deleted && !thread.accessRemoved
    companion object {
        const val KEY = "grandparent.family.v1"
        fun decode(raw: String?): FamilyBinding? = runCatching {
            Json.decodeFromString(serializer(), requireNotNull(raw)).also {
                require(listOf(it.connectionId, it.workspaceId, it.scopeId, it.channelId, it.threadId).all(::isUUID))
                require(TowerOrigin(it.origin).url == it.origin)
                require(studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(it.appNpub) && studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(it.signerNpub))
            }
        }.getOrNull()
        fun of(c: ConnectionRecord, w: WorkspaceRecord, channel: ChannelRecord, thread: ThreadRecord): FamilyBinding {
            require(channel.workspaceId == w.id && thread.channelId == channel.id && thread.workspaceId == w.id)
            return FamilyBinding(TowerOrigin(c.origin).url, c.signerNpub, c.id, w.appNpub, w.id, channel.scopeId, channel.id, thread.id)
        }
    }
}

/** All outbox paths, including background retry, pass here before the canonical message POST. */
class FamilyGuardTransport(
    private val base: HttpTransport, private val catalog: LocalStore, private val identity: IdentityService,
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = java.net.URI(request.url).path
        if (request.method != "POST" || !path.endsWith("/messages")) return base.send(request)
        val binding = FamilyBinding.decode(catalog.reader.state(FamilyBinding.KEY)) ?: return denied()
        if (!binding.allows(request)) return denied()
        val connection = catalog.reader.connections().firstOrNull { it.id == binding.connectionId && it.signerNpub == binding.signerNpub && TowerOrigin(it.origin).url == binding.origin }
            ?: return denied()
        identity.requireReader(connection.signerNpub)
        val api = TowerAPI(TowerClient(TowerOrigin(binding.origin), base, identity))
        // Fresh signed reads on every attempt. Offline failures retain the frozen request for retry.
        try {
            val workspace = api.workspaces().firstOrNull { it.id == binding.workspaceId && it.appNpub == binding.appNpub && !it.accessRemoved } ?: return denied()
            val channel = api.channels(workspace.id, binding.scopeId, workspace.appNpub).firstOrNull { it.id == binding.channelId && it.workspaceId == binding.workspaceId && it.scopeId == binding.scopeId }
                ?: return denied()
            if (channel.accessRemoved || channel.raw.bool("archived") == true || channel.raw.string("record_state") in setOf("deleted", "archived")) return denied()
            if (!binding.allows(api.thread(workspace.id, workspace.appNpub, binding.threadId))) return denied()
            // A deliberate change while validation was in flight must not send to the old conversation.
            if (FamilyBinding.decode(catalog.reader.state(FamilyBinding.KEY)) != binding) return denied()
            if ((identity.state() as? studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked)?.npub != binding.signerNpub) return denied()
            return base.send(request)
        } catch (e: TowerError.Http) {
            if (e.status == 403 || e.status == 404) return denied()
            throw e
        }
    }
    private fun denied() = HttpResponse(403, """{"code":"family_target_unavailable"}""".toByteArray())
}
