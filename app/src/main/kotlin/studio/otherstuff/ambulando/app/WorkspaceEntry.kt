package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.workspaces.NavSnapshot
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import studio.otherstuff.ambulando.core.workspaces.WorkspaceColour
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntime

/**
 * One workspace of one connection as the person sees it in the chooser and the sidebar: its exact boundary, whether it is
 * enabled (kept in sync) or paused, its saved presentation (collapse, order, colour) and the navigation read from its own
 * partition. Presentation never decides routing; every action carries the entry's boundary.
 */
@Stable
class WorkspaceEntry(val boundary: WorkspaceBoundary, connection: ConnectionRecord, workspace: WorkspaceRecord) {
    val id: String get() = boundary.id
    var connection by mutableStateOf(connection)
    var workspace by mutableStateOf(workspace)
    var enabled by mutableStateOf(false)
    var collapsed by mutableStateOf(false)
    var colour by mutableStateOf(WorkspaceColour.BLUE)
    var nav by mutableStateOf(NavSnapshot())
    var runtime: WorkspaceRuntime? by mutableStateOf(null)

    /** The partition observation that fills [nav]; owned by the model. */
    internal var navJob: Job? = null
    internal var navStorePath: String? = null
}
