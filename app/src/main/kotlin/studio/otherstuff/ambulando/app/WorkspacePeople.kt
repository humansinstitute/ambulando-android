package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.management.AccessDirectory
import studio.otherstuff.ambulando.core.management.AccessGrant
import studio.otherstuff.ambulando.core.management.AccessGroup
import studio.otherstuff.ambulando.core.store.directoryMembers
import studio.otherstuff.ambulando.core.store.liveQuery
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

/** What a read can come back with, shown instead of an empty list so "nothing" is never mistaken for "not allowed". */
private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val rows: List<T>) : Loaded<T>
    data class Unavailable(val message: String) : Loaded<Nothing>
}

private fun refusal(e: Exception, what: String): String = when {
    e is TowerError.Http && (e.status == 401 || e.status == 403) -> "Tower shows $what to workspace administrators and channel managers only."
    e is TowerError.Http && (e.status == 404 || e.status == 405 || e.status == 501) -> "This Tower does not offer $what to this app."
    else -> "Could not read $what. Check your connection and try again."
}

/**
 * Who is in the workspace and who can reach this channel: members from the synced directory, groups (administrators
 * only, as Tower decides) and the grants on the selected channel. Read only: adding people, groups and grants stays in the
 * web app, and a refusal is explained rather than shown as an empty list.
 */
@Composable internal fun WorkspacePeople(model: AppModel) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val members by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.directoryMembers(w.id) } }.collectAsState(emptyList())
    val channel = model.channels.firstOrNull { it.id == model.context.channelId && !it.accessRemoved }
    var groups by remember(w.id) { mutableStateOf<Loaded<AccessGroup>>(Loaded.Loading) }
    var grants by remember(w.id, channel?.id) { mutableStateOf<Loaded<AccessGrant>>(Loaded.Loading) }
    var attempt by remember(w.id) { mutableIntStateOf(0) }
    LaunchedEffect(w.id, attempt) {
        val client = TowerClient(TowerOrigin(c.origin), model.transport, model.identity)
        groups = try { Loaded.Ready(model.engine.perform("access-groups:${w.id}", SyncPriority.USER) { AccessDirectory.groups(client, w) }) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Loaded.Unavailable(refusal(e, "groups")) }
    }
    LaunchedEffect(w.id, channel?.id, attempt) {
        val id = channel?.id ?: return@LaunchedEffect
        val client = TowerClient(TowerOrigin(c.origin), model.transport, model.identity)
        grants = try { Loaded.Ready(model.engine.perform("access-grants:${w.id}:$id", SyncPriority.USER) { AccessDirectory.channelGrants(client, w, id) }) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Loaded.Unavailable(refusal(e, "this channel's access")) }
    }
    Column(Modifier.fillMaxWidth().testTag("people.screen"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("People and access", style = MaterialTheme.typography.titleMedium)
        Text("Members (${members.size})", style = MaterialTheme.typography.labelLarge)
        members.sortedBy { it.label.lowercase() }.forEach { member ->
            Text(member.label + (if (member.isAgent) " · agent" else ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("people.member.${member.npub}"))
        }
        Text("Groups", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        when (val state = groups) {
            Loaded.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
            is Loaded.Unavailable -> Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("people.groups.unavailable"))
            is Loaded.Ready -> {
                if (state.rows.isEmpty()) Text("This workspace has no groups.", style = MaterialTheme.typography.bodySmall)
                val names = state.rows.associate { it.id to it.name }
                state.rows.sortedBy { it.name.lowercase() }.forEach { group ->
                    Column(Modifier.testTag("people.group.${group.id}")) {
                        Text(group.name + " (${group.members.size})", style = MaterialTheme.typography.bodyMedium)
                        Text(group.members.joinToString(", ") { it.name }.ifEmpty { "No members" } +
                            (group.childGroupIds.takeIf { it.isNotEmpty() }?.let { ids -> " · includes " + ids.joinToString(", ") { names[it.lowercase()] ?: "another group" } } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (channel != null) {
            Text("Access to #${channel.name}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            when (val state = grants) {
                Loaded.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                is Loaded.Unavailable -> Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("people.grants.unavailable"))
                is Loaded.Ready -> {
                    if (state.rows.isEmpty()) Text("No individual grants. Access may come from the scope or from groups.", style = MaterialTheme.typography.bodySmall)
                    state.rows.forEach { grant ->
                        Text(grant.name + (grant.principalType?.let { " · $it" } ?: "") + " — " + grant.permissions.joinToString(", ").ifEmpty { "no permissions listed" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else Text("Select a channel in the sidebar to see who can reach it.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { attempt += 1 }) { Text("Refresh") }
        Text("Adding people, groups and grants is done in the web app.", style = MaterialTheme.typography.labelSmall)
    }
}
