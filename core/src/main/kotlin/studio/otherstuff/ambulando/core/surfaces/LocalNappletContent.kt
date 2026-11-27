package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Verified content uses the existing private workspace store and its retirement lifecycle. No launcher catalogue. */
class LocalNappletContent(private val store: LocalStore, private val workspace: WorkspaceRecord, private val owner: String, private val current: () -> Boolean) {
    private fun key(file: String, part: String) = "napplet.content.v1:$owner:${workspace.appNpub}:${workspace.id}:$file:$part"
    suspend fun check(file: String) = store.reader.withArtefactAccess(workspace, file, current) {
        val known = store.reader.file(workspace.id, file)
        require(known == null || !known.archived && known.raw.string("record_state") != "deleted" && known.raw.string("deleted_at") == null)
    }
    suspend fun get(file: String, part: String): JsonObject? {
        return runCatching {
            check(file)
            val envelope = Json.parseToJsonElement(store.reader.state(key(file, part)) ?: return null).jsonObject
            val value = envelope.getValue("value").jsonObject
            require(envelope.string("sha256") == Sha256Hex.of(value.toString().toByteArray()))
            check(file); value
        }.onFailure { if(it is CancellationException) throw it }.getOrNull()
    }
    suspend fun put(file: String, part: String, value: JsonObject) {
        check(file)
        store.writer.setState(key(file, part), buildJsonObject {
            put("value", value); put("sha256", Sha256Hex.of(value.toString().toByteArray()))
        }.toString())
        check(file)
    }
    suspend fun forget(file: String, part: String) = store.writer.setState(key(file, part), null)
    /** An old mounted host cannot evict a newer verified code snapshot. */
    suspend fun forgetCode(file: String, expectedVersion: String) = store.writer.write {
        val cacheKey=key(file,"code")
        val raw=db.first("SELECT value FROM app_state WHERE key = ?",cacheKey) {it.text(0)} ?: return@write
        val version=runCatching { Json.parseToJsonElement(raw).jsonObject.getValue("value").jsonObject.getValue("version").jsonObject.string("id") }.getOrNull()
        if(version==expectedVersion) db.run("DELETE FROM app_state WHERE key = ? AND value = ?",cacheKey,raw)
    }
    suspend fun code(file: String): Code? = get(file, "code")?.let { o -> runCatching {
        val record = FileRecord.parse(o.getValue("file").jsonObject)
        val version = FileVersionRecord.parse(o.getValue("version").jsonObject)
        val html = o.getValue("html").jsonPrimitive.content
        require(record.id == file && record.workspaceId == workspace.id && version.fileId == file && record.raw.string("current_version_id") == version.id)
        val known = store.reader.file(workspace.id, file)
        require(known == null || known.raw.string("current_version_id") == version.id && known.raw["metadata"] == record.raw["metadata"])
        FileNapplet.verifiedHTML(html.toByteArray(), html, requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
        Code(record, version, html)
    }.onFailure { if(it is CancellationException) throw it }.getOrNull() }
    suspend fun saveCode(file: FileRecord, version: FileVersionRecord, html: String) {
        FileNapplet.verifiedHTML(html.toByteArray(), html, requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
        check(file.id)
        val value=buildJsonObject { put("file", file.raw); put("version", buildJsonObject {
            put("id", version.id); put("file_id", version.fileId); put("version_number", version.versionNumber)
            put("storage_object_id", version.storageObjectId); put("size_bytes", version.sizeBytes?.let(::JsonPrimitive) ?: JsonNull)
            put("content_type", version.contentType); put("sha256_hex", version.sha256); put("created_at", version.createdAt)
        }); put("html", html) }
        val encoded=buildJsonObject {put("value",value);put("sha256",Sha256Hex.of(value.toString().toByteArray()))}.toString()
        store.writer.write {
            check(current())
            val known=db.first("SELECT raw,access_removed FROM files WHERE workspace_id = ? AND id = ?",workspace.id,file.id) { it.text(0)!! to it.long(1) }
            if(known!=null) {
                val metadata=Json.parseToJsonElement(known.first).jsonObject
                if(known.second!=0L || metadata.string("current_version_id")!=version.id || metadata["metadata"]!=file.raw["metadata"]) return@write
            }
            db.run("INSERT INTO app_state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key(file.id,"code"),encoded)
        }
    }
    data class Code(val file: FileRecord, val version: FileVersionRecord, val html: String)
}

/** Only transport outages retain a running cached frame. Denials, changed code and malformed authority fail closed. */
fun transientNappletFailure(e: Exception): Boolean = e is java.io.IOException || e is kotlinx.coroutines.TimeoutCancellationException || e is TowerError.Http && (e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500)

/** Stable local binding includes pinned backend/service identity, independent of JSON object ordering. */
fun localContentOwner(connection: ConnectionRecord,workspace: WorkspaceRecord): String = connection.id+":"+(connection.towerNpub ?: "")+":"+Sha256Hex.of(sortedJson(workspace.raw.obj("identity") ?: JsonObject(emptyMap())).toString().toByteArray())
