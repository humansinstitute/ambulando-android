# Ambulando for Android: architecture

Ambulando for Android is a native Kotlin client for Ambulando (Wingman Flight Deck) workspaces. It mirrors the native
iPhone app: screens are native Jetpack Compose drawing from an on-device SQLite store, and a single sync engine is the
only owner of network work against the existing Tower HTTPS + NIP-98 API. It adds no backend routes and changes no
shared schema. Web content appears only where the iPhone app also uses it (see §6).

This document describes the design. What is implemented and how it was verified is recorded feature by feature in
[parity-matrix.md](parity-matrix.md); a design statement here is not a claim that the feature is delivered.

## 1. Modules

| Module | Contents |
| --- | --- |
| `core` | Pure Kotlin/JVM, no Android dependencies, unit-tested on the JVM: Nostr keys and events (NIP-01, NIP-19, BIP-340 over libsecp256k1), NIP-98 request signing over exact body bytes, kind 33358 message instruction signatures, the identity vault's sealing logic, the Tower client, record types, the SQLite store, the sync engine, outbox and write queue, document model and Markdown, canvas/board parsing, browser policy and the NIP-07 broker, profile resolution, Deck projection |
| `app` | Android: Compose UI and theme, Android Keystore secret stores, WorkManager background refresh, WebView hosts (in-app browser, sandboxed HTML artefacts, embedded editors), photo privacy stripping, file picking and sharing |
| `web-editors` | The web client's Excalidraw board editor and Tiptap document editor pages, built with bun into the APK's assets |

The same bundled SQLite (`androidx.sqlite` bundled driver, FTS5 included) runs on Android and in JVM tests, so store
and sync tests exercise the real engine.

## 2. Identity and signing

- The user explicitly imports their own `nsec`. The app never searches for, reads or exports keys held elsewhere.
- The secret is sealed with the user's PIN (PBKDF2-HMAC-SHA256, 210,000 iterations) together with a per-install
  secret; the per-install secret is itself sealed by a non-exportable AES-256-GCM key in the **Android Keystore**
  (StrongBox when the device has one). Only ciphertext is written to app storage, and app data is excluded from cloud
  backup and device transfer.
- After a PIN unlock the app stays unlocked on this device until **Lock now**, through a session copy sealed by a
  Keystore key that requires the device to be unlocked. Lock now, removing the identity and disconnecting revoke it
  fail-closed (a revocation marker is written before the key is deleted).
- *Refresh while locked* (off by default) keeps a separate sealed copy usable only under an app-enforced policy: NIP-98
  `GET` requests to the pinned Tower origin for the pinned workspaces. It is not a cryptographically limited key.
- Every Tower request carries a fresh NIP-98 event (kind 27235) over the exact URL, uppercase method and the SHA-256 of
  the exact body bytes sent. Redirects are refused; only HTTPS origins are accepted; `.fips` hosts are refused rather
  than replaced.
- Chat sends carry a kind 33358 instruction signature over the exact trimmed body with workspace, channel and thread
  tags. The request is frozen once signed and resent unchanged under the same `client_request_id`, so Tower replays
  instead of duplicating.

## 3. Store and isolation

- A **catalog** database (`Store/ambulando.sqlite`) holds connections, discovered workspace lists, workspace preferences and
  global settings. Content, drafts, the outbox and the write queue live in **one SQLite partition per boundary** (normalised
  Tower origin, signer, application namespace, workspace id), opened by a store pool, with the same schema as before: every
  record row is keyed `(workspace_id, id)` inside its own file and keeps Tower's canonical JSON beside its indexed columns, so
  unknown fields survive round trips and migrations. This replaces the earlier single database per install so that identical
  workspace ids on two Towers or identities can never collide; see [multi-workspace-storage.md](multi-workspace-storage.md)
  for the migration, ownership and recovery policy. The catalog's legacy content is a frozen backup, never a fallback.
- Upserts are guarded by `row_version`: an older version never overwrites a newer one.
- Connection rows bind origin, workspace, `app_npub` and signer; a different identity cannot write under another
  identity's connection, and a partition is only ever opened by the exact boundary that owns it.
- **Access loss is non-destructive.** When a scope, channel, record or whole workspace stops being visible, records this
  device already received while authorized stay readable, marked as no longer available, and no further reads, writes or
  leases are made for them (the workspace's write gate closes at once); pending local work stays queued for recovery. Tower
  enforces future access. Records Tower reports as deleted are removed, and notification text is purged or redacted under its
  own stricter rules. (The iPhone app removes local copies on access loss; this is a deliberate difference.)

## 4. Sync

`SyncEngine` is the only network owner, shared by every enabled workspace. Jobs are keyed and coalesced, cancellable,
bounded in concurrency, and backed off exponentially on failure; priorities are `user` (a send, opening a thread),
`visible` and `background`, with one slot reserved for non-background work. Each workspace **runtime** reaches the engine
through a scoped view: its keys are namespaced by boundary, equal-priority work rotates between workspaces, one Tower cannot
occupy every slot, its status shows only its own jobs, and pausing it cancels only its own work. Read and write **gates** close
synchronously on pause and re-open only after the exact workspace is revalidated and its cursor caught up. Screens observe
the store and never call the network.

- Foreground: for every enabled workspace, a bounded event catch-up from its durable cursor, then Tower's SSE stream
  (one per enabled workspace), applied in small batches with tombstones and targeted reads. Only the workspace in front also
  refreshes its task, document and file lists.
- Background: WorkManager periodic work (at least 15 minutes apart, scheduled by Android) runs the same catch-up jobs
  at background priority; enabled workspaces take turns within the window, starting from a rotating position. Unsent messages and changes are sent only while the identity is unlocked. There is no
  background SSE and no keep-alive foreground service.
- Offline: chat sends go through a durable outbox and every other change through a durable write queue; both survive
  process death, retry with backoff, keep per-conversation/per-record order, and show unsent, retrying, failed and
  conflict states.

## 5. Navigation

Several workspaces can be enabled at once (the chooser groups them by Tower and adds another Tower with the same unlocked
identity). Each enabled workspace has its own Home, scopes, channels, collapse state, order and saved colour; with more than
one enabled, the sidebar stacks them under shared **Unread & Active** rows that keep their source workspace in their label and
accessibility description, the header takes a faint 12% tint of the workspace in front and the composer says "Working in
<workspace>". Colour supplements the name and never decides routing: every action carries the full boundary. The
**Workspace → Scope → Channel** context of the workspace in front is used by Chat, Tasks, Docs and Files; it, the open section
and each section's record stack are saved per boundary in that workspace's partition and restored after the process ends.
A bottom section bar follows the web client's mobile order: Deck, Chat, Tasks, Docs, Files, Agents and Context (optional),
Toolbox. Inbox, Search and pins span the workspace in front and move the context when a result is opened. Conversations open in
a focused thread view over the current section. The whole connected screen is rebuilt when the workspace in front changes, so
no remembered state, query or editor can follow a switch into another workspace.

## 6. Web content

- **In-app browser:** HTTPS-only tabs in their own WebView profile. `window.nostr` (NIP-07) is answered only for the top
  frame of the page's committed origin; a site must be allowed to see the public key, every signature is shown for
  approval, and only sign-in kinds (22242, 27235) can be allowed until the app locks. Locking cancels and refuses all
  requests. The key never enters the page; NIP-04/44 encryption is not offered.
- **HTML artefacts:** a labelled view with no script bridge, no network and no navigation.
- **Embedded editors:** the board and document editors are served from the app's assets, have no network, signer or
  `window.nostr`, and talk to the app only through an origin-scoped message bridge.

## 7. Platform limits

- Background execution is opportunistic and decided by Android.
- Android OS push notifications are not available: this app needs a supported Android provider and Tower device-registration contract. iPhone's APNs contract is separate. The
  in-app notification inbox (§8) is supported and works without push.
- FIPS transport is not offered; the app is HTTPS-only and never falls back silently.

## 8. Notifications

Tower owns one durable, actor-private notification inbox per workspace (`org.wingman.notification.item` v1, shared by
every consuming app). Android reads it through the same single sync owner as everything else:

- **Store.** `notification_items` and `notification_sync` are private to the connection (origin + signer) and go with it on
  disconnect. Only the inbox partition is hydrated by default; the archive is loaded on demand and dropped with the cursor.
- **Read path.** A keyed `notifications:<w>` engine job. Cold start pages `items?view=inbox`, takes the first page's `head_seq`
  as the snapshot sequence and replays `changes?after_seq=` from it. The snapshot is resumable: each page is stored with its
  continuation cursor, the snapshot sequence and a generation, a job reads a bounded number of pages and hands the rest to a
  following job, and nothing is declared complete (and no replay starts) until the last page is stored; `local_seq` moves only to a delivered page's
  `through_seq`, in the transaction that applied the page. `410 reset_required` forgets the cursor and starts over.
  The event stream's `notification_item` event is a content-free hint that schedules the job; `changes` is the authority.
  Redelivery is expected and idempotent (`change_seq`, then `row_version`).
- **Write path.** Read/dismiss/archive and bulk changes use the durable write queue with a `mutation_id`, the
  `expected_row_version` and `observed_change_seq` of the row as it was drawn (never a later lookup), and an optimistic overlay kept apart from server state.
  `state_conflict` adopts Tower's item and drops the tap (activity folded in after the user looked is never acknowledged
  by an older tap). A changed request under a used `mutation_id` is a rejection, shown to the user. Bulk changes are
  bounded by `up_to_seq`.
- **Per-app view (wire v1.1).** Every list, count, change page and bulk selector carries `client_app_id=ambulando`, a
  presentation filter and never access authority: Tower returns what this app may show (audience, and the person's inbox
  rules for this app), and the unread badge uses the same predicate as the list. Read state is shared with other apps. The
  first snapshot page's `rules_revision` is stored with the snapshot and sent with `changes`; a `410 reset_required` (or a
  page/count under a different revision) starts over from a new snapshot and Settings reads the rules again. A page that
  filtered everything may advance `through_seq` to the head; one that claims more without moving is refused.
- **Rules (preferences).** Category switches write the app-specific key (`client_app_id=ambulando`, never the wildcard,
  which would change other apps) by per-key compare-and-set: each entry carries `expected_row_version`, the exact key's
  `row_version` from the last read (0 only when absent; a `default` row is kept and its version still counts). Writes are
  all-or-nothing; `409 rules_conflict` adopts every current rule and the revision, shows a note and leaves the choice to the
  person, whose retry uses a new `mutation_id`. A reused id with a different body is a rejection.
- **Resource viewed, notification read and feed state stay separate.** Inbox actions never write view state; opening a
  record uses the existing view-state flow, and Tower derives notification read from it.
- **Redaction and access.** An unavailable item shows no text and cannot be opened; a content-free tombstone removes the
  row; a 403 purges stored notification text for that workspace; a missing route (older Tower) is reported as unsupported.
- **Not offered.** Alerts, phone push, quiet hours and per-device settings (not implemented by Tower; no push provider is
  configured) are described as unavailable rather than shown as controls.
