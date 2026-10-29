package studio.otherstuff.ambulando.app.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import studio.otherstuff.ambulando.app.ui.onboarding.PassageMark
import studio.otherstuff.ambulando.app.ui.onboarding.shortNpub
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.PrimaryButton
import studio.otherstuff.ambulando.core.browser.NIP07Decision
import studio.otherstuff.ambulando.core.browser.NIP07Prompt
import java.net.URI
import java.text.DateFormat
import java.util.Date

/**
 * The browser over the app: the selected tab's page and one compact bottom bar — Ambulando (back to exactly where
 * the user was), back, forward, the address with a lock, the tab count and a ••• menu.
 */
@Composable
fun BrowserScreen(browser: BrowserModel) {
    BrowserFileFlows(browser)
    val amb = Amb.colors
    val context = LocalContext.current
    val tab = browser.selected
    BackHandler(enabled = browser.presented) {
        if (tab != null && tab.canGoBack) tab.webView.goBack() else browser.hide()
    }
    Column(Modifier.fillMaxSize().background(amb.background).statusBarsPadding().testTag("browser")) {
        val unavailable = browser.unavailable
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                unavailable != null && tab == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Notice(NoticeTone.WARNING, unavailable, Modifier.testTag("browser.unavailable"))
                }
                tab == null || tab.isBlank && !tab.loading -> EmptyTab()
                else -> key(tab.id) {
                    AndroidView(
                        factory = { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView); tab.webView },
                        modifier = Modifier.fillMaxSize().testTag("browser.page"),
                    )
                }
            }
            tab?.refusal?.let { Notice(NoticeTone.WARNING, it, Modifier.align(Alignment.TopCenter).padding(8.dp).testTag("browser.refusal")) }
            if (tab != null && tab.loading) LinearProgressIndicator(progress = { tab.progress }, modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
        }
        browser.fileTransfer?.let { transfer ->
            Column(Modifier.fillMaxWidth().testTag("browser.download.progress")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saving ${transfer.request.filename} · ${transfer.received / 1024} KB", modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { transfer.cancel() }, modifier = Modifier.testTag("browser.download.cancel")) { Text("Cancel") }
                }
                if (transfer.total > 0) LinearProgressIndicator(progress = { (transfer.received.toFloat() / transfer.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        BottomBar(browser, tab)
    }
    if (browser.showingTabs) TabsSheet(browser)
    browser.prompt?.let { prompt -> NostrApprovalSheet(prompt) { browser.answer(prompt, it) } }
    browser.pendingExternal?.let { url ->
        val scheme = url.substringBefore(':')
        AlertDialog(
            onDismissRequest = { browser.pendingExternal = null },
            title = { Text("Open in another app?") },
            text = { Text(url, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    browser.pendingExternal = null
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) { browser.selected?.refusal = "No app on this device opens $scheme: links." }
                }) { Text("Open $scheme:") }
            },
            dismissButton = { TextButton(onClick = { browser.pendingExternal = null }) { Text("Cancel") } },
        )
    }
    browser.dialog?.let { d ->
        AlertDialog(
            onDismissRequest = { d.reply(false); browser.dialog = null },
            title = { Text(d.origin) },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { d.reply(true); browser.dialog = null }) { Text("OK") } },
            dismissButton = if (d.confirm) ({ TextButton(onClick = { d.reply(false); browser.dialog = null }) { Text("Cancel") } }) else null,
        )
    }
}

@Composable
private fun key(id: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

@Composable
private fun EmptyTab() {
    val amb = Amb.colors
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        PassageMark(48.dp)
        Text("Enter an address below", style = MaterialTheme.typography.titleMedium, color = amb.foreground)
        Text(
            "Pages open here with your Nostr key available through window.nostr. Each site asks before it can see your key, and every signature asks you first.",
            style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BottomBar(browser: BrowserModel, tab: BrowserTab?) {
    val amb = Amb.colors
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(amb.card).navigationBarsPadding().imePadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                TextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = {
                        val target = tab ?: run { browser.open(null); browser.selected }
                        if (target != null && browser.go(address, target)) editing = false
                    }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = amb.muted, unfocusedContainerColor = amb.muted),
                    modifier = Modifier.weight(1f).testTag("browser.address"),
                )
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            } else {
                IconButton(onClick = { browser.hide() }, modifier = Modifier.semantics { contentDescription = "Back to Ambulando" }.testTag("browser.ambulando")) { PassageMark(22.dp) }
                IconButton(onClick = { tab?.webView?.goBack() }, enabled = tab?.canGoBack == true) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                IconButton(onClick = { tab?.webView?.goForward() }, enabled = tab?.canGoForward == true) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Forward") }
                Row(
                    Modifier.weight(1f).heightIn(min = 36.dp).background(amb.muted, RoundedCornerShape(10.dp))
                        .clickable { address = tab?.url?.takeUnless { tab.isBlank }.orEmpty(); editing = true }
                        .padding(horizontal = 10.dp).testTag("browser.addressLabel"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (tab?.origin != null) Icon(Icons.Outlined.Lock, contentDescription = "Secure", tint = amb.mutedForeground, modifier = Modifier.size(14.dp))
                    Text(
                        tab?.url?.takeUnless { tab.isBlank }?.let { runCatching { URI(it).host }.getOrNull() } ?: "Search or enter address",
                        style = MaterialTheme.typography.bodyMedium, color = if (tab?.isBlank != false) amb.mutedForeground else amb.foreground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.padding(horizontal = 6.dp).size(28.dp).border(1.5.dp, amb.foreground, RoundedCornerShape(6.dp))
                        .clickable { browser.showingTabs = true }
                        .semantics { contentDescription = "${browser.tabs.size} open ${if (browser.tabs.size == 1) "tab" else "tabs"}" }
                        .testTag("browser.tabs"),
                    contentAlignment = Alignment.Center,
                ) { Text("${browser.tabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("browser.menu")) { Icon(Icons.Outlined.MoreHoriz, "Browser menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (tab != null) {
                            if (tab.loading) DropdownMenuItem(text = { Text("Stop loading") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = { menu = false; tab.webView.stopLoading() })
                            else if (!tab.isBlank) DropdownMenuItem(text = { Text("Reload") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { menu = false; tab.webView.reload() })
                            val url = tab.url
                            if (url != null && url.startsWith("https://")) {
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                                    menu = false
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                                })
                                DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = {
                                    menu = false
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                                })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; browser.open(null) })
                            DropdownMenuItem(text = { Text("Close tab") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = { menu = false; browser.close(tab) })
                            val origin = tab.origin
                            if (origin != null && browser.sites[origin] != null) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Forget Nostr permission for ${runCatching { URI(origin).host }.getOrNull() ?: origin}") },
                                    leadingIcon = { Icon(Icons.Outlined.Key, null) }, onClick = { menu = false; browser.forget(origin) })
                            }
                        } else {
                            DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; browser.open(null) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Clear browsing data", color = Amb.colors.destructive) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Amb.colors.destructive) },
                            onClick = { menu = false; browser.clearBrowsingData() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(browser: BrowserModel) {
    val amb = Amb.colors
    ModalBottomSheet(onDismissRequest = { browser.showingTabs = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().clickable { browser.showingTabs = false; browser.hide() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PassageMark(22.dp); Text("Ambulando", fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            Text("Tabs", style = MaterialTheme.typography.labelLarge, color = amb.mutedForeground, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(browser.tabs.toList(), key = { it.id }) { tab ->
                    Row(
                        Modifier.fillMaxWidth().background(if (tab.id == browser.selected?.id) amb.muted else amb.card, RoundedCornerShape(Amb.radiusControl))
                            .clickable { browser.select(tab) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tab.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            tab.url?.takeUnless { tab.isBlank }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground) }
                        }
                        IconButton(onClick = { browser.close(tab) }) { Icon(Icons.Outlined.Close, "Close ${tab.displayTitle}") }
                    }
                }
            }
            OutlinedButton(onClick = { browser.showingTabs = false; browser.open(null) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("New tab") }
        }
    }
}

/** The native question for `window.nostr`: which site is asking and, for a signature, exactly what is signed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrApprovalSheet(prompt: NIP07Prompt, decide: (NIP07Decision) -> Unit) {
    val amb = Amb.colors
    val connect = prompt.kind is NIP07Prompt.Kind.Connect
    // "Remember this site" starts on for the public key; an until-lock signing grant always starts off.
    var remember by remember(prompt.id) { mutableStateOf(connect) }
    val host = runCatching { URI(prompt.context.origin).host }.getOrNull() ?: prompt.context.origin
    ModalBottomSheet(
        onDismissRequest = { decide(NIP07Decision.Deny(remember = false)) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("nostr.sheet"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (connect) "Connect to Nostr" else "Sign with Nostr", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = amb.foreground, modifier = Modifier.size(18.dp))
                Text(host, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("nostr.origin"))
            }
            Text(prompt.context.origin, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = amb.mutedForeground)
            Text(
                if (connect) "$host wants to see your Nostr public key. It cannot sign anything without asking you."
                else "$host wants your signature on the event below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val kind = prompt.kind
            if (kind is NIP07Prompt.Kind.Sign) {
                val event = kind.event
                Text("What will be signed", style = MaterialTheme.typography.labelLarge, color = amb.mutedForeground)
                Labeled("Kind", "${event.kind} · ${kindName(event.kind)}")
                Labeled("Time", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(event.createdAt * 1000)))
                if (event.content.isEmpty()) Text("No content", color = amb.mutedForeground)
                else Text(event.content.take(4000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().background(amb.muted, RoundedCornerShape(Amb.radiusControl)).padding(8.dp).testTag("nostr.content"))
                event.tags.take(20).forEach { tag -> Text(tag.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (event.tags.size > 20) Text("and ${event.tags.size - 20} more", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
            }
            prompt.npub?.let { Labeled("Your key", shortNpub(it)) }
            if (connect) {
                SwitchRow("Remember this site", remember) { remember = it }
                Text("You can forget this in the browser menu. Signing still asks every time.", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
            } else if (prompt.offersSessionGrant) {
                SwitchRow("Don't ask again for this kind on $host until Ambulando locks", remember) { remember = it }
                Text("Only for sign-in requests. Other kinds always ask.", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { decide(NIP07Decision.Deny(remember = connect && remember)) }, modifier = Modifier.weight(1f).testTag("nostr.deny")) {
                    Text(if (connect) "Don't allow" else "Reject")
                }
                Box(Modifier.weight(1f)) {
                    PrimaryButton(if (connect) "Allow" else "Sign", onClick = { decide(NIP07Decision.Allow(remember = remember && (connect || prompt.offersSessionGrant))) },
                        modifier = Modifier.testTag("nostr.allow"))
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Amb.colors.mutedForeground, modifier = Modifier.widthIn(min = 80.dp))
        Spacer(Modifier.weight(0.05f))
        Text(value, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("nostr.remember"))
    }
}

fun kindName(kind: Int): String = when (kind) {
    0 -> "Profile"
    1 -> "Note"
    3 -> "Follow list"
    4 -> "Direct message (NIP-04)"
    5 -> "Deletion"
    6 -> "Repost"
    7 -> "Reaction"
    1111 -> "Comment"
    9734 -> "Zap request"
    10002 -> "Relay list"
    22242 -> "Relay sign-in (NIP-42)"
    24242 -> "Blossom authorisation"
    27235 -> "HTTP sign-in (NIP-98)"
    30023 -> "Article"
    else -> "Other"
}
