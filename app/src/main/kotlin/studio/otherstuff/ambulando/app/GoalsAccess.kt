package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.GoalsController
import studio.otherstuff.ambulando.core.goals.GoalsTransport
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.sync.SyncPriority

private val controllers = HashMap<String, GoalsController>()

/**
 * The Goals controller for the workspace in front, shared by the list, a goal and "Add to goal" so they show one state.
 * It is rebuilt when the boundary or signer changes; its calls are signed by the connection's own unlocked identity and
 * refuse to run once the workspace is switched, locked or has lost access.
 */
@Composable internal fun rememberGoals(model: AppModel): GoalsController? {
    val c = model.connection ?: return null; val w = model.workspace ?: return null
    val boundary = model.activeBoundaryId ?: return null
    val runtime = model.active ?: return null
    val owner=studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w)
    return remember(runtime, boundary, c.id, c.signerNpub, w.id, owner) { model.goalsController() }
}

internal fun AppModel.goalsController(): GoalsController? {
    val model = this
    val c = connection ?: return null; val w = workspace ?: return null
    val boundary = activeBoundaryId ?: return null; val runtime = active ?: return null
    val owner = studio.otherstuff.ambulando.core.surfaces.localContentOwner(c, w)
    return run {
        val key = "$boundary|${c.id}|${c.signerNpub}|${w.id}|$owner|${System.identityHashCode(runtime)}"
        synchronized(controllers) {
            controllers.keys.removeAll { it.startsWith("$boundary|") && it != key }
            controllers.getOrPut(key) {
                val reads = object : HttpTransport {
                    override suspend fun send(request: HttpRequest): HttpResponse = runtime.engine.perform("goals-read:${request.method}:${request.url}", SyncPriority.USER) {
                        runtime.readTransport.send(request)
                    }
                }
                GoalsController(model.fileNappletAPI(c, GoalsTransport(reads, runtime.directTransport)), w.id, w.appNpub, c.signerNpub, studio.otherstuff.ambulando.core.goals.GoalsLocalContent(runtime.store, w.id, c.signerNpub, owner, model.fileCache())) {
                    model.active === runtime && model.activeBoundaryId == boundary && model.connection?.id == c.id && model.workspace?.id == w.id &&
                        model.workspace?.accessRemoved == false && (model.identityState as? IdentityState.Unlocked)?.npub == c.signerNpub &&
                        model.connection?.let { connection -> model.workspace?.let { workspace -> studio.otherstuff.ambulando.core.surfaces.localContentOwner(connection,workspace)==owner } }==true
                }
            }
        }
    }
}
