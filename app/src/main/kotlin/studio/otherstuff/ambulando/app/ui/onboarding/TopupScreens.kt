package studio.otherstuff.ambulando.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.app.BuildConfig
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.EditorialStyle
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.PrimaryButton
import studio.otherstuff.ambulando.app.ui.theme.ambCard
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.onboarding.OnboardingFunding
import studio.otherstuff.ambulando.core.onboarding.TopupController
import studio.otherstuff.ambulando.core.onboarding.TopupMethod
import studio.otherstuff.ambulando.core.onboarding.TopupQuote
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Add credit to the hosted account after setup. No new workspace, invitation or introduction, and no agent is started or woken. */
@Composable
fun TopupFlow(model: AppModel, onClose: () -> Unit) {
    val amb = Amb.colors
    val npub = (model.identityState as? IdentityState.Unlocked)?.npub
    val origin = model.connection?.origin
    val gate = onboardingGate(model.environment.isSample, BuildConfig.GRANDPARENT_CHAT, npub)
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(amb.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Add credit", Modifier.weight(1f).padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("topup.close")) { Text("Close") }
        }
        if (gate != null || npub == null) { Notice(NoticeTone.INFO, gate ?: "Unlock your identity first.", Modifier.padding(16.dp).testTag("topup.gate")); return@Column }
        if (origin == null) { Notice(NoticeTone.INFO, "Connect to your NStack first. Credit is added to the account on that NStack.", Modifier.padding(16.dp).testTag("topup.gate")); return@Column }
        TopupScreen(model.onboarding.topup(TowerOrigin(origin), npub), launch = model::launchDetached)
    }
}

@Composable
internal fun TopupScreen(controller: TopupController, launch: (suspend () -> Unit) -> Unit) {
    val s by controller.snapshots.collectAsState()
    val context = LocalContext.current
    fun run(block: suspend () -> Unit) = launch { withContext(Dispatchers.IO) { block() } }
    LaunchedEffect(controller) { withContext(Dispatchers.IO) { controller.load() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (s.awaiting || s.awaitingLightning) run { controller.refresh() } }
    var custom by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<TopupMethod?>(null) }
    var local by remember { mutableStateOf<String?>(null) }
    val customCents = if (custom.isBlank()) null else OnboardingFunding.cents(custom, s.minCents, s.maxCents)
    val method = s.draft.attemptMethod ?: chosen ?: TopupMethod.CARD
    // The device clock only chooses what to show; NStack decides when an invoice has ended. One wake-up at the stated expiry.
    val expiry = s.quote?.expiresAt
    val now by produceState(Instant.now(), s.quote?.id, expiry) {
        value = Instant.now()
        if (expiry != null && value.isBefore(expiry)) { delay(minOf(Duration.between(value, expiry).toMillis() + 500, 3_600_000L)); value = Instant.now() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        s.failure?.let { Notice(NoticeTone.ERROR, it.userMessage, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("topup.failure")) }
        s.notice?.let { Notice(NoticeTone.INFO, it, Modifier.testTag("topup.notice")) }
        local?.let { Notice(NoticeTone.INFO, it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("topup.local")) }
        if (s.unreadable) return@Column
        Column(Modifier.ambCard(Amb.colors).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add credit to your hosted account", style = EditorialStyle, color = Amb.colors.primary, modifier = Modifier.semantics { heading() })
            Text(
                "This only adds credit. It does not create a workspace, use an invitation or start any agent. Credit counts only when NStack itself confirms the payment; coming back from the payment page or opening a wallet does not add it.",
                style = MaterialTheme.typography.bodyMedium, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("topup.explain"),
            )
            if (s.locked) Notice(NoticeTone.INFO, "A payment is already under way, so the amount and method are fixed until NStack reports it finished. Check the payment to continue.", Modifier.testTag("topup.locked"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnboardingFunding.PRESETS_CENTS.forEach { cents ->
                    FilterChip(selected = s.draft.amountCents == cents && custom.isBlank(), enabled = !s.locked, onClick = { custom = ""; run { controller.setAmount(cents) } },
                        label = { Text(OnboardingFunding.label(cents)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("topup.amount.$cents"))
                }
            }
            OutlinedTextField(custom, { custom = it; OnboardingFunding.cents(it, s.minCents, s.maxCents)?.let { c -> run { controller.setAmount(c) } } }, singleLine = true,
                enabled = !s.locked, label = { Text("Other amount in US$") }, isError = custom.isNotBlank() && customCents == null,
                supportingText = { Text("${OnboardingFunding.label(s.minCents)} to ${OnboardingFunding.label(s.maxCents)}, two decimals at most") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("topup.custom"))
            val amountOk = custom.isBlank() || customCents != null
            if (s.lightningOffered || s.draft.attemptMethod == TopupMethod.LIGHTNING) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = method == TopupMethod.CARD, enabled = !s.locked, onClick = { chosen = TopupMethod.CARD }, label = { Text("Card (test mode)") }, modifier = Modifier.heightIn(min = 48.dp).testTag("topup.method.card"))
                FilterChip(selected = method == TopupMethod.LIGHTNING, enabled = !s.locked, onClick = { chosen = TopupMethod.LIGHTNING }, label = { Text("Lightning") }, modifier = Modifier.heightIn(min = 48.dp).testTag("topup.method.lightning"))
            }
            if (method == TopupMethod.CARD) {
                s.unavailable?.let { Notice(NoticeTone.INFO, it, Modifier.testTag("topup.unavailable")) }
                val url = s.checkoutUrl
                if (url != null) PrimaryButton("Open payment page", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) } },
                    Modifier.heightIn(min = 48.dp).testTag("topup.open"))
                else PrimaryButton(if (s.draft.inFlight) "Get the payment page again" else "Pay ${OnboardingFunding.label(s.draft.amountCents)} by card (test mode)",
                    { run { controller.startCheckout() } }, Modifier.heightIn(min = 48.dp).testTag("topup.start"), enabled = s.unavailable == null && !s.busy && amountOk)
            } else {
                val quote = s.quote
                if (quote == null) {
                    if (!s.lightningOffered) Notice(NoticeTone.INFO, "Lightning payments are unavailable right now. Try again later.", Modifier.testTag("topup.ln.unavailable"))
                    PrimaryButton(if (s.draft.inFlight) "Get the Lightning invoice again" else "Get a Lightning invoice for ${OnboardingFunding.label(s.draft.amountCents)}",
                        { run { controller.startLightning() } }, Modifier.heightIn(min = 48.dp).testTag("topup.ln.start"), enabled = s.lightningOffered && !s.busy && amountOk)
                } else LightningInvoice(quote, now, onCopy = { local = copyInvoice(context, it) }, onOpen = { local = openInWallet(context, it) })
            }
            OutlinedButton(onClick = { local = null; run { controller.refresh() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("topup.check")) { Text("Check payment now") }
            if (s.busy) CircularProgressIndicator()
        }
        Column(Modifier.ambCard(Amb.colors).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Credited by NStack", style = EditorialStyle, color = Amb.colors.primary, modifier = Modifier.semantics { heading() })
            val credited = s.topups?.credited.orEmpty()
            if (credited.isEmpty()) Text("Nothing has been credited by a top-up yet.", style = MaterialTheme.typography.bodyMedium, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("topup.none"))
            credited.forEach { Text("${OnboardingFunding.label(it.amountCents)} · ${it.settledAt?.take(10) ?: ""}", Modifier.testTag("topup.credited"), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * An open Lightning quote exactly as NStack holds it: amount, expiry, a safely shown invoice, and what to do if it is stuck. Shared by
 * top-ups ([tag] `topup.ln`) and initial onboarding (`onboarding.ln`). The invoice only ever reaches copy or the `lightning:` hand-off
 * when [TopupQuote.invoiceSafe] accepted it. No QR image is drawn: the app has no QR encoder, and a text invoice is what wallets paste.
 */
@Composable
internal fun LightningInvoice(quote: TopupQuote, now: Instant, onCopy: (String) -> Unit, onOpen: (String) -> Unit, tag: String = "topup.ln") {
    val reference = quote.id.take(8)
    when (quote.phase(now)) {
        TopupQuote.Phase.PAYABLE -> {
            val invoice = quote.invoiceSafe!!
            val expires = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(quote.expiresAt!!)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pay ${"%,d".format(quote.amountSats)} sats (${OnboardingFunding.label(quote.amountCents)})", Modifier.testTag("$tag.sats").semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text("Expires $expires. Pay it once only.", Modifier.testTag("$tag.expiry"), style = MaterialTheme.typography.bodyMedium, color = Amb.colors.mutedForeground)
                Text(if (invoice.length > 40) invoice.take(24) + "…" + invoice.takeLast(12) else invoice, Modifier.testTag("$tag.invoice"), style = MaterialTheme.typography.bodySmall, color = Amb.colors.mutedForeground)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Open in wallet", { onOpen(invoice) }, Modifier.heightIn(min = 48.dp).testTag("$tag.open"))
                    OutlinedButton(onClick = { onCopy(invoice) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tag.copy")) { Text("Copy invoice") }
                }
            }
        }
        TopupQuote.Phase.PREPARING -> Notice(NoticeTone.INFO, "NStack is still preparing this invoice. Check the payment again in a moment; asking again never creates a second one. Reference $reference.", Modifier.testTag("$tag.preparing"))
        TopupQuote.Phase.EXPIRED -> Notice(NoticeTone.INFO, "This invoice has passed its expiry. Do not pay it. Check the payment: NStack decides when it has ended, and a new payment can start only after that. Reference $reference.", Modifier.testTag("$tag.expired"))
        TopupQuote.Phase.SUPPORT -> Notice(NoticeTone.ERROR, "NStack is holding this Lightning payment for review. Do not pay again. Contact your inviter or support with reference $reference; nothing is credited until NStack confirms it.", Modifier.testTag("$tag.support"))
        TopupQuote.Phase.ENDED -> Notice(NoticeTone.INFO, "This invoice has finished. NStack's own record shows whether it was credited.", Modifier.testTag("$tag.ended"))
    }
}

/** Copy and the `lightning:` hand-off for a safe invoice; the messages never claim the payment counted. */
internal fun copyInvoice(context: Context, invoice: String): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Lightning invoice", invoice))
    return "Invoice copied. Pay it from your wallet; credit appears only once NStack confirms it."
}

internal fun openInWallet(context: Context, invoice: String): String {
    val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "lightning:$invoice".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }.isSuccess
    return if (opened) "Opened your wallet. Credit appears only once NStack confirms the payment." else "No Lightning wallet was found. Copy the invoice instead."
}
