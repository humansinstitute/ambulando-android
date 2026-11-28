import { Database } from 'bun:sqlite';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { sha256Hex } from './model.mjs';

/**
 * Local SQLite + FTS5 ontology search index and workspace sync cursor store
 * residing beside the authorized agent host.
 */
export class LocalOntologyStore {
  constructor(dbPath) {
    if (dbPath !== ':memory:') {
      mkdirSync(path.dirname(dbPath), { recursive: true });
    }
    this.dbPath = dbPath;
    this.db = new Database(dbPath, { create: true });
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');
    this.initSchema();
  }

  initSchema() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS sync_meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS sync_cursors (
        channel_id TEXT NOT NULL,
        family TEXT NOT NULL,
        cursor TEXT,
        last_synced_at TEXT NOT NULL,
        item_count INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (channel_id, family)
      );

      CREATE TABLE IF NOT EXISTS sources (
        source_key TEXT PRIMARY KEY,
        source_type TEXT NOT NULL,
        record_id TEXT NOT NULL,
        channel_id TEXT,
        thread_id TEXT,
        scope_name TEXT,
        channel_name TEXT,
        title TEXT NOT NULL,
        text_body TEXT NOT NULL,
        revision TEXT,
        updated_at TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'active',
        last_seen_at TEXT NOT NULL,
        extracted_hash TEXT,
        extracted_at TEXT
      );

      CREATE INDEX IF NOT EXISTS idx_sources_channel_status ON sources(channel_id, status);
      CREATE INDEX IF NOT EXISTS idx_sources_type_record ON sources(source_type, record_id);

      -- Ordered thread messages accumulated across sync pages so thread text never
      -- overwrites earlier pages and thread revision never regresses.
      CREATE TABLE IF NOT EXISTS thread_messages (
        thread_id TEXT NOT NULL,
        message_id TEXT NOT NULL,
        channel_id TEXT NOT NULL,
        scope_name TEXT,
        channel_name TEXT,
        body TEXT NOT NULL,
        row_version INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'active',
        PRIMARY KEY (thread_id, message_id)
      );

      CREATE INDEX IF NOT EXISTS idx_thread_messages_thread ON thread_messages(thread_id, created_at ASC);

      CREATE TABLE IF NOT EXISTS topics (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        kind TEXT NOT NULL,
        description TEXT NOT NULL,
        aliases_json TEXT NOT NULL,
        source_origin TEXT NOT NULL,
        archived INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL
      );

      CREATE VIRTUAL TABLE IF NOT EXISTS topics_fts USING fts5(
        topic_id UNINDEXED,
        name,
        aliases,
        description,
        kind
      );

      CREATE TABLE IF NOT EXISTS evidence_links (
        id TEXT PRIMARY KEY,
        topic_id TEXT NOT NULL,
        source_type TEXT NOT NULL,
        record_id TEXT NOT NULL,
        channel_id TEXT,
        thread_id TEXT,
        source_revision TEXT,
        source_title TEXT NOT NULL,
        explanation TEXT NOT NULL,
        score REAL NOT NULL,
        status TEXT NOT NULL,
        pinned INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_evidence_topic_score ON evidence_links(topic_id, score DESC);
      CREATE INDEX IF NOT EXISTS idx_evidence_source ON evidence_links(source_type, record_id);
    `);

    // Additive migration for pre-existing local SQLite databases
    const cols = this.db.query('PRAGMA table_info(sources)').all().map((r) => r.name);
    if (!cols.includes('extracted_hash')) {
      this.db.exec('ALTER TABLE sources ADD COLUMN extracted_hash TEXT');
    }
    if (!cols.includes('extracted_at')) {
      this.db.exec('ALTER TABLE sources ADD COLUMN extracted_at TEXT');
    }
  }

  close() {
    this.db.close();
  }

  getMeta(key) {
    const row = this.db.query('SELECT value FROM sync_meta WHERE key = ?').get(key);
    return row ? JSON.parse(row.value) : null;
  }

  setMeta(key, value, now = new Date()) {
    this.db
      .query(
        'INSERT INTO sync_meta(key, value, updated_at) VALUES(?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
      )
      .run(key, JSON.stringify(value), now.toISOString());
  }

  getSyncState() {
    return (
      this.getMeta('workspace_sync_state') || {
        phase: 'snapshot',
        snapshot_cursor: null,
        snapshot_through_cursor: null,
        delta_cursor: null,
        snapshot_complete: false,
        pages_synced: 0,
      }
    );
  }

  allCursors() {
    return {
      workspace_sync: this.getSyncState(),
    };
  }

  /**
   * Atomically applies one verified `/sync` page: upserts sources, accumulates
   * thread messages without overwriting prior pages (preserving monotonic revision),
   * applies tombstones/deletions, and commits the updated sync cursor state.
   */
  commitSyncPage({ syncState, items = [], threadRecords = [], threadMessages = [], tombstones = [], accessibleChannelIds = null, now = new Date() }) {
    const ts = now.toISOString();
    const upsertSource = this.db.query(`
      INSERT INTO sources(
        source_key, source_type, record_id, channel_id, thread_id, scope_name, channel_name,
        title, text_body, revision, updated_at, content_hash, status, last_seen_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(source_key) DO UPDATE SET
        channel_id = COALESCE(excluded.channel_id, sources.channel_id),
        thread_id = COALESCE(excluded.thread_id, sources.thread_id),
        scope_name = COALESCE(excluded.scope_name, sources.scope_name),
        channel_name = COALESCE(excluded.channel_name, sources.channel_name),
        title = CASE WHEN excluded.title != '' THEN excluded.title ELSE sources.title END,
        text_body = excluded.text_body,
        revision = COALESCE(excluded.revision, sources.revision),
        updated_at = excluded.updated_at,
        content_hash = excluded.content_hash,
        status = excluded.status,
        last_seen_at = excluded.last_seen_at
    `);
    const upsertThreadMsg = this.db.query(`
      INSERT INTO thread_messages(thread_id, message_id, channel_id, scope_name, channel_name, body, row_version, created_at, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(thread_id, message_id) DO UPDATE SET
        body = excluded.body,
        row_version = MAX(thread_messages.row_version, excluded.row_version),
        status = excluded.status
    `);
    const getSource = this.db.query('SELECT * FROM sources WHERE source_key = ?');
    const getThreadMsgs = this.db.query(
      "SELECT body, row_version, created_at, channel_id, scope_name, channel_name FROM thread_messages WHERE thread_id = ? AND status = 'active' ORDER BY created_at ASC, message_id ASC",
    );

    const tx = this.db.transaction(() => {
      for (const item of items) {
        const key = `${item.source_type}:${item.record_id}`;
        upsertSource.run(
          key,
          item.source_type,
          item.record_id,
          item.channel_id ?? null,
          item.thread_id ?? null,
          item.scope_name ?? null,
          item.channel_name ?? null,
          item.title ?? '',
          item.text_body ?? '',
          item.revision != null ? String(item.revision) : null,
          item.updated_at ?? ts,
          item.content_hash ?? '',
          item.status ?? 'active',
          ts,
        );
      }

      const touchedThreads = new Map();
      for (const tr of threadRecords) {
        touchedThreads.set(tr.id, tr);
      }
      for (const tm of threadMessages) {
        upsertThreadMsg.run(
          tm.thread_id,
          tm.message_id,
          tm.channel_id,
          tm.scope_name ?? null,
          tm.channel_name ?? null,
          tm.body ?? '',
          Number(tm.row_version || 1),
          tm.created_at ?? ts,
          tm.status ?? 'active',
        );
        if (!touchedThreads.has(tm.thread_id)) {
          touchedThreads.set(tm.thread_id, {
            id: tm.thread_id,
            channel_id: tm.channel_id,
            scope_name: tm.scope_name,
            channel_name: tm.channel_name,
          });
        }
      }

      // Recompute accumulated thread source rows preserving monotonic revision and full message history.
      for (const [threadId, meta] of touchedThreads.entries()) {
        const existing = getSource.get(`thread:${threadId}`);
        const msgs = getThreadMsgs.all(threadId);
        const combinedBody = msgs.map((m) => m.body).filter(Boolean).join('\n').slice(0, 12000);
        const existingRevNum = Number(existing?.revision ?? 0) || 0;
        const explicitRevNum = Number(meta.row_version ?? 0) || 0;
        const maxMsgRev = msgs.reduce((acc, m) => Math.max(acc, Number(m.row_version || 0)), 0);
        const monotonicRev = Math.max(existingRevNum, explicitRevNum, maxMsgRev, 1);
        const title = meta.title || existing?.title || msgs[0]?.body?.slice(0, 72) || `Thread ${threadId.slice(0, 8)}`;
        const status = meta.status || (msgs.length > 0 || explicitRevNum > 0 ? 'active' : 'deleted');
        const body = status === 'active' ? combinedBody || meta.summary || existing?.text_body || '' : '';
        upsertSource.run(
          `thread:${threadId}`,
          'thread',
          threadId,
          meta.channel_id ?? existing?.channel_id ?? msgs[0]?.channel_id ?? null,
          threadId,
          meta.scope_name ?? existing?.scope_name ?? msgs[0]?.scope_name ?? null,
          meta.channel_name ?? existing?.channel_name ?? msgs[0]?.channel_name ?? null,
          title,
          body,
          String(monotonicRev),
          meta.updated_at ?? msgs.at(-1)?.created_at ?? existing?.updated_at ?? ts,
          sha256Hex(Buffer.from(body)),
          status,
          ts,
        );
      }

      // Apply tombstones / deletions
      for (const tomb of tombstones) {
        const entityType = String(tomb.entity_type || tomb.resource_type || tomb.type || '').toLowerCase();
        const entityId = tomb.entity_id || tomb.record_id || tomb.id;
        if (!entityId) continue;
        if (entityType === 'channel') {
          this.db.query("UPDATE sources SET status = 'inaccessible', text_body = '', last_seen_at = ? WHERE channel_id = ?").run(ts, entityId);
          this.db.query("UPDATE thread_messages SET status = 'deleted', body = '' WHERE channel_id = ?").run(entityId);
          this.db.query("UPDATE evidence_links SET status = 'inaccessible', updated_at = ? WHERE channel_id = ?").run(ts, entityId);
        } else {
          const normalizedType = entityType === 'document' ? 'doc' : entityType === 'audio-note' ? 'audio_note' : entityType;
          const status = tomb.reason === 'access_revoked' ? 'inaccessible' : 'deleted';
          this.db.query("UPDATE sources SET status = ?, text_body = '', last_seen_at = ? WHERE source_key = ?").run(status, ts, `${normalizedType}:${entityId}`);
          this.db
            .query('UPDATE evidence_links SET status = ?, updated_at = ? WHERE source_type = ? AND record_id = ?')
            .run(status, ts, normalizedType, entityId);
          if (normalizedType === 'message') {
            this.db.query("UPDATE thread_messages SET status = 'deleted', body = '' WHERE message_id = ?").run(entityId);
          }
        }
      }

      // Mark sources in revoked/vanished/unauthorized channels inaccessible when directory channels were refreshed
      if (accessibleChannelIds instanceof Set) {
        const indexedChannels = this.db.query("SELECT DISTINCT channel_id FROM sources WHERE channel_id IS NOT NULL AND status = 'active'").all();
        for (const row of indexedChannels) {
          if (!accessibleChannelIds.has(row.channel_id)) {
            this.db.query("UPDATE sources SET status = 'inaccessible', text_body = '', last_seen_at = ? WHERE channel_id = ?").run(ts, row.channel_id);
            this.db.query("UPDATE thread_messages SET status = 'deleted', body = '' WHERE channel_id = ?").run(row.channel_id);
            this.db.query("UPDATE evidence_links SET status = 'inaccessible', updated_at = ? WHERE channel_id = ?").run(ts, row.channel_id);
          }
        }
      }

      this.db
        .query(
          'INSERT INTO sync_meta(key, value, updated_at) VALUES(?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
        )
        .run('workspace_sync_state', JSON.stringify(syncState), ts);
    });
    tx();
  }

  markSourceStatus(sourceType, recordId, status, now = new Date()) {
    const key = `${sourceType}:${recordId}`;
    const ts = now.toISOString();
    this.db.query('UPDATE sources SET status = ?, last_seen_at = ? WHERE source_key = ?').run(status, ts, key);
    this.db.query('UPDATE evidence_links SET status = ?, updated_at = ? WHERE source_type = ? AND record_id = ?').run(status, ts, sourceType, recordId);
  }

  markChannelInaccessible(channelId, now = new Date()) {
    const ts = now.toISOString();
    const tx = this.db.transaction(() => {
      this.db.query("UPDATE sources SET status = 'inaccessible', last_seen_at = ? WHERE channel_id = ?").run(ts, channelId);
      this.db.query("UPDATE evidence_links SET status = 'inaccessible', updated_at = ? WHERE channel_id = ?").run(ts, channelId);
    });
    tx();
  }

  listActiveSources() {
    return this.db.query("SELECT * FROM sources WHERE status = 'active' ORDER BY updated_at DESC, source_key ASC").all();
  }

  listActiveChannelIds() {
    return this.db
      .query("SELECT DISTINCT channel_id FROM sources WHERE channel_id IS NOT NULL AND status = 'active'")
      .all()
      .map((r) => r.channel_id);
  }

  /**
   * Returns active sources that have never been extracted or whose content_hash changed
   * since their last successful ontology publish. Ordered newest-first so recent changes
   * are indexed promptly while older unprocessed sources systematically drain across runs.
   */
  listPendingExtractionSources(limit = 40) {
    return this.db
      .query(
        "SELECT * FROM sources WHERE status = 'active' AND (extracted_hash IS NULL OR extracted_hash != content_hash) ORDER BY updated_at DESC, source_key ASC LIMIT ?",
      )
      .all(limit);
  }

  countPendingExtractionSources() {
    const row = this.db
      .query(
        "SELECT COUNT(*) AS cnt FROM sources WHERE status = 'active' AND (extracted_hash IS NULL OR extracted_hash != content_hash)",
      )
      .get();
    return Number(row?.cnt ?? 0);
  }

  /**
   * Marks the exact prepared batch items as extracted ONLY when their current content_hash
   * still matches what was analyzed (so concurrent edits remain pending for the next run).
   */
  markSourcesExtracted(batchItems = [], now = new Date()) {
    const ts = now.toISOString();
    const stmt = this.db.query(
      'UPDATE sources SET extracted_hash = content_hash, extracted_at = ? WHERE source_key = ? AND content_hash = ?',
    );
    const tx = this.db.transaction(() => {
      for (const item of batchItems) {
        if (!item?.source_type || !item?.record_id) continue;
        const key = `${item.source_type}:${item.record_id}`;
        if (item.content_hash !== undefined && item.content_hash !== null) {
          stmt.run(ts, key, String(item.content_hash));
        } else {
          this.db.query('UPDATE sources SET extracted_hash = content_hash, extracted_at = ? WHERE source_key = ?').run(ts, key);
        }
      }
    });
    tx();
  }

  listAllSources() {
    return this.db.query('SELECT * FROM sources ORDER BY updated_at DESC, source_key ASC').all();
  }

  getSource(sourceType, recordId) {
    return this.db.query('SELECT * FROM sources WHERE source_key = ?').get(`${sourceType}:${recordId}`) ?? null;
  }

  replaceIndex(topics, evidence) {
    const tx = this.db.transaction(() => {
      this.db.exec('DELETE FROM topics; DELETE FROM topics_fts; DELETE FROM evidence_links;');
      const insertTopic = this.db.query(`
        INSERT INTO topics(id, name, kind, description, aliases_json, source_origin, archived, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `);
      const insertFts = this.db.query(`
        INSERT INTO topics_fts(topic_id, name, aliases, description, kind)
        VALUES (?, ?, ?, ?, ?)
      `);
      const insertEv = this.db.query(`
        INSERT INTO evidence_links(
          id, topic_id, source_type, record_id, channel_id, thread_id,
          source_revision, source_title, explanation, score, status, pinned, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);

      for (const t of topics) {
        insertTopic.run(
          t.id,
          t.name,
          t.kind,
          t.description || '',
          JSON.stringify(t.aliases || []),
          t.source_origin || 'discovered',
          t.archived ? 1 : 0,
          t.updated_at,
        );
        if (!t.archived) {
          insertFts.run(t.id, t.name, (t.aliases || []).join(' '), t.description || '', t.kind || '');
        }
      }
      for (const e of evidence) {
        insertEv.run(
          e.id,
          e.topic_id,
          e.source_type,
          e.record_id,
          e.channel_id ?? null,
          e.thread_id ?? null,
          e.source_revision ?? null,
          e.source_title ?? '',
          e.explanation ?? '',
          e.score ?? 0.5,
          e.status ?? 'active',
          e.pinned ? 1 : 0,
          e.updated_at,
        );
      }
    });
    tx();
  }

  lookup(queryText, { topicLimit = 10, evidenceLimit = 15, includeArchived = false, includeInactiveEvidence = false } = {}) {
    const trimmed = String(queryText || '').trim();
    let matchedTopics = [];
    if (!trimmed) {
      matchedTopics = this.db
        .query(`SELECT * FROM topics ${includeArchived ? '' : 'WHERE archived = 0'} ORDER BY name ASC LIMIT ?`)
        .all(topicLimit);
    } else {
      const tokens = trimmed
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter((t) => t.length >= 2);
      const seen = new Set();
      if (tokens.length > 0) {
        const ftsExpr = tokens.map((t) => `"${t.replace(/"/g, '')}"*`).join(' OR ');
        try {
          const ftsRows = this.db
            .query(`
              SELECT t.*, bm25(topics_fts) AS rank
              FROM topics_fts
              JOIN topics t ON t.id = topics_fts.topic_id
              WHERE topics_fts MATCH ?
              ${includeArchived ? '' : 'AND t.archived = 0'}
              ORDER BY rank ASC, t.name ASC
              LIMIT ?
            `)
            .all(ftsExpr, topicLimit);
          for (const row of ftsRows) {
            seen.add(row.id);
            matchedTopics.push(row);
          }
        } catch {
          // Fallback to LIKE matching
        }
      }
      if (matchedTopics.length < topicLimit) {
        const pattern = `%${trimmed.toLowerCase()}%`;
        const likeRows = this.db
          .query(`
            SELECT * FROM topics
            WHERE (lower(name) LIKE ? OR lower(aliases_json) LIKE ? OR lower(description) LIKE ? OR lower(kind) LIKE ?)
            ${includeArchived ? '' : 'AND archived = 0'}
            ORDER BY name ASC
            LIMIT ?
          `)
          .all(pattern, pattern, pattern, pattern, topicLimit);
        for (const row of likeRows) {
          if (!seen.has(row.id) && matchedTopics.length < topicLimit) {
            seen.add(row.id);
            matchedTopics.push(row);
          }
        }
      }
    }

    const evQuery = this.db.query(`
      SELECT * FROM evidence_links
      WHERE topic_id = ?
      ${includeInactiveEvidence ? '' : "AND status = 'active'"}
      ORDER BY pinned DESC, score DESC, updated_at DESC
      LIMIT ?
    `);

    return matchedTopics.map((t) => {
      const evidence = evQuery.all(t.id, evidenceLimit).map((e) => ({
        id: e.id,
        topic_id: e.topic_id,
        source_type: e.source_type,
        record_id: e.record_id,
        channel_id: e.channel_id,
        thread_id: e.thread_id,
        source_revision: e.source_revision,
        source_title: e.source_title,
        explanation: e.explanation,
        score: e.score,
        status: e.status,
        pinned: Boolean(e.pinned),
        updated_at: e.updated_at,
      }));
      return {
        id: t.id,
        name: t.name,
        kind: t.kind,
        description: t.description,
        aliases: JSON.parse(t.aliases_json || '[]'),
        source_origin: t.source_origin,
        archived: Boolean(t.archived),
        updated_at: t.updated_at,
        evidence,
      };
    });
  }
}
