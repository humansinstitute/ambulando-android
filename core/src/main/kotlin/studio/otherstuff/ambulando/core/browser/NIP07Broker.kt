package studio.otherstuff.ambulando.core.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.util.UUID

/** Why a `window.nostr` request was not answered. `message` is what the page's promise is rejected with. */
sealed class NIP07Error {
    data object Locked : NIP07Error()
    data object Denied : NIP07Error()
    data object PageChanged : NIP07Error()
    data object Closed : NIP07Error()
    data object Busy : NIP07Error()
    data class Invalid(val why: String) : NIP07Error()
    data class Unsupported(val method: String) : NIP07Error()

    val message: String
        get() = when (this) {
            Locked -> "Ambulando is locked."
            Denied -> "The user rejected the request."
            PageChanged -> "The page changed before the request was answered."
            Closed -> "The request was cancelled."
            Busy -> "Too many requests are waiting for the user."
            is Invalid -> "Invalid request: $why"
            is Unsupported -> "$method is not supported by Ambulando."
        }
}

/** Success or a page-visible refusal. */
sealed interface NIP07Result<out T> {
    data class Success<T>(val value: T) : NIP07Result<T>
    data class Failure(val error: NIP07Error) : NIP07Result<Nothing>
}

/** A parsed NIP-07 call. Parameters arrive from page JavaScript, so everything is checked and bounded here. */
sealed interface NIP07Method {
    data object GetPublicKey : NIP07Method
    /** `requestedPubkey` is the `pubkey` the page put in the event, if any; it must be the user's own. */
    data class SignEvent(val template: NostrEventTemplate, val requestedPubkey: String?) : NIP07Method
    data object GetRelays : NIP07Method
    data class Unsupported(val name: String) : NIP07Method

    companion object {
        const val MAX_CONTENT_BYTES = 256 * 1024
        const val MAX_TAGS = 2000
        const val MAX_TAG_ITEMS = 100
        const val MAX_TAG_BYTES = 256 * 1024
        /** 2100-01-01: later timestamps are refused rather than shown as a plausible date. */
        internal const val LATEST_CREATED_AT = 4_102_444_800L

        /** `nowSeconds` is the Unix time used when the page gives no `created_at`. */
        fun parse(method: String, params: JsonElement?, nowSeconds: Long): NIP07Result<NIP07Method> = when (method) {
            "getPublicKey" -> NIP07Result.Success(GetPublicKey)
            "getRelays" -> NIP07Result.Success(GetRelays)
            "signEvent" -> parseEvent(params, nowSeconds)
            else -> {
                val name = method.take(40).filter { it.isLetter() || it.isDigit() || it == '.' }
                NIP07Result.Success(Unsupported(name.ifEmpty { "This method" }))
            }
        }

        private fun invalid(why: String) = NIP07Result.Failure(NIP07Error.Invalid(why))

        internal fun parseEvent(params: JsonElement?, nowSeconds: Long): NIP07Result<NIP07Method> {
            val event = params as? JsonObject ?: return invalid("signEvent needs an event object")
            val kind = JsonFields.safeInt(event["kind"])?.takeIf { it in 0..65535 }?.toInt() ?: return invalid("kind must be 0–65535")
            val content = if (JsonFields.isNull(event["content"])) "" else JsonFields.string(event["content"]) ?: return invalid("content must be a string")
            if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) return invalid("content is too large")
            val tags = ArrayList<List<String>>()
            val rawTags = event["tags"]
            if (!JsonFields.isNull(rawTags)) {
                val list = rawTags as? JsonArray
                if (list == null || list.size > MAX_TAGS) return invalid("tags must be a list of at most $MAX_TAGS")
                var bytes = 0
                for (item in list) {
                    val tag = item as? JsonArray
                    if (tag == null || tag.size > MAX_TAG_ITEMS) return invalid("each tag must be a short list of strings")
                    val strings = ArrayList<String>(tag.size)
                    for (value in tag) {
                        val text = JsonFields.string(value) ?: return invalid("tag values must be strings")
                        bytes += text.toByteArray(Charsets.UTF_8).size
                        strings.add(text)
                    }
                    if (bytes > MAX_TAG_BYTES) return invalid("tags are too large")
                    tags.add(strings)
                }
            }
            val createdAt: Long = if (!JsonFields.isNull(event["created_at"])) {
                JsonFields.safeInt(event["created_at"])?.takeIf { it > 0 && it <= LATEST_CREATED_AT } ?: return invalid("created_at must be a Unix time")
            } else nowSeconds
            var requested: String? = null
            if (!JsonFields.isNull(event["pubkey"])) {
                requested = JsonFields.string(event["pubkey"])?.lowercase() ?: return invalid("pubkey must be a string")
            }
            return NIP07Result.Success(SignEvent(NostrEventTemplate(kind, tags, content, createdAt), requested))
        }
    }
}

/** A site's standing answer to "may this site see your public key" (and, if denied, ask for anything). */
enum class NIP07SitePermission(val wire: String) {
    ALLOWED("allowed"), DENIED("denied");

    companion object {
        fun of(wire: String?): NIP07SitePermission? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Remembered site answers as stored in app state under [STATE_KEY]: a JSON object of origin → `"allowed"` /
 * `"denied"`. Unknown values are dropped on read.
 */
object NIP07Sites {
    const val STATE_KEY = "browser.sites.v1"

    fun encode(sites: Map<String, NIP07SitePermission>): String =
        JsonFields.encode(JsonObject(sites.mapValues { JsonPrimitive(it.value.wire) }))

    fun decode(text: String?): Map<String, NIP07SitePermission> {
        val o = text?.let { JsonFields.parseObject(it) } ?: return emptyMap()
        return o.mapNotNull { (origin, value) -> NIP07SitePermission.of(JsonFields.string(value))?.let { origin to it } }.toMap()
    }
}

/** Where remembered site answers are kept (app state in production). */
fun interface NIP07SitePersistence {
    fun save(sites: Map<String, NIP07SitePermission>)
}

/**
 * The page a request came from: the tab, the document loaded in it (a new number on every main-frame commit) and its
 * HTTPS origin as the web view reported it.
 */
data class NIP07Context(val tabId: UUID, val documentId: Long, val origin: String)

/** A question shown to the user in a native sheet. */
data class NIP07Prompt(
    val id: UUID,
    val context: NIP07Context,
    val kind: Kind,
    /** The user's own key, as shown in the sheet. */
    val npub: String?,
) {
    sealed interface Kind {
        data object Connect : Kind
        data class Sign(val event: NostrEventTemplate) : Kind
    }

    /** Whether "allow for this site until Ambulando locks" may be offered (authentication kinds only). */
    val offersSessionGrant: Boolean get() = (kind as? Kind.Sign)?.let { it.event.kind in NIP07Broker.SESSION_GRANT_KINDS } ?: false
}

sealed interface NIP07Decision {
    /**
     * `remember`: for connect, keep the answer for the site; for an authentication kind, allow that kind for the site
     * until Ambulando locks.
     */
    data class Allow(val remember: Boolean) : NIP07Decision
    data class Deny(val remember: Boolean) : NIP07Decision
}

/** What a request is answered with. */
sealed interface NIP07Reply {
    data class PublicKey(val hex: String) : NIP07Reply
    data class Event(val event: NostrEvent) : NIP07Reply
    data object Relays : NIP07Reply

    /** The value the page's promise resolves with (plain JSON types). `getRelays` answers `{}`. */
    val javaScriptValue: JsonElement
        get() = when (this) {
            is PublicKey -> JsonPrimitive(hex)
            Relays -> JsonObject(emptyMap())
            is Event -> event.toJsonObject()
        }
}

/**
 * Native side of `window.nostr`. Pages never see the key; they get the public key (after the user allows the site)
 * and events the user approved one at a time in a sheet that names the site. Only the authentication kinds in
 * [SESSION_GRANT_KINDS] may be allowed for a site until Ambulando locks.
 *
 * A request is tied to the document that made it: when the tab navigates, reloads or closes, or Ambulando locks, it
 * is cancelled, and `isCurrent` is asked again after the user answers and before anything is signed.
 *
 * The UI observes [prompts] (through [onChange]) and answers with [resolve]. State changes are serialised on one
 * lock and never held across a suspension, so `resolve`, `setLocked` and navigation callbacks may come from the UI
 * thread while requests wait.
 */
class NIP07Broker(
    locked: Boolean,
    sites: Map<String, NIP07SitePermission> = emptyMap(),
    private val publicKey: suspend () -> String,
    private val signer: suspend (NostrEventTemplate) -> NostrEvent,
    private val persist: NIP07SitePersistence = NIP07SitePersistence { },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        /** NIP-42 relay authentication and NIP-98 HTTP authentication. */
        val SESSION_GRANT_KINDS: Set<Int> = setOf(22242, 27235)
        const val MAX_PROMPTS_PER_TAB = 4

        internal fun grantKey(origin: String, kind: Int) = "$origin|$kind"
    }

    private val lock = Any()
    private val promptList = ArrayList<NIP07Prompt>()
    private var lockedState = locked
    private var siteMap = sites.toMap()
    private var sessionGrants: Set<String> = emptySet()
    private val waiters = HashMap<UUID, CompletableDeferred<NIP07Decision?>>()

    /** Called after `prompts`, `sites` or `locked` change. */
    @Volatile var onChange: (() -> Unit)? = null

    val prompts: List<NIP07Prompt> get() = synchronized(lock) { promptList.toList() }
    val current: NIP07Prompt? get() = synchronized(lock) { promptList.firstOrNull() }
    val locked: Boolean get() = synchronized(lock) { lockedState }
    val sites: Map<String, NIP07SitePermission> get() = synchronized(lock) { siteMap }

    private fun changed() { onChange?.invoke() }

    /** Answers one request. `isCurrent` reports whether the requesting document is still the one shown in its tab. */
    suspend fun handle(method: String, params: JsonElement?, context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        if (locked) return NIP07Result.Failure(NIP07Error.Locked)
        if (!isCurrent()) return NIP07Result.Failure(NIP07Error.PageChanged)
        val parsed = when (val r = NIP07Method.parse(method, params, nowSeconds())) {
            is NIP07Result.Success -> r.value
            is NIP07Result.Failure -> return r
        }
        return when (parsed) {
            NIP07Method.GetRelays -> NIP07Result.Success(NIP07Reply.Relays)
            is NIP07Method.Unsupported -> NIP07Result.Failure(NIP07Error.Unsupported(parsed.name))
            NIP07Method.GetPublicKey -> connect(context, isCurrent)
            is NIP07Method.SignEvent -> sign(parsed.template, parsed.requestedPubkey, context, isCurrent)
        }
    }

    private fun failure(e: NIP07Error) = NIP07Result.Failure(e)

    private suspend fun connect(context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        when (sites[context.origin]) {
            NIP07SitePermission.DENIED -> return failure(NIP07Error.Denied)
            NIP07SitePermission.ALLOWED -> Unit
            null -> when (val answer = ask(NIP07Prompt.Kind.Connect, context, isCurrent)) {
                is NIP07Result.Failure -> return answer
                is NIP07Result.Success -> when (val d = answer.value) {
                    is NIP07Decision.Deny -> {
                        if (d.remember) setSite(context.origin, NIP07SitePermission.DENIED)
                        return failure(NIP07Error.Denied)
                    }
                    is NIP07Decision.Allow -> if (d.remember) setSite(context.origin, NIP07SitePermission.ALLOWED)
                }
            }
        }
        if (locked) return failure(NIP07Error.Locked)
        if (!isCurrent()) return failure(NIP07Error.PageChanged)
        return try {
            NIP07Result.Success(NIP07Reply.PublicKey(publicKey()))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("no identity"))
        }
    }

    private suspend fun sign(template: NostrEventTemplate, requested: String?, context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        if (sites[context.origin] == NIP07SitePermission.DENIED) return failure(NIP07Error.Denied)
        val own = try {
            publicKey()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("no identity"))
        }
        if (requested != null && requested != own) return failure(NIP07Error.Invalid("the event's pubkey is not this identity"))
        val grant = grantKey(context.origin, template.kind)
        if (grant !in synchronized(lock) { sessionGrants }) {
            when (val answer = ask(NIP07Prompt.Kind.Sign(template), context, isCurrent)) {
                is NIP07Result.Failure -> return answer
                is NIP07Result.Success -> when (val d = answer.value) {
                    is NIP07Decision.Deny -> return failure(NIP07Error.Denied)
                    is NIP07Decision.Allow -> if (d.remember && template.kind in SESSION_GRANT_KINDS) synchronized(lock) { sessionGrants = sessionGrants + grant }
                }
            }
        }
        // Re-checked after the user answered: a lock or a navigation in the meantime wins over the approval.
        if (locked) return failure(NIP07Error.Locked)
        if (!isCurrent()) return failure(NIP07Error.PageChanged)
        return try {
            val event = signer(template)
            if (locked) return failure(NIP07Error.Locked)
            if (!isCurrent()) return failure(NIP07Error.PageChanged)
            NIP07Result.Success(NIP07Reply.Event(event))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("the event could not be signed"))
        }
    }

    private suspend fun ask(kind: NIP07Prompt.Kind, context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Decision> {
        if (synchronized(lock) { promptList.count { it.context.tabId == context.tabId } } >= MAX_PROMPTS_PER_TAB) return failure(NIP07Error.Busy)
        val npub = runCatching { publicKey() }.getOrNull()?.let { NostrKeys.npub(it) }
        // The key read above suspends; the page or lock may have changed meanwhile.
        if (locked) return failure(NIP07Error.Locked)
        if (!isCurrent()) return failure(NIP07Error.PageChanged)
        val prompt = NIP07Prompt(UUID.randomUUID(), context, kind, npub)
        val waiter = CompletableDeferred<NIP07Decision?>()
        synchronized(lock) {
            waiters[prompt.id] = waiter
            promptList.add(prompt)
        }
        changed()
        val decision = try {
            waiter.await()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            finish(prompt.id, null)
            throw e
        } ?: return failure(if (locked) NIP07Error.Locked else if (isCurrent()) NIP07Error.Closed else NIP07Error.PageChanged)
        return NIP07Result.Success(decision)
    }

    /** The user's answer to a prompt (from the sheet). */
    fun resolve(id: UUID, decision: NIP07Decision) = finish(id, decision)

    /** Cancels what the tab's earlier documents asked (called on every main-frame commit). */
    fun documentChanged(tabId: UUID, documentId: Long) = cancel { it.context.tabId == tabId && it.context.documentId != documentId }

    fun tabClosed(tabId: UUID) = cancel { it.context.tabId == tabId }

    /** Locking cancels every waiting request, forgets the until-lock grants and refuses new requests until unlocked. */
    fun setLocked(value: Boolean) {
        synchronized(lock) {
            if (value == lockedState) return
            lockedState = value
            if (value) sessionGrants = emptySet()
        }
        if (value) cancel { true }
        changed()
    }

    /** Settings › browser: forget what was decided for `origin`. */
    fun forget(origin: String) {
        synchronized(lock) { sessionGrants = sessionGrants.filterNot { it.startsWith("$origin|") }.toSet() }
        setSite(origin, null)
    }

    /** Identity removed or disconnected: nothing a site was allowed survives. */
    fun reset() {
        synchronized(lock) { sessionGrants = emptySet() }
        cancel { true }
        val saved = synchronized(lock) { siteMap = emptyMap(); siteMap }
        persist.save(saved)
        changed()
    }

    private fun setSite(origin: String, value: NIP07SitePermission?) {
        val saved = synchronized(lock) {
            siteMap = if (value == null) siteMap - origin else siteMap + (origin to value)
            siteMap
        }
        persist.save(saved)
        changed()
    }

    private fun cancel(match: (NIP07Prompt) -> Boolean) {
        val ids = synchronized(lock) { promptList.filter(match).map { it.id } }
        for (id in ids) finish(id, null)
    }

    private fun finish(id: UUID, decision: NIP07Decision?) {
        val waiter = synchronized(lock) {
            promptList.removeAll { it.id == id }
            waiters.remove(id)
        }
        waiter?.complete(decision)
        changed()
    }
}
