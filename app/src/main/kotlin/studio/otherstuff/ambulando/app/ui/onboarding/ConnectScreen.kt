package studio.otherstuff.ambulando.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.EditorialStyle
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.PrimaryButton
import studio.otherstuff.ambulando.app.ui.theme.ambCard

/** What the public nStack descriptor says, as shown before connecting. */
data class DescriptorSummaryModel(val towerStatus: String, val protocols: List<String>, val services: List<String>, val advertisesFips: Boolean)

/**
 * Connect to the user's nStack/Tower over HTTPS: read the public descriptor, then bind the origin to the unlocked
 * identity and start discovery. FIPS endpoints are refused, never silently swapped for HTTPS.
 */
@Composable
fun ConnectScreen(
    npub: String?,
    notice: String?,
    checkDescriptor: suspend (origin: String) -> Result<DescriptorSummaryModel>,
    connect: suspend (origin: String) -> Unit,
) {
    val amb = Amb.colors
    var origin by remember { mutableStateOf("") }
    var descriptor by remember { mutableStateOf<DescriptorSummaryModel?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(amb.background).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        BrandHeader(npub?.let { "Signed in as ${shortNpub(it)}" })
        Column(Modifier.ambCard(amb), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect your nStack", style = EditorialStyle, color = amb.primary)
            Text(
                "Enter the HTTPS address of your Tower. Ambulando reads its public service descriptor first, then lists the " +
                    "workspaces your identity can access.",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground,
            )
            OutlinedTextField(
                value = origin, singleLine = true, label = { Text("https://your-tower.example") },
                onValueChange = { origin = it.trim(); descriptor = null; checkError = null },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("connect.origin"),
            )
            checkError?.let { Notice(NoticeTone.ERROR, it) }
            descriptor?.let { DescriptorSummary(it) }
            notice?.let { Notice(NoticeTone.ERROR, it) }
            val current = descriptor
            if (current == null) {
                PrimaryButton(if (working) "…" else "Check service", enabled = origin.isNotEmpty() && !working, modifier = Modifier.testTag("connect.check"), onClick = {
                    working = true
                    scope.launch {
                        checkDescriptor(origin).fold({ descriptor = it }, { checkError = it.message ?: "The service could not be checked." })
                        working = false
                    }
                })
            } else {
                PrimaryButton(if (working) "…" else "Connect and discover workspaces", enabled = !working, modifier = Modifier.testTag("connect.go"), onClick = {
                    working = true
                    scope.launch { try { connect(origin) } finally { working = false } }
                })
            }
        }
    }
}

@Composable
fun DescriptorSummary(d: DescriptorSummaryModel) {
    val amb = Amb.colors
    Column(
        Modifier.fillMaxWidth().background(amb.accent.copy(alpha = 0.6f), RoundedCornerShape(Amb.radiusControl)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Tower ${d.towerStatus}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = amb.foreground)
        Text("Protocols: " + d.protocols.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
        Text("Services: " + d.services.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
        Text(
            if (d.advertisesFips) "A FIPS service is advertised, but this Android app uses HTTPS only and will not switch transports."
            else "Transport: HTTPS with NIP-98 signed requests.",
            style = MaterialTheme.typography.labelSmall, color = if (d.advertisesFips) amb.warning else amb.success,
        )
    }
}
