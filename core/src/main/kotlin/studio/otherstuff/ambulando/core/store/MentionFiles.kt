package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.transport.*

/** Complete authorized channel discovery, independent of visiting the Files screen or its first page. */
suspend fun LocalStore.refreshMentionFiles(api: TowerAPI, workspace: String, app: String, channels: List<String>, onRefreshed: (String) -> Unit = {}, current: () -> Boolean) {
    for (channel in channels.distinct()) {
        check(current()) { "The workspace changed. Search again." }
        try {
            val files = api.client.list(TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channel)}/files", app, "files").map(FileRecord::parse)
            require(files.all { it.workspaceId == workspace && it.channelId == channel })
            check(current()) { "The workspace changed. Search again." }
            writer.upsertFiles(workspace, files, completeChannel = channel)
            onRefreshed(channel)
        } catch (e: TowerError.Http) {
            if (e.status !in setOf(403, 404)) throw e
            check(current())
            for (file in reader.files(workspace, channel)) writer.markAccessRemoved(workspace, AccessTarget.File(file.id))
        }
    }
}
