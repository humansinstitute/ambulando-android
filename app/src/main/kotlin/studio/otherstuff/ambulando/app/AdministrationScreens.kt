package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.otherstuff.ambulando.core.management.AdministrationCommand
import studio.otherstuff.ambulando.core.management.AdministrationList
import studio.otherstuff.ambulando.core.management.AdministrationRow
import studio.otherstuff.ambulando.core.management.GroupDirectory
import studio.otherstuff.ambulando.core.management.ManagementError
import studio.otherstuff.ambulando.core.management.WorkspaceAdministrator
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

internal enum class AdminSection(val title: String) {
    WORKSPACE("Workspace"), SCOPES("Scopes & Channels"), MEMBERS("Members"), GROUPS("Groups"), PERMISSIONS("Permissions"), APPS("Apps & Integrations");
    val tag: String get() = "admin.section.${name.lowercase()}"
}

internal sealed interface AdminRoute {
    data object Home : AdminRoute
    data class Section(val section: AdminSection) : AdminRoute
    data object Profile : AdminRoute
    data object DeleteWorkspace : AdminRoute
    data object MemberAdd : AdminRoute
    data class MemberProfile(val actorId: String) : AdminRoute
    data object GroupNew : AdminRoute
    data class Group(val id: String) : AdminRoute
    data class ChannelAccess(val channelId: String) : AdminRoute
    data class ChannelGrant(val channelId: String, val principalType: String?, val principalId: String?) : AdminRoute
    data class ScopeAccess(val scopeId: String) : AdminRoute
    data class ScopeGrant(val scopeId: String, val principalType: String?, val principalId: String?, val revision: Long) : AdminRoute
    data object DelegationNew : AdminRoute
    data object Activation : AdminRoute
    data class Publishing(val installationId: String?) : AdminRoute
    data class Rotation(val installationId: String) : AdminRoute
}

/** What a section refreshes when it opens (and every [pollMillis] while it stays open). */
private fun listsFor(route: AdminRoute): List<AdministrationList> = when (route) {
    is AdminRoute.Section -> when (route.section) {
        AdminSection.MEMBERS -> listOf(AdministrationList.MEMBERS)
        AdminSection.GROUPS -> listOf(AdministrationList.MEMBERS, AdministrationList.GROUPS)
        AdminSection.PERMISSIONS -> listOf(AdministrationList.MEMBERS, AdministrationList.GROUPS)
        AdminSection.APPS -> listOf(AdministrationList.MEMBERS, AdministrationList.DELEGATIONS, AdministrationList.INSTALLATIONS, AdministrationList.PUBLISHING)
        else -> emptyList()
    }
    is AdminRoute.Group, AdminRoute.GroupNew, AdminRoute.MemberAdd, is AdminRoute.MemberProfile -> listOf(AdministrationList.MEMBERS, AdministrationList.GROUPS)
    is AdminRoute.ChannelAccess, is AdminRoute.ChannelGrant, is AdminRoute.ScopeAccess, is AdminRoute.ScopeGrant -> listOf(AdministrationList.MEMBERS, AdministrationList.GROUPS)
    AdminRoute.DelegationNew, AdminRoute.Activation, is AdminRoute.Publishing, is AdminRoute.Rotation ->
        listOf(AdministrationList.MEMBERS, AdministrationList.DELEGATIONS, AdministrationList.INSTALLATIONS, AdministrationList.PUBLISHING)
    else -> emptyList()
}

/**
 * Manage Workspace. [session] is bound to one boundary: the screen is rebuilt for another workspace and a session that is no
 * longer current ignores every delayed reply. [scopeChannelContent] is the scope and channel management that predates
 * administration (it needs the app model); the rest is drawn here from the session's saved lists and fresh authority reads.
 */
@Composable
internal fun AdministrationHost(
    session: AdministrationSession,
    workspace: WorkspaceRecord,
    scopes: List<ScopeRecord>,
    channels: List<ChannelRecord>,
    scopeChannelContent: @Composable (back: () -> Unit, openChannelAccess: (String) -> Unit, openScopeAccess: (String) -> Unit) -> Unit,
    done: () -> Unit,
    initial: AdminRoute = AdminRoute.Home,
    pollMillis: Long = 30_000,
) {
    var stack by remember { mutableStateOf(listOf(initial)) }
    val route = stack.last()
    fun push(next: AdminRoute) { session.clearPhase(); stack = stack + next }
    fun pop() { session.clearPhase(); stack = if (stack.size > 1) stack.dropLast(1) else stack }
    val lists = listsFor(route)
    LaunchedEffect(session, route, lists) {
        session.checkAuthority()
        if (lists.isNotEmpty()) {
            var first = true
            while (true) {
                // Later rounds skip what Tower already refused or does not offer: that only changes after an explicit refresh.
                val round = if (first) lists else lists.filter { session.listState[it].let { r -> r !is studio.otherstuff.ambulando.core.management.ListRefresh.Restricted && r !is studio.otherstuff.ambulando.core.management.ListRefresh.Unsupported } }
                if (round.isNotEmpty()) session.refresh(*round.toTypedArray(), thenReloadAuthority = false)
                first = false
                if (pollMillis <= 0) break
                delay(pollMillis)
                if (!session.isCurrent) break
            }
        }
    }
    if (!session.isCurrent) {
        AdminPage("Workspace changed", session, done, "admin.stale") {
            Text("Close management and reopen it for the workspace you want. Nothing was changed.", modifier = Modifier.testTag("admin.stale.note"))
        }
        return
    }
    val navigation = AdminNavigation(scopes.filter { !it.accessRemoved }, channels.filter { !it.accessRemoved })
    when (route) {
        AdminRoute.Home -> HomePage(session, workspace, ::push, done)
        is AdminRoute.Section -> when (route.section) {
            AdminSection.WORKSPACE -> WorkspacePage(session, workspace, ::push, ::pop)
            AdminSection.SCOPES -> scopeChannelContent(::pop, { push(AdminRoute.ChannelAccess(it)) }, { push(AdminRoute.ScopeAccess(it)) })
            AdminSection.MEMBERS -> MembersPage(session, ::push, ::pop)
            AdminSection.GROUPS -> GroupsPage(session, ::push, ::pop)
            AdminSection.PERMISSIONS -> PermissionsPage(session, navigation, ::push, ::pop)
            AdminSection.APPS -> AppsPage(session, navigation, ::push, ::pop)
        }
        AdminRoute.Profile -> ProfilePage(session, workspace, ::pop)
        AdminRoute.DeleteWorkspace -> DeleteWorkspacePage(session, workspace, ::pop, done)
        AdminRoute.MemberAdd -> MemberAddPage(session, ::pop)
        is AdminRoute.MemberProfile -> MemberProfilePage(session, route.actorId, ::pop)
        AdminRoute.GroupNew -> GroupNewPage(session, ::pop)
        is AdminRoute.Group -> GroupPage(session, route.id, ::pop)
        is AdminRoute.ChannelAccess -> ChannelAccessPage(session, navigation, route.channelId, ::push, ::pop)
        is AdminRoute.ChannelGrant -> ChannelGrantPage(session, navigation, route, ::pop)
        is AdminRoute.ScopeAccess -> ScopeAccessPage(session, navigation, route.scopeId, ::push, ::pop)
        is AdminRoute.ScopeGrant -> ScopeGrantPage(session, navigation, route, ::pop)
        AdminRoute.DelegationNew -> DelegationPage(session, navigation, ::pop)
        AdminRoute.Activation -> ActivationPage(session, navigation, ::pop)
        is AdminRoute.Publishing -> PublishingPage(session, navigation, route.installationId, ::pop)
        is AdminRoute.Rotation -> RotationPage(session, route.installationId, ::pop)
    }
}

/** The scopes and channels the member can open now, for pickers; removed or unavailable ones are never offered as destinations. */
internal class AdminNavigation(val scopes: List<ScopeRecord>, val channels: List<ChannelRecord>) {
    fun channelName(id: String) = channels.firstOrNull { it.id == id }?.name?.let { "#$it" } ?: "Unavailable channel"
    fun scopeName(id: String) = scopes.firstOrNull { it.id == id }?.name ?: "Unavailable scope"
    fun channelOptions() = channels.map { PickOption(it.id, "#" + it.name, scopeName(it.scopeId)) }
}

@Composable
private fun HomePage(session: AdministrationSession, workspace: WorkspaceRecord, push: (AdminRoute) -> Unit, done: () -> Unit) {
    AdminPage("Manage workspace", session, done, "admin.home") {
        for (section in AdminSection.entries) {
            TextButton(onClick = { push(AdminRoute.Section(section)) }, modifier = Modifier.fillMaxWidth().testTag(section.tag)) { Text(section.title, modifier = Modifier.fillMaxWidth()) }
        }
        TextButton(onClick = { session.checkAuthority(); session.refresh(AdministrationList.MEMBERS, AdministrationList.GROUPS) }, modifier = Modifier.testTag("admin.refresh")) { Text("Refresh access and lists") }
        Text(
            "Connection, retry, appearance, optional sections and notifications are device settings. Workspace appearance and owner: ${workspace.ownerNpub ?: "unavailable"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// MARK: Workspace

@Composable
private fun WorkspacePage(session: AdministrationSession, workspace: WorkspaceRecord, push: (AdminRoute) -> Unit, back: () -> Unit) {
    AdminPage("Workspace", session, back, "admin.workspacePage") {
        Text(workspace.label, style = MaterialTheme.typography.titleMedium)
        Text("Address: ${workspace.slug ?: "unavailable"}", style = MaterialTheme.typography.bodySmall)
        Text("Owner: ${workspace.ownerNpub ?: "unavailable"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.owner"))
        TextButton(onClick = { push(AdminRoute.Profile) }, enabled = session.canAdminister, modifier = Modifier.testTag("admin.profile.open")) { Text("Edit name, address, description and avatar") }
        TextButton(onClick = { push(AdminRoute.DeleteWorkspace) }, enabled = session.canAdminister, modifier = Modifier.testTag("admin.delete.open")) { Text("Delete workspace…") }
    }
}

@Composable
private fun ProfilePage(session: AdministrationSession, workspace: WorkspaceRecord, back: () -> Unit) {
    val initial = mapOf(
        "name" to workspace.label, "slug" to (workspace.slug ?: ""), "description" to (workspace.raw.string("description") ?: ""),
        "avatar" to (workspace.raw.string("avatar_url") ?: ""),
    )
    val form = remember(session, workspace.id) { FormState(session, "profile", initial) }
    AdminPage("Edit workspace", session, back, "admin.profilePage") {
        AdminField(form, "name", "Name"); AdminField(form, "slug", "Address (slug)")
        Text("Changing the address changes workspace links. Confirm the new address before saving.", style = MaterialTheme.typography.bodySmall)
        AdminField(form, "description", "Description", singleLine = false); AdminField(form, "avatar", "Avatar HTTPS or storage URL")
        AvatarUpload(session, form)
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Apply this workspace change?",
            confirmText = "This changes ${workspace.label} for everyone immediately. A changed address changes workspace links. It is not a personal device preference.") {
            send(session, form, AdministrationCommand.Profile(form["name"], form["description"], form["avatar"].trim(), form["slug"].trim().ifEmpty { null }))
        }
    }
}

/**
 * Uploads a public workspace avatar: the image is read here, stripped of metadata and bounded off the main thread, and only after
 * an explicit consent that it becomes publicly readable is it sent. The address lands in the form; the person still saves the
 * workspace. A failure leaves the avatar field exactly as it was.
 */
@Composable
private fun AvatarUpload(session: AdministrationSession, form: FormState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var prepared by remember { mutableStateOf<ByteArray?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = null
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { readAtMost(it, WorkspaceAdministrator.MAX_AVATAR_BYTES + 1) } ?: error("The image could not be read.")
                    if (bytes.size > WorkspaceAdministrator.MAX_AVATAR_BYTES) error("Choose an image smaller than 8 MB.")
                    studio.otherstuff.ambulando.app.media.ImagePrivacy.strippedPng(bytes, maxPixelSize = 1024)
                }
            }
            if (!session.isCurrent) return@launch
            result.onSuccess { prepared = it }.onFailure { problem = it.message ?: "The image could not be read." }
        }
    }
    TextButton(
        onClick = { picker.launch(arrayOf("image/*")) }, enabled = session.canAdminister && session.phase !is Phase.Working && form.loaded,
        modifier = Modifier.testTag("admin.avatar.choose"),
    ) { Text("Choose avatar image…") }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.avatar.problem")) }
    prepared?.let { png ->
        ConfirmDialog(
            "Upload a public workspace avatar?", "The image will be publicly readable on Tower. Location and other image metadata are removed. Save the workspace afterwards to use it as its avatar.",
            "Upload public avatar", "admin.avatar",
            onConfirm = {
                prepared = null
                var address = ""
                session.run(working = "Uploading public avatar…", done = "Avatar uploaded. Save the workspace to use it.", block = { address = session.admin.uploadWorkspaceAvatar(png) }, then = { form["avatar"] = address })
            },
            onDismiss = { prepared = null },
        )
    }
}

@Composable
private fun DeleteWorkspacePage(session: AdministrationSession, workspace: WorkspaceRecord, back: () -> Unit, done: () -> Unit) {
    var typed by remember(session) { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    AdminPage("Delete workspace", session, back, "admin.deletePage") {
        Text(
            "Deleting this workspace on Tower affects every member and removes their workspace access. Tower's owner and protected-workspace safeguards apply. This is not removing a saved device preference.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(typed, { typed = it }, label = { Text("Type ${workspace.label} to confirm") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin.delete.name"))
        TextButton(
            onClick = { confirming = true },
            enabled = session.canAdminister && workspace.label.isNotBlank() && typed == workspace.label && session.phase !is Phase.Working && session.isCurrent,
            modifier = Modifier.testTag("admin.delete.go"),
        ) { Text("Delete workspace…") }
        if (confirming) ConfirmDialog(
            "Delete ${workspace.label} for everyone?", "This changes Tower, affects all members and cannot be undone here. No local data is erased by this screen.", "Delete workspace on Tower", "admin.delete",
            onConfirm = { confirming = false; session.write(AdministrationCommand.DeleteWorkspace(workspace.id)) { done() } }, onDismiss = { confirming = false },
        )
    }
}

// MARK: Members

@Composable
private fun MembersPage(session: AdministrationSession, push: (AdminRoute) -> Unit, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AdminPage("Members", session, back, "admin.membersPage") {
        TextButton(onClick = { push(AdminRoute.MemberAdd) }, enabled = session.canAdminister, modifier = Modifier.testTag("admin.member.add")) { Text("Add workspace member") }
        OutlinedTextField(query, { query = it }, label = { Text("Find workspace members") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin.member.search"))
        ListStatus(session, AdministrationList.MEMBERS, "members", "admin.members")
        val members = GroupDirectory.search(session.rows(AdministrationList.MEMBERS), query)
        for (row in members) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RowSummary(row, "admin.member.${row.id}", row.fields["npub"]?.let { shortKey(it) })
                TextButton(onClick = { push(AdminRoute.MemberProfile(row.id)) }, enabled = session.canAdminister, modifier = Modifier.testTag("admin.member.edit.${row.id}")) { Text("Edit name") }
            }
        }
        if (members.isEmpty() && session.listState[AdministrationList.MEMBERS] != null) Text("No saved members. Refresh to load the directory.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MemberAddPage(session: AdministrationSession, back: () -> Unit) {
    val form = remember(session) { FormState(session, "member", mapOf("kind" to "human")) }
    AdminPage("Add workspace member", session, back, "admin.memberAddPage") {
        AdminField(form, "npub", "Full public key (npub)")
        AdminChoice(form, "kind", "Member type", listOf("human" to "Person", "agent" to "Agent"))
        Text("Adds a member with the member role. Use a group or channel grant to give access.", style = MaterialTheme.typography.bodySmall)
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Add this member?",
            confirmText = "Adds ${form["npub"].take(16)}… to ${session.workspaceLabel} as a member. Tower confirms the identity.") {
            send(session, form, AdministrationCommand.AddMember(form["npub"].trim(), form["kind"]))
        }
    }
}

@Composable
private fun MemberProfilePage(session: AdministrationSession, actorId: String, back: () -> Unit) {
    val row = session.rows(AdministrationList.MEMBERS).firstOrNull { it.id == actorId }
    if (row == null) {
        // The form's starting values come from the saved row: it is not drawn until the row is there.
        AdminPage("Edit member display name", session, back, "admin.memberProfilePage") {
            Text(if (session.listState[AdministrationList.MEMBERS] == null) "Loading this member…" else "This member is not in the saved directory. Refresh the members.", modifier = Modifier.testTag("admin.member.missing"))
        }
        return
    }
    val form = remember(session, actorId) { FormState(session, "member-profile:$actorId", mapOf("name" to row.title)) }
    AdminPage("Edit member display name", session, back, "admin.memberProfilePage") {
        Text(row.title)
        AdminField(form, "name", "Display name")
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Change this display name?",
            confirmText = "The new name is shown to everyone in ${session.workspaceLabel}.") {
            send(session, form, AdministrationCommand.MemberProfile(actorId, form["name"]))
        }
    }
}

/** Builds-and-sends one command from a form; an invalid form is reported without sending anything. */
internal fun send(session: AdministrationSession, form: FormState, command: AdministrationCommand) {
    try {
        command.request(session.admin.workspaceId)
    } catch (e: ManagementError) {
        session.reject(e)
        return
    }
    session.write(command) { form.acknowledge() }
}

// MARK: Groups

@Composable
private fun GroupsPage(session: AdministrationSession, push: (AdminRoute) -> Unit, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AdminPage("Groups", session, back, "admin.groupsPage") {
        TextButton(onClick = { push(AdminRoute.GroupNew) }, enabled = session.canAdminister, modifier = Modifier.testTag("admin.group.new")) { Text("New group") }
        OutlinedTextField(query, { query = it }, label = { Text("Find groups") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin.group.search"))
        ListStatus(session, AdministrationList.GROUPS, "groups", "admin.groups")
        val groups = GroupDirectory.search(session.rows(AdministrationList.GROUPS), query)
        for (group in groups) {
            TextButton(onClick = { push(AdminRoute.Group(group.id)) }, modifier = Modifier.fillMaxWidth().testTag("admin.group.${group.id}")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(group.title)
                    Text("${group.children.size} people · ${group.childGroupIds.size} nested groups" + (group.fields["kind"]?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (groups.isEmpty() && session.listState[AdministrationList.GROUPS] != null) Text("No saved groups. Refresh to load groups.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GroupNewPage(session: AdministrationSession, back: () -> Unit) {
    val form = remember(session) { FormState(session, "group", emptyMap()) }
    AdminPage("New group", session, back, "admin.groupNewPage") {
        AdminField(form, "name", "Group name")
        SaveBar(form, session, allowed = session.canAdminister, confirmTitle = "Create this group?", confirmText = "Creates a custom group in ${session.workspaceLabel}.") {
            send(session, form, AdministrationCommand.CreateGroup(form["name"]))
        }
    }
}

@Composable
private fun GroupPage(session: AdministrationSession, groupId: String, back: () -> Unit) {
    val groups = session.rows(AdministrationList.GROUPS)
    val members = session.rows(AdministrationList.MEMBERS)
    val group = groups.firstOrNull { it.id == groupId }
    val form = remember(session, groupId) { FormState(session, "group-member:$groupId", mapOf("kind" to "human")) }
    val childForm = remember(session, groupId) { FormState(session, "child-group:$groupId", emptyMap()) }
    var pending by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    AdminPage(group?.title ?: "Group", session, back, "admin.groupPage") {
        if (group == null) {
            Text("This group is no longer available. Refresh the groups.", modifier = Modifier.testTag("admin.group.gone"))
            return@AdminPage
        }
        Text("${group.children.size} people · ${group.childGroupIds.size} nested groups", style = MaterialTheme.typography.bodySmall)
        Text("People", style = MaterialTheme.typography.titleSmall)
        for (person in group.children) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RowSummary(person, "admin.group.person.${person.id}", person.fields["npub"]?.let { shortKey(it) })
                TextButton(
                    onClick = { pending = Triple("Remove from group?", "${person.title} stays in the workspace and keeps any access from other groups or grants.") { session.write(AdministrationCommand.RemoveGroupMember(groupId, person.id)) } },
                    enabled = session.canAdminister && session.phase !is Phase.Working, modifier = Modifier.testTag("admin.group.person.remove.${person.id}"),
                ) { Text("Remove") }
            }
        }
        if (group.children.isEmpty()) Text("No direct people.", style = MaterialTheme.typography.bodySmall)
        Text("Nested groups", style = MaterialTheme.typography.titleSmall)
        for (childId in group.childGroupIds) {
            val child = groups.firstOrNull { it.id == childId }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(child?.title ?: "Unavailable group", modifier = Modifier.testTag("admin.group.child.$childId"))
                TextButton(
                    onClick = { pending = Triple("Detach this group?", "Detaching does not delete ${child?.title ?: "it"} or invite its members to ${group.title}; they only lose access that came through this nesting.") { session.write(AdministrationCommand.ChildGroup(groupId, childId, remove = true)) } },
                    enabled = session.canAdminister && session.phase !is Phase.Working, modifier = Modifier.testTag("admin.group.child.detach.$childId"),
                ) { Text("Detach") }
            }
        }
        if (group.childGroupIds.isEmpty()) Text("No nested groups.", style = MaterialTheme.typography.bodySmall)

        Text("Add a person", style = MaterialTheme.typography.titleSmall)
        val inGroup = group.children.mapNotNull { it.fields["npub"] }.toSet()
        RowPicker(form, "member", "Workspace member", members.filter { it.fields["npub"] != null }.map {
            PickOption(it.fields["npub"]!!, it.title, shortKey(it.fields["npub"]!!), keywords = it.id, state = if (it.fields["npub"] in inGroup) "Already in this group" else null)
        }, enabled = session.canAdminister, emptyLabel = "No saved members")
        Text("Or someone outside the workspace:", style = MaterialTheme.typography.bodySmall)
        AdminField(form, "npub", "Full public key (npub)", enabled = session.canAdminister)
        AdminChoice(form, "kind", "Type (outside the workspace)", listOf("human" to "Person", "agent" to "Agent"), enabled = session.canAdminister)
        val chosen = form["member"].ifEmpty { form["npub"].trim() }
        val existing = members.any { it.fields["npub"] == chosen }
        val allowed = session.canAdminister && NostrKeys.isValidNpub(chosen)
        SaveBar(
            form, session, allowed = allowed, saveLabel = if (existing) "Add to group" else "Enrol and add to group",
            confirmTitle = if (existing) "Add to ${group.title}?" else "Enrol a new identity and add to ${group.title}?",
            confirmText = if (existing) "$chosen joins ${group.title}. Their workspace role is unchanged."
            else "This adds ${chosen} (${if (form["kind"] == "agent") "an agent" else "a person"}) to ${session.workspaceLabel} as a member and to ${group.title} in one step. Tower keeps an existing role and type. An older Tower may refuse; nothing is retried automatically.",
        ) {
            session.run(done = "Saved on Tower.", block = {
                if (existing) session.admin.addExistingGroupPerson(groupId, chosen) else session.admin.enrolGroupPerson(groupId, chosen, form["kind"])
                session.loadCached(AdministrationList.GROUPS); session.loadCached(AdministrationList.MEMBERS)
            }, then = { form.acknowledge() })
        }

        Text("Add a nested group", style = MaterialTheme.typography.titleSmall)
        RowPicker(childForm, "child", "Group to nest in ${group.title}", groups.filter { it.id != groupId }.map {
            PickOption(it.id, it.title, "${it.children.size} people", GroupDirectory.attachmentState(groupId, it.id, groups))
        }, enabled = session.canAdminister)
        SaveBar(childForm, session, allowed = session.canAdminister && childForm["child"].isNotEmpty(), saveLabel = "Attach group",
            confirmTitle = "Attach this group?", confirmText = "Members of the attached group gain whatever ${group.title} grants. Tower checks for cycles and protected groups.") {
            send(session, childForm, AdministrationCommand.ChildGroup(groupId, childForm["child"], remove = false))
        }
    }
    pending?.let { (title, text, action) ->
        ConfirmDialog(title, text, "Confirm", "admin.pending", onConfirm = { pending = null; action() }, onDismiss = { pending = null })
    }
}

internal fun shortKey(npub: String) = if (npub.length > 20) npub.take(12) + "…" + npub.takeLast(6) else npub

/** Reads at most [limit] bytes (one more than a limit reveals an oversize file without loading all of it). `readNBytes` needs API 33. */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
