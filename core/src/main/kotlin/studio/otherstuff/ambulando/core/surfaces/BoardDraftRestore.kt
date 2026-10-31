package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WorkspaceStorePool
import studio.otherstuff.ambulando.core.store.file
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.store.connections
import java.io.File
import java.util.UUID

/**
 * Unsaved board edits made before multiple workspaces lived in one flat directory keyed by file id. They move into their
 * boundary's own directory only when ownership is proven the way cached content is: the partition imported its legacy rows
 * with a single owner and knows the board File. Anything else stays where it was (never shown in another workspace), and the
 * originals are never removed. Completion is recorded, so a draft the person discards is not brought back.
 */
object BoardDraftRestore {
    const val MARKER = "partition.board-drafts-restored.v1"
    private const val MAX_BYTES = 8L * 1024 * 1024

    suspend fun restore(partition: LocalStore, legacyDirectory: File, destination: File): Int {
        if (partition.reader.state(WorkspaceStorePool.LEGACY_STATE) != WorkspaceStorePool.IMPORTED) return 0
        if (partition.reader.state(MARKER) != null) return 0
        val connection = partition.reader.connections().singleOrNull() ?: return 0
        val workspace = partition.reader.workspaces(connection.id).singleOrNull() ?: return 0
        var copied = 0
        if (legacyDirectory.isDirectory) {
            val source = BoardDraftStore(legacyDirectory)
            for (candidate in legacyDirectory.listFiles().orEmpty()) {
                if (!candidate.isFile || java.nio.file.Files.isSymbolicLink(candidate.toPath()) || candidate.length() > MAX_BYTES) continue
                if (candidate.extension != "json") continue
                val id = runCatching { UUID.fromString(candidate.nameWithoutExtension).toString().lowercase() }.getOrNull() ?: continue
                if (candidate.nameWithoutExtension.lowercase() != id) continue
                val draft = source.load(id) ?: continue
                if (draft.baseVersionNumber <= 0 || runCatching { UUID.fromString(draft.baseVersionId) }.isFailure) continue
                if (partition.reader.file(workspace.id, id) == null) continue
                val target = File(destination, "$id.json")
                if (target.exists()) continue
                destination.mkdirs()
                val temp = File(destination, "$id.json.migrating")
                candidate.copyTo(temp, overwrite = true)
                if (runCatching { temp.renameTo(target) }.getOrDefault(false)) copied += 1 else temp.delete()
            }
        }
        partition.writer.setState(MARKER, "complete")
        return copied
    }
}
