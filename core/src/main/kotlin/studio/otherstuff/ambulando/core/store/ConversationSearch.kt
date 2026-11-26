package studio.otherstuff.ambulando.core.store

/** A deliberately small search language. Only generated, quoted FTS expressions reach SQLite. */
object ConversationQuery {
    enum class Field { ANY, TITLE, BODY }
    data class Term(val field: Field, val words: List<String>, val phrase: Boolean) {
        val match: String get() = (when (this.field) { Field.TITLE -> "title : "; Field.BODY -> "body : "; else -> "" }) +
            if (phrase) "\"${words.joinToString(" ")}\"" else words.joinToString(" AND ") { "\"$it\"" }
    }
    data class Parsed(val terms: List<Term>, val error: String? = null)
    fun parse(input: String): Parsed {
        if (input.length > 2048) return Parsed(emptyList(), "Search is too long (maximum 2048 characters).")
        val text = input.replace('“', '"').replace('”', '"')
        val terms = mutableListOf<Term>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            var field = Field.ANY
            for ((prefix, value) in listOf("title:" to Field.TITLE, "body:" to Field.BODY)) {
                if (text.regionMatches(i, prefix, 0, prefix.length, ignoreCase = true)) { field = value; i += prefix.length; break }
            }
            while (i < text.length && text[i].isWhitespace()) i++
            if (i == text.length) return Parsed(emptyList(), "Add a word or quoted phrase after the field name.")
            if (field == Field.ANY && text.substring(i).takeWhile { !it.isWhitespace() && it != '"' }.contains(':')) return Parsed(emptyList(), "Supported fields are title: and body:.")
            val phrase = text[i] == '"'
            val start = if (phrase) ++i else i
            if (phrase) {
                while (i < text.length && text[i] != '"') i++
                if (i == text.length) return Parsed(emptyList(), "Close the quoted phrase with a quotation mark.")
            } else {
                while (i < text.length && !text[i].isWhitespace() && text[i] != '"') i++
                if (i < text.length && text[i] == '"') return Parsed(emptyList(), "Separate search clauses with a space.")
            }
            val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*").findAll(text.substring(start, i)).map { it.value }.toList()
            if (words.isEmpty()) return Parsed(emptyList(), "Enter a word or a phrase to search.")
            if (phrase) {
                terms += Term(field, words, true); i++
                if (i < text.length && !text[i].isWhitespace()) return Parsed(emptyList(), "Separate search clauses with a space.")
            }
            else words.forEach { terms += Term(field, listOf(it), false) }
            if (terms.size > 32) return Parsed(emptyList(), "Use at most 32 search terms.")
        }
        return Parsed(terms)
    }
}

enum class ConversationFilter { ALL, UNREAD, MINE }
data class ConversationRow(
    val id: String, val threadId: String?, val channelId: String, val title: String,
    val participants: List<String>, val replyCount: Int, val excerpt: String, val latestAt: String,
    val unread: Boolean, val mine: Boolean, val hitMessageId: String? = null,
    val participantNpubs: List<String> = emptyList(),
)

/** Each term may match a different message; a phrase must match one indexed title or message. */
suspend fun StoreReader.conversations(
    workspaceId: String, channelIds: Set<String>, viewer: String, query: String = "",
    filter: ConversationFilter = ConversationFilter.ALL,
): List<ConversationRow> = read {
    val revision = revisions.value(ConversationInvalidation.rows)
    val cacheKey = ConversationCache.Key(workspaceId, channelIds.toSet(), viewer, query, filter)
    conversationCache.get(cacheKey, revision)?.let { return@read it }
    val parsed = ConversationQuery.parse(query)
    if (first("SELECT 1 FROM workspaces WHERE workspace_id = ? AND access_removed = 1", workspaceId) { true } == true) return@read emptyList()
    if (parsed.error != null || channelIds.isEmpty()) return@read emptyList()
    var matches: Set<String>? = null
    val hits = linkedMapOf<String, Pair<String?, String>>()
    for (term in parsed.terms) {
        val found = query(
            """SELECT coalesce(em.thread_id, m.thread_id, m.id, s.record_id), m.id,
                 snippet(search_index, CASE WHEN s.kind = 'thread' THEN 3 ELSE 4 END, '⟦', '⟧', '…', 28)
               FROM search_index s LEFT JOIN messages m ON s.kind = 'message' AND m.workspace_id = s.workspace_id AND m.id = s.record_id
               LEFT JOIN (SELECT workspace_id, coalesce(thread_id,id) AS thread_id, id AS message_id FROM messages
                 UNION SELECT workspace_id,thread_id,message_id FROM thread_message_members) em ON em.workspace_id = m.workspace_id AND em.message_id = m.id
               LEFT JOIN threads t ON t.workspace_id = s.workspace_id AND t.id = CASE WHEN s.kind = 'thread' THEN s.record_id ELSE em.thread_id END
               JOIN channels c ON c.workspace_id = s.workspace_id AND c.id = CASE WHEN s.kind = 'thread' THEN t.channel_id ELSE m.channel_id END
               WHERE search_index MATCH ? AND s.workspace_id = ? AND s.kind IN ('thread','message')
                 AND (s.kind = 'thread' OR (m.deleted = 0 AND m.access_removed = 0))
                 AND coalesce(t.deleted,0) = 0 AND coalesce(t.access_removed,0) = 0 AND c.access_removed = 0
                 AND c.id IN (${channelIds.joinToString(",") { "?" }}) AND (t.id IS NULL OR t.channel_id = c.id)
                 AND NOT EXISTS(SELECT 1 FROM scopes a WHERE a.workspace_id = c.workspace_id AND a.id = c.scope_id AND a.access_removed = 1)""",
            term.match, workspaceId, *channelIds.toTypedArray(),
        ) { Triple(it.text(0)!!, it.text(1), it.text(2).orEmpty()) }
        val ids = found.map { it.first }.toSet()
        matches = matches?.intersect(ids) ?: ids
        for ((id, message, excerpt) in found) if (id !in hits || message != null) hits[id] = message to excerpt
        if (matches.isEmpty()) return@read emptyList()
    }
    val states = query("SELECT resource_id FROM view_states WHERE workspace_id = ? AND resource_type = 'thread' AND (activity_version > viewed_activity_version OR attention_activity_version > viewed_activity_version)", workspaceId) { it.text(0)!! }.toSet()
    // Intersect indexed hits before hydrating canonical records; a narrow search must not decode every cached body.
    val boundedCandidates = matches?.takeIf { it.size <= 5000 }
    val candidateSql = boundedCandidates?.let { "AND m.effective_id IN (${it.joinToString(",") { "?" }})" }.orEmpty()
    val candidateBindings = boundedCandidates?.toTypedArray() ?: emptyArray<String>()
    val recordCandidateSql = boundedCandidates?.let { "AND id IN (${it.joinToString(",") { "?" }})" }.orEmpty()
    // Only indexed scalar columns are needed for inbox summaries. Canonical JSON is retained untouched.
    data class ThreadSummary(val id: String, val channelId: String, val title: String?, val latest: String?, val updatedAt: String, val creator: String?)
    val records = query("""SELECT id,channel_id,title,latest,updated_at,json_extract(raw,'$.created_by_actor_npub') FROM threads
        WHERE workspace_id = ? AND channel_id IN (${channelIds.joinToString(",") { "?" }}) AND deleted = 0 AND access_removed = 0 $recordCandidateSql""",
        workspaceId, *channelIds.toTypedArray(), *candidateBindings) {
        ThreadSummary(it.text(0)!!, it.text(1)!!, it.text(2), it.text(3), it.text(4)!!, it.text(5))
    }.associateBy { it.id }
    val rows = query(
        """WITH membership AS (
            SELECT workspace_id,coalesce(thread_id,id) AS effective_id,id,channel_id,thread_id,sender_npub,sender_label,created_at,deleted,access_removed FROM messages
            UNION ALL SELECT own.workspace_id,p.thread_id,own.id,own.channel_id,own.thread_id,own.sender_npub,own.sender_label,own.created_at,own.deleted,own.access_removed
              FROM thread_message_members p JOIN messages own ON own.workspace_id = p.workspace_id AND own.id = p.message_id
              WHERE p.thread_id != coalesce(own.thread_id,own.id))
           , eligible AS (SELECT m.id,m.channel_id,m.thread_id,m.sender_npub,m.sender_label,m.created_at,m.effective_id
          FROM membership m
          JOIN channels c ON c.workspace_id = m.workspace_id AND c.id = m.channel_id
          LEFT JOIN threads t ON t.workspace_id = m.workspace_id AND t.id = m.effective_id
          WHERE m.workspace_id = ? AND c.id IN (${channelIds.joinToString(",") { "?" }}) AND (t.id IS NULL OR t.channel_id = c.id) AND m.deleted = 0 AND m.access_removed = 0 AND c.access_removed = 0
            AND coalesce(t.deleted,0) = 0 AND coalesce(t.access_removed,0) = 0
            AND NOT EXISTS(SELECT 1 FROM scopes s WHERE s.workspace_id = c.workspace_id AND s.id = c.scope_id AND s.access_removed = 1)
          $candidateSql
          ), summary AS (SELECT effective_id, min(channel_id) AS channel_id, max(thread_id IS NOT NULL) AS threaded, count(*) AS n,
             json_extract(json_group_array(id ORDER BY created_at,id),'$[0]') AS first_id,
             json_extract(json_group_array(id ORDER BY created_at,id),'$[#-1]') AS last_id, max(created_at) AS latest_at,
             json_group_array(DISTINCT coalesce(sender_label,sender_npub) ORDER BY created_at,id) AS labels,
             json_group_array(DISTINCT sender_npub ORDER BY created_at,id) AS npubs,
             max(sender_npub = ?) AS mine, min(id) AS standalone_id
          FROM eligible GROUP BY effective_id)
          SELECT effective_id,channel_id,threaded,n,
             (SELECT body FROM messages WHERE workspace_id = ?1 AND id = summary.first_id),
             (SELECT body FROM messages WHERE workspace_id = ?1 AND id = summary.last_id),
             latest_at,labels,npubs,mine,standalone_id FROM summary""", workspaceId, *channelIds.toTypedArray(), *candidateBindings, viewer,
    ) { r ->
        fun strings(column: Int) = (kotlinx.serialization.json.Json.parseToJsonElement(r.text(column)!!) as kotlinx.serialization.json.JsonArray)
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content }
        val id = r.text(0)!!
        val record = records[id]
        val creator = record?.creator
        val mine = creator == viewer || r.bool(9)
        val unread = id in states
        val hit = hits[id]
        ConversationRow(id, id.takeIf { record != null || r.bool(2) || it != r.text(10) }, r.text(1)!!,
            record?.title?.takeIf { it.isNotBlank() } ?: r.text(4).orEmpty().lineSequence().firstOrNull().orEmpty(),
            strings(7), (r.int(3)-1).coerceAtLeast(0), hit?.second ?: r.text(5).orEmpty(), r.text(6)!!, unread, mine, hit?.first, strings(8))
    }
    val populated = rows.filter { row -> (matches == null || row.id in matches) &&
        (filter != ConversationFilter.MINE || row.mine) && (filter != ConversationFilter.UNREAD || row.unread) &&
        (parsed.terms.isEmpty() || hits[row.id] != null) }
    val populatedIds = populated.map { it.id }.toSet()
    val emptyThreads = query("""SELECT t.id FROM threads t JOIN channels c ON c.workspace_id = t.workspace_id AND c.id = t.channel_id
        WHERE t.workspace_id = ? AND c.id IN (${channelIds.joinToString(",") { "?" }}) AND t.deleted = 0 AND t.access_removed = 0 AND c.access_removed = 0
        AND NOT EXISTS(SELECT 1 FROM scopes s WHERE s.workspace_id = c.workspace_id AND s.id = c.scope_id AND s.access_removed = 1)
        AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.workspace_id = t.workspace_id AND m.thread_id = t.id AND m.deleted = 0 AND m.access_removed = 0)
        AND NOT EXISTS(SELECT 1 FROM thread_message_members p JOIN messages m ON m.workspace_id = p.workspace_id AND m.id = p.message_id
            WHERE p.workspace_id = t.workspace_id AND p.thread_id = t.id AND m.deleted = 0 AND m.access_removed = 0)""", workspaceId, *channelIds.toTypedArray()) { it.text(0)!! }.mapNotNull { id ->
        if (id in populatedIds || matches != null && id !in matches) return@mapNotNull null
        val t = records[id] ?: return@mapNotNull null
        if (t.channelId !in channelIds) return@mapNotNull null
        val mine = t.creator == viewer
        if (filter == ConversationFilter.MINE && !mine || filter == ConversationFilter.UNREAD && id !in states) return@mapNotNull null
        ConversationRow(id, id, t.channelId, t.title.orEmpty(), emptyList(), 0, hits[id]?.second ?: "Messages have not been downloaded.", t.latest?.takeIf { StoreTime.parse(it) != null } ?: t.updatedAt, id in states, mine)
    }
    (populated + emptyThreads).sortedWith(compareByDescending<ConversationRow> { it.latestAt }.thenBy { it.id }).also {
        conversationCache.put(cacheKey, revision, it)
    }
}

/** Participants from accessible cached history; records and identities stay canonical. */
suspend fun StoreReader.conversationParticipants(workspaceId: String, threadId: String): List<String> = read {
    query("SELECT DISTINCT coalesce(sender_label,sender_npub) FROM messages WHERE workspace_id = ? AND (thread_id = ? OR id IN (SELECT message_id FROM thread_message_members WHERE workspace_id = ? AND thread_id = ?)) AND deleted = 0 AND access_removed = 0", workspaceId, threadId, workspaceId, threadId) { it.text(0) }.filterNotNull()
}

suspend fun StoreReader.channelConversationThreadIds(workspaceId: String, channelId: String): Set<String> = read {
    query("""SELECT id FROM threads WHERE workspace_id = ? AND channel_id = ? AND deleted = 0 AND access_removed = 0
        UNION SELECT thread_id FROM messages WHERE workspace_id = ? AND channel_id = ? AND thread_id IS NOT NULL AND deleted = 0 AND access_removed = 0""", workspaceId, channelId, workspaceId, channelId) { it.text(0)!! }.toSet()
}

suspend fun StoreReader.cachedConversationMessageCount(workspaceId: String, channelIds: Set<String>): Long = read {
    if (channelIds.isEmpty()) 0L else longValue("SELECT count(*) FROM messages WHERE workspace_id = ? AND channel_id IN (${channelIds.joinToString(",") { "?" }}) AND deleted = 0 AND access_removed = 0", workspaceId, *channelIds.toTypedArray()) ?: 0L
}

/** Table dependencies for inbox observers; unrelated activity and draft writes cannot rebuild cached history. */
object ConversationInvalidation {
    val rows = setOf(StoreTable.WORKSPACES, StoreTable.SCOPES, StoreTable.CHANNELS, StoreTable.THREADS,
        StoreTable.MESSAGES, StoreTable.VIEW_STATES, StoreTable.ACCESS)
    val coverage = setOf(StoreTable.MESSAGES, StoreTable.APP_STATE, StoreTable.ACCESS)
}

/** Reader-confined, bounded to recent navigation/search contexts; no data crosses a store boundary.
 * The revision is captured before the WAL snapshot is read, so a concurrent change forces the next load to refresh. */
internal class ConversationCache {
    data class Key(val workspace: String, val channels: Set<String>, val viewer: String, val query: String, val filter: ConversationFilter)
    private val entries = object : LinkedHashMap<Key, Pair<Long,List<ConversationRow>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,Pair<Long,List<ConversationRow>>>?): Boolean = size > 8
    }
    fun get(key: Key, revision: Long): List<ConversationRow>? = entries[key]?.takeIf { it.first == revision }?.second
    fun put(key: Key, revision: Long, rows: List<ConversationRow>) { entries[key] = revision to rows }
}
