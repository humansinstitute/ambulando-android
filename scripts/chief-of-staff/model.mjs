import { createHash } from 'node:crypto';

export const SYSTEM_KEY_ASSIGNMENT = 'ambulando.chief_of_staff';
export const SYSTEM_KEY_ONTOLOGY = 'ambulando.chief_of_staff.ontology';

export const MIME_ASSIGNMENT = 'application/vnd.ambulando.chief-of-staff-assignment+json';
export const MIME_JOB_STATUS = 'application/vnd.ambulando.chief-of-staff-job-status+json';
export const MIME_ONTOLOGY = 'application/vnd.ambulando.chief-of-staff-ontology+json';
export const MIME_CORRECTIONS = 'application/vnd.ambulando.chief-of-staff-corrections+json';

export const FILE_NAME_ASSIGNMENT = 'Chief of Staff.assignment.json';
export const FILE_NAME_JOB_STATUS = 'Chief of Staff.job-status.json';
export const FILE_NAME_ONTOLOGY = 'Chief of Staff.ontology.json';
export const FILE_NAME_CORRECTIONS = 'Chief of Staff.corrections.json';

export const SCHEMA_ASSIGNMENT = 'ambulando.chief_of_staff.assignment';
export const SCHEMA_JOB_STATUS = 'ambulando.chief_of_staff.job_status';
export const SCHEMA_ONTOLOGY = 'ambulando.chief_of_staff.ontology';
export const SCHEMA_CORRECTIONS = 'ambulando.chief_of_staff.corrections';

export const MAX_BYTES = 4 * 1024 * 1024;
export const SCHEDULE_STATES = new Set([
  'unconfigured',
  'pending',
  'scheduled',
  'running',
  'healthy',
  'error',
  'disabled_stale',
]);
export const SOURCE_TYPES = new Set([
  'message',
  'thread',
  'audio_note',
  'task',
  'doc',
  'file',
]);
export const EVIDENCE_STATUSES = new Set(['active', 'stale', 'deleted', 'inaccessible']);
export const CORRECTION_KINDS = new Set([
  'rename',
  'set_description',
  'set_aliases',
  'set_kind',
  'merge_topics',
  'reject_evidence',
  'pin_evidence',
  'archive_topic',
]);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const NPUB_RE = /^npub1[023456789acdefghjklmnpqrstuvwxyz]{58}$/;
// Autopilot pipeline definition IDs, same pattern as Android `ChiefOfStaffPipelineContract`.
const PIPELINE_ID_RE = /^[A-Za-z0-9._-]{1,128}$/;
const ISO_RE = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/;

export const isObject = (v) => Boolean(v && typeof v === 'object' && !Array.isArray(v));
export const isUuid = (v) => typeof v === 'string' && UUID_RE.test(v);
export const isNpub = (v) => typeof v === 'string' && NPUB_RE.test(v);
export const isIso = (v) => typeof v === 'string' && ISO_RE.test(v) && Number.isFinite(Date.parse(v));

export const memberActorId = (m) => m?.actor_id ?? m?.actor?.actor_id ?? m?.actor?.id ?? m?.id;
export const memberNpub = (m) => m?.npub ?? m?.actor?.npub;

/**
 * A listed `/members` row is a current workspace manager only when it is the verified workspace owner identity or
 * NStack's server-computed `authority.can_manage_workspace` is exactly boolean `true`. Membership role labels,
 * display names, kinds and self-asserted permission arrays are never authority; an older server that omits the
 * projection therefore fails closed for delegated managers.
 */
export function isVerifiedWorkspaceManager(m, ownerNpub) {
  return (Boolean(ownerNpub) && memberNpub(m) === ownerNpub) || m?.authority?.can_manage_workspace === true;
}

export function sha256Hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonicalJson(value[k])}`)
    .join(',')}}`;
}

export function normalizeSlug(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80);
}

export function stableTopicId(name) {
  const slug = normalizeSlug(name) || 'topic';
  const hash = createHash('sha256').update(`ambulando.topic:${slug}`).digest('hex').slice(0, 12);
  return `topic_${slug.slice(0, 32)}_${hash}`;
}

/**
 * Isolated per-assignment-epoch private cohort identifier.
 * Each assignment epoch gets its own cohort channel so a previously assigned agent
 * never gains access to a newly assigned agent's ontology/status outputs while
 * historical versions remain intact.
 */
export function cohortId(ownerNpub, agentNpub, assignmentEpoch = 1) {
  const epoch = Number.isSafeInteger(assignmentEpoch) && assignmentEpoch >= 1 ? assignmentEpoch : 1;
  return `owner:${ownerNpub}:agent:${agentNpub}:epoch:${epoch}`;
}

export function isActiveRecord(record) {
  return Boolean(
    record &&
      record.record_state !== 'deleted' &&
      record.record_state !== 'archived' &&
      !record.archived_at &&
      !record.deleted_at &&
      !record.access_removed,
  );
}

/**
 * Encryption adapter seam.
 * Encryption is deferred in v1 (mode = "none"); a future client/agent cipher wraps
 * the entire payload (including topic names, aliases, descriptions and associations)
 * without changing record IDs, storage routes or lookup interfaces.
 */
export const PLAINTEXT_ADAPTER = {
  mode: 'none',
  encrypt(payload) {
    return {
      envelope_version: 1,
      encryption: { mode: 'none' },
      payload,
    };
  },
  decrypt(envelope) {
    if (!isObject(envelope) || envelope.envelope_version !== 1 || !isObject(envelope.encryption)) {
      throw new Error('Unsupported Chief of Staff envelope version.');
    }
    if (envelope.encryption.mode !== 'none') {
      throw new Error(`Encrypted Chief of Staff payload (${envelope.encryption.mode}) requires a matching decryption adapter.`);
    }
    if (!isObject(envelope.payload)) {
      throw new Error('Invalid Chief of Staff envelope payload.');
    }
    return envelope.payload;
  },
};

export function encodeEnvelope(payload, adapter = PLAINTEXT_ADAPTER) {
  const env = adapter.encrypt(payload);
  const bytes = Buffer.from(canonicalJson(env), 'utf8');
  if (bytes.byteLength > MAX_BYTES) {
    throw new Error('Chief of Staff document exceeds 4 MiB limit.');
  }
  return bytes;
}

export function decodeEnvelope(bytes, adapter = PLAINTEXT_ADAPTER) {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  if (buf.byteLength > MAX_BYTES) {
    throw new Error('Chief of Staff document exceeds 4 MiB limit.');
  }
  const parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(buf));
  return adapter.decrypt(parsed);
}

// --- Metadata helpers ---

export function assignmentChannelMetadata(workspaceId, appNpub) {
  return {
    system_key: SYSTEM_KEY_ASSIGNMENT,
    chief_of_staff: {
      schema_version: 1,
      kind: 'assignment',
      workspace_id: workspaceId,
      app_npub: appNpub,
    },
  };
}

export function assignmentFileMetadata(workspaceId, appNpub) {
  return {
    chief_of_staff: {
      schema_version: 1,
      kind: 'assignment',
      workspace_id: workspaceId,
      app_npub: appNpub,
    },
  };
}

export function cohortChannelMetadata(workspaceId, appNpub, ownerNpub, agentNpub, assignmentEpoch = 1) {
  return {
    system_key: SYSTEM_KEY_ONTOLOGY,
    chief_of_staff: {
      schema_version: 1,
      kind: 'ontology_cohort',
      cohort_kind: 'owner_agent_private',
      cohort_id: cohortId(ownerNpub, agentNpub, assignmentEpoch),
      assignment_epoch: assignmentEpoch,
      workspace_id: workspaceId,
      app_npub: appNpub,
      owner_npub: ownerNpub,
      agent_npub: agentNpub,
    },
  };
}

export function cohortFileMetadata(kind, workspaceId, appNpub, ownerNpub, agentNpub, assignmentEpoch = 1) {
  return {
    chief_of_staff: {
      schema_version: 1,
      kind,
      cohort_kind: 'owner_agent_private',
      cohort_id: cohortId(ownerNpub, agentNpub, assignmentEpoch),
      assignment_epoch: assignmentEpoch,
      workspace_id: workspaceId,
      app_npub: appNpub,
      owner_npub: ownerNpub,
      agent_npub: agentNpub,
    },
  };
}

export function isAssignmentChannel(channel, workspaceId, appNpub) {
  const m = channel?.metadata?.chief_of_staff;
  return Boolean(
    isActiveRecord(channel) &&
      channel.kind === 'channel' &&
      channel.workspace_id === workspaceId &&
      channel.metadata?.system_key === SYSTEM_KEY_ASSIGNMENT &&
      m?.schema_version === 1 &&
      m?.kind === 'assignment' &&
      m?.workspace_id === workspaceId &&
      m?.app_npub === appNpub,
  );
}

export function isCohortChannel(channel, workspaceId, appNpub, ownerNpub, agentNpub, assignmentEpoch = null) {
  const m = channel?.metadata?.chief_of_staff;
  const epochMatches =
    assignmentEpoch === null
      ? Number.isSafeInteger(m?.assignment_epoch) && m.assignment_epoch >= 1
      : m?.assignment_epoch === assignmentEpoch && m?.cohort_id === cohortId(ownerNpub, agentNpub, assignmentEpoch);
  return Boolean(
    isActiveRecord(channel) &&
      channel.kind === 'channel' &&
      channel.workspace_id === workspaceId &&
      channel.metadata?.system_key === SYSTEM_KEY_ONTOLOGY &&
      m?.schema_version === 1 &&
      m?.kind === 'ontology_cohort' &&
      m?.cohort_kind === 'owner_agent_private' &&
      m?.workspace_id === workspaceId &&
      m?.app_npub === appNpub &&
      m?.owner_npub === ownerNpub &&
      m?.agent_npub === agentNpub &&
      epochMatches,
  );
}

function fileMime(file) {
  return String(file?.content_type || '')
    .split(';')[0]
    .trim()
    .toLowerCase();
}

export function isAssignmentFile(file, workspaceId, appNpub) {
  const m = file?.metadata?.chief_of_staff;
  return Boolean(
    isActiveRecord(file) &&
      file.workspace_id === workspaceId &&
      fileMime(file) === MIME_ASSIGNMENT &&
      m?.schema_version === 1 &&
      m?.kind === 'assignment' &&
      m?.workspace_id === workspaceId &&
      m?.app_npub === appNpub,
  );
}

export function isCohortFile(file, kind, mime, workspaceId, appNpub, ownerNpub, agentNpub, assignmentEpoch) {
  const m = file?.metadata?.chief_of_staff;
  return Boolean(
    isActiveRecord(file) &&
      file.workspace_id === workspaceId &&
      fileMime(file) === mime &&
      m?.schema_version === 1 &&
      m?.kind === kind &&
      m?.cohort_kind === 'owner_agent_private' &&
      m?.assignment_epoch === assignmentEpoch &&
      m?.cohort_id === cohortId(ownerNpub, agentNpub, assignmentEpoch) &&
      m?.workspace_id === workspaceId &&
      m?.app_npub === appNpub &&
      m?.owner_npub === ownerNpub &&
      m?.agent_npub === agentNpub,
  );
}

// --- Explicit-permission ACL verification ---

function grantPrincipalId(g) {
  const p = g?.principal ?? {};
  return p.actor_id ?? p.group_id ?? p.id ?? g?.principal_id;
}

function isActorGrant(g) {
  const p = g?.principal ?? {};
  return (
    (g?.principal_type === 'actor' ||
      (g?.principal_type === 'person' && g?.stored_principal_type === 'actor')) &&
    (p.type === undefined || p.type === 'actor' || p.type === 'person') &&
    (g?.stored_principal_type === undefined || g?.stored_principal_type === 'actor')
  );
}

function isGroupGrant(g) {
  return g?.principal_type === 'group';
}

function hasAllPermissions(g, required) {
  return (
    Array.isArray(g?.permissions) &&
    g.permissions.every((v) => typeof v === 'string') &&
    required.every((perm) => g.permissions.includes(perm))
  );
}

function hasAnyPermission(g, forbidden) {
  return (
    Array.isArray(g?.permissions) &&
    forbidden.some((perm) => g.permissions.includes(perm))
  );
}

export function verifyAssignmentChannelGrants(grants, members, groups, ownerNpub, { verifiedManagerIds = [] } = {}) {
  if (!Array.isArray(grants) || !Array.isArray(members)) {
    throw new Error('Chief of Staff assignment grants could not be verified.');
  }
  const managerActorIds = new Set([
    ...members.filter((m) => isVerifiedWorkspaceManager(m, ownerNpub)).map(memberActorId),
    ...verifiedManagerIds.filter(Boolean),
  ]);
  const groupById = new Map((groups || []).map((g) => [g.id ?? g.group_id, g]));

  /**
   * Verify privileged writer group structurally instead of trusting a mutable display name:
   * - must be a system group (`kind === 'system'` or `group_kind === 'system'`)
   * - must have at least one verified member and EVERY effective member must be a workspace manager
   *   (owner, admin, or verified workspace.manage). Labels are never authority keys.
   */
  function isVerifiedManagerOnlyGroup(group) {
    if (!group) return false;
    const isSystem = group.kind === 'system' || group.group_kind === 'system';
    if (!isSystem) return false;
    const eff = Array.isArray(group.effective_members)
      ? group.effective_members
      : Array.isArray(group.members)
        ? group.members
        : null;
    if (eff) {
      if (eff.length === 0) return false;
      return eff.every((em) => {
        const eid = em.actor_id ?? em.id;
        const enpub = em.npub;
        return managerActorIds.has(eid) || (enpub && enpub === ownerNpub);
      });
    }
    return false;
  }

  let hasManagerWriter = false;
  let hasWorkspaceReader = false;

  for (const g of grants) {
    const pid = grantPrincipalId(g);
    if (!pid) throw new Error('Malformed Chief of Staff assignment grant.');
    const canWrite = hasAnyPermission(g, ['file.write', 'channel.manage', 'channel.grants.manage']);
    if (isGroupGrant(g)) {
      const group = groupById.get(pid);
      if (canWrite) {
        if (!isVerifiedManagerOnlyGroup(group)) {
          throw new Error('Non-manager or unverified group cannot hold write permission on Chief of Staff assignment.');
        }
        if (hasAllPermissions(g, ['channel.read', 'file.read', 'file.write'])) {
          hasManagerWriter = true;
        }
      } else if (hasAllPermissions(g, ['channel.read', 'file.read'])) {
        hasWorkspaceReader = true;
      }
    } else if (isActorGrant(g)) {
      if (canWrite) {
        if (!managerActorIds.has(pid)) {
          throw new Error('Non-manager actor cannot hold write permission on Chief of Staff assignment.');
        }
        if (hasAllPermissions(g, ['channel.read', 'file.read', 'file.write'])) {
          hasManagerWriter = true;
        }
      }
    } else {
      throw new Error('Unsupported grant principal type on Chief of Staff assignment channel.');
    }
  }
  if (!hasManagerWriter) {
    throw new Error('Chief of Staff assignment channel has no verified manager write grant.');
  }
  return { hasManagerWriter, hasWorkspaceReader };
}

export function verifyCohortChannelGrants(grants, members, ownerNpub, agentNpub) {
  if (!Array.isArray(grants) || !Array.isArray(members)) {
    throw new Error('Private ontology cohort grants could not be verified.');
  }
  const actors = members.map((m) => ({
    id: m.actor_id ?? m.actor?.id ?? m.id,
    npub: m.npub ?? m.actor?.npub,
  }));
  const owners = actors.filter((a) => a.npub === ownerNpub);
  const agents = actors.filter((a) => a.npub === agentNpub);
  if (owners.length !== 1 || agents.length !== 1 || ownerNpub === agentNpub) {
    throw new Error('Ontology cohort owner and agent must be distinct current workspace members.');
  }
  const ownerId = owners[0].id;
  const agentId = agents[0].id;
  if (!ownerId || !agentId || ownerId === agentId) {
    throw new Error('Ambiguous ontology cohort identities.');
  }
  let hasOwner = false;
  let hasAgent = false;
  const seen = new Set();
  for (const g of grants) {
    if (!isActorGrant(g)) {
      throw new Error('Private ontology cohort must not grant access to groups.');
    }
    const pid = grantPrincipalId(g);
    const p = g.principal ?? {};
    if (
      !pid ||
      seen.has(pid) ||
      [p.actor_id, p.id, g.principal_id].some((v) => v !== undefined && v !== pid) ||
      (p.npub !== undefined && p.npub !== (pid === ownerId ? ownerNpub : agentNpub))
    ) {
      throw new Error('Ambiguous ontology cohort grant identity.');
    }
    seen.add(pid);
    const required = [
      'channel.read',
      'file.read',
      'file.write',
      ...(pid === ownerId ? ['channel.manage', 'channel.grants.manage'] : []),
    ];
    if (!hasAllPermissions(g, required)) {
      throw new Error('Ontology cohort effective permissions are incomplete.');
    }
    if (pid === ownerId) hasOwner = true;
    else if (pid === agentId) hasAgent = true;
    else throw new Error('Private ontology cohort must only include owner and assigned agent.');
  }
  if (!hasOwner || !hasAgent) {
    throw new Error('Ontology cohort owner or agent grant is missing.');
  }
  return { ownerId, agentId };
}

/**
 * Verifies server-origin manager provenance on the Assignment channel, File, and current version.
 * Allows ordinary workspace members (who receive 403 on admin-only `/groups` and `/grants`) to
 * reject forged metadata channels/files created or modified by non-managers.
 */
export function verifyAssignmentProvenance({
  channel = null,
  file,
  version = null,
  members,
  ownerNpub,
  verifiedManagerIds = [],
  payload,
}) {
  if (!Array.isArray(members) || !file || !isObject(payload)) {
    throw new Error('Chief of Staff assignment provenance could not be verified.');
  }
  const managerById = new Map();
  for (const m of members) {
    const id = memberActorId(m);
    const npub = memberNpub(m);
    const isManager = isVerifiedWorkspaceManager(m, ownerNpub) || (id && verifiedManagerIds.includes(id));
    if (id && npub && isManager) {
      managerById.set(String(id).toLowerCase(), { id: String(id).toLowerCase(), npub });
    }
  }
  if (managerById.size === 0) {
    throw new Error('No authoritative workspace managers found to verify Chief of Staff provenance.');
  }

  const verifyActor = (label, actorId, actorNpub) => {
    const id = typeof actorId === 'string' && actorId.trim() ? actorId.trim().toLowerCase() : null;
    if (!id) {
      throw new Error(`Chief of Staff ${label} is missing authoritative server actor_id.`);
    }
    const manager = managerById.get(id);
    if (!manager) {
      throw new Error(`Chief of Staff ${label} was not authored by a verified workspace manager.`);
    }
    if (actorNpub && actorNpub !== manager.npub) {
      throw new Error(`Chief of Staff ${label} actor npub does not match workspace manager identity.`);
    }
    return manager;
  };

  // Channel creator: live Tower /scopes/:id/channels omits created_by_actor_id; enforce whenever present.
  if (channel?.created_by_actor_id || channel?.created_by_actor_npub) {
    verifyActor('assignment channel', channel.created_by_actor_id, channel.created_by_actor_npub);
  }

  // File creator MUST be a verified workspace manager (live Tower always sets file.created_by_actor_id).
  verifyActor('assignment file', file.created_by_actor_id, file.created_by_actor_npub);

  // Latest version author (or file updater) MUST be a verified workspace manager and match payload.
  const currentVersion = version ?? file.current_version ?? null;
  const writerId =
    currentVersion?.created_by_actor_id ?? file.updated_by_actor_id ?? file.created_by_actor_id ?? null;
  const writerNpub =
    currentVersion?.created_by_actor_npub ?? file.updated_by_actor_npub ?? file.created_by_actor_npub ?? null;
  const verifiedWriter = verifyActor('assignment version', writerId, writerNpub);

  if (
    String(payload.updated_by_actor_id || '').toLowerCase() !== verifiedWriter.id ||
    payload.updated_by_npub !== verifiedWriter.npub
  ) {
    throw new Error('Chief of Staff assignment payload author does not match authoritative server version provenance.');
  }
  return verifiedWriter;
}

/**
 * Verifies that BOTH the workspace owner AND the assigned agent are demonstrably entitled to
 * read a source channel before indexing it into the owner+agent private cohort.
 * Fails closed when grants or group membership cannot be verified.
 */
export function isSourceChannelAuthorizedForCohort({
  channel,
  grants = null,
  members = [],
  groups = [],
  ownerNpub,
  agentNpub,
}) {
  if (!channel || !isActiveRecord(channel)) {
    return { authorized: false, reason: 'inactive_channel' };
  }
  if (channel.kind === 'dm') {
    const participants = new Set(Array.isArray(channel.participant_npubs) ? channel.participant_npubs : []);
    if (participants.size < 2) {
      return { authorized: false, reason: 'self_dm_excluded' };
    }
    if (!participants.has(ownerNpub) || !participants.has(agentNpub)) {
      return { authorized: false, reason: 'dm_not_shared_by_owner_and_agent' };
    }
    return { authorized: true, reason: null };
  }
  if (channel.kind !== 'channel') {
    return { authorized: false, reason: 'unsupported_channel_kind' };
  }
  if (!Array.isArray(grants) || !Array.isArray(members) || !Array.isArray(groups)) {
    return { authorized: false, reason: 'grants_unverifiable' };
  }
  const actors = members.map((m) => ({
    id: String(m.actor_id ?? m.actor?.actor_id ?? m.actor?.id ?? m.id ?? '').toLowerCase(),
    npub: m.npub ?? m.actor?.npub ?? null,
  }));
  const owner = actors.find((a) => a.npub === ownerNpub);
  const agent = actors.find((a) => a.npub === agentNpub);
  if (!owner?.id || !agent?.id) {
    return { authorized: false, reason: 'cohort_principal_missing' };
  }
  const groupById = new Map(groups.map((g) => [String(g.id ?? g.group_id ?? '').toLowerCase(), g]));
  const ownerPerms = new Set();
  const agentPerms = new Set();

  for (const g of grants) {
    const perms = Array.isArray(g?.permissions) ? g.permissions : [];
    const pid = String(grantPrincipalId(g) ?? '').toLowerCase();
    if (!pid) continue;
    if (isActorGrant(g)) {
      const pNpub = g?.principal?.npub;
      if (pid === owner.id || (pNpub && pNpub === ownerNpub)) {
        for (const perm of perms) ownerPerms.add(perm);
      }
      if (pid === agent.id || (pNpub && pNpub === agentNpub)) {
        for (const perm of perms) agentPerms.add(perm);
      }
    } else if (isGroupGrant(g)) {
      const grp = groupById.get(pid);
      if (!grp) continue; // Fail closed: unknown group grants nothing.
      const effMembers = Array.isArray(grp.effective_members)
        ? grp.effective_members
        : Array.isArray(grp.members)
          ? grp.members
          : null;
      const effNpubs = Array.isArray(grp.effective_member_npubs) ? grp.effective_member_npubs : null;
      if (!effMembers && !effNpubs) continue; // Fail closed: unexpanded group grants nothing.
      const memberIds = new Set((effMembers || []).map((em) => String(em.actor_id ?? em.id ?? '').toLowerCase()).filter(Boolean));
      const memberNpubs = new Set([
        ...(effMembers || []).map((em) => em.npub).filter(Boolean),
        ...(effNpubs || []).filter(Boolean),
      ]);
      if (memberIds.has(owner.id) || memberNpubs.has(ownerNpub)) {
        for (const perm of perms) ownerPerms.add(perm);
      }
      if (memberIds.has(agent.id) || memberNpubs.has(agentNpub)) {
        for (const perm of perms) agentPerms.add(perm);
      }
    }
  }

  if (!ownerPerms.has('channel.read') || !agentPerms.has('channel.read')) {
    return { authorized: false, reason: 'not_entitled_for_both_owner_and_agent' };
  }
  return { authorized: true, reason: null, ownerPerms, agentPerms };
}

// --- Document validators ---

export function validateAssignment(doc, expected) {
  if (
    !isObject(doc) ||
    doc.schema !== SCHEMA_ASSIGNMENT ||
    doc.schema_version !== 1 ||
    !isUuid(doc.workspace_id) ||
    !isNpub(doc.workspace_owner_npub) ||
    !isNpub(doc.app_npub) ||
    !Number.isSafeInteger(doc.assignment_revision) ||
    doc.assignment_revision < 0 ||
    !isUuid(doc.updated_by_actor_id) ||
    !isNpub(doc.updated_by_npub) ||
    !isIso(doc.updated_at)
  ) {
    throw new Error('Invalid Chief of Staff assignment document.');
  }
  // Shared assignment must never carry private cohort content or labels.
  for (const forbidden of ['topics', 'evidence', 'cohort_id', 'cohort_channel_id', 'job_status']) {
    if (forbidden in doc) throw new Error(`Shared assignment must not contain private field: ${forbidden}`);
  }
  const hasAssigned = doc.assigned_npub !== null || doc.assigned_actor_id !== null;
  if (hasAssigned) {
    if (!isUuid(doc.assigned_actor_id) || !isNpub(doc.assigned_npub)) {
      throw new Error('Assigned Chief of Staff requires both stable actor_id and npub.');
    }
  }
  if (doc.previous_assigned_npub !== null && doc.previous_assigned_npub !== undefined && !isNpub(doc.previous_assigned_npub)) {
    throw new Error('Invalid previous_assigned_npub.');
  }
  if (doc.runtime_binding !== null && doc.runtime_binding !== undefined) {
    const rb = doc.runtime_binding;
    if (!isObject(rb)) throw new Error('Invalid runtime_binding: must be an object or null.');
    for (const secretKey of ['token', 'secret', 'key', 'nsec', 'authorization', 'topics', 'evidence', 'cohort_id', 'cohort_channel_id']) {
      if (secretKey in rb) throw new Error(`runtime_binding must not contain private or secret field: ${secretKey}`);
    }
    let parsedUrl;
    try {
      parsedUrl = new URL(String(rb.autopilot_origin || ''));
    } catch {
      throw new Error('Invalid runtime_binding.autopilot_origin.');
    }
    if (
      parsedUrl.protocol !== 'https:' ||
      (parsedUrl.pathname !== '' && parsedUrl.pathname !== '/') ||
      parsedUrl.username ||
      parsedUrl.password ||
      parsedUrl.search ||
      parsedUrl.hash
    ) {
      throw new Error('runtime_binding.autopilot_origin must be a clean https:// origin.');
    }
    if (typeof rb.reconcile_pipeline_id !== 'string' || !PIPELINE_ID_RE.test(rb.reconcile_pipeline_id)) {
      throw new Error('Invalid runtime_binding.reconcile_pipeline_id.');
    }
    if (
      rb.ontology_pipeline_id !== undefined &&
      rb.ontology_pipeline_id !== null &&
      (typeof rb.ontology_pipeline_id !== 'string' || !PIPELINE_ID_RE.test(rb.ontology_pipeline_id))
    ) {
      throw new Error('Invalid runtime_binding.ontology_pipeline_id.');
    }
    if (!isUuid(rb.subscription_id)) throw new Error('Invalid runtime_binding.subscription_id.');
    if (rb.workspace_id !== doc.workspace_id) throw new Error('runtime_binding.workspace_id mismatch.');
    if (rb.owner_npub !== doc.workspace_owner_npub) throw new Error('runtime_binding.owner_npub mismatch.');
    if (rb.agent_npub !== null && rb.agent_npub !== undefined && !isNpub(rb.agent_npub)) {
      throw new Error('Invalid runtime_binding.agent_npub.');
    }
    if (doc.assigned_npub !== null && rb.agent_npub !== doc.assigned_npub) {
      throw new Error('runtime_binding.agent_npub must match assigned_npub when assigned.');
    }
  }
  if (expected) {
    if (expected.workspaceId && doc.workspace_id !== expected.workspaceId) throw new Error('Assignment workspace_id mismatch.');
    if (expected.ownerNpub && doc.workspace_owner_npub !== expected.ownerNpub) throw new Error('Assignment workspace_owner_npub mismatch.');
    if (expected.appNpub && doc.app_npub !== expected.appNpub) throw new Error('Assignment app_npub mismatch.');
  }
  return doc;
}

export function validateJobStatus(doc, expected) {
  if (
    !isObject(doc) ||
    doc.schema !== SCHEMA_JOB_STATUS ||
    doc.schema_version !== 1 ||
    !isUuid(doc.workspace_id) ||
    !isNpub(doc.workspace_owner_npub) ||
    !isNpub(doc.app_npub) ||
    !isNpub(doc.bound_agent_npub) ||
    !SCHEDULE_STATES.has(doc.schedule_state) ||
    !Number.isSafeInteger(doc.assignment_revision_seen) ||
    doc.assignment_revision_seen < 0 ||
    !isIso(doc.updated_at)
  ) {
    throw new Error('Invalid Chief of Staff job status document.');
  }
  if (expected) {
    if (expected.workspaceId && doc.workspace_id !== expected.workspaceId) throw new Error('Job status workspace_id mismatch.');
    if (expected.ownerNpub && doc.workspace_owner_npub !== expected.ownerNpub) throw new Error('Job status owner mismatch.');
    if (expected.appNpub && doc.app_npub !== expected.appNpub) throw new Error('Job status app mismatch.');
  }
  return doc;
}

export function validateOntology(doc, expected) {
  if (
    !isObject(doc) ||
    doc.schema !== SCHEMA_ONTOLOGY ||
    doc.schema_version !== 1 ||
    !isUuid(doc.workspace_id) ||
    !isNpub(doc.workspace_owner_npub) ||
    !isNpub(doc.app_npub) ||
    !isNpub(doc.agent_npub) ||
    typeof doc.cohort_id !== 'string' ||
    !Number.isSafeInteger(doc.snapshot_revision) ||
    doc.snapshot_revision < 0 ||
    !Number.isSafeInteger(doc.assignment_revision_seen) ||
    !isIso(doc.generated_at) ||
    !Array.isArray(doc.topics) ||
    !Array.isArray(doc.evidence) ||
    !isObject(doc.coverage)
  ) {
    throw new Error('Invalid Chief of Staff ontology snapshot.');
  }
  for (const t of doc.topics) {
    if (
      !isObject(t) ||
      typeof t.id !== 'string' ||
      !t.id.trim() ||
      typeof t.name !== 'string' ||
      !t.name.trim() ||
      typeof t.kind !== 'string' ||
      !Array.isArray(t.aliases)
    ) {
      throw new Error('Invalid topic entry in ontology snapshot.');
    }
  }
  for (const e of doc.evidence) {
    if (
      !isObject(e) ||
      typeof e.id !== 'string' ||
      typeof e.topic_id !== 'string' ||
      !SOURCE_TYPES.has(e.source_type) ||
      !isUuid(e.record_id) ||
      !EVIDENCE_STATUSES.has(e.status) ||
      typeof e.explanation !== 'string'
    ) {
      throw new Error('Invalid evidence entry in ontology snapshot.');
    }
  }
  if (expected) {
    if (expected.workspaceId && doc.workspace_id !== expected.workspaceId) throw new Error('Ontology workspace_id mismatch.');
    if (expected.ownerNpub && doc.workspace_owner_npub !== expected.ownerNpub) throw new Error('Ontology owner mismatch.');
    if (expected.appNpub && doc.app_npub !== expected.appNpub) throw new Error('Ontology app mismatch.');
  }
  return doc;
}

export function validateCorrections(doc, expected) {
  if (
    !isObject(doc) ||
    doc.schema !== SCHEMA_CORRECTIONS ||
    doc.schema_version !== 1 ||
    !isUuid(doc.workspace_id) ||
    !isNpub(doc.workspace_owner_npub) ||
    !isNpub(doc.app_npub) ||
    typeof doc.cohort_id !== 'string' ||
    !Array.isArray(doc.corrections) ||
    !isIso(doc.updated_at)
  ) {
    throw new Error('Invalid Chief of Staff corrections document.');
  }
  for (const c of doc.corrections) {
    if (
      !isObject(c) ||
      typeof c.id !== 'string' ||
      !c.id.trim() ||
      !CORRECTION_KINDS.has(c.kind) ||
      !isNpub(c.created_by_npub) ||
      !isIso(c.created_at)
    ) {
      throw new Error('Invalid correction entry.');
    }
  }
  if (expected) {
    if (expected.workspaceId && doc.workspace_id !== expected.workspaceId) throw new Error('Corrections workspace_id mismatch.');
    if (expected.ownerNpub && doc.workspace_owner_npub !== expected.ownerNpub) throw new Error('Corrections owner mismatch.');
    if (expected.appNpub && doc.app_npub !== expected.appNpub) throw new Error('Corrections app mismatch.');
  }
  return doc;
}

// --- Empty builders ---

export function emptyAssignment({ workspaceId, ownerNpub, appNpub, actorId, actorNpub, now = new Date() }) {
  return validateAssignment({
    schema: SCHEMA_ASSIGNMENT,
    schema_version: 1,
    workspace_id: workspaceId,
    workspace_owner_npub: ownerNpub,
    app_npub: appNpub,
    assignment_revision: 0,
    assigned_actor_id: null,
    assigned_npub: null,
    assigned_display_name: null,
    previous_assigned_npub: null,
    updated_by_actor_id: actorId,
    updated_by_npub: actorNpub,
    updated_at: now.toISOString(),
  });
}

export function emptyJobStatus({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch = 1, assignmentRevision = 0, now = new Date() }) {
  return validateJobStatus({
    schema: SCHEMA_JOB_STATUS,
    schema_version: 1,
    workspace_id: workspaceId,
    workspace_owner_npub: ownerNpub,
    app_npub: appNpub,
    cohort_id: cohortId(ownerNpub, agentNpub, assignmentEpoch),
    assignment_epoch: assignmentEpoch,
    bound_agent_npub: agentNpub,
    assignment_revision_seen: assignmentRevision,
    schedule_state: 'pending',
    autopilot_job_id: null,
    pipeline_definition_id: null,
    cron_expression: null,
    timezone: 'Australia/Perth',
    last_run_at: null,
    last_success_at: null,
    last_error: null,
    topic_count: 0,
    evidence_count: 0,
    cursor_summary: null,
    coverage: {
      supported_types: ['message', 'thread', 'audio_note', 'task', 'doc', 'file'],
      scanned_counts: { message: 0, thread: 0, audio_note: 0, task: 0, doc: 0, file: 0 },
      unsupported_inputs: [],
    },
    updated_at: now.toISOString(),
  });
}

export function emptyOntology({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch = 1, assignmentRevision = 0, now = new Date() }) {
  return validateOntology({
    schema: SCHEMA_ONTOLOGY,
    schema_version: 1,
    workspace_id: workspaceId,
    workspace_owner_npub: ownerNpub,
    app_npub: appNpub,
    cohort_id: cohortId(ownerNpub, agentNpub, assignmentEpoch),
    assignment_epoch: assignmentEpoch,
    cohort_kind: 'owner_agent_private',
    agent_npub: agentNpub,
    snapshot_revision: 0,
    assignment_revision_seen: assignmentRevision,
    generated_at: now.toISOString(),
    cursor: { channels: {}, last_event_id: null },
    coverage: {
      supported_types: ['message', 'thread', 'audio_note', 'task', 'doc', 'file'],
      scanned_counts: { message: 0, thread: 0, audio_note: 0, task: 0, doc: 0, file: 0 },
      unsupported_inputs: [],
    },
    topics: [],
    evidence: [],
  });
}

export function emptyCorrections({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch = 1, initialCorrections = [], now = new Date() }) {
  return validateCorrections({
    schema: SCHEMA_CORRECTIONS,
    schema_version: 1,
    workspace_id: workspaceId,
    workspace_owner_npub: ownerNpub,
    app_npub: appNpub,
    cohort_id: cohortId(ownerNpub, agentNpub, assignmentEpoch),
    assignment_epoch: assignmentEpoch,
    corrections: initialCorrections,
    updated_at: now.toISOString(),
  });
}

// --- Durable correction replay engine ---

export function applyCorrections(discoveredTopics, discoveredEvidence, corrections = []) {
  const topicMap = new Map();
  for (const t of discoveredTopics) {
    topicMap.set(t.id, {
      ...t,
      aliases: Array.from(new Set((t.aliases || []).filter(Boolean))),
      archived: Boolean(t.archived),
      source_origin: t.source_origin || 'discovered',
    });
  }

  const mergeRedirects = new Map();
  const resolveId = (id) => {
    let cur = id;
    const visited = new Set();
    while (mergeRedirects.has(cur) && !visited.has(cur)) {
      visited.add(cur);
      cur = mergeRedirects.get(cur);
    }
    return cur;
  };

  const rejectedEvidence = new Set();
  const pinnedEvidence = new Map();

  const ordered = [...corrections].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at) || a.id.localeCompare(b.id));

  for (const c of ordered) {
    if (c.kind === 'rename' && c.topic_id && typeof c.name === 'string' && c.name.trim()) {
      const tid = resolveId(c.topic_id);
      const t = topicMap.get(tid);
      if (t) {
        const nextName = c.name.trim();
        if (t.name !== nextName && !t.aliases.includes(t.name)) {
          t.aliases = [...t.aliases, t.name];
        }
        t.name = nextName;
        t.source_origin = 'corrected';
        t.updated_at = c.created_at;
      }
    } else if (c.kind === 'set_description' && c.topic_id && typeof c.description === 'string') {
      const tid = resolveId(c.topic_id);
      const t = topicMap.get(tid);
      if (t) {
        t.description = c.description.trim();
        t.source_origin = 'corrected';
        t.updated_at = c.created_at;
      }
    } else if (c.kind === 'set_aliases' && c.topic_id && Array.isArray(c.aliases)) {
      const tid = resolveId(c.topic_id);
      const t = topicMap.get(tid);
      if (t) {
        t.aliases = Array.from(new Set(c.aliases.map((a) => String(a || '').trim()).filter((a) => a && a !== t.name)));
        t.source_origin = 'corrected';
        t.updated_at = c.created_at;
      }
    } else if (c.kind === 'set_kind' && c.topic_id && typeof c.topic_kind === 'string' && c.topic_kind.trim()) {
      const tid = resolveId(c.topic_id);
      const t = topicMap.get(tid);
      if (t) {
        t.kind = c.topic_kind.trim();
        t.source_origin = 'corrected';
        t.updated_at = c.created_at;
      }
    } else if (c.kind === 'merge_topics' && c.source_topic_id && c.target_topic_id) {
      const srcId = resolveId(c.source_topic_id);
      const dstId = resolveId(c.target_topic_id);
      if (srcId !== dstId) {
        mergeRedirects.set(srcId, dstId);
        const src = topicMap.get(srcId);
        const dst = topicMap.get(dstId);
        if (src && dst) {
          const mergedAliases = new Set([...(dst.aliases || []), src.name, ...(src.aliases || [])]);
          mergedAliases.delete(dst.name);
          dst.aliases = Array.from(mergedAliases);
          if (!dst.description && src.description) dst.description = src.description;
          dst.source_origin = 'corrected';
          dst.updated_at = c.created_at;
          topicMap.delete(srcId);
        } else if (src && !dst) {
          topicMap.set(dstId, { ...src, id: dstId, source_origin: 'corrected', updated_at: c.created_at });
          topicMap.delete(srcId);
        }
      }
    } else if (c.kind === 'archive_topic' && c.topic_id) {
      const tid = resolveId(c.topic_id);
      const t = topicMap.get(tid);
      if (t) {
        t.archived = c.archived !== false;
        t.source_origin = 'corrected';
        t.updated_at = c.created_at;
      }
    } else if (c.kind === 'reject_evidence' && c.topic_id && c.source_type && c.record_id) {
      const key = `${resolveId(c.topic_id)}::${c.source_type}::${c.record_id}`;
      rejectedEvidence.add(key);
      pinnedEvidence.delete(key);
    } else if (c.kind === 'pin_evidence' && c.topic_id && c.source_type && c.record_id) {
      const key = `${resolveId(c.topic_id)}::${c.source_type}::${c.record_id}`;
      rejectedEvidence.delete(key);
      pinnedEvidence.set(key, c);
    }
  }

  const dedupedEvidence = new Map();
  for (const ev of discoveredEvidence) {
    const tid = resolveId(ev.topic_id);
    if (!topicMap.has(tid)) continue;
    const key = `${tid}::${ev.source_type}::${ev.record_id}`;
    if (rejectedEvidence.has(key)) continue;
    const pinned = pinnedEvidence.has(key);
    const existing = dedupedEvidence.get(key);
    const nextScore = pinned ? Math.max(ev.score ?? 0.5, 0.99) : (ev.score ?? 0.5);
    if (!existing || nextScore > existing.score) {
      dedupedEvidence.set(key, {
        ...ev,
        id: `ev_${sha256Hex(Buffer.from(key)).slice(0, 16)}`,
        topic_id: tid,
        score: Number(nextScore.toFixed(4)),
        pinned,
      });
    }
  }

  const topics = Array.from(topicMap.values()).sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  const evidence = Array.from(dedupedEvidence.values()).sort(
    (a, b) => a.topic_id.localeCompare(b.topic_id) || b.score - a.score || a.record_id.localeCompare(b.record_id),
  );
  return { topics, evidence, mergeRedirects };
}
