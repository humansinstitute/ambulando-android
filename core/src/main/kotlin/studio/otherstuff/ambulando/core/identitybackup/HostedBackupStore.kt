package studio.otherstuff.ambulando.core.identitybackup

import kotlinx.serialization.Serializable
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.io.File

/**
 * Durable record of one hosted backup for exactly one (NStack origin, identity). It holds only the opaque ncryptsec (never a
 * password or key) so an interrupted upload replays the identical body. [confirmed] is written only after the hosted copy
 * has been read back and proven to decrypt to this identity; until then the copy does not count.
 */
@Serializable
data class HostedBackupRecord(
    val version: Int = 1,
    val origin: String,
    val npub: String,
    val username: String,
    val ncryptsec: String,
    val expectedRevision: String = "0",
    val confirmed: Boolean = false,
    val revision: String? = null,
) {
    override fun toString() = "HostedBackupRecord(origin=$origin, username=$username, confirmed=$confirmed)"
}

interface HostedBackupStore {
    fun load(origin: String, npub: String): HostedBackupRecord?
    /** Throws when the record is not durably written: nothing further may then be sent. */
    fun save(record: HostedBackupRecord)
    fun clear(origin: String, npub: String)
}

class FileHostedBackupStore(private val directory: File) : HostedBackupStore {
    private fun file(origin: String, npub: String) = File(directory, Sha256Hex.of("hosted-backup-v1\n$origin\n$npub") + ".json")

    override fun load(origin: String, npub: String): HostedBackupRecord? {
        val bytes = runCatching { file(origin, npub).readBytes() }.getOrNull() ?: return null
        val record = runCatching { WireJson.decodeFromString(HostedBackupRecord.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        return record.takeIf { it.version == 1 && it.origin == origin && it.npub == npub }
    }

    override fun save(record: HostedBackupRecord) {
        AtomicFiles.write(file(record.origin, record.npub), WireJson.encodeToString(HostedBackupRecord.serializer(), record).toByteArray(Charsets.UTF_8))
    }

    override fun clear(origin: String, npub: String) { file(origin, npub).delete() }
}

class MemoryHostedBackupStore : HostedBackupStore {
    private val records = HashMap<String, HostedBackupRecord>()
    private fun key(o: String, n: String) = "$o\n$n"
    @Synchronized override fun load(origin: String, npub: String) = records[key(origin, npub)]
    @Synchronized override fun save(record: HostedBackupRecord) { records[key(record.origin, record.npub)] = record }
    @Synchronized override fun clear(origin: String, npub: String) { records.remove(key(origin, npub)) }
}

@Serializable
private data class OriginMark(val version: Int = 1, val phase: String, val npub: String? = null)

/**
 * Durable client-side truth about whether this device's identity was generated here ("new") or brought in ("existing"); NStack
 * cannot attest it. Written BEFORE the key is created so a crash between creation and bookkeeping can still be recognised: a
 * "creating" mark plus a vault that now exists means this app created it. Anything unrecorded is existing, which is never gated.
 */
interface IdentityOriginStore {
    /** Throws when not durable; the key must not be generated then. */
    fun beginNew()
    fun completeNew(npub: String)
    /** The key was not created (or was imported instead): forget the pending mark. */
    fun abandon()
    /** [vaultNpub] is the identity currently on the device, used to settle a "creating" mark left by a crash. */
    fun originOf(npub: String, vaultNpub: String?): IdentityOrigin
    fun clear()
}

class FileIdentityOriginStore(private val file: File) : IdentityOriginStore {
    private sealed interface Read { data object Absent : Read; data object Unreadable : Read; data class Mark(val mark: OriginMark) : Read }

    /** Absent is a legacy/imported identity; present-but-unreadable (corrupt, denied, unknown version) is NOT the same thing. */
    private fun read(): Read {
        if (!file.exists()) return Read.Absent
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return Read.Unreadable
        val mark = runCatching { WireJson.decodeFromString(OriginMark.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()?.takeIf { it.version == 1 }
        return if (mark == null) Read.Unreadable else Read.Mark(mark)
    }

    private fun write(mark: OriginMark) { AtomicFiles.write(file, WireJson.encodeToString(OriginMark.serializer(), mark).toByteArray(Charsets.UTF_8)) }

    override fun beginNew() = write(OriginMark(phase = "creating"))
    override fun completeNew(npub: String) = write(OriginMark(phase = "new", npub = npub))
    override fun abandon() { file.delete() }
    override fun clear() { file.delete() }

    override fun originOf(npub: String, vaultNpub: String?): IdentityOrigin {
        val mark = when (val r = read()) { Read.Absent -> return IdentityOrigin.EXISTING; Read.Unreadable -> return IdentityOrigin.UNKNOWN; is Read.Mark -> r.mark }
        return when {
            mark.phase == "new" && mark.npub == npub -> IdentityOrigin.NEW
            mark.phase == "creating" && vaultNpub == npub -> {
                runCatching { completeNew(npub) }
                IdentityOrigin.NEW
            }
            else -> IdentityOrigin.EXISTING
        }
    }
}

class MemoryIdentityOriginStore : IdentityOriginStore {
    private var mark: OriginMark? = null
    var failWrites = false
    var unreadable = false
    @Synchronized override fun beginNew() { if (failWrites) throw java.io.IOException("disk full"); mark = OriginMark(phase = "creating") }
    @Synchronized override fun completeNew(npub: String) { if (failWrites) throw java.io.IOException("disk full"); mark = OriginMark(phase = "new", npub = npub) }
    @Synchronized override fun abandon() { mark = null }
    @Synchronized override fun clear() { mark = null }
    @Synchronized override fun originOf(npub: String, vaultNpub: String?): IdentityOrigin {
        if (unreadable) return IdentityOrigin.UNKNOWN
        val m = mark ?: return IdentityOrigin.EXISTING
        return when {
            m.phase == "new" && m.npub == npub -> IdentityOrigin.NEW
            m.phase == "creating" && vaultNpub == npub -> { mark = OriginMark(phase = "new", npub = npub); IdentityOrigin.NEW }
            else -> IdentityOrigin.EXISTING
        }
    }
}
