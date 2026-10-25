package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.lowerUUID
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Single owner of the vault: every operation runs under one mutex on a background dispatcher, so PBKDF2 and Schnorr
 * signing never run on the main thread and never interleave.
 *
 * Two signing capabilities exist. The unlocked vault signs anything the app asks for. The optional background
 * credential (see [BackgroundReadCredential]) is used only when the vault is locked, and only for NIP-98 GETs its
 * policy allows; [state] still reports [IdentityState.Locked], so the UI never treats it as an unlock.
 *
 * Trusted-device session: after a PIN unlock or import the key is kept in [session] (a device-only platform secret
 * readable only while the device is unlocked), so the app stays unlocked across relaunch until lock / remove /
 * disconnect. Both the session and the background credential are revoked through durable marker files written before
 * the platform item is deleted: while a marker exists the item is never loaded or used, even if deleting it failed
 * (revocation fails closed).
 */
class IdentityService(
    vaultFile: File,
    secrets: VaultSecretStore,
    private val backgroundStore: BackgroundCredentialStore? = null,
    private val sessionStore: SessionKeyStore? = null,
) : EventSigner {
    private val mutex = Mutex()
    private val vault = IdentityVault(vaultFile, secrets)
    private var reader: BackgroundReader? = null
    private val directory: File = vaultFile.absoluteFile.parentFile
    private val revocationFile = File(directory, "background-read.revoked")
    private val sessionRevocationFile = File(directory, "session.revoked")

    /** The last revocation could not delete the platform item; the marker still blocks it and deletion is retried. */
    private var revocationIncompleteFlag = false

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.Default) { mutex.withLock { block() } }

    private val sessionRevoked: Boolean get() = sessionRevocationFile.exists()
    private val revoked: Boolean get() = revocationFile.exists()

    private fun writeMarker(file: File): Boolean =
        runCatching { AtomicFiles.write(file, "revoked".toByteArray(Charsets.UTF_8)) }.isSuccess

    /**
     * Writes the session revocation marker first, then deletes the platform item (fails closed like the background
     * credential). Throws only if neither could be done.
     */
    private fun revokeSession() {
        val store = sessionStore ?: return
        var markerWritten = sessionRevoked
        if (!markerWritten) markerWritten = writeMarker(sessionRevocationFile)
        try {
            store.delete()
        } catch (e: Exception) {
            revocationIncompleteFlag = true
            if (!markerWritten) throw IdentityError.KeychainUnavailable
        }
    }

    /** Keeps the unlocked key for the trusted-device session (after a PIN unlock or import). */
    private fun storeSession() {
        val store = sessionStore ?: return
        val hex = vault.publicKeyHex ?: return
        val key = runCatching { vault.secretCopy() }.getOrNull() ?: return
        try {
            if (runCatching { store.write(SessionKey(1, hex, key)) }.isFailure) return
            sessionRevocationFile.delete()
        } finally {
            key.fill(0)
        }
    }

    private fun resumeSessionLocked(): Boolean {
        if (vault.isUnlocked) return true
        vault.reload()
        val store = sessionStore
        val stored = if (store != null && !sessionRevoked) runCatching { store.read() }.getOrNull() else null
        if (stored != null && stored.publicKeyHex == vault.publicKeyHex && runCatching { vault.unlock(stored.secretKey) }.isSuccess) return true
        // Finish an earlier failed delete.
        if (sessionRevoked && store != null && runCatching { store.read() }.getOrNull() != null) runCatching { store.delete() }
        return false
    }

    /**
     * Resumes the trusted-device session at launch: unlocked without the PIN only if a session key exists for this
     * identity and was not revoked by lock, remove or disconnect. Returns whether the identity is now unlocked.
     */
    suspend fun resumeSession(): Boolean = locked { resumeSessionLocked() }

    /** Ends the trusted-device session without locking the identity in memory (disconnect). */
    suspend fun endSession() = locked { revokeSession() }

    /**
     * Revokes the background credential: memory first, then the durable marker, then the platform item. Throws only
     * if the marker could not be written either (nothing then guarantees revocation, so the caller must report it).
     */
    private fun revokeBackgroundRead() {
        reader = null
        val store = backgroundStore ?: return
        var markerWritten = revoked
        if (!markerWritten) markerWritten = writeMarker(revocationFile)
        try {
            store.delete()
            revocationIncompleteFlag = false
        } catch (e: Exception) {
            revocationIncompleteFlag = true
            if (!markerWritten) throw IdentityError.KeychainUnavailable
        }
    }

    private fun stateLocked(): IdentityState {
        if (!vault.exists) return IdentityState.None
        vault.reload()
        val npub = vault.publicKeyHex?.let { NostrKeys.npub(it) } ?: return IdentityState.Unavailable
        return if (vault.isUnlocked) IdentityState.Unlocked(npub) else IdentityState.Locked(npub)
    }

    suspend fun state(): IdentityState = locked { stateLocked() }

    /** The last revocation could not delete the platform item (the marker still blocks it). */
    suspend fun revocationIncomplete(): Boolean = locked { revocationIncompleteFlag }

    /** True while reads can be signed without the PIN (background credential loaded, matching, not revoked). */
    suspend fun canReadWhileLocked(): Boolean = locked { reader != null && !revoked }

    /** Whether a usable background credential is stored for this identity (it may not be loaded yet). */
    suspend fun backgroundReadEnabled(): Boolean = locked {
        if (revoked) return@locked false
        val stored = runCatching { backgroundStore?.read() }.getOrNull() ?: return@locked false
        stored.publicKeyHex == vault.knownPublicKeyHex
    }

    /** Why the existing vault could not be read (null when readable or absent). */
    suspend fun vaultProblem(): VaultReadProblem? = locked {
        if (vault.exists && vault.publicKeyHex == null) vault.readProblem ?: VaultReadProblem.UNREADABLE else null
    }

    /**
     * Reads the vault and the trusted-device session again (app became active, protected storage became available).
     * A launch that could read neither recovers both without the PIN when allowed.
     */
    suspend fun refreshAvailability(): IdentityState = locked {
        vault.reload()
        if (vault.publicKeyHex != null) resumeSessionLocked()
        stateLocked()
    }

    /**
     * Imports an identity. Refused while any vault file exists (readable or not), and, when the app already has a
     * Tower connection, unless the key is that connection's identity: an import never replaces an account.
     */
    suspend fun importIdentity(nsec: String, pin: String, requiredNpub: String? = null): IdentityState = locked {
        if (vault.exists) throw IdentityError.Exists
        if (requiredNpub != null) {
            val scalar = NostrKeys.secretKey(nsec) ?: throw IdentityError.InvalidNsec
            try {
                if (NostrKeys.npub(NostrKeys.publicKeyHex(scalar)) != requiredNpub) throw IdentityError.AccountMismatch
            } finally {
                scalar.fill(0)
            }
        }
        vault.create(nsec, pin)
        storeSession()
        stateLocked()
    }

    suspend fun unlock(pin: String): IdentityState = locked {
        vault.unlock(pin)
        storeSession()
        stateLocked()
    }

    /**
     * Manual lock. Also revokes the session and the background credential: nothing is signed until the PIN is entered
     * again. The vault is always locked; a revocation that could not be recorded at all is reported by
     * [revocationIncomplete].
     */
    suspend fun lock(): IdentityState = locked {
        vault.lock()
        try { revokeSession() } catch (e: Exception) { revocationIncompleteFlag = true }
        try { revokeBackgroundRead() } catch (e: Exception) { revocationIncompleteFlag = true }
        stateLocked()
    }

    suspend fun remove(): IdentityState = locked {
        revokeSession()
        revokeBackgroundRead()
        vault.remove()
        stateLocked()
    }

    /** Stores the background credential for [origin] and the given workspaces. Requires the unlocked vault. */
    suspend fun enableBackgroundRead(origin: TowerOrigin, workspaceIds: List<String>) = locked {
        val store = backgroundStore ?: throw IdentityError.KeychainUnavailable
        val hex = vault.publicKeyHex ?: throw IdentityError.Missing
        val credential = BackgroundReadCredential(
            publicKeyHex = hex, secretKey = vault.secretCopy(), origin = origin.toString(),
            workspaceIds = workspaceIds.mapNotNull { lowerUUID(it) }.sorted(),
            createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
        )
        store.write(credential)
        // The marker is removed only once the new credential is stored, so a failed enable never un-revokes an old one.
        revocationFile.delete()
        if (revoked) throw IdentityError.KeychainUnavailable
        reader = BackgroundReader(credential)
        revocationIncompleteFlag = false
    }

    suspend fun disableBackgroundRead() = locked { revokeBackgroundRead() }

    /** Updates the pinned workspaces (after discovery while unlocked). Only an existing credential is changed. */
    suspend fun updateBackgroundWorkspaces(origin: TowerOrigin, workspaceIds: List<String>) = locked {
        val store = backgroundStore
        if (!vault.isUnlocked || revoked || store == null) return@locked
        val stored = store.read() ?: return@locked
        if (stored.publicKeyHex != vault.publicKeyHex || stored.origin != origin.toString()) return@locked
        val ids = workspaceIds.mapNotNull { lowerUUID(it) }.sorted()
        if (ids == stored.workspaceIds) return@locked
        val updated = stored.withWorkspaces(ids)
        store.write(updated)
        reader = BackgroundReader(updated)
    }

    /** Loads a stored background credential if it belongs to this identity (launch, including background launch). */
    suspend fun loadBackgroundRead(): Boolean = locked {
        if (revoked) {
            // Revoked earlier: never load, and finish deleting the platform item if that failed before.
            reader = null
            if (revocationIncompleteFlag || runCatching { backgroundStore?.read() }.getOrNull() != null) {
                runCatching { revokeBackgroundRead() }
            }
            return@locked false
        }
        if (reader != null) return@locked true
        // Matched against the vault's key, or (vault sealed) the public-key hint. The state stays locked/unavailable:
        // this credential only signs the GETs its policy allows.
        val stored = runCatching { backgroundStore?.read() }.getOrNull() ?: return@locked false
        val known = vault.knownPublicKeyHex ?: return@locked false
        if (stored.publicKeyHex != known) return@locked false
        reader = BackgroundReader(stored)
        true
    }

    suspend fun backgroundPolicy(): ReadPolicy? = locked { if (revoked) null else reader?.credential?.policy }

    override suspend fun sign(template: NostrEventTemplate): NostrEvent = locked {
        if (vault.isUnlocked) return@locked vault.sign(template)
        val r = reader
        if (r == null || revoked) throw IdentityError.Locked
        r.sign(template)
    }

    suspend fun publicKeyHex(): String = locked {
        val hex = vault.publicKeyHex
        if (vault.isUnlocked && hex != null) return@locked hex
        val r = reader
        if (r != null && !revoked) return@locked r.credential.publicKeyHex
        throw IdentityError.Locked
    }

    private fun requireSignerLocked(npub: String) {
        val current = stateLocked() as? IdentityState.Unlocked ?: throw IdentityError.Locked
        if (current.npub != npub) throw IdentityError.SignerMismatch
    }

    /** Throws unless the unlocked identity is exactly [npub] (connection binding). Required for every write. */
    suspend fun requireSigner(npub: String) = locked { requireSignerLocked(npub) }

    /**
     * Like [requireSigner], but also satisfied by the background credential of the same identity. For read jobs only;
     * the credential's policy still refuses anything but GETs on its pinned origin and workspaces.
     */
    suspend fun requireReader(npub: String) = locked {
        if (stateLocked() is IdentityState.Unlocked) return@locked requireSignerLocked(npub)
        val r = reader
        if (r == null || revoked) throw IdentityError.Locked
        if (r.npub != npub) throw IdentityError.SignerMismatch
    }
}
