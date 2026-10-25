package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Public nStack service descriptor (`GET /.well-known/nstack.json`, schema `org.wingman.nstack.services` v1). It is
 * metadata only, never an authority grant.
 */
@Serializable
data class NStackDescriptor(
    val schema: String,
    @SerialName("schema_version") val schemaVersion: Int,
    val stack: Stack? = null,
    val services: List<Service>,
) {
    @Serializable
    data class Service(
        val id: String,
        val kind: String,
        val endpoint: String? = null,
        val version: String? = null,
        val protocols: List<String>? = null,
        val capabilities: List<String>? = null,
        val availability: Availability? = null,
    ) {
        @Serializable
        data class Availability(val status: String? = null)
    }

    @Serializable
    data class Stack(@SerialName("tower_npub") val towerNpub: String? = null, val name: String? = null)

    val tower: Service? get() = services.firstOrNull { it.kind == "tower" }

    /** True when the descriptor advertises any FIPS service. Native cannot use it (HTTPS only). */
    val advertisesFIPS: Boolean
        get() = services.any { it.kind.lowercase().contains("fips") || (it.endpoint ?: "").lowercase().contains(".fips") }

    companion object {
        /** Validates the schema and that the advertised Tower endpoint is the same HTTPS origin the user chose. */
        fun parse(data: ByteArray, expectedOrigin: TowerOrigin): NStackDescriptor {
            val descriptor = runCatching { TowerJson.decodeFromString(serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
                ?: throw TowerError.Descriptor("unreadable JSON")
            if (descriptor.schema != "org.wingman.nstack.services" || descriptor.schemaVersion != 1) {
                throw TowerError.Descriptor("unsupported schema ${descriptor.schema} v${descriptor.schemaVersion}")
            }
            val tower = descriptor.tower
            val endpoint = tower?.endpoint ?: throw TowerError.Descriptor("no Tower service")
            val advertised = try {
                TowerOrigin(endpoint)
            } catch (e: TowerError) {
                throw TowerError.Descriptor("Tower endpoint is not an HTTPS origin")
            }
            if (advertised != expectedOrigin) throw TowerError.Descriptor("Tower endpoint $advertised differs from $expectedOrigin")
            if (tower.protocols?.contains("nip-98") != true) throw TowerError.Descriptor("Tower does not advertise NIP-98")
            return descriptor
        }
    }
}

/**
 * Typed Tower reads used by the sync engine. Paths are built only from validated UUIDs, and every response is checked
 * to belong to the requested workspace before it is returned for materialisation.
 *
 * Route groups live beside their records as extension functions: work, storage, leases and events in this package,
 * chat routes in `core.chat`, Context Tree / Canvas / agents / Toolbox / pins in `core.surfaces`.
 */
class TowerAPI(
    val client: TowerClient,
    /** Out-of-process transfers for presigned object-store URLs; null means in-process transfers. */
    val transfers: ObjectTransfers? = null,
) {
    companion object {
        const val WORKSPACES_PATH = "/api/v4/flightdeck-pg/workspaces"

        /** `/api/v4/flightdeck-pg/workspaces/<id>`; [TowerError.UnsafePath] unless [workspaceId] is a UUID. */
        fun base(workspaceId: String): String {
            if (!isUUID(workspaceId)) throw TowerError.UnsafePath
            return "$WORKSPACES_PATH/$workspaceId"
        }

        /** Returns [value] unchanged if it is a UUID, else [TowerError.UnsafePath] (it is about to enter a path). */
        fun uuid(value: String): String {
            if (!isUUID(value)) throw TowerError.UnsafePath
            return value
        }
    }

    suspend fun descriptor(): NStackDescriptor =
        NStackDescriptor.parse(client.getPublic(TowerClient.DESCRIPTOR_PATH), client.origin)

    suspend fun workspaces(): List<WorkspaceRecord> =
        client.list(WORKSPACES_PATH, appNpub = null, key = "workspaces").map { WorkspaceRecord.parse(it) }

    suspend fun scopes(workspaceId: String, appNpub: String): List<ScopeRecord> =
        client.list(base(workspaceId) + "/scopes", appNpub, "scopes").map { ScopeRecord.parse(it) }

    suspend fun channels(workspaceId: String, scopeId: String, appNpub: String): List<ChannelRecord> {
        if (!isUUID(scopeId)) throw TowerError.UnsafePath
        return client.list(base(workspaceId) + "/scopes/" + scopeId + "/channels", appNpub, "channels").map { ChannelRecord.parse(it) }
    }

    /** A response body that must be a JSON object. */
    internal fun obj(data: ByteArray, what: String): JsonObject = parseJsonObject(data, what)

    /**
     * Forward-paged list (`limit ≤ 200`, opaque `next_cursor`), read to its end. A repeated cursor is malformed; more
     * than [maxPages] pages is reported as possibly incomplete.
     */
    internal suspend fun pages(
        path: String, appNpub: String, key: String, extra: List<Pair<String, String>> = emptyList(), maxPages: Int = 50,
    ): List<JsonObject> {
        val rows = ArrayList<JsonObject>()
        var cursor: String? = null
        val seen = HashSet<String>()
        repeat(maxPages) {
            val query = mutableListOf("limit" to "200") + extra
            val withCursor = cursor?.let { query + ("cursor" to it) } ?: query
            val o = obj(client.request(path = path, query = withCursor, appNpub = appNpub), key)
            val page = o.objects(key) ?: throw TowerError.Malformed(key)
            rows.addAll(page)
            val next = o.string("next_cursor")
            if (next.isNullOrEmpty()) return rows
            if (!seen.add(next)) throw TowerError.Malformed("$key cursor")
            cursor = next
        }
        throw TowerError.ListLimitReached(key)
    }
}
