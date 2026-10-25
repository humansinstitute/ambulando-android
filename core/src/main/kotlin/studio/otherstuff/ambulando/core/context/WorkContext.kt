package studio.otherstuff.ambulando.core.context

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields

/**
 * The shared data context of the app: workspace → scope → channel, as chosen in the sidebar. Chat, tasks, documents
 * and files read and write within it. The workspace is held by the selection itself; this value is stored per
 * connection and workspace, so it never refers to another workspace's records.
 *
 * - `scopeId == null`: Home, all scopes of the workspace (reads span them; a write still needs a channel).
 * - `scopeId` only: the scope's home (its channels).
 * - `scopeId` + `channelId`: one channel.
 */
class WorkContext(scopeId: String? = null, channelId: String? = null) {
    val scopeId: String? = scopeId
    val channelId: String? = if (scopeId == null) null else channelId

    val isHome: Boolean get() = scopeId == null

    /** Stored JSON: `{"scopeId": …, "channelId": …}` with absent values omitted. */
    fun encoded(): String {
        val fields = LinkedHashMap<String, JsonPrimitive>()
        scopeId?.let { fields["scopeId"] = JsonPrimitive(it) }
        channelId?.let { fields["channelId"] = JsonPrimitive(it) }
        return JsonFields.encode(JsonObject(fields), sortKeys = false)
    }

    /**
     * The context with anything the user can no longer see removed: a channel that is gone (deleted, or access lost)
     * falls back to its scope; a scope that is gone falls back to Home. A channel is kept only under its own scope.
     */
    fun resolved(scopes: List<NavScope>, channels: List<NavChannel>): WorkContext {
        val scope = scopeId
        if (scope == null || scopes.none { it.id == scope }) return HOME
        val channel = channelId ?: return WorkContext(scope)
        return if (channels.any { it.id == channel && it.scopeId == scope }) this else WorkContext(scope)
    }

    /** Whether a record in `channelId` (under `scopeId`) belongs to this context. */
    fun contains(scopeId: String?, channelId: String?): Boolean {
        val scope = this.scopeId ?: return true
        this.channelId?.let { return channelId == it }
        return scopeId == scope
    }

    override fun equals(other: Any?): Boolean = other is WorkContext && other.scopeId == scopeId && other.channelId == channelId
    override fun hashCode(): Int = (scopeId?.hashCode() ?: 0) * 31 + (channelId?.hashCode() ?: 0)
    override fun toString(): String = "WorkContext(scopeId=$scopeId, channelId=$channelId)"

    companion object {
        val HOME = WorkContext()

        fun stateKey(connectionId: String, workspaceId: String): String = "context:$connectionId:$workspaceId"

        fun decode(text: String?): WorkContext? {
            val o = text?.let { JsonFields.parseObject(it) } ?: return null
            val scope = o["scopeId"]; val channel = o["channelId"]
            if ((scope != null && !JsonFields.isNull(scope) && JsonFields.string(scope) == null) ||
                (channel != null && !JsonFields.isNull(channel) && JsonFields.string(channel) == null)) return null
            return WorkContext(JsonFields.string(scope), JsonFields.string(channel))
        }
    }
}

/**
 * A scope as the sidebar needs it (seam: built from Tower's scope records once the transport layer lands).
 */
data class NavScope(val id: String, val name: String, val kind: String? = null)

/**
 * A channel as the sidebar needs it (seam: built from Tower's channel records). `raw` is the channel's canonical
 * JSON, read for `created_at`, `description` and `participant_npubs`.
 */
data class NavChannel(
    val id: String,
    val scopeId: String,
    val name: String,
    val kind: String? = null,
    val position: Double? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** One scope and its channels in the sidebar. */
data class SidebarGroup(val scope: NavScope, val channels: List<NavChannel>, val isDirectMessages: Boolean) {
    val id: String get() = scope.id
}

object ScopeOrdering {
    /** Personal order first (as saved on Tower), then the remaining scopes in their default order. */
    fun <T> apply(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, s -> rank.putIfAbsent(s, i) }
        return items.withIndex().sortedWith { a, b ->
            val x = rank[id(a.value)]; val y = rank[id(b.value)]
            when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.index.compareTo(b.index)
            }
        }.map { it.value }
    }

    fun decode(text: String?): List<String> = (text?.let { JsonFields.parse(it) } as? JsonArray)?.mapNotNull { JsonFields.string(it) } ?: emptyList()
}

/**
 * Port of the web's sidebar projection (navigation, channel order, DM scope and channel labels) over the records
 * Tower returns to this member. Tower lists only the scopes and channels the member can read, so anything absent
 * here is not shown and not queried.
 */
object SidebarNavigation {
    /**
     * Direct-message groups first, then the other scopes in the member's personal order (Tower `scope-order`), then
     * any scope not in it. Channels by their persisted position within the scope, then creation time and id.
     */
    fun groups(scopes: List<NavScope>, channels: List<NavChannel>, personalOrder: List<String>): List<SidebarGroup> {
        val seenScopes = HashSet<String>()
        val visible = scopes.filter { seenScopes.add(it.id) }
        val ordered = ScopeOrdering.apply(visible, personalOrder) { it.id }
        val seenChannels = HashSet<String>()
        val byScope = HashMap<String, MutableList<NavChannel>>()
        for (channel in channels) {
            if (channel.scopeId in seenScopes && seenChannels.add(channel.id)) byScope.getOrPut(channel.scopeId) { ArrayList() }.add(channel)
        }
        val groups = ordered.map { SidebarGroup(it, sortChannels(byScope[it.id] ?: emptyList()), isDirectMessageScope(it)) }
        return groups.filter { it.isDirectMessages } + groups.filter { !it.isDirectMessages }
    }

    /** An integer position ≥ 1 first (ascending), then creation time, then id. */
    fun sortChannels(channels: List<NavChannel>): List<NavChannel> {
        val keyed = channels.map { it to ChannelDetails.of(it.raw) }
        return keyed.sortedWith { a, b ->
            val pa = persisted(a.first.position); val pb = persisted(b.first.position)
            if (pa != null || pb != null) {
                if (pa == null) return@sortedWith 1
                if (pb == null) return@sortedWith -1
                if (pa != pb) return@sortedWith pa.compareTo(pb)
            }
            if (a.second.createdAt != b.second.createdAt) return@sortedWith a.second.createdAt.compareTo(b.second.createdAt)
            a.first.id.compareTo(b.first.id)
        }.map { it.first }
    }

    private fun persisted(position: Double?): Int? = position?.takeIf { it >= 1 && Math.rint(it) == it }?.toInt()

    fun isDirectMessageScope(scope: NavScope): Boolean = scope.kind?.lowercase() == "dm" || scope.name.trim().lowercase() == "dms"

    fun isDirectMessageChannel(channel: NavChannel): Boolean {
        if (channel.kind?.lowercase() == "dm") return true
        val details = ChannelDetails.of(channel.raw)
        if (details.description.lowercase().startsWith("dm:")) return true
        val title = channel.name.trim().lowercase()
        if (title.startsWith("dm:")) return true
        return details.participants.toSet().size == 2 && title.contains("dm")
    }

    private val dmPrefix = Regex("^DM:\\s*", RegexOption.IGNORE_CASE)
    private val bareNpub = Regex("^npub1[0-9a-z]+$", RegexOption.IGNORE_CASE)
    private val npubInName = Regex("npub1[0-9a-z]{20,}", RegexOption.IGNORE_CASE)

    /**
     * A channel's title, or for a direct message the other participants' names ("Note to self" when the viewer is the
     * only participant).
     */
    fun label(channel: NavChannel, viewerNpub: String?, names: Map<String, String>): String {
        val title = channel.name.trim()
        val direct = isDirectMessageChannel(channel)
        if (title.isNotEmpty() && !direct) return title
        val participants = participants(channel)
        if (direct && participants.size == 1 && participants.first() == viewerNpub) return "Note to self"
        val others = participants.filter { it != viewerNpub }
        if (others.isNotEmpty()) return others.joinToString(", ") { names[it] ?: compact(it) }
        if (direct) {
            val stripped = title.replace(dmPrefix, "")
            if (stripped.isNotEmpty() && !bareNpub.matches(stripped)) return stripped
            return "Direct message"
        }
        return title.ifEmpty { channel.id.take(8) }
    }

    fun participants(channel: NavChannel): List<String> {
        val details = ChannelDetails.of(channel.raw)
        if (details.participants.isNotEmpty()) return details.participants.toSet().sorted()
        if (details.description.lowercase().startsWith("dm:")) {
            return details.description.drop(3).split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()
        }
        return npubInName.findAll(channel.name).map { it.value }.toSet().sorted()
    }

    internal fun compact(npub: String): String = if (npub.length <= 16) npub else "${npub.take(10)}...${npub.takeLast(6)}"

    /** "Unread & Active": channels, in sidebar order, that have unread or attention-needing activity. */
    fun unreadChannels(groups: List<SidebarGroup>, unreadChannelIds: Set<String>): List<NavChannel> {
        val seen = HashSet<String>()
        return groups.flatMap { it.channels }.filter { it.id in unreadChannelIds && seen.add(it.id) }
    }
}

/** Channel fields kept only in the stored Tower payload. */
internal data class ChannelDetails(val createdAt: String = "", val description: String = "", val participants: List<String> = emptyList()) {
    companion object {
        fun of(raw: JsonObject): ChannelDetails = ChannelDetails(
            JsonFields.string(raw["created_at"]) ?: "",
            (JsonFields.string(raw["description"]) ?: "").trim(),
            (raw["participant_npubs"] as? JsonArray)?.let { list ->
                // All entries must be strings, as a `[String]` cast requires.
                if (list.all { JsonFields.string(it) != null }) list.map { JsonFields.string(it)!!.trim() }.filter { it.isNotEmpty() } else emptyList()
            } ?: emptyList(),
        )
    }
}
