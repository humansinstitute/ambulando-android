# Ambulando for Android

A native Kotlin / Jetpack Compose client for a Wingman Tower. Android 10 or newer and an arm64 device are required.
Main navigation, chat, tasks, documents, files, search, Deck and workspace surfaces are native. The document and
Excalidraw editors use bundled web editors; WApps open in the isolated in-app browser.

Import your own Nostr identity, protect it with a PIN, then enter your Tower's HTTPS address. Membership and permissions
come from Tower. Keep your own identity backup: this app does not export an existing identity or recover a forgotten PIN.
Updates signed with the same release certificate retain the identity, connection, history, drafts and unsent work.

## Workflows

- Several workspaces (on one or more Towers) enabled at once, each with its own runtime and storage; workspace → scope → channel selection across chat, tasks, documents and files; independent section navigation per workspace.
- Chat conversations, replies, mentions, reactions, signed edits/deletes, privacy-stripped image and file uploads,
  per-conversation drafts and a durable retrying outbox. Large pasted text becomes a `.txt` attachment with a paged
  editor, offline recovery and sent-file version history.
- Task lists/boards, creation, state/priority/assignee changes, comments and conflict handling.
- Native rich-document reading, bundled lossless editing with edit leases and durable drafts, comments, preserved drafts with reviewable three-way merges, explicit takeover, saved versions, and `[[` wiki linking with create-and-link ([reconciliation](docs/document-reconciliation.md), [wiki links](docs/wiki-links.md)).
- Files and folders, uploads, hash-verified downloads, local previews and Android sharing.
- Notification inbox shared with other Ambulando apps: unread badge, filters, read/dismiss/archive, tap-through, offline reading.
- Local search; Deck activity, personal pins, recent channels, canvases and WApp/agent launchers.
- Native canvas viewport and naplets, task relationships, native Excalidraw previews and bundled board editing.
- Context references, agents/activity, Toolbox, scope/channel management and an HTTPS browser with explicit NIP-07 consent.

See [feature status and validation](docs/parity-matrix.md). Feed, Android OS push notifications, FIPS transport, pipeline/session
viewers, workrooms and reports are not supported. iPhone has APNs-specific registration and tap support; Android needs its own supported provider contract. WApp configuration
and personal agent configuration remain in the web app.

## Privacy

There is no analytics SDK or advertising. Tower traffic is HTTPS, signed over the exact request; redirects are refused.
Identity material is sealed using Android Keystore and a PIN-protected vault. Backups and device transfer are excluded.
Photo attachments are decoded and re-encoded without camera/GPS metadata. See [privacy](docs/privacy.md) for storage,
public-profile requests, browser isolation and optional background reads.

Previously received records remain readable when access is removed, with editing disabled. Server deletion tombstones
still remove records. Keeping a local copy grants no future server access. Disconnecting stops using that Tower's data and moves it to a private recovery folder; removing the identity deletes it.

## Build

Use JDK 21, Android SDK platform 37.2, the pinned Gradle wrapper and Bun for the locked editor dependencies:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=2
(cd web-editors && bun install --frozen-lockfile && bun test test)
```

Set the SDK path in an untracked `local.properties` or `ANDROID_HOME`. Run instrumentation on a dedicated emulator:

```sh
./gradlew :app:connectedDebugAndroidTest --max-workers=2
```

Debug builds can explicitly select isolated, labelled synthetic data using the `studio.otherstuff.ambulando.SAMPLE_DATA`
boolean launch extra. Release builds ignore that extra and cannot enter sample mode. Never use a personal device or
real identity for fixture tests.

See [release and public-export procedure](docs/releasing.md). Do not push private development history to the public
repository. Source is MIT; **brand artwork is excluded** and must be replaced in forks. See [NOTICE.md](NOTICE.md).
