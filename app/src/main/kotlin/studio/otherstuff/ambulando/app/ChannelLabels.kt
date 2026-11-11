package studio.otherstuff.ambulando.app

import androidx.compose.runtime.*
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.store.*

/** Display only: profile updates must never replace channel ids, stored titles or composer targets. */
@Composable internal fun channelLabels(model: AppModel): Map<String, String> {
    val workspace = model.workspace ?: return emptyMap()
    val connection = model.connection ?: return emptyMap()
    val people by remember(model.activeBoundaryId, connection.id, workspace.id) {
        model.store.liveQuery({ it.workspaceId == workspace.id || it.table == StoreTable.PROFILES }) {
            model.store.reader.people(connection.id, workspace.id)
        }
    }.collectAsState(null)
    return model.channels.associate { row -> row.id to SidebarNavigation.label(
        NavChannel(row.id, row.scopeId, row.name, row.kind, row.position, row.raw), connection.signerNpub, people?.names ?: emptyMap()) }
}
