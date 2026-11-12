package studio.otherstuff.ambulando.core.discovery

import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant

/** Public hash-to-curve-point namespace, shared by Android/iOS; no private scalar exists in the apps. */
object NativeIndexNamespace {
    const val LABEL = "org.wingman.nstack.app:studio.otherstuff.ambulando.native:v1"
    const val PUBKEY = "5494fba97efec3d5d9c66047b17d2172bc6a8bd02e6d539b93d9353d7dcaf553"
    const val NPUB = "npub12j20h2t7lmpatkwxvprmzlfpw27x4z7s9ek48xunmy6n6lw274fs40dp7t"
}

fun privateLocator(origin: String, workspace: WorkspaceRecord, expectedTowerNpub: String?): PrivateWorkspaceLocator {
    val i = workspace.raw["identity"]!!.jsonObject
    require(i["workspace_id"] == JsonPrimitive(workspace.id) && i["app_npub"] == JsonPrimitive(workspace.appNpub)) { "Workspace locator boundary mismatch" }
    fun npub(name: String): String = i[name]!!.jsonPrimitive.content.also { require(NostrKeys.isValidNpub(it)) }
    val tower = npub("tower_service_npub")
    require(expectedTowerNpub == null || tower == expectedTowerNpub)
    return PrivateWorkspaceLocator(TowerOrigin(origin).url, tower, workspace.id, workspace.appNpub,
        npub("workspace_service_npub"), npub("workspace_owner_npub"), workspace.label)
}

/** The user signs the index; the originating app namespace never signs or replaces the workspace locator app header. */
class NativeWorkspacePublisher(
    private val identity: IdentityService, private val transport: HttpTransport,
    private val relay: PrivateIndexRelay = OkHttpPrivateIndexRelay(),
    private val relays: List<String> = listOf("wss://relay.primal.net", "wss://relay.damus.io"),
) {
    suspend fun requireSigner(npub: String) = identity.requireSigner(npub)
    private suspend fun verify(locator: PrivateWorkspaceLocator) {
        identity.requireSigner(NostrKeys.npub(identity.publicKeyHex())!!)
        TowerIndexDiscovery(identity,transport,relay,relays).verifyMembership(locator)
    }
    suspend fun prepare(locator: PrivateWorkspaceLocator): NostrEvent {
        verify(locator)
        val pubkey = identity.publicKeyHex()
        val now = System.currentTimeMillis() / 1000
        val stamp = Instant.ofEpochSecond(now).toString()
        val body = buildJsonObject {
            put("type", "flightdeck_workspace_self_index"); put("version",1)
            put("user_npub",NostrKeys.npub(pubkey)); put("issued_at",stamp); put("updated_at",stamp)
            put("app",buildJsonObject { put("app_npub",NativeIndexNamespace.NPUB); put("app_pubkey",NativeIndexNamespace.PUBKEY); put("namespace","flightdeck_pg") })
            put("workspace",buildJsonObject {
                put("type","wingman_workspace_locator"); put("version",1); put("tower_base_url",locator.origin)
                put("tower_service_npub",locator.towerNpub); put("workspace_id",locator.workspaceId)
                put("workspace_service_npub",locator.serviceNpub); put("workspace_owner_npub",locator.ownerNpub)
                put("app_npub",locator.appNpub); put("label",locator.label)
            })
            put("state",buildJsonObject { put("deleted",false) })
        }
        val address = "fd-self:" + Sha256Hex.of("v1:$pubkey:${NativeIndexNamespace.PUBKEY}:${locator.towerNpub}:${locator.workspaceId}".toByteArray())
        val tags = listOf(listOf("d",address),listOf("p",pubkey),listOf("app_pub",NativeIndexNamespace.PUBKEY),listOf("protocol","workspace-self-index"),listOf("v","1"))
        return identity.sign(NostrEventTemplate(PrivateWorkspaceIndex.KIND,tags,identity.encryptPrivateIndex(body.toString()),now))
    }
    suspend fun matches(locator: PrivateWorkspaceLocator, event: NostrEvent): Boolean {
        val reader = PrivateWorkspaceIndex(identity.publicKeyHex(),identity::decryptPrivateIndex)
        return try { reader.decode(event).locator == locator && event.tags.single { it[0] == "app_pub" }[1] == NativeIndexNamespace.PUBKEY }
        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /** Retried events keep their exact ciphertext/signature. Success requires ACK and exact readback/decryption. */
    suspend fun publish(locator: PrivateWorkspaceLocator, event: NostrEvent) = withTimeout(45_000) {
        verify(locator)
        val reader = PrivateWorkspaceIndex(identity.publicKeyHex(),identity::decryptPrivateIndex)
        require(reader.decode(event).locator == locator && event.tags.single { it[0] == "app_pub" }[1] == NativeIndexNamespace.PUBKEY)
        for (url in relays.take(8)) {
            try {
                if (!relay.publish(url,event)) continue
                val read = relay.query(url,buildJsonObject { put("ids",JsonArray(listOf(JsonPrimitive(event.id)))) })
                val exact = read.firstOrNull { it.id == event.id } ?: continue
                if (reader.decode(exact).locator == locator) return@withTimeout
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (e !is TimeoutCancellationException) throw e
            } catch (_: Exception) { }
        }
        error("Private workspace announcement is pending. Connection remains usable; retry publication.")
    }
}
