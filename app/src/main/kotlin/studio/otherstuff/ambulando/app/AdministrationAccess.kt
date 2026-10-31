package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.management.AdministrationAuthority
import studio.otherstuff.ambulando.core.management.AdministrationCommand
import studio.otherstuff.ambulando.core.management.AdministrationList
import studio.otherstuff.ambulando.core.management.AdministrationRow
import studio.otherstuff.ambulando.core.management.AdministrationSuperseded
import studio.otherstuff.ambulando.core.management.ScopeAdministrationAccess

@Composable
internal fun PermissionsPage(session: AdministrationSession, nav: AdminNavigation, push: (AdminRoute) -> Unit, back: () -> Unit) {
    AdminPage("Permissions", session, back, "admin.permissionsPage") {
        Text("Scope access", style = MaterialTheme.typography.titleSmall)
        Text("Scope roles manage a scope and its context. They do not grant access to channels or linked content.", style = MaterialTheme.typography.bodySmall)
        for (scope in nav.scopes) TextButton(onClick = { push(AdminRoute.ScopeAccess(scope.id)) }, modifier = Modifier.fillMaxWidth().testTag("admin.scopeAccess.${scope.id}")) { Text(scope.name, modifier = Modifier.fillMaxWidth()) }
        Text("Channel access", style = MaterialTheme.typography.titleSmall)
        for (channel in nav.channels) TextButton(onClick = { push(AdminRoute.ChannelAccess(channel.id)) }, modifier = Modifier.fillMaxWidth().testTag("admin.channelAccess.${channel.id}")) { Text("#${channel.name} · ${nav.scopeName(channel.scopeId)}", modifier = Modifier.fillMaxWidth()) }
        if (nav.channels.isEmpty() && nav.scopes.isEmpty()) Text("No scopes or channels are available to you here.", style = MaterialTheme.typography.bodySmall)
        Text("Your workspace permissions", style = MaterialTheme.typography.titleSmall)
        val known = session.membership
        if (known == null) Text("Unknown until your access can be read.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.permissions.unknown"))
        else for (permission in known.permissions.sorted()) Text(permission, style = MaterialTheme.typography.bodySmall)
    }
}

/** What the channel access page knows after reading the channel's grants and the member's authority just now. */
internal sealed interface ChannelAccess {
    data object Loading : ChannelAccess
    /** [canManage] comes only from a `/me` and a grants read made for this channel, never from the saved lists alone. */
    data class Ready(val grants: List<AdministrationRow>, val canManage: Boolean, val scopeGrants: List<AdministrationRow>, val groups: List<AdministrationRow>) : ChannelAccess
    data class Failed(val message: String) : ChannelAccess
}

@Stable
internal class ChannelAccessState(private val session: AdministrationSession, private val channelId: String, private val scopeId: String?) {
    var value by mutableStateOf<ChannelAccess>(ChannelAccess.Loading); private set

    /** Reads `/me`, the grants and (when readable) the scope's access, then decides. Any failure clears authority. */
    suspend fun reload() {
        if (!session.isCurrent) return
        value = ChannelAccess.Loading
        try {
            val fresh = session.admin.membership()
            val grants = session.admin.channelGrants(channelId)
            // The group directory is administrator-only. Only a directory read made now counts toward group-derived authority (a saved
            // one, from a wider past role, never does), and it is the same list the write itself re-reads, so this screen never offers a
            // change the write would then refuse. A scope manager's own read can name groups to choose as recipients; that list only
            // fills the picker and is never authority.
            val directory = if (session.admin.refresh(AdministrationList.GROUPS) == studio.otherstuff.ambulando.core.management.ListRefresh.Fresh) session.admin.cached(AdministrationList.GROUPS) else emptyList()
            val scope = scopeId?.let { runCatching { session.admin.scopeAccess(it) }.getOrNull() }
            val choices = if (scope != null) (directory + scope.groups).distinctBy { it.id } else directory
            val can = AdministrationAuthority.canManageChannelGrants(fresh, grants, directory, session.admin.signerNpub)
            if (session.isCurrent) value = ChannelAccess.Ready(grants, can, scope?.grants ?: emptyList(), choices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdministrationSuperseded) {
        } catch (e: Exception) {
            if (session.isCurrent) value = ChannelAccess.Failed(AdministrationSession.failureText(e).first)
        }
    }

    /** A refusal clears the decision; it is re-earned only by an explicit refresh or a successful change. */
    fun revoke() { if (value is ChannelAccess.Ready) value = (value as ChannelAccess.Ready).copy(canManage = false) }
}

@Composable
internal fun ChannelAccessPage(session: AdministrationSession, nav: AdminNavigation, channelId: String, push: (AdminRoute) -> Unit, back: () -> Unit) {
    val channel = nav.channels.firstOrNull { it.id == channelId }
    val state = remember(session, channelId) { ChannelAccessState(session, channelId, channel?.scopeId) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<AdministrationRow?>(null) }
    LaunchedEffect(state) { state.reload() }
    AdminPage(channel?.let { "#${it.name}" } ?: "Channel access", session, back, "admin.channelAccessPage") {
        Text("Channel grants decide who can read and contribute here. Scope roles and workspace-wide permissions do not.", style = MaterialTheme.typography.bodySmall)
        when (val s = state.value) {
            ChannelAccess.Loading -> Text("Checking this channel's access…", modifier = Modifier.testTag("admin.channel.loading"))
            is ChannelAccess.Failed -> {
                Text("Could not read this channel's access: ${s.message}", modifier = Modifier.testTag("admin.channel.failed"))
                TextButton(onClick = { scope.launch { state.reload() } }, modifier = Modifier.testTag("admin.channel.refresh")) { Text("Refresh channel access") }
            }
            is ChannelAccess.Ready -> {
                TextButton(onClick = { push(AdminRoute.ChannelGrant(channelId, null, null)) }, enabled = s.canManage, modifier = Modifier.testTag("admin.channel.grant")) { Text("Grant access") }
                if (!s.canManage) Text("You can see this list, but only an administrator or a holder of grant management on this exact channel can change it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.channel.readOnly"))
                TextButton(onClick = { scope.launch { state.reload() } }, modifier = Modifier.testTag("admin.channel.refresh")) { Text("Refresh channel access") }
                for (row in s.grants) {
                    val custom = row.fields["access_level"] == "custom"
                    val type = row.fields["principal_type"]; val id = row.fields["principal_id"] ?: row.id
                    RowSummary(row, "admin.grant.$id", if (custom) row.channelPermissions.joinToString(", ") else null)
                    if (s.canManage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!custom) TextButton(onClick = { push(AdminRoute.ChannelGrant(channelId, type, id)) }, modifier = Modifier.testTag("admin.grant.change.$id")) { Text("Change access") }
                        else Text("Custom permissions are preserved. Add access separately, or explicitly revoke this grant.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { pending = row }, modifier = Modifier.testTag("admin.grant.revoke.$id")) { Text("Revoke") }
                    }
                }
                if (s.grants.isEmpty()) Text("No individual grants. Access may come from groups.", style = MaterialTheme.typography.bodySmall)
                Text("Scope roles (do not grant this channel)", style = MaterialTheme.typography.titleSmall)
                for (row in s.scopeGrants) RowSummary(row, "admin.scopeRole.${row.id}")
                if (s.scopeGrants.isEmpty()) Text("None readable.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    pending?.let { row ->
        ConfirmDialog("Revoke this channel grant?", "This grant is removed immediately. Other grants may still provide access.", "Revoke access", "admin.revoke", onConfirm = {
            pending = null
            val command = AdministrationCommand.RevokeChannelGrant(channelId, row.fields["principal_type"] ?: "", row.fields["principal_id"] ?: row.id)
            session.write(command) { state.reload() }
        }, onDismiss = { pending = null })
    }
}

@Composable
internal fun ChannelGrantPage(session: AdministrationSession, nav: AdminNavigation, route: AdminRoute.ChannelGrant, back: () -> Unit) {
    val channel = nav.channels.firstOrNull { it.id == route.channelId }
    val state = remember(session, route.channelId) { ChannelAccessState(session, route.channelId, channel?.scopeId) }
    LaunchedEffect(state) { state.reload() }
    val ready = state.value as? ChannelAccess.Ready
    if (ready == null) {
        // The editor starts from the grant as Tower just returned it, and its authority comes from the same fresh read.
        AdminPage(if (route.principalId == null) "Grant channel access" else "Change channel access", session, back, "admin.channelGrantPage") {
            Text(if (state.value is ChannelAccess.Failed) "Could not read this channel's access. Go back and refresh it." else "Checking this channel's access…", modifier = Modifier.testTag("admin.channelGrant.loading"))
        }
        return
    }
    val existing = ready.grants.firstOrNull { it.fields["principal_type"] == route.principalType && (it.fields["principal_id"] ?: it.id) == route.principalId }
    val level = existing?.fields?.get("access_level")?.takeIf { it in AdministrationCommand.ACCESS_LEVELS } ?: "view"
    val form = remember(session, route) { FormState(session, "grant:${route.channelId}:${route.principalId ?: "new"}", mapOf("type" to (route.principalType ?: "actor"), "access" to level)) }
    val members = session.rows(AdministrationList.MEMBERS)
    AdminPage(if (route.principalId == null) "Grant channel access" else "Change channel access", session, back, "admin.channelGrantPage") {
        Text(channel?.let { "#${it.name}" } ?: "Unavailable channel")
        if (route.principalId == null) {
            AdminChoice(form, "type", "Give access to", listOf("actor" to "Person or agent", "group" to "Group"))
            val options = if (form["type"] == "group") ready.groups.map { PickOption(it.id, it.title, "${it.children.size} people") }
            else members.map { PickOption(it.id, it.title, it.fields["npub"]?.let(::shortKey) ?: "", keywords = it.fields["npub"] ?: "") }
            RowPicker(form, "principal", "Recipient", options, emptyLabel = if (form["type"] == "group") "Group choices are unavailable to you. Choose a person instead." else "No saved members")
        } else Text(existing?.title ?: "Recipient unavailable")
        AdminChoice(form, "access", "Access", listOf("view" to "View", "contribute" to "Contribute", "manage" to "Manage"))
        Text("Manage access includes authority over this channel. Tower checks the exact grant.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = ready.canManage && (route.principalId != null || form["principal"].isNotEmpty()), confirmTitle = "Apply this workspace change?",
            confirmText = "This changes who can reach ${channel?.name?.let { "#$it" } ?: "this channel"} in ${session.workspaceLabel} immediately.") {
            val type = route.principalType ?: form["type"]; val principal = route.principalId ?: form["principal"]
            send(session, form, AdministrationCommand.ChannelGrant(route.channelId, type, principal, form["access"], update = route.principalId != null))
        }
    }
}

// MARK: Scope access

@Composable
internal fun ScopeAccessPage(session: AdministrationSession, nav: AdminNavigation, scopeId: String, push: (AdminRoute) -> Unit, back: () -> Unit) {
    var access by remember(session, scopeId) { mutableStateOf<ScopeAdministrationAccess?>(null) }
    var fresh by remember(session, scopeId) { mutableStateOf(false) }
    var error by remember(session, scopeId) { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AdministrationRow?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        fresh = false
        try {
            val read = session.admin.scopeAccess(scopeId)
            if (session.isCurrent) { access = read; fresh = true; error = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdministrationSuperseded) {
        } catch (e: Exception) {
            if (session.isCurrent) error = AdministrationSession.failureText(e).first
        }
    }
    LaunchedEffect(session, scopeId) {
        // Saved first (marked as saved, never authority), then the fresh read that decides.
        session.admin.cachedScopeAccess(scopeId)?.let { if (session.isCurrent) access = it }
        reload()
    }
    AdminPage(nav.scopeName(scopeId), session, back, "admin.scopeAccessPage") {
        Text("Scope roles manage the scope and its context. They never grant access to linked content or channel participation.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text("Could not read this scope's access: $it", modifier = Modifier.testTag("admin.scope.failed")) }
        TextButton(onClick = { scope.launch { reload() } }, modifier = Modifier.testTag("admin.scope.refresh")) { Text("Refresh scope access") }
        val current = access
        if (current == null) Text(if (error == null) "Loading scope access…" else "No saved scope access.", modifier = Modifier.testTag("admin.scope.empty"))
        else {
            if (!fresh) Text("Saved copy. Controls stay off until a fresh read confirms your authority.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.scope.stale"))
            val manage = fresh && current.canManage
            if (fresh && !current.canManage) Text("Only a manager of this exact scope (or an administrator) can change its roles.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.scope.readOnly"))
            TextButton(onClick = { push(AdminRoute.ScopeGrant(scopeId, null, null, current.revision)) }, enabled = manage, modifier = Modifier.testTag("admin.scope.grant")) { Text("Grant scope role") }
            for (row in current.grants) {
                val type = row.fields["principal_type"]; val id = row.fields["principal_id"] ?: row.id
                RowSummary(row, "admin.scopeRole.${row.id}")
                if (manage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { push(AdminRoute.ScopeGrant(scopeId, type, id, current.revision)) }, modifier = Modifier.testTag("admin.scopeRole.change.$id")) { Text("Change role") }
                    TextButton(onClick = { pending = row }, modifier = Modifier.testTag("admin.scopeRole.revoke.$id")) { Text("Revoke role grants") }
                }
            }
            if (current.grants.isEmpty()) Text("No scope role grants", style = MaterialTheme.typography.bodySmall)
            Text("Revoking role grants preserves custom permissions and group-derived access. A revision conflict requires refresh and review.", style = MaterialTheme.typography.bodySmall)
        }
    }
    pending?.let { row ->
        ConfirmDialog("Revoke scope role grants?", "Existing custom permissions and group-derived access remain. This removes the selected principal's role grants on this scope.", "Revoke role grants", "admin.revokeScope", onConfirm = {
            pending = null
            val current = access ?: return@ConfirmDialog
            session.run(block = {
                session.admin.saveScopeAccess(scopeId, row.fields["principal_type"] ?: "", row.fields["principal_id"] ?: row.id, "context_editor", revoke = true, expectedRevision = current.revision)
            }, then = { reload() })
        }, onDismiss = { pending = null })
    }
}

@Composable
internal fun ScopeGrantPage(session: AdministrationSession, nav: AdminNavigation, route: AdminRoute.ScopeGrant, back: () -> Unit) {
    var access by remember(session, route) { mutableStateOf<ScopeAdministrationAccess?>(null) }
    var verified by remember(session, route) { mutableStateOf(false) }
    val form = remember(session, route) { FormState(session, "scope-grant:${route.scopeId}:${route.principalId ?: "new"}", mapOf("type" to (route.principalType ?: "actor"), "role" to "context_editor")) }
    LaunchedEffect(session, route) {
        try {
            val read = session.admin.scopeAccess(route.scopeId)
            if (session.isCurrent) { access = read; verified = read.canManage && read.revision == route.revision }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (session.isCurrent) verified = false }
    }
    val current = access
    AdminPage("Grant scope access", session, back, "admin.scopeGrantPage") {
        Text(nav.scopeName(route.scopeId))
        if (current != null && current.revision != route.revision) Text("This scope's access changed elsewhere. Go back and review it before saving your retained entries.", modifier = Modifier.testTag("admin.scopeGrant.stale"))
        if (route.principalId == null) {
            AdminChoice(form, "type", "Give access to", listOf("actor" to "Person or agent", "group" to "Group"))
            val options = if (form["type"] == "group") (current?.groups ?: emptyList()).map { PickOption(it.id, it.title) } else (current?.actors ?: emptyList()).map { PickOption(it.id, it.title, it.fields["npub"]?.let(::shortKey) ?: "", keywords = it.fields["npub"] ?: "") }
            RowPicker(form, "principal", "Recipient", options)
        } else Text(current?.grants?.firstOrNull { (it.fields["principal_id"] ?: it.id) == route.principalId }?.title ?: "Recipient")
        AdminChoice(form, "role", "Role", listOf("context_editor" to "Context editor", "scope_manager" to "Scope manager"))
        Text("Context editors can edit the context tree. Scope managers can also manage scope settings and access. Neither role grants access to linked content.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = verified && (route.principalId != null || form["principal"].isNotEmpty()), confirmTitle = "Apply this scope change?",
            confirmText = "This changes who can manage ${nav.scopeName(route.scopeId)} immediately. It is saved against the access you reviewed; if it changed meanwhile nothing is saved.") {
            val type = route.principalType ?: form["type"]; val principal = route.principalId ?: form["principal"]
            session.run(block = { session.admin.saveScopeAccess(route.scopeId, type, principal, form["role"], revoke = false, expectedRevision = route.revision) }, then = { form.acknowledge() })
        }
    }
}
