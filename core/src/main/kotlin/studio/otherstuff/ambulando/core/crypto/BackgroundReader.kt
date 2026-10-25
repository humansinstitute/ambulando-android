package studio.otherstuff.ambulando.core.crypto

import kotlinx.serialization.Serializable
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.WireJson
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.net.URI
import java.util.Base64

/**
 * The opt-in "refresh while locked" credential.
 *
 * The identity vault is PIN-sealed and its key exists only in memory while unlocked, so after the process ends nothing
 * could be refreshed in the background. If the user explicitly turns on background refresh, a copy of the key is kept
 * in a separate device-only platform secret together with a policy. Code holding it can sign **only** NIP-98 `GET`
 * requests to the pinned Tower origin for the pinned workspaces: no message signatures, no POST/PUT/PATCH/DELETE, no
 * other host. Sending still needs the PIN.
 *
 * Locking manually removes it (background refresh stops until the next unlock); turning the setting off or removing
 * the identity removes it as well.
 *
 * Platform stores persist the opaque [encode] bytes and give them back to [decode]; the secret is never exposed.
 */
class BackgroundReadCredential internal constructor(
    val version: Int = 1,
    val publicKeyHex: String,
    internal val secretKey: ByteArray,
    val origin: String,
    val workspaceIds: List<String>,
    val createdAt: String,
) {
    val policy: ReadPolicy get() = ReadPolicy(origin, workspaceIds.toSet())

    internal fun withWorkspaces(ids: List<String>) = BackgroundReadCredential(version, publicKeyHex, secretKey, origin, ids, createdAt)

    /** JSON with the same field names as the iOS item (`secretKey` is standard base64). */
    fun encode(): ByteArray = WireJson.encodeToString(
        Wire.serializer(),
        Wire(version, publicKeyHex, Base64.getEncoder().encodeToString(secretKey), origin, workspaceIds, createdAt),
    ).toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is BackgroundReadCredential && version == other.version &&
        publicKeyHex == other.publicKeyHex && secretKey.contentEquals(other.secretKey) && origin == other.origin &&
        workspaceIds == other.workspaceIds && createdAt == other.createdAt

    override fun hashCode(): Int = publicKeyHex.hashCode() * 31 + workspaceIds.hashCode()

    override fun toString(): String = "BackgroundReadCredential(publicKeyHex=$publicKeyHex, origin=$origin, workspaceIds=$workspaceIds)"

    @Serializable
    internal data class Wire(
        val version: Int = 1,
        val publicKeyHex: String,
        val secretKey: String,
        val origin: String,
        val workspaceIds: List<String>,
        val createdAt: String,
    )

    companion object {
        /** Decodes stored bytes; null for anything unreadable (an unreadable item is treated as absent, as on iOS). */
        fun decode(bytes: ByteArray): BackgroundReadCredential? = runCatching {
            val w = WireJson.decodeFromString(Wire.serializer(), bytes.toString(Charsets.UTF_8))
            BackgroundReadCredential(w.version, w.publicKeyHex, Base64.getDecoder().decode(w.secretKey), w.origin, w.workspaceIds, w.createdAt)
        }.getOrNull()
    }
}

/** Which signing requests the background credential may answer. */
data class ReadPolicy(val origin: String, val workspaceIds: Set<String>) {
    fun allows(template: NostrEventTemplate): Boolean {
        if (template.kind != NIP98.KIND || template.content.isNotEmpty() || template.tags.size != 2) return false
        val u = template.tags[0]
        if (u.size != 2 || u[0] != "u" || template.tags[1] != listOf("method", "GET")) return false
        val url = runCatching { URI(u[1]) }.getOrNull() ?: return false
        val pinned = runCatching { TowerOrigin(origin) }.getOrNull() ?: return false
        if (url.scheme != "https" || url.host == null || url.host != pinned.host || url.port.takeIf { it >= 0 } != pinned.port ||
            url.rawUserInfo != null || url.rawFragment != null
        ) return false
        val path = url.path ?: return false
        if (path.contains("..") || !(path == PREFIX || path.startsWith("$PREFIX/"))) return false
        if (path == PREFIX) return true   // workspace discovery
        val workspace = path.substring(PREFIX.length + 1).split('/').firstOrNull { it.isNotEmpty() } ?: ""
        return workspace in workspaceIds
    }

    companion object {
        const val PREFIX = "/api/v4/flightdeck-pg/workspaces"
    }
}

/**
 * Storage for the background credential. The production implementation is a device-only, never backed up platform
 * secret readable after the first unlock since boot. `read` returns null when absent or unreadable as a credential;
 * any operation that cannot reach the platform store throws [IdentityError.KeychainUnavailable].
 */
interface BackgroundCredentialStore {
    fun read(): BackgroundReadCredential?
    fun write(credential: BackgroundReadCredential)
    fun delete()
}

/** In-memory signer for the background credential. It refuses everything outside its policy. */
internal class BackgroundReader(val credential: BackgroundReadCredential) {
    val npub: String? get() = NostrKeys.npub(credential.publicKeyHex)

    fun sign(template: NostrEventTemplate): NostrEvent {
        if (!credential.policy.allows(template)) throw IdentityError.Locked
        val pubkey = runCatching { NostrKeys.publicKeyHex(credential.secretKey) }.getOrNull()
        if (pubkey != credential.publicKeyHex) throw IdentityError.InvalidVault
        return NostrKeys.sign(template, credential.secretKey)
    }
}

/** The trusted-device session key (see [IdentityService.resumeSession]). Opaque to platform stores, like the credential. */
class SessionKey internal constructor(
    val version: Int = 1,
    val publicKeyHex: String,
    internal val secretKey: ByteArray,
) {
    fun encode(): ByteArray = WireJson.encodeToString(
        Wire.serializer(), Wire(version, publicKeyHex, Base64.getEncoder().encodeToString(secretKey)),
    ).toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is SessionKey && version == other.version &&
        publicKeyHex == other.publicKeyHex && secretKey.contentEquals(other.secretKey)

    override fun hashCode(): Int = publicKeyHex.hashCode()
    override fun toString(): String = "SessionKey(publicKeyHex=$publicKeyHex)"

    @Serializable
    internal data class Wire(val version: Int = 1, val publicKeyHex: String, val secretKey: String)

    companion object {
        fun decode(bytes: ByteArray): SessionKey? = runCatching {
            val w = WireJson.decodeFromString(Wire.serializer(), bytes.toString(Charsets.UTF_8))
            SessionKey(w.version, w.publicKeyHex, Base64.getDecoder().decode(w.secretKey))
        }.getOrNull()
    }
}

/**
 * Storage for the trusted-device session key. The production implementation is a device-only platform secret that
 * can be read only while the device is unlocked; `read` returns null when absent or not readable right now.
 */
interface SessionKeyStore {
    fun read(): SessionKey?
    fun write(key: SessionKey)
    fun delete()
}
