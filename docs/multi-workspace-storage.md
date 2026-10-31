# Workspace storage boundaries and runtimes

Ambulando for Android keeps several workspaces at once, on one or more Towers, with the same unlocked identity. This
document records how storage, scheduling and navigation are partitioned so that no workspace's data or writes can reach
another's. It mirrors the iPhone/Mac design (`docs/native-multi-workspace.md` and `docs/multi-workspace-storage.md` in the
Swift project) with one deliberate difference: Android keeps previously received content readable after access is lost.

## Boundary

A **boundary** is the normalised Tower HTTPS origin, the signing identity, the application namespace (`app_npub`) and the
workspace id. Server ids are meaningful only inside a complete boundary: two Towers (or two identities, or two application
namespaces) may serve the same workspace id. The boundary id is a versioned, length-delimited SHA-256 digest
(`ambulando.boundary.v1|…`), used as the partition directory name, the scheduler namespace and the cache directory name.
The *connection namespace* (origin and signer only) groups the workspaces of one Tower for scheduling and removal.

## Partitions and the catalog

- `Store/ambulando.sqlite` is the **catalog**: connections, each connection's discovered workspace list, workspace
  preferences (enabled set, order, collapse, colour), the workspace in front, global settings and the background report. It
  never serves content, drafts, the outbox or the write queue after the upgrade.
- `Store/Workspaces/<boundary-id>/ambulando.sqlite` is one **partition** per boundary, using the same schema as before. It
  holds that workspace's cached content, drafts (message and document), outbox, write queue, event cursor, navigation
  and route state, pins, notifications, profiles and its own connection and workspace rows. Reads, writes and change
  notifications cannot cross partitions because they never share a store instance.
- Staged attachment/upload bytes live in shared directories under unique ids referenced from partition rows. Downloaded
  files (`cache/AmbulandoFiles/<boundary-id>/`) and unsaved board edits (`board-drafts/<boundary-id>/`) are rooted at the
  boundary. Text-attachment edits and caches were already qualified by connection and workspace.

## Upgrade migration

The first time a boundary is opened, the pool:

1. makes one consistent backup of the catalog, including committed WAL pages (`VACUUM INTO`), written to a temporary file
   and moved into place without replacing an existing backup (`Store/Workspaces/legacy-cache-v1.sqlite`);
2. opens the partition, saves the canonical connection and workspace row, and imports from the backup in **one
   transaction that also records a completion marker**, so an interrupted import rolls back and a completed one is never
   repeated (even after the rows were edited, sent or discarded).

Import rules (nothing is guessed):

- workspace-only cached rows move only when the backup lists that workspace under exactly one connection whose origin,
  signer and application match the boundary;
- connection-qualified private rows (drafts, outbox, writes, pins, notifications, profiles, document drafts) move only for
  the exact connection and workspace, and for rows carrying origin, signer or application fields only when those match;
- only recognised, exactly qualified navigation keys move, plus document review journals and upload receipts keyed by the exact connection and workspace; the event cursor and personal scope order move only with uniquely owned cache (a cursor never claims an empty partition is caught up); unknown state stays in the backup;
- outbox and write request blobs and ids, ambiguity flags, staged-file references and draft bytes are copied unchanged, and
  interrupted sends are recovered afterwards by the existing idempotent paths **before** the write gate opens;
- ambiguous cache stays in the backup and is withheld from every partition; it is refetched through the correct connection;
- unsaved board drafts move only when the partition imported uniquely and knows the board file; originals are retained;
- downloaded file bytes are not migrated (they are re-downloadable); the old unqualified cache is simply no longer read.

If a partition cannot be opened or imported, the workspace shows a recoverable message and nothing is served from the
catalog instead. A first run after the upgrade enables the workspace the previous build remembered, plus any workspace that
still holds unsent messages, queued changes or saved drafts, so queued work is picked up rather than left behind.

## Runtimes and scheduling

Each enabled workspace has a `WorkspaceRuntime`: partition, scoped scheduler, live feed and stream, outbox, write sender,
document saver, composer uploads and profile sync. One shared `SyncEngine` runs all network work (still the sole
network/update owner). Runtimes talk to it through a scoped view that

- prefixes every job key with the boundary (identical workspace ids never coalesce, cancel or deduplicate together),
- tags jobs with their boundary and connection so equal-priority work **rotates between workspaces** and **one Tower can
  occupy at most three of four slots** (one slot stays reserved for user/visible work, as before),
- shows a workspace only its own queue, errors and idle state, and
- cancels only its own work when paused.

## Gates, pause, resume

Every runtime has a **read gate** and a **write gate** (synchronous, atomic, generation-numbered). Pausing closes both
before anything else, then cancels queued and running jobs, retries, reads, the stream and document leases of that boundary.
A response that arrives after a pause or a re-enable is refused even if the transport ignored cancellation. Local draft
and preference writes never need a gate. Every runtime request also passes a **boundary guard**: it must go to the
boundary's own origin, name its own workspace in the path and carry its own application header, otherwise it is refused
before it is sent.

Resume (enable, unlock, foreground, network return) opens reads, revalidates that the exact workspace and application are
still listed for the identity, catches up the saved event cursor and only then opens the write gate and kicks the senders.
With the identity locked, reads can be validated with the optional read-only credential, but the write gate stays closed
until unlock. Direct writes made from screens (canvas, board, scopes, task tree) use the same gates and report a visible
"still confirming access" failure instead of silently waiting; queued writes simply stay queued.

Pause is not Disconnect: pause keeps the partition, drafts, outbox and cursors. **Disconnect** stops that connection's
runtimes, jobs and leases first, removes the catalog connection, and only then retires its partitions (moved under
`Store/Workspaces/Retired/` out of the app's reach, for local recovery); no runtime or partition can be opened again for the
disconnected connection id, and a partition left behind by an interrupted Disconnect is retired when a new connection meets it; reconnecting creates a fresh partition that cannot resurrect the old
queue. **Remove identity** deletes the whole `Workspaces` directory including the backup and retired copies.

## Access loss (Android)

When the catalog's discovery no longer lists a workspace, or a runtime's own revalidation or an API answer says so, the
runtime closes its write gate and every later read, stream and lease. Content already received while authorized stays
readable and labelled "no longer available"; pending local work stays queued for recovery and is not sent with any
connection. Notification text purges, redactions and records Tower reports deleted keep their stricter existing rules. A
different signer never sees another identity's partitions or catalog rows.

## Background

Android decides when background work runs; there is no background SSE and no foreground service. Enabled workspaces take turns (the start rotates each window), each within a share of the 25-second budget.
Unsent work is sent only for a workspace whose access was revalidated and only while the identity is unlocked in memory. The
optional read-only credential is pinned to one Tower origin, so with the app locked only that Tower's workspaces refresh.

## Limits

Verified with synthetic Towers and an emulator; not a statement about a live Tower or a physical phone. Presigned object-store
transfers may keep running in the system after a workspace is paused (bytes only; the Tower-side completion step is gated).
Disconnect leaves a recovery copy on the device until the identity is removed.
