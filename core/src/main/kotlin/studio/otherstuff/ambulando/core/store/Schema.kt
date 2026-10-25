package studio.otherstuff.ambulando.core.store

/**
 * The on-device schema. Android has no installs older than its first schema, so version 1 creates the complete current
 * schema (the iOS v1…v5 tables in one step, plus the Android `access_removed` flags). Later versions append a
 * [Migration]; [migrate] runs every step above the stored `user_version` in one transaction.
 *
 * Every record table is keyed `(workspace_id, id)` and keeps Tower's canonical JSON in `raw` (keys sorted). Tables private
 * to one actor (drafts, outbox, writes, document drafts, pins, personal WApps, profiles) carry `connection_id` and go
 * with the connection (origin + signer) through `ON DELETE CASCADE`.
 *
 * `access_removed` (Android only): the record stopped being visible to this identity (403/404, missing from a complete
 * list, scope or channel access lost). The row stays readable and is labelled by the UI; sync never selects it again.
 * Records Tower reports deleted (tombstones) are still removed.
 */
object Schema {
    /** One schema step. `statements` run in order inside the migration transaction. */
    class Migration(val version: Int, val statements: List<String>)

    val migrations: List<Migration> = listOf(Migration(1, v1()))

    val VERSION: Int get() = migrations.maxOf { it.version }

    /** Applies every migration above the stored version, then stores the new version, all in one transaction. */
    fun migrate(db: SqlConnection, steps: List<Migration> = migrations) {
        val current = db.userVersion
        val target = steps.maxOf { it.version }
        if (current >= target) return
        db.transaction {
            for (step in steps.sortedBy { it.version }) if (step.version > current) db.executeAll(step.statements)
            db.userVersion = target
        }
    }

    /** Workspace-scoped tables, cleared with a workspace that no connection on the device lists any more. */
    internal val workspaceTables = listOf(
        "scopes", "channels", "messages", "threads", "members", "channel_sync",
        "tasks", "comments", "docs", "doc_bodies", "files", "folders", "view_states", "reactions", "list_sync",
        "context_components", "context_references", "canvases", "canvas_snapshots", "board_scenes", "board_links",
        "workspace_agents", "agent_activities", "personal_wapps", "pins",
    )

    /** Tables carrying the `access_removed` flag. */
    internal val flaggedTables = setOf(
        "workspaces", "scopes", "channels", "messages", "threads", "tasks", "comments", "docs", "files", "folders",
        "context_components", "context_references", "canvases", "agent_activities",
    )

    private fun v1(): List<String> = listOf(
        """CREATE TABLE connections(
          id TEXT PRIMARY KEY, origin TEXT NOT NULL, signer_npub TEXT NOT NULL, tower_npub TEXT, created_at TEXT NOT NULL,
          UNIQUE(origin, signer_npub))""",
        """CREATE TABLE workspaces(
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          workspace_id TEXT NOT NULL, app_npub TEXT NOT NULL, label TEXT NOT NULL, slug TEXT, tower_base_url TEXT,
          role TEXT, owner_npub TEXT, raw TEXT NOT NULL, synced_at TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(connection_id, workspace_id))""",
        """CREATE TABLE scopes(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT, description TEXT,
          default_channel_id TEXT, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        """CREATE TABLE channels(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, scope_id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT,
          position REAL, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX channels_scope ON channels(workspace_id, scope_id, position, name)",
        """CREATE TABLE messages(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, channel_id TEXT NOT NULL, thread_id TEXT, thread_source_id TEXT,
          body TEXT NOT NULL, sender_npub TEXT, sender_label TEXT, client_request_id TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL, row_version INTEGER NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0, access_removed INTEGER NOT NULL DEFAULT 0, raw TEXT NOT NULL,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX messages_channel_time ON messages(workspace_id, channel_id, created_at, id)",
        "CREATE INDEX messages_thread_time ON messages(workspace_id, thread_id, created_at, id)",
        "CREATE INDEX messages_sender ON messages(workspace_id, sender_npub)",
        """CREATE TABLE threads(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, channel_id TEXT NOT NULL, source_message_id TEXT, title TEXT, latest TEXT,
          archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, access_removed INTEGER NOT NULL DEFAULT 0,
          row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX threads_channel ON threads(workspace_id, channel_id)",
        """CREATE TABLE members(
          workspace_id TEXT NOT NULL, npub TEXT NOT NULL, actor_id TEXT NOT NULL, kind TEXT, display_name TEXT,
          PRIMARY KEY(workspace_id, npub))""",
        """CREATE TABLE drafts(
          key TEXT PRIMARY KEY,
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          workspace_id TEXT NOT NULL, channel_id TEXT NOT NULL, thread_id TEXT,
          text TEXT NOT NULL, mentions TEXT NOT NULL, updated_at TEXT NOT NULL)""",
        """CREATE TABLE outbox(
          id TEXT PRIMARY KEY,
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          origin TEXT NOT NULL, signer_npub TEXT NOT NULL, workspace_id TEXT NOT NULL, app_npub TEXT NOT NULL,
          channel_id TEXT NOT NULL, thread_id TEXT, create_thread INTEGER NOT NULL,
          body TEXT NOT NULL, mentions TEXT NOT NULL, request BLOB,
          state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_status INTEGER, last_code TEXT, last_error TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL, attachments TEXT NOT NULL DEFAULT '[]')""",
        "CREATE INDEX outbox_target ON outbox(workspace_id, channel_id, thread_id, created_at)",
        """CREATE TABLE channel_sync(
          workspace_id TEXT NOT NULL, channel_id TEXT NOT NULL,
          resume_cursor TEXT, resume_after_created_at TEXT, resume_after_id TEXT,
          complete INTEGER NOT NULL DEFAULT 0, synced_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, channel_id))""",
        "CREATE TABLE app_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        // Tasks, documents, files, inbox view states, reactions, the write queue.
        """CREATE TABLE tasks(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, scope_id TEXT, channel_id TEXT NOT NULL, thread_id TEXT, title TEXT NOT NULL,
          description TEXT, state TEXT NOT NULL, priority TEXT NOT NULL, assigned_to_npub TEXT, created_by_npub TEXT,
          row_version INTEGER NOT NULL, activity_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          raw TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX tasks_channel ON tasks(workspace_id, channel_id)",
        "CREATE INDEX tasks_scope_state ON tasks(workspace_id, scope_id, state, updated_at)",
        """CREATE TABLE comments(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, parent_id TEXT NOT NULL, parent_comment_id TEXT,
          body TEXT NOT NULL, sender_npub TEXT, status TEXT, deleted INTEGER NOT NULL DEFAULT 0, client_record_id TEXT,
          row_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX comments_parent ON comments(workspace_id, parent, parent_id, created_at, id)",
        """CREATE TABLE docs(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, scope_id TEXT, channel_id TEXT NOT NULL, storage_object_id TEXT,
          title TEXT NOT NULL, summary TEXT, archived INTEGER NOT NULL DEFAULT 0, body_text TEXT,
          row_version INTEGER NOT NULL, activity_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          raw TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX docs_channel ON docs(workspace_id, channel_id)",
        """CREATE TABLE doc_bodies(
          workspace_id TEXT NOT NULL, doc_id TEXT NOT NULL, storage_object_id TEXT NOT NULL, sha256 TEXT NOT NULL, content_type TEXT NOT NULL,
          doc_row_version INTEGER NOT NULL, bytes BLOB NOT NULL, fetched_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, doc_id))""",
        """CREATE TABLE doc_drafts(
          key TEXT PRIMARY KEY,
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          workspace_id TEXT NOT NULL, doc_id TEXT NOT NULL, title TEXT NOT NULL, body BLOB NOT NULL, content_type TEXT NOT NULL,
          base_row_version INTEGER NOT NULL, base_sha256 TEXT NOT NULL, base_storage_object_id TEXT NOT NULL,
          state TEXT NOT NULL, last_error TEXT, recovery_id TEXT, updated_at TEXT NOT NULL)""",
        """CREATE TABLE files(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, scope_id TEXT, channel_id TEXT NOT NULL, folder_id TEXT, storage_object_id TEXT,
          display_name TEXT NOT NULL, description TEXT, content_type TEXT, size_bytes INTEGER, sha256 TEXT, archived INTEGER NOT NULL DEFAULT 0,
          row_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX files_channel ON files(workspace_id, channel_id, folder_id)",
        """CREATE TABLE folders(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, channel_id TEXT NOT NULL, parent_folder_id TEXT, title TEXT NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        """CREATE TABLE view_states(
          workspace_id TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id TEXT NOT NULL, scope_id TEXT, channel_id TEXT,
          activity_version INTEGER NOT NULL, viewed_activity_version INTEGER NOT NULL, attention_activity_version INTEGER,
          row_version INTEGER NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, resource_type, resource_id))""",
        """CREATE TABLE reactions(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT NOT NULL, emoji TEXT NOT NULL,
          reactor_npub TEXT, deleted INTEGER NOT NULL DEFAULT 0, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX reactions_target ON reactions(workspace_id, target_type, target_id)",
        """CREATE TABLE list_sync(workspace_id TEXT NOT NULL, key TEXT NOT NULL, complete INTEGER NOT NULL, synced_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, key))""",
        """CREATE TABLE writes(
          id TEXT PRIMARY KEY,
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          origin TEXT NOT NULL, signer_npub TEXT NOT NULL, workspace_id TEXT NOT NULL, app_npub TEXT NOT NULL,
          kind TEXT NOT NULL, target_id TEXT, group_key TEXT NOT NULL, payload BLOB NOT NULL, request BLOB,
          state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_status INTEGER, last_code TEXT, last_error TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
        "CREATE INDEX writes_target ON writes(workspace_id, target_id)",
        // Context Tree, canvases, boards, agents and their activity, Toolbox, personal pins.
        """CREATE TABLE context_components(
          workspace_id TEXT NOT NULL, scope_id TEXT NOT NULL, id TEXT NOT NULL, parent_id TEXT, title TEXT NOT NULL, sort_order INTEGER NOT NULL,
          row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX context_components_scope ON context_components(workspace_id, scope_id, parent_id, sort_order, id)",
        """CREATE TABLE context_references(
          workspace_id TEXT NOT NULL, scope_id TEXT NOT NULL, component_id TEXT NOT NULL, id TEXT NOT NULL, target_type TEXT NOT NULL,
          target_id TEXT, status TEXT NOT NULL, sort_order INTEGER NOT NULL, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL,
          raw TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX context_references_component ON context_references(workspace_id, component_id, sort_order, id)",
        """CREATE TABLE canvases(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, position INTEGER NOT NULL,
          row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL, access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        """CREATE TABLE canvas_snapshots(
          workspace_id TEXT NOT NULL, canvas_id TEXT NOT NULL, canvas_row_version INTEGER NOT NULL, bytes BLOB NOT NULL, fetched_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, canvas_id))""",
        """CREATE TABLE board_scenes(
          workspace_id TEXT NOT NULL, file_id TEXT NOT NULL, version_id TEXT NOT NULL, sha256 TEXT NOT NULL, bytes BLOB NOT NULL, fetched_at TEXT NOT NULL,
          PRIMARY KEY(workspace_id, file_id))""",
        """CREATE TABLE board_links(
          workspace_id TEXT NOT NULL, canvas_id TEXT NOT NULL, file_id TEXT NOT NULL,
          PRIMARY KEY(workspace_id, canvas_id, file_id))""",
        """CREATE TABLE workspace_agents(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, agent_npub TEXT NOT NULL, display_name TEXT NOT NULL, sort_order INTEGER NOT NULL,
          visible INTEGER NOT NULL, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          PRIMARY KEY(workspace_id, id))""",
        """CREATE TABLE agent_activities(
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, activity_id TEXT NOT NULL, scope_id TEXT, channel_id TEXT NOT NULL, thread_id TEXT,
          agent_npub TEXT NOT NULL, state TEXT NOT NULL, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          access_removed INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(workspace_id, id))""",
        "CREATE INDEX agent_activities_channel ON agent_activities(workspace_id, channel_id, updated_at)",
        """CREATE TABLE personal_wapps(
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, sort_order INTEGER NOT NULL, status TEXT NOT NULL,
          row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          PRIMARY KEY(connection_id, workspace_id, id))""",
        """CREATE TABLE pins(
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          workspace_id TEXT NOT NULL, id TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id TEXT NOT NULL, channel_id TEXT, scope_id TEXT,
          pinned INTEGER NOT NULL, row_version INTEGER NOT NULL, updated_at TEXT NOT NULL, raw TEXT NOT NULL,
          PRIMARY KEY(connection_id, workspace_id, id))""",
        "CREATE INDEX pins_resource ON pins(connection_id, workspace_id, resource_type, resource_id)",
        // Public Nostr profiles, per connection.
        """CREATE TABLE profiles(
          connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
          pubkey TEXT NOT NULL, npub TEXT NOT NULL,
          display_name TEXT, name TEXT, picture TEXT, nip05 TEXT,
          event_id TEXT, event_created_at INTEGER,
          status TEXT NOT NULL, checked_at REAL NOT NULL,
          PRIMARY KEY(connection_id, pubkey))""",
        "CREATE INDEX profiles_checked ON profiles(connection_id, checked_at)",
    ) + SearchIndex.statements
}

/**
 * Local full-text search: SQLite FTS5 (bundled with the driver on every platform, so the iOS `LIKE` fallback is not
 * needed), maintained by triggers inside the writer's transactions.
 */
internal object SearchIndex {
    val statements: List<String> = listOf(
        "CREATE VIRTUAL TABLE search_index USING fts5(workspace_id UNINDEXED, kind UNINDEXED, record_id UNINDEXED, title, body, tokenize = 'unicode61 remove_diacritics 2')",
        """CREATE TRIGGER search_messages_ai AFTER INSERT ON messages WHEN new.deleted = 0 BEGIN
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'message', new.id, '', new.body); END""",
        """CREATE TRIGGER search_messages_au AFTER UPDATE OF body, deleted ON messages BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'message' AND record_id = old.id;
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) SELECT new.workspace_id, 'message', new.id, '', new.body WHERE new.deleted = 0; END""",
        """CREATE TRIGGER search_messages_ad AFTER DELETE ON messages BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'message' AND record_id = old.id; END""",
        """CREATE TRIGGER search_tasks_ai AFTER INSERT ON tasks BEGIN
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'task', new.id, new.title, coalesce(new.description, '')); END""",
        """CREATE TRIGGER search_tasks_au AFTER UPDATE OF title, description ON tasks BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'task' AND record_id = old.id;
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'task', new.id, new.title, coalesce(new.description, '')); END""",
        """CREATE TRIGGER search_tasks_ad AFTER DELETE ON tasks BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'task' AND record_id = old.id; END""",
        """CREATE TRIGGER search_docs_ai AFTER INSERT ON docs BEGIN
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'doc', new.id, new.title, coalesce(new.body_text, new.summary, '')); END""",
        """CREATE TRIGGER search_docs_au AFTER UPDATE OF title, summary, body_text ON docs BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'doc' AND record_id = old.id;
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'doc', new.id, new.title, coalesce(new.body_text, new.summary, '')); END""",
        """CREATE TRIGGER search_docs_ad AFTER DELETE ON docs BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'doc' AND record_id = old.id; END""",
        """CREATE TRIGGER search_files_ai AFTER INSERT ON files BEGIN
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'file', new.id, new.display_name, coalesce(new.description, '')); END""",
        """CREATE TRIGGER search_files_au AFTER UPDATE OF display_name, description ON files BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'file' AND record_id = old.id;
          INSERT INTO search_index(workspace_id, kind, record_id, title, body) VALUES(new.workspace_id, 'file', new.id, new.display_name, coalesce(new.description, '')); END""",
        """CREATE TRIGGER search_files_ad AFTER DELETE ON files BEGIN
          DELETE FROM search_index WHERE workspace_id = old.workspace_id AND kind = 'file' AND record_id = old.id; END""",
    )

    /**
     * FTS5 query from user text: each word (letters and digits only, at most 8) is a quoted prefix term, so punctuation
     * and FTS operators in the input cannot inject query syntax.
     */
    fun ftsQuery(text: String): String? {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // A combining mark continues the word it decorates (Swift compares whole grapheme clusters).
            if (Character.isLetter(cp) || isNumberLike(cp) || (current.isNotEmpty() && isMark(cp))) current.appendCodePoint(cp)
            else if (current.isNotEmpty()) { words.add(current.toString()); current.clear() }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) words.add(current.toString())
        if (words.isEmpty()) return null
        return words.take(8).joinToString(" ") { "\"" + it.replace("\"", "") + "\"*" }
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isNumberLike(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}
