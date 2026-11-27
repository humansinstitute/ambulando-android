package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.surfaces.artefactFile
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** A stable Goal identity. The File wrapper preserves existing references/UI; SQL content never replaces File history. */
data class GoalDocument(val file: FileRecord, val versionId: String, val goal: Goal) {
    val id: String get() = file.id
}

object GoalFiles {
    const val APP_ID = "goals"
    const val METADATA_KEY = "goal"

    fun metadata(): JsonObject = jsonObjectOf(METADATA_KEY to jsonObjectOf("schema_version" to Goal.VERSION))

    fun isGoal(file: FileRecord): Boolean =
        !file.archived && file.raw.string("record_state") != "deleted" && file.raw["deleted_at"].let { it == null || it is kotlinx.serialization.json.JsonNull } &&
            file.contentType?.substringBefore(';')?.trim()?.lowercase() == Goal.MIME &&
            flexibleInt(file.raw.obj("metadata")?.obj(METADATA_KEY)?.get("schema_version")) == Goal.VERSION

    fun fileName(goal: Goal): String =
        goal.name.replace(Regex("[\\u0000-\\u001f\\u007f/\\\\]"), " ").trim().take(80).ifEmpty { "Goal" } + ".goal.json"
}

/** Goal Files of one channel. A failed or truncated list is an error, never "no goals". */
suspend fun TowerAPI.legacyGoalFiles(workspace: String, app: String, channelId: String): List<FileRecord> =
    client.list(TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channelId)}/files", app, "files")
        .map(FileRecord::parse).onEach { require(it.workspaceId == workspace && it.channelId == channelId) }
        .filter(GoalFiles::isGoal)

/** The file's current version, read directly (independent of history length) and verified against its hash. */
suspend fun TowerAPI.readLegacyGoal(workspace: String, app: String, fileId: String): GoalDocument {
    val o = obj(client.request(path = TowerAPI.base(workspace) + "/files/" + TowerAPI.uuid(fileId), appNpub = app), "goal")
    val file = o.obj("file")?.let(FileRecord::parse) ?: throw TowerError.Malformed("goal")
    if (file.workspaceId != workspace || file.id != fileId.lowercase()) throw TowerError.Malformed("goal")
    if (!GoalFiles.isGoal(file)) throw GoalError.Invalid("This is not a goal.")
    return readLegacyGoal(workspace, app, file)
}

suspend fun TowerAPI.readLegacyGoal(workspace: String, app: String, file: FileRecord): GoalDocument {
    val embedded = file.raw.obj("current_version")?.let { runCatching { FileVersionRecord.parse(it) }.getOrNull() }
        ?.takeIf { it.fileId == file.id && it.sha256 != null }
    val version = embedded ?: fileVersions(workspace, app, file.id).let { list ->
        list.versions.firstOrNull { it.id == list.currentVersionId } ?: throw TowerError.Malformed("goal version")
    }
    if ((version.sizeBytes ?: 0) > Goal.MAX_BYTES) throw GoalError.Invalid("This goal is too large to read.")
    val goal = Goal.parse(fileVersionBytes(workspace, app, file.id, version))
    return GoalDocument(file, version.id, goal)
}

suspend fun TowerAPI.createLegacyGoal(workspace: String, app: String, channelId: String, goal: Goal): GoalDocument {
    goal.validated()
    val bytes = goal.encode().toByteArray(Charsets.UTF_8)
    currentCoroutineContext().ensureActive()
    val objectId = store(workspace, app, bytes, Goal.MIME, GoalFiles.fileName(goal), null) { }
    currentCoroutineContext().ensureActive()
    val reply = obj(client.request(method = "POST", path = TowerAPI.base(workspace) + "/channels/" + TowerAPI.uuid(channelId) + "/files", appNpub = app,
        jsonBody = jsonBody(jsonObjectOf("storage_object_id" to objectId, "display_name" to GoalFiles.fileName(goal), "metadata" to GoalFiles.metadata()))), "goal file")
    val file = reply.obj("file")?.let(FileRecord::parse) ?: throw TowerError.Malformed("goal file")
    if (file.workspaceId != workspace || file.channelId != channelId) throw TowerError.Malformed("goal file")
    return readLegacyGoal(workspace, app, file.id)
}

/** The edit lost every attempt to concurrent saves, or Tower refused it. The goal is unchanged; nothing was overwritten. */
class GoalSaveFailed(message: String, val retryable: Boolean) : Exception(message, null, false, false)

/**
 * Applies [edits] to the newest saved goal and saves them as one new version against that version. If another save wins
 * the race (`stale_base_version`) the edits are re-applied to the new head, so independent changes from two devices
 * or an agent both survive; an edit whose expectation no longer holds throws [GoalError.Conflict] and saves nothing.
 */
suspend fun TowerAPI.editLegacyGoal(workspace: String, app: String, fileId: String, edits: List<GoalEdit>, attempts: Int = 4): GoalDocument {
    require(edits.isNotEmpty())
    var head = readLegacyGoal(workspace, app, fileId)
    repeat(attempts) {
        currentCoroutineContext().ensureActive()
        val next = GoalEdit.applyAll(head.goal, edits)
        if (next == head.goal) return head
        val bytes = next.encode().toByteArray(Charsets.UTF_8)
        val objectId = store(workspace, app, bytes, Goal.MIME, GoalFiles.fileName(next), null) { }
        when (val outcome = addFileVersion(workspace, app, fileId, head.versionId, objectId, UUID.randomUUID().toString())) {
            is VersionOutcome.Saved -> {
                // Verify the exact version we wrote, not whatever is newest now: a later save by someone else is not our failure.
                val savedId = outcome.versionId ?: outcome.file.currentVersionId ?: throw GoalSaveFailed("Saved, but the new version could not be identified. Reload to check.", retryable = true)
                val recorded = outcome.file.file.raw.obj("current_version")?.takeIf { lowerUUID(it.string("id")) == savedId }?.string("sha256_hex")
                if (recorded != null && recorded.lowercase() != Sha256Hex.of(bytes)) throw GoalSaveFailed("The saved goal did not match what was sent. Reload to check.", retryable = false)
                return GoalDocument(outcome.file.file, savedId, next)
            }
            is VersionOutcome.Conflict -> head = readLegacyGoal(workspace, app, fileId)
            is VersionOutcome.Denied -> throw GoalSaveFailed(outcome.message, retryable = false)
            is VersionOutcome.Retryable -> throw GoalSaveFailed(outcome.message, retryable = true)
        }
    }
    throw GoalSaveFailed("This goal is being changed elsewhere. Try again in a moment.", retryable = true)
}

/** Native references keep their original File UUID while repository authority selects current storage. */
suspend fun TowerAPI.goalFiles(workspace:String,app:String,channelId:String):List<FileRecord> {
    val repository=goalsRelational ?: return legacyGoalFiles(workspace,app,channelId)
    return when(repository.mode(channelId)) {
        "legacy" -> legacyGoalFiles(workspace,app,channelId)
        "relational" -> repository.list(channelId).map { it.file }
        else -> throw GoalSaveFailed("Goals are read-only during migration. Your saved content is retained.",true)
    }
}
suspend fun TowerAPI.readGoal(workspace:String,app:String,fileId:String):GoalDocument =
    goalsRelational?.read(fileId) ?: readLegacyGoal(workspace,app,fileId)
suspend fun TowerAPI.readGoal(workspace:String,app:String,file:FileRecord):GoalDocument =
    goalsRelational?.read(file.id) ?: readLegacyGoal(workspace,app,file)
suspend fun TowerAPI.createGoal(workspace:String,app:String,channelId:String,goal:Goal):GoalDocument =
    goalsRelational?.create(channelId,goal) ?: createLegacyGoal(workspace,app,channelId,goal)
suspend fun TowerAPI.editGoal(workspace:String,app:String,fileId:String,edits:List<GoalEdit>,attempts:Int=4):GoalDocument =
    goalsRelational?.edit(fileId,edits,attempts) ?: editLegacyGoal(workspace,app,fileId,edits,attempts)
