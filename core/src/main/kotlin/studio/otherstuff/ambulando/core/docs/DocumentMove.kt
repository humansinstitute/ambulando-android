package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.*

/**
 * What moving a document changes, stated from what this device knows about the two channels. A move is not a copy: the
 * record keeps its id, body, comments, attachments and history, and its access becomes that of the destination channel.
 * A link grants no access, so links to the document keep working only for people who can read it where it now lives.
 */
data class DocMoveConsequences(val lines: List<String>, val touchesPrivateConversation: Boolean)

object DocMovePlan {
    private fun nav(channel: ChannelRecord) = NavChannel(channel.id, channel.scopeId, channel.name, channel.kind, channel.position, channel.raw)

    fun consequences(source: ChannelRecord, destination: ChannelRecord, viewerNpub: String?, names: Map<String, String> = emptyMap()): DocMoveConsequences {
        fun label(channel: ChannelRecord) = SidebarNavigation.label(nav(channel), viewerNpub, names)
        val sourcePrivate = SidebarNavigation.isDirectMessageChannel(nav(source))
        val destinationPrivate = SidebarNavigation.isDirectMessageChannel(nav(destination))
        val lines = mutableListOf(
            "The document moves; it is not copied. Its text, comments, attachments and history go with it.",
            "Access follows ${label(destination)}: people who can read that channel can open the document, and people who could open it only through ${label(source)} can no longer open it.",
            "Links to it keep pointing at this document, but a link never grants access.",
        )
        if (sourcePrivate) lines.add(if (SidebarNavigation.participants(nav(source)).size == 1) "It leaves your private note-to-self conversation." else "It leaves a direct message that only its participants can read.")
        if (destinationPrivate) lines.add("It enters a direct message: only that conversation's participants can read it.")
        if (source.scopeId != destination.scopeId) lines.add("It also changes scope, so scope-level access and the places that list it change too.")
        return DocMoveConsequences(lines, sourcePrivate || destinationPrivate)
    }
}

sealed class DocMoveResult {
    /** Tower moved it (confirmed by its answer or by reading the document back). [draftNote] is set when an unsaved draft could not be re-based. */
    data class Moved(val doc: DocRecord, val draftNote: String? = null) : DocMoveResult()
    /** The document changed since this device last read it; nothing moved. */
    data class Stale(val message: String) : DocMoveResult()
    data class SameDestination(val message: String) : DocMoveResult()
    /** The viewer may not move it from here or into the destination; nothing moved. */
    data class Denied(val message: String) : DocMoveResult()
    /** Nothing was sent, or Tower confirmed the document is still where it was. */
    data class NotMoved(val message: String) : DocMoveResult()
    /** The request may have reached Tower and neither its answer nor the read-back arrived. Never retried automatically. */
    data class Unknown(val message: String) : DocMoveResult()
}

/**
 * Moves a document with Tower's own operation (`POST /docs/:d/move`), online only: it needs the current row version, and
 * an access change is never shown as done before Tower confirms it, so it is never queued for a later blind retry.
 *
 * A draft on this device is kept: when it is based on the version being moved, its base version follows the document
 * (compare-and-set); a draft on an older version, or one being saved, stops the move before anything is sent. If the
 * answer is lost or unusable the document is read back by id: moved, still in place, or unknown, never guessed.
 */
suspend fun WorkspaceSync.moveDocument(
    connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, destination: ChannelRecord, rowVersion: Int,
): DocMoveResult = engine.perform("doc-move:${workspace.id}:$docId", SyncPriority.USER) {
    val key = DocDraft.key(connection.id, workspace.id, docId)
    val draft = store.reader.docDraft(key)
    if (draft != null && draft.baseRowVersion != rowVersion) {
        return@perform DocMoveResult.NotMoved("You have unsaved edits based on an older version of this document. Save or discard them, then move it.")
    }
    if (draft != null && draft.state != DocDraft.State.EDITING && draft.state != DocDraft.State.HELD && draft.state != DocDraft.State.FAILED) {
        return@perform DocMoveResult.NotMoved("This document has edits that still need attention. Resolve them, then move it.")
    }
    try {
        identity.requireSigner(connection.signerNpub)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        return@perform DocMoveResult.NotMoved("Not moved: unlock to move this document. Nothing was sent.")
    }
    val api = api(connection)
    val body = jsonBody(buildJsonObject {
        put("destination_channel_id", destination.id); put("destination_scope_id", destination.scopeId); put("row_version", rowVersion)
    })
    val path = TowerAPI.base(workspace.id) + "/docs/" + TowerAPI.uuid(docId) + "/move"

    fun isTheMove(doc: DocRecord) = doc.workspaceId == workspace.id && doc.id == docId.lowercase() && doc.channelId == destination.id &&
        (doc.scopeId == null || doc.scopeId == destination.scopeId) && doc.rowVersion > rowVersion

    suspend fun finish(doc: DocRecord): DocMoveResult {
        runCatching { store.writer.upsertDocs(workspace.id, listOf(doc)) }
        var note: String? = null
        if (store.reader.docDraft(key)?.baseRowVersion == rowVersion) {
            val rebased = runCatching { store.writer.rebaseDocDraft(key, workspace.id, rowVersion, doc.rowVersion.toInt()) }.getOrDefault(false)
            if (!rebased) note = "Your unsaved edits are still on this device, but they changed while the document moved. Saving may ask you to review them."
        }
        return DocMoveResult.Moved(doc, note)
    }

    suspend fun reconcile(why: String): DocMoveResult {
        val current = runCatching { api.doc(workspace.id, workspace.appNpub, docId) }.getOrNull()
            ?: return DocMoveResult.Unknown("Tower's answer did not arrive ($why), so it is not known whether the document moved. Do not repeat the move until you have looked at where it is.")
        if (isTheMove(current)) return finish(current)
        runCatching { store.writer.upsertDocs(workspace.id, listOf(current)) }
        if (current.channelId != destination.id && current.rowVersion == rowVersion.toLong()) {
            return DocMoveResult.NotMoved("The move did not happen ($why); Tower still has the document where it was.")
        }
        return DocMoveResult.Unknown("The document changed while it was being moved ($why). It has been refreshed; check where it is before trying again.")
    }

    val reply = try {
        api.client.exchange("POST", path, workspace.appNpub, body)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        return@perform reconcile(e.message ?: "no connection")
    }
    val obj = parseJsonObjectOrNull(reply.body)
    val code = obj?.string("code") ?: obj?.string("error")
    if (reply.status in 200..299) {
        val moved = obj?.obj("doc")?.let { runCatching { DocRecord.parse(it) }.getOrNull() }?.takeIf(::isTheMove)
        return@perform if (moved != null) finish(moved) else reconcile("Tower's answer could not be read")
    }
    when {
        reply.status == 409 && code == "stale_row_version" -> DocMoveResult.Stale("This document changed since you opened it. Nothing was moved; reopen it and try again.")
        reply.status == 409 && code == "same_destination" -> DocMoveResult.SameDestination("The document is already in that channel.")
        reply.status == 403 -> DocMoveResult.Denied("You do not have permission to move this document there. Nothing was moved.")
        reply.status == 404 -> DocMoveResult.Denied("The document or the destination is no longer available. Nothing was moved.")
        else -> reconcile("Tower answered HTTP ${reply.status}")
    }
}
