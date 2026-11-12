package studio.otherstuff.ambulando.core.discovery

import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Untrusted, decrypted locator; only fresh matching Tower membership admits it to the picker. */
data class PrivateWorkspaceLocator(
    val origin: String, val towerNpub: String, val workspaceId: String, val appNpub: String,
    val serviceNpub: String, val ownerNpub: String, val label: String,
)
data class DiscoveredTower(val origin: String, val towerNpub: String, val label: String)
data class TowerDiscoveryResult(val towers: List<DiscoveredTower>, val skipped: Int, val partial: Boolean)

/** Existing kind-33356 contract shared with Deets and Ambulando web; no originating-app query filter. */
class PrivateWorkspaceIndex(private val pubkey: String, private val decrypt: suspend (String) -> String) {
    companion object {
        const val KIND = 33356
        const val LIMIT = 1000
        fun filter(pubkey: String) = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(KIND))))
            put("authors", JsonArray(listOf(JsonPrimitive(pubkey))))
            put("#p", JsonArray(listOf(JsonPrimitive(pubkey))))
            put("limit", LIMIT)
        }
    }
    data class Entry(val event: NostrEvent, val locator: PrivateWorkspaceLocator, val deleted: Boolean)
    private fun tag(e: NostrEvent, name: String): String {
        val tags = e.tags.filter { it.firstOrNull() == name }
        require(tags.size == 1 && tags.single().size == 2)
        return tags.single()[1]
    }
    suspend fun decode(e: NostrEvent): Entry {
        require(e.id.matches(Regex("[0-9a-f]{64}")))
        require(e.kind == KIND && e.pubkey == pubkey && e.createdAt in 0..(System.currentTimeMillis() / 1000 + 300) && SignatureVerifier.verify(e))
        require(tag(e,"p") == pubkey && tag(e,"protocol") == "workspace-self-index" && tag(e,"v") == "1")
        val app = tag(e,"app_pub")
        require(app.matches(Regex("[0-9a-f]{64}")))
        val payload = WireJson.parseToJsonElement(decrypt(e.content)).jsonObject
        require(payload["type"] == JsonPrimitive("flightdeck_workspace_self_index") && payload["version"] == JsonPrimitive(1) && payload["user_npub"] == JsonPrimitive(NostrKeys.npub(pubkey)))
        val namespace = payload["app"]!!.jsonObject
        require(namespace["app_pubkey"] == JsonPrimitive(app) && namespace["app_npub"] == JsonPrimitive(NostrKeys.npub(app)) && namespace["namespace"] == JsonPrimitive("flightdeck_pg"))
        val raw = payload["workspace"]!!.jsonObject
        require(raw["type"] == JsonPrimitive("wingman_workspace_locator") && raw["version"] == JsonPrimitive(1))
        val identity = (raw["identity"] as? JsonObject) ?: raw
        fun npub(key: String): String = identity[key]!!.jsonPrimitive.content.also { require(NostrKeys.isValidNpub(it)) }
        val id = identity["workspace_id"]!!.jsonPrimitive.content.also { require(isUUID(it) && it == it.lowercase()) }
        val origin = TowerOrigin(raw["tower_base_url"]!!.jsonPrimitive.content).url
        val locator = PrivateWorkspaceLocator(origin, npub("tower_service_npub"), id, npub("app_npub"), npub("workspace_service_npub"), npub("workspace_owner_npub"), raw["label"]?.jsonPrimitive?.content ?: "Workspace")
        val address = "fd-self:" + Sha256Hex.of("v1:$pubkey:$app:${locator.towerNpub}:$id".toByteArray())
        require(tag(e,"d") == address)
        val deleted = payload["state"]!!.jsonObject["deleted"]!!.jsonPrimitive.let { require(!it.isString); it.boolean }
        return Entry(e, locator, deleted)
    }
    suspend fun active(events: List<NostrEvent>): List<PrivateWorkspaceLocator> {
        val newest = events.distinctBy { it.id }
            .filter { it.kind == KIND && it.pubkey == pubkey && it.id.matches(Regex("[0-9a-f]{64}")) && SignatureVerifier.verify(it) }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            // Reduce by the first NIP-33 d only; strict decoding rejects duplicates without cross-address effects.
            .mapNotNull { e -> e.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)?.let { it to e } }
            .distinctBy { it.first }.map { it.second }.distinctBy { it.id }
        val active = ArrayList<PrivateWorkspaceLocator>()
        for (event in newest) {
            try { decode(event).takeUnless { it.deleted }?.let { active += it.locator } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return active
    }

}

class TowerIndexDiscovery(
    private val identity: IdentityService,
    private val transport: HttpTransport,
    private val relay: PrivateIndexRelay = OkHttpPrivateIndexRelay(),
    private val relayUrls: List<String> = listOf("wss://relay.primal.net", "wss://relay.damus.io"),
) {
    suspend fun discover(): TowerDiscoveryResult = withTimeout(45_000) {
        require(identity.state() is IdentityState.Unlocked) { "Unlock your identity to discover Towers." }
        val pubkey = identity.publicKeyHex()
        val index = PrivateWorkspaceIndex(pubkey, identity::decryptPrivateIndex)
        val replies = coroutineScope { relayUrls.take(8).map { url -> async { runCatching { relay.query(url, PrivateWorkspaceIndex.filter(pubkey)) } } }.awaitAll() }
        check(replies.any { it.isSuccess }) { "No discovery relay answered. Retry or enter a Tower address." }
        val events = replies.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.id }
        check(events.size < PrivateWorkspaceIndex.LIMIT) { "Tower discovery was incomplete. Retry or enter an address." }
        val towers = LinkedHashMap<String, DiscoveredTower>()
        var skipped = 0
        for (candidate in index.active(events)) {
            if (towers.containsKey(candidate.origin)) continue
            try {
                withTimeout(8_000) {
                    val api = TowerAPI(TowerClient(TowerOrigin(candidate.origin), transport, identity))
                    val descriptor = api.descriptor()
                    require(descriptor.stack?.towerNpub == candidate.towerNpub) { "Tower identity mismatch" }
                    verifyMembership(candidate)
                    towers[candidate.origin] = DiscoveredTower(candidate.origin, candidate.towerNpub, descriptor.stack?.name ?: candidate.origin.removePrefix("https://"))
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (e !is TimeoutCancellationException) throw e
                skipped++
            } catch (_: Exception) { skipped++ }
        }
        TowerDiscoveryResult(towers.values.toList(), skipped, replies.any { it.isFailure })
    }

    suspend fun verifyMembership(candidate: PrivateWorkspaceLocator) {
        val client = TowerClient(TowerOrigin(candidate.origin), transport, identity)
        val response = WireJson.parseToJsonElement(client.request(path = TowerAPI.base(candidate.workspaceId) + "/me", appNpub = candidate.appNpub).toString(Charsets.UTF_8)).jsonObject
        val i = response["identity"]!!.jsonObject
        require(i["workspace_id"] == JsonPrimitive(candidate.workspaceId) && i["app_npub"] == JsonPrimitive(candidate.appNpub) &&
            i["tower_service_npub"] == JsonPrimitive(candidate.towerNpub) && i["workspace_service_npub"] == JsonPrimitive(candidate.serviceNpub) &&
            i["workspace_owner_npub"] == JsonPrimitive(candidate.ownerNpub)) { "Workspace membership identity changed" }
    }
}
