package studio.otherstuff.ambulando.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.AppModel

/** Tower choice is separate from the existing explicit multi-workspace chooser. Manual origin entry stays available. */
@Composable
fun DiscoveryConnectScreen(model: AppModel, done: () -> Unit = {}) {
    var manual by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (manual || model.environment.isSample) {
        Column(Modifier.fillMaxSize()) {
            if (!model.environment.isSample) TextButton(onClick = { manual = false }) { Text("Back to discovered Towers") }
            Box(Modifier.weight(1f)) {
                ConnectScreen(model.identityState?.npub, model.notice, { origin -> runCatching {
                    val d = model.descriptor(origin)
                    DescriptorSummaryModel(d.tower?.availability?.status ?: "Available", d.tower?.protocols ?: emptyList(), d.services.map { it.kind }, d.advertisesFIPS)
                } }) { origin -> model.connect(origin); done() }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Choose a Tower", style = MaterialTheme.typography.headlineMedium)
        Text(model.towerDiscoveryMessage, modifier = Modifier.testTag("discovery.status"))
        if (model.towerDiscoveryBusy || connecting) CircularProgressIndicator()
        model.discoveredTowers.forEach { tower ->
            OutlinedButton(enabled = !connecting && !model.towerDiscoveryBusy,
                modifier = Modifier.fillMaxWidth().testTag("discovery.tower.${tower.origin}"), onClick = {
                    connecting = true; error = null
                    scope.launch {
                        try { model.connectDiscoveredTower(tower); done() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "This Tower could not be connected. Discover again or enter its address." }
                        finally { connecting = false }
                    }
                }) { Column { Text(tower.label); Text(tower.origin, style = MaterialTheme.typography.bodySmall) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = !connecting && !model.towerDiscoveryBusy, onClick = model::discoverTowers, modifier = Modifier.testTag("discovery.retry")) { Text("Discover Towers again") }
        TextButton(enabled = !connecting, onClick = { manual = true }, modifier = Modifier.testTag("discovery.manual")) { Text("Enter a Tower address") }
        Text("Discovery never enables a workspace automatically. Choose which workspaces to enable after selecting a Tower.", style = MaterialTheme.typography.bodySmall)
    }
}
