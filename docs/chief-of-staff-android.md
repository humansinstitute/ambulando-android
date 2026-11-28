# Android Chief of Staff Implementation

Android implements the Ambulando Chief of Staff assignment, viewer-specific DM shortcut, searchable topic ontology reference, and durable corrections on top of the shared v1 wire contract in `docs/chief-of-staff.md` and `scripts/chief-of-staff/model.mjs`.

## Ownership and boundaries

- **Core models, ACL verification, correction replay, and API:**
  - `core/src/main/kotlin/studio/otherstuff/ambulando/core/chiefofstaff/ChiefOfStaffModels.kt`
  - `core/src/main/kotlin/studio/otherstuff/ambulando/core/chiefofstaff/ChiefOfStaffAPI.kt`
- **App wiring, Manage Workspace, sidebar shortcut, and topic UI:**
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/ChiefOfStaffApp.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/ChiefOfStaffScreens.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/AdministrationScreens.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/WorkspaceManagement.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/ui/shell/Sidebar.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/ui/nav/Routes.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/ui/nav/RouteState.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/AppModel.kt`
  - `app/src/main/kotlin/studio/otherstuff/ambulando/app/AmbulandoApp.kt`

## Wire contract and security invariants

1. **Manager-controlled, workspace-readable assignment (`ambulando.chief_of_staff`):**
   - Ordinary read-only workspace members read `Chief of Staff.assignment.json` with `verifyAcl = false`, never requiring admin-only `/groups` or `/grants` inventory calls (tested with HTTP 403 on `/groups` and `/grants`).
   - All reads verify authoritative NStack server provenance (`channel.created_by_actor_id/npub`, `file.created_by_actor_id/npub`, `current_version.created_by_actor_id/npub`) against `/members` workspace managers (the verified `workspace_owner_npub` identity or a row with strict boolean `authority.can_manage_workspace === true`; never role labels, group names or permission arrays) and cross-check against the payload's `updated_by_*` fields. Forged non-manager candidates fail closed; canonical channel/file IDs are retained once established.
   - Managers (callers whose own `/me` effective permissions include `workspace.manage`) additionally verify explicit channel grants (including structural `effective_members` checks on system groups) before mutating, write immutable versions using `base_version_id` CAS (`409 stale_base_version`), and verify readback SHA-256 digests.
   - Note on live `/members` provenance (confirmed against Tower `flightdeck-pg.ts`): for non-managers, `GET /members` returns `{ actor }` without `membership`, and `/groups` + `/channels/:id/grants` return `403`. Android strictly reads the server-derived boolean `member.authority.can_manage_workspace` (in addition to `workspace_owner_npub`) so ordinary members can verify both owner-created and delegated-manager-created assignment files; NStack now serves this projection. Missing, `false`, or non-boolean `can_manage_workspace` values fail closed for delegated managers.
   - Note on Autopilot schedule reconciliation: Android implements a narrow authenticated client ([ChiefOfStaffPipelineClient.kt](../core/src/main/kotlin/studio/otherstuff/ambulando/core/chiefofstaff/ChiefOfStaffPipelineClient.kt)) for Autopilot's existing generic pipeline invocation route: `POST /api/pipelines/definitions/:definitionId/runs` when the signer is the workspace owner, or the exact owner-space form `POST /api/owners/:workspaceOwnerNpub/pipelines/definitions/:definitionId/runs` for a delegated manager, so the run always executes in the workspace owner's pipeline space (NIP-98 signed, exact user-confirmed HTTPS host via `ConfirmedAutopilotOrigin`, `ChiefOfStaffPipelineGuard` + `BoundaryGuardTransport` allowlist, no redirects, no `X-App-Npub`, no exported credentials, idempotency key per `(workspace, revision, agent)`). Saving, changing, or removing a Chief of Staff in Manage Workspace invokes this pinned pipeline (`chief-of-staff-reconcile.v1` by default) and reports honest `Completed` / `Accepted` / `DefinitionNotFound (404)` / `Failed` outcomes. If no Autopilot host is confirmed or the reconciliation definition is not yet installed on Autopilot, the assignment still saves and displays an honest pending/setup-error status.
   - Assignment selects only an existing workspace agent (`kind == "agent"`), grants no new source authority, and preserves normal `@` mentions.
   - Assignment and cohort reads are owned by `SyncEngine` with explicit coalesced keys (`chief-of-staff:assignment:<ws>`, `chief-of-staff:cohort:<ws>:<epoch>`), observed on `LocalStore` channel/file/access changes, and revalidated before DM shortcut routing.
2. **Per-assignment-epoch private ontology cohort (`ambulando.chief_of_staff.ontology`):**
   - Cohort channels and files are scoped by `cohort_id = owner:<owner>:agent:<agent>:epoch:<assignment_epoch>` and `assignment_epoch`.
   - Unauthorized viewers (anyone other than the workspace owner or assigned agent) receive `CohortLoadResult.Restricted` immediately without reading or surfacing private topic data.
   - Authorized reads verify strict 2-actor grants (owner + assigned agent only, zero groups, zero extra principals) before and after reading bytes, and verify `is_public == false` and SHA-256 digests.
3. **Encryption-deferred posture:**
   - All payloads use `envelope_version = 1` and `encryption = { mode = "none" }` via `ChiefOfStaffEnvelopeAdapter`.
   - The UI explicitly states that encryption is deferred and never claims payloads are encrypted today.
4. **Viewer-specific DM shortcut:**
   - Pinned near the top of the sidebar when a Chief of Staff is assigned for the active workspace.
   - Resolves strictly the viewer's own 2-party DM (`{viewerNpub, assignedNpub}`) in the DM scope, never opening another member's DM, self-DM, or group DM. Creates a DM only via existing `ScopeChannelManager.createDirectMessage` after refreshing remote DM channels to avoid duplicates.
5. **Workspace & agent switching isolation:**
   - Cached assignment state in `ChiefOfStaffStore` is keyed by `(connectionId, workspaceId, appNpub)`.
   - Switching workspaces, locking identity, or changing the assigned Chief of Staff immediately clears old topic state and selection.
6. **Honest job status and durable corrections:**
   - Distinguishes `Unassigned`, `Assigned · Index setup pending`, `Scheduled`, `Running`, `Healthy`, `Error`, and `Disabled (stale assignment)`.
   - Android consumes saved ontology snapshots and appends durable corrections (`rename`, `merge_topics`, `reject_evidence`, `pin_evidence`, `archive_topic`) via `base_version_id` CAS with bounded conflict retry; it does not run workspace source scans on-device.
   - Supporting evidence drills through to native `WorkRoute.Task`, `WorkRoute.Doc`, `WorkRoute.File`, `WorkRoute.Thread`, or message mentions.
