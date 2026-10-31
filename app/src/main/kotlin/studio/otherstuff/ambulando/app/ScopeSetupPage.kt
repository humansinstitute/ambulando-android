package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.management.AdministrationSuperseded
import studio.otherstuff.ambulando.core.management.ManagementError
import studio.otherstuff.ambulando.core.management.ScopeChannelManager
import studio.otherstuff.ambulando.core.management.ScopeSetup

/**
 * New scope with starting channels. The page is bound to the exact workspace it was opened for: it uses that runtime's own
 * partition and write-gated transport, keeps its draft per connection and workspace, and applies no result once another
 * workspace is in front. Each creation is its own confirmed online step; interrupted setup resumes from the saved scope.
 */
@Composable
internal fun ScopeSetupPage(model: AppModel, back: () -> Unit) {
    val runtime = model.active ?: return
    val boundaryId = runtime.boundary.id
    val c = runtime.connection; val w = runtime.workspace
    val current = { model.activeBoundaryId == boundaryId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember(boundaryId) { ScopeSetup(ScopeChannelManager(runtime.store, model.identity, runtime.directTransport), runtime.store, c, w, current) }
    val draft = remember(boundaryId) { mutableStateMapOf<String, String>() }
    var loaded by remember(boundaryId) { mutableStateOf(false) }
    var status by remember(boundaryId) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var working by remember(boundaryId) { mutableStateOf(false) }
    var confirm by remember(boundaryId) { mutableStateOf(false) }
    var existing by remember(boundaryId) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(boundaryId) { setup.load().let { stored -> if (current()) { draft.putAll(stored); loaded = true } } }
    LaunchedEffect(draft.toMap(), loaded) { if (loaded) { delay(150); setup.persist(draft.toMap()) } }
    fun step(label: String, block: suspend () -> String) {
        working = true; status = label to false
        scope.launch {
            try {
                val done = block()
                if (current()) status = done to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdministrationSuperseded) {
            } catch (e: Exception) {
                if (current()) status = ((e as? ManagementError)?.message ?: ("Something went wrong: " + (e.message ?: e::class.simpleName))) to true
            } finally {
                if (current()) working = false
                if (current()) model.refresh(force = true)
            }
        }
    }
    fun template(): String? = runCatching { context.assets.open("scope-product-template.json").bufferedReader().use { it.readText() } }.getOrNull()
    BackHandler(onBack = back)
    if (!current()) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("admin.scopeSetup.stale")) {
            Text("Workspace changed. Close management and reopen it for the workspace you want. Nothing was changed.")
            TextButton(onClick = back) { Text("Back") }
        }
        return
    }
    val captured = draft["scope"]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("admin.scopeSetup"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = back, modifier = Modifier.testTag("admin.back")) { Text("Back") }
        Text("New scope setup", style = MaterialTheme.typography.titleLarge)
        Text(w.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("admin.workspace"))
        Text("Creates a top-level scope, then its starting channels. Each step is saved separately. Interrupted setup keeps its progress on this device. Nothing is queued offline.", style = MaterialTheme.typography.bodySmall)
        val locked = captured != null || working
        OutlinedTextField(draft["name"] ?: "", { draft["name"] = it }, label = { Text("Name") }, singleLine = true, enabled = loaded && !locked, modifier = Modifier.fillMaxWidth().testTag("admin.scopeSetup.name"))
        OutlinedTextField(draft["description"] ?: "", { draft["description"] = it }, label = { Text("Description") }, enabled = loaded && !locked, modifier = Modifier.fillMaxWidth().testTag("admin.scopeSetup.description"))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Template", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf("blank" to "Blank", "product" to "Product")) {
                    FilterChip(selected = (draft["template"] ?: "blank") == value, onClick = { draft["template"] = value }, enabled = loaded && !locked, label = { Text(label) }, modifier = Modifier.testTag("admin.scopeSetup.template.$value"))
                }
            }
        }
        if (draft["template"] == "product") {
            OutlinedTextField(draft["directory"] ?: "", { draft["directory"] = it }, label = { Text("Working directory") }, singleLine = true, enabled = loaded && !locked, modifier = Modifier.fillMaxWidth().testTag("admin.scopeSetup.directory"))
            Text("Creates Features, Implementation, Bugs and Refactoring using the current product template's descriptions and context prompts.", style = MaterialTheme.typography.bodySmall)
        }
        if (captured != null) {
            Text("Scope created: $captured", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.scopeSetup.progress"))
            Text("Continue checks this scope's current channels and creates only missing template channels. Existing channel settings are preserved.", style = MaterialTheme.typography.bodySmall)
        }
        if (captured == null && draft["attempted"] == "true") {
            TextButton(enabled = !working, modifier = Modifier.testTag("admin.scopeSetup.recover"), onClick = {
                val d = draft.toMutableMap()
                step("Checking the current scopes…") {
                    val found = setup.recover(d)
                    draft.remove("attempted")
                    if (found != null) { existing = found; "Tower already lists a scope with this name. Choose whether to continue in it." }
                    else { "Tower lists no scope with this name. You can explicitly try creating it again." }
                }
            }) { Text("Check Tower for this scope") }
        }
        TextButton(enabled = loaded && !working && !(draft["name"].isNullOrBlank()) && draft["complete"] != "true", onClick = { confirm = true }, modifier = Modifier.testTag("admin.scopeSetup.go")) {
            Text(if (captured == null) "Create scope…" else "Continue starting channels…")
        }
        if (draft["complete"] == "true") {
            // A finished setup is over: the next scope starts from an empty form and its own progress.
            TextButton(enabled = !working, onClick = { draft.clear(); status = null; scope.launch { setup.persist(emptyMap()) } }, modifier = Modifier.testTag("admin.scopeSetup.another")) { Text("Start another scope setup") }
        }
        Text("You receive scope management. Use Permissions to grant scope roles and channel access.", style = MaterialTheme.typography.bodySmall)
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { (text, failed) ->
            Text(text, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(if (failed) "admin.scopeSetup.failed" else "admin.scopeSetup.status"))
            if (failed) Text("Your entries and progress are kept.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) ConfirmDialog(
        "Set up this scope in ${w.label}?", "Each creation is an immediate workspace change. A failure can leave a scope or some channels created; saved progress is retained.",
        if (captured == null) "Create scope" else "Continue setup", "admin.scopeSetup",
        onConfirm = {
            confirm = false
            val d = draft.toMutableMap()
            step("Setting up scope…") {
                try {
                    setup.execute(d, template())
                    d["complete"] = "true"
                } finally {
                    if (current()) { draft.clear(); draft.putAll(d) }
                }
                "Scope and starting channels are saved on Tower. Manage access in Permissions."
            }
        }, onDismiss = { confirm = false },
    )
    existing?.let { (id, name) ->
        ConfirmDialog("Continue setup in an existing scope?", "Tower already has a scope named $name. Choosing it preserves its current settings and lets the next confirmed step add only missing template channels.",
            "Use this scope for remaining channels", "admin.scopeSetup.adopt", onConfirm = { existing = null; draft["scope"] = id }, onDismiss = { existing = null })
    }
}
