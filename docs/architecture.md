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

- One SQLite database per install (WAL). Every record row is keyed by `(workspace_id, id)` and keeps Tower's canonical
  JSON beside its indexed columns, so unknown fields survive round trips and migrations.
- Upserts are guarded by `row_version`: an older version never overwrites a newer one.
- Connection rows bind origin, workspace, `app_npub` and signer; a different identity cannot write under another
  identity's connection.
- **Access loss is non-destructive.** When a scope, channel or record stops being visible, records this device already
  received while authorized stay readable, marked as no longer available, and no further reads or writes are made for
  them; Tower enforces future access. Records Tower reports as deleted are removed. (The iPhone app removes local
  copies on access loss; this is a deliberate difference.)

## 4. Sync

`SyncEngine` is the only network owner. Jobs are keyed and coalesced, cancellable, bounded in concurrency, and backed
off exponentially on failure; priorities are `user` (a send, opening a thread), `visible` and `background`, with one
slot reserved for non-background work. Screens observe the store and never call the network.

- Foreground: a bounded event catch-up from a durable per-workspace cursor, then Tower's SSE stream, applied in small
  batches with tombstones and targeted reads.
- Background: WorkManager periodic work (at least 15 minutes apart, scheduled by Android) runs the same catch-up jobs
  at background priority. Unsent messages and changes are sent only while the identity is unlocked. There is no
  background SSE and no keep-alive foreground service.
- Offline: chat sends go through a durable outbox and every other change through a durable write queue; both survive
  process death, retry with backoff, keep per-conversation/per-record order, and show unsent, retrying, failed and
  conflict states.

## 5. Navigation

The shared **Workspace → Scope → Channel** context, chosen in the sidebar, is used by Chat, Tasks, Docs and Files and
remembered per workspace. A bottom section bar follows the web client's mobile order: Deck, Chat, Tasks, Docs, Files,
Agents and Context (optional), Toolbox. Inbox, Search and pins span the workspace and move the context when a result
is opened. Conversations open in a focused thread view over the current section.

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
- Push notifications are not available (Tower provides Web Push only).
- FIPS transport is not offered; the app is HTTPS-only and never falls back silently.
