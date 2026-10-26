package studio.otherstuff.ambulando.core.chat

/**
 * What a composer edit means for automatic text attachments. The text field reports every change as `before → after`;
 * this classifies it without any platform type so it can be tested on the JVM.
 */
object ComposerPaste {
    /** A single insertion longer than this many code points is a paste (a keyboard or IME inserts a word, not a page). */
    const val TYPING_BURST = 16

    sealed class Decision {
        /** The edit stands. */
        data object Accept : Decision()
        /** Typing or IME text that would pass the inline cap: the field keeps its previous text. */
        data object Reject : Decision()
        /** The inserted [text] becomes a text attachment; the field keeps its previous text and selection. */
        data class Attach(val text: String) : Decision()
    }

    /**
     * @param clipboard the plain text on the clipboard, when known: an insertion equal to it is a paste even if short,
     *   so a short paste that would pass the inline cap becomes a file rather than being refused.
     */
    fun decide(before: String, after: String, clipboard: String? = null, selectionStart: Int? = null, selectionEnd: Int? = null): Decision {
        val selected = if (selectionStart != null && selectionEnd != null &&
            selectionStart in 0..before.length && selectionEnd in 0..before.length) {
            val start = minOf(selectionStart, selectionEnd)
            val end = maxOf(selectionStart, selectionEnd)
            val prefix = before.substring(0, start)
            val suffix = before.substring(end)
            if (after.length >= prefix.length + suffix.length && after.startsWith(prefix) && after.endsWith(suffix))
                PastedTextPolicy.Insertion(start, after.substring(start, after.length - suffix.length), prefix + suffix)
            else null
        } else null
        val insertion = selected ?: PastedTextPolicy.insertion(before, after) ?: return Decision.Accept
        val points = PastedTextPolicy.codePoints(insertion.inserted)
        val paste = points > TYPING_BURST || (clipboard != null && points > 1 && insertion.inserted == clipboard)
        if (paste) return if (PastedTextPolicy.shouldAttach(insertion.inserted, insertion.remaining)) Decision.Attach(insertion.inserted) else Decision.Accept
        if (PastedTextPolicy.fitsInline(after)) return Decision.Accept
        // Already over the cap (an old draft): shrinking edits are fine, growth is not.
        return if (after.length <= before.length) Decision.Accept else Decision.Reject
    }
}
