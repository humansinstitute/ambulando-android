package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.chat.ArtefactChatReference
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

data class DocumentVersion(val rowVersion: Int, val title: String, val bytes: ByteArray?, val contentType: String) {
    fun reference(id: String, historical: Boolean): ArtefactChatReference = ArtefactChatReference("doc", id, rowVersion.toString(), historical, Sha256Hex.of(requireNotNull(bytes) { "Document snapshot bytes are unavailable." }))
}
/** Existing canonical snapshot route, independently of edit-lease recovery copies. */
suspend fun TowerAPI.documentVersions(workspace: String, app: String, doc: String): List<DocumentVersion> =
    pages(TowerAPI.base(workspace) + "/docs/" + TowerAPI.uuid(doc) + "/versions", app, "versions").map { row ->
        require(row.string("workspace_id") == workspace && row.string("doc_id") == doc)
        val revision = row["row_version"]?.jsonPrimitive?.intOrNull ?: row["version"]?.jsonPrimitive?.intOrNull ?: error("Missing document version")
        require(revision > 0)
        val content = row.obj("content")
        DocumentVersion(revision, row.string("title") ?: error("Missing title"),
            if (content?.bool("missing") == true) null else content?.string("raw")?.toByteArray(), content?.string("content_type") ?: "text/markdown")
    }.sortedByDescending { it.rowVersion }
