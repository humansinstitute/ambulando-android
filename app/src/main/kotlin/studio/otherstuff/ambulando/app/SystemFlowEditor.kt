package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.UUID

/** Provenance is a link, never permission to hydrate external content inside an editor. */
@Composable internal fun ArtefactProvenance(file: FileRecord) {
    val migration = file.raw.obj("metadata")?.obj("migration") ?: return
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("artefacts.provenance")) {
        Text("Imported source · ${migration.string("source_version") ?: "selected version"} · older history remains in the original")
        (migration["limitations"] as? JsonArray)?.forEach { Text(it.jsonPrimitive.content) }
        migration.string("source_url")?.takeIf { value -> runCatching { val parsed = java.net.URI(value); parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank() && parsed.userInfo == null }.getOrDefault(false) }?.let { url ->
            TextButton(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.testTag("artefacts.original")) { Text("Open original source") }
        }
    }
}

@Composable internal fun SystemFlowFile(model: AppModel, file: FileRecord) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    studio.otherstuff.ambulando.app.ui.shell.OwnEditorNavigation()
    val exit = studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    BackHandler(enabled = studio.otherstuff.ambulando.app.ui.shell.LocalEditorActive.current) { exit?.invoke() }
    val runtime = model.active ?: return
    val controller = remember(runtime, c, w, file.id) {
        val api = model.fileNappletAPI(c).fencedArtefactAPI(runtime.store.reader,w,file.id) {
            model.active === runtime && !runtime.workspace.accessRemoved && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked
        }
        SystemFlowController(object : SystemFlowController.Transport {
            override suspend fun head(): Pair<Boolean, FileVersionList> {
                return runtime.sync.genericNappletRead(file.id) {
                    val fresh = api.artefactFile(w.id, w.appNpub, file.id)
                    check(ArtefactType.of(fresh) == ArtefactType.SYSTEM_FLOW && !fresh.accessRemoved && !fresh.archived)
                    (fresh.raw.bool("can_write") == true) to api.fileVersions(w.id, w.appNpub, file.id)
                }
            }
            override suspend fun bytes(version: FileVersionRecord): ByteArray {
                require(version.sha256 != null && version.sizeBytes in 1..SystemFlow.MAX_BYTES.toLong()) { "System Flow requires a bounded, hash-verified version." }
                return runtime.sync.genericNappletRead(file.id) { api.fileVersionBytes(w.id, w.appNpub, file.id, version) }
            }
            override suspend fun save(base: String, bytes: ByteArray): VersionOutcome {
                val objectId = api.store(w.id, w.appNpub, bytes, ArtefactType.SYSTEM_FLOW.mime, "flow.json", null) { }
                return api.addFileVersion(w.id, w.appNpub, file.id, base, objectId, UUID.randomUUID().toString())
            }
        }, File(model.htmlDraftFile(file.id).parentFile, "flow-${file.id}.json"))
    }
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.open() }
    var exporting by remember(runtime, file.id) { mutableStateOf(false) }
    if (exporting) ArtefactFileActions(model, file) { exporting = false }
    SystemFlowEditor(state, file.displayName, { action -> model.act { action(controller) } }, file.raw.obj("metadata")?.obj("artefact")?.string("style_id") ?: "default", onBack = { exit?.invoke() }, onExport = { exporting = true }) { ArtefactProvenance(file) }
}

/** Native typed controls, no arbitrary code/runtime or host bridge. Complete scene collections stay opaque and intact. */
@Composable internal fun SystemFlowEditor(state: SystemFlowController.State, title: String,
    act: (suspend (SystemFlowController) -> Unit) -> Unit, style: String = "default", onBack: () -> Unit = {}, onExport: (() -> Unit)? = null, provenance: @Composable () -> Unit = {}) {
    var diagram by remember { mutableIntStateOf(0) }
    var versions by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var nodeId by remember { mutableStateOf("") }; var nodeLabel by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }
    val flow = state.flow
    val editable = state.canWrite && state.viewing == null && !state.busy
    val cleanSaved = !state.busy && !state.dirty && state.canWrite && state.viewing == null && state.message.startsWith("Saved version ")
    // Back uses the normal File route. Every accepted field edit already has a durable draft.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("flow.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back (keep draft)") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("flow.title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(enabled = editable && state.dirty, onClick = { act { it.save() } }, modifier = Modifier.testTag("flow.save")) { Text("Save") }
            Box {
                IconButton(onClick = { versions = true }, modifier = Modifier.testTag("flow.versions")) { Icon(Icons.Outlined.MoreHoriz, "Flow options") }
                DropdownMenu(versions, { versions = false }) {
                    provenance()
                    onExport?.let { export -> DropdownMenuItem(text = { Text("Download / share File") }, onClick = { versions = false; export() }, modifier = Modifier.testTag("artefacts.export")) }
                    if (cleanSaved) Text(state.message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("flow.status"))
                    Text("Typed node and relationship editing. Linked whiteboard scenes and feedback stay intact; use the original for those edits.",
                        Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                    DropdownMenuItem(text = { Text("Latest / resume draft") }, enabled = !state.busy,
                        onClick = { versions = false; diagram = 0; act { it.open() } }, modifier = Modifier.testTag("flow.latest"))
                    state.versions.reversed().forEach { v ->
                        DropdownMenuItem(text = { Text("Version ${v.versionNumber} · read-only") }, enabled = !state.busy,
                            onClick = { versions = false; diagram = 0; act { it.open(v.id) } }, modifier = Modifier.testTag("flow.version.${v.versionNumber}"))
                    }
                    if (state.dirty) DropdownMenuItem(text = { Text("Discard draft") }, enabled = !state.busy,
                        onClick = { versions = false; discard = true }, modifier = Modifier.testTag("flow.discard"))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!cleanSaved) Text(state.message.ifBlank { "Loading System Flow…" }, modifier = Modifier.testTag("flow.status"))
        if (flow != null) {
            val diagrams = SystemFlow.diagrams(flow)
            diagrams.forEachIndexed { index, d ->
                FilterChip(diagram == index, { diagram = index }, label = { Text(d.string("title") ?: "Main flow") }, modifier = Modifier.testTag("flow.diagram.$index"))
            }
            val at = diagram.coerceIn(diagrams.indices)
            val nodes = SystemFlow.items(flow, at, "nodes")
            val edges = SystemFlow.items(flow, at, "edges")
            FlowOverview(diagrams[at], style)
            Text("Nodes", style = MaterialTheme.typography.titleMedium)
            nodes.forEach { n ->
                val id = n.string("id")!!; val data = n.obj("data")!!
                fun change(key: String, value: String) = act { controller -> controller.edit { current ->
                    SystemFlow.updateItems(current, at, "nodes", SystemFlow.items(current, at, "nodes").map { old ->
                        if (old.string("id") == id) JsonObject(old + ("data" to JsonObject(old.obj("data")!! + (key to JsonPrimitive(value))))) else old
                    })
                } }
                Text("$id · ${data.string("kind")}")
                FlowField(data.string("label") ?: "", { if (it.length <= 200) change("label", it) }, enabled = editable,
                    label = { Text("Label") }, modifier = Modifier.fillMaxWidth().testTag("flow.node.$id.label"))
                FlowField(data.string("owner") ?: "", { if (it.length <= 200) change("owner", it) }, enabled = editable,
                    label = { Text("Owner") }, modifier = Modifier.fillMaxWidth().testTag("flow.node.$id.owner"))
                FlowField(data.string("description") ?: "", { if (it.length <= 5000) change("description", it) }, enabled = editable,
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth().testTag("flow.node.$id.description"))
                // Linked whiteboard and system container kinds have structural constraints and remain intact.
                if (data.string("kind") !in listOf("whiteboard", "system")) {
                    var kinds by remember(id) { mutableStateOf(false) }
                    TextButton(enabled = editable, onClick = { kinds = !kinds }) { Text("Kind: ${data.string("kind")}") }
                    if (kinds) SystemFlow.kinds.filter { it !in listOf("whiteboard", "system") }.forEach { kind ->
                        TextButton(enabled = editable, onClick = { change("kind", kind); kinds = false }) { Text(kind) }
                    }
                }
                data.string("detailFlowId")?.let { Text("Linked diagram: $it (retained)") }
                data.string("whiteboardId")?.let { Text("Linked whiteboard: $it (retained)") }
                HorizontalDivider()
            }
            if (editable) {
                OutlinedTextField(nodeId, { nodeId = it }, label = { Text("New node ID") }, modifier = Modifier.testTag("flow.newId"))
                OutlinedTextField(nodeLabel, { nodeLabel = it }, label = { Text("New node label") }, modifier = Modifier.testTag("flow.newLabel"))
                TextButton(onClick = { val id = nodeId; val label = nodeLabel; act { it.edit { f -> SystemFlow.updateItems(f, at, "nodes", SystemFlow.items(f, at, "nodes") + SystemFlow.node(id, "action", label)) } } }, modifier = Modifier.testTag("flow.addNode")) { Text("Add action node") }
            }
            Text("Relationships", style = MaterialTheme.typography.titleMedium)
            edges.forEach { edge ->
                val id = edge.string("id")!!
                Text("${edge.string("source")} → ${edge.string("target")} · ${edge.string("relationship") ?: "flow"}")
                FlowField(edge.string("label") ?: "", { label -> if (label.length <= 500) act { it.edit { f ->
                    SystemFlow.updateItems(f, at, "edges", SystemFlow.items(f, at, "edges").map { old -> if (old.string("id") == id) JsonObject(old + ("label" to JsonPrimitive(label))) else old })
                } } }, enabled = editable, label = { Text("Relationship label") }, modifier = Modifier.fillMaxWidth().testTag("flow.edge.$id.label"))
            }
            if (editable) {
                OutlinedTextField(from, { from = it }, label = { Text("From node ID") }, modifier = Modifier.testTag("flow.from"))
                OutlinedTextField(to, { to = it }, label = { Text("To node ID") }, modifier = Modifier.testTag("flow.to"))
                TextButton(onClick = { val source = from; val target = to; act { it.edit { f -> SystemFlow.updateItems(f, at, "edges", SystemFlow.items(f, at, "edges") + SystemFlow.edge("edge-${UUID.randomUUID()}", source, target)) } } }, modifier = Modifier.testTag("flow.addEdge")) { Text("Add flow relationship") }
            }
        }
    }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this device’s unsaved flow draft?") },
        confirmButton = { TextButton(onClick = { discard = false; diagram = 0; act { it.discard(); it.open() } }, modifier = Modifier.testTag("flow.discard.confirm")) { Text("Discard and reload") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep draft") } })
}


/** Local text owns IME composition while ordered controller edits persist. Remote/history values apply when unfocused. */
@Composable private fun FlowField(value: String, onValueChange: (String) -> Unit, enabled: Boolean,
    label: @Composable () -> Unit, modifier: Modifier) {
    var local by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) local = value }
    OutlinedTextField(if (focused && enabled) local else value, { local = it; onValueChange(it) }, enabled = enabled, label = label,
        modifier = modifier.onFocusChanged { focused = it.isFocused })
}

/** Readable overview of original positions, containers and directed relationships; fields expose complete labels. */
@Composable private fun FlowOverview(diagram: JsonObject, style: String) {
    val nodes = (diagram["nodes"] as JsonArray).map { it.jsonObject }
    val edges = (diagram["edges"] as JsonArray).map { it.jsonObject }
    val byId = nodes.associateBy { it.string("id") }
    fun number(o: JsonObject, key: String, fallback: Float) = (o[key] as? JsonPrimitive)?.floatOrNull ?: fallback
    fun position(n: JsonObject, depth: Int = 0): Offset {
        val p = n.obj("position")!!
        val own = Offset(number(p, "x", 0f), number(p, "y", 0f))
        val parent = n.string("parentId")?.let { byId[it] }
        return if (parent != null && depth < nodes.size) own + position(parent, depth + 1) else own
    }
    val paper = if (style == "paper") Color.White else Color(0xfffaf8fc)
    val accent = if (style == "paper") Color(0xff35655c) else Color(0xff70509a)
    Canvas(Modifier.fillMaxWidth().height(300.dp).testTag("flow.overview").semantics {
        contentDescription = "System Flow overview: ${nodes.size} nodes and ${edges.size} directed relationships. Complete labels and links follow below."
    }) {
        drawRect(paper)
        if (nodes.isEmpty()) return@Canvas
        val left = nodes.minOf { position(it).x }; val top = nodes.minOf { position(it).y }
        val right = nodes.maxOf { position(it).x + number(it, "width", 180f) }; val bottom = nodes.maxOf { position(it).y + number(it, "height", 80f) }
        val scale = minOf((size.width - 32f) / (right - left).coerceAtLeast(1f), (size.height - 32f) / (bottom - top).coerceAtLeast(1f))
        fun origin(n: JsonObject) = Offset(16f + (position(n).x - left) * scale, 16f + (position(n).y - top) * scale)
        fun center(n: JsonObject) = origin(n) + Offset(number(n, "width", 180f) * scale / 2, number(n, "height", 80f) * scale / 2)
        nodes.filter { it.obj("data")?.string("kind") == "system" }.forEach { n ->
            drawRect(accent.copy(alpha = .08f), origin(n), androidx.compose.ui.geometry.Size(number(n, "width", 180f) * scale, number(n, "height", 80f) * scale))
        }
        edges.forEach { e ->
            val source = byId[e.string("source")] ?: return@forEach
            val target = byId[e.string("target")] ?: return@forEach
            val a = center(source); val b = center(target); val delta = b - a
            val length = delta.getDistance().coerceAtLeast(1f)
            val unit = delta / length
            val distance = minOf(number(target, "width", 180f) * scale / (2 * kotlin.math.abs(unit.x).coerceAtLeast(.0001f)),
                number(target, "height", 80f) * scale / (2 * kotlin.math.abs(unit.y).coerceAtLeast(.0001f)))
            val end = b - unit * distance
            drawLine(accent, a, end, 2f)
            val perpendicular = Offset(-unit.y, unit.x)
            val path = Path().apply { moveTo(end.x, end.y); val p = end - unit * 9f + perpendicular * 4f; lineTo(p.x, p.y); val q = end - unit * 9f - perpendicular * 4f; lineTo(q.x, q.y); close() }
            drawPath(path, accent)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (style == "paper") android.graphics.Color.rgb(32,33,36) else android.graphics.Color.rgb(43,27,60)
            textSize = (14f * scale).coerceIn(8f, 22f)
        }
        nodes.forEach { n ->
            val p = origin(n); val w = number(n, "width", 180f) * scale; val h = number(n, "height", 80f) * scale
            val container = n.obj("data")?.string("kind") == "system"
            if (!container) drawRoundRect(paper, p, androidx.compose.ui.geometry.Size(w, h))
            drawRect(accent, p, androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.5f))
            val label = n.obj("data")?.string("label") ?: ""
            val max = ((w - 8f) / (paint.textSize * .6f)).toInt().coerceAtLeast(1)
            drawContext.canvas.nativeCanvas.drawText(label.take(max), p.x + 4, p.y + paint.textSize + 4, paint)
        }
    }
}
