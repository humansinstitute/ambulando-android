package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*

/** Ambulando data only: references never confer source access or select another device's revision. */
data class ThreadArtefactMember(val kind: String, val id: String) {
    init { require(kind in setOf("doc", "file") && isUUID(id) && id == id.lowercase()) }
    fun json() = buildJsonObject { put("kind", kind); put("id", id) }
}
object ThreadArtefactMembership {
    const val KEY = "ambulando_thread_artefacts"
    const val LIMIT = 32
    /** null means never initialized. An initialized empty list must survive the last removal. */
    fun read(metadata: JsonObject): List<ThreadArtefactMember>? {
        val value = metadata[KEY] ?: return null
        val o = value as? JsonObject ?: error("Thread attachment format is unavailable. Update the app before changing attachments.")
        require(o.keys == setOf("version", "items") && o["version"] == JsonPrimitive(1)) { "Unsupported thread attachment version. Update the app." }
        val items = o["items"] as? JsonArray ?: error("Invalid thread attachments; nothing was changed.")
        require(items.size <= LIMIT) { "Thread attachment limit exceeded; nothing was changed." }
        return items.map { item ->
            val r = item.jsonObject
            require(r.keys == setOf("kind", "id"))
            ThreadArtefactMember(r.getValue("kind").jsonPrimitive.content, r.getValue("id").jsonPrimitive.content.lowercase())
        }.distinct()
    }
    fun metadata(base: JsonObject, members: List<ThreadArtefactMember>): JsonObject {
        val unique = members.distinct()
        require(unique.size <= LIMIT) { "A thread supports up to 32 attachments. Remove one before adding another." }
        return JsonObject(base + (KEY to buildJsonObject { put("version", 1); put("items", JsonArray(unique.map { it.json() })) }))
    }
}

/** Serializes this client's intents; server CAS reconciles independent clients. No cached whole-list write. */
class ThreadArtefactMembershipSync(private val transport: Transport) {
    data class Row(val version: Long, val metadata: JsonObject)
    class Uncertain(val observed: Row, cause: Exception) : Exception("The server's latest attachments differ from this action. They were refreshed; retry explicitly if still wanted.", cause)
    interface Transport {
        suspend fun read(): Row
        suspend fun write(base: Row, metadata: JsonObject): Row
        suspend fun verify(member: ThreadArtefactMember)
    }
    private val mutex = Mutex()
    suspend fun refresh(): Row = mutex.withLock { transport.read().also { ThreadArtefactMembership.read(it.metadata) } }
    suspend fun add(member: ThreadArtefactMember): Row = change(member, true)
    suspend fun remove(member: ThreadArtefactMember): Row = change(member, false)
    private suspend fun change(member: ThreadArtefactMember, add: Boolean): Row = mutex.withLock {
        if (add) transport.verify(member)
        repeat(4) {
            val base = transport.read()
            val current = ThreadArtefactMembership.read(base.metadata).orEmpty()
            if ((member in current) == add && (add || ThreadArtefactMembership.KEY in base.metadata)) return@withLock base
            val desired = if (add) current + member else current - member
            try {
                return@withLock transport.write(base, ThreadArtefactMembership.metadata(base.metadata, desired)).also { saved ->
                    check((member in ThreadArtefactMembership.read(saved.metadata).orEmpty()) == add) { "Attachment change was not acknowledged. Refresh and retry." }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is TowerError.Http && e.status == 409) return@repeat
                if (e is TowerError.Http && e.status in setOf(400, 401, 403, 404)) throw e
                // A dropped response is not a failed commit. Read authority before offering a retry.
                val observed = transport.read()
                if ((add || ThreadArtefactMembership.KEY in observed.metadata) && (member in ThreadArtefactMembership.read(observed.metadata).orEmpty()) == add) return@withLock observed
                throw Uncertain(observed, e)
            }
        }
        error("Thread changed repeatedly. Refresh and retry; existing attachments are preserved.")
    }
    /** Losing initialization never reapplies legacy cache over another client's authoritative membership. */
    suspend fun migrate(legacy: List<ThreadArtefactMember>): Row = mutex.withLock {
        val base = transport.read()
        if (ThreadArtefactMembership.read(base.metadata) != null || legacy.isEmpty()) return@withLock base
        val verified = legacy.distinct().take(ThreadArtefactMembership.LIMIT).filter { member ->
            try { transport.verify(member); true } catch (e: CancellationException) { throw e } catch (e: TowerError.Http) { if (e.status in setOf(403, 404)) false else throw e }
        }
        if (verified.isEmpty()) return@withLock base
        try { transport.write(base, ThreadArtefactMembership.metadata(base.metadata, verified)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val current = transport.read()
            if (ThreadArtefactMembership.read(current.metadata) != null) current else throw e
        }
    }
}

/** Generic thread PATCH with the actual metadata base; source ACLs remain separate. */
suspend fun TowerAPI.updateThreadMetadata(workspace: String, app: String, threadId: String, base: ThreadArtefactMembershipSync.Row, metadata: JsonObject): ThreadRecord {
    require(base.version in 1..Int.MAX_VALUE.toLong())
    val bytes = client.request(method = "PATCH", path = TowerAPI.base(workspace) + "/threads/" + TowerAPI.uuid(threadId), appNpub = app,
        jsonBody = buildJsonObject { put("row_version", base.version); put("metadata", metadata) }.toString().toByteArray())
    val record = ThreadRecord.parse(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("thread").jsonObject)
    require(record.workspaceId == workspace && record.id == threadId && record.rowVersion > base.version)
    return record
}
