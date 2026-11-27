package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.GoalsController

private val controllers = HashMap<String, GoalsController>()

/**
 * The Goals controller for the workspace in front, shared by the list, a goal and "Add to goal" so they show one state.
 * It is rebuilt when the boundary or signer changes; its calls are signed by the connection's own unlocked identity and
 * refuse to run once the workspace is switched, locked or has lost access.
 */
@Composable internal fun rememberGoals(model: AppModel): GoalsController? {
    val c = model.connection ?: return null; val w = model.workspace ?: return null
    val boundary = model.activeBoundaryId ?: return null
    val owner=studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w)
    return remember(boundary, c.id, c.signerNpub, w.id, owner) {
        val key = "$boundary|${c.id}|${c.signerNpub}|${w.id}|$owner"
        synchronized(controllers) {
            controllers.keys.removeAll { it.startsWith("$boundary|") && it != key }
            controllers.getOrPut(key) {
                GoalsController(model.fileNappletAPI(c), w.id, w.appNpub, c.signerNpub, studio.otherstuff.ambulando.core.goals.GoalsLocalContent(model.store, w.id, c.signerNpub, owner)) {
                    model.activeBoundaryId == boundary && model.connection?.id == c.id && model.workspace?.id == w.id &&
                        model.workspace?.accessRemoved == false && (model.identityState as? IdentityState.Unlocked)?.npub == c.signerNpub &&
                        model.connection?.let { connection -> model.workspace?.let { workspace -> studio.otherstuff.ambulando.core.surfaces.localContentOwner(connection,workspace)==owner } }==true
                }
            }
        }
    }
}
