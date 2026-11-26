package studio.otherstuff.ambulando.core.identitybackup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.crypto.Nip49
import studio.otherstuff.ambulando.core.crypto.Nip49Error
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin

/**
 * Restores an identity on a device that has none: fetches the public ciphertext by username, decrypts it here with the person's
 * password, and seals the key into the device vault under a new PIN. The password and the key stay on the device; the only
 * request is an unauthenticated read that returns ciphertext. An existing identity is never replaced (the vault refuses), and a
 * wrong password, a tampered backup and a refused cost all leave the device exactly as it was.
 */
class HostedBackupRecovery(
    private val identity: IdentityService, private val transport: HttpTransport, private val store: HostedBackupStore,
) {
    suspend fun recover(origin: TowerOrigin, rawUsername: String, password: CharSequence, pin: String): IdentityState {
        val username = BackupUsername.normalize(rawUsername) ?: throw BackupFailure(BackupFailure.Kind.INVALID_USERNAME)
        if (password.isEmpty()) throw BackupFailure(BackupFailure.Kind.WRONG_PASSWORD)
        if (!studio.otherstuff.ambulando.core.crypto.IdentityVault.validPIN(pin)) throw IdentityError.InvalidPIN
        try { TowerAPI(TowerClient(origin, transport, null)).descriptor() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw BackupFailure(BackupFailure.Kind.NETWORK) }
        val copy = HostedBackupAPI(origin, transport, null).fetch(username)
        val decrypted = try { withContext(Dispatchers.Default) { Nip49.decrypt(copy.ncryptsec, password, BackupCiphertext.MAX_LOG_N) } }
        catch (e: Nip49Error) {
            throw BackupFailure(if (e == Nip49Error.CostOutOfBounds) BackupFailure.Kind.COST_TOO_HIGH else BackupFailure.Kind.WRONG_PASSWORD)
        }
        try {
            val state = identity.importRecovered(decrypted.secret, pin)
            // Bookkeeping only: this identity is "existing" (recovered), and its hosted copy is already proven by the decrypt above.
            val npub = (state as? IdentityState.Unlocked)?.npub ?: (state as? IdentityState.Locked)?.npub
            if (npub != null && NostrKeys.publicKeyHex(decrypted.secret) == NostrKeys.publicKeyHex(npub)) {
                runCatching { store.save(HostedBackupRecord(origin = origin.url, npub = npub, username = username, ncryptsec = copy.ncryptsec, expectedRevision = copy.revision, confirmed = true, revision = copy.revision)) }
            }
            return state
        } finally { decrypted.secret.fill(0) }
    }
}
