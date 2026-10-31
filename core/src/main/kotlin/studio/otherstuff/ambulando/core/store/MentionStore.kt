package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.chat.BodySegment
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.chat.MentionType

/** One `@` lookahead result: the mention it inserts and a short description of what it is. */
data class MentionCandidate(val mention: Mention, val sublabel: String)

/**
 * The composer's `@` lookahead over this device's store (never the network). As on the web, every type filters on its
 * name (case-insensitive substring); a type prefix (`doc:`, `task:`, `thread:`, `person:`, `agent:`, `channel:`,
 * `scope:`) limits the search to one type. People and agents come first, an exact name first among them, then
 * documents, tasks, chat threads, channels and scopes. Document and task titles, which can number in the thousands,
 * are found through the trigram index in [MentionIndex]; the other lists are small and read directly.
 */
object MentionSearch {
    /** Results shown at most. */
    const val LIMIT = 12

    /** Results for a bare `@` (the web's defaults plus recent threads). */
    const val DEFAULT_LIMIT = 10
    /** Most recently tagged people and agents a bare `@` lists first. */
    const val RECENT_LIMIT = 7

    private val prefixes = mapOf(
        "person" to MentionType.PERSON, "agent" to MentionType.AGENT, "doc" to MentionType.DOC, "document" to MentionType.DOC,
        "task" to MentionType.TASK, "thread" to MentionType.CHAT, "channel" to MentionType.CHANNEL, "chat" to MentionType.CHANNEL,
        "scope" to MentionType.SCOPE,
    )

    /** The type a `prefix:` query names (null: every type) and the remaining needle, lower-cased. */
    fun parse(query: String): Pair<MentionType?, String> {
        val trimmed = query.trim()
        val colon = trimmed.indexOf(':')
        if (colon > 0) prefixes[trimmed.substring(0, colon).lowercase()]?.let { return it to trimmed.substring(colon + 1).trim().lowercase() }
        return null to trimmed.lowercase()
    }

    /** A thread's name from its first message: the first line as plain text (no Markdown, mention tokens as names). */
    fun preview(body: String): String? {
        val line = MentionCodec.plain(body).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val text = studio.otherstuff.ambulando.core.docs.MarkdownInline.parse(line.trimStart('#', '>', ' ')).joinToString("") { it.text }.trim()
        return text.ifEmpty { null }?.let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
    }

    /** A chat thread's token id: the web's copied thread reference `<channelId>#<rootMessageId>`. */
    fun threadId(channelId: String, rootMessageId: String) = "$channelId#$rootMessageId"

    /** Splits a `chat` token id into channel and message ids (null when malformed). */
    fun parseThreadId(id: String): Pair<String, String>? {
        val hash = id.indexOf('#')
        if (hash <= 0 || hash == id.length - 1) return null
        return id.substring(0, hash) to id.substring(hash + 1)
    }
}

/**
 * Lookahead candidates for `query` (the text after `@`). [names] are the display names the app knows for npubs (public
 * profiles), used when a member has no workspace display name. Records no longer available to this identity, deleted
 * threads and archived documents are never offered.
 */
suspend fun StoreReader.mentionCandidates(workspaceId: String, query: String, names: Map<String, String> = emptyMap(), viewerNpub: String? = null): List<MentionCandidate> = read {
    val (only, needle) = MentionSearch.parse(query)
    val bare = needle.isEmpty() && only == null
    fun wants(type: MentionType) = only == null || only == type || (only == MentionType.PERSON && type == MentionType.AGENT)
    fun cap(type: MentionType, normal: Int, defaults: Int) = when {
        bare -> defaults
        only == type || (only == MentionType.PERSON && type == MentionType.AGENT) -> MentionSearch.LIMIT
        else -> normal
    }
    val out = mutableListOf<MentionCandidate>()
    val actors = LinkedHashMap<String, MentionCandidate>()

    // People and agents: workspace members plus the workspace's agents (agents can be addressed in every channel).
    if (wants(MentionType.PERSON) || wants(MentionType.AGENT)) {
        for (member in memberRows(workspaceId)) {
            val type = if (member.isAgent) MentionType.AGENT else MentionType.PERSON
            val label = member.displayName?.takeIf { it.isNotBlank() } ?: names[member.npub] ?: member.label
            actors[member.npub] = MentionCandidate(Mention(type, member.npub, label), if (member.isAgent) "Agent" else "Person")
        }
        query("SELECT agent_npub, display_name FROM workspace_agents WHERE workspace_id = ? AND visible = 1 ORDER BY sort_order, display_name", workspaceId) {
            it.text(0)!! to it.text(1)!!
        }.forEach { (npub, name) -> actors[npub] = MentionCandidate(Mention(MentionType.AGENT, npub, name), "Agent") }
        val matching = actors.values.filter { c ->
            (only == null || only == MentionType.PERSON || c.mention.type == only) &&
                (needle.isEmpty() || c.mention.label.lowercase().contains(needle) || c.mention.npub.lowercase().startsWith(needle))
        }.sortedWith(compareBy<MentionCandidate>({ it.mention.label.lowercase() != needle }, { !it.mention.label.lowercase().startsWith(needle) }, { it.mention.label.lowercase() }))
        out += matching.take(cap(MentionType.PERSON, 4, 2))
    }

    if (wants(MentionType.DOC)) out += MentionIndex.find(this, workspaceId, "doc", needle, cap(MentionType.DOC, 4, 3)).map { (id, title) ->
        MentionCandidate(Mention(MentionType.DOC, id, title), "Document")
    }
    if (wants(MentionType.TASK)) out += MentionIndex.find(this, workspaceId, "task", needle, cap(MentionType.TASK, 4, 2)).map { (id, title) ->
        MentionCandidate(Mention(MentionType.TASK, id, title), "Task")
    }

    if (wants(MentionType.CHAT)) {
        // A thread is named by its title, otherwise its first message. Only threads with a stored root can be referenced.
        query(
            """SELECT t.channel_id, coalesce(t.source_message_id, (SELECT m.id FROM messages m WHERE m.workspace_id = t.workspace_id AND m.thread_id = t.id
                 AND m.deleted = 0 ORDER BY m.created_at, m.id LIMIT 1)) AS root, t.title,
                 (SELECT m.body FROM messages m WHERE m.workspace_id = t.workspace_id AND m.id = t.source_message_id) AS root_body, c.name
               FROM threads t LEFT JOIN channels c ON c.workspace_id = t.workspace_id AND c.id = t.channel_id
               WHERE t.workspace_id = ?1 AND t.deleted = 0 AND t.access_removed = 0
                 AND (?2 = '' OR instr(lower(coalesce(t.title, '')), ?2) > 0
                   OR instr(lower(coalesce((SELECT m.body FROM messages m WHERE m.workspace_id = t.workspace_id AND m.id = t.source_message_id), '')), ?2) > 0)
               ORDER BY t.updated_at DESC, t.id LIMIT ?3""",
            workspaceId, needle, cap(MentionType.CHAT, 3, 2) * 2,
        ) { row -> listOf(row.text(0), row.text(1), row.text(2), row.text(3), row.text(4)) }
            .mapNotNull { (channel, root, title, rootBody, channelName) ->
                if (channel == null || root == null) return@mapNotNull null
                val label = title?.takeIf { it.isNotBlank() } ?: MentionSearch.preview(rootBody ?: "") ?: return@mapNotNull null
                MentionCandidate(Mention(MentionType.CHAT, MentionSearch.threadId(channel, root), label), channelName?.let { "Thread in #$it" } ?: "Thread")
            }.take(cap(MentionType.CHAT, 3, 2)).let { out += it }
    }

    if (wants(MentionType.CHANNEL)) out += query(
        """SELECT id, name FROM channels WHERE workspace_id = ?1 AND access_removed = 0 AND (?2 = '' OR instr(lower(name), ?2) > 0)
           ORDER BY instr(lower(name), ?2) <> 1, name COLLATE NOCASE LIMIT ?3""",
        workspaceId, needle, cap(MentionType.CHANNEL, 2, 2),
    ) { MentionCandidate(Mention(MentionType.CHANNEL, it.text(0)!!, it.text(1)!!), "Channel") }

    if (wants(MentionType.SCOPE) && !bare) out += query(
        """SELECT id, name, kind FROM scopes WHERE workspace_id = ?1 AND access_removed = 0 AND (?2 = '' OR instr(lower(name), ?2) > 0)
           ORDER BY instr(lower(name), ?2) <> 1, name COLLATE NOCASE LIMIT ?3""",
        workspaceId, needle, cap(MentionType.SCOPE, 2, 0),
    ) { MentionCandidate(Mention(MentionType.SCOPE, it.text(0)!!, it.text(1)!!), it.text(2)?.replaceFirstChar { c -> c.uppercase() } ?: "Scope") }

    if (bare && viewerNpub != null) {
        // A bare `@` lists whom this person tagged most recently first (newest message first), each once, under their current
        // name and only while the directory still lists them; the usual defaults follow.
        val recent = LinkedHashMap<String, MentionCandidate>()
        for (body in query("SELECT body FROM messages WHERE workspace_id = ? AND sender_npub = ? AND deleted = 0 AND body LIKE '%(mention:%' ORDER BY created_at DESC, id DESC LIMIT 300",
            workspaceId, viewerNpub) { it.text(0) ?: "" }) {
            for (segment in MentionCodec.segments(body)) {
                val mention = (segment as? BodySegment.MentionSegment)?.mention ?: continue
                actors[mention.npub]?.let { recent.putIfAbsent(mention.npub, it) }
            }
            if (recent.size >= MentionSearch.RECENT_LIMIT) break
        }
        val rest = out.filter { c -> recent.values.none { it.mention.npub == c.mention.npub && it.mention.type == c.mention.type } }
        return@read (recent.values.take(MentionSearch.RECENT_LIMIT) + rest).take(MentionSearch.DEFAULT_LIMIT + MentionSearch.RECENT_LIMIT)
    }

    out.take(if (bare) MentionSearch.DEFAULT_LIMIT else MentionSearch.LIMIT)
}

/**
 * Trigram full-text index of document and task titles for the `@` lookahead, so a substring of any title is found
 * without scanning the tables. Maintained by triggers inside the writer's transactions (as [SearchIndex]); queries
 * shorter than a trigram, and the bare `@`, read the most recently updated records instead.
 */
internal object MentionIndex {
    val statements: List<String> = listOf(
        "CREATE VIRTUAL TABLE mention_titles USING fts5(workspace_id UNINDEXED, kind UNINDEXED, record_id UNINDEXED, title, tokenize = 'trigram')",
        """CREATE TRIGGER mention_docs_ai AFTER INSERT ON docs BEGIN
          INSERT INTO mention_titles(workspace_id, kind, record_id, title) VALUES(new.workspace_id, 'doc', new.id, new.title); END""",
        """CREATE TRIGGER mention_docs_au AFTER UPDATE OF title ON docs BEGIN
          DELETE FROM mention_titles WHERE workspace_id = old.workspace_id AND kind = 'doc' AND record_id = old.id;
          INSERT INTO mention_titles(workspace_id, kind, record_id, title) VALUES(new.workspace_id, 'doc', new.id, new.title); END""",
        """CREATE TRIGGER mention_docs_ad AFTER DELETE ON docs BEGIN
          DELETE FROM mention_titles WHERE workspace_id = old.workspace_id AND kind = 'doc' AND record_id = old.id; END""",
        """CREATE TRIGGER mention_tasks_ai AFTER INSERT ON tasks BEGIN
          INSERT INTO mention_titles(workspace_id, kind, record_id, title) VALUES(new.workspace_id, 'task', new.id, new.title); END""",
        """CREATE TRIGGER mention_tasks_au AFTER UPDATE OF title ON tasks BEGIN
          DELETE FROM mention_titles WHERE workspace_id = old.workspace_id AND kind = 'task' AND record_id = old.id;
          INSERT INTO mention_titles(workspace_id, kind, record_id, title) VALUES(new.workspace_id, 'task', new.id, new.title); END""",
        """CREATE TRIGGER mention_tasks_ad AFTER DELETE ON tasks BEGIN
          DELETE FROM mention_titles WHERE workspace_id = old.workspace_id AND kind = 'task' AND record_id = old.id; END""",
        // Records stored before the index existed.
        "INSERT INTO mention_titles(workspace_id, kind, record_id, title) SELECT workspace_id, 'doc', id, title FROM docs",
        "INSERT INTO mention_titles(workspace_id, kind, record_id, title) SELECT workspace_id, 'task', id, title FROM tasks",
    )

    /** `kind` is `doc` or `task`; returns `(id, title)`, newest first among matches, at most [limit]. */
    fun find(db: SqlConnection, workspaceId: String, kind: String, needle: String, limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val table = if (kind == "doc") "docs" else "tasks"
        val available = if (kind == "doc") "r.access_removed = 0 AND r.archived = 0" else "r.access_removed = 0"
        if (needle.codePointCount(0, needle.length) < 3) return db.query(
            """SELECT r.id, r.title FROM $table r WHERE r.workspace_id = ?1 AND $available AND (?2 = '' OR instr(lower(r.title), ?2) > 0)
               ORDER BY r.updated_at DESC, r.id LIMIT ?3""",
            workspaceId, needle, limit,
        ) { it.text(0)!! to it.text(1)!! }
        // A quoted phrase is matched as a substring by the trigram tokenizer; quotes in the input are doubled.
        val phrase = "\"" + needle.replace("\"", "\"\"") + "\""
        return db.query(
            """SELECT r.id, r.title FROM mention_titles i JOIN $table r ON r.workspace_id = i.workspace_id AND r.id = i.record_id
               WHERE mention_titles MATCH ?1 AND i.workspace_id = ?2 AND i.kind = ?3 AND $available
               ORDER BY r.updated_at DESC, r.id LIMIT ?4""",
            "title : $phrase", workspaceId, kind, limit,
        ) { it.text(0)!! to it.text(1)!! }
    }
}
