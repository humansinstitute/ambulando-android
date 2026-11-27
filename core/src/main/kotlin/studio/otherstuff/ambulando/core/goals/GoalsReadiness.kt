package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.flow.collectLatest
import studio.otherstuff.ambulando.core.sync.WorkspaceActivity

/** A resumed read generation hydrates automatically, even when the screen opened before the runtime was ready. */
suspend fun GoalsController.followReadiness(activity: WorkspaceActivity) {
    activity.state.collectLatest { readiness ->
        if (readiness.open) refresh() else hydrate()
    }
}
