package studio.otherstuff.ambulando.core.surfaces

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Boundary-private recovery source. A started atomic write completes even when its editor closes. */
class HtmlDraftStore(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    data class Draft(val base: String, val source: String, val accepted: Boolean = false, val savedVersionId: String? = null, val mutationId: String = java.util.UUID.randomUUID().toString(), val uploadedObjectId: String? = null)
    private class Owner { val mutex = Mutex(); val generation = java.util.concurrent.atomic.AtomicLong() }
    private companion object { val owners = java.util.concurrent.ConcurrentHashMap<String,Owner>() }
    private val owner = owners.computeIfAbsent(file.absolutePath) { Owner() }
    private val mutex get() = owner.mutex
    fun ticket(): Long = owner.generation.get()
    suspend fun load(): Draft? = mutex.withLock { withContext(io) {
        if (!file.exists()) null else Json.parseToJsonElement(file.readText()).jsonObject.let {
            Draft(it.getValue("base_version_id").jsonPrimitive.content, it.getValue("source").jsonPrimitive.content,
                it["accepted"]?.jsonPrimitive?.booleanOrNull == true, it["saved_version_id"]?.jsonPrimitive?.contentOrNull,
                it["mutation_id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                it["uploaded_object_id"]?.jsonPrimitive?.contentOrNull)
        }
    } }
    suspend fun save(draft: Draft, ticket: Long = ticket()) = mutex.withLock {
      if (ticket != owner.generation.get()) return@withLock
      withContext(NonCancellable + io) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        pending.writeText(buildJsonObject { put("base_version_id", draft.base); put("source", draft.source); put("accepted", draft.accepted); draft.savedVersionId?.let { put("saved_version_id", it) }; put("mutation_id", draft.mutationId); draft.uploadedObjectId?.let { put("uploaded_object_id", it) } }.toString())
        check(pending.renameTo(file)) { "Could not preserve your HTML source. Keep the editor open and copy your changes." }
    } }
    suspend fun checkpoint(draft: Draft) {
        val ticket = owner.generation.incrementAndGet()
        save(draft, ticket)
    }
    suspend fun clear() {
        // Fence even accepted UI edits whose view-model jobs have not acquired the write lock yet.
        owner.generation.incrementAndGet()
        mutex.withLock { withContext(NonCancellable + io) {
            check(!file.exists() || file.delete()) { "Saved, but could not remove the recovery draft." }
        } }
    }
}
