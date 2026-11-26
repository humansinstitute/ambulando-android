package studio.otherstuff.ambulando.core.identitybackup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.Nip49
import studio.otherstuff.ambulando.core.crypto.Nip49Error
import studio.otherstuff.ambulando.core.nostr.EventSigner
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

enum class HostedBackupStage { NONE, PENDING, CONFIRMED }

data class HostedBackupSnapshot(
    val stage: HostedBackupStage = HostedBackupStage.NONE,
    val username: String? = null,
    val revision: String? = null,
    val failure: BackupFailure? = null,
    val busy: Boolean = false,
)

/**
 * Backs up one on-device identity to one NStack as an opaque, password-encrypted `ncryptsec`, and counts the copy only after
 * proving it.
 *
 * Order of every save: validate locally, encrypt on the device (the vault proves the result decrypts to this identity), write the
 * ciphertext to disk, and only then touch the network. A hosted copy counts as [HostedBackupStage.CONFIRMED] solely when a
 * signed read-back names the username, the public ciphertext equals what was stored, and it decrypts with the person's password to
 * exactly this identity. Memory is updated only after the confirmation is durable, and no exception path ever reports a copy done.
 * Neither the password nor the key crosses the network or is stored.
 */
class HostedBackupController(
    private val origin: TowerOrigin,
    private val npub: String,
    private val signer: EventSigner,
    private val requireSigner: suspend (String) -> Unit,
    private val encrypt: suspend (password: CharSequence, logN: Int) -> String,
    transport: HttpTransport,
    private val store: HostedBackupStore,
    private val logN: Int = BackupCiphertext.DEFAULT_LOG_N,
) {
    private val expectedHex = NostrKeys.publicKeyHex(npub) ?: ""
    private val bound = object : EventSigner {
        override suspend fun sign(template: NostrEventTemplate): NostrEvent {
            if (expectedHex.isEmpty()) throw IdentityError.SignerMismatch
            requireSigner(npub)
            val event = signer.sign(template)
            if (event.pubkey != expectedHex) throw IdentityError.SignerMismatch
            return event
        }
    }
    private val api = HostedBackupAPI(origin, transport, bound)
    private val publicOnly = TowerAPI(TowerClient(origin, transport, null))
    private val gate = Mutex()
    private var state: HostedBackupSnapshot
    private val flow: MutableStateFlow<HostedBackupSnapshot>

    init {
        require(logN in BackupCiphertext.MIN_LOG_N..BackupCiphertext.MAX_LOG_N)
        state = store.load(origin.url, npub).toSnapshot()
        flow = MutableStateFlow(state)
    }

    val snapshots: StateFlow<HostedBackupSnapshot> get() = flow
    fun snapshot(): HostedBackupSnapshot = state

    private fun HostedBackupRecord?.toSnapshot() = when {
        this == null -> HostedBackupSnapshot()
        confirmed -> HostedBackupSnapshot(HostedBackupStage.CONFIRMED, username, revision)
        else -> HostedBackupSnapshot(HostedBackupStage.PENDING, username)
    }

    /**
     * Asks NStack, signed, whether a durable hosted copy exists for this identity now. Used as the payment gate: a local flag
     * alone never opens it. A network problem is "not confirmed", never "confirmed".
     */
    suspend fun confirmedOnServer(): Boolean = gate.withLock {
        val record = store.load(origin.url, npub)
        if (record == null || !record.confirmed) return@withLock false
        try {
            // A signed /me alone proves little: it must also agree with the public ciphertext we proved earlier and not be older than it.
            val receipt = api.me() ?: return@withLock false
            if (receipt.username != record.username) return@withLock false
            val copy = api.fetch(record.username)
            copy.ncryptsec == record.ncryptsec && copy.revision == receipt.revision &&
                java.math.BigInteger(receipt.revision) >= java.math.BigInteger(record.revision ?: "1")
        } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** Creates, uploads and proves the hosted copy. Safe to repeat after any failure or relaunch. */
    suspend fun save(rawUsername: String, password: CharSequence, confirmation: CharSequence): HostedBackupSnapshot = gate.withLock {
        state = state.copy(failure = null, busy = true); flow.value = state
        try { doSave(rawUsername, password, confirmation) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(e) }
        finally { state = state.copy(busy = false); flow.value = state }
    }

    private suspend fun doSave(rawUsername: String, password: CharSequence, confirmation: CharSequence): HostedBackupSnapshot {
        val username = BackupUsername.normalize(rawUsername) ?: throw BackupFailure(BackupFailure.Kind.INVALID_USERNAME)
        BackupPassword.problem(password, confirmation, username)?.let { throw BackupFailure(BackupFailure.Kind.WEAK_PASSWORD).withProblem(it) }
        requireSigner(npub)
        var record = store.load(origin.url, npub)
        if (record?.confirmed == true && record.username == username) return confirmed(record)

        // An earlier attempt may have reached NStack whose answer was lost. Ask first; never guess.
        val existing = api.me()
        if (existing != null) {
            if (existing.username != username) throw BackupFailure(BackupFailure.Kind.ALREADY_BACKED_UP)
            val verified = verifyHosted(username, password)
            return confirm(record?.copy(username = username) ?: HostedBackupRecord(origin = origin.url, npub = npub, username = username, ncryptsec = verified.ncryptsec), verified)
        }

        // Nothing is hosted for this identity. Reuse a pending ciphertext only if this password opens it; otherwise make a fresh one.
        if (record != null && (record.username != username || !opensWith(record.ncryptsec, password))) record = null
        if (record == null) {
            val text = encrypt(password, logN)
            if (!BackupCiphertext.isWellFormed(text)) throw BackupFailure(BackupFailure.Kind.INVALID_BACKUP)
            record = HostedBackupRecord(origin = origin.url, npub = npub, username = username, ncryptsec = text, expectedRevision = "0")
        }
        requireSigner(npub)                               // identity re-checked after the encryption await, before anything is staged
        persist(record)                                   // ciphertext and CAS value on disk before the upload
        state = record.toSnapshot().copy(busy = true); flow.value = state

        val receipt = try { api.put(username, record.ncryptsec, record.expectedRevision) }
        catch (f: BackupFailure) {
            if (f.kind == BackupFailure.Kind.USERNAME_UNAVAILABLE || f.kind == BackupFailure.Kind.ALREADY_BACKED_UP || f.kind == BackupFailure.Kind.REVISION_CONFLICT) {
                // Possibly our own earlier write under another body: decide from what the server actually holds.
                val now = api.me()
                if (now != null && now.username == username) {
                    val verified = verifyHosted(username, password)
                    return confirm(record, verified)
                }
            }
            throw f
        }
        val verified = verifyHosted(username, password)
        // A later write by this identity elsewhere is fine; an older revision than the one just accepted is not.
        if (java.math.BigInteger(verified.revision) < java.math.BigInteger(receipt.revision)) throw BackupFailure(BackupFailure.Kind.VERIFY_FAILED)
        return confirm(record.copy(revision = receipt.revision), verified)
    }

    /** Reads the hosted copy back (signed, then public) and proves it decrypts, with this password, to this identity. */
    private suspend fun verifyHosted(username: String, password: CharSequence): HostedBackupCopy {
        val receipt = api.me()
        if (receipt == null || receipt.username != username) throw BackupFailure(BackupFailure.Kind.VERIFY_FAILED)
        val copy = api.fetch(username)
        if (copy.revision != receipt.revision) throw BackupFailure(BackupFailure.Kind.VERIFY_FAILED)
        if (!opensWith(copy.ncryptsec, password)) throw BackupFailure(BackupFailure.Kind.VERIFY_FAILED)
        return copy
    }

    /** scrypt runs off the caller's thread (never Compose main) and one at a time process-wide (see [Nip49]). */
    private suspend fun opensWith(ncryptsec: String, password: CharSequence): Boolean = withContext(Dispatchers.Default) {
        try {
            val decrypted = Nip49.decrypt(ncryptsec, password, BackupCiphertext.MAX_LOG_N)
            try { NostrKeys.publicKeyHex(decrypted.secret) == expectedHex } finally { decrypted.secret.fill(0) }
        } catch (e: Nip49Error) { false }
    }

    private suspend fun confirm(record: HostedBackupRecord, copy: HostedBackupCopy): HostedBackupSnapshot {
        requireSigner(npub)                               // an identity swapped during the awaits above confirms nothing
        val done = record.copy(username = copy.username, ncryptsec = copy.ncryptsec, expectedRevision = copy.revision, confirmed = true, revision = copy.revision)
        persist(done)                                     // only a durable confirmation is ever reported (record is keyed to this origin and identity)
        return confirmed(done)
    }

    private fun confirmed(record: HostedBackupRecord): HostedBackupSnapshot {
        state = record.toSnapshot().copy(failure = null); flow.value = state
        return state
    }

    private fun persist(record: HostedBackupRecord) {
        try { store.save(record) } catch (e: Exception) { throw BackupFailure(BackupFailure.Kind.PERSISTENCE) }
    }

    private fun fail(error: Exception): HostedBackupSnapshot {
        val failure = when (error) {
            is BackupFailure -> error
            is IdentityError -> BackupFailure(if (error == IdentityError.Locked) BackupFailure.Kind.IDENTITY_LOCKED else BackupFailure.Kind.IDENTITY_MISMATCH)
            is TowerError -> BackupFailure(BackupFailure.Kind.MALFORMED)
            is Nip49Error -> BackupFailure(BackupFailure.Kind.WRONG_PASSWORD)
            else -> BackupFailure(BackupFailure.Kind.NETWORK)
        }
        // Whatever failed, the stage is whatever is durably on disk: a confirmed flag is never invented here.
        state = store.load(origin.url, npub).toSnapshot().copy(failure = failure)
        flow.value = state
        return state
    }

    /** The opaque ciphertext of the backup in progress or confirmed, for saving to a file the person owns. Never a password or key. */
    fun savedCiphertext(): String? = store.load(origin.url, npub)?.ncryptsec

    fun gate(origin: IdentityOrigin): IdentityBackupGate = object : IdentityBackupGate {
        override val origin = origin
        override fun locallyConfirmed() = state.stage == HostedBackupStage.CONFIRMED
        override suspend fun confirmedOnServer() = this@HostedBackupController.confirmedOnServer()
    }

    /** Public origin check used before any backup request: the address must be an NStack. */
    suspend fun checkOrigin(): Boolean = try { publicOnly.descriptor(); true } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
}
