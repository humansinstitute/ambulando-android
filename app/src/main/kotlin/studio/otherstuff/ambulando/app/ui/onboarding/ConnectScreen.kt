package studio.otherstuff.ambulando.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.CancellationException
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
    connectWorkspaceQr: (suspend (String, String) -> Unit)? = null,
    connect: suspend (origin: String) -> Unit,
) {
    val family = studio.otherstuff.ambulando.app.BuildConfig.GRANDPARENT_CHAT
    val amb = Amb.colors
    var origin by remember { mutableStateOf("") }
    var descriptor by remember { mutableStateOf<DescriptorSummaryModel?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<studio.otherstuff.ambulando.core.transport.WorkspaceConnection?>(null) }
    var qrMode by remember { mutableStateOf("https") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val fipsConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Activity result codes are not grant authority. The next explicit confirmation checks the provider.
        checkError = "Native approval returned. Confirm this workspace again to verify access."
    }
    val qrImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            working = true
            try {
                val value = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "Unreadable QR image" }
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
                    options.inJustDecodeBounds = false; options.inSampleSize = sample
                    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("Unreadable QR image")
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val codes = QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
                        require(codes.size == 1) { "Choose an image containing exactly one workspace QR" }
                        codes.single().text
                    } finally { bitmap.recycle() }
                }
                origin = value; qr = null; descriptor = null; checkError = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { checkError = "Could not scan workspace QR image. Paste the connection text or choose a clearer image." }
            finally { working = false }
        }
    }
    Column(
        Modifier.fillMaxSize().background(amb.background).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        BrandHeader(npub?.let { "Signed in as ${shortNpub(it)}" })
        Column(Modifier.ambCard(amb), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (family) "Connect to your family" else "Connect your nStack", style = EditorialStyle, color = amb.primary)
            Text(
                if (family) "Enter the connection address your family gave you. We will check which family chats you can join." else "Paste workspace QR text or enter the HTTPS address of your Tower. Ambulando reads its public service descriptor first, then lists the " +
                    "workspaces your identity can access.",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground,
            )
            OutlinedTextField(
                value = origin, singleLine = true, label = { Text("https://your-tower.example") },
                onValueChange = { origin = it; descriptor = null; qr = null; checkError = null },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("connect.origin"),
            )
            PrimaryButton("Scan workspace QR image", enabled = !working, onClick = { qrImage.launch("image/*") })
            checkError?.let { Notice(NoticeTone.ERROR, it) }
            descriptor?.let { if (family) Text("Connection found") else DescriptorSummary(it) }
            notice?.let { Notice(NoticeTone.ERROR, it) }
            val current = descriptor
            qr?.let { q ->
                Text("${q.name}\n${q.workspaceId}\n${q.serviceNpub}\n${q.authority}")
                Text("Trust the QR source before confirming. Your current identity must already have access.")
                q.modes.forEach { mode ->
                    PrimaryButton((if (qrMode == mode) "✓ " else "") + mode.uppercase(), enabled = !working, onClick = { qrMode = mode })
                }
                Text(q.payload.getValue("transports").toString())
                PrimaryButton("Confirm and verify workspace", enabled = !working, onClick = {
                    working = true
                    val confirmedOrigin = origin
                    val confirmedMode = qrMode
                    scope.launch {
                        try { connectWorkspaceQr!!(confirmedOrigin, confirmedMode) }
                        catch (e: CancellationException) { throw e }
                        catch (e: studio.otherstuff.ambulando.app.fips.FipsConsentRequired) {
                            checkError = e.message
                            try {
                                check(origin == confirmedOrigin && qrMode == confirmedMode) { "Connection selection changed" }
                                val launched = launchConsentIfResumed(lifecycle, e) { intent ->
                                    fipsConsent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                                }
                                if (!launched) checkError = "Return to this screen and confirm again to open native approval."
                            } catch (failure: Exception) {
                                e.abandonConsent()
                                val issue = (failure as? studio.otherstuff.ambulando.core.transport.SafeConnectionIssue)?.connectionIssue
                                checkError = if (issue != null && issue != studio.otherstuff.ambulando.core.transport.ConnectionIssue.CONSENT) issue.userMessage
                                    else "Native approval could not be opened. Confirm again to request fresh approval."
                            }
                        }
                        catch (e: Exception) { checkError = e.message ?: "Connection rejected" }
                        finally { working = false }
                    }
                })
            }
            if (current == null && qr == null) {
                PrimaryButton(if (working) "…" else if (family) "Check connection" else "Check service", enabled = origin.isNotEmpty() && !working, modifier = Modifier.testTag("connect.check"), onClick = {
                    working = true
                    scope.launch {
                        if (origin.startsWith("nstack:workspace:")) {
                            try {
                                check(connectWorkspaceQr != null) { "Workspace QR connection is unavailable in this app." }
                                qr = studio.otherstuff.ambulando.core.transport.WorkspaceConnection.parse(origin)
                                qrMode = qr!!.preferred
                            } catch (e: Exception) { checkError = e.message ?: "Invalid workspace QR" }
                        } else checkDescriptor(origin).fold({ descriptor = it }, { checkError = it.message ?: "The service could not be checked." })
                        working = false
                    }
                })
            } else if (current != null) {
                PrimaryButton(if (working) "…" else if (family) "Connect" else "Connect and discover workspaces", enabled = !working, modifier = Modifier.testTag("connect.go"), onClick = {
                    working = true
                    scope.launch {
                        try { connect(origin) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { checkError = e.message ?: "The Tower could not be connected. Retry or choose another address." }
                        finally { working = false }
                    }
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
            if (d.advertisesFips) "FIPS needs an approved shared Nostr Browser VPN connection. Select it explicitly from a signed workspace QR."
            else "Transport: HTTPS with NIP-98 signed requests.",
            style = MaterialTheme.typography.labelSmall, color = if (d.advertisesFips) amb.warning else amb.success,
        )
    }
}

/** Explicit tap only: neither a delayed reply nor resuming the activity may launch approval in the background. */
internal fun launchConsentIfResumed(
    lifecycle: androidx.lifecycle.Lifecycle,
    consent: studio.otherstuff.ambulando.app.fips.FipsConsentRequired,
    launch: (android.app.PendingIntent) -> Unit,
): Boolean {
    fun resumed() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    if (!resumed()) return false
    val intent = consent.takeConsent(::resumed) ?: return false
    // Runs on the UI thread without suspension. Fail closed if a custom handoff changed lifecycle synchronously.
    if (!resumed()) { consent.abandonConsent(); return false }
    launch(intent)
    return true
}
