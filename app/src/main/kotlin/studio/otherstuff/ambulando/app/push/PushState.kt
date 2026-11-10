package studio.otherstuff.ambulando.app.push

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Private device state. Never exported, backed up, logged, or placed in diagnostics. */
@Serializable
data class PushRegistration(
    val instance: String = UUID.randomUUID().toString(), val connectionId: String, val signerNpub: String,
    val workspaceId: String, val appNpub: String, val origin: String,
    val installationId: String = instance, val towerNpub: String? = null, val transport: String = "fcm", val leaseExpiresAt: String? = null,
    val enabled: Boolean = true, val token: String? = null, val projectId: String? = null,
    val message: String = "Unlock to set up push", val ready: Boolean = false,
    val seen: Map<String, Long> = emptyMap(),
)

class PushState(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push/registrations.json").also { it.parentFile!!.mkdirs() })
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized fun all(): List<PushRegistration> = runCatching {
        val text = file.readFully().decodeToString()
        val raw = json.parseToJsonElement(text).jsonArray
        json.decodeFromString<List<PushRegistration>>(text).mapIndexed { index, row ->
            if (raw[index].jsonObject["transport"]?.jsonPrimitive?.content != "fcm")
                row.copy(enabled = false, ready = false, message = "FCM setup required for this workspace.", token = null)
            else row
        }
    }.getOrDefault(emptyList())
    /** One FCM transport per saved Tower/actor, with independent workspace enrollment and local routing rows. */
    @Synchronized fun enrollment(connectionId: String, signerNpub: String, workspaceId: String, appNpub: String, origin: String, towerNpub: String?): PushRegistration {
        val rows = all()
        val old = rows.firstOrNull { it.connectionId == connectionId && it.signerNpub == signerNpub && it.workspaceId == workspaceId }
        if (old != null && old.enabled) return old.copy(enabled = true, ready = false, towerNpub = towerNpub)
        val shared = rows.firstOrNull { it.origin == origin && it.signerNpub == signerNpub && it.transport == "fcm" }
        val fresh = PushRegistration(connectionId = connectionId, signerNpub = signerNpub, workspaceId = workspaceId, appNpub = appNpub, origin = origin, towerNpub = towerNpub)
        return if (shared != null) fresh.copy(installationId = shared.installationId) else fresh
    }
    fun matching(hint: studio.otherstuff.ambulando.core.notifications.PushHint, projectId: String?): PushRegistration? =
        all().firstOrNull { it.enabled && it.projectId != null && it.projectId == projectId && it.installationId == hint.installationId &&
            it.workspaceId == hint.workspaceId && it.origin == hint.origin && hint.towerNpub != null && it.towerNpub == hint.towerNpub }
    @Synchronized fun update(instance: String, transform: (PushRegistration) -> PushRegistration) = write(all().map { if (it.instance == instance) transform(it) else it })
    @Synchronized fun add(row: PushRegistration) = write(all().filterNot { it.instance == row.instance } + row)
    @Synchronized fun remove(instance: String) = write(all().filterNot { it.instance == instance })
    private fun write(rows: List<PushRegistration>) {
        val out = file.startWrite()
        try { out.write(json.encodeToString(rows).toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}
