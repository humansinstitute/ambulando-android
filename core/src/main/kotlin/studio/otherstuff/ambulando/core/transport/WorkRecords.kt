package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Typed views over the Tower serializers used by tasks, documents, files, inbox and reactions
// (`serializeFlightDeckPgTask/TaskComment/Doc/DocComment/File/FileFolder/ResourceViewState/Reaction`).
// Task, comment, document, file and folder records keep their canonical JSON in `raw`.

private val EMPTY = JsonObject(emptyMap())

enum class TaskState(val wire: String) {
    NEW("new"), BACKLOG("backlog"), READY("ready"), IN_PROGRESS("in_progress"), REVIEW("review"), BLOCKED("blocked"),
    DONE("done"), ARCHIVE("archive"), ARCHIVED("archived");

    val label: String
        get() = when (this) {
            NEW -> "New"
            BACKLOG -> "Backlog"
            READY -> "Ready"
            IN_PROGRESS -> "In progress"
            REVIEW -> "Review"
            BLOCKED -> "Blocked"
            DONE -> "Done"
            ARCHIVE, ARCHIVED -> "Archived"
        }

    val isTerminal: Boolean get() = this == DONE || this == ARCHIVE || this == ARCHIVED

    companion object {
        fun from(wire: String?): TaskState? = entries.firstOrNull { it.wire == wire }

        /** Board columns, in the web board's order. Archived tasks are not shown on the board. */
        val board: List<TaskState> = listOf(NEW, READY, IN_PROGRESS, REVIEW, BLOCKED, DONE)
    }
}

enum class TaskPriority(val wire: String) {
    ROCK("rock"), PEBBLE("pebble"), SAND("sand"), LOW("low"), NORMAL("normal"), HIGH("high"), URGENT("urgent");

    val label: String get() = wire.replaceFirstChar { it.uppercase() }

    companion object {
        fun from(wire: String?): TaskPriority? = entries.firstOrNull { it.wire == wire }

        /** The priorities the web task editor offers. */
        val editable: List<TaskPriority> = listOf(ROCK, PEBBLE, SAND)
    }
}

data class TaskRecord(
    val workspaceId: String,
    val id: String,
    val scopeId: String?,
    val channelId: String,
    val threadId: String? = null,
    val title: String,
    val description: String?,
    val state: String,
    val priority: String,
    val assignedToNpub: String?,
    val createdByNpub: String? = null,
    val rowVersion: Long,
    val activityVersion: Long = 0,
    val createdAt: String,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    val taskState: TaskState? get() = TaskState.from(state)

    companion object {
        fun parse(raw: JsonObject): TaskRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val c = lowerUUID(raw.string("channel_id"))
            if (id == null || w == null || c == null) throw TowerError.Malformed("task")
            val createdAt = raw.string("created_at") ?: ""
            return TaskRecord(
                workspaceId = w, id = id, scopeId = lowerUUID(raw.string("scope_id")), channelId = c,
                threadId = lowerUUID(raw.string("thread_id")), title = raw.string("title") ?: "Untitled task",
                description = raw.string("description"), state = raw.string("state") ?: "new", priority = raw.string("priority") ?: "sand",
                assignedToNpub = raw.string("assigned_to_npub")?.takeIf { it.isNotEmpty() }, createdByNpub = raw.string("created_by_actor_npub"),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, activityVersion = flexibleLong(raw["activity_version"]) ?: 0,
                createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt, raw = raw,
            )
        }
    }
}

/** Task and document comments share one shape locally. */
data class CommentRecord(
    val workspaceId: String,
    val id: String,
    val parent: Parent,
    val parentId: String,
    val parentCommentId: String? = null,
    val body: String,
    val senderNpub: String?,
    /** Document comments: `open` / `resolved`. */
    val status: String? = null,
    val deleted: Boolean = false,
    val rowVersion: Long = 1,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val clientRecordId: String? = null,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    enum class Parent(val wire: String) { TASK("task"), DOC("doc") }

    companion object {
        fun parse(raw: JsonObject, parent: Parent): CommentRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val p = lowerUUID(raw.string(if (parent == Parent.TASK) "task_id" else "doc_id"))
            if (id == null || w == null || p == null) throw TowerError.Malformed("comment")
            val metadata = raw.obj("metadata")
            val createdAt = raw.string("created_at") ?: ""
            return CommentRecord(
                workspaceId = w, id = id, parent = parent, parentId = p, parentCommentId = lowerUUID(raw.string("parent_comment_id")),
                body = raw.string("body") ?: "", senderNpub = raw.string("sender_npub") ?: raw.string("created_by_actor_npub"),
                status = metadata?.string("comment_status"), deleted = raw.string("record_state") == "deleted",
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt,
                clientRecordId = metadata?.string("client_record_id")?.lowercase(), raw = raw,
            )
        }
    }
}

data class DocRecord(
    val workspaceId: String,
    val id: String,
    val scopeId: String?,
    val channelId: String,
    val storageObjectId: String?,
    val title: String,
    val summary: String?,
    val archived: Boolean = false,
    val rowVersion: Long,
    val activityVersion: Long = 0,
    val createdAt: String,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): DocRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val c = lowerUUID(raw.string("channel_id"))
            if (id == null || w == null || c == null) throw TowerError.Malformed("document")
            val createdAt = raw.string("created_at") ?: ""
            return DocRecord(
                workspaceId = w, id = id, scopeId = lowerUUID(raw.string("scope_id")), channelId = c,
                storageObjectId = lowerUUID(raw.string("storage_object_id")), title = raw.string("title") ?: "Untitled document",
                summary = raw.string("summary"), archived = raw.string("record_state") == "archived" || raw.string("archived_at") != null,
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, activityVersion = flexibleLong(raw["activity_version"]) ?: 0,
                createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt, raw = raw,
            )
        }
    }
}

/** A fetched document body with the identity Tower needs to accept a save against it. */
class DocBodyRecord(
    val workspaceId: String,
    val docId: String,
    val storageObjectId: String,
    /** sha256 of the canonical body (Tower's storage identity, verified against the bytes). */
    val sha256: String,
    val contentType: String,
    val docRowVersion: Long,
    val bytes: ByteArray,
    val fetchedAt: String,
) {
    override fun equals(other: Any?): Boolean = other is DocBodyRecord && workspaceId == other.workspaceId && docId == other.docId &&
        storageObjectId == other.storageObjectId && sha256 == other.sha256 && contentType == other.contentType &&
        docRowVersion == other.docRowVersion && bytes.contentEquals(other.bytes) && fetchedAt == other.fetchedAt

    override fun hashCode(): Int = (docId.hashCode() * 31 + sha256.hashCode()) * 31 + docRowVersion.hashCode()
    override fun toString(): String = "DocBodyRecord(docId=$docId, sha256=$sha256, bytes=${bytes.size})"
}

data class FileRecord(
    val workspaceId: String,
    val id: String,
    val scopeId: String?,
    val channelId: String,
    val folderId: String?,
    val storageObjectId: String?,
    val displayName: String,
    val description: String? = null,
    val contentType: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val archived: Boolean = false,
    val rowVersion: Long = 1,
    val createdAt: String,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): FileRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val c = lowerUUID(raw.string("channel_id"))
            if (id == null || w == null || c == null) throw TowerError.Malformed("file")
            val version = raw.obj("current_version")
            val storage = raw.obj("object")?.obj("storage_object")
            val createdAt = raw.string("created_at") ?: ""
            return FileRecord(
                workspaceId = w, id = id, scopeId = lowerUUID(raw.string("scope_id")), channelId = c,
                folderId = lowerUUID(raw.string("folder_id")), storageObjectId = lowerUUID(raw.string("storage_object_id")),
                displayName = raw.string("display_name")?.takeIf { it.isNotEmpty() } ?: storage?.string("file_name") ?: "Untitled file",
                description = raw.string("description"),
                contentType = version?.string("content_type") ?: storage?.string("content_type"),
                sizeBytes = flexibleLong(version?.get("size_bytes")) ?: flexibleLong(storage?.get("size_bytes")),
                sha256 = (version?.string("sha256_hex") ?: storage?.string("sha256_hex"))?.lowercase(),
                archived = raw.string("record_state") == "archived" || raw.string("archived_at") != null,
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, createdAt = createdAt, updatedAt = raw.string("updated_at") ?: createdAt,
                raw = raw,
            )
        }
    }
}

data class FolderRecord(
    val workspaceId: String,
    val id: String,
    val channelId: String,
    val parentFolderId: String?,
    val title: String,
    val deleted: Boolean = false,
    val rowVersion: Long = 1,
    val updatedAt: String,
    val raw: JsonObject = EMPTY,
    /** Local store flag (Android: access loss is non-destructive): the record is no longer visible to this identity but kept
     *  readable, labelled and excluded from sync. Always false for records parsed from Tower. */
    val accessRemoved: Boolean = false,
) {
    companion object {
        fun parse(raw: JsonObject): FolderRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val c = lowerUUID(raw.string("channel_id"))
            if (id == null || w == null || c == null) throw TowerError.Malformed("folder")
            return FolderRecord(
                workspaceId = w, id = id, channelId = c, parentFolderId = lowerUUID(raw.string("parent_folder_id")),
                title = raw.string("title") ?: "Folder", deleted = raw.string("deleted_at") != null,
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "", raw = raw,
            )
        }
    }
}

/** `serializeFlightDeckPgResourceViewState`: the actor's read position for one task, document or thread. */
data class ViewStateRecord(
    val workspaceId: String,
    val resourceType: ResourceType,
    val resourceId: String,
    val scopeId: String?,
    val channelId: String?,
    val activityVersion: Long,
    val viewedActivityVersion: Long,
    /** Present when Tower evaluated its attention policy (mentions, assignment). */
    val attentionActivityVersion: Long? = null,
    val rowVersion: Long = 1,
    val updatedAt: String,
) {
    enum class ResourceType(val wire: String) {
        TASK("task"), DOCUMENT("document"), THREAD("thread");

        companion object {
            fun from(wire: String?): ResourceType? = entries.firstOrNull { it.wire == wire }
        }
    }

    val id: String get() = "${resourceType.wire}:$resourceId"
    val unread: Boolean get() = activityVersion > viewedActivityVersion
    val needsAttention: Boolean get() = (attentionActivityVersion ?: 0) > viewedActivityVersion

    companion object {
        fun parse(raw: JsonObject): ViewStateRecord {
            val w = lowerUUID(raw.string("workspace_id"))
            val type = ResourceType.from(raw.string("resource_type"))
            val r = lowerUUID(raw.string("resource_id"))
            if (w == null || type == null || r == null) throw TowerError.Malformed("view state")
            return ViewStateRecord(
                workspaceId = w, resourceType = type, resourceId = r, scopeId = lowerUUID(raw.string("scope_id")),
                channelId = lowerUUID(raw.string("channel_id")), activityVersion = flexibleLong(raw["activity_version"]) ?: 0,
                viewedActivityVersion = flexibleLong(raw["viewed_activity_version"]) ?: 0,
                attentionActivityVersion = flexibleLong(raw["attention_activity_version"]),
                rowVersion = flexibleLong(raw["row_version"]) ?: 0, updatedAt = raw.string("updated_at") ?: "",
            )
        }
    }
}

/** The six reactions Tower accepts (`reactionEmojis`), with the glyph shown for each. */
enum class ReactionEmoji(val wire: String, val displayName: String, val glyph: String) {
    THUMBS_UP("thumbs_up", "Thumbs up", "👍"),
    SMILE("smile", "Smile", "😄"),
    HEART("heart", "Heart", "❤️"),
    EYES("eyes", "Eyes", "👀"),
    PARTY("party", "Party", "🎉"),
    CHECK("white_check_mark", "Done", "✅");

    companion object {
        fun from(wire: String?): ReactionEmoji? = entries.firstOrNull { it.wire == wire }
    }
}

data class ReactionRecord(
    val workspaceId: String,
    val id: String,
    val targetType: String,
    val targetId: String,
    val emoji: String,
    val reactorNpub: String?,
    val deleted: Boolean = false,
    val rowVersion: Long = 1,
    val updatedAt: String,
) {
    companion object {
        fun parse(raw: JsonObject): ReactionRecord {
            val id = lowerUUID(raw.string("id"))
            val w = lowerUUID(raw.string("workspace_id"))
            val target = lowerUUID(raw.string("target_id"))
            if (id == null || w == null || target == null) throw TowerError.Malformed("reaction")
            return ReactionRecord(
                workspaceId = w, id = id, targetType = raw.string("target_type") ?: "message", targetId = target,
                emoji = raw.string("emoji") ?: "", reactorNpub = raw.string("reactor_npub"),
                deleted = raw.string("record_state") == "deleted", rowVersion = flexibleLong(raw["row_version"]) ?: 0,
                updatedAt = raw.string("updated_at") ?: "",
            )
        }
    }
}

/**
 * A storage object attached to a message (Tower's `metadata.attachments` entries). The transport layer reads these from
 * message JSON; `StorageMarkdown` derives them from storage Markdown.
 */
@Serializable
data class MessageAttachment(
    val kind: String,
    val storageObjectId: String,
    val filename: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    /** Text attachment contract v1: `content_type`, `sha256_hex` and the `text_attachment` marker (counts only). */
    val sha256: String? = null,
    val textAttachment: studio.otherstuff.ambulando.core.chat.TextAttachmentInfo? = null,
) {
    val requestObject: JsonObject
        get() {
            val pairs = mutableListOf<Pair<String, Any?>>("kind" to kind, "storage_object_id" to storageObjectId, "filename" to filename)
            // The web writes text attachments with `content_type`; other attachments keep `mime_type`.
            if (mimeType != null) pairs.add((if (textAttachment != null) "content_type" else "mime_type") to mimeType)
            if (sizeBytes != null) pairs.add("size_bytes" to sizeBytes)
            if (sha256 != null) pairs.add("sha256_hex" to sha256)
            if (textAttachment != null) pairs.add("text_attachment" to textAttachment.requestObject)
            return jsonObjectOf(*pairs.toTypedArray())
        }

    val isText: Boolean get() = studio.otherstuff.ambulando.core.chat.TextAttachments.isText(kind, mimeType, textAttachment)

    companion object {
        /** Attachments of a message's canonical JSON (only well-formed storage references). */
        fun from(messageRaw: JsonObject): List<MessageAttachment> {
            val list = messageRaw.objects("attachments") ?: messageRaw.obj("metadata")?.objects("attachments") ?: emptyList()
            return list.mapNotNull { a ->
                val id = lowerUUID(a.string("storage_object_id")) ?: return@mapNotNull null
                MessageAttachment(
                    kind = a.string("kind") ?: "file", storageObjectId = id, filename = a.string("filename") ?: "Attachment",
                    mimeType = a.string("mime_type") ?: a.string("content_type"), sizeBytes = flexibleLong(a["size_bytes"]),
                    sha256 = a.string("sha256_hex")?.lowercase(), textAttachment = studio.otherstuff.ambulando.core.chat.TextAttachmentInfo.from(a),
                )
            }
        }
    }
}
