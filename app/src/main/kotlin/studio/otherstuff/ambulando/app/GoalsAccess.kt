package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.GoalsController
import studio.otherstuff.ambulando.core.goals.GoalsTransport
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.SyncPriority

private val controllers = HashMap<String, GoalsController>()

/**
 * The Goals controller for the workspace in front, shared by the list, a goal and "Add to goal" so they show one state.
 * It is rebuilt when the boundary or signer changes; its calls are signed by the connection's own unlocked identity and
 * refuse to run once the workspace is switched, locked or has lost access.
 */
@Composable internal fun rememberGoals(model: AppModel, allAccessible: Boolean = false): GoalsController? {
    val c = model.connection ?: return null; val w = model.workspace ?: return null
    val boundary = model.activeBoundaryId ?: return null
    val runtime = model.active ?: return null
    val owner=studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w)
    return remember(runtime, boundary, c.id, c.signerNpub, w.id, owner, allAccessible) { model.goalsController(allAccessible) }
}

internal fun AppModel.goalsController(allAccessible: Boolean = false): GoalsController? {
    val model = this
    val c = connection ?: return null; val w = workspace ?: return null
    val boundary = activeBoundaryId ?: return null; val runtime = active ?: return null
    val owner = studio.otherstuff.ambulando.core.surfaces.localContentOwner(c, w)
    return run {
        val key = "$boundary|${c.id}|${c.signerNpub}|${w.id}|$owner|${System.identityHashCode(runtime)}|$allAccessible"
        synchronized(controllers) {
            controllers.keys.removeAll { it.startsWith("$boundary|") && it != key && it.endsWith("|$allAccessible") }
            controllers.getOrPut(key) {
                val reads = object : HttpTransport {
                    override suspend fun send(request: HttpRequest): HttpResponse = runtime.engine.perform("goals-read:${request.method}:${request.url}:${request.body?.let { studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(it) }.orEmpty()}", SyncPriority.USER) {
                        runtime.readTransport.send(request)
                    }
                }
                GoalsController(model.fileNappletAPI(c, GoalsTransport(reads, runtime.directTransport)), w.id, w.appNpub, c.signerNpub, if (allAccessible) null else studio.otherstuff.ambulando.core.goals.GoalsLocalContent(runtime.store, w.id, c.signerNpub, owner, model.fileCache()), allAccessible) {
                    model.active === runtime && model.activeBoundaryId == boundary && model.connection?.id == c.id && model.workspace?.id == w.id &&
                        model.workspace?.accessRemoved == false && (model.identityState as? IdentityState.Unlocked)?.npub == c.signerNpub &&
                        model.connection?.let { connection -> model.workspace?.let { workspace -> studio.otherstuff.ambulando.core.surfaces.localContentOwner(connection,workspace)==owner } }==true
                }
            }
        }
    }
}

/** Bound repository state is separate from retained File metadata/history. An observed SQL epoch never falls back. */
internal fun AppModel.bindGoals(api: TowerAPI, c: studio.otherstuff.ambulando.core.store.ConnectionRecord): TowerAPI {
    val runtime=active ?: return api
    val w=workspace ?: return api
    val owner=w.ownerNpub ?: return api
    if(runtime.connection.id!=c.id) return api
    val boundary=activeBoundaryId
    val binding=studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w)
    val key="goals.relational.observed.v1:${c.signerNpub}:${w.id}:$binding"
    fun current()=active===runtime && activeBoundaryId==boundary && workspace?.id==w.id && workspace?.accessRemoved==false && connection?.id==c.id &&
        (identityState as? IdentityState.Unlocked)?.npub==c.signerNpub && connection?.let { latest -> workspace?.let { studio.otherstuff.ambulando.core.surfaces.localContentOwner(latest,it)==binding } }==true
    api.goalsRelational=studio.otherstuff.ambulando.core.goals.GoalsRelational(api,owner,w.id,w.appNpub,c.signerNpub,
        rememberAuthority={ check(current());runtime.store.writer.setState(key,"observed") },
        hadAuthority={runtime.store.reader.state(key)!=null}, current=::current,
        loadPending={ runtime.store.reader.state("$key:pending")?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject } },
        savePending={ check(current()); runtime.store.writer.setState("$key:pending",it?.toString()) },
        rememberChannelAuthority={ channel -> check(current()); runtime.store.writer.setState("$key:channel:$channel", "observed") },
        hadChannelAuthority={ channel ->
            runtime.store.reader.state("$key:channel:$channel") != null ||
                // Upgrade from the workspace-only marker: retain the known SQL home before new channel markers exist.
                (runtime.store.reader.state(key) != null &&
                    (studio.otherstuff.ambulando.core.goals.GoalsLocalContent(runtime.store,w.id,c.signerNpub,binding).load()
                        ?.space as? studio.otherstuff.ambulando.core.goals.GoalsSpaceState.Ready)?.channel?.id == channel)
        })
    return api
}
