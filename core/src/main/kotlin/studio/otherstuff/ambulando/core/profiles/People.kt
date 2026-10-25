package studio.otherstuff.ambulando.core.profiles

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.net.URI
import java.time.Instant

/** Short form of an npub for display (`npub1abcde…wxyz`); short values are returned as they are. */
fun shortNpubLabel(npub: String): String = if (npub.length > 16) "${npub.take(10)}…${npub.takeLast(4)}" else npub

/**
 * A public Nostr profile (kind 0) cached for one connection, keyed by the author's public key.
 *
 * Only the fields the client shows are kept, each trimmed and bounded. `status` records whether the last relay check
 * found a profile; a `MISSING` row is a negative cache entry so absent profiles are not asked for on every launch.
 */
data class ProfileRecord(
    val pubkey: String,
    val npub: String,
    val displayName: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val eventId: String? = null,
    val eventCreatedAt: Long? = null,
    val status: Status,
    val checkedAt: Instant,
) {
    enum class Status(val wire: String) { FOUND("found"), MISSING("missing") }

    /** The profile's own name, `display_name` before `name`. */
    val preferredName: String? get() = displayName ?: name

    /** NIP-01 replaceable-event order: the newer `created_at` wins; on a tie the lower event id wins. */
    fun isNewer(than: ProfileRecord): Boolean {
        val mine = eventCreatedAt ?: return false
        val mineId = eventId ?: return false
        val theirs = than.eventCreatedAt ?: return true
        val theirId = than.eventId ?: return true
        return mine > theirs || (mine == theirs && mineId < theirId)
    }

    companion object {
        /** Builds a record from a verified kind-0 event. Unknown, non-string and oversized fields are dropped. */
        fun from(event: NostrEvent, checkedAt: Instant): ProfileRecord? {
            if (event.kind != 0) return null
            val npub = NostrKeys.npub(event.pubkey) ?: return null
            val fields = NostrProfileContent.parse(event.content)
            return ProfileRecord(event.pubkey, npub, fields.displayName, fields.name, fields.picture?.toString(), fields.nip05,
                event.id, event.createdAt, Status.FOUND, checkedAt)
        }
    }
}

/** Field extraction from kind-0 `content` (a JSON object as text), following the web client's profile reader. */
object NostrProfileContent {
    const val MAX_CONTENT_BYTES = 64 * 1024
    const val MAX_NAME_LENGTH = 120
    internal const val MAX_NIP05_LENGTH = 256

    data class Fields(
        val displayName: String? = null,
        val name: String? = null,
        val picture: URI? = null,
        val nip05: String? = null,
    )

    fun parse(content: String): Fields {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) return Fields()
        val o: JsonObject = JsonFields.parseObject(content) ?: return Fields()
        fun text(keys: List<String>, limit: Int): String? = keys.firstNotNullOfOrNull { clean(JsonFields.string(o[it]), limit) }
        // The first picture field holding an address the app will fetch.
        val picture = listOf("picture", "image", "avatar_url", "avatarUrl", "avatar").asSequence()
            .mapNotNull { key -> clean(JsonFields.string(o[key]), AvatarURLPolicy.MAX_LENGTH)?.let(AvatarURLPolicy::validate) }.firstOrNull()
        return Fields(
            displayName = text(listOf("display_name", "displayName"), MAX_NAME_LENGTH),
            name = text(listOf("name", "username"), MAX_NAME_LENGTH),
            picture = picture,
            nip05 = text(listOf("nip05"), MAX_NIP05_LENGTH),
        )
    }

    /** A single-line, trimmed string within `limit` characters, or null. Control and format characters become spaces. */
    fun clean(raw: String?, limit: Int): String? {
        if (raw == null) return null
        val line = buildString(raw.length) {
            for (c in raw) append(if (Character.getType(c).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() }) ' ' else c)
        }.trim()
        if (line.isEmpty()) return null
        return if (JsonFields.length(line) > limit) JsonFields.prefix(line, limit) else line
    }
}

/**
 * Which avatar addresses the app will fetch. Avatars come from a profile, which anyone can write, so only plain
 * HTTPS to a public host name is accepted: no credentials in the URL, no IP literals or local names (the request
 * must not reach the user's own network), and a bounded length.
 */
object AvatarURLPolicy {
    const val MAX_LENGTH = 2048

    fun validate(value: String): URI? {
        if (JsonFields.length(value) > MAX_LENGTH) return null
        val url = runCatching { URI(value) }.getOrNull() ?: return null
        if (url.scheme?.lowercase() != "https" || url.rawUserInfo != null) return null
        val host = url.host?.lowercase() ?: return null
        return if (isPublicHostName(host)) url else null
    }

    internal fun isPublicHostName(host: String): Boolean {
        if (host.isEmpty() || !host.contains('.') || host.endsWith('.')) return false
        // IPv6 literals ("[::1]") and IPv4 literals ("10.0.0.1", also written as 0x0a.1 or a bare number).
        if (host.contains(':') || host.startsWith('[')) return false
        val last = host.split('.').last()
        if (last.all { it.isDigit() } || last.startsWith("0x")) return false
        for (suffix in listOf("localhost", "local", "internal", "home.arpa", "lan", "onion")) {
            if (host == suffix || host.endsWith(".$suffix")) return false
        }
        return true
    }
}

/** How a person is shown: name, initials for the fallback avatar, and the avatar address if one is known. */
data class PersonDisplay(
    val npub: String,
    val name: String,
    val avatarURL: URI?,
    val nameSource: NameSource,
) {
    enum class NameSource { WORKSPACE, AGENT, MESSAGE, NOSTR, NPUB }

    val initials: String get() = PersonInitials.of(name)

    companion object {
        /** Someone the directory does not know: short npub and initials, no avatar. */
        fun unknown(npub: String) = PersonDisplay(npub, shortNpubLabel(npub), null, NameSource.NPUB)
    }
}

/**
 * Whether a name from Tower or a profile can be shown as a person's name. Tower fills some actors with a setup
 * placeholder ("Workspace Creator", ...) and some clients store the key itself as the name; neither may hide a real
 * public profile name, so both count as no name.
 */
object PersonNamePolicy {
    /** Tower's setup actor display names plus the names its upsert treats as replaceable. */
    internal val placeholders: Set<String> = setOf("workspace creator", "flight deck pg creator", "flight deck pg workspace owner",
        "flight deck pg smoke viewer", "flight deck pg collaborator")

    fun usable(value: String?): String? {
        val name = NostrProfileContent.clean(value, NostrProfileContent.MAX_NAME_LENGTH) ?: return null
        val lower = name.lowercase()
        if (lower in placeholders) return null
        // A full or shortened npub ("npub1abc…xyz", "npub1abc...xyz") or a 64-character hex key.
        if (lower.startsWith("npub1") && !lower.contains(' ')) return null
        if (lower.length == 64 && lower.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return name
    }
}

/**
 * Initials as the web helper builds them: first letters of the first two words, otherwise the first two characters,
 * upper-cased; `?` for an empty label.
 */
object PersonInitials {
    fun of(label: String): String {
        val cleaned = label.trim()
        if (cleaned.isEmpty()) return "?"
        // Like the web helper, but a word's first letter or digit ("You (sample)" → "YS", not "Y(").
        fun lead(word: String): Char? = word.firstOrNull { it.isLetterOrDigit() }
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() && lead(it) != null }
        if (parts.size >= 2) {
            val a = lead(parts[0]); val b = lead(parts[1])
            if (a != null && b != null) return "$a$b".uppercase()
        }
        val letters = cleaned.filter { it.isLetterOrDigit() }
        return JsonFields.prefix(letters.ifEmpty { cleaned }, 2).uppercase()
    }
}

/**
 * A workspace member as the directory needs it (seam: built from Tower's member records once the transport layer
 * lands). `displayName` is Tower's own name for the member, if any.
 */
data class DirectoryMember(val npub: String, val displayName: String?)

/**
 * Everything the app knows about the people of one workspace, resolved once per screen load (off the main thread).
 *
 * Membership comes only from Tower (`members`, `workspace_agents`); a public profile decorates a person the client
 * already sees, never adds one. Name order follows the web's sender naming: the public profile (`display_name`, then
 * `name`), then the workspace's own name for the member (Tower `display_name`), the agent's name, the name Tower
 * attached to the message, then the short npub. Tower names that are setup placeholders or just a key are skipped
 * ([PersonNamePolicy]). Avatar: an agent's Tower `avatar_url`, then the profile picture, else initials.
 */
class PeopleDirectory private constructor(
    private val resolved: Map<String, PersonDisplay>,
    val memberNpubs: Set<String>,
) {
    data class AgentInfo(val name: String, val avatarURL: String?)

    constructor() : this(emptyMap(), emptySet())

    /** The person for `npub`. `senderLabel` is the name Tower attached to a message (`created_by_actor_label`); it is
     *  used only when neither the public profile nor the workspace directory names the person. */
    fun person(npub: String?, senderLabel: String? = null): PersonDisplay {
        if (npub.isNullOrEmpty()) return PersonDisplay("", PersonNamePolicy.usable(senderLabel) ?: "Unknown", null, PersonDisplay.NameSource.MESSAGE)
        val base = resolved[npub] ?: PersonDisplay.unknown(npub)
        val label = PersonNamePolicy.usable(senderLabel)
        if (base.nameSource != PersonDisplay.NameSource.NPUB || label == null) return base
        return PersonDisplay(npub, label, base.avatarURL, PersonDisplay.NameSource.MESSAGE)
    }

    fun name(npub: String?, senderLabel: String? = null): String = person(npub, senderLabel).name

    /** Name map for screens that only show text (tasks, documents). */
    val names: Map<String, String> get() = resolved.mapValues { it.value.name }

    val count: Int get() = resolved.size

    override fun equals(other: Any?): Boolean = other is PeopleDirectory && other.resolved == resolved && other.memberNpubs == memberNpubs
    override fun hashCode(): Int = resolved.hashCode() * 31 + memberNpubs.hashCode()

    companion object {
        operator fun invoke(members: List<DirectoryMember>, agents: Map<String, AgentInfo>, profiles: List<ProfileRecord>,
                            extra: List<String> = emptyList()): PeopleDirectory {
            val profileByNpub = HashMap<String, ProfileRecord>()
            for (p in profiles) {
                val current = profileByNpub[p.npub]
                profileByNpub[p.npub] = if (current == null) p else if (current.isNewer(p)) current else p
            }
            val memberNpubs = LinkedHashSet<String>()
            val memberName = HashMap<String, String>()
            for (m in members) {
                memberNpubs.add(m.npub)
                PersonNamePolicy.usable(m.displayName)?.let { memberName[m.npub] = it }
            }
            val everyone = members.map { it.npub }.toSet() + agents.keys + profileByNpub.keys + extra
            val resolved = HashMap<String, PersonDisplay>()
            for (npub in everyone) {
                val profile = profileByNpub[npub]
                val agent = agents[npub]
                val avatar = agent?.avatarURL?.let(AvatarURLPolicy::validate) ?: profile?.picture?.let(AvatarURLPolicy::validate)
                val profileName = profile?.preferredName?.let(PersonNamePolicy::usable)
                val agentName = agent?.let { PersonNamePolicy.usable(it.name) }
                val (name, source) = when {
                    profileName != null -> profileName to PersonDisplay.NameSource.NOSTR
                    memberName[npub] != null -> memberName.getValue(npub) to PersonDisplay.NameSource.WORKSPACE
                    agentName != null -> agentName to PersonDisplay.NameSource.AGENT
                    else -> shortNpubLabel(npub) to PersonDisplay.NameSource.NPUB
                }
                resolved[npub] = PersonDisplay(npub, name, avatar, source)
            }
            return PeopleDirectory(resolved, memberNpubs)
        }
    }
}
