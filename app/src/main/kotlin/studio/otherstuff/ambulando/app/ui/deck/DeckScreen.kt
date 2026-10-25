package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.store.LocalStore

/**
 * Deck's cards in the web's order (sol `DECK_MOBILE_CARDS`). Pinned items is present only while the member has pins;
 * Inbox is the default.
 */
enum class DeckCard(val key: String, val title: String) {
    HELLO("hello", "Hello and Links"),
    INBOX("inbox", "Inbox"),
    PINNED("pinned", "Pinned items"),
    CANVAS("canvas", "Canvas"),
    FEED("feed", "Feed"),
    RECENT("recent", "Recent channels");

    companion object {
        fun visible(hasPins: Boolean): List<DeckCard> = entries.filter { it != PINNED || hasPins }
        fun fromKey(key: String?): DeckCard? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Deck on a phone (iOS `DeckView`): full-width cards you swipe between, with dots below. The chosen card and each
 * card's scroll position are kept (every card stays composed); records open on top of Deck, so Back returns to the same
 * card. A card out of sight pauses its store query. Deck reads the sidebar's Scope | Channel context.
 *
 * @param active Deck is the current section (out of sight, every card pauses).
 * @param inboxRequests Incremented by the app model when a sidebar choice should show the Inbox card.
 */
@Composable
fun DeckScreen(
    store: LocalStore,
    screen: ScreenScope,
    navigation: WorkNavigation,
    pins: PinsSnapshot,
    intents: DeckIntents,
    modifier: Modifier = Modifier,
    status: SurfaceStatusLookup = SurfaceStatusLookup.NONE,
    active: Boolean = true,
    inboxRequests: Int = 0,
) {
    val cards = DeckCard.visible(pins.visibleItems.isNotEmpty())
    var stored by rememberSaveable { mutableStateOf(DeckCard.INBOX.key) }
    val initial = cards.indexOf(DeckCard.fromKey(stored) ?: DeckCard.INBOX).takeIf { it >= 0 } ?: cards.indexOf(DeckCard.INBOX)
    val pager = rememberPagerState(initialPage = initial) { cards.size }
    val scope = rememberCoroutineScope()
    val currentCards by androidx.compose.runtime.rememberUpdatedState(cards)

    // The settled page names the chosen card.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> currentCards.getOrNull(page)?.let { stored = it.key } }
    }
    // Pins appearing or the last pin going: keep the chosen card, or show Inbox in place of a card that went away.
    LaunchedEffect(cards) {
        val target = cards.indexOf(DeckCard.fromKey(stored)).takeIf { it >= 0 } ?: cards.indexOf(DeckCard.INBOX)
        if (pager.currentPage != target) pager.scrollToPage(target)
        cards.getOrNull(target)?.let { stored = it.key }
    }
    // A sidebar choice shows the Inbox card.
    LaunchedEffect(inboxRequests) {
        if (inboxRequests > 0) {
            stored = DeckCard.INBOX.key
            pager.animateScrollToPage(cards.indexOf(DeckCard.INBOX))
        }
    }
    val shown = cards.getOrNull(pager.currentPage)
    Column(modifier.fillMaxSize().background(Amb.colors.background)) {
        HorizontalPager(
            state = pager, beyondViewportPageCount = DeckCard.entries.size,
            key = { cards[it].key }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("deck.track"),
        ) { page ->
            val card = cards[page]
            val visible = active && shown == card
            when (card) {
                DeckCard.HELLO -> DeckHelloCard(store, screen, navigation, intents, status, visible)
                DeckCard.INBOX -> DeckInboxCard(store, screen, navigation, pins, intents, status, visible)
                DeckCard.PINNED -> DeckPinnedCard(screen, pins, intents)
                DeckCard.CANVAS -> DeckCanvasCard(store, screen, intents, status, visible)
                DeckCard.FEED -> DeckFeedCard()
                DeckCard.RECENT -> DeckRecentCard(store, screen, navigation, intents, visible)
            }
        }
        DeckPagination(cards, shown) { card -> scope.launch { pager.animateScrollToPage(cards.indexOf(card)) } }
    }
}

/** The dots under Deck (sol `.deck-mobile-pagination`). Each is a button labelled with its card. */
@Composable
private fun DeckPagination(cards: List<DeckCard>, selection: DeckCard?, onSelect: (DeckCard) -> Unit) {
    val amb = Amb.colors
    Row(
        Modifier.fillMaxWidth().background(amb.background).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        cards.forEach { card ->
            val on = selection == card
            Box(
                Modifier.size(width = 32.dp, height = 30.dp)
                    .clickable(role = Role.Button) { onSelect(card) }
                    .semantics { contentDescription = "Show ${card.title}"; selected = on }
                    .testTag("deck.page.${card.key}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(if (on) amb.primary else amb.muted, CircleShape))
            }
        }
    }
}

/** A card's frame (iOS `DeckCardFrame`): heading row with its actions, and the card's own vertical scroll. */
@Composable
internal fun DeckCardFrame(
    title: String,
    identifier: String,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val amb = Amb.colors
    Column(Modifier.fillMaxSize().testTag("deck.card.$identifier")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f).semantics { heading() }.testTag("deck.card.title"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            actions()
        }
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
        )
    }
}
