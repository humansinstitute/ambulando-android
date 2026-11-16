package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.*
import java.util.Base64
import java.security.SecureRandom

/** Shared v3 metadata is a locator, never membership or app consent. */
@ConsistentCopyVisibility
data class WorkspaceConnection private constructor(val payload: JsonObject) {
    val workspaceId get() = payload.getValue("workspace_id").jsonPrimitive.content
    val serviceNpub get() = payload.getValue("service_npub").jsonPrimitive.content
    val authority get() = payload.getValue("workspace_service_npub").jsonPrimitive.content
    val name get() = payload["name"]?.jsonPrimitive?.content ?: "NStack workspace"
    val preferred get() = payload.getValue("policy").jsonObject.getValue("preferred").jsonPrimitive.content
    val modes get() = payload.getValue("transports").jsonObject.keys.toList()
    fun origin(mode: String = preferred): TowerOrigin {
        require(mode in modes) { "Unsupported transport" }
        if (mode == "fips") {
            val f = payload.getValue("transports").jsonObject.getValue("fips").jsonObject
            return TowerOrigin(FipsPeerEndpoint(text(f, "peer_npub"), text(f, "host"), f.getValue("port").jsonPrimitive.int).origin)
        }
        return TowerOrigin(payload.getValue("transports").jsonObject.getValue("https").jsonObject.getValue("url").jsonPrimitive.content)
    }
    fun verifyProof(event: NostrEvent, nonce: String, url: String, now: Long = System.currentTimeMillis() / 1000) {
        val p = signed(event, 30384, "nstack-connection-challenge-v1", now)
        shape(p, setOf("nonce", "service_npub", "workspace_id", "workspace_service_npub", "request_url", "expires_at"))
        val expiry = p["expires_at"]?.jsonPrimitive?.longOrNull
        require(nonce.matches(Regex("[0-9a-f]{64}")) && text(p, "nonce") == nonce && text(p, "request_url") == url &&
            text(p, "service_npub") == serviceNpub && NostrKeys.npub(event.pubkey) == serviceNpub && text(p, "workspace_id") == workspaceId &&
            text(p, "workspace_service_npub") == authority && expiry != null && expiry > now && expiry <= now + 60 &&
            event.createdAt in (now - 60)..now && expiry == event.createdAt + 60) { "Workspace connection proof mismatch or expired" }
    }
    companion object {
        const val PREFIX = "nstack:workspace:v3:"
        fun parse(envelope: String, now: Long = System.currentTimeMillis() / 1000): WorkspaceConnection {
            require(envelope.toByteArray().size <= 8192 && envelope.startsWith(PREFIX)) { "Unsupported workspace connection envelope" }
            val encoded = envelope.removePrefix(PREFIX)
            require(encoded.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid connection encoding" }
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
            val raw = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val o = canonical(raw)
            shape(o, setOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig"))
            val event = WireJson.decodeFromString<NostrEvent>(raw)
            val p = signed(event, 30383, "nstack-workspace-connection-v3", now)
            val required = setOf("type", "version", "service_npub", "workspace_id", "workspace_service_npub", "transports", "policy")
            shape(p, required + "name", required)
            require(text(p, "type") == "nstack-workspace" && p["version"]?.jsonPrimitive?.intOrNull == 3) { "Unsupported workspace connection version" }
            npub(text(p, "service_npub")); npub(text(p, "workspace_service_npub"))
            require(NostrKeys.npub(event.pubkey) == text(p, "service_npub")) { "Service identity mismatch" }
            require(text(p, "workspace_id").matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            p["name"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 128) }
            val t = p.getValue("transports").jsonObject
            shape(t, setOf("https", "fips"), emptySet()); require(t.isNotEmpty())
            t["https"]?.let { value ->
                val h = value.jsonObject; shape(h, setOf("url")); val u = text(h, "url")
                require(!TowerOrigin(u).isFips && TowerOrigin(u).toString() == u) { "Noncanonical HTTPS origin" }
            }
            t["fips"]?.let { value ->
                val f = value.jsonObject; shape(f, setOf("peer_npub", "host", "port")); val peer = text(f, "peer_npub"); npub(peer)
                FipsPeerEndpoint(peer, text(f, "host"), f["port"]?.jsonPrimitive?.intOrNull ?: error("Invalid FIPS port"))
            }
            val policy = p.getValue("policy").jsonObject; shape(policy, setOf("preferred", "fallback"))
            require(text(policy, "preferred") in t.keys && text(policy, "fallback") == "none")
            return WorkspaceConnection(p)
        }
        private fun npub(value: String) { require(NostrKeys.publicKeyHex(value)?.let { NostrKeys.npub(it) } == value) { "Invalid canonical npub" } }
        internal fun text(o: JsonObject, k: String): String = o.getValue(k).jsonPrimitive.let { require(it.isString); it.content }
        private fun shape(o: JsonObject, allowed: Set<String>, required: Set<String> = allowed) { require(o.keys.all { it in allowed } && o.keys.containsAll(required)) { "Invalid connection shape" } }
        // Re-serialize preserving key order and ECMAScript escaping. Duplicate fields,
        // alternate escapes, whitespace and numeric spellings cannot round-trip.
        private fun stringify(v: JsonElement, depth: Int = 0): String {
            require(depth <= 32) { "Connection JSON nesting is too deep" }
            return when (v) {
            is JsonObject -> v.entries.joinToString(",", "{", "}") { NostrSerialization.quote(it.key) + ":" + stringify(it.value, depth + 1) }
            is JsonArray -> v.joinToString(",", "[", "]") { stringify(it, depth + 1) }
            is JsonPrimitive -> if (v.isString) NostrSerialization.quote(v.content) else {
                require(v.content in listOf("true", "false", "null") || v.longOrNull?.toString() == v.content); v.content
            }
        }
        }
        internal fun canonical(raw: String): JsonObject {
            val value = WireJson.parseToJsonElement(raw)
            require(stringify(value) == raw) { "Noncanonical or duplicate JSON" }; return value.jsonObject
        }
        private fun signed(event: NostrEvent, kind: Int, tag: String, now: Long): JsonObject {
            require(event.kind == kind && event.createdAt in 0..(now + 300) && event.tags == listOf(listOf("d", tag)) &&
                event.id.matches(Regex("[0-9a-f]{64}")) && event.pubkey.matches(Regex("[0-9a-f]{64}")) && event.sig.matches(Regex("[0-9a-f]{128}")) && SignatureVerifier.verify(event)) { "Invalid signed workspace proof" }
            return canonical(event.content)
        }
    }
}

/** Authenticated proof precedes any durable connection change. */
suspend fun TowerAPI.verifyConnection(connection: WorkspaceConnection) {
    val nonce = Hex.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val path = TowerAPI.base(connection.workspaceId) + "/connection-proof"
    val query = listOf("nonce" to nonce)
    val url = client.origin.url(path, query).toString()
    val result = WorkspaceConnection.canonical(client.request(path = path, query = query, appNpub = null).toString(Charsets.UTF_8))
    val raw = result.getValue("proof").jsonObject
    require(raw.keys == setOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig"))
    connection.verifyProof(WireJson.decodeFromString<NostrEvent>(raw.toString()), nonce, url)
}

/** Require an existing authorized app namespace; scanning cannot register one. */
suspend fun TowerAPI.connectionWorkspace(connection: WorkspaceConnection): WorkspaceRecord {
    verifyConnection(connection)
    val candidates = workspaces().filter { it.id == connection.workspaceId }
    require(candidates.size == 1) { "The exact workspace must have one authorized app namespace. Ask its administrator to configure Ambulando, or use the existing namespace chooser." }
    val workspace = candidates.single()
    val i = workspace.raw.getValue("identity").jsonObject
    require(WorkspaceConnection.text(i, "workspace_service_npub") == connection.authority && WorkspaceConnection.text(i, "tower_service_npub") == connection.serviceNpub) { "Workspace authority mismatch" }
    // The existing app-specific read enforces registration and consent.
    val me = parseJsonObject(client.request(path = TowerAPI.base(workspace.id) + "/me", appNpub = workspace.appNpub), "workspace membership")
    val identity = me.getValue("identity").jsonObject
    require(WorkspaceConnection.text(identity, "workspace_id") == connection.workspaceId &&
        WorkspaceConnection.text(identity, "workspace_service_npub") == connection.authority &&
        WorkspaceConnection.text(identity, "tower_service_npub") == connection.serviceNpub &&
        WorkspaceConnection.text(identity, "app_npub") == workspace.appNpub) { "Workspace membership identity mismatch" }
    return workspace
}
