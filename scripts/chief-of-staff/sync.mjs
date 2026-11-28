import crypto from 'node:crypto';
import * as M from './model.mjs';
import { buildExtractionBatch, mergeStructuredExtraction } from './extractor.mjs';

const TEXT_MIME_PREFIXES = ['text/', 'application/json', 'application/xml', 'application/javascript', 'application/markdown'];
const EXCLUDED_SYSTEM_KEYS = new Set([
  M.SYSTEM_KEY_ASSIGNMENT,
  M.SYSTEM_KEY_ONTOLOGY,
  'ambulando.briefings',
]);
const MAX_ARTEFACT_BYTES = 128 * 1024;

export class IncompletePaginationError extends Error {
  constructor(key, pagesRead) {
    super(`Incomplete ${key} listing: pagination cap (${pagesRead} pages) reached with remaining pages.`);
    this.name = 'IncompletePaginationError';
    this.code = 'incomplete_pagination';
    this.pagesRead = pagesRead;
  }
}

export async function listAll(client, pathname, key, { limit = 200, maxPages = 50 } = {}) {
  const rows = [];
  const seen = new Set();
  let cursor;
  for (let page = 0; page < maxPages; page++) {
    const sep = pathname.includes('?') ? '&' : '?';
    const url = `${pathname}${sep}limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const body = await client.request('GET', url);
    if (!Array.isArray(body?.[key]) || body.partial === true || body.complete === false) {
      throw new Error(`Incomplete ${key} listing.`);
    }
    rows.push(...body[key]);
    cursor = body.next_cursor;
    if (!cursor) {
      if (body.has_more === true) throw new Error(`Incomplete ${key} listing (has_more without cursor).`);
      return rows;
    }
    if (seen.has(cursor) || body[key].length === 0) throw new Error(`Invalid ${key} pagination cursor.`);
    seen.add(cursor);
  }
  throw new IncompletePaginationError(key, maxPages);
}

function workspaceRoot(workspaceId) {
  return `/api/v4/flightdeck-pg/workspaces/${encodeURIComponent(workspaceId)}`;
}

async function readVerifiedFile(client, workspaceId, fileId, expectedChannelId, validator, expectedContext, adapter = M.PLAINTEXT_ADAPTER) {
  const { file } = await client.getFile(fileId);
  if (!file || file.id !== fileId || file.workspace_id !== workspaceId || file.channel_id !== expectedChannelId) {
    throw new Error('Chief of Staff File boundary mismatch.');
  }
  const versionId = file.current_version_id;
  const meta = file.object?.storage_object;
  if (!versionId || !meta?.sha256_hex || !Number.isSafeInteger(meta.size_bytes) || meta.size_bytes > M.MAX_BYTES || meta.is_public !== false) {
    throw new Error('Unverified Chief of Staff File version metadata.');
  }
  const bytes = await client.readVersionBytes(fileId, versionId, { maxBytes: M.MAX_BYTES });
  if (bytes.byteLength !== meta.size_bytes || M.sha256Hex(bytes) !== String(meta.sha256_hex).toLowerCase()) {
    throw new Error('Chief of Staff File content digest mismatch.');
  }
  const payload = M.decodeEnvelope(bytes, adapter);
  return { file, versionId, sha256: M.sha256Hex(bytes), document: validator(payload, expectedContext) };
}

async function writeVerifiedVersion(client, fileId, baseVersionId, bytes, mime, fileName) {
  const objectId = await client.uploadObject({ bytes, contentType: mime, fileName });
  const saved = await client.createVersion(fileId, {
    base_version_id: baseVersionId,
    storage_object_id: objectId,
    client_mutation_id: crypto.randomUUID(),
  });
  const versionId = saved?.version?.id;
  if (!versionId) throw new Error('Version write outcome uncertain; reconcile before retrying.');
  const readback = await client.readVersionBytes(fileId, versionId, { maxBytes: M.MAX_BYTES });
  if (M.sha256Hex(readback) !== M.sha256Hex(bytes)) {
    throw new Error('Chief of Staff File readback digest mismatch.');
  }
  return { versionId, sha256: M.sha256Hex(readback) };
}

async function checkGrantsComplete(client, workspaceId, channelId) {
  const acl = await client.request('GET', `${workspaceRoot(workspaceId)}/channels/${encodeURIComponent(channelId)}/grants`);
  if (acl.partial === true || acl.complete === false || acl.has_more === true || acl.next_cursor) {
    throw new Error('Incomplete channel grants envelope.');
  }
  return acl.grants || [];
}

async function resolveCallerManagerIds(client, workspaceId) {
  try {
    const me = await client.request('GET', `${workspaceRoot(workspaceId)}/me`);
    // The caller's own server-computed effective permissions; membership role labels are not authority.
    const perms = Array.isArray(me?.permissions) ? me.permissions : [];
    const actorId = me?.actor?.actor_id ?? me?.actor?.id ?? null;
    if (actorId && perms.includes('workspace.manage')) {
      return [actorId];
    }
  } catch {
    // Non-fatal: fall back to /members owner identity and authority.can_manage_workspace.
  }
  return [];
}

async function readCurrentFileVersion(client, workspaceId, fileId, versionId) {
  if (!versionId) return null;
  try {
    const res = await client.request('GET', `${workspaceRoot(workspaceId)}/files/${encodeURIComponent(fileId)}/versions`);
    const list = Array.isArray(res?.versions) ? res.versions : [];
    return list.find((v) => v?.id === versionId) ?? null;
  } catch {
    return null;
  }
}

// --- Assignment channel & file ---

/**
 * Discovers the workspace Assignment channel and canonical File.
 * `verifyAcl`:
 * - `'strict'` (managers/writers/sync): requires reading grants & groups and verifying manager-only writers.
 * - `'best_effort'` (regular workspace member readers): verifies full ACL when permitted, and tolerates
 *   403 on admin-only `/groups` or `/channels/:id/grants` while still verifying server-origin manager
 *   provenance in `readAssignment`.
 * - `'none'`: skips grant inventory check (setup bootstrap only).
 */
export async function discoverAssignment(client, { workspaceId, ownerNpub, appNpub, verifyAcl = 'best_effort' }) {
  const mode = verifyAcl === true ? 'strict' : verifyAcl === false ? 'none' : verifyAcl;
  const root = workspaceRoot(workspaceId);
  const found = [];
  for (const scope of await listAll(client, `${root}/scopes`, 'scopes')) {
    if (scope.kind === 'dm' || !M.isActiveRecord(scope)) continue;
    for (const ch of await listAll(client, `${root}/scopes/${encodeURIComponent(scope.id)}/channels`, 'channels')) {
      if (M.isAssignmentChannel(ch, workspaceId, appNpub)) found.push(ch);
    }
  }
  if (!found.length) return { state: 'needs_setup', channel: null, file: null, aclVerified: false, candidates: [] };

  const verifiedManagerIds = await resolveCallerManagerIds(client, workspaceId);
  const members = await listAll(client, `${root}/members`, 'members');

  const evaluateCandidate = async (channel) => {
    let aclVerified = false;
    if (mode !== 'none') {
      try {
        const groups = await listAll(client, `${root}/groups`, 'groups');
        const grants = await checkGrantsComplete(client, workspaceId, channel.id);
        M.verifyAssignmentChannelGrants(grants, members, groups, ownerNpub, { verifiedManagerIds });
        aclVerified = true;
      } catch (e) {
        const restricted = e?.status === 401 || e?.status === 403;
        if (mode === 'strict' || !restricted) throw e;
      }
    }
    const files = (await listAll(client, `${root}/channels/${encodeURIComponent(channel.id)}/files`, 'files')).filter((f) =>
      M.isAssignmentFile(f, workspaceId, appNpub),
    );
    if (files.length > 1) throw new Error('Multiple Chief of Staff assignment Files need reconciliation.');
    return { state: files.length ? 'ready' : 'needs_file', channel, file: files[0] ?? null, aclVerified };
  };

  if (found.length === 1) {
    const res = await evaluateCandidate(found[0]);
    return { ...res, members, verifiedManagerIds };
  }

  // Multiple candidate channels: filter out any forged/unauthorized candidates before failing on ambiguity.
  const validCandidates = [];
  let lastError = null;
  for (const ch of found) {
    try {
      const cand = await evaluateCandidate(ch);
      if (cand.file) {
        const { file } = await client.getFile(cand.file.id);
        const managerIds = new Set([
          ...members
            .filter((m) => M.isVerifiedWorkspaceManager(m, ownerNpub))
            .map((m) => String(M.memberActorId(m)).toLowerCase()),
          ...verifiedManagerIds.map((id) => String(id).toLowerCase()),
        ]);
        const creatorId = String(file?.created_by_actor_id || '').toLowerCase();
        if (!creatorId || !managerIds.has(creatorId)) continue; // Ignore forged decoy candidate
      }
      validCandidates.push(cand);
    } catch (e) {
      lastError = e;
    }
  }
  if (validCandidates.length > 1) {
    throw new Error('Multiple Chief of Staff assignment channels need reconciliation.');
  }
  if (validCandidates.length === 0) {
    throw lastError || new Error('Chief of Staff assignment candidates failed manager provenance verification.');
  }
  return { ...validCandidates[0], members, verifiedManagerIds };
}

export async function readAssignment(client, { workspaceId, ownerNpub, appNpub, adapter = M.PLAINTEXT_ADAPTER, verifyAcl = 'best_effort' }) {
  const space = await discoverAssignment(client, { workspaceId, ownerNpub, appNpub, verifyAcl });
  if (space.state !== 'ready') {
    return { state: space.state, channel: space.channel, file: null, versionId: null, assignment: null, aclVerified: space.aclVerified };
  }
  const data = await readVerifiedFile(
    client,
    workspaceId,
    space.file.id,
    space.channel.id,
    M.validateAssignment,
    { workspaceId, ownerNpub, appNpub },
    adapter,
  );
  const currentVersion = await readCurrentFileVersion(client, workspaceId, data.file.id, data.versionId);
  M.verifyAssignmentProvenance({
    channel: space.channel,
    file: data.file,
    version: currentVersion,
    members: space.members,
    ownerNpub,
    verifiedManagerIds: space.verifiedManagerIds || [],
    payload: data.document,
  });
  return { state: 'ready', channel: space.channel, file: data.file, versionId: data.versionId, assignment: data.document, aclVerified: space.aclVerified };
}

async function assertManagerAuthority(client, { workspaceId, ownerNpub, appNpub }) {
  const me = await client.request('GET', `${workspaceRoot(workspaceId)}/me`);
  if (
    me?.identity?.workspace_id !== workspaceId ||
    me?.identity?.workspace_owner_npub !== ownerNpub ||
    me?.identity?.app_npub !== appNpub
  ) {
    throw new Error('Workspace identity boundary mismatch.');
  }
  const perms = Array.isArray(me?.permissions) ? me.permissions : [];
  if (!perms.includes('workspace.manage')) {
    const err = new Error('workspace.manage authority is required to change the Chief of Staff assignment.');
    err.status = 403;
    err.code = 'permission_denied';
    throw err;
  }
  return {
    actorId: me.actor?.actor_id ?? me.actor?.id,
    actorNpub: me.actor?.npub,
  };
}

function findVerifiedSystemGroups(groups, members, ownerNpub, verifiedManagerIds = []) {
  const actors = members.map((m) => ({ id: M.memberActorId(m), npub: M.memberNpub(m) }));
  const managerIds = new Set([
    ...members.filter((m) => M.isVerifiedWorkspaceManager(m, ownerNpub)).map(M.memberActorId),
    ...verifiedManagerIds.filter(Boolean),
  ]);
  const systemGroups = (groups || []).filter((g) => g.kind === 'system' || g.group_kind === 'system');
  // Identify manager-only system group structurally (every effective member is a verified manager; labels are not keys)
  const adminsGroup = systemGroups.find((g) => {
    const eff = Array.isArray(g.effective_members) ? g.effective_members : Array.isArray(g.members) ? g.members : [];
    return eff.length > 0 && eff.every((m) => managerIds.has(m.actor_id ?? m.id) || m.npub === ownerNpub);
  });
  // Identify workspace-wide member system group structurally (contains all workspace members, distinct from admins)
  const workspaceGroup = systemGroups.find((g) => {
    if (adminsGroup && (g.id ?? g.group_id) === (adminsGroup.id ?? adminsGroup.group_id)) return false;
    const eff = Array.isArray(g.effective_members) ? g.effective_members : Array.isArray(g.members) ? g.members : [];
    return eff.length >= actors.length && actors.length > 0;
  });
  return { adminsGroup, workspaceGroup };
}

export async function setupAssignmentChannel(client, { workspaceId, ownerNpub, appNpub, scopeId, adapter = M.PLAINTEXT_ADAPTER, now = new Date() }) {
  const root = workspaceRoot(workspaceId);
  const actor = await assertManagerAuthority(client, { workspaceId, ownerNpub, appNpub });
  let space = await discoverAssignment(client, { workspaceId, ownerNpub, appNpub, verifyAcl: 'none' });
  if (space.state === 'needs_setup') {
    const [members, groups] = await Promise.all([
      listAll(client, `${root}/members`, 'members'),
      listAll(client, `${root}/groups`, 'groups'),
    ]);
    const { adminsGroup, workspaceGroup } = findVerifiedSystemGroups(groups, members, ownerNpub, [actor.actorId]);
    const grants = [];
    if (adminsGroup) grants.push({ principal_type: 'group', principal_id: adminsGroup.id ?? adminsGroup.group_id, access_level: 'manage' });
    else grants.push({ principal_type: 'actor', principal_id: actor.actorId, access_level: 'manage' });
    if (workspaceGroup) grants.push({ principal_type: 'group', principal_id: workspaceGroup.id ?? workspaceGroup.group_id, access_level: 'view' });
    await client.request('POST', `${root}/scopes/${encodeURIComponent(scopeId)}/channels`, {
      name: 'Chief of Staff',
      kind: 'channel',
      description: 'Workspace Chief of Staff assignment (manager-controlled, workspace-readable).',
      metadata: M.assignmentChannelMetadata(workspaceId, appNpub),
      grants,
    });
    space = await discoverAssignment(client, { workspaceId, ownerNpub, appNpub, verifyAcl: 'strict' });
  }
  if (space.state === 'needs_file') {
    const initial = M.emptyAssignment({ workspaceId, ownerNpub, appNpub, actorId: actor.actorId, actorNpub: actor.actorNpub, now });
    const bytes = M.encodeEnvelope(initial, adapter);
    const objectId = await client.uploadObject({ bytes, contentType: M.MIME_ASSIGNMENT, fileName: M.FILE_NAME_ASSIGNMENT });
    await client.createFile(space.channel.id, {
      storage_object_id: objectId,
      display_name: M.FILE_NAME_ASSIGNMENT,
      metadata: M.assignmentFileMetadata(workspaceId, appNpub),
    });
  }
  return readAssignment(client, { workspaceId, ownerNpub, appNpub, adapter, verifyAcl: 'strict' });
}

export async function setAssignment(
  client,
  { workspaceId, ownerNpub, appNpub, agentNpub, expectedRevision, runtimeBinding = undefined, adapter = M.PLAINTEXT_ADAPTER, now = new Date() },
) {
  const root = workspaceRoot(workspaceId);
  const manager = await assertManagerAuthority(client, { workspaceId, ownerNpub, appNpub });
  const current = await readAssignment(client, { workspaceId, ownerNpub, appNpub, adapter, verifyAcl: 'strict' });
  if (current.state !== 'ready' || !current.file || !current.assignment) {
    throw new Error('Chief of Staff assignment channel is not initialized.');
  }
  if (expectedRevision !== undefined && current.assignment.assignment_revision !== expectedRevision) {
    const err = new Error(`Assignment revision conflict: expected ${expectedRevision}, actual ${current.assignment.assignment_revision}.`);
    err.status = 409;
    err.code = 'assignment_revision_conflict';
    throw err;
  }

  let assignedActorId = null;
  let assignedNpub = null;
  let assignedDisplayName = null;
  if (agentNpub !== null) {
    const members = await listAll(client, `${root}/members`, 'members');
    const match = members.find((m) => (m.npub ?? m.actor?.npub) === agentNpub);
    if (!match) throw new Error('Selected Chief of Staff agent is not a current member of this workspace.');
    const kind = match.kind ?? match.actor?.kind;
    if (kind !== 'agent') throw new Error('Chief of Staff must be an existing workspace agent.');
    assignedActorId = match.actor_id ?? match.actor?.id ?? match.id;
    assignedNpub = agentNpub;
    assignedDisplayName = match.display_name ?? match.actor?.display_name ?? null;
  }

  // Never silently reuse an old agent's subscription binding for a different agent;
  // preserve existing binding only when re-saving the same assigned agent (or explicitly provided).
  const sameAgent = assignedNpub !== null && assignedNpub === current.assignment.assigned_npub;
  const resolvedBinding =
    runtimeBinding !== undefined
      ? runtimeBinding
      : sameAgent && current.assignment.runtime_binding
        ? { ...current.assignment.runtime_binding, agent_npub: assignedNpub }
        : null;

  // Carry unknown/future top-level fields forward; known fields below always win.
  const next = M.validateAssignment(
    {
      ...current.assignment,
      schema: M.SCHEMA_ASSIGNMENT,
      schema_version: 1,
      workspace_id: workspaceId,
      workspace_owner_npub: ownerNpub,
      app_npub: appNpub,
      assignment_revision: current.assignment.assignment_revision + 1,
      assigned_actor_id: assignedActorId,
      assigned_npub: assignedNpub,
      assigned_display_name: assignedDisplayName,
      previous_assigned_npub: current.assignment.assigned_npub,
      ...(resolvedBinding !== undefined ? { runtime_binding: resolvedBinding } : {}),
      updated_by_actor_id: manager.actorId,
      updated_by_npub: manager.actorNpub,
      updated_at: now.toISOString(),
    },
    { workspaceId, ownerNpub, appNpub },
  );

  const bytes = M.encodeEnvelope(next, adapter);
  const saved = await writeVerifiedVersion(client, current.file.id, current.versionId, bytes, M.MIME_ASSIGNMENT, M.FILE_NAME_ASSIGNMENT);
  const verified = await readAssignment(client, { workspaceId, ownerNpub, appNpub, adapter, verifyAcl: 'strict' });
  return { ...verified, savedVersionId: saved.versionId, sha256: saved.sha256 };
}

// --- Per-assignment-epoch isolated owner+agent private ontology cohort ---

export async function discoverCohort(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch }) {
  const root = workspaceRoot(workspaceId);
  const epoch = Number.isSafeInteger(assignmentEpoch) && assignmentEpoch >= 1 ? assignmentEpoch : 1;
  const found = [];
  for (const scope of await listAll(client, `${root}/scopes`, 'scopes')) {
    if (scope.kind === 'dm' || !M.isActiveRecord(scope)) continue;
    for (const ch of await listAll(client, `${root}/scopes/${encodeURIComponent(scope.id)}/channels`, 'channels')) {
      if (M.isCohortChannel(ch, workspaceId, appNpub, ownerNpub, agentNpub, epoch)) found.push(ch);
    }
  }
  if (found.length > 1) throw new Error('Multiple Chief of Staff ontology cohort channels for the same epoch need reconciliation.');
  if (!found.length) return { state: 'needs_setup', assignmentEpoch: epoch, channel: null, files: {} };
  const channel = found[0];
  const members = await listAll(client, `${root}/members`, 'members');
  const grants = await checkGrantsComplete(client, workspaceId, channel.id);
  M.verifyCohortChannelGrants(grants, members, ownerNpub, agentNpub);

  const allFiles = await listAll(client, `${root}/channels/${encodeURIComponent(channel.id)}/files`, 'files');
  const pick = (kind, mime) => {
    const matches = allFiles.filter((f) => M.isCohortFile(f, kind, mime, workspaceId, appNpub, ownerNpub, agentNpub, epoch));
    if (matches.length > 1) throw new Error(`Multiple Chief of Staff ${kind} Files need reconciliation.`);
    return matches[0] ?? null;
  };
  const files = {
    jobStatus: pick('job_status', M.MIME_JOB_STATUS),
    ontology: pick('ontology', M.MIME_ONTOLOGY),
    corrections: pick('corrections', M.MIME_CORRECTIONS),
  };
  const ready = Boolean(files.jobStatus && files.ontology && files.corrections);
  return { state: ready ? 'ready' : 'needs_files', assignmentEpoch: epoch, channel, files };
}

async function collectPriorCorrections(client, { workspaceId, ownerNpub, appNpub, currentEpoch, adapter }) {
  const root = workspaceRoot(workspaceId);
  const candidates = [];
  for (const scope of await listAll(client, `${root}/scopes`, 'scopes')) {
    if (scope.kind === 'dm' || !M.isActiveRecord(scope)) continue;
    for (const ch of await listAll(client, `${root}/scopes/${encodeURIComponent(scope.id)}/channels`, 'channels')) {
      const m = ch?.metadata?.chief_of_staff;
      if (
        M.isActiveRecord(ch) &&
        ch.kind === 'channel' &&
        ch.workspace_id === workspaceId &&
        ch.metadata?.system_key === M.SYSTEM_KEY_ONTOLOGY &&
        m?.schema_version === 1 &&
        m?.kind === 'ontology_cohort' &&
        m?.owner_npub === ownerNpub &&
        m?.app_npub === appNpub &&
        Number.isSafeInteger(m?.assignment_epoch) &&
        m.assignment_epoch < currentEpoch
      ) {
        candidates.push({ channel: ch, epoch: m.assignment_epoch, agentNpub: m.agent_npub });
      }
    }
  }
  candidates.sort((a, b) => b.epoch - a.epoch);
  for (const cand of candidates) {
    try {
      const files = await listAll(client, `${root}/channels/${encodeURIComponent(cand.channel.id)}/files`, 'files');
      const corrFile = files.find((f) =>
        M.isCohortFile(f, 'corrections', M.MIME_CORRECTIONS, workspaceId, appNpub, ownerNpub, cand.agentNpub, cand.epoch),
      );
      if (!corrFile) continue;
      const read = await readVerifiedFile(
        client,
        workspaceId,
        corrFile.id,
        cand.channel.id,
        M.validateCorrections,
        { workspaceId, ownerNpub, appNpub },
        adapter,
      );
      return read.document.corrections || [];
    } catch {
      // Skip inaccessible prior epochs cleanly.
    }
  }
  return [];
}

export async function setupCohortChannel(
  client,
  { workspaceId, ownerNpub, appNpub, agentNpub, scopeId, assignmentEpoch = 1, assignmentRevision = assignmentEpoch, adapter = M.PLAINTEXT_ADAPTER, now = new Date() },
) {
  const root = workspaceRoot(workspaceId);
  const epoch = Number.isSafeInteger(assignmentEpoch) && assignmentEpoch >= 1 ? assignmentEpoch : 1;
  let cohort = await discoverCohort(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch });
  if (cohort.state === 'needs_setup') {
    const members = await listAll(client, `${root}/members`, 'members');
    const actors = members.map((m) => ({ id: m.actor_id ?? m.actor?.id ?? m.id, npub: m.npub ?? m.actor?.npub }));
    const owner = actors.find((a) => a.npub === ownerNpub);
    const agent = actors.find((a) => a.npub === agentNpub);
    if (!owner || !agent || owner.id === agent.id) {
      throw new Error('Owner and assigned agent must be distinct current workspace members.');
    }
    await client.request('POST', `${root}/scopes/${encodeURIComponent(scopeId)}/channels`, {
      name: `Chief of Staff Index (Epoch ${epoch})`,
      kind: 'channel',
      description: `Private owner and Chief of Staff agent ontology, corrections and job status for assignment epoch ${epoch}.`,
      metadata: M.cohortChannelMetadata(workspaceId, appNpub, ownerNpub, agentNpub, epoch),
      grants: [
        { principal_type: 'actor', principal_id: owner.id, access_level: 'manage' },
        {
          principal_type: 'actor',
          principal_id: agent.id,
          permissions: ['channel.read', 'file.read', 'file.write', 'channel.grants.read'],
        },
      ],
    });
    cohort = await discoverCohort(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch });
  }
  const carriedCorrections =
    !cohort.files.corrections && epoch > 1
      ? await collectPriorCorrections(client, { workspaceId, ownerNpub, appNpub, currentEpoch: epoch, adapter })
      : [];

  const ensureFile = async (existing, kind, mime, fileName, initialDoc) => {
    if (existing) return existing;
    const bytes = M.encodeEnvelope(initialDoc, adapter);
    const objectId = await client.uploadObject({ bytes, contentType: mime, fileName });
    const created = await client.createFile(cohort.channel.id, {
      storage_object_id: objectId,
      display_name: fileName,
      metadata: M.cohortFileMetadata(kind, workspaceId, appNpub, ownerNpub, agentNpub, epoch),
    });
    return created.file;
  };
  await ensureFile(
    cohort.files.jobStatus,
    'job_status',
    M.MIME_JOB_STATUS,
    M.FILE_NAME_JOB_STATUS,
    M.emptyJobStatus({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch, assignmentRevision, now }),
  );
  await ensureFile(
    cohort.files.ontology,
    'ontology',
    M.MIME_ONTOLOGY,
    M.FILE_NAME_ONTOLOGY,
    M.emptyOntology({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch, assignmentRevision, now }),
  );
  await ensureFile(
    cohort.files.corrections,
    'corrections',
    M.MIME_CORRECTIONS,
    M.FILE_NAME_CORRECTIONS,
    M.emptyCorrections({ workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch, initialCorrections: carriedCorrections, now }),
  );
  return discoverCohort(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch: epoch });
}

export async function readCohortState(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, adapter = M.PLAINTEXT_ADAPTER }) {
  const cohort = await discoverCohort(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch });
  if (cohort.state !== 'ready') return { state: cohort.state, cohort, jobStatus: null, ontology: null, corrections: null };
  const expected = { workspaceId, ownerNpub, appNpub };
  const [jobStatus, ontology, corrections] = await Promise.all([
    readVerifiedFile(client, workspaceId, cohort.files.jobStatus.id, cohort.channel.id, M.validateJobStatus, expected, adapter),
    readVerifiedFile(client, workspaceId, cohort.files.ontology.id, cohort.channel.id, M.validateOntology, expected, adapter),
    readVerifiedFile(client, workspaceId, cohort.files.corrections.id, cohort.channel.id, M.validateCorrections, expected, adapter),
  ]);
  return { state: 'ready', cohort, jobStatus, ontology, corrections };
}

export async function appendCorrection(
  client,
  { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, correction, adapter = M.PLAINTEXT_ADAPTER, now = new Date(), maxAttempts = 4 },
) {
  const expected = { workspaceId, ownerNpub, appNpub };
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const state = await readCohortState(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, adapter });
    if (state.state !== 'ready') throw new Error('Chief of Staff cohort is not ready for corrections.');
    const cur = state.corrections.document;
    const entry = {
      id: correction.id || crypto.randomUUID(),
      created_at: correction.created_at || now.toISOString(),
      ...correction,
    };
    const already = cur.corrections.find((c) => c.id === entry.id);
    if (already) return { versionId: state.corrections.versionId, document: cur, changed: false };
    const next = M.validateCorrections(
      {
        ...cur,
        corrections: [...cur.corrections, entry],
        updated_at: now.toISOString(),
      },
      expected,
    );
    const bytes = M.encodeEnvelope(next, adapter);
    try {
      const saved = await writeVerifiedVersion(
        client,
        state.cohort.files.corrections.id,
        state.corrections.versionId,
        bytes,
        M.MIME_CORRECTIONS,
        M.FILE_NAME_CORRECTIONS,
      );
      return { versionId: saved.versionId, sha256: saved.sha256, document: next, changed: true };
    } catch (e) {
      if (e.status === 409 && attempt < maxAttempts - 1) continue;
      throw e;
    }
  }
  throw new Error('Could not save correction after CAS retries.');
}

export async function publishJobStatus(
  client,
  { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, statusPatch, adapter = M.PLAINTEXT_ADAPTER, now = new Date(), maxAttempts = 4 },
) {
  const expected = { workspaceId, ownerNpub, appNpub };
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const state = await readCohortState(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, adapter });
    if (state.state !== 'ready') throw new Error('Chief of Staff cohort is not ready.');
    const next = M.validateJobStatus(
      {
        ...state.jobStatus.document,
        ...statusPatch,
        workspace_id: workspaceId,
        workspace_owner_npub: ownerNpub,
        app_npub: appNpub,
        bound_agent_npub: agentNpub,
        updated_at: now.toISOString(),
      },
      expected,
    );
    const bytes = M.encodeEnvelope(next, adapter);
    try {
      const saved = await writeVerifiedVersion(
        client,
        state.cohort.files.jobStatus.id,
        state.jobStatus.versionId,
        bytes,
        M.MIME_JOB_STATUS,
        M.FILE_NAME_JOB_STATUS,
      );
      return { versionId: saved.versionId, sha256: saved.sha256, document: next };
    } catch (e) {
      if (e.status === 409 && attempt < maxAttempts - 1) continue;
      throw e;
    }
  }
  throw new Error('Could not publish Chief of Staff job status after CAS retries.');
}

// --- Verified /sync workspace change ingestion ---

function isNotesToSelf(channel) {
  if (!channel) return true;
  if (channel.kind === 'dm') {
    const participants = Array.isArray(channel.participant_npubs) ? Array.from(new Set(channel.participant_npubs)) : [];
    if (participants.length < 2) return true;
  }
  return /^note to self$/i.test(String(channel.name || '').trim());
}

function isExcludedSystemChannel(channel) {
  const sys = channel?.metadata?.system_key;
  return Boolean(sys && EXCLUDED_SYSTEM_KEYS.has(sys));
}

function isReadableTextFile(file) {
  const mime = String(file?.content_type || '').split(';')[0].trim().toLowerCase();
  return TEXT_MIME_PREFIXES.some((p) => mime.startsWith(p));
}

async function assertActiveAssignment(client, { workspaceId, ownerNpub, appNpub, agentNpub, adapter }) {
  const current = await readAssignment(client, { workspaceId, ownerNpub, appNpub, adapter, verifyAcl: 'strict' });
  if (current.state !== 'ready' || !current.assignment) {
    return { ok: false, reason: 'assignment_unavailable', assignment: null };
  }
  if (!current.assignment.assigned_npub) {
    return { ok: false, reason: 'assignment_removed', assignment: current.assignment };
  }
  if (current.assignment.assigned_npub !== agentNpub) {
    return { ok: false, reason: 'assignment_reassigned', assignment: current.assignment };
  }
  return { ok: true, reason: null, assignment: current.assignment };
}

/**
 * Phase A: Incremental workspace sync using Tower's verified `/workspaces/:id/sync`
 * snapshot-continuation + delta-cursor + tombstones contract.
 *
 * - Never abuses message pagination as a change cursor.
 * - Never starves channels beyond a fixed first-N cap: continues snapshot pages via
 *   `next_cursor` across runs until `snapshot_complete === true`, then advances via
 *   `delta_cursor = through_cursor` catching every create, edit, deletion, and revocation.
 * - Preserves real thread records/revisions and accumulates ordered messages across pages.
 * - Persists every page + cursor atomically into SQLite for crash-safe resumption.
 */
export async function syncSourcesAndPrepareBatch(
  client,
  store,
  {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    adapter = M.PLAINTEXT_ADAPTER,
    limits = {},
    now = new Date(),
    onAfterPage = null,
  },
) {
  const root = workspaceRoot(workspaceId);
  const pageLimit = limits.pageLimit ?? 100;
  const maxPagesPerRun = limits.maxPagesPerRun ?? 25;
  const maxFileBytes = limits.maxFileBytes ?? MAX_ARTEFACT_BYTES;
  const maxBatchSources = limits.maxBatchSources ?? 40;

  // Guard 1: before doing work
  const preCheck = await assertActiveAssignment(client, { workspaceId, ownerNpub, appNpub, agentNpub, adapter });
  if (!preCheck.ok) {
    return {
      ok: false,
      aborted: 'stale_assignment',
      reason: preCheck.reason,
      assignmentRevision: preCheck.assignment?.assignment_revision ?? null,
    };
  }
  const assignmentEpoch = preCheck.assignment.assignment_revision;

  const cohortState = await readCohortState(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, adapter });
  if (cohortState.state !== 'ready') {
    throw new Error(`Chief of Staff private ontology cohort for epoch ${assignmentEpoch} is not set up.`);
  }

  // Load current members & groups so every indexed source channel is proven entitled to BOTH owner and agent.
  const [members, groups] = await Promise.all([
    listAll(client, `${root}/members`, 'members'),
    listAll(client, `${root}/groups`, 'groups'),
  ]);
  const channelAuthCache = new Map();

  async function isChannelDualAuthorized(ch) {
    if (!ch || !M.isActiveRecord(ch) || isNotesToSelf(ch) || isExcludedSystemChannel(ch)) {
      return false;
    }
    if (channelAuthCache.has(ch.id)) return channelAuthCache.get(ch.id);
    let authorized = false;
    if (ch.kind === 'dm') {
      authorized = M.isSourceChannelAuthorizedForCohort({
        channel: ch,
        members,
        groups,
        ownerNpub,
        agentNpub,
      }).authorized;
    } else if (ch.kind === 'channel') {
      try {
        const grants = await checkGrantsComplete(client, workspaceId, ch.id);
        authorized = M.isSourceChannelAuthorizedForCohort({
          channel: ch,
          grants,
          members,
          groups,
          ownerNpub,
          agentNpub,
        }).authorized;
      } catch {
        // Fail closed: if grants cannot be read or verified completely, never index into owner+agent cohort.
        authorized = false;
      }
    }
    channelAuthCache.set(ch.id, authorized);
    return authorized;
  }

  let syncState = store.getSyncState();
  const scannedCounts = { message: 0, thread: 0, audio_note: 0, task: 0, doc: 0, file: 0 };
  const unsupportedInputs = [];
  const scopeNames = new Map();
  const channelById = new Map();
  let syncComplete = true;

  for (let page = 0; page < maxPagesPerRun; page++) {
    const requestCursor =
      syncState.phase === 'delta' ? syncState.delta_cursor : syncState.snapshot_cursor;
    const url = `${root}/sync?limit=${pageLimit}${requestCursor ? `&cursor=${encodeURIComponent(requestCursor)}` : ''}`;
    const res = await client.request('GET', url);
    if (!res || (res.mode !== 'snapshot' && res.mode !== 'delta')) {
      throw new Error('Malformed /sync response from Tower.');
    }

    for (const s of res.scopes || []) {
      scopeNames.set(s.id, s.name);
    }
    const bundleChannelIds = new Set((res.channel_bundles || []).map((b) => b.channel_id).filter(Boolean));
    const indexedChannelIds = new Set(store.listActiveChannelIds());
    let accessibleChannelIds = null;
    if (Array.isArray(res.channels) && (res.refreshed?.directory || res.mode === 'snapshot' || res.channels.length > 0)) {
      if (res.refreshed?.directory || res.mode === 'snapshot') {
        accessibleChannelIds = new Set();
      }
      for (const ch of res.channels) {
        channelById.set(ch.id, ch);
        // Only query /channels/:id/grants for channels that either carry data in this page
        // or already have active local sources (avoids blowing capability broker rate limits
        // on 50+ untouched channels while still pruning any revoked indexed channel!).
        if (accessibleChannelIds && (bundleChannelIds.has(ch.id) || indexedChannelIds.has(ch.id))) {
          if (await isChannelDualAuthorized(ch)) {
            accessibleChannelIds.add(ch.id);
          }
        }
      }
    }

    const items = [];
    const threadRecords = [];
    const threadMessages = [];

    for (const bundle of res.channel_bundles || []) {
      const chId = bundle.channel_id;
      let ch = channelById.get(chId);
      if (!ch) {
        // Fail closed: fetch channel metadata if omitted from delta response; skip if unverifiable.
        try {
          const chRes = await client.request('GET', `${root}/channels/${encodeURIComponent(chId)}`);
          if (chRes?.channel) {
            ch = chRes.channel;
            channelById.set(chId, ch);
          }
        } catch {
          continue;
        }
      }
      if (!(await isChannelDualAuthorized(ch))) continue;
      const chName = ch?.name || chId;
      const scopeName = ch?.scope_id ? scopeNames.get(ch.scope_id) ?? null : null;

      // Real thread records with authoritative row_version
      for (const th of bundle.threads || []) {
        const active = M.isActiveRecord(th);
        threadRecords.push({
          id: th.id,
          channel_id: chId,
          scope_name: scopeName,
          channel_name: chName,
          title: th.title || '',
          row_version: Number(th.row_version ?? 1),
          updated_at: th.updated_at ?? th.created_at ?? now.toISOString(),
          status: active ? 'active' : 'deleted',
        });
        if (active) scannedCounts.thread++;
      }

      // Messages (also accumulated into thread_messages without overwriting prior pages)
      for (const m of bundle.messages || []) {
        const active = M.isActiveRecord(m);
        const body = String(m.body ?? m.content ?? '');
        const threadId = m.thread_id ?? m.effective_thread_id ?? null;
        items.push({
          source_type: 'message',
          record_id: m.id,
          channel_id: chId,
          thread_id: threadId,
          scope_name: scopeName,
          channel_name: chName,
          title: body.slice(0, 80) || `Message in #${chName}`,
          text_body: active ? body : '',
          revision: String(m.row_version ?? 1),
          updated_at: m.updated_at ?? m.created_at ?? now.toISOString(),
          content_hash: M.sha256Hex(Buffer.from(body)),
          status: active ? 'active' : 'deleted',
        });
        if (active) scannedCounts.message++;
        if (threadId) {
          threadMessages.push({
            thread_id: threadId,
            message_id: m.id,
            channel_id: chId,
            scope_name: scopeName,
            channel_name: chName,
            body: active ? body : '',
            row_version: Number(m.row_version ?? 1),
            created_at: m.created_at ?? m.updated_at ?? now.toISOString(),
            status: active ? 'active' : 'deleted',
          });
        }
      }

      // Audio notes / voice transcripts
      for (const n of bundle.audio_notes || []) {
        const active = M.isActiveRecord(n);
        const transcript = String(n.transcript ?? n.transcript_text ?? n.summary ?? '');
        if (active && !transcript.trim()) {
          unsupportedInputs.push({ source_type: 'audio_note', record_id: n.id, channel_id: chId, reason: 'audio_without_transcript' });
          continue;
        }
        if (active) scannedCounts.audio_note++;
        items.push({
          source_type: 'audio_note',
          record_id: n.id,
          channel_id: chId,
          thread_id: n.thread_id ?? null,
          scope_name: scopeName,
          channel_name: chName,
          title: n.title || transcript.slice(0, 72) || `Voice note in #${chName}`,
          text_body: active ? transcript : '',
          revision: String(n.row_version ?? 1),
          updated_at: n.updated_at ?? n.created_at ?? now.toISOString(),
          content_hash: M.sha256Hex(Buffer.from(transcript)),
          status: active ? 'active' : 'deleted',
        });
      }

      // Tasks (+ task_comments folded into task context without mutating sources)
      const commentsByTask = new Map();
      for (const tc of bundle.task_comments || []) {
        if (!M.isActiveRecord(tc)) continue;
        const arr = commentsByTask.get(tc.task_id) || [];
        arr.push(String(tc.body || ''));
        commentsByTask.set(tc.task_id, arr);
      }
      for (const t of bundle.tasks || []) {
        const active = M.isActiveRecord(t);
        const commentText = (commentsByTask.get(t.id) || []).join('\n').slice(0, 2000);
        const text = `${t.title || ''}\n${t.description || ''}\nstate: ${t.state || ''}${commentText ? `\n${commentText}` : ''}`.trim();
        if (active) scannedCounts.task++;
        items.push({
          source_type: 'task',
          record_id: t.id,
          channel_id: chId,
          thread_id: t.thread_id ?? null,
          scope_name: scopeName,
          channel_name: chName,
          title: t.title || `Task ${t.id.slice(0, 8)}`,
          text_body: active ? text : '',
          revision: String(t.row_version ?? 1),
          updated_at: t.updated_at ?? t.created_at ?? now.toISOString(),
          content_hash: M.sha256Hex(Buffer.from(text)),
          status: active ? 'active' : 'deleted',
        });
      }

      // Docs
      for (const d of bundle.docs || []) {
        const active = M.isActiveRecord(d);
        let bodyText = String(d.body ?? d.summary ?? '');
        if (active && !bodyText) {
          try {
            const full = await client.request('GET', `${root}/docs/${encodeURIComponent(d.id)}/body`);
            bodyText = String(full?.body ?? full?.content ?? '');
          } catch {
            // Keep title metadata if body fetch fails
          }
        }
        if (active) scannedCounts.doc++;
        items.push({
          source_type: 'doc',
          record_id: d.id,
          channel_id: chId,
          thread_id: null,
          scope_name: scopeName,
          channel_name: chName,
          title: d.title || `Doc ${d.id.slice(0, 8)}`,
          text_body: active ? `${d.title || ''}\n${bodyText.slice(0, 12000)}` : '',
          revision: String(d.row_version ?? d.current_version_id ?? 1),
          updated_at: d.updated_at ?? d.created_at ?? now.toISOString(),
          content_hash: M.sha256Hex(Buffer.from(bodyText)),
          status: active ? 'active' : 'deleted',
        });
      }

      // Files / Artefacts (metadata + bounded readable UTF-8 content; binary/oversized explicitly reported)
      for (const f of bundle.files || []) {
        const active = M.isActiveRecord(f);
        if (!active) {
          items.push({
            source_type: 'file',
            record_id: f.id,
            channel_id: chId,
            scope_name: scopeName,
            channel_name: chName,
            title: f.display_name || f.id,
            text_body: '',
            revision: String(f.current_version_id ?? f.row_version ?? 1),
            updated_at: f.updated_at ?? f.created_at ?? now.toISOString(),
            content_hash: '',
            status: 'deleted',
          });
          continue;
        }
        let extracted = '';
        const sizeBytes = f.object?.storage_object?.size_bytes ?? f.size_bytes ?? 0;
        if (!isReadableTextFile(f)) {
          unsupportedInputs.push({
            source_type: 'file',
            record_id: f.id,
            channel_id: chId,
            content_type: f.content_type || 'application/octet-stream',
            reason: 'binary_or_unsupported_mime_indexed_by_metadata_only',
          });
        } else if (sizeBytes > maxFileBytes) {
          unsupportedInputs.push({
            source_type: 'file',
            record_id: f.id,
            channel_id: chId,
            content_type: f.content_type,
            reason: 'file_exceeds_text_index_bound',
          });
        } else if (f.current_version_id) {
          try {
            const raw = await client.readVersionBytes(f.id, f.current_version_id, { maxBytes: maxFileBytes });
            extracted = new TextDecoder('utf-8', { fatal: false }).decode(raw).slice(0, 12000);
          } catch {
            unsupportedInputs.push({ source_type: 'file', record_id: f.id, channel_id: chId, reason: 'file_bytes_unreadable' });
          }
        }
        scannedCounts.file++;
        const combined = `${f.display_name || ''}\n${f.description || ''}\n${extracted}`.trim();
        items.push({
          source_type: 'file',
          record_id: f.id,
          channel_id: chId,
          scope_name: scopeName,
          channel_name: chName,
          title: f.display_name || `File ${f.id.slice(0, 8)}`,
          text_body: combined,
          revision: String(f.current_version_id ?? f.row_version ?? 1),
          updated_at: f.updated_at ?? f.created_at ?? now.toISOString(),
          content_hash: M.sha256Hex(Buffer.from(combined)),
          status: 'active',
        });
      }
    }

    // Advance cursor state faithfully honoring snapshot_complete vs has_more
    const nextPagesSynced = (syncState.pages_synced || 0) + 1;
    if (res.mode === 'snapshot') {
      const throughCursor = res.through_cursor ?? syncState.snapshot_through_cursor;
      if (res.snapshot_complete === true && !res.has_more) {
        syncState = {
          phase: 'delta',
          snapshot_cursor: null,
          snapshot_through_cursor: throughCursor,
          delta_cursor: throughCursor,
          snapshot_complete: true,
          pages_synced: nextPagesSynced,
        };
      } else {
        if (!res.next_cursor) throw new Error('Incomplete snapshot page missing next_cursor.');
        syncState = {
          phase: 'snapshot',
          snapshot_cursor: res.next_cursor,
          snapshot_through_cursor: throughCursor,
          delta_cursor: syncState.delta_cursor,
          snapshot_complete: false,
          pages_synced: nextPagesSynced,
        };
      }
    } else {
      const nextDelta = res.next_cursor ?? res.through_cursor ?? syncState.delta_cursor;
      syncState = {
        ...syncState,
        phase: 'delta',
        delta_cursor: nextDelta,
        snapshot_complete: true,
        pages_synced: nextPagesSynced,
      };
    }

    store.commitSyncPage({
      syncState,
      items,
      threadRecords,
      threadMessages,
      tombstones: Array.isArray(res.tombstones) ? res.tombstones : [],
      accessibleChannelIds,
      now,
    });

    if (onAfterPage) await onAfterPage(page, syncState);

    if (!res.has_more && (res.mode === 'delta' || res.snapshot_complete === true)) {
      syncComplete = true;
      break;
    }
    if (page === maxPagesPerRun - 1) {
      syncComplete = false;
    }
  }

  const activeSources = store.listActiveSources();
  const pendingTotal = store.countPendingExtractionSources();
  const pendingSources = store.listPendingExtractionSources(maxBatchSources);
  const batch = buildExtractionBatch(
    pendingSources,
    cohortState.ontology.document,
    cohortState.corrections.document.corrections,
    { maxSources: maxBatchSources, pendingTotalCount: pendingTotal },
  );

  // Persist exact prepared batch manifest so applyExtractionAndPublish validates proposals strictly against it.
  store.setMeta(
    'last_prepared_batch',
    {
      batch_id: batch.batch_id,
      batch_digest: batch.batch_digest,
      assignment_epoch: assignmentEpoch,
      prepared_at: now.toISOString(),
      sources: batch.sources.map((s) => ({
        source_type: s.source_type,
        record_id: s.record_id,
        channel_id: s.channel_id ?? null,
        revision: s.revision ?? null,
        content_hash: s.content_hash ?? null,
      })),
    },
    now,
  );

  const coverage = {
    supported_types: ['message', 'thread', 'audio_note', 'task', 'doc', 'file'],
    scanned_counts: scannedCounts,
    unsupported_inputs: unsupportedInputs.slice(0, 200),
    sync_complete: syncComplete,
    sync_phase: syncState.phase,
    snapshot_complete: syncState.snapshot_complete,
    total_active_sources: activeSources.length,
    batch_source_count: batch.sources.length,
    pending_extraction_count: batch.pending_remaining_count,
  };

  return {
    ok: true,
    assignmentEpoch,
    syncComplete,
    syncState,
    activeSources,
    coverage,
    batch,
    priorOntology: cohortState.ontology.document,
    corrections: cohortState.corrections.document.corrections,
  };
}

export async function applyExtractionAndPublish(
  client,
  store,
  {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    assignmentEpoch,
    proposals,
    coverage,
    batchSources = null,
    expectedBatchDigest = null,
    adapter = M.PLAINTEXT_ADAPTER,
    now = new Date(),
    onBeforePublish = null,
  },
) {
  const latestCohort = await readCohortState(client, { workspaceId, ownerNpub, appNpub, agentNpub, assignmentEpoch, adapter });
  if (latestCohort.state !== 'ready') {
    throw new Error(`Chief of Staff private ontology cohort for epoch ${assignmentEpoch} is not set up.`);
  }

  const persistedBatch = store.getMeta('last_prepared_batch');
  if (expectedBatchDigest && persistedBatch?.batch_digest && persistedBatch.batch_digest !== expectedBatchDigest) {
    throw new Error(`Prepared batch digest mismatch: expected ${expectedBatchDigest}, stored ${persistedBatch.batch_digest}.`);
  }
  const effectiveBatchSources = Array.isArray(batchSources)
    ? batchSources
    : Array.isArray(persistedBatch?.sources)
      ? persistedBatch.sources
      : null;

  const activeSources = store.listActiveSources();
  const merged = mergeStructuredExtraction(proposals, activeSources, latestCohort.ontology.document, {
    batchSources: effectiveBatchSources,
    now,
  });
  const applied = M.applyCorrections(merged.topics, merged.evidence, latestCohort.corrections.document.corrections);
  store.replaceIndex(applied.topics, applied.evidence);

  if (onBeforePublish) await onBeforePublish();

  // Guard 2: immediately before publishing (prevents stale worker publishing after reassignment/removal)
  const postCheck = await assertActiveAssignment(client, { workspaceId, ownerNpub, appNpub, agentNpub, adapter });
  if (!postCheck.ok || postCheck.assignment.assignment_revision !== assignmentEpoch) {
    return {
      ok: false,
      aborted: 'stale_assignment',
      reason: postCheck.ok ? 'assignment_revision_changed_during_run' : postCheck.reason,
      assignmentRevision: postCheck.assignment?.assignment_revision ?? null,
    };
  }

  const syncState = store.getSyncState();
  const finalCoverage = coverage || {
    supported_types: ['message', 'thread', 'audio_note', 'task', 'doc', 'file'],
    scanned_counts: { message: 0, thread: 0, audio_note: 0, task: 0, doc: 0, file: 0 },
    unsupported_inputs: [],
    sync_complete: syncState.snapshot_complete,
    sync_phase: syncState.phase,
    snapshot_complete: syncState.snapshot_complete,
  };

  const nextSnapshot = M.validateOntology(
    {
      schema: M.SCHEMA_ONTOLOGY,
      schema_version: 1,
      workspace_id: workspaceId,
      workspace_owner_npub: ownerNpub,
      app_npub: appNpub,
      cohort_id: M.cohortId(ownerNpub, agentNpub, assignmentEpoch),
      assignment_epoch: assignmentEpoch,
      cohort_kind: 'owner_agent_private',
      agent_npub: agentNpub,
      snapshot_revision: latestCohort.ontology.document.snapshot_revision + 1,
      assignment_revision_seen: postCheck.assignment.assignment_revision,
      generated_at: now.toISOString(),
      cursor: store.allCursors(),
      coverage: finalCoverage,
      topics: applied.topics,
      evidence: applied.evidence,
    },
    { workspaceId, ownerNpub, appNpub },
  );

  const ontologyBytes = M.encodeEnvelope(nextSnapshot, adapter);
  const savedOntology = await writeVerifiedVersion(
    client,
    latestCohort.cohort.files.ontology.id,
    latestCohort.ontology.versionId,
    ontologyBytes,
    M.MIME_ONTOLOGY,
    M.FILE_NAME_ONTOLOGY,
  );

  const savedStatus = await publishJobStatus(client, {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    assignmentEpoch,
    adapter,
    now,
    statusPatch: {
      assignment_revision_seen: postCheck.assignment.assignment_revision,
      schedule_state: latestCohort.jobStatus.document.autopilot_job_id ? 'healthy' : 'pending',
      last_run_at: now.toISOString(),
      last_success_at: now.toISOString(),
      last_error: null,
      topic_count: applied.topics.filter((t) => !t.archived).length,
      evidence_count: applied.evidence.filter((e) => e.status === 'active').length,
      cursor_summary: `${syncState.phase}${syncState.snapshot_complete ? ' (complete)' : ' (backfilling)'} · ${syncState.pages_synced} pages`,
      coverage: finalCoverage,
    },
  });

  if (Array.isArray(effectiveBatchSources) && effectiveBatchSources.length > 0) {
    store.markSourcesExtracted(effectiveBatchSources, now);
  }
  store.setMeta('last_snapshot_revision', nextSnapshot.snapshot_revision, now);
  return {
    ok: true,
    snapshotRevision: nextSnapshot.snapshot_revision,
    ontologyVersionId: savedOntology.versionId,
    statusVersionId: savedStatus.versionId,
    topicCount: applied.topics.filter((t) => !t.archived).length,
    evidenceCount: applied.evidence.length,
    rejectedProposalCount: merged.rejectedProposals.length,
    rejectedProposals: merged.rejectedProposals,
    pendingExtractionCount: store.countPendingExtractionSources(),
    coverage: finalCoverage,
  };
}

export async function runIncrementalSync(
  client,
  store,
  {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    extractor,
    adapter = M.PLAINTEXT_ADAPTER,
    limits = {},
    now = new Date(),
    onBeforePublish = null,
    onAfterPage = null,
  },
) {
  if (typeof extractor !== 'function') {
    throw new Error('runIncrementalSync requires a structured semantic extractor function (batch -> proposals).');
  }
  const prep = await syncSourcesAndPrepareBatch(client, store, {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    adapter,
    limits,
    now,
    onAfterPage,
  });
  if (!prep.ok) return prep;

  const proposals = await extractor(prep.batch);

  return applyExtractionAndPublish(client, store, {
    workspaceId,
    ownerNpub,
    appNpub,
    agentNpub,
    assignmentEpoch: prep.assignmentEpoch,
    proposals,
    coverage: prep.coverage,
    batchSources: prep.batch.sources,
    expectedBatchDigest: prep.batch.batch_digest,
    adapter,
    now,
    onBeforePublish,
  });
}

export async function fetchVerifiedSource(client, store, { workspaceId, ownerNpub = null, agentNpub = null, sourceType, recordId, channelId }) {
  const root = workspaceRoot(workspaceId);
  const routes = {
    message: [`${root}/messages/${encodeURIComponent(recordId)}`, 'message'],
    thread: [`${root}/threads/${encodeURIComponent(recordId)}`, 'thread'],
    audio_note: [`${root}/audio-notes/${encodeURIComponent(recordId)}`, 'audio_note'],
    task: [`${root}/tasks/${encodeURIComponent(recordId)}`, 'task'],
    doc: [`${root}/docs/${encodeURIComponent(recordId)}`, 'doc'],
    file: [`${root}/files/${encodeURIComponent(recordId)}`, 'file'],
  };
  const route = routes[sourceType];
  if (!route) throw new Error(`Unsupported source_type: ${sourceType}`);
  try {
    const res = await client.request('GET', route[0]);
    const record = res?.[route[1]];
    if (!record || record.id !== recordId || record.workspace_id !== workspaceId) {
      store?.markSourceStatus(sourceType, recordId, 'deleted');
      return { available: false, status: 'deleted', record: null };
    }
    if (!M.isActiveRecord(record)) {
      store?.markSourceStatus(sourceType, recordId, 'deleted');
      return { available: false, status: 'deleted', record: null };
    }
    const targetChannelId = record.channel_id ?? channelId;
    if (targetChannelId) {
      const chRes = await client.request('GET', `${root}/channels/${encodeURIComponent(targetChannelId)}`);
      if (!chRes?.channel || isNotesToSelf(chRes.channel) || isExcludedSystemChannel(chRes.channel)) {
        store?.markSourceStatus(sourceType, recordId, 'inaccessible');
        return { available: false, status: 'inaccessible', record: null };
      }
      if (ownerNpub && agentNpub) {
        const [members, groups] = await Promise.all([
          listAll(client, `${root}/members`, 'members'),
          listAll(client, `${root}/groups`, 'groups'),
        ]);
        const grants = chRes.channel.kind === 'channel' ? await checkGrantsComplete(client, workspaceId, targetChannelId) : null;
        const auth = M.isSourceChannelAuthorizedForCohort({
          channel: chRes.channel,
          grants,
          members,
          groups,
          ownerNpub,
          agentNpub,
        });
        if (!auth.authorized) {
          store?.markSourceStatus(sourceType, recordId, 'inaccessible');
          return { available: false, status: 'inaccessible', record: null };
        }
      }
    }
    return { available: true, status: 'active', record };
  } catch (e) {
    if (e.status === 403) {
      store?.markSourceStatus(sourceType, recordId, 'inaccessible');
      return { available: false, status: 'inaccessible', record: null };
    }
    if (e.status === 404) {
      store?.markSourceStatus(sourceType, recordId, 'deleted');
      return { available: false, status: 'deleted', record: null };
    }
    throw e;
  }
}
