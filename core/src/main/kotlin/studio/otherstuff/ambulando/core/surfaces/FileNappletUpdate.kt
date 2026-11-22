package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** User-confirmed, code-only upgrade of an exact known built-in. Never rewrites data or metadata. */
class FileNappletUpdate(
    private val api: TowerAPI, private val workspace: String, private val app: String,
    private val viewer: String, private val codeId: String, private val base: String,
    private val metadata: JsonObject, private val manifest: JsonObject, private val dataId: String,
    private val dataWritable: Boolean, private val isCurrent: () -> Boolean = { true },
) {
    companion object {
        const val ORIGINAL_BYTES = 14824
        const val ORIGINAL_SHA256 = "192779e50335a83d90076fb17954059e466ccfecea25c5eb5b14d68e8dfa91cd"
        fun recognized(html: String): Boolean = html.toByteArray().let { it.size == ORIGINAL_BYTES && Sha256Hex.of(it) == ORIGINAL_SHA256 }
    }
    private var attempted = false
    private suspend fun current(): Pair<FileVersionRecord, ByteArray> {
        val reply = Json.parseToJsonElement(api.client.request(
            path = TowerAPI.base(workspace) + "/files/" + TowerAPI.uuid(codeId) + "/versions",
            query = listOf("limit" to "1"), appNpub = app,
        ).toString(Charsets.UTF_8)).jsonObject
        val version = FileVersionRecord.parse(reply.getValue("versions").jsonArray.single().jsonObject)
        require(version.fileId == codeId && version.id == reply.string("current_version_id"))
        require(version.contentType?.substringBefore(';') == "text/html")
        require(version.sizeBytes in 1..FileNapplet.MAX_BYTES.toLong())
        val bytes = api.fileVersionBytes(workspace, app, codeId, version)
        FileNapplet.verifiedHTML(bytes, bytes.toString(Charsets.UTF_8), requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
        check(isCurrent())
        return version to bytes
    }
    suspend fun authorize() {
        check(isCurrent())
        val code = api.artefactFile(workspace, app, codeId)
        require(code.raw.bool("can_write") == true && code.raw["metadata"] == metadata)
        require(code.raw.string("current_version_id") == base && FileNapplet.binding(metadata, manifest) == dataId)
        val access = FileNappletTransport(api, workspace, app, viewer, codeId, base, manifest, dataId).authorize()
        require(access == dataWritable)
        val (version, bytes) = current()
        require(version.id == base && bytes.size == ORIGINAL_BYTES && Sha256Hex.of(bytes) == ORIGINAL_SHA256)
    }
    suspend fun update(replacement: String): String {
        check(isCurrent())
        check(!attempted) { "Close and reopen before checking another update." }
        val bytes = replacement.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= FileNapplet.MAX_BYTES && !recognized(replacement))
        authorize()
        attempted = true // Upload/publication failures are uncertain: no automatic or blind retry.
        val objectId = api.store(workspace, app, bytes, "text/html", "Daily tracker.html", null) { }
        currentCoroutineContext().ensureActive()
        authorize()
        val outcome = api.addFileVersion(workspace, app, codeId, base, objectId, UUID.randomUUID().toString())
        require(outcome is VersionOutcome.Saved && outcome.versionId != null && outcome.versionId != base) { "Update was not acknowledged. Close and reopen to check the current app." }
        val code = api.artefactFile(workspace, app, codeId)
        require(code.raw["metadata"] == metadata && code.raw.bool("can_write") == true)
        require(code.raw.string("current_version_id") == outcome.versionId)
        require(FileNappletTransport(api, workspace, app, viewer, codeId, outcome.versionId, manifest, dataId).authorize() == dataWritable)
        val (version, saved) = current()
        require(version.id == outcome.versionId && version.storageObjectId == objectId && saved.contentEquals(bytes))
        check(isCurrent())
        return version.id
    }
}
