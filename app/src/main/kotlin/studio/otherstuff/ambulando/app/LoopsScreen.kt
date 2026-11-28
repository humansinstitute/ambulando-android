package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun LoopsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp).testTag("loops.page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Loops", style = MaterialTheme.typography.headlineMedium)
        Text("Regular work and loops we delegate to our agents.")
        Text("Preview · No work is scheduled or running from this page.", style = MaterialTheme.typography.bodyMedium)
        Text("Example: a weekly review of progress toward your goals.")
        Text("Example: a daily briefing of updates that need your attention.")
    }
}
