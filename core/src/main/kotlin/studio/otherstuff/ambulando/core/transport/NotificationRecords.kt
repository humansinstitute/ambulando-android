package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonObject

// Tower's per-recipient notification inbox: `org.wingman.notification.item` v1 and the pages around it. Parsing is
// tolerant by design: an unknown schema version, category, target kind or extra field never throws, it degrades to a
// generic card. Only an item without an id, workspace, change sequence or row version is refused (it cannot be keyed).
//
// Items carry no capability: the client opens the record through the normal record routes with its own credentials.

/** What a notification points at. [kind] is Tower's registry name; unknown kinds stay readable (generic detail card). */
data class NotificationTarget(
    val kind: String,
    val channelId: String? = null,
    val threadId: String? = null,
    val messageId: String? = null,
    val taskId: String? = null,
    val docId: String? = null,
    val fileId: String? = null,
    val scopeId: String? = null,
    val appId: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): NotificationTarget? {
            val kind = o.string("kind")?.takeIf { it.isNotEmpty() } ?: return null
            fun id(vararg keys: String): String? = keys.firstNotNullOfOrNull { lowerUUID(o.string(it)) }
            return NotificationTarget(
                kind, channelId = id("channel_id"), threadId = id("thread_id"), messageId = id("message_id"),
                taskId = id("task_id") ?: if (kind == "task") id("id", "subject_id") else null,
                docId = id("doc_id") ?: if (kind == "doc") id("id", "subject_id") else null,
                fileId = id("file_id"), scopeId = id("scope_id"), appId = o.string("app_id"),
            )
        }
    }
}

/** The per-recipient flags a person changes. Server truth; the store keeps an optimistic overlay beside it. */
data class NotificationState(val read: Boolean, val readReason: String?, val dismissed: Boolean, val archived: Boolean)

data class NotificationItem(
    val id: String,
    val workspaceId: String,
    val changeSeq: Long,
    val rowVersion: Long,
    val sourceApp: String,
    val sourceLabel: String?,
    val category: String,
    val reasons: List<String>,
    val severity: String,
    /** `active`, `resolved`, `withdrawn`, `expired`, `revoked` (anything else is kept as sent). */
    val status: String,
    val origin: String,
    val groupKey: String?,
    val eventCount: Int,
    val actorName: String?,
    val actorKind: String?,
    val title: String,
    val body: String,
    val target: NotificationTarget?,
    val occurredAt: String,
    val updatedAt: String,
    val expiresAt: String?,
    val state: NotificationState,
    /** False when Tower says the recipient can no longer read the subject; text and target are already redacted. */
    val accessible: Boolean,
    /** Highest activity version of the subject this item covers (derived-read bookkeeping; informational). */
    val subjectActivityVersion: Long?,
    val raw: JsonObject,
) {
    val unread: Boolean get() = status == "active" && accessible && !state.read && !state.dismissed && !state.archived

    companion object {
        const val SCHEMA = "org.wingman.notification.item"
        const val UNAVAILABLE_TITLE = "Item no longer available"

        fun parse(o: JsonObject): NotificationItem {
            val id = lowerUUID(o.string("id")) ?: throw TowerError.Malformed("notification item")
            val workspace = lowerUUID(o.string("workspace_id")) ?: throw TowerError.Malformed("notification item")
            val seq = flexibleLong(o["change_seq"]) ?: throw TowerError.Malformed("notification item")
            val version = flexibleLong(o["row_version"]) ?: throw TowerError.Malformed("notification item")
            val source = o.obj("source")
            val group = o.obj("group")
            val actor = o.obj("actor")
            val presentation = o.obj("presentation")
            val state = o.obj("state")
            val accessible = o.string("access")?.let { it == "ok" } ?: true
            val subject = o.obj("subject")
            // Redaction is enforced here as well as by Tower: an unavailable item never shows text or opens anything.
            val title = if (accessible) presentation?.string("title").orEmpty() else UNAVAILABLE_TITLE
            return NotificationItem(
                id = id, workspaceId = workspace, changeSeq = seq, rowVersion = version,
                sourceApp = source?.string("app_id") ?: "",
                sourceLabel = source?.string("label"),
                category = o.string("category") ?: "unknown",
                reasons = o.strings("reasons") ?: emptyList(),
                severity = o.string("severity") ?: "normal",
                status = o.string("status") ?: "active",
                origin = o.string("origin") ?: "live",
                groupKey = group?.string("key"),
                eventCount = group?.let { flexibleInt(it["event_count"]) } ?: 1,
                actorName = actor?.string("display_name"),
                actorKind = actor?.string("kind"),
                title = title,
                body = if (accessible) presentation?.string("body").orEmpty() else "",
                target = if (accessible) o.obj("action")?.obj("target")?.let { NotificationTarget.parse(it) } else null,
                occurredAt = o.string("occurred_at") ?: "",
                updatedAt = o.string("updated_at") ?: o.string("occurred_at") ?: "",
                expiresAt = o.string("expires_at"),
                state = NotificationState(
                    read = state?.bool("read") ?: false, readReason = state?.string("read_reason"),
                    dismissed = state?.bool("dismissed") ?: false, archived = state?.bool("archived") ?: false,
                ),
                accessible = accessible,
                subjectActivityVersion = subject?.let { flexibleLong(it["activity_version"]) },
                raw = o,
            )
        }
    }
}

/** One entry of `changes`: the item as it now is, or a content-free removal (withdrawn, access lost, retention). */
sealed class NotificationChange {
    abstract val changeSeq: Long
    data class Upsert(val item: NotificationItem) : NotificationChange() { override val changeSeq: Long get() = item.changeSeq }
    data class Removed(val id: String, override val changeSeq: Long, val reason: String?) : NotificationChange()

    companion object {
        const val TOMBSTONE_SCHEMA = "org.wingman.notification.tombstone"

        fun parse(o: JsonObject): NotificationChange {
            // Contract: `org.wingman.notification.tombstone` v1, `change_type: "remove"`, content-free.
            val removed = o.string("schema") == TOMBSTONE_SCHEMA || o.string("change_type") == "remove" || o.string("removal_reason") != null ||
                o.string("removed_at") != null
            if (removed) {
                val id = lowerUUID(o.string("id") ?: o.string("item_id")) ?: throw TowerError.Malformed("notification change")
                val seq = flexibleLong(o["change_seq"]) ?: throw TowerError.Malformed("notification change")
                return Removed(id, seq, o.string("removal_reason"))
            }
            return Upsert(NotificationItem.parse(o.obj("item") ?: o))
        }
    }
}

data class NotificationItemsPage(
    val items: List<NotificationItem>, val nextCursor: String?, val headSeq: Long, val unreadTotal: Int?,
    /** When this recipient's inbox began (it starts empty at activation; older activity is never imported). */
    val inboxStartedAt: String? = null,
    /** The per-recipient rules revision this page was computed under (wire v1.1); null from a Tower that does not send it. */
    val rulesRevision: Long? = null,
)

data class NotificationChangesPage(
    val changes: List<NotificationChange>, val throughSeq: Long, val headSeq: Long, val hasMore: Boolean, val rulesRevision: Long? = null,
)

data class NotificationCounts(
    val unreadTotal: Int,
    val byCategory: Map<String, Int>,
    val bySourceApp: Map<String, Int>,
    val badge: Int?,
    val cappedAt: Int?,
    val headSeq: Long,
    val inboxStartedAt: String? = null,
    val rulesRevision: Long? = null,
) {
    companion object {
        fun parse(o: JsonObject): NotificationCounts {
            fun ints(key: String): Map<String, Int> = o.obj(key)?.mapNotNull { (k, v) -> flexibleInt(v)?.let { k to it } }?.toMap() ?: emptyMap()
            return NotificationCounts(
                unreadTotal = flexibleInt(o["unread_total"]) ?: throw TowerError.Malformed("notification counts"),
                byCategory = ints("by_category"), bySourceApp = ints("by_source_app"),
                badge = flexibleInt(o["badge"]), cappedAt = flexibleInt(o["capped_at"]),
                headSeq = flexibleLong(o["head_seq"]) ?: 0, inboxStartedAt = o.string("inbox_started_at"),
                rulesRevision = flexibleLong(o["rules_revision"]),
            )
        }
    }
}

/** Outcome of a state write. Conflicts carry Tower's current item when it sent one. */
sealed class NotificationWriteResult {
    data class Applied(val item: NotificationItem) : NotificationWriteResult()
    /** `state_conflict`: the item changed (folded activity or another device). The caller adopts [current]; it must not re-ack. */
    data class StateConflict(val current: NotificationItem?) : NotificationWriteResult()
    /**
     * `mutation_id_reused`: the same id arrived with a different request. An identical retry is acknowledged with the
     * original reply (a 200), so this is a rejection, never a success.
     */
    data object MutationReused : NotificationWriteResult()
    data class BulkApplied(val affected: Int, val throughSeq: Long?) : NotificationWriteResult()
    /** Any other status: the caller classifies it (retry, permanent, access). */
    data class Failed(val status: Int, val code: String?) : NotificationWriteResult()
}

/** One category a source can raise, as Tower's registry describes it (labels come from the registry, never a payload). */
data class NotificationCategoryInfo(
    val sourceAppId: String, val sourceLabel: String, val category: String, val label: String, val defaultSeverity: String,
    val defaultInbox: Boolean, val defaultAlert: Boolean, val mandatory: Boolean,
)

/**
 * A preference rule of the caller in one workspace. `*` is the wildcard for source, category and client app. [mode] is
 * `on`, `off` or `default` (inherit: it decides nothing, but its [rowVersion] is still the base for a later write).
 */
data class NotificationRule(
    val sourceAppId: String, val category: String, val clientAppId: String, val channel: String, val mode: String, val preview: String?, val rowVersion: Long,
) {
    val on: Boolean get() = mode == "on"
    val keyId: String get() = "$sourceAppId|$category|$clientAppId|$channel"
}

/** The rules and the revision they were read at. */
data class NotificationRuleSet(val rules: List<NotificationRule>, val rulesRevision: Long?)

/** One entry of a rules write. [expectedRowVersion] is the exact key's `row_version` in the last read, 0 when the key was absent. */
data class NotificationRuleWrite(
    val sourceAppId: String, val category: String, val clientAppId: String, val channel: String, val mode: String, val preview: String?, val expectedRowVersion: Long,
)

sealed class NotificationRulesResult {
    data class Saved(val current: NotificationRuleSet) : NotificationRulesResult()
    /** `rules_conflict`: some key moved (another device). Nothing was written; [current] is every current rule. */
    data class Conflict(val current: NotificationRuleSet, val conflictCount: Int) : NotificationRulesResult()
    /** `mutation_id_reused`: the same id arrived with a different request; a rejection, not a success. */
    data object MutationReused : NotificationRulesResult()
    data class Failed(val status: Int, val code: String?) : NotificationRulesResult()
}

fun parseNotificationCategories(o: JsonObject): List<NotificationCategoryInfo> =
    (o.objects("sources") ?: throw TowerError.Malformed("notification categories")).flatMap { source ->
        val app = source.string("app_id") ?: return@flatMap emptyList()
        val label = source.string("label") ?: app
        (source.objects("categories") ?: emptyList()).mapNotNull { c ->
            val category = c.string("category") ?: return@mapNotNull null
            NotificationCategoryInfo(
                app, label, category, c.string("label") ?: category, c.string("default_severity") ?: "normal",
                (c.string("default_inbox") ?: "on") == "on" && c.bool("default_inbox") != false,
                (c.string("default_alert") ?: "on") == "on" && c.bool("default_alert") != false,
                c.bool("mandatory") ?: false,
            )
        }
    }

fun parseNotificationRuleSet(o: JsonObject): NotificationRuleSet = NotificationRuleSet(parseNotificationRules(o), flexibleLong(o["rules_revision"]))

fun parseNotificationRules(o: JsonObject): List<NotificationRule> =
    (o.objects("rules") ?: throw TowerError.Malformed("notification rules")).mapNotNull { r ->
        NotificationRule(
            r.string("source_app_id") ?: "*", r.string("category") ?: "*", r.string("client_app_id") ?: "*",
            r.string("channel") ?: return@mapNotNull null, r.string("mode") ?: return@mapNotNull null,
            r.string("preview"), flexibleLong(r["row_version"]) ?: 0,
        )
    }
