package studio.otherstuff.ambulando.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.transport.ConnectionFailure
import studio.otherstuff.ambulando.core.transport.TowerError

/**
 * First-use setup: create the owner's private Goals channel in a scope the owner chooses (Home is suggested, never
 * assumed) and say which agents may work on goals. Everything is explicit; one grant per agent covers every goal.
 */
@Composable internal fun GoalsSetupCard(goals: GoalsController, setup: GoalsSpaceState.NeedsSetup) {
    var scopeId by remember(setup) { mutableStateOf(setup.suggested?.id) }
    var chosen by remember(setup) { mutableStateOf(emptySet<String>()) }
    var message by remember(setup) { mutableStateOf<String?>(null) }
    var refused by remember(setup) { mutableStateOf(false) }
    var busy by remember(setup) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) { busy = true; message = null; scope.launch {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            refused = e is TowerError.Http && (e.status == 403 || e.status == 404)
            message = if (refused) "You cannot create a private space in that scope. Choose another scope, or use the owner-only space below." else ConnectionFailure.userMessage(e) ?: "Could not set up Goals."
        } finally { busy = false }
    } }
    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Amb.colors.border), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(16.dp).testTag("goals.setup"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set up your private Goals space", style = MaterialTheme.typography.titleMedium, color = Amb.colors.foreground)
        Text("Goals are kept in a private channel only you can open. Pick where it lives and which agents may help with your goals. You can change agent access later.", fontSize = AmbType.footnote, color = Amb.colors.mutedForeground)
        Text("Where", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
        if (setup.scopes.isEmpty()) Text("No scope is available to hold it.", color = Amb.colors.mutedForeground)
        setup.scopes.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = scopeId == s.id, onClick = { scopeId = s.id }, modifier = Modifier.testTag("goals.setup.scope.${s.id}"))
                Text(s.name + if (s.id == setup.suggested?.id) "  (suggested)" else "", color = Amb.colors.foreground)
            }
        }
        if (setup.agents.isNotEmpty()) {
            Text("Agents that may add and edit goals", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
            setup.agents.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = a.actorId in chosen, onCheckedChange = { on -> chosen = if (on) chosen + a.actorId else chosen - a.actorId }, modifier = Modifier.testTag("goals.setup.agent"))
                    Text(a.label, color = Amb.colors.foreground)
                }
            }
        }
        message?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("goals.setup.error")) }
        Button(enabled = !busy && scopeId != null, onClick = { run { goals.setUp(scopeId, chosen.toList()) } }, modifier = Modifier.testTag("goals.setup.create")) { Text("Create private Goals space") }
        if (refused || setup.scopes.isEmpty()) {
            Text("Owner-only space: goals stay in your private conversation. No agent can read or edit them there.", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
            OutlinedButton(enabled = !busy, onClick = { run { goals.setUp(null, emptyList()) } }, modifier = Modifier.testTag("goals.setup.ownerOnly")) { Text("Use owner-only space") }
        }
    }
}

/** Which workspace agents may work on the owner's goals. One switch per agent covers every goal. */
@Composable internal fun AgentAccessDialog(goals: GoalsController, ready: GoalsSpaceState.Ready, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("goals.access")) {
                Text("Agents you turn on can read, create and edit your goals. They see only this Goals space.", fontSize = AmbType.footnote, color = Amb.colors.mutedForeground)
                if (ready.agents.isEmpty()) Text("No agents are in this workspace.", color = Amb.colors.mutedForeground)
                ready.agents.forEach { a ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.member.label, Modifier.weight(1f), color = Amb.colors.foreground)
                        Switch(checked = a.access != null, enabled = !busy, modifier = Modifier.testTag("goals.access.switch"), onCheckedChange = { on ->
                            busy = true; message = null
                            scope.launch {
                                try { goals.setAgentAccess(a.member, on) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { message = ConnectionFailure.userMessage(e) ?: "Could not change access." }
                                finally { busy = false }
                            }
                        })
                    }
                }
                message?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
