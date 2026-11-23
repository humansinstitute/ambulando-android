package studio.otherstuff.ambulando.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.surfaces.*

/** Owned by the open conversation so recycling a tail row cannot close an explicit disclosure. */
internal class AgentProgressDisclosure {
    private var phase: Pair<Boolean, Boolean>? = null
    var expanded by mutableStateOf(false)
    fun update(live: Boolean, terminal: Boolean) {
        val next = live to terminal
        if (phase != next) { phase = next; expanded = false }
    }
}

/** Public updates at the conversation tail. Finishing or expiring a turn resets its disclosure. */
@Composable internal fun AgentProgressCard(activity: AgentActivityRecord, now: Long, current: Boolean, agentName: String = "Agent", onDisclosure: () -> Unit = {}, disclosure: AgentProgressDisclosure? = null, loadEarlier: () -> Unit) {
    val status = activity.progressStatus(now)
    val live = current && !activity.isTerminal && status != "Stopped responding"
    val state = disclosure ?: remember(activity.activityId, activity.turnId) { AgentProgressDisclosure() }
    state.update(live, activity.isTerminal)
    var expanded by state::expanded
    val history = activity.commentary
    val latest = history.lastOrNull()?.body ?: activity.body ?: activity.summary
    val amb = Amb.colors
    val tint = if (status == "Stopped responding" || activity.state == "failed") amb.destructive else amb.mutedForeground
    val label = if (activity.isTerminal) "Earlier activity · ${status.lowercase()}" else status
    Surface(color = amb.muted.copy(alpha = if (live) 0.6f else 0.3f), shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("chat.agent.${activity.id}")) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().testTag("chat.agent.history.${activity.id}")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse activity" else "Expand activity") { onDisclosure(); expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live && activity.state == "working") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = amb.primary)
                else Icon(when {
                    status == "Stopped responding" -> Icons.Outlined.ErrorOutline
                    activity.state == "failed" -> Icons.Outlined.Cancel
                    activity.state == "queued" -> Icons.Outlined.Schedule
                    activity.state == "waiting" -> Icons.Outlined.PanTool
                    else -> Icons.Outlined.CheckCircle
                }, null, Modifier.size(14.dp), tint = tint)
                Text(agentName, fontSize = AmbType.caption, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                Text(label, fontSize = AmbType.caption, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("chat.agent.current.${activity.id}"))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(14.dp), tint = amb.mutedForeground)
            }
            if (expanded) {
                Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (activity.historyBefore != null) Text("Earlier updates", fontSize = AmbType.caption, color = amb.primary,
                        modifier = Modifier.clickable(role = Role.Button, onClick = loadEarlier).padding(vertical = 4.dp))
                    history.forEach { entry ->
                        Text(entry.body, fontSize = AmbType.caption, color = if (live) amb.foreground else amb.mutedForeground,
                            modifier = Modifier.testTag("chat.agent.update.${activity.id}.${entry.sequence}"))
                    }
                    if (history.isEmpty()) latest?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = AmbType.caption, color = amb.mutedForeground) }
                    if (activity.state in setOf("failed", "cancelled")) (activity.body ?: activity.summary)?.takeIf { it != latest }?.let {
                        Text(it, fontSize = AmbType.caption, color = tint)
                    }
                }
            } else if (live) latest?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = AmbType.caption, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp).testTag("chat.agent.latest.${activity.id}"))
            }
        }
    }
}
