package studio.otherstuff.ambulando.core.support

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.crypto.BackgroundCredentialStore
import studio.otherstuff.ambulando.core.crypto.BackgroundReadCredential
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.SessionKey
import studio.otherstuff.ambulando.core.crypto.SessionKeyStore
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.toJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/** Public NIP-19 specification test key. Never a real user identity. Other keys are derived from synthetic secrets. */
object TestKeys {
    const val NSEC = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    const val SECRET_HEX = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
    const val NPUB = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    const val PUBKEY_HEX = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
    const val PIN = "246810"

    /** nsec of the synthetic secret `byte` repeated 32 times. */
    fun syntheticNsec(byte: Int): String = Bech32.encode("nsec", ByteArray(32) { byte.toByte() })
    fun syntheticNpub(byte: Int): String = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { byte.toByte() }))!!
    fun otherNsec(): String = syntheticNsec(0x11)
}

class MemorySecretStore : VaultSecretStore {
    private var value: ByteArray? = null
    var writes = 0
        private set

    @Synchronized override fun read(): ByteArray? = value?.copyOf()
    @Synchronized override fun write(data: ByteArray) {
        value = data.copyOf()
        writes += 1
    }
}

/** Platform secret that cannot be read (as before the first unlock since boot). */
class UnavailableSecretStore : VaultSecretStore {
    override fun read(): ByteArray? = throw IdentityError.KeychainUnavailable
    override fun write(data: ByteArray) = throw IdentityError.KeychainUnavailable
}

/** Background credential store whose delete fails a set number of times. */
open class FlakyBackgroundStore : BackgroundCredentialStore {
    private var value: ByteArray? = null
    @Volatile var failDeletes = 0

    @Synchronized override fun read(): BackgroundReadCredential? = value?.let { BackgroundReadCredential.decode(it) }
    @Synchronized override fun write(credential: BackgroundReadCredential) { value = credential.encode() }
    @Synchronized override fun delete() {
        if (failDeletes > 0) {
            failDeletes -= 1
            throw IdentityError.KeychainUnavailable
        }
        value = null
    }

    val stored: Boolean @Synchronized get() = value != null
}

class MemoryBackgroundStore : FlakyBackgroundStore()

class MemorySessionStore : SessionKeyStore {
    private var value: ByteArray? = null
    @Volatile var failDeletes = 0

    @Synchronized override fun read(): SessionKey? = value?.let { SessionKey.decode(it) }
    @Synchronized override fun write(key: SessionKey) { value = key.encode() }
    @Synchronized override fun delete() {
        if (failDeletes > 0) {
            failDeletes -= 1
            throw IdentityError.KeychainUnavailable
        }
        value = null
    }

    val stored: Boolean @Synchronized get() = value != null
}

private val tempRoot: File by lazy {
    Files.createTempDirectory("ambulando-tests").toFile().also { root ->
        Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
    }
}

fun tempDir(): File = File(tempRoot, UUID.randomUUID().toString()).also { it.mkdirs() }

suspend fun makeIdentity(
    unlocked: Boolean = true, directory: File = tempDir(), secrets: VaultSecretStore = MemorySecretStore(),
): IdentityService {
    val service = IdentityService(File(directory, "vault.json"), secrets)
    service.importIdentity(TestKeys.NSEC, TestKeys.PIN)
    if (!unlocked) service.lock()
    return service
}

/** Records requests and answers from a routing function. No network is touched. */
class StubTransport(handler: (HttpRequest) -> Pair<Int, Any?>) : HttpTransport {
    @Volatile private var handler = handler
    private val requests = ArrayList<HttpRequest>()

    fun setHandler(handler: (HttpRequest) -> Pair<Int, Any?>) { this.handler = handler }
    val recorded: List<HttpRequest> get() = synchronized(requests) { requests.toList() }

    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(requests) { requests.add(request) }
        val (status, body) = handler(request)
        val bytes = when (body) {
            is ByteArray -> body
            null -> ByteArray(0)
            else -> toJsonElement(body).toString().toByteArray()
        }
        return HttpResponse(status, bytes)
    }
}

fun decodeAuthorization(request: HttpRequest): NostrEvent {
    val header = request.header("Authorization") ?: error("no Authorization header")
    require(header.startsWith("Nostr "))
    return WireJson.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(header.removePrefix("Nostr "))))
}

/** Query items of a URL, decoded. */
fun queryOf(url: String): Map<String, String> {
    val parsed = url.toHttpUrlOrNull()!!
    return parsed.queryParameterNames.associateWith { parsed.queryParameter(it) ?: "" }
}

fun pathOf(url: String): String = url.toHttpUrlOrNull()!!.encodedPath

/** Synthetic Tower rows. Every id is a placeholder UUID and the host is a reserved example domain. */
object Fixtures {
    const val ORIGIN = "https://tower.example.com"
    const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000001"
    const val WORKSPACE_ID_2 = "00000000-0000-4000-8000-000000000002"
    const val SCOPE_A = "00000000-0000-4000-8000-00000000000a"
    const val SCOPE_B = "00000000-0000-4000-8000-00000000000b"
    const val CHANNEL_1 = "00000000-0000-4000-8000-000000000c01"
    const val CHANNEL_2 = "00000000-0000-4000-8000-000000000c02"
    val APP_NPUB = TestKeys.NPUB

    fun descriptor(origin: String, fips: Boolean = false, protocols: List<String> = listOf("https", "nip-98")): Map<String, Any?> {
        val services = mutableListOf<Map<String, Any?>>(
            mapOf(
                "id" to "tower", "kind" to "tower", "endpoint" to origin, "version" to "0.1.0", "protocols" to protocols,
                "capabilities" to listOf("workspace.authenticated_discovery", "flightdeck.pg"), "availability" to mapOf("status" to "available"),
            ),
        )
        if (fips) services.add(mapOf("id" to "fips", "kind" to "fips", "endpoint" to "http://npub1x.fips:8080"))
        return mapOf(
            "schema" to "org.wingman.nstack.services", "schema_version" to 1, "stack" to mapOf("tower_npub" to TestKeys.NPUB, "name" to null),
            "services" to services,
        )
    }

    fun workspace(id: String, label: String): Map<String, Any?> = mapOf(
        "identity" to mapOf("workspace_id" to id, "app_npub" to APP_NPUB, "workspace_owner_npub" to TestKeys.NPUB),
        "label" to label, "slug" to label.lowercase(), "tower_base_url" to ORIGIN, "membership" to mapOf("role" to "member"),
    )

    fun scope(id: String, workspace: String = WORKSPACE_ID, name: String, updated: String = "2026-10-01T00:00:00.000Z"): MutableMap<String, Any?> =
        linkedMapOf(
            "id" to id, "workspace_id" to workspace, "name" to name, "description" to null, "kind" to "project", "owner_actor_id" to null,
            "owner_group_id" to null, "default_channel_id" to null, "row_version" to 1, "created_at" to updated, "updated_at" to updated,
        )

    fun channel(
        id: String, scope: String, workspace: String = WORKSPACE_ID, name: String, position: Double? = null, updated: String = "2026-10-01T00:00:00.000Z",
    ): MutableMap<String, Any?> = linkedMapOf(
        "id" to id, "workspace_id" to workspace, "scope_id" to scope, "name" to name, "description" to null, "metadata" to emptyMap<String, Any?>(),
        "kind" to "channel", "position" to position, "participant_npubs" to null, "row_version" to 1, "created_at" to updated, "updated_at" to updated,
    )

    fun member(npub: String, name: String, kind: String = "human"): Map<String, Any?> = mapOf(
        "actor" to mapOf("actor_id" to newId(), "id" to newId(), "npub" to npub, "kind" to kind, "display_name" to name),
    )

    fun message(id: String, channel: String = CHANNEL_1, body: String, thread: String? = null, workspace: String = WORKSPACE_ID): Map<String, Any?> = mapOf(
        "id" to id, "workspace_id" to workspace, "channel_id" to channel, "thread_id" to thread, "body" to body,
        "sender_npub" to TestKeys.NPUB, "created_at" to "2026-10-01T00:00:00.000Z", "row_version" to 1, "future_field" to mapOf("kept" to true),
    )

    fun newId(): String = UUID.randomUUID().toString()

    fun json(value: Map<String, Any?>): ByteArray = jsonBody(toJsonElement(value) as JsonObject)
    fun obj(value: Map<String, Any?>): JsonObject = toJsonElement(value) as JsonObject
}
