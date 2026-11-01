package studio.otherstuff.ambulando.core.deck

import studio.otherstuff.ambulando.core.context.WorkContext

/** Which channel Deck's New thread composer writes to (iOS `DeckCompose`). */
object DeckCompose {
    /**
     * The channel to preselect when the composer opens: the context's own channel when it is still eligible, else the
     * only eligible channel, else none ("Choose channel").
     */
    fun initialChannel(context: WorkContext, eligible: List<String>): String? {
        context.channelId?.let { if (it in eligible) return it }
        return eligible.singleOrNull()
    }

    /**
     * The channel after the context or the eligible list changed while the composer is open. A channel the user chose is
     * kept while the scope stays the same and it is still eligible; a scope change starts the choice again.
     */
    fun reconcile(chosen: String?, from: WorkContext, to: WorkContext, eligible: List<String>): String? {
        if (from.scopeId == to.scopeId && chosen != null && chosen in eligible) return chosen
        return initialChannel(to, eligible)
    }
}
