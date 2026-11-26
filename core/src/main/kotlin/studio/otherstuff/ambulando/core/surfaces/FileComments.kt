package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.Base64
import java.util.UUID

data class HtmlArtefactVersion(
    val id: String,
    val versionNumber: Int,
    val createdAt: String,
    val createdByActorNpub: String?,
    val contentType: String?,
    val sizeBytes: Long?,
)

data class HtmlArtefactPayload(
    val fileId: String,
    val versionId: String,
    val versionNumber: Int,
    val currentVersionId: String?,
    val currentVersionNumber: Int?,
    val digest: String,
    val html: String,
    val versions: List<HtmlArtefactVersion>,
    val manifestError: String?,
)

data class HtmlCommentAnchor(
    val kind: String = "html_element",
    val elementId: String? = null,
    val selector: String? = null,
    val domPath: List<Int> = emptyList(),
    val tagName: String? = null,
    val textQuote: String? = null,
    val textPrefix: String? = null,
    val textSuffix: String? = null,
    val attrFingerprint: String? = null,
    val fileVersionId: String? = null,
    val fileVersionNumber: Int? = null,
    val versionDigest: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind)
        elementId?.takeIf { it.isNotBlank() }?.let { put("element_id", it) }
        selector?.takeIf { it.isNotBlank() }?.let { put("selector", it) }
        if (domPath.isNotEmpty()) put("dom_path", buildJsonArray { domPath.forEach { add(it) } })
        tagName?.takeIf { it.isNotBlank() }?.let { put("tag_name", it) }
        textQuote?.takeIf { it.isNotBlank() }?.let { put("text_quote", it) }
        textPrefix?.takeIf { it.isNotBlank() }?.let { put("text_prefix", it) }
        textSuffix?.takeIf { it.isNotBlank() }?.let { put("text_suffix", it) }
        attrFingerprint?.takeIf { it.isNotBlank() }?.let { put("attr_fingerprint", it) }
        fileVersionId?.takeIf { it.isNotBlank() }?.let { put("file_version_id", it) }
        fileVersionNumber?.let { put("file_version_number", it) }
        versionDigest?.takeIf { it.isNotBlank() }?.let { put("version_digest", it) }
    }

    fun draftKey(versionId: String): String =
        "root:$versionId:${elementId ?: selector ?: attrFingerprint ?: "doc"}"

    fun targetSummary(): String {
        val quote = textQuote?.trim()?.take(80)
        val tag = tagName?.lowercase()
        return when {
            !quote.isNullOrBlank() && !tag.isNullOrBlank() -> "<$tag> “$quote”"
            !quote.isNullOrBlank() -> "“$quote”"
            !attrFingerprint.isNullOrBlank() && !tag.isNullOrBlank() -> "<$tag> (${attrFingerprint.take(40)})"
            !elementId.isNullOrBlank() -> "#$elementId"
            !selector.isNullOrBlank() -> selector.take(60)
            !tag.isNullOrBlank() -> "<$tag>"
            else -> "Document"
        }
    }

    companion object {
        fun parse(o: JsonObject?): HtmlCommentAnchor? {
            if (o == null || o.isEmpty()) return null
            val kind = o.string("kind")?.takeIf { it == "html_element" || it == "document" } ?: "html_element"
            val domPath = (o["dom_path"] as? JsonArray)
                ?.take(64)
                ?.mapNotNull { flexibleInt(it)?.takeIf { idx -> idx in 0..100_000 } }
                ?: emptyList()
            return HtmlCommentAnchor(
                kind = kind,
                elementId = o.string("element_id")?.trim()?.take(256)?.takeIf { it.isNotEmpty() },
                selector = o.string("selector")?.trim()?.take(1024)?.takeIf { it.isNotEmpty() },
                domPath = domPath,
                tagName = o.string("tag_name")?.trim()?.lowercase()?.take(64)?.takeIf { it.isNotEmpty() },
                textQuote = o.string("text_quote")?.trim()?.take(240)?.takeIf { it.isNotEmpty() },
                textPrefix = o.string("text_prefix")?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
                textSuffix = o.string("text_suffix")?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
                attrFingerprint = o.string("attr_fingerprint")?.trim()?.take(256)?.takeIf { it.isNotEmpty() },
                fileVersionId = lowerUUID(o.string("file_version_id")),
                fileVersionNumber = flexibleInt(o["file_version_number"]),
                versionDigest = o.string("version_digest")?.trim()?.lowercase()?.take(64)?.takeIf { it.length == 64 },
            )
        }
    }
}

data class FileCommentRecord(
    val id: String,
    val workspaceId: String,
    val fileId: String,
    val fileVersionId: String,
    val fileVersionNumber: Int?,
    val parentCommentId: String?,
    val body: String,
    val commentStatus: String,
    val anchor: HtmlCommentAnchor?,
    val createdByActorId: String,
    val createdByActorNpub: String?,
    val updatedByActorId: String,
    val updatedByActorNpub: String?,
    val rowVersion: Int,
    val createdAt: String,
    val updatedAt: String,
    val canEdit: Boolean,
    val canResolve: Boolean,
    val canDelete: Boolean,
) {
    val isResolved: Boolean get() = commentStatus == "resolved"
    val isRoot: Boolean get() = parentCommentId == null

    companion object {
        fun parse(o: JsonObject): FileCommentRecord? {
            val id = lowerUUID(o.string("id")) ?: return null
            val workspaceId = lowerUUID(o.string("workspace_id")) ?: return null
            val fileId = lowerUUID(o.string("file_id")) ?: return null
            val fileVersionId = lowerUUID(o.string("file_version_id")) ?: return null
            val body = o.string("body") ?: return null
            val createdByActorId = lowerUUID(o.string("created_by_actor_id")) ?: return null
            val updatedByActorId = lowerUUID(o.string("updated_by_actor_id")) ?: createdByActorId
            val rowVersion = flexibleInt(o["row_version"]) ?: 1
            val createdAt = o.string("created_at") ?: ""
            val updatedAt = o.string("updated_at") ?: createdAt
            return FileCommentRecord(
                id = id,
                workspaceId = workspaceId,
                fileId = fileId,
                fileVersionId = fileVersionId,
                fileVersionNumber = flexibleInt(o["file_version_number"]),
                parentCommentId = lowerUUID(o.string("parent_comment_id")),
                body = body,
                commentStatus = o.string("comment_status") ?: "open",
                anchor = HtmlCommentAnchor.parse(o.obj("anchor")),
                createdByActorId = createdByActorId,
                createdByActorNpub = o.string("created_by_actor_npub") ?: o.string("sender_npub"),
                updatedByActorId = updatedByActorId,
                updatedByActorNpub = o.string("updated_by_actor_npub"),
                rowVersion = rowVersion,
                createdAt = createdAt,
                updatedAt = updatedAt,
                canEdit = o.bool("can_edit") == true,
                canResolve = o.bool("can_resolve") == true,
                canDelete = o.bool("can_delete") == true,
            )
        }
    }
}

data class FileCommentPermissions(
    val canRead: Boolean = true,
    val canComment: Boolean = false,
    val canResolveAny: Boolean = false,
    val canEditSource: Boolean = false,
    val canModerate: Boolean = false,
)

data class FileCommentList(
    val fileId: String,
    val currentVersionId: String?,
    val permissions: FileCommentPermissions,
    val comments: List<FileCommentRecord>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class FileCommentThread(
    val root: FileCommentRecord,
    val replies: List<FileCommentRecord>,
    val originVersionId: String,
    val originVersionNumber: Int?,
    val fromEarlierVersion: Boolean,
)

/**
 * Groups flat file comments into ordered root+reply threads for the currently viewed version.
 * When viewing latest, unresolved threads from earlier versions can be surfaced with origin labels
 * while keeping their original revision link intact.
 */
fun groupFileCommentThreads(
    comments: List<FileCommentRecord>,
    viewedVersionId: String?,
    isViewingLatest: Boolean,
    includeEarlierUnresolved: Boolean = true,
): List<FileCommentThread> {
    val roots = comments.filter { it.isRoot }
    val repliesByParent = comments.filter { !it.isRoot }.groupBy { it.parentCommentId }
    return roots.mapNotNull { root ->
        val onViewedVersion = viewedVersionId != null && root.fileVersionId == viewedVersionId
        val carryEarlier = isViewingLatest && includeEarlierUnresolved && !onViewedVersion && !root.isResolved
        if (!onViewedVersion && !carryEarlier) return@mapNotNull null
        FileCommentThread(
            root = root,
            replies = repliesByParent[root.id].orEmpty(),
            originVersionId = root.fileVersionId,
            originVersionNumber = root.fileVersionNumber ?: root.anchor?.fileVersionNumber,
            fromEarlierVersion = !onViewedVersion,
        )
    }
}

/**
 * Boundary-private local draft persistence for unsent file comments and replies.
 * Survives offline/network failures and sheet dismissal; cleared only on confirmed server acceptance.
 */
class FileCommentDraftStore(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    data class Draft(
        val key: String,
        val body: String,
        val clientRequestId: String = UUID.randomUUID().toString(),
        val fileVersionId: String? = null,
        val parentCommentId: String? = null,
        val anchor: HtmlCommentAnchor? = null,
    )

    private val mutex = Mutex()

    suspend fun loadAll(): Map<String, Draft> = mutex.withLock {
        withContext(io) {
            if (!file.exists()) return@withContext emptyMap()
            runCatching {
                val obj = Json.parseToJsonElement(file.readText()).jsonObject
                obj.entries.mapNotNull { (key, value) ->
                    val row = value as? JsonObject ?: return@mapNotNull null
                    val body = row.string("body") ?: return@mapNotNull null
                    key to Draft(
                        key = key,
                        body = body,
                        clientRequestId = row.string("client_request_id") ?: UUID.randomUUID().toString(),
                        fileVersionId = lowerUUID(row.string("file_version_id")),
                        parentCommentId = lowerUUID(row.string("parent_comment_id")),
                        anchor = HtmlCommentAnchor.parse(row.obj("anchor")),
                    )
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }

    suspend fun save(draft: Draft) = mutex.withLock {
        withContext(NonCancellable + io) {
            val current = if (file.exists()) {
                runCatching { Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap() }.getOrDefault(mutableMapOf())
            } else mutableMapOf()
            if (draft.body.isBlank()) {
                current.remove(draft.key)
            } else {
                current[draft.key] = buildJsonObject {
                    put("body", draft.body)
                    put("client_request_id", draft.clientRequestId)
                    draft.fileVersionId?.let { put("file_version_id", it) }
                    draft.parentCommentId?.let { put("parent_comment_id", it) }
                    draft.anchor?.let { put("anchor", it.toJson()) }
                }
            }
            writeAtomically(JsonObject(current))
        }
    }

    suspend fun remove(key: String) = mutex.withLock {
        withContext(NonCancellable + io) {
            if (!file.exists()) return@withContext
            val current = runCatching { Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap() }.getOrDefault(mutableMapOf())
            current.remove(key)
            if (current.isEmpty()) {
                file.delete()
            } else {
                writeAtomically(JsonObject(current))
            }
        }
    }

    private fun writeAtomically(obj: JsonObject) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        pending.writeText(obj.toString())
        check(pending.renameTo(file)) { "Could not preserve your comment draft." }
    }
}

suspend fun TowerAPI.artefactHTMLPayload(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    versionId: String? = null,
): HtmlArtefactPayload {
    val query = versionId?.let { listOf("version_id" to TowerAPI.uuid(it)) } ?: emptyList()
    val o = obj(
        client.request(
            path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/html",
            query = query,
            appNpub = appNpub,
        ),
        "HTML artefact",
    )
    if (lowerUUID(o.string("workspace_id")) != workspaceId || lowerUUID(o.obj("source")?.string("id")) != fileId.lowercase()) {
        throw TowerError.Malformed("HTML artefact")
    }
    val content = o.obj("content") ?: throw TowerError.Malformed("HTML artefact")
    val rawHtml = content.string("html") ?: throw TowerError.Malformed("HTML artefact")
    val resolvedVersionId = lowerUUID(content.string("version_id")) ?: throw TowerError.Malformed("HTML artefact")
    val versionNumber = flexibleInt(content["version_number"]) ?: 1
    val currentVersionId = lowerUUID(content.string("current_version_id"))
    val currentVersionNumber = flexibleInt(content["current_version_number"])
    val digest = content.string("digest") ?: ""
    val manifestError = content.string("manifest_error")
    val versions = (o["versions"] as? JsonArray)?.mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = lowerUUID(row.string("id")) ?: return@mapNotNull null
        val number = flexibleInt(row["version_number"]) ?: return@mapNotNull null
        HtmlArtefactVersion(
            id = id,
            versionNumber = number,
            createdAt = row.string("created_at") ?: "",
            createdByActorNpub = row.string("created_by_actor_npub"),
            contentType = row.string("content_type"),
            sizeBytes = flexibleLong(row["size_bytes"]),
        )
    }.orEmpty()

    val resolvedAssets = buildJsonObject {
        var total = 0L
        (content["assets"] as? JsonArray)?.take(64)?.forEach { entry ->
            val asset = entry as? JsonObject ?: return@forEach
            if (asset.string("status") != "ready") return@forEach
            val mime = asset.string("content_type") ?: return@forEach
            if (mime !in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/svg+xml")) return@forEach
            val name = asset.string("name") ?: return@forEach
            val id = lowerUUID(asset.string("file_id")) ?: return@forEach
            val version = lowerUUID(asset.string("version_id")) ?: return@forEach
            val size = flexibleLong(asset["size_bytes"]) ?: return@forEach
            val sha = asset.string("sha256") ?: return@forEach
            if (size !in 1..(8 * 1024 * 1024) || total + size > 32 * 1024 * 1024) return@forEach
            try {
                val bytes = client.request(path = TowerAPI.base(workspaceId) + "/files/$id/versions/$version/object", appNpub = appNpub)
                if (bytes.size.toLong() != size || Sha256Hex.of(bytes) != sha) return@forEach
                total += size
                put(name, "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                /* A revoked or unavailable asset contributes no bytes. */
            }
        }
    }
    val html = if (resolvedAssets.isEmpty()) rawHtml else {
        val json = resolvedAssets.toString().replace("<", "\\u003c")
        rawHtml + "<script>(()=>{const urls=$json;Object.defineProperty(window,'wingmanAssets',{value:Object.freeze({url:n=>Object.hasOwn(urls,String(n))?urls[String(n)]:null,names:()=>Object.keys(urls)})});const apply=()=>{document.querySelectorAll('[data-asset],[data-asset-background]').forEach(el=>{const n=el.getAttribute('data-asset'),b=el.getAttribute('data-asset-background');if(n){const u=urls[n];if(u){if(el.namespaceURI==='http://www.w3.org/2000/svg')el.setAttribute('href',u);else el.setAttribute('src',u)}else el.removeAttribute('src');el.setAttribute('data-asset-state',u?'ready':'missing')}if(b){const u=urls[b];if(u)el.style.backgroundImage='url(\"'+u+'\")';el.setAttribute('data-asset-state',u?'ready':'missing')}})};apply();new MutationObserver(apply).observe(document.documentElement,{childList:true,subtree:true});document.dispatchEvent(new CustomEvent('wingman:assets-ready',{detail:{names:Object.keys(urls)}}))})();</script>"
    }

    return HtmlArtefactPayload(
        fileId = fileId.lowercase(),
        versionId = resolvedVersionId,
        versionNumber = versionNumber,
        currentVersionId = currentVersionId,
        currentVersionNumber = currentVersionNumber,
        digest = digest,
        html = html,
        versions = versions,
        manifestError = manifestError,
    )
}

suspend fun TowerAPI.fileComments(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    versionId: String? = null,
    status: String? = null,
    cursor: String? = null,
    limit: Int = 200,
): FileCommentList {
    val expectedWorkspace = workspaceId.lowercase()
    val expectedFile = fileId.lowercase()
    val query = buildList {
        versionId?.let { add("version_id" to TowerAPI.uuid(it)) }
        status?.let { add("status" to it) }
        cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
        add("limit" to limit.coerceIn(1, 200).toString())
    }
    val o = obj(
        client.request(
            path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/comments",
            query = query,
            appNpub = appNpub,
        ),
        "file comments",
    )
    val returnedFileId = lowerUUID(o.string("file_id"))
    if (returnedFileId != expectedFile) throw TowerError.Malformed("file comments file_id mismatch")
    val permsObj = o.obj("permissions")
    val perms = FileCommentPermissions(
        canRead = permsObj?.bool("can_read") != false,
        canComment = permsObj?.bool("can_comment") == true,
        canResolveAny = permsObj?.bool("can_resolve_any") == true,
        canEditSource = permsObj?.bool("can_edit_source") == true,
        canModerate = permsObj?.bool("can_moderate") == true,
    )
    val rawComments = (o["comments"] as? JsonArray).orEmpty()
    val comments = rawComments.map { entry ->
        val row = (entry as? JsonObject)?.let(FileCommentRecord::parse) ?: throw TowerError.Malformed("file comment row")
        if (row.workspaceId != expectedWorkspace || row.fileId != expectedFile) {
            throw TowerError.Malformed("file comment workspace/file binding mismatch")
        }
        if (versionId != null && row.fileVersionId != versionId.lowercase()) {
            throw TowerError.Malformed("file comment version binding mismatch")
        }
        row
    }
    return FileCommentList(
        fileId = expectedFile,
        currentVersionId = lowerUUID(o.string("current_version_id")),
        permissions = perms,
        comments = comments,
        nextCursor = o.string("next_cursor"),
        hasMore = o.bool("has_more") == true,
    )
}

/**
 * Fetches all pages of file comments so threads and replies beyond 200 records are never truncated.
 */
suspend fun TowerAPI.allFileComments(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    versionId: String? = null,
    status: String? = null,
    maxPages: Int = 50,
): FileCommentList {
    val all = mutableListOf<FileCommentRecord>()
    val seenIds = mutableSetOf<String>()
    var cursor: String? = null
    var lastPage: FileCommentList? = null
    for (pageIdx in 0 until maxPages) {
        val page = fileComments(
            workspaceId = workspaceId,
            appNpub = appNpub,
            fileId = fileId,
            versionId = versionId,
            status = status,
            cursor = cursor,
            limit = 200,
        )
        lastPage = page
        for (c in page.comments) {
            if (seenIds.add(c.id)) all.add(c)
        }
        val next = page.nextCursor
        if (!page.hasMore || next.isNullOrBlank() || next == cursor) {
            return page.copy(comments = all, nextCursor = null, hasMore = false)
        }
        cursor = next
    }
    val tail = lastPage ?: return FileCommentList(fileId.lowercase(), null, FileCommentPermissions(), emptyList(), null, false)
    return tail.copy(comments = all)
}

suspend fun TowerAPI.createFileComment(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    body: String,
    fileVersionId: String? = null,
    parentCommentId: String? = null,
    anchor: HtmlCommentAnchor? = null,
    clientRequestId: String = UUID.randomUUID().toString(),
): FileCommentRecord {
    val payload = buildJsonObject {
        put("body", body.trim())
        put("client_request_id", clientRequestId)
        fileVersionId?.let { put("file_version_id", TowerAPI.uuid(it)) }
        parentCommentId?.let { put("parent_comment_id", TowerAPI.uuid(it)) }
        anchor?.let { put("anchor", it.toJson()) }
    }
    val o = obj(
        client.request(
            method = "POST",
            path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/comments",
            appNpub = appNpub,
            jsonBody = payload.toString().toByteArray(Charsets.UTF_8),
        ),
        "create file comment",
    )
    return o.obj("comment")?.let(FileCommentRecord::parse) ?: throw TowerError.Malformed("create file comment")
}

suspend fun TowerAPI.updateFileComment(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    commentId: String,
    rowVersion: Int,
    commentStatus: String? = null,
    body: String? = null,
): FileCommentRecord {
    val payload = buildJsonObject {
        put("row_version", rowVersion)
        commentStatus?.let { put("comment_status", it) }
        body?.let { put("body", it.trim()) }
    }
    val o = obj(
        client.request(
            method = "PATCH",
            path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/comments/" + TowerAPI.uuid(commentId),
            appNpub = appNpub,
            jsonBody = payload.toString().toByteArray(Charsets.UTF_8),
        ),
        "update file comment",
    )
    return o.obj("comment")?.let(FileCommentRecord::parse) ?: throw TowerError.Malformed("update file comment")
}

suspend fun TowerAPI.deleteFileComment(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    commentId: String,
    rowVersion: Int,
) {
    client.request(
        method = "DELETE",
        path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/comments/" + TowerAPI.uuid(commentId),
        query = listOf("row_version" to rowVersion.toString()),
        appNpub = appNpub,
    )
}
