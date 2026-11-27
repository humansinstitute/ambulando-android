package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowCircleRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.chat.ChatTime
import studio.otherstuff.ambulando.app.ui.deck.AppLauncherCard
import studio.otherstuff.ambulando.app.ui.deck.DeckHello
import studio.otherstuff.ambulando.app.ui.deck.DeckLaunchers
import studio.otherstuff.ambulando.app.ui.deck.LauncherLogo
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import java.time.LocalDate

/** Home's goals in focus order: the first [HomeFocusCount] shown prominently. */
internal const val HomeFocusCount = 3
/** Goals shown before "Show all": the goals in focus and a quieter peek at the next, so Home stays calm with many goals. */
internal const val HomeGoalPeek = HomeFocusCount + 1

/**
 * Workspace Home (board "Ambulando-Next"): the viewer's active goals in their chosen focus order, a private daily agent briefing, and the latest active chats. Deck stays a separate section. Wide windows show goals beside
 * the briefing and chats; a phone stacks them.
 */
@Composable internal fun ConnectedHome(model: AppModel) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val goals = rememberGoals(model) ?: return
    val state by goals.state.collectAsState()
    GoalsRefresh(model, goals)
    val hello by remember(model.store, c.id, w.id) {
        model.store.liveQuery(DeckHello.matches(w.id)) { DeckHello.load(model.store, c.id, w.id, c.signerNpub) }
    }.collectAsState(DeckLaunchers())
    val ids = LatestChats.channelIds(w.id, w.accessRemoved, model.channels, model.scopes)
    val latest by key(c.id, w.id, ids) {
        remember(model.store, c.id, w.id, ids) {
            model.store.liveQuery({ it.workspaceId == w.id && it.table in ConversationInvalidation.rows }) {
                LatestChats.load(model.store.reader, w.id, ids, c.signerNpub)
            }
        }.collectAsState(null)
    }
    val labels = channelLabels(model)
    val people by remember(model.store, c.id, w.id) {
        model.store.liveQuery({ it.workspaceId == w.id || it.table == StoreTable.PROFILES }) { model.store.reader.people(c.id, w.id) }
    }.collectAsState(studio.otherstuff.ambulando.core.profiles.PeopleDirectory())
    var creating by remember(goals) { mutableStateOf(false) }
    var moveError by remember(goals) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var moving by remember(goals) { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val focus = GoalFocus.order(state.goals.filter { it.goal.status == GoalStatus.ACTIVE })
    val ready = state.space is GoalsSpaceState.Ready
    /** Saves a move through [GoalsController.move]; a partial save is reported ("Saved n of m order changes. …") and reloaded. */
    fun move(fileId: String, toIndex: Int) {
        if (moving) return
        moving = true
        scope.launch {
            moveError = try { goals.move(focus, fileId, toIndex); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: GoalMoveIncomplete) { e.message }
            catch (e: Exception) { (e as? GoalError)?.message ?: studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(e) ?: "The order could not be saved." }
            finally { moving = false }
        }
    }
    val goalsSection: LazyListScope.() -> Unit = {
        item("goals.header") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Goals", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, modifier = Modifier.weight(1f).semantics { heading() }.testTag("home.goals.title"))
                TextButton(onClick = { services.open(WorkRoute.Goals(w.id)) }, modifier = Modifier.testTag("home.goals.all")) { Text("All goals") }
                if (ready) TextButton(onClick = { creating = true }, enabled = services.unlocked && !w.accessRemoved, modifier = Modifier.testTag("home.goals.new")) { Text("New goal") }
            }
        }
        state.error?.let { item("goals.error") { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("home.goals.error")) } }
        moveError?.let { item("goals.moveError") { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("home.goals.moveError")) } }
        when (val space = state.space) {
            is GoalsSpaceState.NeedsSetup -> item("goals.setup") { GoalsSetupCard(goals, space) }
            is GoalsSpaceState.Unsafe -> item("goals.unsafe") { Text(space.reason, color = Amb.colors.destructive) }
            else -> {}
        }
        if (!state.loaded && state.error == null) item("goals.loading") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("home.goals.loading")) }
        if (ready && state.loaded && focus.isEmpty()) item("goals.empty") {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No active goals. A goal has a name, a few checkpoints and the work that moves it forward.", color = Amb.colors.mutedForeground, fontSize = AmbType.subheadline, modifier = Modifier.testTag("home.goals.empty"))
                Button(onClick = { creating = true }, enabled = services.unlocked && !w.accessRemoved, modifier = Modifier.testTag("home.goals.emptyNew")) { Text("New goal") }
            }
        }
        if (focus.size > HomeFocusCount) item("goals.hint") {
            Text("The first $HomeFocusCount goals are in focus. Drag a goal or use its menu to change the order.", fontSize = AmbType.caption, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("home.goals.hint"))
        }
        item("goals.list") {
            HomeGoalList(focus, enabled = services.unlocked && !w.accessRemoved && !moving, onOpen = { services.open(WorkRoute.Goal(w.id, it)) }, onMove = ::move, expanded = showAll)
        }
        if (focus.size > HomeGoalPeek) item("goals.more") {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.testTag("home.goals.more")) {
                Text(if (showAll) "Show fewer goals" else "Show all ${focus.size} goals")
            }
        }
    }
    val sideSection: LazyListScope.() -> Unit = {
        item("briefing") { ConnectedPersonalBriefing(model) }
        item("latest.header") { Text("Latest", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, modifier = Modifier.padding(top = 16.dp).semantics { heading() }.testTag("home.latest.title")) }
        val rows = latest
        when {
            rows == null -> item("latest.loading") { Text("Loading conversations…", color = Amb.colors.mutedForeground, fontSize = AmbType.footnote) }
            rows.isEmpty() -> item("latest.empty") { Text("No conversations on this device yet.", color = Amb.colors.mutedForeground, fontSize = AmbType.footnote, modifier = Modifier.testTag("home.latest.empty")) }
            else -> items(rows, key = { "latest:" + it.id }) { row ->
                LatestChatCard(row, labels[row.channelId], row.participantNpubs.take(3).map { people.name(it) }.ifEmpty { row.participants.take(3) }) {
                    services.open(row.threadId?.let { WorkRoute.Thread(w.id, row.channelId, it) } ?: WorkRoute.Channel(w.id, row.channelId))
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Amb.colors.background).testTag("home.screen")) {
        val wide = maxWidth >= 840.dp
        val padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        if (wide) Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("home.goals"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("hello") { HomeHello(hello) }
                goalsSection()
            }
            LazyColumn(Modifier.width(380.dp).fillMaxHeight().testTag("home.side"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) { sideSection() }
        } else LazyColumn(Modifier.fillMaxSize().testTag("home.goals"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("hello") { HomeHello(hello) }
            goalsSection()
            item("side.gap") { Spacer(Modifier.height(8.dp)) }
            sideSection()
        }
    }
    if (creating) GoalEditorDialog(null, onDismiss = { creating = false }) { name, description, start, target ->
        val created = goals.create(Goal.create(name, description, start, target))
        creating = false
        services.open(WorkRoute.Goal(w.id, created.id))
    }
}

/** The welcome line and the member's apps and personal agents, as on Deck's Hello card. */
@Composable private fun HomeHello(hello: DeckLaunchers) {
    val services = LocalAppServices.current
    Column(Modifier.fillMaxWidth().testTag("home.hello"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Welcome" + (hello.name?.let { " $it" } ?: "") + ",\nwhere will we focus today?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, lineHeight = 26.sp)
        if (hello.apps.isNotEmpty() || hello.agents.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hello.apps.forEach { app ->
                Box(Modifier.width(240.dp)) { AppLauncherCard(app.title, app.description ?: "App", { LauncherLogo(app.logoCandidates, app.title, 36.dp) }) { app.safeLaunchURL?.let { services.openUrl(it.toString()) } } }
            }
            hello.agents.forEach { agent ->
                val name = hello.people?.name(agent.npub) ?: agent.npub.take(18)
                Box(Modifier.width(240.dp)) { AppLauncherCard(name, "My agent", { LauncherLogo(emptyList(), name, 36.dp) }) { agent.safeURL?.let { services.openUrl(it.toString()) } } }
            }
        }
    }
}

/**
 * The goals in focus order. The first [HomeFocusCount] are prominent; later goals are quieter but readable. Order changes
 * by long-press drag, the ⋮ menu, accessibility actions or Alt+Up/Down; each move saves one goal.
 */
@Composable internal fun HomeGoalList(focus: List<GoalDocument>, enabled: Boolean, onOpen: (String) -> Unit, onMove: (String, Int) -> Unit, expanded: Boolean = true) {
    val current by rememberUpdatedState(focus)
    var dragging by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableIntStateOf(-1) }
    val bounds = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (if (expanded) focus else focus.take(HomeGoalPeek)).forEachIndexed { index, doc ->
            key(doc.id) {
                var distance by remember { mutableFloatStateOf(0f) }
                val top = remember { floatArrayOf(0f) }
                val finger = remember { floatArrayOf(0f) }
                fun moveBy(offset: Int): Boolean {
                    val at = current.indexOfFirst { it.id == doc.id }
                    val to = (at + offset).coerceIn(0, current.lastIndex)
                    if (!enabled || at < 0 || at == to) return false
                    onMove(doc.id, to); return true
                }
                HomeGoalCard(
                    doc, index, prominent = index < HomeFocusCount, count = focus.size, enabled = enabled, onOpen = { onOpen(doc.id) }, moveBy = ::moveBy,
                    modifier = Modifier
                        .onGloballyPositioned { if (dragging == null) { bounds[doc.id] = it.boundsInRoot(); top[0] = it.boundsInRoot().top } }
                        .zIndex(if (dragging == doc.id) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging == doc.id) distance else 0f; alpha = if (dragging == doc.id) 0.85f else 1f }
                        .pointerInputDrag(enabled, doc.id,
                            onStart = { y -> dragging = doc.id; distance = 0f; finger[0] = top[0] + y; target = current.indexOfFirst { it.id == doc.id } },
                            onDrag = { dy ->
                                distance += dy; finger[0] += dy
                                target = current.indexOfFirst { other -> bounds[other.id]?.let { finger[0] < it.bottom } == true }.let { if (it < 0) current.lastIndex else it }
                            },
                            onEnd = {
                                val from = current.indexOfFirst { it.id == doc.id }
                                if (target >= 0 && target != from) onMove(doc.id, target)
                                dragging = null; distance = 0f; target = -1
                            },
                            onCancel = { dragging = null; distance = 0f; target = -1 }),
                )
            }
        }
    }
}

/** Long-press then drag (as Deck's pinned items); [onDrag] receives each vertical step. */
private fun Modifier.pointerInputDrag(enabled: Boolean, id: String, onStart: (Float) -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit): Modifier =
    if (!enabled) this else this.then(Modifier.pointerInput(id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onStart(it.y) }, onDragEnd = onEnd, onDragCancel = onCancel,
            onDrag = { change, drag -> change.consume(); onDrag(drag.y) },
        )
    })

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HomeGoalCard(doc: GoalDocument, index: Int, prominent: Boolean, count: Int, enabled: Boolean, onOpen: () -> Unit, moveBy: (Int) -> Boolean, modifier: Modifier) {
    val amb = Amb.colors
    val goal = doc.goal
    val shape = RoundedCornerShape(Amb.radiusPanel)
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().alpha(if (prominent) 1f else 0.62f)
            .background(amb.card, shape).border(if (prominent) 1.5.dp else 1.dp, if (prominent) amb.foreground.copy(alpha = 0.7f) else amb.border, shape)
            .clickable(onClickLabel = "Open goal", onClick = onOpen)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isAltPressed) false
                else when (e.key) { Key.DirectionUp -> moveBy(-1); Key.DirectionDown -> moveBy(1); else -> false }
            }
            .semantics {
                stateDescription = (if (prominent) "In focus, " else "Out of focus, ") + "${index + 1} of $count. ${GoalFormat.progress(goal)}"
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Move up") { moveBy(-1) },
                    CustomAccessibilityAction("Move down") { moveBy(1) },
                    CustomAccessibilityAction("Move to top") { moveBy(-count) },
                )
            }
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp).testTag("home.goal.${doc.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(goal.name, fontSize = if (prominent) 17.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("home.goal.name"))
                Spacer(Modifier.width(10.dp))
                CheckpointDots(goal)
            }
            Text(goal.description, fontSize = AmbType.subheadline, color = amb.mutedForeground, maxLines = if (prominent) 2 else 1, overflow = TextOverflow.Ellipsis)
            // Dates first, progress on its own line.
            val start = GoalFormat.date(goal.startDate); val target = GoalFormat.date(goal.targetDate)
            // Large text or a narrow card moves the target under the start instead of running them together.
            if (start != null || target != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                start?.let { Text("Start $it", fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1) }
                target?.let { Text("Target $it", fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1) }
            }
            Text(GoalFormat.progress(goal), fontSize = AmbType.caption, color = amb.mutedForeground)
        }
        if (enabled && count > 1) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("home.goal.menu")) { Icon(Icons.Outlined.MoreVert, "Reorder ${goal.name}", tint = amb.mutedForeground) }
            DropdownMenu(menu, { menu = false }) {
                if (index > 0) DropdownMenuItem({ Text("Move to top") }, { menu = false; moveBy(-count) }, modifier = Modifier.testTag("home.goal.top"))
                if (index > 0) DropdownMenuItem({ Text("Move up") }, { menu = false; moveBy(-1) }, modifier = Modifier.testTag("home.goal.up"))
                if (index < count - 1) DropdownMenuItem({ Text("Move down") }, { menu = false; moveBy(1) }, modifier = Modifier.testTag("home.goal.down"))
            }
        }
    }
}

/** Checkpoints as a line of dots, in the detail timeline's vocabulary: reached filled, next ringed, later open. */
@Composable private fun CheckpointDots(goal: Goal) {
    val amb = Amb.colors
    val shown = goal.checkpoints.take(8)
    if (shown.isEmpty()) return
    val next = goal.nextCheckpointId
    Row(
        Modifier.drawBehind { drawLine(amb.border, Offset(6.dp.toPx(), size.height / 2), Offset(size.width - 6.dp.toPx(), size.height / 2), 1.5.dp.toPx()) }
            .clearAndSetSemantics { contentDescription = GoalFormat.progress(goal); testTag = "home.goal.dots" },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { cp ->
            Box(Modifier.size(12.dp).background(amb.card, CircleShape).then(
                when { cp.reached -> Modifier.background(amb.primary, CircleShape); cp.id == next -> Modifier.border(3.5.dp, amb.primary, CircleShape); else -> Modifier.border(1.5.dp, amb.mutedForeground, CircleShape) }))
        }
        if (goal.checkpoints.size > shown.size) Text("+${goal.checkpoints.size - shown.size}", fontSize = AmbType.caption2, color = amb.mutedForeground)
    }
}

/** One recent conversation: "#channel" and time, its title, then who is in it and the latest words. */
@Composable private fun LatestChatCard(row: ConversationRow, channel: String?, participants: List<String>, onOpen: () -> Unit) {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusPanel)
    Column(Modifier.fillMaxWidth().background(amb.card, shape).border(1.dp, amb.border, shape).clickable(onClickLabel = "Open conversation", onClick = onOpen)
        .padding(horizontal = 14.dp, vertical = 10.dp).testTag("home.latest.row"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel?.let { "#$it" } ?: "Chat", fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(ChatTime.label(row.latestAt), fontSize = AmbType.caption2, color = amb.mutedForeground)
        }
        Text(MentionCodec.plain(row.title).ifBlank { "Conversation" }, fontWeight = if (row.unread) FontWeight.Bold else FontWeight.SemiBold, fontSize = AmbType.subheadline,
            color = amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val excerpt = MentionCodec.plain(row.excerpt).replace("⟦", "").replace("⟧", "").takeIf { it.isNotBlank() && it != MentionCodec.plain(row.title) }
        Text(listOfNotNull(participants.joinToString(", ").ifBlank { null }, excerpt).joinToString(" · "), fontSize = AmbType.footnote, color = amb.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
