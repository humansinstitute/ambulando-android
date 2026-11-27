package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.briefing.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.ConnectionFailure
import java.time.*
import java.time.format.DateTimeFormatter

/** No persisted prose cache: losing access, switching identity or locking hides the briefing immediately. */
@Composable internal fun ConnectedPersonalBriefing(model: AppModel) {
    val w=model.workspace ?: return; val c=model.connection ?: return
    val boundary=model.activeBoundaryId ?: return
    val services=LocalAppServices.current
    val unlocked=services.unlocked && !w.accessRemoved
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while(true) { delay(30_000);now=Instant.now() } }
    val today=Briefing.today(now)
    var selected by rememberSaveable(boundary,c.signerNpub,w.id) { mutableStateOf<String?>(null) }
    val day=selected?.let(LocalDate::parse)?.coerceAtMost(today) ?: today
    var content by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableStateOf<Briefing?>(null) }
    var loaded by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableStateOf(false) }
    var failure by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableStateOf<String?>(null) }
    var location by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableStateOf<BriefingLocation?>(null) }
    var lastFingerprint by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableStateOf<String?>(null) }
    var lastDiscoveryMillis by remember(boundary,c.id,c.signerNpub,w.id,unlocked) { mutableLongStateOf(0L) }
    val gate=remember(boundary,c.id,c.signerNpub,w.id) { Mutex() }
    suspend fun fingerprint(): String? {
        val at=location ?: return null
        val file=model.store.reader.file(w.id,at.fileId)
        val channel=model.store.reader.channel(w.id,at.channelId)
        return "${file?.rowVersion}|${file?.storageObjectId}|${file?.accessRemoved}|${channel?.rowVersion}|${channel?.accessRemoved}|${model.store.reader.isChannelAccessRemoved(w.id,at.channelId)}"
    }
    fun current() = model.activeBoundaryId==boundary && model.connection?.id==c.id && model.workspace?.id==w.id && model.workspace?.accessRemoved==false && (model.identityState as? IdentityState.Unlocked)?.npub==c.signerNpub
    suspend fun refresh(rediscover: Boolean = false) = gate.withLock {
        if(!current()) return@withLock
        val startingFingerprint=fingerprint()
        try {
            val result=withContext(Dispatchers.IO) { model.fileNappletAPI(c).personalBriefing(w.id,w.appNpub,c.signerNpub,if(rediscover)null else location) }
            if(current()) {content=result;location=result?.location;loaded=true;failure=null;lastDiscoveryMillis=android.os.SystemClock.elapsedRealtime();lastFingerprint=startingFingerprint ?: fingerprint()}
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(current()) {content=null;loaded=true;failure=ConnectionFailure.userMessage(e) ?: "Your briefing is unavailable. Try refreshing."}}
    }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    LaunchedEffect(boundary,c.id,c.signerNpub,w.id,unlocked,today) {if(unlocked)refresh(rediscover=true)}
    DisposableEffect(lifecycle,boundary,c.id,c.signerNpub,w.id,unlocked) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME&&unlocked)scope.launch {now=Instant.now();refresh(rediscover=true)}}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(boundary,c.id,c.signerNpub,w.id,unlocked) {
        if(unlocked)model.store.changes.stream().collect {changes->
            val relevant=changes.filter {it.workspaceId==null||it.workspaceId==w.id}
            val authority=relevant.any {it.table in setOf(StoreTable.ACCESS,StoreTable.WORKSPACES)}
            if(authority) {content=null;loaded=false;refresh()}
            else if(relevant.any {it.table in setOf(StoreTable.FILES,StoreTable.CHANNELS)}) {
                delay(1500)
                if(location!=null && fingerprint()!=lastFingerprint) refresh()
                else if(location==null && android.os.SystemClock.elapsedRealtime()-lastDiscoveryMillis>=60_000) refresh(rediscover=true)
            }
        }
    }
    PersonalDailyBriefing(if(unlocked)content else null,loaded&&unlocked,if(unlocked)failure else "Unlock this workspace to read your briefing.",day,today,Briefing.pending(now),onDay={selected=if(it==today)null else it.toString()},onRefresh={scope.launch {refresh(rediscover=true)}},onLink={l->
        val route=when(l.type){"task"->WorkRoute.Task(w.id,l.recordId);"doc"->WorkRoute.Doc(w.id,l.recordId);"file"->WorkRoute.File(w.id,l.recordId);"thread"->WorkRoute.Thread(w.id,l.channelId,l.recordId);else->null}
        route?.let {services.open(it)}
    })
}

@Composable internal fun PersonalDailyBriefing(content: Briefing?,loaded: Boolean,failure: String?,day: LocalDate,today: LocalDate,pending: Boolean,onDay:(LocalDate)->Unit,onRefresh:()->Unit,onLink:(BriefingLink)->Unit) {
    val colors=Amb.colors
    val entry=content?.entries?.get(day)
    val latest=content?.entries?.keys?.filter {it<day}?.maxOrNull()
    Column(Modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(Amb.radiusPanel)).border(1.dp,colors.border,RoundedCornerShape(Amb.radiusPanel)).padding(14.dp).testTag("home.briefing"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Daily Briefing",fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=colors.foreground,modifier=Modifier.semantics {heading()})
        Row {
            IconButton(onClick={onDay(day.minusDays(1))},modifier=Modifier.testTag("home.briefing.prev")){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,"Previous day")}
            Text((if(day==today)"Today, " else "")+day.format(DateTimeFormatter.ofPattern("d MMM uuuu")),fontSize=AmbType.footnote,color=colors.foreground,modifier=Modifier.testTag("home.briefing.day"))
            IconButton(onClick={onDay(day.plusDays(1))},enabled=day<today,modifier=Modifier.testTag("home.briefing.next")){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,"Next day")}
            if(day!=today)TextButton(onClick={onDay(today)},modifier=Modifier.testTag("home.briefing.today")){Text("Today")}
        }
        when {
            failure!=null -> Text(failure,color=colors.mutedForeground,modifier=Modifier.testTag("home.briefing.unavailable"))
            !loaded -> Text("Loading your briefing…",color=colors.mutedForeground,modifier=Modifier.testTag("home.briefing.loading"))
            entry!=null -> {
                entry.paragraphs.forEach {Text(it,color=colors.foreground,fontSize=AmbType.footnote,modifier=Modifier.testTag("home.briefing.paragraph"))}
                entry.coverageNote?.let {Text(it,color=colors.mutedForeground,fontSize=AmbType.caption,modifier=Modifier.testTag("home.briefing.coverage"))}
                entry.links.forEach {link->TextButton(onClick={onLink(link)},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("home.briefing.link")){Text(link.label)}}
                Text("From "+(content?.authorName ?: content?.author?.let {studio.otherstuff.ambulando.core.transport.shortLabel(it)} ?: "your chief of staff")+" · "+entry.generatedAt.atZone(Briefing.ZONE).format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm"))+" Perth",fontSize=AmbType.caption2,color=colors.mutedForeground,modifier=Modifier.testTag("home.briefing.source"))
            }
            else -> {
                Text(if(day==today&&pending)"Your morning briefing is due at 05:30 Perth." else if(day==today)"Today’s briefing hasn’t arrived yet." else "No briefing was saved for this day.",color=colors.mutedForeground,modifier=Modifier.testTag("home.briefing.empty"))
                if(latest!=null)TextButton(onClick={onDay(latest)},modifier=Modifier.testTag("home.briefing.stale")){Text("Read the previous briefing · $latest")}
            }
        }
        TextButton(onClick=onRefresh,modifier=Modifier.testTag("home.briefing.refresh")){Text("Refresh briefing")}
    }
}
