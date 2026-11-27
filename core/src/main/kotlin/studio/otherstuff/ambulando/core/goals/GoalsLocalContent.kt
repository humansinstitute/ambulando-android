package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.FileCache

/** Content in the existing workspace partition, never a grant for server requests or writes. */
class GoalsLocalContent(private val store: LocalStore, private val workspace: String, private val viewer: String, private val binding: String = "", private val fileCache: FileCache? = null) {
    private val key = "goals.content.v1:$viewer:$workspace:$binding"
    var lastLoadIssue: String? = null; private set
    suspend fun load(): GoalsController.State? = runCatching {
        val raw = store.reader.state(key) ?: return canonical()
        val o = Json.parseToJsonElement(raw).jsonObject
        require(o.string("viewer") == viewer && o.string("workspace") == workspace) { "snapshot identity" }
        val channel = ChannelRecord.parse(o.getValue("channel").jsonObject)
        require(channel.workspaceId == workspace && !channel.accessRemoved) { "channel boundary" }
        require(!store.reader.isChannelAccessRemoved(workspace, channel.id)) { "channel access removed" }
        val known = store.reader.channel(workspace, channel.id)
        require(known == null || !known.accessRemoved && known.raw.string("record_state") != "deleted" && known.raw.string("archived_at") == null) { "channel tombstone" }
        val ownerOnly = o.bool("ownerOnly") == true
        require(if (ownerOnly) channel.kind == "dm" && channel.raw["participant_npubs"] == JsonArray(listOf(JsonPrimitive(viewer))) else GoalsSpace.isOwnedBy(channel, viewer)) { "location mode" }
        val goals = o.getValue("goals").jsonArray.mapNotNull { entry ->
            val item = entry.jsonObject
            val file = FileRecord.parse(item.getValue("file").jsonObject)
            require(file.workspaceId == workspace && file.channelId == channel.id && GoalFiles.isGoal(file) && !file.accessRemoved)
            val local = store.reader.file(workspace, file.id)
            if (local?.accessRemoved == true || local?.archived == true || local?.raw?.string("record_state") == "deleted") null
            else GoalDocument(file, item.getValue("version").jsonPrimitive.content, Goal.parse(item.getValue("goal").toString().toByteArray()))
        }
        GoalsController.State(loaded = true, goals = goals.sortedWith(GoalFocus.comparator), space = GoalsSpaceState.Ready(channel, o.getValue("agents").jsonArray.map { entry ->
            val a=entry.jsonObject
            GoalsAgent(MemberRecord(workspace, a.getValue("id").jsonPrimitive.content, a.getValue("npub").jsonPrimitive.content, "agent", a.string("name")), a.string("access"))
        }, ownerOnly))
    }.onFailure { if(it is CancellationException) throw it; lastLoadIssue=it.message ?: it.javaClass.simpleName }.getOrNull()

    /** Pre-snapshot installs already have canonical rows and verified downloaded bytes. Read them in this partition;
     * do not promote channel metadata into a verified write location or persist an incomplete list as a snapshot. */
    private suspend fun canonical(): GoalsController.State? {
        val cache = fileCache ?: return null
        val channels = store.reader.channels(workspace).filter { channel ->
            !channel.accessRemoved && channel.raw.string("record_state") != "deleted" && channel.raw.string("archived_at") == null &&
                (GoalsSpace.isOwnedBy(channel, viewer) || channel.kind == "dm" && channel.raw["participant_npubs"] == JsonArray(listOf(JsonPrimitive(viewer))))
        }
        val goals = mutableListOf<GoalDocument>()
        for (channel in channels) {
            if (store.reader.isChannelAccessRemoved(workspace, channel.id)) continue
            for (file in store.reader.files(workspace, channel.id)) {
                if (!GoalFiles.isGoal(file) || file.accessRemoved || file.sha256 == null || (file.sizeBytes ?: 0) > Goal.MAX_BYTES) continue
                val version = lowerUUID(file.raw.string("current_version_id") ?: file.raw.obj("current_version")?.string("id")) ?: continue
                val document = runCatching {
                    val bytes = cache.cached(file)?.takeIf { it.length() <= Goal.MAX_BYTES }?.readBytes() ?: return@runCatching null
                    require(studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(bytes) == file.sha256)
                    GoalDocument(file, version, Goal.parse(bytes))
                }.getOrNull() ?: continue
                // Access can be withdrawn while disk bytes are being read.
                val current = store.reader.file(workspace, file.id)
                if (!store.reader.isChannelAccessRemoved(workspace, channel.id) && current?.accessRemoved == false && current.storageObjectId == file.storageObjectId && current.sha256 == file.sha256 && current.raw.string("current_version_id") == file.raw.string("current_version_id") && current.raw.obj("current_version")?.string("id") == file.raw.obj("current_version")?.string("id") && GoalFiles.isGoal(current)) goals += document
            }
        }
        return goals.takeIf { it.isNotEmpty() }?.let { GoalsController.State(loaded = true, goals = it.sortedWith(GoalFocus.comparator)) }
    }
    suspend fun save(state: GoalsController.State) {
        val space = state.space as? GoalsSpaceState.Ready
        if (space == null) { store.writer.setState(key, null); return }
        require(!store.reader.isChannelAccessRemoved(workspace,space.channel.id))
        if(store.reader.channel(workspace,space.channel.id)==null) store.writer.upsertChannels(workspace,space.channel.scopeId,listOf(space.channel),complete=false)
        state.goals.forEach { store.writer.cacheVerifiedArtefact(it.file) { true } }
        store.writer.setState(key, buildJsonObject {
            put("viewer", viewer); put("workspace", workspace); put("channel", space.channel.raw); put("ownerOnly", space.ownerOnly)
            put("agents", JsonArray(space.agents.map { agent -> buildJsonObject {
                put("id", agent.member.actorId); put("npub", agent.member.npub); put("name", agent.member.displayName); put("access", agent.access)
            } }))
            put("goals", JsonArray(state.goals.map { doc -> buildJsonObject {
                put("file", doc.file.raw); put("version", doc.versionId); put("goal", Json.parseToJsonElement(doc.goal.encode()))
            } }))
        }.toString())
    }
    suspend fun token(): String? = store.reader.state(key)
    suspend fun remove(file: String) = store.writer.markAccessRemoved(workspace,AccessTarget.File(file))
    suspend fun visible(state: GoalsController.State): GoalsController.State {
        val space=state.space as? GoalsSpaceState.Ready
        if (space != null) {
            val known=store.reader.channel(workspace,space.channel.id)
            if(store.reader.isChannelAccessRemoved(workspace,space.channel.id) || known?.raw?.string("record_state")=="deleted" || known?.raw?.string("archived_at")!=null) return GoalsController.State()
        }
        return state.copy(goals=state.goals.filter { doc ->
            val file=store.reader.file(workspace,doc.id)
            val channel=store.reader.channel(workspace,doc.file.channelId)
            channel?.raw?.string("record_state")!="deleted" && channel?.raw?.string("archived_at")==null && !store.reader.isChannelAccessRemoved(workspace,doc.file.channelId) && file?.accessRemoved!=true && file?.archived!=true && file?.raw?.string("record_state")!="deleted"
        })
    }
    suspend fun clear() = store.writer.setState(key, null)
}
