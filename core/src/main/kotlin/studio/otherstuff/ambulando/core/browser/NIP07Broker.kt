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
    data class Cipher(val peer: String, val text: String, val decrypt: Boolean) : NIP07Method
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
            "nip44.encrypt", "nip44.decrypt" -> {
                val obj = params as? JsonObject
                val peer = obj?.get("pubkey")?.let { JsonFields.string(it) }
                val text = obj?.get("text")?.let { JsonFields.string(it) }
                val decrypt = method == "nip44.decrypt"
                if (obj == null || obj.keys != setOf("pubkey", "text")) invalid("NIP-44 requires only pubkey and text")
                else if (peer == null || !peer.matches(Regex("[0-9a-fA-F]{64}"))) invalid("NIP-44 requires a 32-byte hex peer key")
                else if (text == null || text.toByteArray(Charsets.UTF_8).size !in (if (decrypt) 132..87472 else 1..65535)) invalid("Invalid NIP-44 input size")
                else NIP07Result.Success(Cipher(peer.lowercase(), text, decrypt))
            }
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

/** Independent, account-bound capabilities. Legacy connection answers never populate this store. */
data class NIP07Grant(val origin: String, val account: String, val operation: String)

object NIP07Grants {
    const val STATE_KEY = "browser.operations.v1"
    fun encode(grants: Set<NIP07Grant>): String = JsonFields.encode(JsonArray(grants.map {
        JsonArray(listOf(JsonPrimitive(it.origin), JsonPrimitive(it.account), JsonPrimitive(it.operation)))
    }))
    fun decode(text: String?): Set<NIP07Grant> = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { value ->
            val row = value as? JsonArray ?: return@mapNotNull null
            if (row.size != 3) return@mapNotNull null
            val origin = JsonFields.string(row[0]) ?: return@mapNotNull null
            val account = JsonFields.string(row[1]) ?: return@mapNotNull null
            val operation = JsonFields.string(row[2]) ?: return@mapNotNull null
            if (BrowserURLPolicy.origin(parseUri(origin)) != origin || !account.matches(Regex("[0-9a-f]{64}")) ||
                !(operation in setOf("encrypt", "decrypt") || operation.removePrefix("sign:").toIntOrNull()?.let { operation == "sign:$it" && it in 0..65535 } == true)) null
            else NIP07Grant(origin, account, operation)
        }.toSet()
    }.getOrDefault(emptySet())
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
        data class Cipher(val peer: String, val decrypt: Boolean) : Kind
        data object Connect : Kind
        data class Sign(val event: NostrEventTemplate) : Kind
    }
}

sealed interface NIP07Decision {
    /**
     * `remember`: persist only this operation (and event kind when signing), exact origin and active account.
     * Connection answers remain in their separate, account-scoped store.
     */
    data class Allow(val remember: Boolean) : NIP07Decision
    data class Deny(val remember: Boolean) : NIP07Decision
}

/** What a request is answered with. */
sealed interface NIP07Reply {
    data class PublicKey(val hex: String) : NIP07Reply
    data class Event(val event: NostrEvent) : NIP07Reply
    data class Text(val text: String) : NIP07Reply
    data object Relays : NIP07Reply

    /** The value the page's promise resolves with (plain JSON types). `getRelays` answers `{}`. */
    val javaScriptValue: JsonElement
        get() = when (this) {
            is Text -> JsonPrimitive(text)
            is PublicKey -> JsonPrimitive(hex)
            Relays -> JsonObject(emptyMap())
            is Event -> event.toJsonObject()
        }
}

/**
 * Native side of `window.nostr`. Pages never see the key; they get the public key (after the user allows the site)
 * and results approved in a sheet naming the site, or through an explicit persisted capability for the same
 * origin, account and operation. Connection grants cannot authorize signing or ciphers.
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
    private val cipher: (suspend (String, String, String, Boolean) -> String)? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    grants: Set<NIP07Grant> = emptySet(),
    private val persistGrants: (Set<NIP07Grant>) -> Unit = {},
    private val accountSigner: (suspend (String, NostrEventTemplate) -> NostrEvent)? = null,
) {
    companion object {
        const val MAX_PROMPTS_PER_TAB = 4
    }

    private val lock = Any()
    private val promptList = ArrayList<NIP07Prompt>()
    private var authorizationRevision = 0L
    private val documentRevisions = HashMap<UUID, Long>()
    private fun documentRevision(context: NIP07Context): Long = synchronized(lock) { documentRevisions[context.tabId] ?: 0L }
    private var lockedState = locked
    private var siteMap = sites.toMap()
    private var operationGrants = grants.toSet()
    val grants: Set<NIP07Grant> get() = synchronized(lock) { operationGrants.toSet() }
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
        if (!isCurrent() || BrowserURLPolicy.origin(parseUri(context.origin)) != context.origin) return NIP07Result.Failure(NIP07Error.PageChanged)
        val parsed = when (val r = NIP07Method.parse(method, params, nowSeconds())) {
            is NIP07Result.Success -> r.value
            is NIP07Result.Failure -> return r
        }
        return when (parsed) {
            NIP07Method.GetRelays -> NIP07Result.Success(NIP07Reply.Relays)
            is NIP07Method.Unsupported -> NIP07Result.Failure(NIP07Error.Unsupported(parsed.name))
            NIP07Method.GetPublicKey -> connect(context, isCurrent)
            is NIP07Method.Cipher -> crypt(parsed, context, isCurrent)
            is NIP07Method.SignEvent -> sign(parsed.template, parsed.requestedPubkey, context, isCurrent)
        }
    }

    private fun failure(e: NIP07Error) = NIP07Result.Failure(e)

    private suspend fun crypt(call: NIP07Method.Cipher, context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        val operation = cipher ?: return failure(NIP07Error.Unsupported("nip44"))
        val revision = synchronized(lock) { authorizationRevision }
        val pageRevision = documentRevision(context)
        fun invalid(): NIP07Error? {
            if (locked) return NIP07Error.Locked
            if (!isCurrent() || pageRevision != documentRevision(context)) return NIP07Error.PageChanged
            if (synchronized(lock) { revision != authorizationRevision } || sites[context.origin] == NIP07SitePermission.DENIED) return NIP07Error.Denied
            return null
        }
        return try {
            invalid()?.let { return failure(it) }
            val own = publicKey()
            invalid()?.let { return failure(it) }
            val grant = NIP07Grant(context.origin, own, if (call.decrypt) "decrypt" else "encrypt")
            var remember = false
            if (grant !in grants) {
                when (val answer = ask(NIP07Prompt.Kind.Cipher(call.peer, call.decrypt), context, isCurrent, own)) {
                    is NIP07Result.Failure -> return answer
                    is NIP07Result.Success -> {
                        val decision = answer.value as? NIP07Decision.Allow ?: return failure(NIP07Error.Denied)
                        remember = decision.remember
                    }
                }
            }
            invalid()?.let { return failure(it) }
            if (publicKey() != own) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            if (remember && !rememberGrant(grant, revision)) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            val result = operation(own, call.peer, call.text, call.decrypt)
            invalid()?.let { return failure(it) }
            if (publicKey() != own) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            NIP07Result.Success(NIP07Reply.Text(result))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("NIP-44 operation failed"))
        }
    }

    private suspend fun connect(context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        val revision = synchronized(lock) { authorizationRevision }
        val pageRevision = documentRevision(context)
        return try {
            val own = publicKey()
            var remember: NIP07SitePermission? = null
            when (sites[context.origin]) {
                NIP07SitePermission.DENIED -> return failure(NIP07Error.Denied)
                NIP07SitePermission.ALLOWED -> Unit
                null -> when (val answer = ask(NIP07Prompt.Kind.Connect, context, isCurrent, own)) {
                    is NIP07Result.Failure -> return answer
                    is NIP07Result.Success -> when (val d = answer.value) {
                        is NIP07Decision.Deny -> {
                            if (d.remember && publicKey() == own && isCurrent()) synchronized(lock) {
                                if (!lockedState && revision == authorizationRevision) setSite(context.origin, NIP07SitePermission.DENIED)
                            }
                            return failure(NIP07Error.Denied)
                        }
                        is NIP07Decision.Allow -> if (d.remember) remember = NIP07SitePermission.ALLOWED
                    }
                }
            }
            if (publicKey() != own) return failure(NIP07Error.Denied)
            if (locked) return failure(NIP07Error.Locked)
            if (!isCurrent() || pageRevision != documentRevision(context)) return failure(NIP07Error.PageChanged)
            synchronized(lock) {
                if (revision != authorizationRevision || pageRevision != documentRevision(context)) return failure(NIP07Error.Denied)
                if (remember != null) setSite(context.origin, remember)
            }
            NIP07Result.Success(NIP07Reply.PublicKey(own))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("no identity"))
        }
    }

    private suspend fun sign(template: NostrEventTemplate, requested: String?, context: NIP07Context, isCurrent: () -> Boolean): NIP07Result<NIP07Reply> {
        if (sites[context.origin] == NIP07SitePermission.DENIED) return failure(NIP07Error.Denied)
        val revision = synchronized(lock) { authorizationRevision }
        val pageRevision = documentRevision(context)
        val own = try {
            publicKey()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("no identity"))
        }
        if (requested != null && requested != own) return failure(NIP07Error.Invalid("the event's pubkey is not this identity"))
        fun invalid(): NIP07Error? = when {
            locked -> NIP07Error.Locked
            !isCurrent() || pageRevision != documentRevision(context) -> NIP07Error.PageChanged
            synchronized(lock) { revision != authorizationRevision } || sites[context.origin] == NIP07SitePermission.DENIED -> NIP07Error.Denied
            else -> null
        }
        val grant = NIP07Grant(context.origin, own, "sign:${template.kind}")
        var remember = false
        if (grant !in grants) {
            when (val answer = ask(NIP07Prompt.Kind.Sign(template), context, isCurrent, own)) {
                is NIP07Result.Failure -> return answer
                is NIP07Result.Success -> {
                    val decision = answer.value as? NIP07Decision.Allow ?: return failure(NIP07Error.Denied)
                    remember = decision.remember
                }
            }
        }
        return try {
            invalid()?.let { return failure(it) }
            if (publicKey() != own) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            if (remember && !rememberGrant(grant, revision)) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            val event = accountSigner?.invoke(own, template) ?: signer(template)
            invalid()?.let { return failure(it) }
            if (event.pubkey != own || publicKey() != own) return failure(NIP07Error.Denied)
            invalid()?.let { return failure(it) }
            NIP07Result.Success(NIP07Reply.Event(event))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(if (locked) NIP07Error.Locked else NIP07Error.Invalid("the event could not be signed"))
        }
    }

    private fun rememberGrant(grant: NIP07Grant, revision: Long): Boolean {
        synchronized(lock) {
            if (lockedState || authorizationRevision != revision) return false
            val saved = operationGrants + grant
            persistGrants(saved)
            operationGrants = saved
        }
        changed()
        return true
    }

    private suspend fun ask(kind: NIP07Prompt.Kind, context: NIP07Context, isCurrent: () -> Boolean, expectedAccount: String? = null): NIP07Result<NIP07Decision> {
        if (synchronized(lock) { promptList.count { it.context.tabId == context.tabId } } >= MAX_PROMPTS_PER_TAB) return failure(NIP07Error.Busy)
        val revision = synchronized(lock) { authorizationRevision }
        val pageRevision = documentRevision(context)
        val key = try { publicKey() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (_: Exception) { return failure(NIP07Error.Locked) }
        if (expectedAccount != null && key != expectedAccount) return failure(NIP07Error.Denied)
        val npub = NostrKeys.npub(key)
        // The key read above suspends; the page or lock may have changed meanwhile.
        if (locked) return failure(NIP07Error.Locked)
        if (!isCurrent() || pageRevision != documentRevision(context)) return failure(NIP07Error.PageChanged)
        val prompt = NIP07Prompt(UUID.randomUUID(), context, kind, npub)
        val waiter = CompletableDeferred<NIP07Decision?>()
        synchronized(lock) {
            if (lockedState || revision != authorizationRevision || pageRevision != documentRevision(context)) return failure(NIP07Error.Denied)
            if (promptList.count { it.context.tabId == context.tabId } >= MAX_PROMPTS_PER_TAB) return failure(NIP07Error.Busy)
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
    fun documentChanged(tabId: UUID, documentId: Long) {
        synchronized(lock) { documentRevisions[tabId] = (documentRevisions[tabId] ?: 0L) + 1 }
        cancel { it.context.tabId == tabId && it.context.documentId != documentId }
    }

    fun tabClosed(tabId: UUID) {
        synchronized(lock) { documentRevisions[tabId] = (documentRevisions[tabId] ?: 0L) + 1 }
        cancel { it.context.tabId == tabId }
    }

    /** Locking cancels pending work and refuses new requests; explicit remembered capabilities survive unlocking. */
    fun setLocked(value: Boolean) {
        synchronized(lock) {
            if (value == lockedState) return
            authorizationRevision++
            lockedState = value

        }
        if (value) cancel { true }
        changed()
    }

    /** Settings › browser: forget what was decided for `origin`. */
    fun forget(origin: String) {
        synchronized(lock) {
            authorizationRevision++
            operationGrants = operationGrants.filterNot { it.origin == origin }.toSet()
            siteMap = siteMap - origin
            persistGrants(operationGrants)
            persist.save(siteMap)
        }
        cancel { it.context.origin == origin }
        changed()
    }

    /** Revoke one capability and invalidate work that may have already used it. */
    fun revoke(grant: NIP07Grant) {
        synchronized(lock) {
            authorizationRevision++
            operationGrants = operationGrants - grant
            persistGrants(operationGrants)
        }
        cancel { it.context.origin == grant.origin }
        changed()
    }

    /** Identity removed or disconnected: nothing a site was allowed survives. */
    fun reset() {
        synchronized(lock) {
            authorizationRevision++
            operationGrants = emptySet()
            siteMap = emptyMap()
            persistGrants(operationGrants)
            persist.save(siteMap)
        }
        cancel { true }
        changed()
    }

    private fun setSite(origin: String, value: NIP07SitePermission?) {
        val saved = synchronized(lock) {
            if (value == NIP07SitePermission.DENIED) authorizationRevision++
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
