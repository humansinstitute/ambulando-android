package studio.otherstuff.ambulando.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.R
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.CodeStyle
import studio.otherstuff.ambulando.app.ui.theme.EditorialStyle
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.PrimaryButton
import studio.otherstuff.ambulando.app.ui.theme.ambCard

fun shortNpub(npub: String): String = if (npub.length > 20) "${npub.take(12)}…${npub.takeLast(6)}" else npub

/** Approved passage artwork on its original white backing (never recoloured or clipped beyond its rounded tile). */
@Composable
fun PassageMark(size: Dp = 32.dp) {
    Image(
        painter = painterResource(R.drawable.passage_mark), contentDescription = "Ambulando",
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.18f)),
    )
}

@Composable
fun BrandHeader(subtitle: String?) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PassageMark(64.dp)
        Text("Ambulando", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold), color = amb.foreground)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = amb.mutedForeground, textAlign = TextAlign.Center) }
    }
}

@Composable
internal fun SecretField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean, tag: String,
                         modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = if (numeric) MaterialTheme.typography.bodyLarge else CodeStyle,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
            autoCorrectEnabled = false, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

private fun BusyLabel(working: Boolean, text: String) = if (working) "…" else text

/**
 * Explicit identity import. The user pastes their own nsec; the app never searches for or reads keys held by other
 * apps or the browser. The scalar is sealed with the PIN and a non-exportable Android Keystore key.
 */
@Composable
fun ImportIdentityScreen(notice: String?, onImport: suspend (nsec: String, pin: String) -> Unit) {
    val amb = Amb.colors
    var nsec by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = nsec.trim().startsWith("nsec1") && pin.length in 4..12 && pin.all(Char::isDigit) && pin == confirm
    Column(
        Modifier.fillMaxSize().background(amb.background).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        BrandHeader("Solvitur ambulando — it is solved by walking.")
        Column(Modifier.ambCard(amb), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import your identity", style = EditorialStyle, color = amb.primary)
            Text(
                "Paste the nsec you use with Ambulando. It is encrypted on this device with your PIN and a key held by the " +
                    "Android Keystore, and never leaves this app.",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground,
            )
            SecretField(nsec, { nsec = it }, "nsec1…", numeric = false, tag = "import.nsec")
            SecretField(pin, { pin = it.filter(Char::isDigit).take(12) }, "PIN (4–12 digits)", numeric = true, tag = "import.pin")
            SecretField(confirm, { confirm = it.filter(Char::isDigit).take(12) }, "Confirm PIN", numeric = true, tag = "import.confirm")
            notice?.let { Notice(NoticeTone.ERROR, it) }
            PrimaryButton(BusyLabel(working, "Import and encrypt"), enabled = valid && !working, modifier = Modifier.testTag("import.submit"), onClick = {
                working = true
                scope.launch {
                    try { onImport(nsec.trim(), pin) } finally { nsec = ""; pin = ""; confirm = ""; working = false }
                }
            })
        }
    }
}

/**
 * Full-screen unlock shown whenever an existing identity is locked. The PIN field is focused at once; a wrong PIN
 * says so and keeps the field ready. Nothing behind it is lost: selection, navigation, drafts and unsent messages
 * return when it opens. Once unlocked, the app stays unlocked on this device until Lock now.
 */
@Composable
fun LockScreen(npub: String?, notice: String?, onUnlock: suspend (pin: String) -> Unit) {
    val amb = Amb.colors
    var pin by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val unlock = {
        if (pin.length >= 4 && !working) {
            working = true
            scope.launch {
                try { onUnlock(pin) } finally { pin = ""; working = false; runCatching { focus.requestFocus() } }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(amb.background).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PassageMark(88.dp)
            Text("Ambulando", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold), color = amb.foreground)
            Text("Locked", style = MaterialTheme.typography.titleMedium, color = amb.primary, modifier = Modifier.testTag("lock.screen"))
            npub?.let { Text(shortNpub(it), style = CodeStyle, color = amb.mutedForeground) }
        }
        Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecretField(pin, { pin = it.filter(Char::isDigit).take(12) }, "PIN", numeric = true, tag = "lock.pin",
                modifier = Modifier.focusRequester(focus), onDone = unlock)
            notice?.let { Notice(NoticeTone.ERROR, it, Modifier.testTag("lock.error")) }
            PrimaryButton(BusyLabel(working, "Unlock"), enabled = pin.length >= 4 && !working, modifier = Modifier.testTag("lock.unlock"), onClick = unlock)
            Text(
                "Enter your PIN to open your workspaces. Ambulando stays unlocked on this device until you choose Lock now.",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, textAlign = TextAlign.Center,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The identity is on this device but cannot be read right now (the Keystore refused because the device is locked, or
 * the vault file is damaged). Nothing is imported, replaced or deleted from here.
 */
@Composable
fun IdentityUnavailableScreen(damaged: Boolean, onRetry: suspend () -> Unit) {
    val amb = Amb.colors
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(amb.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        PassageMark(88.dp)
        Text("Your identity is on this device", style = MaterialTheme.typography.titleMedium, color = amb.primary,
            modifier = Modifier.testTag("identity.unavailable"))
        Text(
            if (damaged) "The identity file on this device could not be opened: it looks damaged. It has been left exactly as it is, " +
                "together with your Tower connection and saved work. Nothing was replaced."
            else "It can't be opened right now. This happens when Ambulando was started while the device was locked. Your key, " +
                "Tower connection and saved work are kept; nothing needs to be imported again.",
            style = MaterialTheme.typography.bodyMedium, color = amb.foreground, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp).semantics { contentDescription = if (damaged) "identity.damaged" else "identity.sealed" },
        )
        PrimaryButton(if (checking) "…" else "Try again", enabled = !checking, modifier = Modifier.widthIn(max = 360.dp).testTag("identity.retry"), onClick = {
            checking = true
            scope.launch { try { onRetry() } finally { checking = false } }
        })
        Text(
            "If this stays after unlocking your device, the identity file may be damaged. Leave it in place; do not uninstall the app.",
            style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}
