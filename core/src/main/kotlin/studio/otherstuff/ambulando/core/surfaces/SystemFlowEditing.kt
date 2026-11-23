package studio.otherstuff.ambulando.core.surfaces

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*

/** Serialises edits and persists each accepted change before exposing it. Leaving never publishes or drops a draft. */
class SystemFlowController(private val transport: Transport, private val draft: File) {
    interface Transport {
        suspend fun head(): Pair<Boolean, FileVersionList>
        suspend fun bytes(version: FileVersionRecord): ByteArray
        suspend fun save(base: String, bytes: ByteArray): VersionOutcome
    }
    data class State(val flow: JsonObject? = null, val base: String? = null, val versions: List<FileVersionRecord> = emptyList(),
        val viewing: String? = null, val canWrite: Boolean = false, val dirty: Boolean = false, val message: String = "", val busy: Boolean = false)
    private val mutable = MutableStateFlow(State()); val state = mutable.asStateFlow(); private val mutex = Mutex()
    private suspend fun guarded(network: Boolean = true, block: suspend () -> Unit) = mutex.withLock {
        if (network) mutable.value = mutable.value.copy(busy = true)
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(message = e.message ?: "System Flow unavailable. Your draft is kept.") }
        finally { if (network) mutable.value = mutable.value.copy(busy = false) }
    }
    suspend fun open(versionId: String? = null) = guarded {
        mutable.value = mutable.value.copy(canWrite = false)
        val (write, list) = transport.head()
        val version = list.versions.first { it.id == (versionId ?: list.currentVersionId) }
        val saved = withContext(Dispatchers.IO) { SystemFlow.parse(transport.bytes(version)) }
        val restored = if (versionId == null) withContext(Dispatchers.IO) {
            if (draft.isFile) Json.parseToJsonElement(draft.readText()).jsonObject else null
        } else null
        val flow = restored?.get("flow")?.let { SystemFlow.validate(it) } ?: saved
        val base = restored?.get("base")?.jsonPrimitive?.content ?: list.currentVersionId
        mutable.value = State(flow, base, list.versions, versionId, write, restored != null,
            if (restored != null) "Restored your draft. Save checks its original base version." else if (!write || versionId != null) "Read-only" else "Saved version ${version.versionNumber}", busy = true)
    }
    suspend fun edit(transform: (JsonObject) -> JsonObject) = guarded(network = false) {
        val s = mutable.value
        check(s.canWrite && s.viewing == null) { "Read-only. Your source was not changed." }
        val next = SystemFlow.validate(transform(requireNotNull(s.flow)))
        withContext(Dispatchers.IO) {
            draft.parentFile?.mkdirs()
            val pending = File(draft.parentFile, draft.name + ".pending")
            pending.writeText(buildJsonObject { put("base", requireNotNull(s.base)); put("flow", next) }.toString())
            check(pending.renameTo(draft)) { "Could not preserve the draft. This edit was not applied." }
        }
        mutable.value = s.copy(flow = next, dirty = true, message = "Unsaved · draft kept on this device")
    }
    suspend fun save() = guarded {
        val s = mutable.value
        check(s.dirty && s.canWrite && s.viewing == null)
        val (canWrite, _) = transport.head()
        if (!canWrite) { mutable.value = s.copy(canWrite = false, message = "Read-only. Your draft is kept."); return@guarded }
        val bytes = SystemFlow.validate(requireNotNull(s.flow)).toString().toByteArray()
        when (val result = transport.save(requireNotNull(s.base), bytes)) {
            is VersionOutcome.Saved -> {
                val (_, list) = transport.head()
                val version = list.versions.first { it.id == result.versionId }
                val readback = SystemFlow.parse(transport.bytes(version))
                check(readback == s.flow) { "Save readback did not match. Your draft is kept." }
                withContext(Dispatchers.IO) { check(!draft.exists() || draft.delete()) }
                mutable.value = s.copy(flow = readback, base = version.id, versions = list.versions, dirty = false, message = "Saved version ${version.versionNumber}")
            }
            is VersionOutcome.Conflict -> mutable.value = s.copy(message = "A newer version was saved elsewhere. Your draft and original base are kept; inspect versions or explicitly discard.")
            is VersionOutcome.Denied -> mutable.value = s.copy(canWrite = false, message = "Permission denied. Your draft is kept.")
            is VersionOutcome.Retryable -> mutable.value = s.copy(message = "Save failed. Your draft is kept; retry when connected.")
        }
    }
    suspend fun discard() = guarded {
        withContext(Dispatchers.IO) { check(!draft.exists() || draft.delete()) }
        mutable.value = mutable.value.copy(dirty = false, message = "Draft discarded. Reload latest.")
    }
}
