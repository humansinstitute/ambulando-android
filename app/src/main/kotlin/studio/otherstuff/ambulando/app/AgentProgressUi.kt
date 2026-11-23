package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.surfaces.*

/** Public producer updates only. Expansion belongs to one lifecycle, not the reused message position. */
@Composable internal fun AgentProgressCard(activity: AgentActivityRecord, now: Long, current: Boolean, agentName: String = "Agent", loadEarlier: () -> Unit) {
    var expanded by remember(activity.activityId, activity.turnId) { mutableStateOf(false) }
    var fullLatest by remember(activity.activityId, activity.turnId) { mutableStateOf(false) }
    val history = activity.commentary
    val latest = history.lastOrNull()?.body ?: activity.body ?: activity.summary
    var latestOverflow by remember(latest) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(studio.otherstuff.ambulando.app.ui.theme.Amb.radiusPanel), modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("chat.agent.${activity.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${agentName} · ${if (current || activity.isTerminal) activity.progressStatus(now) else "Earlier activity · status unconfirmed"}", style = MaterialTheme.typography.labelLarge)
            latest?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (fullLatest) Int.MAX_VALUE else 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!fullLatest) latestOverflow = layout.hasVisualOverflow })
                if (fullLatest || latestOverflow) TextButton(onClick = { fullLatest = !fullLatest }) {
                    Text(if (fullLatest) "Show less" else "Read full update")
                }
            }
            if (activity.state in setOf("failed", "cancelled")) (activity.body ?: activity.summary)?.takeIf { it != latest }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (!activity.isTerminal && activity.progressStatus(now) != "Stopped responding") {
                Text("Public updates · status as last received", style = MaterialTheme.typography.labelSmall)
            }
            if (history.isNotEmpty() || activity.historyBefore != null) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat.agent.history.${activity.id}")) {
                    Text(if (expanded) "Hide update history" else "Show update history (${history.size})")
                }
                if (expanded) {
                    if (activity.historyBefore != null) TextButton(onClick = loadEarlier) { Text("Load earlier updates") }
                    history.forEach { entry ->
                        Text(entry.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("chat.agent.update.${activity.id}.${entry.sequence}"))
                    }
                }
            }
        }
    }
}
