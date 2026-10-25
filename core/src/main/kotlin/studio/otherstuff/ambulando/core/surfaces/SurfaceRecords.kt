package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.browser.WebContentPolicy
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.profiles.AvatarURLPolicy
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleDouble
import studio.otherstuff.ambulando.core.transport.flexibleInt
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.lowerUUID
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.stringOrNull
import java.net.URI

// Typed views over the Tower serializers behind the Context Tree, Canvas, agents, Toolbox and personal pins. Each keeps
// its canonical JSON in `raw`; ids are lower-cased UUIDs or the record is refused.

private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.text(key: String): String? = nonEmpty(string(key))

// MARK: Context Tree

/** One node of a scope's Context Tree (`flightdeck_pg_context_components`). */
data class ContextComponentRecord(
    val workspaceId: String,
    val scopeId: String,
    val id: String,
    val parentId: String?,
    val title: String,
    val sortOrder: Int,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    companion object {
        fun parse(raw: JsonObject): ContextComponentRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val s = lowerUUID(raw.string("scope_id"))
            if (id == null || w == null || s == null) throw TowerError.Malformed("context component")
            return ContextComponentRecord(
                workspaceId = w, scopeId = s, id = id, parentId = lowerUUID(raw.string("parent_id")), title = raw.text("title") ?: "Untitled",
                sortOrder = flexibleInt(raw["sort_order"]) ?: 0, rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: raw.string("created_at") ?: "", raw = raw,
            )
        }
    }
}

/** A follow-latest Artifact WApp page. Its access authority is the Artifact WApp itself; Tower never fetches it. */
data class ArtifactTarget(val origin: String, val project: String, val artifact: String) {
    /** `origin/artifacts/<project>/<artifact>/`. */
    val latestURL: String get() = "$origin/artifacts/$project/$artifact/"
    val isHTTPS: Boolean get() = origin.startsWith("https://")

    companion object {
        private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** Same canonical-target rules as Tower and the web client; null for anything else. */
        fun parse(o: JsonObject?): ArtifactTarget? {
            if (o == null) return null
            val url = o.string("origin")?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            val scheme = url.scheme?.lowercase() ?: return null
            val host = url.host
            val path = url.rawPath ?: ""
            if (scheme !in setOf("http", "https") || url.rawUserInfo != null || url.rawQuery != null || url.rawFragment != null ||
                !(path.isEmpty() || path == "/") || host.isNullOrEmpty()
            ) return null
            val project = o.string("project")?.takeIf { SEGMENT.matches(it) } ?: return null
            val artifact = o.string("artifact")?.takeIf { SEGMENT.matches(it) } ?: return null
            if (o.string("page") != "index.html" || o.string("version_policy") != "latest") return null
            val origin = "$scheme://${host.lowercase()}" + (url.port.takeIf { it >= 0 }?.let { ":$it" } ?: "")
            return ArtifactTarget(origin, project, artifact)
        }
    }
}

/** A component's typed link to a task, document, file or artifact, with Tower's per-viewer resolution. */
data class ContextReferenceRecord(
    val workspaceId: String,
    val scopeId: String,
    val componentId: String,
    val id: String,
    val targetType: TargetType?,
    /** Raw `target_type`, kept when the type is one this client does not know. */
    val targetTypeName: String,
    val targetRecordId: String?,
    val artifact: ArtifactTarget?,
    val resolution: Resolution,
    /** Title Tower resolved for this viewer (only when available). */
    val resolvedTitle: String?,
    val sortOrder: Int,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    enum class TargetType(val wire: String) {
        TASK("task"), DOC("doc"), FILE("file"), ARTIFACT("artifact");

        companion object {
            fun from(wire: String?): TargetType? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class Resolution(val wire: String) {
        AVAILABLE("available"), UNAVAILABLE("unavailable"), EXTERNAL("external");

        companion object {
            fun from(wire: String?): Resolution? = entries.firstOrNull { it.wire == wire }
        }
    }

    companion object {
        fun parse(raw: JsonObject): ContextReferenceRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val s = lowerUUID(raw.string("scope_id"))
            val c = lowerUUID(raw.string("component_id"))
            if (id == null || w == null || s == null || c == null) throw TowerError.Malformed("context reference")
            val typeName = raw.string("target_type") ?: ""
            val type = TargetType.from(typeName)
            val target = raw.obj("target")
            val resolutionObject = raw.obj("resolution")
            // Missing or unknown resolution is treated as unavailable: access is never assumed.
            val resolution = Resolution.from(resolutionObject?.string("status")) ?: Resolution.UNAVAILABLE
            return ContextReferenceRecord(
                workspaceId = w, scopeId = s, componentId = c, id = id, targetType = type, targetTypeName = typeName,
                targetRecordId = if (type == TargetType.ARTIFACT) null else lowerUUID(target?.string("record_id")),
                artifact = if (type == TargetType.ARTIFACT) ArtifactTarget.parse(target) else null, resolution = resolution,
                resolvedTitle = if (resolution == Resolution.AVAILABLE) resolutionObject?.text("title") else null,
                sortOrder = flexibleInt(raw["sort_order"]) ?: 0, rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

// MARK: Canvas

data class CanvasViewport(val x: Double = 0.0, val y: Double = 0.0, val scale: Double = 1.0)

/**
 * A canvas definition (`serializeFlightDeckPgCanvas`). The list route has no per-row workspace id, so the route's
 * workspace is bound in.
 */
data class CanvasRecord(
    val workspaceId: String,
    val id: String,
    val title: String,
    val position: Int,
    val visibilityMode: String,
    /** The saved viewport (Tower clamps scale to 0.2…3). */
    val viewport: CanvasViewport,
    val canManage: Boolean,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    companion object {
        fun parse(raw: JsonObject, workspaceId: String): CanvasRecord {
            val id = lowerUUID(raw.string("id") ?: raw.string("canvas_id"))
            val w = lowerUUID(workspaceId)
            if (id == null || w == null) throw TowerError.Malformed("canvas")
            val v = raw.obj("viewport")
            return CanvasRecord(
                workspaceId = w, id = id, title = raw.text("title") ?: "Untitled canvas", position = flexibleInt(raw["position"]) ?: 0,
                visibilityMode = raw.obj("visibility")?.string("mode") ?: "workspace",
                viewport = CanvasViewport(
                    x = flexibleDouble(v?.get("x")) ?: 0.0, y = flexibleDouble(v?.get("y")) ?: 0.0,
                    scale = (flexibleDouble(v?.get("scale")) ?: 1.0).coerceIn(0.2, 3.0),
                ),
                canManage = raw.bool("can_manage") == true, rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

/**
 * The stored snapshot response for one canvas: naplets (already resolved and authorized by Tower for this viewer) and
 * native elements, kept as Tower sent them.
 */
class CanvasSnapshotRecord(
    val workspaceId: String,
    val canvasId: String,
    val canvasRowVersion: Long,
    /** `{"elements": [...], "naplets": [...]}` exactly as returned (keys sorted). */
    val bytes: ByteArray,
    val fetchedAt: String,
) {
    override fun equals(other: Any?): Boolean = other is CanvasSnapshotRecord && workspaceId == other.workspaceId &&
        canvasId == other.canvasId && canvasRowVersion == other.canvasRowVersion && bytes.contentEquals(other.bytes) && fetchedAt == other.fetchedAt

    override fun hashCode(): Int = canvasId.hashCode() * 31 + canvasRowVersion.hashCode()
}

/** Verified bytes of one Excalidraw board version (a Flight Deck File whose current version holds the scene). */
class BoardSceneRecord(
    val workspaceId: String,
    val fileId: String,
    val versionId: String,
    val sha256: String,
    val bytes: ByteArray,
    val fetchedAt: String,
) {
    override fun equals(other: Any?): Boolean = other is BoardSceneRecord && workspaceId == other.workspaceId && fileId == other.fileId &&
        versionId == other.versionId && sha256 == other.sha256 && bytes.contentEquals(other.bytes) && fetchedAt == other.fetchedAt

    override fun hashCode(): Int = (fileId.hashCode() * 31 + versionId.hashCode()) * 31 + sha256.hashCode()
}

// MARK: Agents

/**
 * An agent installed in the workspace from an Autopilot connection (`serializeWorkspaceAgent`). Connection and FIPS
 * endpoint details are not used: this client reaches agents only through Tower records.
 */
data class WorkspaceAgentRecord(
    val workspaceId: String,
    val id: String,
    val agentNpub: String,
    val displayName: String,
    val avatarURL: String?,
    val capabilities: List<String>,
    val sortOrder: Int,
    val isVisible: Boolean,
    val archived: Boolean,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    companion object {
        fun parse(raw: JsonObject): WorkspaceAgentRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val npub = raw.text("agent_npub")
            if (id == null || w == null || npub == null || !npub.startsWith("npub1")) throw TowerError.Malformed("workspace agent")
            val capabilities = (raw["capabilities"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
            return WorkspaceAgentRecord(
                workspaceId = w, id = id, agentNpub = npub, displayName = raw.text("display_name") ?: "Agent",
                avatarURL = raw.text("avatar_url"), capabilities = capabilities, sortOrder = flexibleInt(raw["sort_order"]) ?: 0,
                isVisible = raw.bool("is_visible") ?: true, archived = !(raw["archived_at"] == null || raw["archived_at"] is JsonNull),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

/** One user-visible agent activity Tower materialised for a conversation (`serializeFlightDeckPgAgentActivity`). */
data class AgentActivityRecord(
    val workspaceId: String,
    val id: String,
    val activityId: String,
    val scopeId: String?,
    val channelId: String,
    val threadId: String?,
    val agentNpub: String,
    val state: String,
    val label: String?,
    val summary: String?,
    val body: String?,
    val sequence: Long,
    /** `live` or `stale` (Tower: lease expired without a terminal state). */
    val leaseHealth: String,
    val queuePosition: Int?,
    val terminalAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val raw: JsonObject,
) {
    val isTerminal: Boolean get() = state in TERMINAL_STATES || terminalAt != null

    companion object {
        val TERMINAL_STATES: Set<String> = setOf("completed", "failed", "cancelled")

        /** Same acceptance as the web client: only user-visible rows with an activity id and a sequence. */
        fun parse(raw: JsonObject): AgentActivityRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val c = lowerUUID(raw.string("channel_id"))
            val activity = raw.text("activity_id")
            val sequence = flexibleLong(raw["sequence"])
            if (id == null || w == null || c == null || activity == null || raw.string("visibility") != "user_visible" ||
                sequence == null || sequence < 0
            ) throw TowerError.Malformed("agent activity")
            val createdAt = raw.string("created_at") ?: ""
            return AgentActivityRecord(
                workspaceId = w, id = id, activityId = activity, scopeId = lowerUUID(raw.string("scope_id")), channelId = c,
                threadId = lowerUUID(raw.string("thread_id")), agentNpub = raw.text("agent_npub") ?: "",
                state = (raw.text("state") ?: "working").lowercase(), label = raw.text("label"), summary = raw.text("summary"),
                body = raw.text("body"), sequence = sequence, leaseHealth = (raw.text("lease_health") ?: "live").lowercase(),
                queuePosition = flexibleInt(raw["queue_position"])?.takeIf { it > 0 }, terminalAt = raw.text("terminal_at"),
                createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt, raw = raw,
            )
        }
    }
}

// MARK: Toolbox

/**
 * A personal WApp in the viewer's Toolbox (`serializeFlightDeckPgPersonalWapp`). Its UI is a web document its
 * publisher owns; launch URL and logo policies belong to the browser layer.
 */
data class PersonalWappRecord(
    val workspaceId: String,
    val id: String,
    val title: String,
    val description: String?,
    val launchURL: String?,
    val iconURL: String?,
    val scopeId: String?,
    val channelId: String?,
    val sortOrder: Int,
    val status: String,
    val ownerNpub: String?,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    /** The launch URL if it is an HTTPS URL without credentials; anything else is never opened. */
    val safeLaunchURL: URI? get() = WebContentPolicy.launchURL(launchURL)

    /**
     * Where the launcher logo may come from, best first: `icon_url`, the metadata's manifest icon, icon and favicon,
     * then `/favicon.ico` at the launch origin. Only `storage://<uuid>` objects and HTTPS addresses on public hosts
     * are kept; the caller shows initials when none loads.
     */
    val logoCandidates: List<LauncherLogoSource>
        get() {
            val metadata = raw["metadata"] as? JsonObject
            val texts = ArrayList<String?>()
            texts.add(iconURL)
            listOf("manifest_icon_url", "icon_url", "favicon_url").forEach { texts.add((metadata?.get(it) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { v -> v.isNotEmpty() }) }
            safeLaunchURL?.let { WebContentPolicy.origin(it) }?.let { texts.add("$it/favicon.ico") }
            return texts.mapNotNull { it?.let(LauncherLogoSource::parse) }.distinct()
        }

    companion object {
        fun parse(raw: JsonObject): PersonalWappRecord {
            val id = lowerUUID(raw.string("id") ?: raw.string("record_id"))
            val w = lowerUUID(raw.string("workspace_id"))
            if (id == null || w == null) throw TowerError.Malformed("personal WApp")
            return PersonalWappRecord(
                workspaceId = w, id = id, title = raw.text("title") ?: "WApp", description = raw.text("description"),
                launchURL = raw.text("launch_url"), iconURL = raw.text("icon_url"), scopeId = lowerUUID(raw.string("scope_id")),
                channelId = lowerUUID(raw.string("channel_id")), sortOrder = flexibleInt(raw["sort_order"]) ?: 0,
                status = (raw.text("status") ?: "active").lowercase(), ownerNpub = raw.text("owner_npub") ?: raw.text("owner_actor_npub"),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

// MARK: Personal pins

/**
 * A launcher logo address the app is willing to load: a Tower storage object (signed download into the file cache)
 * or a public HTTPS image (the credential-free avatar cache).
 */
sealed interface LauncherLogoSource {
    data class Storage(val objectId: String) : LauncherLogoSource
    data class Web(val url: URI) : LauncherLogoSource

    companion object {
        fun parse(text: String): LauncherLogoSource? {
            val value = text.trim()
            if (value.lowercase().startsWith("storage://")) {
                val id = value.drop("storage://".length)
                return if (JsonFields.isUuid(id)) Storage(id.lowercase()) else null
            }
            return AvatarURLPolicy.validate(value)?.let { Web(it) }
        }
    }
}

// MARK: Personal pins

/**
 * One of the viewer's personal pins (`flightdeck_pg_thread_pins` row, record family `personal_pin`). An unpinned row
 * is a tombstone that Tower keeps; it is stored so a stale device can never resurrect the pin.
 */
data class PinRecord(
    val workspaceId: String,
    val id: String,
    val resourceType: ResourceType,
    val resourceId: String,
    val channelId: String?,
    val scopeId: String?,
    val pinned: Boolean,
    val orderPosition: Int?,
    val pinnedAt: String?,
    val rowVersion: Long,
    val updatedAt: String,
    val raw: JsonObject,
) {
    enum class ResourceType(val wire: String) {
        THREAD("thread"), TASK("task"), DOCUMENT("document"), CHANNEL("channel");

        companion object {
            fun from(wire: String?): ResourceType? = entries.firstOrNull { it.wire == wire }
        }
    }

    companion object {
        fun parse(raw: JsonObject): PinRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val type = ResourceType.from(raw.string("resource_type") ?: if (raw.string("thread_id") != null) "thread" else "")
            val resource = lowerUUID(raw.string("resource_id") ?: raw.string("thread_id"))
            if (id == null || w == null || type == null || resource == null) throw TowerError.Malformed("personal pin")
            return PinRecord(
                workspaceId = w, id = id, resourceType = type, resourceId = resource, channelId = lowerUUID(raw.string("channel_id")),
                scopeId = lowerUUID(raw.string("scope_id")), pinned = raw.bool("pinned") == true,
                orderPosition = flexibleInt(raw["order_position"]), pinnedAt = raw.text("pinned_at"),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }

        /** Tower's display order (`movePersonalPin`): unpositioned rows (newest first) before positioned ones. */
        val displayOrder: Comparator<PinRecord> = Comparator { a, b ->
            val x = a.orderPosition
            val y = b.orderPosition
            when {
                x == null && y != null -> -1
                x != null && y == null -> 1
                x != null && y != null && x != y -> x.compareTo(y)
                x == null && y == null && a.pinnedAt != b.pinnedAt -> (b.pinnedAt ?: "").compareTo(a.pinnedAt ?: "")
                else -> compareValuesBy(a, b, { it.resourceType.wire }, { it.resourceId })
            }
        }
    }
}
