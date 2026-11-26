package studio.otherstuff.ambulando.app.ui.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.core.net.toUri
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
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
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupController
import studio.otherstuff.ambulando.core.identitybackup.HostedBackupStage
import studio.otherstuff.ambulando.core.onboarding.OnboardingAgentState
import studio.otherstuff.ambulando.core.onboarding.OnboardingController
import studio.otherstuff.ambulando.core.onboarding.OnboardingFunding
import studio.otherstuff.ambulando.core.onboarding.OnboardingInviteCode
import studio.otherstuff.ambulando.core.onboarding.OnboardingInviteInput
import studio.otherstuff.ambulando.core.onboarding.OnboardingName
import studio.otherstuff.ambulando.core.onboarding.OnboardingPaymentOption
import studio.otherstuff.ambulando.core.onboarding.OnboardingPhase
import studio.otherstuff.ambulando.core.onboarding.OnboardingSnapshot
import studio.otherstuff.ambulando.core.onboarding.OnboardingUnavailability
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

/** Why the wizard cannot start in this build or state. Shown as an explanation, never hidden. */
internal fun onboardingGate(sample: Boolean, family: Boolean, unlockedNpub: String?): String? = when {
    family -> "Hosted workspace setup is not part of the family app."
    sample -> "Sample data mode has no real NStack, so hosted setup is switched off here."
    unlockedNpub == null -> "Unlock your identity to set up a hosted workspace. Setup is signed with it."
    else -> null
}

/**
 * Guided setup of a hosted workspace with an invitation, for an identity already on this device. Everything that costs
 * money or holds a place is explained first; the person confirms a review screen before anything is sent.
 */
@Composable
fun InvitedOnboardingFlow(model: AppModel, initialOrigin: String?, onConnected: () -> Unit, onClose: () -> Unit) {
    val amb = Amb.colors
    val npub = (model.identityState as? IdentityState.Unlocked)?.npub
    val gate = onboardingGate(model.environment.isSample, BuildConfig.GRANDPARENT_CHAT, npub)
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(amb.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Hosted workspace", Modifier.weight(1f).padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.close")) { Text("Close") }
        }
        if (gate != null || npub == null) {
            Notice(NoticeTone.INFO, gate ?: "Unlock your identity first.", Modifier.padding(16.dp).testTag("onboarding.gate"))
            return@Column
        }
        var origin by remember { mutableStateOf<TowerOrigin?>(null) }
        val chosen = origin
        if (chosen == null) OriginStep(model, initialOrigin) { origin = it }
        else OnboardingWizard(
            model.onboarding.controller(chosen, npub), launch = model::launchDetached, backup = model.onboarding.backup(chosen, npub),
            isConnected = { origin -> model.connections.any { it.origin == origin } }, connect = { origin -> model.connect(origin) }, onFinished = onConnected,
        )
    }
}

@Composable
private fun OriginStep(model: AppModel, initialOrigin: String?, chosen: (TowerOrigin) -> Unit) {
    val amb = Amb.colors
    var address by remember { mutableStateOf(initialOrigin.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<String?>(null) }
    var linkText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.ambCard(amb), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up with an invitation", style = EditorialStyle, color = amb.primary)
            Text(
                "Choose the NStack that gave you the invitation. Setup is signed with the identity already on this device; nothing is sent until you confirm a review screen.",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground,
            )
            OutlinedTextField(
                value = linkText, onValueChange = { linkText = it; error = null }, singleLine = true, label = { Text("Invitation link (optional)") },
                modifier = Modifier.fillMaxWidth().testTag("onboarding.link"),
            )
            OutlinedButton(onClick = {
                val parsed = OnboardingInviteInput.parse(linkText)
                when {
                    parsed == null -> error = "That is not an invitation code or an https invitation link."
                    parsed.suggestedOrigin == null -> error = "That is only a code. Enter your NStack's address below; the code is asked for on the next step."
                    else -> { suggestion = parsed.suggestedOrigin.toString(); error = null }
                }
            }, enabled = linkText.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.link.read")) { Text("Read link") }
            suggestion?.let { s ->
                Notice(NoticeTone.WARNING, "The link names $s. Only use it if you trust where you got it.")
                OutlinedButton(onClick = { address = s; suggestion = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.link.use")) { Text("Use $s") }
            }
            OutlinedTextField(
                value = address, onValueChange = { address = it; error = null }, singleLine = true, label = { Text("https://your-nstack.example") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("onboarding.origin"),
            )
            error?.let { Notice(NoticeTone.ERROR, it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            OnbButton("Check this NStack", enabled = address.isNotBlank(), modifier = Modifier.testTag("onboarding.origin.go"), onClick = {
                try { chosen(TowerOrigin(address)) }
                catch (e: TowerError) { error = "Use the HTTPS address of your NStack, without a path, credentials, query or fragment." }
            })
        }
    }
}

/**
 * The wizard for one controller. [launch] runs work that must outlive this screen (a request in flight survives rotation);
 * [connect] attaches the Tower to the app once the workspace exists.
 */
@Composable
internal fun OnboardingWizard(
    controller: OnboardingController, launch: (suspend () -> Unit) -> Unit, isConnected: (String) -> Boolean, connect: suspend (String) -> Unit,
    onFinished: () -> Unit, backup: HostedBackupController? = null,
) {
    val snapshot by controller.snapshots.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun run(block: suspend () -> Unit) = launch { withContext(Dispatchers.IO) { block() } }
    // After a confirmed backup the controller must re-read what the wizard may now do (it asks NStack, not the local flag).
    val backupState by (backup?.snapshots ?: remember { kotlinx.coroutines.flow.MutableStateFlow(studio.otherstuff.ambulando.core.identitybackup.HostedBackupSnapshot()) }).collectAsState()
    LaunchedEffect(backupState.stage) { if (backupState.stage == HostedBackupStage.CONFIRMED) withContext(Dispatchers.IO) { controller.load() } }

    LaunchedEffect(controller) { withContext(Dispatchers.IO) { controller.load() } }
    // Funding is read from the server on every return to the app and, while a payment is open, every few seconds in front.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (snapshot.phase in WAITING) run { controller.refresh() } }
    LaunchedEffect(controller, snapshot.phase) {
        if (snapshot.phase !in WAITING) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { delay(6_000); withContext(Dispatchers.IO) { controller.refresh() } } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        snapshot.failure?.let { Notice(NoticeTone.ERROR, it.userMessage, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("onboarding.failure")) }
        if (snapshot.identityOrigin == studio.otherstuff.ambulando.core.identitybackup.IdentityOrigin.UNKNOWN) Notice(NoticeTone.WARNING,
            "This phone's record of where your identity came from could not be read. To be safe, back the identity up to NStack before paying; you can do that below.", Modifier.testTag("onboarding.origin.unknown"))
        snapshot.notice?.let { Notice(NoticeTone.INFO, it, Modifier.testTag("onboarding.notice")) }
        when (val phase = snapshot.phase) {
            OnboardingPhase.Checking -> Checking()
            is OnboardingPhase.Unavailable -> UnavailableStep(phase.why) { run { controller.load() } }
            OnboardingPhase.Form -> { if (backup != null && snapshot.identityOrigin.needsBackup) HostedBackupCard(backup, ::run); FormStep(snapshot, controller, ::run) }
            OnboardingPhase.Review -> { if (backup != null && snapshot.identityOrigin.needsBackup) HostedBackupCard(backup, ::run); ReviewStep(snapshot, controller, ::run) }
            OnboardingPhase.AwaitingPayment -> AwaitingStep(snapshot, controller, ::run) { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
            }
            OnboardingPhase.ReadyToProvision -> ReadyStep(snapshot, controller, ::run)
            OnboardingPhase.Provisioning -> ProvisioningStep(controller, ::run)
            OnboardingPhase.Provisioned -> DoneStep(snapshot, controller, ::run, launch, isConnected, connect, onFinished)
            OnboardingPhase.Cancelled -> CancelledStep(controller, ::run)
        }
    }
}

private val WAITING = setOf(OnboardingPhase.AwaitingPayment, OnboardingPhase.Provisioning)

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val amb = Amb.colors
    Column(Modifier.ambCard(amb).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = EditorialStyle, color = amb.primary, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable private fun Body(text: String, tag: String? = null) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Amb.colors.mutedForeground, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

@Composable
private fun Checking() = Card("Checking this NStack") {
    CircularProgressIndicator(Modifier.semantics { contentDescription = "Checking this NStack" })
    Body("Reading its public service description, then your signed account and any setup already in progress.")
}

@Composable
private fun UnavailableStep(why: OnboardingUnavailability, retry: () -> Unit) = Card("Setup is not available right now") {
    val (message, canRetry) = when (why) {
        is OnboardingUnavailability.OriginRejected -> "This address was not accepted as an NStack: ${why.reason} Nothing was sent signed." to false
        OnboardingUnavailability.BackendMissing -> "This NStack does not offer invited onboarding." to true
        OnboardingUnavailability.RolloutDisabled -> "Invited onboarding is switched off on this NStack. Your progress is kept; check again later." to true
        OnboardingUnavailability.SignerRefused -> "This NStack did not accept the signature. Check that the identity you unlocked is the one you invited." to true
        OnboardingUnavailability.SignerMismatch -> "The identity unlocked now is not the one this setup started with. Unlock the original identity to continue; nothing was sent." to true
        OnboardingUnavailability.IdentityLocked -> "Your identity is locked. Unlock it to continue; your progress is kept." to true
    }
    Body(message, "onboarding.unavailable")
    if (canRetry) OnbButton("Check again", retry, Modifier.testTag("onboarding.retry"))
}

@Composable
private fun FormStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    val d = s.draft
    var name by remember(d.intentId) { mutableStateOf(d.workspaceName) }
    var custom by remember { mutableStateOf(if (d.fundingCents in OnboardingFunding.PRESETS_CENTS) "" else (d.fundingCents / 100.0).toString()) }
    var invite by remember { mutableStateOf("") }
    var funding by remember(d.intentId) { mutableIntStateOf(d.fundingCents) }
    var agent by remember(d.intentId) { mutableStateOf(d.agentOfferId) }
    val needsInvite = d.intentId == null && d.invite == null
    val inviteOk = !needsInvite || OnboardingInviteInput.parse(invite) != null
    val customCents = if (custom.isBlank()) null else OnboardingFunding.cents(custom, s.minFundingCents, s.maxFundingCents)
    Card("Name your workspace") {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Workspace name") }, isError = name.isNotEmpty() && OnboardingName.normalized(name) == null,
            supportingText = { Text("1 to 80 characters") }, modifier = Modifier.fillMaxWidth().testTag("onboarding.name"))
    }
    if (needsInvite) Card("Invitation") {
        OutlinedTextField(invite, { invite = it }, singleLine = true, label = { Text("Invitation code") }, isError = invite.isNotEmpty() && !inviteOk,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("onboarding.invite"))
        Body("Starting setup holds one of the invitation's places for you until you cancel or finish. The code is saved locally and sent when you start setup. If NStack then turns the request down, the code has still been sent.")
    }
    Card("Funding") {
        Body("Choose how much to add to your hosted account. The minimum is ${OnboardingFunding.label(s.minFundingCents)}. Only NStack's own confirmation of a completed payment counts; returning to the app does not.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingFunding.PRESETS_CENTS.forEach { cents ->
                FilterChip(selected = funding == cents && custom.isBlank(), onClick = { funding = cents; custom = "" }, label = { Text(OnboardingFunding.label(cents)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.funding.$cents"))
            }
        }
        OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("Other amount in US$") }, isError = custom.isNotBlank() && customCents == null,
            supportingText = { Text("${OnboardingFunding.label(s.minFundingCents)} to ${OnboardingFunding.label(s.maxFundingCents)}, two decimals at most") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("onboarding.funding.custom"))
    }
    Card("Agent (optional)") {
        if (s.offers.isEmpty()) Body("No agents are on offer yet. You can still set up your workspace.", "onboarding.agents.none")
        else {
            Body("Choosing an agent only records your choice. Setup does not start it, and nothing is spent on it until it is running.")
            FilterChip(selected = agent == null, onClick = { agent = null }, label = { Text("No agent") }, modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.agent.none"))
            s.offers.forEach { offer ->
                FilterChip(selected = agent == offer.id, onClick = { agent = offer.id }, label = { Text("${offer.template} · up to ${OnboardingFunding.label(offer.maxDailyCapCents)}/day") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.agent.${offer.id}"))
            }
        }
    }
    val chosenFunding = if (custom.isNotBlank()) customCents else funding
    val ready = OnboardingName.normalized(name) != null && chosenFunding != null && inviteOk
    OnbButton("Review", enabled = ready && !s.busy, modifier = Modifier.testTag("onboarding.review"), onClick = {
        run {
            controller.edit(name = name, fundingCents = chosenFunding, agentOfferId = agent, clearAgent = agent == null,
                invite = if (needsInvite) OnboardingInviteInput.parse(invite)?.code else null)
            controller.confirmReview()
        }
    })
}

@Composable
private fun ReviewStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    val d = s.draft
    val offer = s.offers.firstOrNull { it.id == d.agentOfferId }
    var chosen by remember { mutableStateOf<OnboardingPaymentOption.Method?>(null) }
    // Card stays the default; an explicit pick is honoured only while that method is on offer.
    val method = chosen?.takeIf { pick -> s.paymentOptions.any { it.method == pick && it.available } } ?: OnboardingPaymentOption.Method.CARD
    val selected = s.paymentOptions.first { it.method == method }
    val lightning = method == OnboardingPaymentOption.Method.LIGHTNING
    Card("Review before anything is sent") {
        Body("Workspace: ${OnboardingName.normalized(d.workspaceName)}", "onboarding.review.name")
        Body("Funding: ${OnboardingFunding.label(d.fundingCents)}", "onboarding.review.funding")
        Body(if (offer != null) "Agent chosen: ${offer.template}. It is only a choice: setup does not start it." else "No agent chosen.")
        Body(if (lightning) "Continuing opens your hosted account, holds an invitation place for you and asks NStack for a Lightning invoice. No money moves until you pay it from your wallet, and it counts only once NStack confirms it."
        else "Continuing opens your hosted account, holds an invitation place for you and asks for a payment page. No money moves until you pay on that page.")
        Body("Once you continue, this setup is tied to that payment method until NStack has finished with it. To use the other one you would cancel this setup first.", "onboarding.review.methodLock")
    }
    Card("Payment") {
        s.paymentOptions.forEach { option ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = option.method == method, enabled = option.available, role = Role.RadioButton, onClick = { chosen = option.method })
                .testTag("onboarding.pay.${option.method.name.lowercase()}")) {
                Text(option.label + if (option.available) "" else " — unavailable", fontWeight = FontWeight.SemiBold)
                option.unavailableReason?.let { Body(it) }
            }
        }
    }
    OnbButton(if (lightning) "Get a Lightning invoice" else "Continue to payment", enabled = selected.available && !s.busy && !s.backupRequired, modifier = Modifier.testTag("onboarding.pay"),
        onClick = { run { controller.continueToPayment(method) } })
    OutlinedButton(onClick = { run { controller.reopenForEditing() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.back")) { Text("Back to edit") }
    if (s.busy) CircularProgressIndicator()
}

@Composable
private fun AwaitingStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit, open: (String) -> Unit) {
    if (s.supportHold) { SupportHoldStep(s, controller, run); return }
    if (s.draft.method == OnboardingPaymentOption.Method.LIGHTNING) { AwaitingLightningStep(s, controller, run); return }
    Card("Waiting for NStack to confirm payment") {
        Body("Pay on the Stripe test page (test mode: no real money moves). When you come back, NStack's own record decides whether the payment counted; the page you return from does not.")
        val url = s.checkoutUrl
        if (url != null) OnbButton("Open payment page", { open(url) }, Modifier.testTag("onboarding.openPayment"))
        else {
            Body("The payment page is not open. Ask for it again; the same order is reused, so you are never charged twice for one setup.")
            OnbButton("Get the payment page again", { run { controller.continueToPayment() } }, Modifier.testTag("onboarding.repay"), enabled = !s.busy)
        }
        OutlinedButton(onClick = { run { controller.refresh() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.check")) { Text("Check payment now") }
        if (s.busy) CircularProgressIndicator()
    }
    SwitchMethod(s, controller, run)
    CancelSetup(s, controller, run)
}

/** NStack's payment for this setup cannot be resumed here (more than one, awaiting support, or not the one this device holds). */
@Composable
private fun SupportHoldStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    val reference = s.paymentAttempt?.quoteId ?: s.paymentAttempt?.orderId
    Card("A payment needs a closer look") {
        Body("NStack is holding an unfinished payment for this setup that this phone cannot safely continue." +
            (reference?.let { " Reference ${it.take(8)}." } ?: "") + " Do not pay again. You can ask NStack to settle it and then choose a method, or contact your inviter or support.", "onboarding.support")
        // Status only: no invoice, wallet hand-off or new request is offered for a payment NStack has closed to payment actions.
        val attempt = s.paymentAttempt
        if (attempt?.method == OnboardingPaymentOption.Method.LIGHTNING) Notice(NoticeTone.ERROR,
            "Lightning payment status: ${(s.quote ?: attempt.quote)?.state ?: attempt.state}. Nothing is credited until NStack confirms it. Do not pay any invoice for this setup.", Modifier.testTag("onboarding.ln.support"))
        OutlinedButton(onClick = { run { controller.refresh() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.check")) { Text("Check payment now") }
        if (s.busy) CircularProgressIndicator()
    }
    SwitchMethod(s, controller, run)
    CancelSetup(s, controller, run)
}

/**
 * The only way to leave a method: NStack's provider settles the unfinished payment first (paid funds the setup, open is cancelled).
 * Never automatic. If NStack cannot verify the end, the payment stays and the person is told.
 */
@Composable
private fun SwitchMethod(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    if (!s.canReleasePayment) return
    var confirm by remember { mutableStateOf(false) }
    if (!confirm) TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.release")) { Text("Use a different payment method") }
    else Card("Switch payment method?") {
        Body("NStack will ask its payment provider to settle this payment. If it was already paid, setup simply continues. If it is still open it is cancelled, so never pay the old invoice or page afterwards. If NStack cannot confirm it ended, nothing changes.")
        OnbButton("Ask NStack to settle it", { confirm = false; run { controller.releasePayment() } }, Modifier.testTag("onboarding.release.confirm"), enabled = !s.busy)
        OutlinedButton(onClick = { confirm = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep this payment") }
    }
}

/** The Lightning invoice as NStack holds it. Only the intent NStack reports can say the money arrived; nothing here credits anything. */
@Composable
private fun AwaitingLightningStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var local by remember { mutableStateOf<String?>(null) }
    // The device clock only chooses what to show; NStack decides when an invoice has ended. One wake-up at the stated expiry.
    val expiry = s.quote?.expiresAt
    val now by produceState(Instant.now(), s.quote?.id, expiry) {
        value = Instant.now()
        if (expiry != null && value.isBefore(expiry)) { delay(minOf(Duration.between(value, expiry).toMillis() + 500, 3_600_000L)); value = Instant.now() }
    }
    Card("Pay with Lightning") {
        Body("Pay the invoice from any Lightning wallet. Coming back to the app, or the wallet saying it paid, funds nothing: your setup continues only when NStack itself records the payment.")
        local?.let { Notice(NoticeTone.INFO, it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("onboarding.ln.local")) }
        val quote = s.quote
        if (quote != null) LightningInvoice(quote, now, onCopy = { local = copyInvoice(context, it) }, onOpen = { local = openInWallet(context, it) }, tag = "onboarding.ln")
        else {
            Body("The invoice is not on screen. Ask for it again; the same request is replayed, so you are never given a second invoice for one setup.", "onboarding.ln.none")
            OnbButton("Get the Lightning invoice again", { run { controller.continueToPayment(OnboardingPaymentOption.Method.LIGHTNING) } }, Modifier.testTag("onboarding.ln.again"), enabled = !s.busy && !s.backupRequired)
        }
        OutlinedButton(onClick = { local = null; run { controller.refresh() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.check")) { Text("Check payment now") }
        if (s.busy) CircularProgressIndicator()
    }
    SwitchMethod(s, controller, run)
    CancelSetup(s, controller, run)
}

@Composable
private fun CancelSetup(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (!confirm) TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding.cancel")) { Text("Cancel this setup") }
    else Card("Cancel this setup?") {
        Body("This releases your invitation place if no payment is still open. If a payment is open it has to finish or expire first.")
        OnbButton("Cancel setup", { confirm = false; run { controller.cancelSetup() } }, Modifier.testTag("onboarding.cancel.confirm"), enabled = !s.busy)
        OutlinedButton(onClick = { confirm = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep it") }
    }
}

@Composable
private fun ReadyStep(s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit) = Card("Payment confirmed by NStack") {
    Body("NStack has recorded your ${OnboardingFunding.label(s.intent?.fundingCents ?: s.draft.fundingCents)} payment. Next it creates the workspace.")
    OnbButton("Create workspace", { run { controller.provision() } }, Modifier.testTag("onboarding.provision"), enabled = !s.busy)
    if (s.busy) CircularProgressIndicator()
}

@Composable
private fun ProvisioningStep(controller: OnboardingController, run: (suspend () -> Unit) -> Unit) = Card("Creating your workspace") {
    Body("Setup is under way. Your payment and any finished steps are kept if it stops part-way.")
    CircularProgressIndicator(Modifier.semantics { contentDescription = "Creating your workspace" })
    OutlinedButton(onClick = { run { controller.provision() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.provision.retry")) { Text("Continue setup") }
}

@Composable
private fun DoneStep(
    s: OnboardingSnapshot, controller: OnboardingController, run: (suspend () -> Unit) -> Unit, launch: (suspend () -> Unit) -> Unit,
    isConnected: (String) -> Boolean, connect: suspend (String) -> Unit, onFinished: () -> Unit,
) {
    val landing = s.landing
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card("Your workspace is ready") {
        Body(OnboardingName.normalized(s.draft.workspaceName)?.let { "$it has been created on this NStack." } ?: "Your workspace has been created.", "onboarding.done")
        Body(if (landing?.generalReady == true) "General channel: ready." else "General channel: not reported yet. NStack has not confirmed it, so it is not shown as ready.")
        if (s.intent?.agentState != OnboardingAgentState.NONE) Body(
            if (landing?.agentRunning == true) "Agent: running." else "Agent: chosen but not running. Nothing has been started or spent on it, and it stays off until you send its first request.", "onboarding.done.agent",
        )
        error?.let { Notice(NoticeTone.ERROR, it) }
        val connected = isConnected(s.draft.origin)
        if (!connected) OnbButton(if (busy) "Connecting…" else "Connect to this NStack", enabled = !busy, modifier = Modifier.testTag("onboarding.connect"), onClick = {
            busy = true; error = null
            launch {
                try { connect(s.draft.origin); onFinished() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = "Could not connect yet. Your workspace is safe on the NStack; try again." }
                finally { busy = false }
            }
        }) else Body("This NStack is already connected. Enable the new workspace from Towers and workspaces.")
        OutlinedButton(onClick = { run { controller.finish() }; onFinished() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding.finish")) { Text("Done") }
    }
}

@Composable
private fun CancelledStep(controller: OnboardingController, run: (suspend () -> Unit) -> Unit) = Card("Setup cancelled") {
    Body("Nothing further will be sent for this setup. If you paid, the payment stays on your account.")
    OnbButton("Start another setup", { run { controller.finish() } }, Modifier.testTag("onboarding.again"))
}

/** Onboarding actions are 48 dp tall at minimum, whatever the shared button's own minimum is. */
@Composable
private fun OnbButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    PrimaryButton(text, onClick, modifier.heightIn(min = 48.dp), enabled)
