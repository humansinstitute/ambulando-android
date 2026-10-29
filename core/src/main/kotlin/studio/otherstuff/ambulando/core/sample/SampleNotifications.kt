package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.strings

/**
 * In-process stand-in for Tower's notification inbox routes (`notifications-v1`), for tests and the clearly labelled
 * sample-data mode. It is **not** Tower and is never evidence of live behaviour. It follows the contract rules the client
 * depends on: per-recipient `change_seq`, `row_version` compare-and-set with `409 state_conflict`, `mutation_id`
 * receipts (an identical retry replays the original reply, a changed request is `409 mutation_id_reused`),
 * `up_to_seq`-bounded bulk writes, and `410 reset_required` for a cursor ahead of the head or a stale `rules_revision`.
 *
 * Wire v1.1: `client_app_id` filters items, counts, changes and bulk to what that app may show (audience and the effective
 * inbox rule, one shared predicate); read state stays shared. Rules are written by per-key compare-and-set (all or nothing,
 * `409 rules_conflict` with every current rule) and every write bumps `rules_revision`.
 */
class SampleNotifications(private val workspaceId: String, private val auth: SampleAuth) {
    private val lock = Any()
    private val items = LinkedHashMap<String, MutableMap<String, Any?>>()
    private val receipts = HashMap<String, Pair<String, Reply>>()
    private var seq = 0L
    private val rules = LinkedHashMap<String, Rule>()
    private var rulesRevision = 1L
    private class Rule(val source: String, val category: String, val client: String, val channel: String, var mode: String, var preview: String?, var version: Long) {
        val key get() = "$source|$category|$client|$channel"
        fun json(): Map<String, Any?> = linkedMapOf("source_app_id" to source, "category" to category, "client_app_id" to client, "channel" to channel, "mode" to mode, "preview" to preview, "row_version" to version)
    }
    private val startedAt = "2026-10-26T09:00:00Z"
    @Volatile var offlineReads = false

    init {
        add("mention", "Ana mentioned you", "Welcome thread", mapOf("kind" to "thread", "channel_id" to SampleIds.GENERAL, "thread_id" to SampleIds.thread(1)), "2026-10-26T09:30:00Z")
        add("task_assignment", "Ana assigned you a task", "Review the sample canvas", mapOf("kind" to "task", "task_id" to SampleIds.CANVAS_TASK), "2026-10-26T09:40:00Z")
        add("chat_thread", "New activity in a thread", "Design review", mapOf("kind" to "thread", "channel_id" to SampleIds.DESIGN, "thread_id" to SampleIds.thread(2)), "2026-10-26T09:50:00Z")
    }

    private fun add(category: String, title: String, body: String, target: Map<String, Any?>, at: String, audience: List<String> = listOf("*")) = synchronized(lock) {
        seq += 1
        val id = String.format("5a00ffff-0000-4000-8000-%012d", seq)
        items[id] = linkedMapOf(
            "schema" to "org.wingman.notification.item", "version" to 1, "id" to id, "workspace_id" to workspaceId, "recipient_actor_id" to SampleIds.ACTOR,
            "change_seq" to seq, "row_version" to 1L,
            "source" to mapOf("app_id" to "flightdeck.core", "label" to "Flight Deck (sample)", "kind" to "tower_rule"),
            "category" to category, "reasons" to listOf(category), "severity" to "normal", "status" to "active", "origin" to "live",
            "group" to mapOf("key" to "$category:$id", "event_count" to 1), "actor" to mapOf("actor_id" to null, "display_name" to "Ana", "kind" to "human"),
            "subject" to mapOf("kind" to (target["kind"] ?: "thread")),
            "presentation" to mapOf("title" to title, "body" to body, "icon" to category, "push_preview" to "generic"),
            "action" to mapOf("type" to "open", "target" to target), "audience" to mapOf("client_apps" to audience),
            "occurred_at" to at, "updated_at" to at, "expires_at" to null,
            "state" to mapOf("read" to false, "read_at" to null, "read_reason" to null, "dismissed" to false, "archived" to false), "access" to "ok",
        )
    }

    private fun flags(item: Map<String, Any?>): Map<String, Any?> = (item["state"] as Map<*, *>).mapKeys { it.key.toString() }
    private fun flag(item: Map<String, Any?>, name: String): Boolean = flags(item)[name] == true

    private fun page(rows: List<Map<String, Any?>>): List<Map<String, Any?>> = rows.sortedWith(compareByDescending<Map<String, Any?>> { it["occurred_at"].toString() }.thenByDescending { it["id"].toString() })

    private fun inbox(item: Map<String, Any?>) = !flag(item, "dismissed") && !flag(item, "archived")
    private fun unread(item: Map<String, Any?>) = inbox(item) && item["status"] == "active" && !flag(item, "read")

    /** Effective inbox rule for (source, category, client): level (src,cat) > (src,*) > (*,cat) > (*,*); client-specific over `*` at a level; `off` wins ties. */
    private fun inboxOff(item: Map<String, Any?>, client: String): Boolean {
        val source = ((item["source"] as Map<*, *>)["app_id"]).toString()
        val category = item["category"].toString()
        val best = rules.values.filter { r ->
            r.channel == "inbox" && r.mode != "default" && (r.source == "*" || r.source == source) && (r.category == "*" || r.category == category) && (r.client == "*" || r.client == client)
        }.maxWithOrNull(compareBy<Rule> { (if (it.source != "*") 2 else 0) + (if (it.category != "*") 1 else 0) }.thenBy { if (it.client == client) 1 else 0 }.thenBy { if (it.mode == "off") 1 else 0 })
        return best?.mode == "off"
    }

    /** Visible to [client] iff the audience names it (or `*`) and its effective inbox rule is not off. Null client: no app filtering. */
    private fun visible(item: Map<String, Any?>, client: String?): Boolean {
        if (client == null) return true
        val audience = ((item["audience"] as Map<*, *>)["client_apps"] as List<*>).map { it.toString() }
        return ("*" in audience || client in audience) && !inboxOff(item, client)
    }

    fun handle(request: HttpRequest): Reply? {
        val url = SampleURL.of(request) ?: return null
        val base = "/api/v4/flightdeck-pg/workspaces/$workspaceId/notifications"
        if (!url.path.startsWith("$base/") && url.path != base) return null
        when (val result = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(result.reason)
            is SampleAuth.Result.Valid -> Unit
        }
        if (offlineReads && request.method.uppercase() == "GET") throw SampleOfflineException()
        val parts = url.path.removePrefix(base).split('/').filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        val body = request.body?.let { parseJsonObjectOrNull(it) }
        return synchronized(lock) {
            when {
                method == "GET" && parts == listOf("items") -> list(url)
                method == "GET" && parts.size == 2 && parts[0] == "items" -> items[parts[1]]?.let { 200 to mapOf("item" to it) } ?: errorReply(404, "notification_not_found")
                method == "GET" && parts == listOf("changes") -> changes(url)
                method == "GET" && parts == listOf("counts") -> counts(url)
                method == "PATCH" && parts.size == 2 && parts[0] == "items" -> patch(parts[1], body, request)
                method == "POST" && parts == listOf("bulk") -> bulk(body, request)
                method == "GET" && parts == listOf("categories") -> 200 to mapOf("sources" to listOf(mapOf("app_id" to "flightdeck.core", "label" to "Flight Deck (sample)", "kind" to "tower_rule",
                    "categories" to listOf("mention", "task_assignment", "chat_thread").map { mapOf("category" to it, "label" to it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }, "default_severity" to "normal", "default_inbox" to "on", "default_alert" to "on", "mandatory" to false, "grouping" to "subject") })))
                method == "GET" && parts == listOf("rules") -> ruleSet()
                method == "PUT" && parts == listOf("rules") -> putRules(body)
                else -> errorReply(404, "not_found")
            }
        }
    }

    private fun list(url: SampleURL): Reply {
        val view = url.query("view") ?: "inbox"
        val category = url.query("category")
        val client = url.query("client_app_id")
        val rows = page(items.values.filter { item ->
            visible(item, client) && (category == null || item["category"] == category) && when (view) {
                "unread" -> unread(item); "archived" -> flag(item, "archived") && !flag(item, "dismissed"); "dismissed" -> flag(item, "dismissed"); "all" -> true; else -> inbox(item)
            }
        })
        val limit = (url.query("limit")?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val start = url.query("cursor")?.removePrefix("idx:")?.toIntOrNull() ?: 0
        val slice = rows.drop(start).take(limit)
        val next = if (start + slice.size < rows.size) "idx:${start + slice.size}" else null
        return 200 to mapOf("items" to slice, "next_cursor" to next, "head_seq" to seq, "counts" to mapOf("unread_total" to items.values.count { unread(it) && visible(it, client) }, "capped_at" to 999),
            "inbox_started_at" to startedAt, "rules_revision" to rulesRevision)
    }

    private fun changes(url: SampleURL): Reply {
        val after = url.query("after_seq")?.toLongOrNull() ?: return validationError("after_seq is required")
        if (after > seq) return 410 to mapOf("code" to "reset_required", "reason" to "ahead", "rules_revision" to rulesRevision)
        val client = url.query("client_app_id")
        val known = url.query("rules_revision")?.toLongOrNull()
        if (known != null && known != rulesRevision) return 410 to mapOf("code" to "reset_required", "reason" to "rules_changed", "rules_revision" to rulesRevision)
        val limit = (url.query("limit")?.toIntOrNull() ?: 200).coerceIn(1, 200)
        // Items not visible to the app are omitted (a page may filter everything); the cursor still advances.
        val all = items.values.filter { (it["change_seq"] as Long) > after && visible(it, client) }.sortedBy { it["change_seq"] as Long }
        val slice = all.take(limit)
        val hasMore = slice.size < all.size
        val through = if (hasMore) slice.last()["change_seq"] as Long else seq
        return 200 to mapOf("changes" to slice, "through_seq" to through, "head_seq" to seq, "has_more" to hasMore, "rules_revision" to rulesRevision)
    }

    private fun counts(url: SampleURL): Reply {
        val client = url.query("client_app_id")
        val unread = items.values.filter { unread(it) && visible(it, client) }
        return 200 to mapOf("unread_total" to unread.size, "by_category" to unread.groupingBy { it["category"].toString() }.eachCount(),
            "by_source_app" to mapOf("flightdeck.core" to unread.size), "badge" to unread.size, "capped_at" to 999, "head_seq" to seq, "inbox_started_at" to startedAt,
            "rules_revision" to rulesRevision)
    }

    private fun ruleSet(): Reply = 200 to mapOf("rules" to rules.values.map { it.json() }, "rules_revision" to rulesRevision)

    /** Per-key compare-and-set, all or nothing. `default` keeps a durable inherited row (its version still rises, so a stale base cannot resurrect an override). One revision bump per write. */
    private fun putRules(body: kotlinx.serialization.json.JsonObject?): Reply {
        val mutation = body?.string("mutation_id") ?: return validationError("mutation_id is required")
        val list = body.objects("rules")?.takeIf { it.size in 1..50 } ?: return validationError("rules is required")
        val hash = body.toString()
        receipt("rules:$mutation", hash)?.let { return it }
        class Entry(val key: String, val rule: Rule?, val expected: Long, val mode: String, val preview: String?, val source: String, val category: String, val client: String, val channel: String)
        val entries = list.map { r ->
            val source = r.string("source_app_id") ?: "*"; val category = r.string("category") ?: "*"; val client = r.string("client_app_id") ?: "*"
            val channel = r.string("channel") ?: return validationError("channel is required")
            val mode = r.string("mode")?.takeIf { it in listOf("on", "off", "default") } ?: return validationError("mode is invalid")
            val expected = flexibleLong(r["expected_row_version"]) ?: return validationError("expected_row_version is required")
            val key = "$source|$category|$client|$channel"
            Entry(key, rules[key], expected, mode, r.string("preview"), source, category, client, channel)
        }
        val stale = entries.filter { (it.rule?.version ?: 0L) != it.expected }
        if (stale.isNotEmpty()) {
            return errorReply(409, "rules_conflict", mapOf(
                "conflicts" to stale.map { mapOf("source_app_id" to it.source, "category" to it.category, "client_app_id" to it.client, "channel" to it.channel,
                    "expected_row_version" to it.expected, "current" to it.rule?.json()) },
                "rules" to rules.values.map { it.json() }, "rules_revision" to rulesRevision))
        }
        for (e in entries) {
            when {
                e.rule != null -> { e.rule.mode = e.mode; e.rule.preview = e.preview; e.rule.version += 1 }
                else -> rules[e.key] = Rule(e.source, e.category, e.client, e.channel, e.mode, e.preview, 1)
            }
        }
        rulesRevision += 1
        val reply: Reply = 200 to mapOf("rules" to rules.values.map { it.json() }, "rules_revision" to rulesRevision)
        receipts["rules:$mutation"] = hash to reply
        return reply
    }

    private fun apply(item: MutableMap<String, Any?>, patch: Map<String, Any?>) {
        val state = LinkedHashMap(flags(item))
        (patch["read"] as? Boolean)?.let { state["read"] = it; state["read_reason"] = if (it) "user" else null }
        (patch["dismissed"] as? Boolean)?.let { state["dismissed"] = it; if (it) { state["read"] = true; if (state["read_reason"] == null) state["read_reason"] = "user" } }
        (patch["archived"] as? Boolean)?.let { state["archived"] = it; if (it) { state["read"] = true; if (state["read_reason"] == null) state["read_reason"] = "user" } }
        if (state != flags(item)) {
            item["state"] = state
            item["row_version"] = (item["row_version"] as Long) + 1
            seq += 1
            item["change_seq"] = seq
        }
    }

    private fun receipt(key: String, hash: String): Reply? = receipts[key]?.let { (h, reply) -> if (h == hash) reply else errorReply(409, "mutation_id_reused") }

    private fun patch(id: String, body: kotlinx.serialization.json.JsonObject?, request: HttpRequest): Reply {
        val item = items[id] ?: return errorReply(404, "notification_not_found")
        val mutation = body?.string("mutation_id") ?: return validationError("mutation_id is required")
        val expected = flexibleLong(body["expected_row_version"]) ?: return validationError("expected_row_version is required")
        val patchObject = body.obj("patch") ?: return validationError("patch is required")
        val key = "$id:$mutation"; val hash = body.toString()
        receipt(key, hash)?.let { return it }
        val observed = flexibleLong(body["observed_change_seq"])
        if (expected != item["row_version"] || (observed != null && observed < (item["change_seq"] as Long))) return errorReply(409, "state_conflict", mapOf("item" to item))
        apply(item, mapOf("read" to patchObject.bool("read"), "dismissed" to patchObject.bool("dismissed"), "archived" to patchObject.bool("archived")).filterValues { it != null })
        val reply: Reply = 200 to mapOf("item" to item.toMap())
        receipts[key] = hash to reply
        return reply
    }

    private fun bulk(body: kotlinx.serialization.json.JsonObject?, request: HttpRequest): Reply {
        val mutation = body?.string("mutation_id") ?: return validationError("mutation_id is required")
        val op = body.string("op") ?: return validationError("op is required")
        val selector = body.obj("selector") ?: return validationError("selector is required")
        val key = "bulk:$mutation"; val hash = body.toString()
        receipt(key, hash)?.let { return it }
        val patch = when (op) {
            "mark_read" -> mapOf("read" to true); "mark_unread" -> mapOf("read" to false); "dismiss" -> mapOf("dismissed" to true)
            "archive" -> mapOf("archived" to true); "unarchive" -> mapOf("archived" to false); else -> return validationError("unknown op")
        }
        val ids = selector.strings("ids")
        val upTo = flexibleLong(selector["up_to_seq"])
        val client = selector.string("client_app_id")
        val targets = items.values.filter { item ->
            visible(item, client) && if (ids != null) item["id"] in ids else upTo != null && (item["change_seq"] as Long) <= upTo
        }
        var affected = 0
        for (item in targets) { val before = item["row_version"]; apply(item, patch); if (item["row_version"] != before) affected += 1 }
        val reply: Reply = 200 to mapOf("affected" to affected, "through_seq" to seq, "mutation_id" to mutation)
        receipts[key] = hash to reply
        return reply
    }

    /** Test hook: new activity folds into an open item (row_version and change_seq advance, unread again). */
    fun fold(id: String) = synchronized(lock) {
        val item = items[id] ?: return@synchronized
        item["row_version"] = (item["row_version"] as Long) + 1; seq += 1; item["change_seq"] = seq
        item["state"] = flags(item) + mapOf("read" to false, "read_at" to null, "read_reason" to null)
        val group = LinkedHashMap((item["group"] as Map<*, *>).mapKeys { it.key.toString() }); group["event_count"] = (group["event_count"] as Number).toInt() + 1; item["group"] = group
    }

    /** Test hook: another producer raises an item for the given client apps (`*` for all). Returns its id. */
    fun raise(category: String, title: String, audience: List<String>, at: String = "2026-10-26T10:00:00Z"): String = synchronized(lock) {
        add(category, title, "Raised by a test", mapOf("kind" to "thread", "channel_id" to SampleIds.GENERAL, "thread_id" to SampleIds.thread(1)), at, audience)
        items.keys.last()
    }

    /** Test hook: the person changes a rule on another device (bumps `rules_revision`, creating or replacing the row). */
    fun setRule(source: String, category: String, client: String, mode: String) = synchronized(lock) {
        val existing = rules["$source|$category|$client|inbox"]
        if (existing != null) { existing.mode = mode; existing.version += 1 } else rules["$source|$category|$client|inbox"] = Rule(source, category, client, "inbox", mode, null, 1)
        rulesRevision += 1
    }

    fun currentRulesRevision(): Long = synchronized(lock) { rulesRevision }

    /** Test hook: the item disappears for this recipient (content-free tombstone in `changes`). */
    fun ids(): List<String> = synchronized(lock) { items.keys.toList() }
}
