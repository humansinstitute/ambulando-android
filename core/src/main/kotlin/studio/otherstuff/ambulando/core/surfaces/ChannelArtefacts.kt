package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.util.Base64

/** Built-in formats only. Metadata selects a compatible editor; it never installs executable code. */
enum class ArtefactType(val wire: String, val label: String, val mime: String, val extension: String) {
    WHITEBOARD("excalidraw", "Whiteboard", "application/vnd.excalidraw+json", "excalidraw"),
    HTML("html", "HTML", "text/html", "html"),
    SYSTEM_FLOW("system-flow", "System Flow", "application/vnd.ambulando.system-flow+json", "flow.json");

    fun metadata(): JsonObject = buildJsonObject {
        put("artefact", buildJsonObject {
            put("schema_version", 1); put("type", wire); put("format_version", 1); put("editor_id", "builtin.$wire")
        })
    }
    fun initialContent(): String = when (this) {
        SYSTEM_FLOW -> ArtefactTemplates.source(wire, "system", "default", "System Flow")
        WHITEBOARD -> """{"type":"excalidraw","version":2,"source":"ambulando","elements":[],"appState":{},"files":{}}"""
        HTML -> "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body><h1>New artefact</h1><p>Edit the source to develop your idea.</p></body></html>"
    }
    companion object {
        fun of(file: FileRecord): ArtefactType? {
            val meta = file.raw.obj("metadata")?.obj("artefact")
            if (meta != null) {
                if (flexibleInt(meta["schema_version"]) != 1 || flexibleInt(meta["format_version"]) != 1) return null
                return entries.firstOrNull { it.wire == meta.string("type") && meta.string("editor_id") == "builtin.${it.wire}" && (it != SYSTEM_FLOW || file.contentType?.substringBefore(';')?.lowercase() == it.mime) }
            }
            val mime = file.contentType?.substringBefore(';')?.lowercase()
            return when {
                mime == "text/html" || mime == "application/xhtml+xml" || file.raw.obj("metadata")?.bool("html_artifact") == true || file.displayName.endsWith(".html", true) || file.displayName.endsWith(".htm", true) -> HTML
                mime == "application/vnd.excalidraw+json" || file.raw.obj("metadata")?.string("kind") == "excalidraw_board" || file.displayName.endsWith(".excalidraw", true) -> WHITEBOARD
                else -> null
            }
        }
    }
}

suspend fun TowerAPI.artefactFile(workspaceId: String, appNpub: String, fileId: String): FileRecord {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId), appNpub = appNpub), "artefact")
    val file = o.obj("file")?.let(FileRecord::parse) ?: throw TowerError.Malformed("artefact")
    if (file.workspaceId != workspaceId || file.id != fileId.lowercase()) throw TowerError.Malformed("artefact")
    return file
}

suspend fun TowerAPI.artefactHTML(workspaceId: String, appNpub: String, fileId: String, versionId: String? = null): String =
    artefactHTMLPayload(workspaceId, appNpub, fileId, versionId).html
