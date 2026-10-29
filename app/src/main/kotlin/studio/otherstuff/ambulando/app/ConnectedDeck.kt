package studio.otherstuff.ambulando.app

import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.deck.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.context.*
import studio.otherstuff.ambulando.core.deck.refreshPersonalAgentLaunchers
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.ViewStateRecord

@Composable internal fun ConnectedDeck(model: AppModel) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val screen = ScreenScope(c.id, w.id, w.appNpub, c.signerNpub, model.context.scopeId, model.context.channelId)
    val navigation = WorkNavigation(model.scopes.map { s -> SidebarGroup(NavScope(s.id, s.name, s.kind), model.channels.filter { it.scopeId == s.id }.map { NavChannel(it.id, it.scopeId, it.name, it.kind) }, SidebarNavigation.isDirectMessageScope(NavScope(s.id, s.name, s.kind))) }, model.channels.associate { it.id to it.name }, model.context)
    val pinsState = remember(c.id, w.id) { PinsState(model.store, c.id, w.id, scope) }
    DisposableEffect(pinsState) { pinsState.start(); onDispose { pinsState.stop() } }
    val pins by pinsState.state.collectAsState()
    val intents = remember(screen) { object : DeckIntents {
        override suspend fun movePin(target: PinRecord, before: PinRecord?): Result<Unit> = runCatching {
            model.engine.perform("pin-order:${w.id}", SyncPriority.USER) {
                check(model.store.reader.workspaceWrites(c.id, w.id).none { it.kind == WriteKind.PIN_SET || it.kind == WriteKind.PIN_ORDER }) { "Wait for pending pin changes to synchronize (or discard a failed move)." }
                check(model.towerAPI(c).supportsPinOrdering(w.id, w.appNpub)) { "Pin ordering needs an updated Tower connection." }
                fun ref(pin: PinRecord) = buildJsonObject {
                    put("resource_type", pin.resourceType.wire); put("resource_id", pin.resourceId); put("expected_row_version", pin.rowVersion)
                }
                model.writes.submit(WriteIntent(WriteKind.PIN_ORDER, null, buildJsonObject {
                    ref(target).forEach { (key, value) -> put(key, value) }
                    put("before", before?.let(::ref) ?: JsonNull); put("mutation_id", java.util.UUID.randomUUID().toString())
                }), c, w)
            }
        }
        override fun refreshInbox(context: WorkContext) { model.refresh() }
        override fun markRead(resources: List<ViewStateRecord>) {
            resources.chunked(500).forEach { batch -> model.submit(WriteIntent(WriteKind.VIEW_MARK, null, buildJsonObject {
                put("resources", JsonArray(batch.map { row -> buildJsonObject { put("resource_type", row.resourceType.wire); put("resource_id", row.resourceId); put("viewed_activity_version", row.activityVersion) } }))
            })) }
        }
        override fun markDone(taskId: String) { model.submit(WriteIntent(WriteKind.TASK_STATE, taskId, buildJsonObject { put("state", "done") })) }
        override fun openInChat(channelId: String, threadId: String?) {
            model.channels.firstOrNull { it.id == channelId }?.let { model.selectContext(it.scopeId, it.id) }
            model.section = AppSection.CHAT
            threadId?.let { services.open(WorkRoute.Thread(w.id, channelId, it)) }
        }
        override fun refreshCanvases(force: Boolean) { model.act { model.sync.refreshCanvases(c, w, force = force) } }
        override suspend fun createCanvas(title: String): Result<String> = runCatching {
            model.engine.perform("canvas-create:${w.id}", SyncPriority.USER) {
                StoreCanvasEditBackend(model.store, model.identity, model.transport, c, w,
                    { model.sync.refreshCanvases(c, w, force = true) }, { model.sync.refreshCanvas(c, w, it) }).editor().createCanvas(title).id
            }
        }
        override fun refreshLaunchers(force: Boolean) { model.act { model.sync.refreshToolbox(c, w, force = force); model.sync.refreshPersonalAgentLaunchers(c, w, force) } }
        override fun launchApp(url: String) { services.openUrl(url) }
        override fun showToolbox() { model.section = AppSection.TOOLBOX }
        override fun setPinned(pinned: Boolean, type: PinRecord.ResourceType, id: String) {
            model.act {
                val old = model.store.reader.pin(c.id, w.id, type, id)
                model.writes.submit(WriteIntent(WriteKind.PIN_SET, id, buildJsonObject {
                    put("resource_type", type.wire); put("pinned", pinned); put("expected_row_version", old?.rowVersion ?: 0); put("mutation_id", java.util.UUID.randomUUID().toString())
                }), c, w)
            }
        }
        override fun retryPinWrite(writeId: String) { model.act { model.writes.retry(writeId, c) } }
        override fun discardPinWrite(writeId: String) { model.act { model.store.writer.discardWrite(writeId) } }
    } }
    LaunchedEffect(c.id, w.id) { model.sync.refreshPins(c, w) }
    DeckScreen(model.store, screen, navigation, pins.value, intents, active = model.section == AppSection.DECK)
}
