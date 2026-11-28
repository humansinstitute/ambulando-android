# Ambulando Chief of Staff and living workspace topic ontology (v1)

Ambulando owns the Chief of Staff relationship, topic ontology, evidence links, durable corrections and job status formats. NStack supplies existing Flight Deck PG channel ACLs, private storage and immutable File versions with optimistic `base_version_id` CAS (`409 stale_base_version`). Autopilot owns agent runtime, capability broker signing and scheduled execution bound through `scheduler/session-context.ts`. No bespoke Chief of Staff NStack routes or client-held app keys are added.

## Pluggable backend assessment and NStack capabilities

Live verification against NStack Flight Deck PG (Tower) established:

- Generic `workspace_app_rows` (`/db/:collection/rows`) is unsuitable: `visibility="workspace"` allows any workspace member to mutate rows, has no CAS/revision check, and scopes by `workspace_owner_npub` rather than `workspace_id`.
- Generic `native-v1` (`/db/native-v1/:workspaceId/...`) is already active with relational policies installed for existing app tables. Tower `src/services/app-db.ts` (`assertNoNativePolicy`) currently rejects all new schema migrations across an app namespace (`409 native_policy_required`) once any native policy exists, and `native-v1` queries still require channel-level `file.read` grants.

**Workspace member authority (available):** every row of `GET /workspaces/:workspaceId/members` carries `authority: { can_manage_workspace: boolean }`, NStack's server-computed CURRENT effective `workspace.manage` for the listed actor (direct or nested-group grants, unrevoked, current members only). It discloses no roles, groups or grants, grants no capability and is not a historical write-time attestation; `membership` is still returned only to managers. Chief of Staff provenance accepts a writer only when it is the verified `workspace_owner_npub` identity or its row has `can_manage_workspace === true` (the caller may also count itself when its own `/me` effective permissions include `workspace.manage`). Membership role labels, group names and self-asserted permission arrays are never authority. An older server that omits `authority` fails closed for delegated managers; the owner is still recognized.

**Remaining reusable NStack capability gaps (future backend work, not required to ship v1 source):**

1. Historical source-revision reads: evidence fetches return the current authorized record; indexed revisions show drift but cannot be re-read.
2. **Additive schema evolution with existing native policies:** allow verified `CREATE TABLE` migrations for new unpolicied tables without disturbing frozen/active existing tables.
3. **Workspace-readable, manager-writable native-v1 table policy:** allow `read_scope: "workspace_member"` with `manager_only_writes: true` so workspace settings do not require a synthetic channel.

## Live v1 storage contract (zero NStack restart)

### 1. Workspace Assignment channel (workspace-readable, manager-writable)

One non-DM channel carries metadata:

```json
{
  "system_key": "ambulando.chief_of_staff",
  "chief_of_staff": {
    "schema_version": 1,
    "kind": "assignment",
    "workspace_id": "<workspace-uuid>",
    "app_npub": "<app-npub>"
  }
}
```

**Server-enforced ACLs & provenance:**

- Manager-only system group (verified structurally by effective membership rather than trusting mutable group labels) and/or explicit verified workspace managers: `manage` (`channel.read`, `file.read`, `file.write`, `channel.manage`, `channel.grants.manage`).
- Workspace-wide member system group: `view` ONLY (`channel.read`, `file.read`, NO `file.write`).
- Managers and sync workers verify explicit `permissions` arrays on `/channels/:id/grants` (rejecting partial/paginated ACL responses and rejecting any non-manager actor or unverified group holding `file.write`).
- Ordinary read-only workspace members (who receive `403` on admin-only `/groups` and `/grants`) verify server-origin manager provenance on `file.created_by_actor_id`, `file.updated_by_actor_id`, and the current version's `created_by_actor_id` / `created_by_actor_npub` against `/members`, rejecting forged metadata candidates created by non-managers.

**Canonical Assignment File:**

- Display name: `Chief of Staff.assignment.json`
- MIME: `application/vnd.ambulando.chief-of-staff-assignment+json`
- Metadata: `{"chief_of_staff":{"schema_version":1,"kind":"assignment","workspace_id":"<ws>","app_npub":"<app>"}}`
- Content (encryption-ready envelope):

```json
{
  "envelope_version": 1,
  "encryption": { "mode": "none" },
  "payload": {
    "schema": "ambulando.chief_of_staff.assignment",
    "schema_version": 1,
    "workspace_id": "<workspace-uuid>",
    "workspace_owner_npub": "<owner-npub>",
    "app_npub": "<app-npub>",
    "assignment_revision": 1,
    "assigned_actor_id": "<agent-actor-uuid-or-null>",
    "assigned_npub": "<agent-npub-or-null>",
    "assigned_display_name": "Rick",
    "previous_assigned_npub": null,
    "runtime_binding": {
      "autopilot_origin": "https://autopilot.example.invalid",
      "reconcile_pipeline_id": "chief-of-staff-reconcile.v1",
      "ontology_pipeline_id": "chief-of-staff-ontology.v1",
      "subscription_id": "<subscription-uuid>",
      "workspace_id": "<workspace-uuid>",
      "owner_npub": "<owner-npub>",
      "agent_npub": "<agent-npub-or-null>",
      "cron_expression": "0 * * * *",
      "timezone": "Australia/Perth"
    },
    "updated_by_actor_id": "<manager-actor-uuid>",
    "updated_by_npub": "<manager-npub>",
    "updated_at": "2026-11-28T00:00:00.000Z"
  }
}
```

Assignment grants **no new authority** to the selected agent. Removing the Chief of Staff writes a new immutable version with `assigned_actor_id: null`, `assigned_npub: null`, incrementing `assignment_revision` and preserving history. All writes use `POST /files/:id/versions` with `base_version_id` CAS (`409 stale_base_version`).

### 2. Owner+Agent Private Ontology Cohort channel (per-assignment-epoch isolation)

To guarantee private-source knowledge is never silently promoted workspace-wide and a previously assigned agent never gains access to a newly assigned agent's ontology/status outputs, v1 stores ontology state in an explicit `owner_agent_private` cohort channel per assignment epoch:

```json
{
  "system_key": "ambulando.chief_of_staff.ontology",
  "chief_of_staff": {
    "schema_version": 1,
    "kind": "ontology_cohort",
    "cohort_kind": "owner_agent_private",
    "cohort_id": "owner:<owner-npub>:agent:<agent-npub>:epoch:<assignment-epoch>",
    "assignment_epoch": 1,
    "workspace_id": "<workspace-uuid>",
    "app_npub": "<app-npub>",
    "owner_npub": "<owner-npub>",
    "agent_npub": "<agent-npub>"
  }
}
```

**Server-enforced ACLs:**

- Direct actor grants ONLY for `owner_npub` (`channel.read`, `file.read`, `file.write`, `channel.manage`, `channel.grants.manage`) and `agent_npub` (`channel.read`, `file.read`, `file.write`).
- Zero groups and zero additional principals. Every read and write validates explicit `permissions` arrays against current workspace members before reading or writing bytes.

**Three separate canonical Files (zero write contention between assignment, status, ontology, and corrections):**

1. `Chief of Staff.job-status.json` (`application/vnd.ambulando.chief-of-staff-job-status+json`)
   - Separates Autopilot schedule/job status from the workspace assignment.
   - `schedule_state`: `unconfigured | pending | scheduled | running | healthy | error | disabled_stale`.
   - Records `autopilot_job_id`, `bound_agent_npub`, `assignment_revision_seen`, `cron_expression`, `timezone`, `last_run_at`, `last_success_at`, `last_error`, `topic_count`, `evidence_count`, `cursor_summary`, `coverage`.
2. `Chief of Staff.ontology.json` (`application/vnd.ambulando.chief-of-staff-ontology+json`)
   - Versioned durable topic ontology (`topics[]`: stable `id`, `name`, `kind`, `aliases[]`, `description`, `archived`, `source_origin`, `updated_at`), ranked multi-topic `evidence[]` (`id`, `topic_id`, `source_type`, `record_id`, `channel_id`, `thread_id`, `source_revision`, `source_title`, `explanation`, `score`, `status`, `pinned`, `updated_at`), `cursor` checkpoint, and explicit `coverage` (`supported_types`, `scanned_counts`, `unsupported_inputs[]`).
   - Wrapped in `envelope_version: 1, encryption: { mode: "none" }`. Encryption is deferred; the envelope and storage adapter permit future client/agent encryption of names, aliases, descriptions, and associations without changing topic IDs, record references, or agent lookup APIs. Not claimed encrypted or operator-unreadable today.
3. `Chief of Staff.corrections.json` (`application/vnd.ambulando.chief-of-staff-corrections+json`)
   - Separate durable list of human/manager corrections (`rename`, `set_description`, `set_aliases`, `set_kind`, `merge_topics`, `reject_evidence`, `pin_evidence`, `archive_topic`).
   - Mutated with `base_version_id` CAS and automatically reapplied on every incremental sync or full rebuild.

## Viewer-specific Chief of Staff DM shortcut

The navigation shortcut pinned near the top of the sidebar reads the workspace Assignment File and opens the **viewer's own** authorized DM channel with `assigned_npub` (`participant_npubs` equal to `{viewerNpub, assignedNpub}` in the workspace's DM scope). If no such DM exists yet, it creates one only through the existing authorized `createDirectMessage` path. It never opens another member's DM, never creates duplicates, and grants no authority.

## Autopilot scheduling, local SQLite FTS5 index, and retrieval

- Schedule reconciliation binds Autopilot's existing scheduler (`scheduler/session-context.ts`) to exact `(ownerNpub, botNpub, subscriptionId, workspaceId)` with `actionType: "pipeline"` and hourly cadence (`0 * * * *`, configurable 15–60 min).
- One schedule per `(workspaceId, assignment)`. Reassignment or removal disables old schedules for that workspace.
- Every run performs a **double stale-assignment guard**: once before scanning and again immediately before publishing. Stale runs abort without publishing.
- **Dual source authorization (fail closed):** before indexing any source into the `owner_agent_private` cohort, the engine verifies via complete `/channels/:id/grants` and `/groups` (or `participant_npubs` for DMs) that BOTH `owner_npub` and `agent_npub` are entitled to read that channel. Agent-readable-only channels, other-member DMs, and unverifiable grants are excluded without leaking titles, counts, or topics.
- Agent-local SQLite + FTS5 database stores incremental `/sync` snapshot+delta cursors (crash-recoverable), source revisions/hashes, a persisted extraction queue (`extracted_hash`) so bounded batches drain older unprocessed sources across runs, exact prepared-batch digest binding, deletion/revocation pruning, topics, evidence links, and applied corrections.
- Pipeline steps (`scripts/chief-of-staff/chief-of-staff-ontology.pipeline.json`) use Autopilot's native `DeclarativePipeline` `input.pick` and `$.` state paths, treat all source text strictly as untrusted data (never instructions to execute), and bind proposed evidence strictly to the exact prepared batch.
- Supported source inputs: `message`, `thread`, `audio_note` (voice transcripts), `task` (including comments), `doc` (title/body), and `file` (metadata + bounded UTF-8 text/markdown/JSON/HTML). Notes to Self / self-DMs and system metadata channels are excluded; binary/oversized/unreadable artefacts are reported explicitly in `coverage.unsupported_inputs`.
