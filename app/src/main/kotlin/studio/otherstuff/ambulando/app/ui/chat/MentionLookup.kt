package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import studio.otherstuff.ambulando.app.MentionLookupFailure
import studio.otherstuff.ambulando.core.store.MentionCandidate

internal data class MentionLookup(val query: String?, val candidates: List<MentionCandidate> = emptyList(), val error: String? = null, val loading: Boolean = true)

/** Query-tagged results cannot flash or be selected after the caret, workspace or query changes. */
@Composable internal fun rememberMentionLookup(query: String?, boundary: Any?, lookup: suspend (String) -> List<MentionCandidate>): MentionLookup =
    rememberMentionLookup(query, boundary, { emptyList() }, lookup)

/** Publish local matches before debouncing or awaiting optional remote discovery. */
@Composable internal fun rememberMentionLookup(
    query: String?, boundary: Any?, localLookup: suspend (String) -> List<MentionCandidate>,
    lookup: suspend (String) -> List<MentionCandidate>,
): MentionLookup {
    val result by key(query, boundary) {
        produceState(MentionLookup(query), query, boundary) {
            if (query != null) {
                try {
                    value = MentionLookup(query, localLookup(query))
                    delay(150)
                    value = MentionLookup(query, lookup(query), loading = false)
                } catch (e: CancellationException) { throw e }
                catch (e: MentionLookupFailure) { value = MentionLookup(query, e.candidates, e.message, false) }
                catch (e: Exception) { value = value.copy(error = "Mentions unavailable. Try again.", loading = false) }
            }
        }
    }
    return result
}
