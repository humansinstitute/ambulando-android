# Workspace administration on Android

Manage Workspace is native Compose, reached from **Manage workspace** at the foot of the sidebar (also from a scope's *New channel*
and a channel's settings). It mirrors the iPhone/Mac client's administration workflows (that client's contract is the reference) over the same typed Tower routes and adds none. Tower is the authority; a visible or cached record is never a grant.
All administration requests are confirmed online writes: nothing is queued while offline, nothing is retried automatically, and every
screen says which workspace it is for.

`W` is `/api/v4/flightdeck-pg/workspaces/:workspaceId`. Every request is signed as the unlocked identity (NIP-98, exact body). No
owner impersonation, no private-key handling, no cache purge or cursor reset.

## Actions

| Action | Authority the app requires (fresh) | Route | Notes |
| --- | --- | --- | --- |
| Workspace name, address, description, avatar URL | administrator | `PATCH W` | Validation before send (HTTPS avatar without credentials, lower-case slug). The catalog learns Tower's answer from a forced discovery read, not from the submitted text. |
| Public avatar image | administrator, rechecked before upload | `POST /api/v4/storage/prepare` (public), upload, complete | Explicit public-visibility consent; metadata-stripped PNG; <= 8 MB. |
| Delete workspace | administrator; typed workspace name | `DELETE W` with `confirmation` | Destructive; Tower's owner/protected-workspace rules apply. Never exercised against a real workspace. |
| New channel with explicit access | creator of a channel (`channel.create` on the scope) | `POST W/scopes/:s/channels` with `grants` | Only the people and groups ticked receive access; scope roles and group ownership never add anyone. An explicit grant for the creator is dropped (after a fresh `/me`) so Tower's creator recovery bundle stays. |
| New scope with starting channels | `scope.create` | `POST W/scopes`, then `POST W/scopes/:s/channels` for each missing template channel | Blank or Product template (bundled, with title and working directory). Each step is confirmed and saved separately; an interrupted setup resumes from the saved scope and never repeats a scope create that may have reached Tower. |
| Add member (person/agent) | administrator | `POST W/members` (`role: member`) | No role promotion or removal exists in the supported contract. |
| Member display name | administrator | `PATCH W/members/:actor/profile` | |
| Create group | administrator | `POST W/groups` (`kind: custom`) | |
| Add/remove group person | administrator | `POST W/groups/:g/members`, `DELETE …/members/:actor` | Removing a person keeps their workspace membership. |
| Enrol an outsider into a group | administrator | `POST W/groups/:g/members` with `ensure_workspace_member`, `kind` | Tower's additive atomic contract. No fallback to the role-replacing `POST W/members`, no automatic retry. |
| Attach/detach nested group | administrator | `POST/DELETE W/groups/:g/child-groups[/:child]` | Self, already-nested and cycle hints are local; Tower decides. Detaching does not delete the group. |
| Channel grant create/change/revoke | administrator, **or** a fresh grant naming `channel.grants.manage` on that exact channel (directly or through a group, nested groups included) | `POST/PUT/DELETE W/channels/:c/grants…` | `channel.manage`, scope roles and workspace-wide permission unions never count. Custom bundles are preserved; only revoke is offered. |
| Scope role grant/revoke | the scope's own `can_manage` from a read made now | `GET/PUT W/scopes/:s/grants` | Holds the canonical scope edit lease and sends the editor's `expected_revision`; a changed revision is reported and never rebased. |
| App delegation create/revoke | administrator | `POST W/wapp-delegations[/:id/revoke]` | One explicit app, destination channel and a future expiry (1/7/30 days); no wildcard. |
| Installation reconcile/revoke | administrator, **or** an active, unexpired, non-revoked delegation of the member naming that installation (or its app and every destination) | `POST W/wapp-installations/:id/reconcile|revoke` | Listing an installation never enables a write. |
| Managed app activation | administrator, or one explicit active delegation covering the app and destination | Autopilot `GET /api/wapps/activation-catalog`, `POST W/wapp-install-intents`, Autopilot `POST /api/wapps/install-intents/process` | Only to the HTTPS Autopilot origin the person types: the normalized origin is shown and confirmed **before the catalog request**, then again for activation and for a retried claim. Tower metadata never selects the host and redirects are refused. The saved intent id is bound to that origin and to the full workspace boundary; a claim for another origin or boundary, or from a closed screen, is refused before anything is sent, and a retry processes that exact intent and never creates another installation. |
| Publishing grant add/edit | administrator | `PUT W/wapp-publishing-grants/:id` | Destinations and open origins are replaced; a destination the member cannot see blocks the save instead of being dropped. |
| Publishing disable/revoke/rotate | administrator | `POST …/disable|revoke|rotate` | Public keys only; rotation sends a nonce and a ten-minute expiry. Owner step-up is shown as a refusal. |

Not offered, because the supported contracts do not exist for them (the iPhone does not offer them either): group rename/delete,
removing or promoting a workspace member, managed-app uninstall/runtime teardown.

## How authority is decided

1. A screen opened for a workspace reads `/me` and shows what that read allows; while it is outstanding, or if it fails, administration
   controls are **off** (never defaulted on from a cached role). A refusal re-reads it.
2. Every write first reads `/me` again. An administrator is a role `owner`/`admin` or the `workspace.manage` permission. (The one exception is
   claiming a saved activation intent at the Autopilot origin: it carries no authority of its own and Autopilot and Tower decide it.)
3. For anyone else the exact resource is read again: the channel's grants for a channel grant,
   the scope's `can_manage` and revision for scope roles, the delegations and installations for an installation. A group
   grant counts only if the member is, directly or through nested groups, in a group named by a group list read **now**; a
   non-administrator cannot read the group directory, so group-derived authority they cannot show is treated as absent. The
   channel access screen and the write make this same decision from the same fresh directory read, so a screen never offers a
   change the write would refuse. (Groups a scope manager can read through the scope's own access list fill the recipient picker; they
   are never authority. The iPhone's write path likewise reads the administrator-only group list it saved, so this matches its
   effective behaviour.) A group list
   saved earlier (for example while this device held a wider role) never counts. A grant row that lists no permissions and has level
   `manage` is accepted as grant management, as on the iPhone; a row that lists permissions must name `channel.grants.manage`.
4. The request is sent only if the screen is still the one for that boundary (see below). Tower checks everything again.

## Activation transport guards

Two operations are allowed to reach a host that is not Tower, and only as a pair of exact requests at the one confirmed origin: a `GET` of
`/api/wapps/activation-catalog` and a `POST` of `/api/wapps/install-intents/process` whose body is exactly this workspace and this intent. Each is
checked in three places: the signed-request client only builds those two paths (`TowerOrigin.url`), the activation step wraps the workspace
runtime's gated transport in a guard for that method, host, port and path (no query, credentials, app header or other body), and the runtime's own
boundary guard refuses every other `/api/wapps/` path and any query. Tower's `/api/v4` checks are unchanged. A catalog entry that names another
Autopilot is not offered and cannot be activated through this origin.

## Boundary binding and delayed callbacks

`WorkspaceAdministrator` is created by one `WorkspaceRuntime` and refuses any other origin, signer, application or workspace. It sends
through that runtime's guarded transports (reads on the read gate, writes on the write gate), schedules through its scoped scheduler,
and stores lists and drafts in its own partition. Leaving the screen, or another workspace coming to the front, closes it: a step that
has not yet sent is refused, and a reply that arrives later changes no screen state (the screen drops it; a request already on its way
still completes on Tower and is simply not reported). A stale screen shows "Workspace changed" and no controls.

## Drafts, failures and readback

Each form keeps its entries per connection, workspace and form on this device and writes them before sending. A refusal or lost
connection never clears them, and an acknowledged unchanged draft cannot be sent again without an edit. A saved draft is restored only over the
values it was started from: if Tower's record changed meanwhile (someone renamed the workspace) the draft is set aside and the current values shown,
so an old entry cannot undo another person's change. A create that carries a generated identifier (a new publishing installation, an activation)
keeps it across a failed save, so a retry repeats the same idempotent request, and starts fresh once it has finished (the open editor
restarts empty with a new identifier, so an edit cannot overwrite the installation it just created). After a write the affected list
is read back: *Saved on Tower* with an observed result, *Saved on Tower, the refreshed list does not show it yet*, or
*Saved on Tower, but the list could not be refreshed* are three different messages, and all three count as saved (the create is not offered again).
A lost reply never claims that nothing changed: it says Tower may have received the request and to refresh before trying again.

Lists show their own state — loading, restricted (Tower shows it to administrators or the exact resource's managers), not offered by this Tower,
or unavailable offline — with the saved rows kept below, so an empty list is never read as "not allowed".

## Remaining limits

See [parity-matrix.md](parity-matrix.md). Notably: the synthetic Tower used by tests and sample data implements these routes as
documented and is not evidence of live Tower behaviour; no authenticated live administration was run; a delegation's optional
installation/origin filter combinations beyond one app and one destination are read and shown but not created (the iPhone's delegation editor builds exactly one app, scope and channel as well, so this is shared, not an Android-only omission); Android has no OS
push.

## Reusable checks

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :core:test --tests '*AdministrationTests*'          # requests, authority, boundary, failures
./gradlew --offline :app:testDebugUnitTest --tests '*AdministrationUiTest*'   # the real screens (Robolectric, JVM secp256k1)
# Dedicated emulator only (synthetic data):
./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=studio.otherstuff.ambulando.app.integration.AdministrationFlowTest
```
