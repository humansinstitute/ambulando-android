package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.management.ActivationApp
import studio.otherstuff.ambulando.core.management.ActivationIntent
import studio.otherstuff.ambulando.core.management.ConfirmedAutopilotOrigin
import studio.otherstuff.ambulando.core.management.AdministrationAuthority
import studio.otherstuff.ambulando.core.management.AdministrationCommand
import studio.otherstuff.ambulando.core.management.AdministrationDestination
import studio.otherstuff.ambulando.core.management.AdministrationList
import studio.otherstuff.ambulando.core.management.AdministrationRow
import studio.otherstuff.ambulando.core.management.GroupDirectory
import studio.otherstuff.ambulando.core.management.ManagementError
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.strings
import java.time.Instant

@Composable
internal fun AppsPage(session: AdministrationSession, nav: AdminNavigation, push: (AdminRoute) -> Unit, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val members = session.rows(AdministrationList.MEMBERS)
    val delegations = session.rows(AdministrationList.DELEGATIONS)
    val installations = session.rows(AdministrationList.INSTALLATIONS)
    val publishing = session.rows(AdministrationList.PUBLISHING)
    val admin = session.canAdminister
    val actor = session.membership?.actorId
    fun matches(row: AdministrationRow) = query.isBlank() || (row.title + " " + row.detail + " " + (row.fields["npub"] ?: "")).contains(query.trim(), ignoreCase = true)
    AdminPage("Apps & Integrations", session, back, "admin.appsPage") {
        OutlinedTextField(query, { query = it }, label = { Text("Find apps or delegations") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin.apps.search"))
        TextButton(onClick = { push(AdminRoute.DelegationNew) }, enabled = admin, modifier = Modifier.testTag("admin.delegation.new")) { Text("Grant narrow app management") }
        TextButton(
            onClick = { push(AdminRoute.Activation) }, modifier = Modifier.testTag("admin.activation.open"),
            enabled = admin || AdministrationAuthority.activeDelegations(actor, delegations).isNotEmpty(),
        ) { Text("Activate managed app") }
        TextButton(onClick = { push(AdminRoute.Publishing(null)) }, enabled = admin, modifier = Modifier.testTag("admin.publishing.new")) { Text("Add publishing installation") }

        Text("Publishing installations", style = MaterialTheme.typography.titleSmall)
        ListStatus(session, AdministrationList.PUBLISHING, "publishing installations", "admin.publishing")
        for (row in publishing.filter(::matches)) {
            RowSummary(row, "admin.publishing.${row.id}", row.fields["publisher_npub"]?.let { "Publisher " + shortKey(it) })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { push(AdminRoute.Publishing(row.id)) }, enabled = admin, modifier = Modifier.testTag("admin.publishing.edit.${row.id}")) { Text("Destinations") }
                TextButton(onClick = { push(AdminRoute.Rotation(row.id)) }, enabled = admin, modifier = Modifier.testTag("admin.publishing.rotate.${row.id}")) { Text("Rotate key") }
            }
            if (admin) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { pending = Triple("Disable publishing?", REMOVE_NOTE) { session.write(AdministrationCommand.DisablePublishing(row.id)) } }, modifier = Modifier.testTag("admin.publishing.disable.${row.id}")) { Text("Disable") }
                TextButton(onClick = { pending = Triple("Revoke publishing and open links?", REMOVE_NOTE) { session.write(AdministrationCommand.RevokePublishing(row.id)) } }, modifier = Modifier.testTag("admin.publishing.revoke.${row.id}")) { Text("Revoke") }
            }
        }
        if (publishing.isEmpty()) Text("No saved publishing installations", style = MaterialTheme.typography.bodySmall)

        Text("Delegations", style = MaterialTheme.typography.titleSmall)
        ListStatus(session, AdministrationList.DELEGATIONS, "delegations", "admin.delegations")
        for (row in delegations.filter(::matches)) {
            val who = members.firstOrNull { it.id == row.fields["delegate_actor_id"] }?.title ?: row.title
            RowSummary(row.copy(title = who), "admin.delegation.${row.id}")
            for ((key, values) in row.filters.entries.sortedBy { it.key }) {
                Text(key.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " + values.joinToString(", ") { filterLabel(it, key, nav) }, style = MaterialTheme.typography.bodySmall)
            }
            if (admin) TextButton(onClick = { pending = Triple("Revoke this delegation?", REMOVE_NOTE) { session.write(AdministrationCommand.RevokeDelegation(row.id)) } }, modifier = Modifier.testTag("admin.delegation.revoke.${row.id}")) { Text("Revoke delegation") }
        }
        if (delegations.isEmpty()) Text("No saved delegations", style = MaterialTheme.typography.bodySmall)

        Text("Managed installations", style = MaterialTheme.typography.titleSmall)
        ListStatus(session, AdministrationList.INSTALLATIONS, "installations", "admin.installations")
        for (row in installations.filter(::matches)) {
            RowSummary(row, "admin.installation.${row.id}")
            // A visible record is never a grant: a non-administrator needs an active delegation naming this exact installation.
            val may = admin || (actor != null && AdministrationAuthority.hasInstallationDelegation(actor, row.id, delegations, row))
            if (may) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { session.write(AdministrationCommand.Installation(row.id, revoke = false)) }, enabled = session.phase !is Phase.Working, modifier = Modifier.testTag("admin.installation.reconcile.${row.id}")) { Text("Reconcile installation") }
                TextButton(onClick = { pending = Triple("Revoke this installation?", REMOVE_NOTE) { session.write(AdministrationCommand.Installation(row.id, revoke = true)) } }, modifier = Modifier.testTag("admin.installation.revoke.${row.id}")) { Text("Revoke installation") }
            } else Text("Management needs an administrator or an active delegation for this exact installation.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.installation.noauthority.${row.id}"))
        }
        if (installations.isEmpty()) Text("No saved installations", style = MaterialTheme.typography.bodySmall)
    }
    pending?.let { (title, text, action) -> ConfirmDialog(title, text, "Remove access", "admin.pending", onConfirm = { pending = null; action() }, onDismiss = { pending = null }) }
}

private const val REMOVE_NOTE = "Tower will remove the selected delegation, installation or publishing authority immediately. Content is not deleted. Other grants may still provide access."

private fun filterLabel(value: String, key: String, nav: AdminNavigation): String = when (key) {
    "scope_ids" -> nav.scopes.firstOrNull { it.id == value }?.name ?: "Unavailable scope"
    "channel_ids" -> nav.channels.firstOrNull { it.id == value }?.name?.let { "#$it" } ?: "Unavailable channel"
    else -> value
}

// MARK: Delegation

@Composable
internal fun DelegationPage(session: AdministrationSession, nav: AdminNavigation, back: () -> Unit) {
    val form = remember(session) { FormState(session, "delegation", mapOf("days" to "1")) }
    val members = session.rows(AdministrationList.MEMBERS)
    AdminPage("Grant app management", session, back, "admin.delegationPage") {
        RowPicker(form, "actor", "Delegate", members.map { PickOption(it.id, it.title, it.fields["npub"]?.let(::shortKey) ?: "", keywords = it.fields["npub"] ?: "") })
        AdminField(form, "app", "App identifier")
        RowPicker(form, "channel", "Destination channel", nav.channelOptions())
        AdminChoice(form, "days", "Expires in", listOf("1" to "1 day", "7" to "7 days", "30" to "30 days"))
        Text("Limited to the specified app and destination, with Feed publishing. Does not grant workspace administration, people, billing, storage or arbitrary channel access.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Grant this delegation?",
            confirmText = "The delegate can manage installations of ${form["app"].ifBlank { "this app" }} for the chosen destination until it expires.") {
            val channel = nav.channels.firstOrNull { it.id == form["channel"] }
            if (channel == null) { session.reject(ManagementError.Invalid("Choose a destination channel.")); return@SaveBar }
            val expires = AdministrationCommand.isoSeconds(Instant.now().plusSeconds((form["days"].toLongOrNull() ?: 1L) * 86_400))
            send(session, form, AdministrationCommand.Delegation(form["actor"], expires, form["app"].trim(), channel.scopeId, channel.id))
        }
    }
}

// MARK: Publishing

@Composable
internal fun PublishingPage(session: AdministrationSession, nav: AdminNavigation, installationId: String?, back: () -> Unit) {
    val row = installationId?.let { id -> session.rows(AdministrationList.PUBLISHING).firstOrNull { it.id == id } }
    if (installationId != null && row == null) {
        // The form starts from the saved grant, so it is not drawn (and cannot be edited blind) until the grant is there.
        AdminPage("Publishing installation", session, back, "admin.publishingPage") {
            Text(if (session.listState[AdministrationList.PUBLISHING] == null) "Loading this installation…" else "This installation is not in the saved list. Refresh publishing installations.", modifier = Modifier.testTag("admin.publishing.missing"))
        }
        return
    }
    val destinations = row?.raw?.objects("destinations")?.mapNotNull { it["channel_id"]?.let { v -> (v as? JsonPrimitive)?.content } }?.sorted() ?: emptyList()
    val initial = mapOf(
        "installation" to (installationId ?: newRequestId()), "app" to (row?.fields?.get("app_id") ?: ""), "name" to (row?.title ?: ""),
        "publisher" to (row?.fields?.get("publisher_npub") ?: ""), "owner" to (row?.fields?.get("owner_npub") ?: ""),
        "channels" to destinations.joinToString(","), "origins" to (row?.raw?.strings("registered_open_origins")?.joinToString("\n") ?: ""),
    )
    // The generated installation id is kept with the draft so a retried save repeats the same idempotent PUT; it is not part of the
    // record the draft is based on, and a finished save clears the draft so the next new installation gets a new id.
    val form = remember(session, installationId) { FormState(session, "publishing:${installationId ?: "new"}", initial, baseKeys = if (installationId == null) emptySet() else initial.keys - "installation") }
    val selected = form["channels"].split(',').filter { it.isNotEmpty() }.toSet()
    AdminPage(if (installationId == null) "Add publishing installation" else "Publishing installation", session, back, "admin.publishingPage") {
        if (installationId == null) AdminField(form, "installation", "Installation UUID")
        AdminField(form, "app", "App identifier"); AdminField(form, "name", "Display name")
        AdminField(form, "publisher", "Publisher npub"); AdminField(form, "owner", "Owner npub")
        Text("Destinations", style = MaterialTheme.typography.titleSmall)
        for (scope in nav.scopes) {
            Text(scope.name, style = MaterialTheme.typography.labelMedium)
            for (channel in nav.channels.filter { it.scopeId == scope.id }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = channel.id in selected, enabled = form.loaded,
                        onCheckedChange = { on -> form["channels"] = (if (on) selected + channel.id else selected - channel.id).sorted().joinToString(",") },
                        modifier = Modifier.testTag("admin.dest.${channel.id}"),
                    )
                    Text("#" + channel.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val unavailable = selected.filter { id -> nav.channels.none { it.id == id } }
        if (unavailable.isNotEmpty()) Text("${unavailable.size} previous destination(s) are not visible to you. Refresh and review before saving; a save would drop them.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.publishing.hidden"))
        AdminField(form, "origins", "HTTPS open origins (one per line)", singleLine = false)
        Text("Saving replaces this publishing grant's destinations and open origins with the values shown. Feed publishing is limited to the selected destinations.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Replace this publishing grant?",
            confirmText = "Destinations and open origins become exactly what is shown, for everyone, immediately.") {
            if (unavailable.isNotEmpty() || selected.isEmpty()) {
                session.reject(ManagementError.Invalid("Choose available publishing destinations. A previous destination may no longer be visible; refresh and review before saving."))
                return@SaveBar
            }
            val chosen = nav.channels.filter { it.id in selected }.map { AdministrationDestination(it.scopeId, it.id) }
            val command = AdministrationCommand.Publishing(
                installationId ?: form["installation"], form["app"].trim(), form["publisher"].trim(), form["owner"].trim(), form["name"].trim(), chosen,
                form["origins"].split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            )
            try { command.request(session.admin.workspaceId) } catch (e: ManagementError) { session.reject(e); return@SaveBar }
            session.write(command) { if (installationId == null) form.restart(initial + ("installation" to newRequestId())) else form.acknowledge() }
        }
    }
}

@Composable
internal fun RotationPage(session: AdministrationSession, installationId: String, back: () -> Unit) {
    val row = session.rows(AdministrationList.PUBLISHING).firstOrNull { it.id == installationId }
    val form = remember(session, installationId) { FormState(session, "rotation:$installationId", emptyMap()) }
    AdminPage("Rotate publisher public key", session, back, "admin.rotationPage") {
        Text(row?.title ?: "Unavailable installation")
        Text("Current publisher: ${row?.fields?.get("publisher_npub") ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
        AdminField(form, "replacement", "Replacement publisher npub")
        Text("Only a public key is accepted; no private key is ever entered here. The server may require owner step-up approval.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = session.canAdminister && row?.fields?.get("publisher_npub") != null, confirmTitle = "Rotate the publisher key?",
            confirmText = "The replacement key becomes the only publisher for this installation. A short-lived request is sent; Tower may ask the owner to approve.") {
            send(session, form, AdministrationCommand.RotatePublisher(installationId, row?.fields?.get("publisher_npub") ?: "", form["replacement"].trim()))
        }
    }
}

// MARK: Activation (user-consented Autopilot origin)

private fun encodeCatalog(apps: List<ActivationApp>): String = JsonArray(apps.map {
    JsonObject(mapOf(
        "id" to JsonPrimitive(it.id), "version" to JsonPrimitive(it.version), "title" to JsonPrimitive(it.title), "launch" to JsonPrimitive(it.launchUrl),
        "origin" to JsonPrimitive(it.origin), "npub" to JsonPrimitive(it.autopilotNpub), "view" to JsonPrimitive(it.viewOrigin),
    ))
}).toString()

private fun decodeCatalog(text: String): List<ActivationApp> = runCatching {
    (TowerJson.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String): String? = (o[k] as? JsonPrimitive)?.content
        val fields = listOf("id", "version", "title", "launch", "origin", "npub", "view").map { s(it) ?: return@mapNotNull null }
        ActivationApp(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])
    }
}.getOrDefault(emptyList())

@Composable
internal fun ActivationPage(session: AdministrationSession, nav: AdminNavigation, back: () -> Unit) {
    val form = remember(session) { FormState(session, "activation", mapOf("request" to newRequestId(), "installation" to newRequestId()), baseKeys = emptySet()) }
    // The action and the exact normalized origin the person is shown; the confirmed value, not the text field, is what gets signed to.
    var confirming by remember { mutableStateOf<Pair<String, ConfirmedAutopilotOrigin>?>(null) }
    var otherOrigin by remember { mutableStateOf(0) }
    val delegations = session.rows(AdministrationList.DELEGATIONS)
    val actor = session.membership?.actorId
    val usable = AdministrationAuthority.activeDelegations(actor, delegations)
    val apps = decodeCatalog(form["catalog"])
    val typed = form["origin"].trim()
    val normalized = runCatching { ConfirmedAutopilotOrigin.of(typed) }.getOrNull()
    val savedIntent = form["intent"]
    val idle = session.phase !is Phase.Working && session.isCurrent
    AdminPage("Activate managed app", session, back, "admin.activationPage") {
        Text(
            "This signs requests as your unlocked identity to the HTTPS Autopilot origin you choose here, and only to it. Tower metadata does not select or trust this host. Redirects are refused.",
            style = MaterialTheme.typography.bodySmall,
        )
        AdminField(form, "origin", "Autopilot HTTPS origin", enabled = savedIntent.isEmpty())
        if (typed.isNotEmpty()) Text(
            normalized?.let { "Will sign requests to exactly ${it.url}" } ?: "Enter an HTTPS origin such as https://autopilot.example.com (no path, query or credentials).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.activation.target"),
        )
        TextButton(onClick = { normalized?.let { confirming = "catalog" to it } }, enabled = form.loaded && normalized != null && savedIntent.isEmpty() && idle, modifier = Modifier.testTag("admin.activation.catalog")) { Text("Load activation catalog…") }
        if (otherOrigin > 0) Text("$otherOrigin catalog entr${if (otherOrigin == 1) "y names" else "ies name"} a different Autopilot and ${if (otherOrigin == 1) "is" else "are"} not offered here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.activation.otherOrigin"))
        RowPicker(form, "app", "App", apps.map { PickOption(it.id, it.title, it.version) }, enabled = savedIntent.isEmpty(), emptyLabel = "Load the catalog first")
        RowPicker(form, "channel", "Destination channel", nav.channelOptions(), enabled = savedIntent.isEmpty())
        // The authority an activation runs under: an administrator, or one explicit active delegation of this member.
        RowPicker(
            form, "delegation", "Authority",
            (if (session.canAdminister) listOf(PickOption("", "Workspace administrator")) else emptyList()) +
                usable.map { PickOption(it.id, "Delegation · ${it.title}", it.fields["expires_at"] ?: "") },
            enabled = savedIntent.isEmpty(),
        )
        apps.firstOrNull { it.id == form["app"] }?.let { app ->
            Text("Launch URL: ${app.launchUrl}", style = MaterialTheme.typography.bodySmall); Text("Autopilot public key: ${shortKey(app.autopilotNpub)}", style = MaterialTheme.typography.bodySmall)
            Text("Open origin: ${app.viewOrigin}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Activation requests Feed publishing only for the selected channel. Tower checks the exact app, delegation, destination and origin; owner-only approvals remain required.", style = MaterialTheme.typography.bodySmall)
        if (savedIntent.isNotEmpty()) {
            Text("Saved activation intent: $savedIntent", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.activation.savedIntent"))
            Text("Bound to ${form["intentOrigin"].ifEmpty { "an unknown origin" }} and this workspace.", style = MaterialTheme.typography.bodySmall)
            if (form["processed"] == "true") {
                Text("Autopilot has processed this intent.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    // A finished activation is over: the next one gets its own request and installation ids.
                    for (key in listOf("intent", "intentOrigin", "intentBoundary", "processed", "app", "channel", "delegation")) form[key] = ""
                    form["request"] = newRequestId(); form["installation"] = newRequestId()
                }, enabled = session.phase !is Phase.Working, modifier = Modifier.testTag("admin.activation.another")) { Text("Start another activation") }
            } else {
                TextButton(onClick = { normalized?.let { confirming = "process" to it } }, enabled = idle && normalized != null, modifier = Modifier.testTag("admin.activation.process")) { Text("Process this saved intent…") }
                Text("An interrupted claim may already have completed. Processing retries this exact intent. It never creates another installation.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val ready = apps.any { it.id == form["app"] } && nav.channels.any { it.id == form["channel"] } && (session.canAdminister || form["delegation"].isNotEmpty()) && normalized != null
            TextButton(onClick = { normalized?.let { confirming = "activate" to it } }, enabled = form.loaded && ready && idle, modifier = Modifier.testTag("admin.activation.activate")) { Text("Activate selected app…") }
        }
    }
    androidx.compose.runtime.LaunchedEffect(form) { form.load() }
    androidx.compose.runtime.LaunchedEffect(form.fingerprint, form.loaded) { if (form.loaded) { kotlinx.coroutines.delay(150); form.persist() } }
    confirming?.let { (action, origin) ->
        val sends = when (action) {
            "catalog" -> "Sends one signed GET to ${origin.url}/api/wapps/activation-catalog."
            "activate" -> "Creates the install intent on Tower and saves it, then sends one signed POST to ${origin.url}/api/wapps/install-intents/process for that intent."
            else -> "Sends one signed POST to ${origin.url}/api/wapps/install-intents/process for the saved intent."
        }
        ConfirmDialog(
            "Sign a request to ${origin.url}?", "$sends It uses your public identity and signature with this exact HTTPS host. Redirects are refused. Activation changes this workspace and may provision a managed app. Nothing is submitted to an app store.",
            if (action == "catalog") "Load catalog from ${origin.url}" else "Activate through ${origin.url}", "admin.activation.sign",
            onConfirm = {
                confirming = null
                when (action) {
                    "catalog" -> session.run(working = "Waiting for signed confirmation…", done = "Catalog loaded. Choose an app and destination.", block = {
                        val loaded = session.admin.activationCatalog(origin)
                        form["catalog"] = encodeCatalog(loaded.apps); form["catalogOrigin"] = origin.url; form["app"] = ""
                        otherOrigin = loaded.otherOrigin
                        form.persist()
                    })
                    "activate" -> {
                        val app = apps.firstOrNull { it.id == form["app"] }
                        val channel = nav.channels.firstOrNull { it.id == form["channel"] }
                        if (app == null || channel == null || form["catalogOrigin"] != origin.url) {
                            session.reject(ManagementError.Invalid("Load this origin's catalog and choose an app and channel."))
                        } else session.run(working = "Waiting for signed confirmation…", done = "Autopilot processed the saved intent. Refresh installations to inspect its state.", block = {
                            form.persist()
                            val intent = session.admin.createActivationIntent(app, origin, channel.scopeId, channel.id, form["installation"], form["request"], form["delegation"].ifEmpty { null })
                            // Kept, with the origin and boundary it belongs to, before the separate claim: a failed claim retries this exact intent.
                            form["intent"] = intent.id; form["intentOrigin"] = intent.origin; form["intentBoundary"] = intent.boundary; form.persist()
                            session.admin.processActivationIntent(origin, intent)
                            form["processed"] = "true"; form.persist()
                        })
                    }
                    "process" -> session.run(working = "Waiting for signed confirmation…", done = "Autopilot processed this saved intent. Refresh installations to inspect its state.", block = {
                        session.admin.processActivationIntent(origin, ActivationIntent(form["intent"], form["intentOrigin"], form["intentBoundary"]))
                        form["processed"] = "true"; form.persist()
                    })
                }
            },
            onDismiss = { confirming = null },
        )
    }
}
