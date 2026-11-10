package studio.otherstuff.ambulando.app.push

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Private device state. Never exported, backed up, logged, or placed in diagnostics. */
@Serializable
data class PushRegistration(
    val instance: String = UUID.randomUUID().toString(), val connectionId: String, val signerNpub: String,
    val workspaceId: String, val appNpub: String, val origin: String,
    val enabled: Boolean = true, val endpoint: String? = null, val p256dh: String? = null, val auth: String? = null,
    val vapid: String? = null, val leaseExpiresAt: String? = null,
    val message: String = "Waiting for distributor", val ready: Boolean = false,
    val seen: Map<String, Long> = emptyMap(),
)

class PushState(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push/registrations.json").also { it.parentFile!!.mkdirs() })
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun all(): List<PushRegistration> = runCatching {
        json.decodeFromString<List<PushRegistration>>(file.readFully().decodeToString())
    }.getOrDefault(emptyList())
    @Synchronized fun update(instance: String, transform: (PushRegistration) -> PushRegistration) = write(all().map { if (it.instance == instance) transform(it) else it })
    @Synchronized fun add(row: PushRegistration) = write(all().filterNot { it.instance == row.instance } + row)
    @Synchronized fun remove(instance: String) = write(all().filterNot { it.instance == instance })
    private fun write(rows: List<PushRegistration>) {
        val out = file.startWrite()
        try { out.write(json.encodeToString(rows).toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}
