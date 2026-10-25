package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.nostr.NostrKeys

// Typed views over the Tower Flight Deck PG serializers. Each keeps the canonical JSON object (`raw`) exactly as Tower
// sent it, so unknown fields survive local storage. `parse` validates ids and throws `TowerError.Malformed`; the
// primary constructors are the memberwise initialisers used by the store and tests.

private val EMPTY = JsonObject(emptyMap())

data class WorkspaceRecord(
    val id: String,
    val appNpub: String,
    val label: String,
    val slug: String?,
    val towerBaseURL: String?,
    val role: String?,
    val ownerNpub: String?,
    val raw: JsonObject,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): WorkspaceRecord {
            val identity = raw.obj("identity") ?: throw TowerError.Malformed("workspace")
            val id = identity.string("workspace_id")
            val app = identity.string("app_npub")
            if (!isUUID(id) || app == null || !NostrKeys.isValidNpub(app)) throw TowerError.Malformed("workspace")
            return WorkspaceRecord(
                id = id!!.lowercase(), appNpub = app, label = raw.string("label") ?: "Workspace", slug = raw.string("slug"),
                towerBaseURL = raw.string("tower_base_url"), role = raw.obj("membership")?.string("role"),
                ownerNpub = identity.string("workspace_owner_npub"), raw = raw,
            )
        }
    }
}

data class ScopeRecord(
    val workspaceId: String,
    val id: String,
    val name: String,
    val kind: String? = null,
    val description: String? = null,
    val defaultChannelId: String? = null,
    val rowVersion: Long = 1,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): ScopeRecord {
            val id = raw.string("id")
            val workspace = raw.string("workspace_id")
            if (!isUUID(id) || workspace == null) throw TowerError.Malformed("scope")
            return ScopeRecord(
                workspaceId = workspace.lowercase(), id = id!!.lowercase(), name = raw.string("name") ?: "Untitled scope",
                kind = raw.string("kind"), description = raw.string("description"),
                defaultChannelId = raw.string("default_channel_id")?.lowercase(), rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

data class ChannelRecord(
    val workspaceId: String,
    val id: String,
    val scopeId: String,
    val name: String,
    val kind: String? = null,
    val position: Double? = null,
    val rowVersion: Long = 1,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): ChannelRecord {
            val id = raw.string("id")
            val scope = raw.string("scope_id")
            val workspace = raw.string("workspace_id")
            if (!isUUID(id) || !isUUID(scope) || workspace == null) throw TowerError.Malformed("channel")
            return ChannelRecord(
                workspaceId = workspace.lowercase(), id = id!!.lowercase(), scopeId = scope!!.lowercase(),
                name = raw.string("name") ?: "Untitled channel", kind = raw.string("kind"), position = flexibleDouble(raw["position"]),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

data class MessageRecord(
    val workspaceId: String,
    val id: String,
    val channelId: String,
    val threadId: String? = null,
    /** The message that started [threadId]. A message is a channel-level post when it is its thread's source. */
    val threadSourceId: String? = null,
    val body: String,
    val senderNpub: String? = null,
    val senderLabel: String? = null,
    val clientRequestId: String? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val rowVersion: Long = 1,
    val deleted: Boolean = false,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    /** Channel-level post (thread root), as the web client maps it: `thread_source_message_id == id`, or no thread. */
    val isThreadRoot: Boolean get() = threadId == null || threadSourceId == id

    companion object {
        fun parse(raw: JsonObject): MessageRecord {
            val id = raw.string("id")
            val channel = raw.string("channel_id")
            val workspace = raw.string("workspace_id")
            if (!isUUID(id) || !isUUID(channel) || workspace == null) throw TowerError.Malformed("message")
            val createdAt = raw.string("created_at") ?: ""
            return MessageRecord(
                workspaceId = workspace.lowercase(), id = id!!.lowercase(), channelId = channel!!.lowercase(),
                threadId = raw.string("thread_id")?.lowercase(), threadSourceId = raw.string("thread_source_message_id")?.lowercase(),
                body = raw.string("body") ?: "", senderNpub = raw.string("sender_npub"), senderLabel = raw.string("created_by_actor_label"),
                clientRequestId = raw.string("client_request_id"), createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt,
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, deleted = raw.string("record_state") == "deleted", raw = raw,
            )
        }
    }
}

data class ThreadRecord(
    val workspaceId: String,
    val id: String,
    val channelId: String,
    val sourceMessageId: String?,
    val title: String?,
    val latest: String? = null,
    val archived: Boolean = false,
    val deleted: Boolean = false,
    val rowVersion: Long = 1,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): ThreadRecord {
            val id = raw.string("id")
            val channel = raw.string("channel_id")
            val workspace = raw.string("workspace_id")
            if (!isUUID(id) || !isUUID(channel) || workspace == null) throw TowerError.Malformed("thread")
            val state = raw.string("record_state")
            return ThreadRecord(
                workspaceId = workspace.lowercase(), id = id!!.lowercase(), channelId = channel!!.lowercase(),
                sourceMessageId = raw.string("source_message_id")?.lowercase(), title = raw.string("title"), latest = raw.string("latest"),
                archived = state == "archived", deleted = state == "deleted", rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

/**
 * Slim workspace directory entry (`GET /workspaces/:w/members`, `serializeFlightDeckPgActor`). Like the iOS record it
 * keeps no raw JSON (the directory is re-read whole).
 */
data class MemberRecord(
    val workspaceId: String,
    val actorId: String,
    val npub: String,
    val kind: String?,
    val displayName: String?,
) {
    val id: String get() = npub

    /** Agents are mentioned as `agent` on the wire, everyone else as `person`. */
    val isAgent: Boolean get() = kind == "agent"
    val label: String get() = if (!displayName.isNullOrEmpty()) displayName else shortLabel(npub)

    companion object {
        fun parse(workspaceId: String, raw: JsonObject): MemberRecord {
            val actor = raw.obj("actor") ?: throw TowerError.Malformed("member")
            val npub = actor.string("npub") ?: throw TowerError.Malformed("member")
            val actorId = actor.string("actor_id") ?: actor.string("id")
            if (!NostrKeys.isValidNpub(npub) || actorId == null) throw TowerError.Malformed("member")
            return MemberRecord(workspaceId, actorId.lowercase(), npub, actor.string("kind"), actor.string("display_name"))
        }
    }
}

/** `npub1abcdefg…wxyz` for display when no name is known. */
fun shortLabel(npub: String): String = if (npub.length > 16) "${npub.take(10)}…${npub.takeLast(4)}" else npub
