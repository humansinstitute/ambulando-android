import { describe, expect, test } from 'bun:test';
import crypto from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import * as M from './model.mjs';
import { LocalOntologyStore } from './store.mjs';
import * as Sync from './sync.mjs';
import { mergeStructuredExtraction } from './extractor.mjs';
import { DEFAULT_CRON_EXPRESSION, DEFAULT_PIPELINE_ID, reconcileSchedule, resolveBoundSubscription } from './scheduler.mjs';

// Arbitrary synthetic fixture identifiers (zero production workspace/channel/group/actor/npub IDs).
const WS_A = '10000000-0000-4000-8000-000000000001';
const SCOPE_HOME = '20000000-0000-4000-8000-000000000001';
const SCOPE_WORK = '20000000-0000-4000-8000-000000000002';
const CH_PROJECT = '30000000-0000-4000-8000-000000000001';
const CH_PRIVATE_NOTES = '30000000-0000-4000-8000-000000000002';
const CH_REVOKED = '30000000-0000-4000-8000-000000000003';
const CH_AGENT_ONLY = '30000000-0000-4000-8000-000000000004';
const CH_OTHER_DM = '30000000-0000-4000-8000-000000000005';

const OWNER_NPUB = 'npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq02';
const APP_NPUB = 'npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq03';
const AGENT_A_NPUB = 'npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq04';
const AGENT_B_NPUB = 'npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq05';
const MEMBER_NPUB = 'npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq06';

const OWNER_ID = '11111111-1111-4111-8111-111111111111';
const AGENT_A_ID = '22222222-2222-4222-8222-222222222221';
const AGENT_B_ID = '22222222-2222-4222-8222-222222222222';
const MEMBER_ID = '33333333-3333-4333-8333-333333333333';
const ADMINS_GROUP = '44444444-4444-4444-8444-444444444441';
const WORKSPACE_GROUP = '44444444-4444-4444-8444-444444444442';
const SUBSCRIPTION_ID = '55555555-5555-4555-8555-555555555555';

const THREAD_COS_ID = 'bbbb1111-1111-4111-8111-111111111111';
const MSG_COS_1_ID = 'aaaa1111-1111-4111-8111-111111111111';
const MSG_COS_2_ID = 'aaaa1111-1111-4111-8111-111111111112';
const MSG_COS_NEW_ID = 'aaaa1111-1111-4111-8111-111111111113';
const MSG_CANOPY_ID = 'aaaa2222-2222-4222-8222-222222222222';
const MSG_AGENT_ONLY_ID = 'aaaa3333-3333-4333-8333-333333333333';
const MSG_OTHER_DM_ID = 'aaaa4444-4444-4444-8444-444444444444';
const AUDIO_COS_ID = 'cccc1111-1111-4111-8111-111111111111';
const TASK_COS_ID = 'dddd1111-1111-4111-8111-111111111111';
const DOC_ARCH_ID = 'eeee1111-1111-4111-8111-111111111111';
const FILE_TEXT_ID = 'ffff1111-1111-4111-8111-111111111111';

function createMockTower({ workspaceId = WS_A, ownerNpub = OWNER_NPUB, appNpub = APP_NPUB } = {}) {
  const members = [
    { actor_id: OWNER_ID, npub: ownerNpub, kind: 'human', role: 'owner', display_name: 'Owner' },
    { actor_id: AGENT_A_ID, npub: AGENT_A_NPUB, kind: 'agent', role: 'agent', display_name: 'Agent Alpha' },
    { actor_id: AGENT_B_ID, npub: AGENT_B_NPUB, kind: 'agent', role: 'agent', display_name: 'Agent Beta' },
    { actor_id: MEMBER_ID, npub: MEMBER_NPUB, kind: 'human', role: 'member', display_name: 'Member' },
  ];

  const expandAccessLevel = (level) => {
    if (level === 'manage') return ['channel.read', 'file.read', 'file.write', 'channel.manage', 'channel.grants.manage'];
    if (level === 'contribute') return ['channel.read', 'file.read', 'file.write'];
    return ['channel.read', 'file.read'];
  };

  const state = {
    scopes: [
      { id: SCOPE_HOME, workspace_id: workspaceId, name: 'Home', kind: 'project' },
      { id: SCOPE_WORK, workspace_id: workspaceId, name: 'Projects', kind: 'project' },
    ],
    channels: [
      { id: CH_PROJECT, workspace_id: workspaceId, scope_id: SCOPE_WORK, name: 'Product', kind: 'channel', metadata: {} },
      { id: CH_PRIVATE_NOTES, workspace_id: workspaceId, scope_id: SCOPE_WORK, name: 'Note to self', kind: 'dm', participant_npubs: [OWNER_NPUB], metadata: {} },
      { id: CH_REVOKED, workspace_id: workspaceId, scope_id: SCOPE_WORK, name: 'Canopy', kind: 'channel', metadata: {} },
      { id: CH_AGENT_ONLY, workspace_id: workspaceId, scope_id: SCOPE_WORK, name: 'Secret Agent Ops', kind: 'channel', metadata: {} },
      { id: CH_OTHER_DM, workspace_id: workspaceId, scope_id: SCOPE_WORK, name: 'Member DM', kind: 'dm', participant_npubs: [AGENT_A_NPUB, MEMBER_NPUB], metadata: {} },
    ],
    members,
    groups: [
      {
        id: ADMINS_GROUP,
        group_id: ADMINS_GROUP,
        name: 'Admins',
        kind: 'system',
        group_kind: 'system',
        effective_members: [
          { actor_id: OWNER_ID, npub: ownerNpub, kind: 'human' },
          { actor_id: AGENT_A_ID, npub: AGENT_A_NPUB, kind: 'agent' },
        ],
      },
      {
        id: WORKSPACE_GROUP,
        group_id: WORKSPACE_GROUP,
        name: 'Workspace',
        kind: 'system',
        group_kind: 'system',
        effective_members: members.map((m) => ({ actor_id: m.actor_id, npub: m.npub, kind: m.kind })),
      },
    ],
    grants: new Map([
      [CH_PROJECT, [{ principal_type: 'group', principal_id: WORKSPACE_GROUP, permissions: expandAccessLevel('contribute') }]],
      [CH_REVOKED, [{ principal_type: 'group', principal_id: WORKSPACE_GROUP, permissions: expandAccessLevel('view') }]],
      [
        CH_AGENT_ONLY,
        [
          { principal_type: 'actor', principal_id: AGENT_A_ID, permissions: expandAccessLevel('contribute') },
          { principal_type: 'actor', principal_id: MEMBER_ID, permissions: expandAccessLevel('contribute') },
        ],
      ],
    ]),
    files: new Map(),
    objects: new Map(),
    threads: new Map([
      [
        CH_PROJECT,
        [
          {
            id: THREAD_COS_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            title: 'Chief of Staff Design Thread',
            row_version: 5,
            updated_at: '2026-11-28T01:02:00.000Z',
          },
        ],
      ],
    ]),
    messages: new Map([
      [
        CH_PROJECT,
        [
          {
            id: MSG_COS_1_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            thread_id: THREAD_COS_ID,
            body: 'First page message: building Chief of Staff ontology indexing for workspace.',
            row_version: 2,
            created_at: '2026-11-28T01:00:00.000Z',
            updated_at: '2026-11-28T01:00:00.000Z',
          },
          {
            id: MSG_COS_2_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            thread_id: THREAD_COS_ID,
            body: 'Second page message: preserve human corrections across rebuilds.',
            row_version: 3,
            created_at: '2026-11-28T01:01:00.000Z',
            updated_at: '2026-11-28T01:01:00.000Z',
          },
        ],
      ],
      [
        CH_PRIVATE_NOTES,
        [
          {
            id: 'aaaa9999-9999-4999-8999-999999999999',
            workspace_id: workspaceId,
            channel_id: CH_PRIVATE_NOTES,
            body: 'TOP SECRET PRIVATE DIARY PHRASE ZEBRA999',
            row_version: 1,
            created_at: '2026-11-28T01:05:00.000Z',
            updated_at: '2026-11-28T01:05:00.000Z',
          },
        ],
      ],
      [
        CH_REVOKED,
        [
          {
            id: MSG_CANOPY_ID,
            workspace_id: workspaceId,
            channel_id: CH_REVOKED,
            body: 'Canopy relay sync architecture milestone discussion',
            row_version: 1,
            created_at: '2026-11-28T01:10:00.000Z',
            updated_at: '2026-11-28T01:10:00.000Z',
          },
        ],
      ],
      [
        CH_AGENT_ONLY,
        [
          {
            id: MSG_AGENT_ONLY_ID,
            workspace_id: workspaceId,
            channel_id: CH_AGENT_ONLY,
            body: 'AGENT_ONLY_SECRET_PHRASE_XYLOPHONE777 restricted to agent and member',
            row_version: 1,
            created_at: '2026-11-28T01:11:00.000Z',
            updated_at: '2026-11-28T01:11:00.000Z',
          },
        ],
      ],
      [
        CH_OTHER_DM,
        [
          {
            id: MSG_OTHER_DM_ID,
            workspace_id: workspaceId,
            channel_id: CH_OTHER_DM,
            body: 'OTHER_MEMBER_DM_SECRET_QUARTZ888 private conversation',
            row_version: 1,
            created_at: '2026-11-28T01:11:30.000Z',
            updated_at: '2026-11-28T01:11:30.000Z',
          },
        ],
      ],
    ]),
    audioNotes: new Map([
      [
        CH_PROJECT,
        [
          {
            id: AUDIO_COS_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            title: 'Voice memo on Chief of Staff',
            transcript: 'Spoken note about Chief of Staff voice transcripts and mobile release.',
            row_version: 1,
            updated_at: '2026-11-28T01:12:00.000Z',
          },
          {
            id: 'cccc2222-2222-4222-8222-222222222222',
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            title: 'Untranscribed voice clip',
            transcript: '',
            row_version: 1,
            updated_at: '2026-11-28T01:13:00.000Z',
          },
        ],
      ],
    ]),
    tasks: new Map([
      [
        CH_PROJECT,
        [
          {
            id: TASK_COS_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            title: 'Chief of Staff assignment and living workspace topic index',
            description: 'Deliver mobile UI and desktop parity for Chief of Staff.',
            state: 'in_progress',
            row_version: 3,
            updated_at: '2026-11-28T01:15:00.000Z',
          },
        ],
      ],
    ]),
    docs: new Map([
      [
        CH_PROJECT,
        [
          {
            id: DOC_ARCH_ID,
            workspace_id: workspaceId,
            channel_id: CH_PROJECT,
            title: 'Platform Suite Architecture',
            body: 'Local SQLite FTS5 ontology lookup backed by immutable file versions.',
            row_version: 4,
            updated_at: '2026-11-28T01:20:00.000Z',
          },
        ],
      ],
    ]),
    revokedChannels: new Set(),
    // Model NStack's current `/members` authority projection; set false to simulate an older server that omits it.
    authorityProjection: true,
    deltaQueue: [],
    syncVersion: 100,
  };

  const textArtVer = 'ffff1111-1111-4111-8111-111111111112';
  const textArtBytes = Buffer.from('# Release Checklist\nChief of Staff release steps.', 'utf8');
  state.files.set(FILE_TEXT_ID, {
    id: FILE_TEXT_ID,
    workspace_id: workspaceId,
    channel_id: CH_PROJECT,
    display_name: 'Release Checklist.md',
    content_type: 'text/markdown',
    current_version_id: textArtVer,
    created_by_actor_id: OWNER_ID,
    updated_by_actor_id: OWNER_ID,
    object: { storage_object: { size_bytes: textArtBytes.byteLength, sha256_hex: M.sha256Hex(textArtBytes), is_public: false } },
    metadata: {},
    updated_at: '2026-11-28T01:25:00.000Z',
    versions: new Map([[textArtVer, textArtBytes]]),
    versionMeta: new Map([[textArtVer, { id: textArtVer, created_by_actor_id: OWNER_ID, created_by_actor_npub: OWNER_NPUB }]]),
  });

  const binArtId = 'ffff2222-2222-4222-8222-222222222222';
  const binArtVer = 'ffff2222-2222-4222-8222-222222222223';
  const binArtBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]);
  state.files.set(binArtId, {
    id: binArtId,
    workspace_id: workspaceId,
    channel_id: CH_PROJECT,
    display_name: 'Architecture.png',
    content_type: 'image/png',
    current_version_id: binArtVer,
    created_by_actor_id: OWNER_ID,
    updated_by_actor_id: OWNER_ID,
    object: { storage_object: { size_bytes: binArtBytes.byteLength, sha256_hex: M.sha256Hex(binArtBytes), is_public: false } },
    metadata: {},
    updated_at: '2026-11-28T01:26:00.000Z',
    versions: new Map([[binArtVer, binArtBytes]]),
    versionMeta: new Map([[binArtVer, { id: binArtVer, created_by_actor_id: OWNER_ID, created_by_actor_npub: OWNER_NPUB }]]),
  });

  function clientAs(actorNpub) {
    const member = state.members.find((m) => m.npub === actorNpub);
    const inAdminsGroup = state.groups
      .find((g) => g.id === ADMINS_GROUP)
      ?.effective_members?.some((em) => em.actor_id === member?.actor_id);
    const isAdmin = member?.role === 'owner' || member?.role === 'admin' || Boolean(inAdminsGroup);

    function canReadChannel(chId) {
      if (state.revokedChannels.has(chId)) return false;
      const ch = state.channels.find((c) => c.id === chId);
      if (ch?.kind === 'dm') {
        return Array.isArray(ch.participant_npubs) && ch.participant_npubs.includes(actorNpub);
      }
      const grants = state.grants.get(chId);
      if (!grants) return false;
      for (const g of grants) {
        if (!g.permissions.includes('channel.read')) continue;
        if (g.principal_type === 'actor' && g.principal_id === member?.actor_id) return true;
        if (g.principal_type === 'group' && g.principal_id === ADMINS_GROUP && isAdmin) return true;
        if (g.principal_type === 'group' && g.principal_id === WORKSPACE_GROUP && member) return true;
      }
      return false;
    }

    function canWriteChannel(chId) {
      if (state.revokedChannels.has(chId)) return false;
      const grants = state.grants.get(chId) || [];
      for (const g of grants) {
        if (!g.permissions.includes('file.write')) continue;
        if (g.principal_type === 'actor' && g.principal_id === member?.actor_id) return true;
        if (g.principal_type === 'group' && g.principal_id === ADMINS_GROUP && isAdmin) return true;
        if (g.principal_type === 'group' && g.principal_id === WORKSPACE_GROUP && member) return true;
      }
      return false;
    }

    return {
      async request(method, pathname, body) {
        const [clean, queryString] = pathname.split('?');
        const query = new URLSearchParams(queryString || '');
        const prefix = `/api/v4/flightdeck-pg/workspaces/${workspaceId}`;
        if (!clean.startsWith(prefix)) {
          const e = new Error('Wrong workspace');
          e.status = 403;
          throw e;
        }
        const sub = clean.slice(prefix.length);

        if (method === 'GET' && sub === '/me') {
          return {
            identity: { workspace_id: workspaceId, workspace_owner_npub: ownerNpub, app_npub: appNpub },
            actor: member,
            membership: { role: member?.role },
            permissions: isAdmin ? ['workspace.manage', 'workspace.read'] : ['workspace.read'],
          };
        }
        if (method === 'GET' && sub === '/members') {
          // Server-computed CURRENT effective workspace.manage per listed actor (mock grant engine: owner + Admins group).
          const admins = state.groups.find((g) => g.id === ADMINS_GROUP)?.effective_members || [];
          return {
            members: state.members.map((m) => ({
              ...m,
              ...(state.authorityProjection
                ? { authority: { can_manage_workspace: m.role === 'owner' || admins.some((em) => em.actor_id === m.actor_id) } }
                : {}),
            })),
          };
        }
        if (method === 'GET' && sub === '/groups') {
          if (!isAdmin) {
            const e = new Error('Forbidden: admin only');
            e.status = 403;
            throw e;
          }
          return { groups: state.groups };
        }
        if (method === 'GET' && sub === '/scopes') return { scopes: state.scopes };

        if (method === 'GET' && sub === '/sync') {
          const cursor = query.get('cursor');
          const visibleChannels = state.channels.filter((c) => canReadChannel(c.id));
          if (!cursor) {
            return {
              mode: 'snapshot',
              from_cursor: null,
              through_cursor: `delta:${state.syncVersion}`,
              next_cursor: 'snap:page2',
              has_more: true,
              full_snapshot: true,
              snapshot_complete: false,
              refreshed: { directory: true },
              scopes: state.scopes,
              channels: visibleChannels,
              channel_bundles: [
                {
                  channel_id: CH_PROJECT,
                  threads: state.threads.get(CH_PROJECT) || [],
                  messages: (state.messages.get(CH_PROJECT) || []).slice(0, 1),
                  audio_notes: state.audioNotes.get(CH_PROJECT) || [],
                  tasks: state.tasks.get(CH_PROJECT) || [],
                  task_comments: [],
                  docs: [],
                  files: [],
                },
                {
                  channel_id: CH_PRIVATE_NOTES,
                  threads: [],
                  messages: state.messages.get(CH_PRIVATE_NOTES) || [],
                  audio_notes: [],
                  tasks: [],
                  task_comments: [],
                  docs: [],
                  files: [],
                },
                {
                  channel_id: CH_AGENT_ONLY,
                  threads: [],
                  messages: state.messages.get(CH_AGENT_ONLY) || [],
                  audio_notes: [],
                  tasks: [],
                  task_comments: [],
                  docs: [],
                  files: [],
                },
                {
                  channel_id: CH_OTHER_DM,
                  threads: [],
                  messages: state.messages.get(CH_OTHER_DM) || [],
                  audio_notes: [],
                  tasks: [],
                  task_comments: [],
                  docs: [],
                  files: [],
                },
              ],
              tombstones: [],
            };
          }
          if (cursor === 'snap:page2') {
            return {
              mode: 'snapshot',
              from_cursor: 'snap:page2',
              through_cursor: `delta:${state.syncVersion}`,
              next_cursor: null,
              has_more: false,
              full_snapshot: true,
              snapshot_complete: true,
              refreshed: { directory: true },
              scopes: state.scopes,
              channels: visibleChannels,
              channel_bundles: [
                {
                  channel_id: CH_PROJECT,
                  threads: [],
                  messages: (state.messages.get(CH_PROJECT) || []).slice(1, 2),
                  audio_notes: [],
                  tasks: [],
                  task_comments: [],
                  docs: state.docs.get(CH_PROJECT) || [],
                  files: Array.from(state.files.values()).filter((f) => f.channel_id === CH_PROJECT),
                },
                {
                  channel_id: CH_REVOKED,
                  threads: [],
                  messages: state.messages.get(CH_REVOKED) || [],
                  audio_notes: [],
                  tasks: [],
                  task_comments: [],
                  docs: [],
                  files: [],
                },
              ],
              tombstones: [],
            };
          }
          const nextDelta = state.deltaQueue.shift();
          const newCursor = `delta:${++state.syncVersion}`;
          return {
            mode: 'delta',
            from_cursor: cursor,
            through_cursor: newCursor,
            next_cursor: newCursor,
            has_more: state.deltaQueue.length > 0,
            full_snapshot: false,
            refreshed: nextDelta?.refreshed || { directory: true },
            scopes: state.scopes,
            channels: visibleChannels,
            channel_bundles: nextDelta?.channel_bundles || [],
            tombstones: nextDelta?.tombstones || [],
          };
        }

        const scopeCh = sub.match(/^\/scopes\/([^/]+)\/channels$/);
        if (scopeCh && method === 'GET') {
          return { channels: state.channels.filter((c) => c.scope_id === scopeCh[1] && canReadChannel(c.id)) };
        }
        if (scopeCh && method === 'POST') {
          const id = crypto.randomUUID();
          const ch = {
            id,
            workspace_id: workspaceId,
            scope_id: scopeCh[1],
            name: body.name,
            kind: body.kind || 'channel',
            description: body.description || '',
            metadata: body.metadata || {},
          };
          state.channels.push(ch);
          state.grants.set(
            id,
            (body.grants || []).map((g) => ({
              principal_type: g.principal_type,
              principal_id: g.principal_id,
              permissions: Array.isArray(g.permissions) ? g.permissions : expandAccessLevel(g.access_level),
            })),
          );
          return { channel: ch };
        }

        const chMatch = sub.match(/^\/channels\/([^/]+)$/);
        if (chMatch && method === 'GET') {
          if (!canReadChannel(chMatch[1])) {
            const e = new Error('Forbidden');
            e.status = 403;
            throw e;
          }
          const ch = state.channels.find((c) => c.id === chMatch[1]);
          if (!ch) {
            const e = new Error('Not found');
            e.status = 404;
            throw e;
          }
          return { channel: ch };
        }

        const grantMatch = sub.match(/^\/channels\/([^/]+)\/grants$/);
        if (grantMatch && method === 'GET') {
          const ch = state.channels.find((c) => c.id === grantMatch[1]);
          if (!isAdmin && ch?.metadata?.system_key === M.SYSTEM_KEY_ASSIGNMENT) {
            const e = new Error('Forbidden: grant inventory restricted');
            e.status = 403;
            throw e;
          }
          if (!canReadChannel(grantMatch[1])) {
            const e = new Error('Forbidden');
            e.status = 403;
            throw e;
          }
          return { grants: state.grants.get(grantMatch[1]) || [], complete: true, partial: false, has_more: false };
        }

        const chFiles = sub.match(/^\/channels\/([^/]+)\/files$/);
        if (chFiles && method === 'GET') {
          if (!canReadChannel(chFiles[1])) {
            const e = new Error('Forbidden');
            e.status = 403;
            throw e;
          }
          return { files: Array.from(state.files.values()).filter((f) => f.channel_id === chFiles[1]) };
        }

        const fileVersions = sub.match(/^\/files\/([^/]+)\/versions$/);
        if (fileVersions && method === 'GET') {
          const f = state.files.get(fileVersions[1]);
          if (!f || !canReadChannel(f.channel_id)) {
            const e = new Error('Forbidden');
            e.status = 403;
            throw e;
          }
          return { versions: Array.from(f.versionMeta?.values() || []) };
        }

        const msgById = sub.match(/^\/messages\/([^/]+)$/);
        if (msgById && method === 'GET') {
          for (const [cid, list] of state.messages.entries()) {
            const found = list.find((m) => m.id === msgById[1]);
            if (found) {
              if (!canReadChannel(cid)) {
                const e = new Error('Forbidden');
                e.status = 403;
                throw e;
              }
              return { message: found };
            }
          }
          const e = new Error('Not found');
          e.status = 404;
          throw e;
        }

        const taskById = sub.match(/^\/tasks\/([^/]+)$/);
        if (taskById && method === 'GET') {
          for (const list of state.tasks.values()) {
            const found = list.find((t) => t.id === taskById[1]);
            if (found) return { task: found };
          }
          const e = new Error('Not found');
          e.status = 404;
          throw e;
        }

        throw new Error(`Unhandled mock route: ${method} ${sub}`);
      },

      async uploadObject({ bytes, contentType }) {
        const id = crypto.randomUUID();
        state.objects.set(id, { bytes: Buffer.from(bytes), contentType, sha256_hex: M.sha256Hex(bytes) });
        return id;
      },

      async createFile(channelId, body) {
        if (!canWriteChannel(channelId)) {
          const e = new Error('Forbidden');
          e.status = 403;
          throw e;
        }
        const obj = state.objects.get(body.storage_object_id);
        const id = crypto.randomUUID();
        const verId = crypto.randomUUID();
        const file = {
          id,
          workspace_id: workspaceId,
          channel_id: channelId,
          display_name: body.display_name,
          content_type: obj.contentType,
          current_version_id: verId,
          created_by_actor_id: member.actor_id,
          updated_by_actor_id: member.actor_id,
          object: { storage_object: { size_bytes: obj.bytes.byteLength, sha256_hex: obj.sha256_hex, is_public: false } },
          metadata: body.metadata || {},
          versions: new Map([[verId, obj.bytes]]),
          versionMeta: new Map([[verId, { id: verId, created_by_actor_id: member.actor_id, created_by_actor_npub: member.npub }]]),
        };
        state.files.set(id, file);
        return { file };
      },

      async getFile(fileId) {
        const f = state.files.get(fileId);
        if (!f) {
          const e = new Error('Not found');
          e.status = 404;
          throw e;
        }
        if (!canReadChannel(f.channel_id)) {
          const e = new Error('Forbidden');
          e.status = 403;
          throw e;
        }
        return { file: f };
      },

      async createVersion(fileId, body) {
        const f = state.files.get(fileId);
        if (!f) {
          const e = new Error('Not found');
          e.status = 404;
          throw e;
        }
        if (!canWriteChannel(f.channel_id)) {
          const e = new Error('Forbidden');
          e.status = 403;
          e.code = 'permission_denied';
          throw e;
        }
        if (f.current_version_id !== body.base_version_id) {
          const e = new Error('File base_version_id is stale');
          e.status = 409;
          e.code = 'stale_base_version';
          throw e;
        }
        const obj = state.objects.get(body.storage_object_id);
        const verId = crypto.randomUUID();
        f.current_version_id = verId;
        f.updated_by_actor_id = member.actor_id;
        f.object = { storage_object: { size_bytes: obj.bytes.byteLength, sha256_hex: obj.sha256_hex, is_public: false } };
        f.versions.set(verId, obj.bytes);
        f.versionMeta.set(verId, { id: verId, created_by_actor_id: member.actor_id, created_by_actor_npub: member.npub });
        return { version: { id: verId } };
      },

      async readVersionBytes(fileId, versionId) {
        const f = state.files.get(fileId);
        const bytes = f?.versions?.get(versionId);
        if (!bytes || !canReadChannel(f.channel_id)) {
          const e = new Error('Version unavailable');
          e.status = 404;
          throw e;
        }
        return bytes;
      },
    };
  }

  return { state, clientAs };
}

function makeStructuredExtractor() {
  return (batch) => {
    const has = (type, id) => batch.sources.some((s) => s.source_type === type && s.record_id === id);
    const topics = [];
    const cosEvidence = [];
    if (has('message', MSG_COS_1_ID)) {
      cosEvidence.push({ source_type: 'message', record_id: MSG_COS_1_ID, channel_id: CH_PROJECT, explanation: 'Discusses Chief of Staff ontology indexing.', score: 0.94 });
    }
    if (has('message', MSG_COS_NEW_ID)) {
      cosEvidence.push({ source_type: 'message', record_id: MSG_COS_NEW_ID, channel_id: CH_PROJECT, explanation: 'New delta message added after snapshot head.', score: 0.91 });
    }
    if (has('thread', THREAD_COS_ID)) {
      cosEvidence.push({ source_type: 'thread', record_id: THREAD_COS_ID, channel_id: CH_PROJECT, explanation: 'Design thread accumulating Chief of Staff decisions.', score: 0.95 });
    }
    if (has('task', TASK_COS_ID)) {
      cosEvidence.push({ source_type: 'task', record_id: TASK_COS_ID, channel_id: CH_PROJECT, explanation: 'Implementation task for Chief of Staff.', score: 0.97 });
    }
    if (has('audio_note', AUDIO_COS_ID)) {
      cosEvidence.push({ source_type: 'audio_note', record_id: AUDIO_COS_ID, channel_id: CH_PROJECT, explanation: 'Voice transcript covering mobile release.', score: 0.86 });
    }
    if (has('file', FILE_TEXT_ID)) {
      cosEvidence.push({ source_type: 'file', record_id: FILE_TEXT_ID, channel_id: CH_PROJECT, explanation: 'Release checklist referencing Chief of Staff steps.', score: 0.81 });
    }
    if (cosEvidence.length) {
      topics.push({
        name: 'Chief of Staff',
        kind: 'feature',
        aliases: ['Workspace Chief of Staff'],
        description: 'Workspace Chief of Staff assignment, DM shortcut, and living topic ontology.',
        evidence: cosEvidence,
      });
    }
    if (has('message', MSG_CANOPY_ID)) {
      topics.push({
        name: 'Canopy Relay Sync',
        kind: 'project',
        aliases: [],
        description: 'Relay synchronization architecture work in Canopy.',
        evidence: [{ source_type: 'message', record_id: MSG_CANOPY_ID, channel_id: CH_REVOKED, explanation: 'Canopy relay sync milestone.', score: 0.9 }],
      });
    }
    if (has('doc', DOC_ARCH_ID)) {
      topics.push({
        name: 'Platform Suite Architecture',
        kind: 'architecture',
        aliases: ['Storage Architecture'],
        description: 'System boundaries between client, agent runtime, and storage.',
        evidence: [{ source_type: 'doc', record_id: DOC_ARCH_ID, channel_id: CH_PROJECT, explanation: 'Architecture specification.', score: 0.92 }],
      });
    }
    return { schema: 'ambulando.chief_of_staff.extraction_proposals', schema_version: 1, topics };
  };
}

describe('Chief of Staff Engine — ACL, provenance, dual auth, bounded batch queue, epoch isolation, and pipeline schema', () => {
  test('ordinary member reads authoritative assignment without /groups or /grants, while rejecting forged non-manager metadata candidates', async () => {
    const tower = createMockTower();
    const ownerClient = tower.clientAs(OWNER_NPUB);
    const memberClient = tower.clientAs(MEMBER_NPUB);

    await expect(
      Sync.setupAssignmentChannel(memberClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, scopeId: SCOPE_HOME }),
    ).rejects.toThrow(/workspace\.manage/);

    // Forged candidate planted by non-manager member claiming updated_by_npub = OWNER_NPUB must FAIL provenance!
    const forgedCh = (
      await memberClient.request('POST', `/api/v4/flightdeck-pg/workspaces/${WS_A}/scopes/${SCOPE_HOME}/channels`, {
        name: 'Forged Chief of Staff',
        kind: 'channel',
        metadata: M.assignmentChannelMetadata(WS_A, APP_NPUB),
        grants: [{ principal_type: 'group', principal_id: WORKSPACE_GROUP, access_level: 'contribute' }],
      })
    ).channel;
    const forgedDoc = M.validateAssignment({
      schema: M.SCHEMA_ASSIGNMENT,
      schema_version: 1,
      workspace_id: WS_A,
      workspace_owner_npub: OWNER_NPUB,
      app_npub: APP_NPUB,
      assignment_revision: 1,
      assigned_actor_id: AGENT_B_ID,
      assigned_npub: AGENT_B_NPUB,
      assigned_display_name: 'Agent Beta',
      previous_assigned_npub: null,
      updated_by_actor_id: OWNER_ID,
      updated_by_npub: OWNER_NPUB,
      updated_at: new Date().toISOString(),
    });
    const forgedObj = await memberClient.uploadObject({ bytes: M.encodeEnvelope(forgedDoc), contentType: M.MIME_ASSIGNMENT });
    await memberClient.createFile(forgedCh.id, {
      storage_object_id: forgedObj,
      display_name: M.FILE_NAME_ASSIGNMENT,
      metadata: M.assignmentFileMetadata(WS_A, APP_NPUB),
    });

    await expect(
      Sync.readAssignment(memberClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB }),
    ).rejects.toThrow(/not authored by a verified workspace manager/);

    // Clean up forged channel and test legitimate owner setup + ordinary member read
    tower.state.channels = tower.state.channels.filter((c) => c.id !== forgedCh.id);

    const initial = await Sync.setupAssignmentChannel(ownerClient, {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      scopeId: SCOPE_HOME,
    });
    const assigned = await Sync.setAssignment(ownerClient, {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      agentNpub: AGENT_A_NPUB,
      expectedRevision: 0,
    });

    const memberRead = await Sync.readAssignment(memberClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB });
    expect(memberRead.state).toBe('ready');
    expect(memberRead.aclVerified).toBe(false);
    expect(memberRead.assignment.assigned_npub).toBe(AGENT_A_NPUB);

    await expect(
      memberClient.createVersion(initial.file.id, {
        base_version_id: assigned.versionId,
        storage_object_id: await memberClient.uploadObject({ bytes: Buffer.from('{}'), contentType: M.MIME_ASSIGNMENT }),
      }),
    ).rejects.toThrow(/Forbidden/);
  });

  test('verifies privileged writer group structurally instead of trusting mutable group name', () => {
    const members = [
      { actor_id: OWNER_ID, npub: OWNER_NPUB, role: 'owner' },
      { actor_id: MEMBER_ID, npub: MEMBER_NPUB, role: 'member' },
    ];
    const poisonedAdminsGroup = {
      id: ADMINS_GROUP,
      name: 'Admins',
      kind: 'system',
      effective_members: [
        { actor_id: OWNER_ID, npub: OWNER_NPUB },
        { actor_id: MEMBER_ID, npub: MEMBER_NPUB },
      ],
    };
    expect(() =>
      M.verifyAssignmentChannelGrants(
        [{ principal_type: 'group', principal_id: ADMINS_GROUP, permissions: ['channel.read', 'file.read', 'file.write'] }],
        members,
        [poisonedAdminsGroup],
        OWNER_NPUB,
      ),
    ).toThrow(/Non-manager or unverified group/);

    const renamedManagerGroup = {
      id: ADMINS_GROUP,
      name: 'Workspace Stewards',
      kind: 'system',
      effective_members: [{ actor_id: OWNER_ID, npub: OWNER_NPUB }],
    };
    const ok = M.verifyAssignmentChannelGrants(
      [{ principal_type: 'group', principal_id: ADMINS_GROUP, permissions: ['channel.read', 'file.read', 'file.write'] }],
      members,
      [renamedManagerGroup],
      OWNER_NPUB,
    );
    expect(ok.hasManagerWriter).toBe(true);
  });

  test('enforces dual owner+agent source authorization, /sync crash recovery, delta edits/tombstones/revocation, and durable corrections', async () => {
    const tower = createMockTower();
    const ownerClient = tower.clientAs(OWNER_NPUB);
    const agentClient = tower.clientAs(AGENT_A_NPUB);

    await Sync.setupAssignmentChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, scopeId: SCOPE_HOME });
    const assigned = await Sync.setAssignment(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB });
    const epoch = assigned.assignment.assignment_revision;
    await Sync.setupCohortChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB, scopeId: SCOPE_HOME, assignmentEpoch: epoch });

    const extractor = makeStructuredExtractor();
    const store = new LocalOntologyStore(':memory:');
    try {
      let crashed = false;
      await expect(
        Sync.runIncrementalSync(agentClient, store, {
          workspaceId: WS_A,
          ownerNpub: OWNER_NPUB,
          appNpub: APP_NPUB,
          agentNpub: AGENT_A_NPUB,
          extractor,
          onAfterPage: () => {
            if (!crashed) {
              crashed = true;
              throw new Error('simulated_crash_after_page_1');
            }
          },
        }),
      ).rejects.toThrow('simulated_crash_after_page_1');
      expect(store.getSyncState().phase).toBe('snapshot');
      expect(store.getSyncState().snapshot_cursor).toBe('snap:page2');

      const run1 = await Sync.runIncrementalSync(agentClient, store, {
        workspaceId: WS_A,
        ownerNpub: OWNER_NPUB,
        appNpub: APP_NPUB,
        agentNpub: AGENT_A_NPUB,
        extractor,
      });
      expect(run1.ok).toBe(true);
      expect(store.getSyncState().phase).toBe('delta');
      expect(store.getSyncState().snapshot_complete).toBe(true);

      // Dual authorization: Notes to Self, agent-only channel, and agent+member DM are NEVER indexed or leaked!
      const allText = store.listAllSources().map((s) => `${s.title} ${s.text_body}`).join('\n');
      expect(allText).not.toContain('ZEBRA999');
      expect(allText).not.toContain('XYLOPHONE777');
      expect(allText).not.toContain('QUARTZ888');
      expect(JSON.stringify(run1.coverage)).not.toContain('Secret Agent Ops');
      expect(JSON.stringify(run1.coverage)).not.toContain(CH_AGENT_ONLY);

      // Thread accumulated BOTH pages' messages AND preserved real row_version=5
      const threadSrc = store.getSource('thread', THREAD_COS_ID);
      expect(threadSrc).toBeDefined();
      expect(threadSrc.revision).toBe('5');
      expect(threadSrc.text_body).toContain('First page message');
      expect(threadSrc.text_body).toContain('Second page message');

      // Delta: new message, edited message, task tombstone, channel revocation
      tower.state.revokedChannels.add(CH_REVOKED);
      tower.state.deltaQueue.push({
        refreshed: { directory: true },
        channel_bundles: [
          {
            channel_id: CH_PROJECT,
            threads: [],
            messages: [
              {
                id: MSG_COS_1_ID,
                workspace_id: WS_A,
                channel_id: CH_PROJECT,
                thread_id: THREAD_COS_ID,
                body: 'EDITED first page message: Chief of Staff ontology indexing updated.',
                row_version: 6,
                created_at: '2026-11-28T01:00:00.000Z',
                updated_at: '2026-11-28T02:00:00.000Z',
              },
              {
                id: MSG_COS_NEW_ID,
                workspace_id: WS_A,
                channel_id: CH_PROJECT,
                thread_id: THREAD_COS_ID,
                body: 'BRAND NEW message arriving after snapshot head.',
                row_version: 1,
                created_at: '2026-11-28T02:05:00.000Z',
                updated_at: '2026-11-28T02:05:00.000Z',
              },
            ],
            tasks: [],
            docs: [],
            files: [],
            audio_notes: [],
          },
        ],
        tombstones: [{ entity_type: 'task', entity_id: TASK_COS_ID }],
      });

      const cosId = M.stableTopicId('Chief of Staff');
      await Sync.appendCorrection(ownerClient, {
        workspaceId: WS_A,
        ownerNpub: OWNER_NPUB,
        appNpub: APP_NPUB,
        agentNpub: AGENT_A_NPUB,
        assignmentEpoch: epoch,
        correction: { kind: 'rename', topic_id: cosId, name: 'Workspace Chief of Staff Program', created_by_npub: OWNER_NPUB },
      });

      const run2 = await Sync.runIncrementalSync(agentClient, store, {
        workspaceId: WS_A,
        ownerNpub: OWNER_NPUB,
        appNpub: APP_NPUB,
        agentNpub: AGENT_A_NPUB,
        extractor,
      });
      expect(run2.ok).toBe(true);

      expect(store.getSource('message', MSG_COS_1_ID).text_body).toContain('EDITED first page message');
      expect(store.getSource('message', MSG_COS_NEW_ID).text_body).toContain('BRAND NEW message');
      const updatedThread = store.getSource('thread', THREAD_COS_ID);
      expect(updatedThread.revision).toBe('6');
      expect(updatedThread.text_body).toContain('Second page message');
      expect(updatedThread.text_body).toContain('BRAND NEW message');
      expect(store.getSource('task', TASK_COS_ID).status).toBe('deleted');
      expect(store.getSource('task', TASK_COS_ID).text_body).toBe('');
      expect(store.lookup('Canopy Relay Sync').length).toBe(0);

      const renamed = store.lookup('Workspace Chief of Staff Program').find((t) => t.id === cosId);
      expect(renamed).toBeDefined();
      expect(renamed.evidence.some((e) => e.record_id === MSG_COS_NEW_ID)).toBe(true);
      expect(renamed.evidence.some((e) => e.record_id === TASK_COS_ID)).toBe(false);
    } finally {
      store.close();
    }
  });

  test('drains older unprocessed sources across bounded batches and binds proposals strictly to exact prepared batch', async () => {
    const tower = createMockTower();
    const ownerClient = tower.clientAs(OWNER_NPUB);
    const agentClient = tower.clientAs(AGENT_A_NPUB);

    await Sync.setupAssignmentChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, scopeId: SCOPE_HOME });
    const epoch = (await Sync.setAssignment(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB })).assignment.assignment_revision;
    await Sync.setupCohortChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB, scopeId: SCOPE_HOME, assignmentEpoch: epoch });

    const seenBatches = [];
    const store = new LocalOntologyStore(':memory:');
    try {
      const extractor = makeStructuredExtractor();
      for (let i = 0; i < 6; i++) {
        const res = await Sync.runIncrementalSync(agentClient, store, {
          workspaceId: WS_A,
          ownerNpub: OWNER_NPUB,
          appNpub: APP_NPUB,
          agentNpub: AGENT_A_NPUB,
          limits: { maxBatchSources: 2 },
          extractor: (batch) => {
            seenBatches.push(batch.sources.map((s) => `${s.source_type}:${s.record_id}`));
            return extractor(batch);
          },
        });
        expect(res.ok).toBe(true);
        if (res.pendingExtractionCount === 0) break;
      }
      // Multiple non-overlapping bounded batches drained ALL active sources down to 0 pending!
      expect(seenBatches.length).toBeGreaterThanOrEqual(3);
      expect(store.countPendingExtractionSources()).toBe(0);
      const flat = seenBatches.flat();
      expect(new Set(flat).size).toBe(flat.length);
      expect(flat).toContain(`message:${MSG_COS_1_ID}`);
      expect(flat).toContain(`doc:${DOC_ARCH_ID}`);
    } finally {
      store.close();
    }

    // Strict prepared-batch binding rejects active sources outside the prepared batch
    const activeSources = [
      { source_type: 'message', record_id: MSG_COS_1_ID, channel_id: CH_PROJECT, title: 'Cos', text_body: 'Chief of Staff', content_hash: 'h1', revision: '2', updated_at: '2026-11-28T01:00:00.000Z' },
      { source_type: 'doc', record_id: DOC_ARCH_ID, channel_id: CH_PROJECT, title: 'Arch', text_body: 'Architecture', content_hash: 'h2', revision: '4', updated_at: '2026-11-28T01:20:00.000Z' },
    ];
    const merged = mergeStructuredExtraction(
      {
        topics: [
          {
            name: 'Chief of Staff',
            kind: 'feature',
            aliases: [],
            description: 'Valid topic',
            evidence: [
              { source_type: 'message', record_id: MSG_COS_1_ID, channel_id: CH_PROJECT, explanation: 'Grounded in batch.', score: 0.9 },
              { source_type: 'doc', record_id: DOC_ARCH_ID, channel_id: CH_PROJECT, explanation: 'Not in prepared batch.', score: 0.92 },
              { source_type: 'message', record_id: '99999999-9999-4999-8999-999999999999', channel_id: CH_PROJECT, explanation: 'Hallucinated ID.', score: 0.95 },
            ],
          },
        ],
      },
      activeSources,
      null,
      { batchSources: [activeSources[0]] },
    );
    expect(merged.evidence.length).toBe(1);
    expect(merged.rejectedProposals.some((r) => r.reason === 'source_not_in_prepared_batch')).toBe(true);
    expect(merged.rejectedProposals.some((r) => r.reason === 'unauthorized_or_unknown_source_id')).toBe(true);
  });

  test('fails closed on listAll pagination cap, isolates epochs, guards stale assignment, schedules hourly pipeline, and validates pipeline JSON schema', async () => {
    let page = 0;
    const pagedClient = {
      async request() {
        page++;
        return { items: [{ id: `row-${page}` }], next_cursor: `cursor-${page}`, has_more: true };
      },
    };
    await expect(Sync.listAll(pagedClient, '/test', 'items', { maxPages: 3 })).rejects.toThrow(Sync.IncompletePaginationError);

    const tower = createMockTower();
    const ownerClient = tower.clientAs(OWNER_NPUB);
    const agentAClient = tower.clientAs(AGENT_A_NPUB);

    await Sync.setupAssignmentChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, scopeId: SCOPE_HOME });
    const epoch1 = (await Sync.setAssignment(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB })).assignment.assignment_revision;
    await Sync.setupCohortChannel(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_A_NPUB, scopeId: SCOPE_HOME, assignmentEpoch: epoch1 });

    const store = new LocalOntologyStore(':memory:');
    try {
      const abort = await Sync.runIncrementalSync(agentAClient, store, {
        workspaceId: WS_A,
        ownerNpub: OWNER_NPUB,
        appNpub: APP_NPUB,
        agentNpub: AGENT_A_NPUB,
        extractor: makeStructuredExtractor(),
        onBeforePublish: async () => {
          await Sync.setAssignment(ownerClient, { workspaceId: WS_A, ownerNpub: OWNER_NPUB, appNpub: APP_NPUB, agentNpub: AGENT_B_NPUB });
        },
      });
      expect(abort.ok).toBe(false);
      expect(abort.aborted).toBe('stale_assignment');
    } finally {
      store.close();
    }

    const epoch2Cohort = await Sync.setupCohortChannel(ownerClient, {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      agentNpub: AGENT_B_NPUB,
      scopeId: SCOPE_HOME,
      assignmentEpoch: 2,
    });
    await expect(agentAClient.getFile(epoch2Cohort.files.ontology.id)).rejects.toThrow(/Forbidden/);

    const jobs = [];
    const schedulerClient = {
      async listJobs() { return jobs.map((j) => ({ ...j })); },
      async createJob(input) { const job = { id: `job_${jobs.length + 1}`, userNpub: OWNER_NPUB, ...input }; jobs.push(job); return job; },
      async updateJob(id, patch) { const j = jobs.find((x) => x.id === id); Object.assign(j, patch); return j; },
    };
    const res = await reconcileSchedule(schedulerClient, {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      towerUrl: 'https://tower.example.invalid',
      assignedAgentNpub: AGENT_A_NPUB,
      subscriptionId: SUBSCRIPTION_ID,
      workingDirectory: '/workspace/project',
      scriptPath: '/workspace/project/scripts/chief-of-staff/cli.mjs',
    });
    expect(res.schedule_state).toBe('scheduled');
    expect(jobs[0].actionType).toBe('pipeline');
    expect(jobs[0].pipelineDefinitionId).toBe(DEFAULT_PIPELINE_ID);
    expect(jobs[0].cronExpression).toBe(DEFAULT_CRON_EXPRESSION);
    expect(jobs[0].cronExpression).toBe('0 * * * *');
    expect(jobs[0].flightdeckContext).toEqual({ ownerNpub: OWNER_NPUB, subscriptionId: SUBSCRIPTION_ID, workspaceId: WS_A });

    // The subscription a reconcile may use comes only from the authoritative assignment's binding for its agent.
    const bindingFor = (agentNpub, subscriptionId = SUBSCRIPTION_ID) => ({
      autopilot_origin: 'https://autopilot.example.invalid',
      reconcile_pipeline_id: 'chief-of-staff-reconcile.v1',
      owner_npub: OWNER_NPUB,
      workspace_id: WS_A,
      agent_npub: agentNpub,
      subscription_id: subscriptionId,
    });
    const assignedA = { assigned_npub: AGENT_A_NPUB, runtime_binding: bindingFor(AGENT_A_NPUB) };
    expect(resolveBoundSubscription(assignedA)).toEqual({ subscriptionId: SUBSCRIPTION_ID, reason: null });
    expect(resolveBoundSubscription(assignedA, SUBSCRIPTION_ID.toUpperCase()).subscriptionId).toBe(SUBSCRIPTION_ID);
    expect(resolveBoundSubscription(assignedA, '66666666-6666-4666-8666-666666666666')).toEqual({ subscriptionId: null, reason: 'subscription_mismatch' });
    // Reassigned to B while the binding still names A, or B without any binding: old subscription is never used.
    expect(resolveBoundSubscription({ assigned_npub: AGENT_B_NPUB, runtime_binding: bindingFor(AGENT_A_NPUB) }, SUBSCRIPTION_ID).subscriptionId).toBeNull();
    expect(resolveBoundSubscription({ assigned_npub: AGENT_B_NPUB, runtime_binding: null }, SUBSCRIPTION_ID).reason).toBe('no_runtime_binding_for_assigned_agent');
    expect(resolveBoundSubscription({ assigned_npub: null, runtime_binding: null }, SUBSCRIPTION_ID).reason).toBe('unassigned');

    // Reconcile after reassignment to B without B's own binding: disables A's job, creates nothing, honest pending.
    const reassignedNoBinding = await reconcileSchedule(schedulerClient, {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      towerUrl: 'https://tower.example.invalid',
      assignedAgentNpub: AGENT_B_NPUB,
      subscriptionId: resolveBoundSubscription({ assigned_npub: AGENT_B_NPUB, runtime_binding: bindingFor(AGENT_A_NPUB) }, SUBSCRIPTION_ID).subscriptionId,
      workingDirectory: '/workspace/project',
      scriptPath: '/workspace/project/scripts/chief-of-staff/cli.mjs',
    });
    expect(reassignedNoBinding.schedule_state).toBe('pending');
    expect(reassignedNoBinding.disabled_job_ids).toEqual(['job_1']);
    expect(jobs.length).toBe(1);
    expect(jobs[0].enabled).toBe(false);

    // B with its own binding: exactly one new job bound to B's subscription; rerun is idempotent.
    const B_SUBSCRIPTION = '66666666-6666-4666-8666-666666666666';
    const bOpts = {
      workspaceId: WS_A,
      ownerNpub: OWNER_NPUB,
      appNpub: APP_NPUB,
      towerUrl: 'https://tower.example.invalid',
      assignedAgentNpub: AGENT_B_NPUB,
      subscriptionId: resolveBoundSubscription({ assigned_npub: AGENT_B_NPUB, runtime_binding: bindingFor(AGENT_B_NPUB, B_SUBSCRIPTION) }).subscriptionId,
      workingDirectory: '/workspace/project',
      scriptPath: '/workspace/project/scripts/chief-of-staff/cli.mjs',
    };
    const bScheduled = await reconcileSchedule(schedulerClient, bOpts);
    expect(bScheduled.created).toBe(true);
    expect(jobs.length).toBe(2);
    expect(jobs[1].botNpub).toBe(AGENT_B_NPUB);
    expect(jobs[1].flightdeckContext.subscriptionId).toBe(B_SUBSCRIPTION);
    const bAgain = await reconcileSchedule(schedulerClient, bOpts);
    expect(bAgain).toMatchObject({ schedule_state: 'scheduled', created: false, updated: false, autopilot_job_id: jobs[1].id });
    expect(jobs.length).toBe(2);

    // Removal: every workspace Chief schedule disabled, none created.
    const removed = await reconcileSchedule(schedulerClient, { ...bOpts, assignedAgentNpub: null, subscriptionId: null });
    expect(removed.schedule_state).toBe('disabled_stale');
    expect(removed.disabled_job_ids).toEqual([jobs[1].id]);
    expect(jobs.every((j) => j.enabled === false)).toBe(true);
    expect(jobs.length).toBe(2);

    // Validate DeclarativePipeline JSON matches Autopilot live runtime syntax ($. paths, input.pick, no {{...}} templates)
    const pipelineRaw = readFileSync(path.join(import.meta.dir, 'chief-of-staff-ontology.pipeline.json'), 'utf8');
    expect(pipelineRaw).not.toContain('{{');
    const pipeline = JSON.parse(pipelineRaw);
    expect(pipeline.name).toBe('chief-of-staff-ontology');
    expect(pipeline.version).toBe(1);
    expect(pipeline.steps.length).toBe(3);
    for (const step of pipeline.steps) {
      expect(step.type).toBe('agent');
      expect(step.assign.startsWith('$.')).toBe(true);
      expect(step.input?.pick).toBeDefined();
      if (step.when) expect(step.when.path.startsWith('$.')).toBe(true);
    }
    expect(pipeline.steps[1].prompt).toContain('UNTRUSTED DATA');

    // Shared v1 golden fixture (byte-identical in core test resources and the Mac repo): exact canonical
    // runtime_binding names, optional ontology_pipeline_id, required UUID subscription_id, unknown field preserved.
    const goldenRaw = readFileSync(path.join(import.meta.dir, 'fixtures', 'assignment-runtime-binding.v1.json'));
    const goldenEnvelope = JSON.parse(goldenRaw.toString('utf8'));
    expect(goldenEnvelope.envelope_version).toBe(1);
    expect(goldenEnvelope.encryption).toEqual({ mode: 'none' });
    const G = goldenEnvelope.payload;
    const goldenContext = { workspaceId: G.workspace_id, ownerNpub: G.workspace_owner_npub, appNpub: G.app_npub };
    const goldenDecoded = M.validateAssignment(M.decodeEnvelope(goldenRaw), goldenContext);
    expect(Object.keys(goldenDecoded.runtime_binding).sort()).toEqual([
      'agent_npub',
      'autopilot_origin',
      'future_option',
      'ontology_pipeline_id',
      'owner_npub',
      'reconcile_pipeline_id',
      'subscription_id',
      'workspace_id',
    ]);
    expect(goldenDecoded.runtime_binding.autopilot_origin).toBe('https://autopilot.example.invalid');
    expect(goldenDecoded.runtime_binding.agent_npub).toBe(G.assigned_npub);
    expect(goldenDecoded.runtime_binding.owner_npub).toBe(G.workspace_owner_npub);
    expect(goldenDecoded.runtime_binding.workspace_id).toBe(G.workspace_id);
    expect(goldenDecoded.runtime_binding.reconcile_pipeline_id).toBe('chief-of-staff-reconcile.v1');
    expect(goldenDecoded.runtime_binding.ontology_pipeline_id).toBe(DEFAULT_PIPELINE_ID);
    expect(goldenDecoded.runtime_binding.subscription_id).toBe('44444444-4444-4444-8444-444444444444');
    expect(goldenDecoded.runtime_binding.future_option).toEqual({ example: true });
    const goldenReencoded = M.decodeEnvelope(M.encodeEnvelope(goldenDecoded));
    expect(goldenReencoded).toEqual(goldenDecoded);
    expect(goldenReencoded).toEqual(G);
    const withBinding = (patch) => ({ ...goldenDecoded, runtime_binding: { ...goldenDecoded.runtime_binding, ...patch } });
    const { ontology_pipeline_id: _omitOntology, ...withoutOntology } = goldenDecoded.runtime_binding;
    expect(M.validateAssignment({ ...goldenDecoded, runtime_binding: withoutOntology }).runtime_binding.ontology_pipeline_id).toBeUndefined();

    // Reject secrets, non-clean origins, missing/invalid subscription, legacy aliases without canonical names, agent mismatch.
    expect(() => M.validateAssignment(withBinding({ token: 'leaked' }))).toThrow(/private or secret field/);
    for (const origin of [
      'http://insecure.example.invalid',
      'https://autopilot.example.invalid/extra',
      'https://user:pass@autopilot.example.invalid',
      'https://autopilot.example.invalid?x=1',
      'https://autopilot.example.invalid#frag',
    ]) {
      expect(() => M.validateAssignment(withBinding({ autopilot_origin: origin }))).toThrow(/clean https:\/\//);
    }
    const { subscription_id: _omitSub, ...withoutSub } = goldenDecoded.runtime_binding;
    expect(() => M.validateAssignment({ ...goldenDecoded, runtime_binding: withoutSub })).toThrow(/subscription_id/);
    expect(() => M.validateAssignment(withBinding({ subscription_id: 'not-a-uuid' }))).toThrow(/subscription_id/);
    const { autopilot_origin: _omitOrigin, agent_npub: _omitAgent, ...legacyBase } = goldenDecoded.runtime_binding;
    expect(() =>
      M.validateAssignment({
        ...goldenDecoded,
        runtime_binding: { ...legacyBase, autopilot_base_url: 'https://autopilot.example.invalid', bot_npub: G.assigned_npub },
      }),
    ).toThrow(/autopilot_origin/);
    expect(() => M.validateAssignment(withBinding({ agent_npub: AGENT_B_NPUB }))).toThrow(/must match assigned_npub/);
    expect(() => M.validateAssignment(goldenDecoded, { ...goldenContext, workspaceId: WS_A })).toThrow(/workspace_id mismatch/);

    // Current-version provenance: only the verified owner identity or strict boolean authority.can_manage_workspace.
    const OWNER_G = { actor_id: G.updated_by_actor_id, npub: G.workspace_owner_npub };
    const DELEGATE_G = { actor_id: G.assigned_actor_id, npub: G.assigned_npub, kind: 'agent' };
    const delegatedFile = { created_by_actor_id: DELEGATE_G.actor_id, updated_by_actor_id: DELEGATE_G.actor_id };
    const delegatedPayload = { ...goldenDecoded, updated_by_actor_id: DELEGATE_G.actor_id, updated_by_npub: DELEGATE_G.npub };
    const provenanceWith = (delegateRow) => () =>
      M.verifyAssignmentProvenance({
        file: delegatedFile,
        members: [{ actor: OWNER_G }, { actor: DELEGATE_G, ...delegateRow }],
        ownerNpub: G.workspace_owner_npub,
        payload: delegatedPayload,
      });
    for (const [label, row] of [
      ['missing (older server)', {}],
      ['false', { authority: { can_manage_workspace: false } }],
      ['string "true"', { authority: { can_manage_workspace: 'true' } }],
      ['number 1', { authority: { can_manage_workspace: 1 } }],
      ['role=agent label', { membership: { role: 'agent' } }],
      ['role=admin label only', { membership: { role: 'admin' }, role: 'admin' }],
      ['self-asserted permissions', { permissions: ['workspace.manage'] }],
    ]) {
      expect(provenanceWith(row), label).toThrow(/not authored by a verified workspace manager/);
    }
    expect(provenanceWith({ authority: { can_manage_workspace: true } })().id).toBe(DELEGATE_G.actor_id);
    // The verified owner identity still passes on an older server without the projection.
    const ownerAuthored = M.verifyAssignmentProvenance({
      file: { created_by_actor_id: OWNER_G.actor_id, updated_by_actor_id: OWNER_G.actor_id },
      members: [{ actor: OWNER_G }, { actor: DELEGATE_G }],
      ownerNpub: G.workspace_owner_npub,
      payload: goldenDecoded,
    });
    expect(ownerAuthored.id).toBe(OWNER_G.actor_id);
    // Current-manager agent: a delegated manager that is ALSO the assigned Chief of Staff must still carry the boolean.
    expect(
      M.verifyAssignmentProvenance({
        file: delegatedFile,
        members: [{ actor: OWNER_G }, { actor: DELEGATE_G, authority: { can_manage_workspace: true } }],
        ownerNpub: G.workspace_owner_npub,
        payload: delegatedPayload,
      }).npub,
    ).toBe(G.assigned_npub);

    // Validate reconcile pipeline JSON schema as well
    const reconcileRaw = readFileSync(path.join(import.meta.dir, 'chief-of-staff-reconcile.pipeline.json'), 'utf8');
    expect(reconcileRaw).not.toContain('{{');
    const reconcileSpec = JSON.parse(reconcileRaw);
    expect(reconcileSpec.name).toBe('chief-of-staff-reconcile');
    expect(reconcileSpec.steps.length).toBe(1);
    expect(reconcileSpec.steps[0].assign).toBe('$.reconciled');
  });
});
