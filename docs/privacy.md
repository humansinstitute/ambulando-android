# Privacy and storage

Ambulando connects to the Tower address you choose. It sends the selected workspace's application identity and your
Nostr request signature to that Tower. Messages and mutations use its existing authorization rules. HTTPS and signing
do not make workspace content end-to-end encrypted.

## On this device

The identity vault uses PIN-derived protection and a device-bound Android Keystore secret. Keystore keys are
non-exportable; their hardware/security level depends on the device and is not universally StrongBox-backed.
Trusted-session material and optional background-read credentials are separately sealed. No raw key is placed in
WebViews, shared storage, crash reports or application logs.

Messages, documents, files, drafts and queued writes are stored in private app storage. The SQLite database is not
separately encrypted by this app; it relies on Android's sandbox and device storage protection. Downloaded files and
avatars use app-private cache storage. Cloud backup and device transfer are disabled. An explicit Android share action
grants the chosen receiver temporary access to that file through a narrowly scoped FileProvider.

Upgrades keep the same data paths and signing identity. An unavailable vault is kept and reported as unavailable,
never treated as a missing account. Explicit disconnect/removal is different from an update and removes the associated
local history and drafts. Records whose membership access was removed remain read-only; real deletion tombstones
remove records. The server remains authoritative for all future access.

## Requests beyond Tower

Public Nostr profiles use credential-free requests to relay.primal.net, nos.lol and purplepag.es. Verified profile
pictures may be fetched from their published HTTPS hosts through a separate credential-free cache. Public-profile
refresh can be disabled in Settings. Existing profile cache entries can still supply names when disabled.

WApps and other HTTPS pages opened by the user use the browser's isolated WebView profile. A website needs native
consent to obtain a public key and each event signature needs approval. Origin, main-frame and document checks bind
those requests. Locking or leaving the foreground cancels signing consent. Browser, bundled editors and HTML artefacts
use separate profiles; unsupported WebViews fail closed with an update message.

Bundled editors cannot fetch the network and have no Nostr bridge. Workspace HTML is labelled, has no signing bridge,
and cannot fetch network resources. Editor output is validated and encoded through the original document representation.
Photo uploads are orientation-corrected, resized and re-encoded; camera, time and location metadata are discarded.

## Background work

Foreground updates use a resumable event stream. Android WorkManager supplies opportunistic refresh and transfer time;
it is not an indefinite background connection. Optional background reads are restricted to GETs for the selected Tower
and authorized workspaces. A cold background process loads only that opted-in credential, not a trusted interactive
signing session. Unsent mutations require an unlocked identity in the running process. Lock now revokes background reads.

Presigned object transfers carry no Nostr authorization, cookies or Tower credentials. Their private pending records
allow retries after process death. Android controls scheduling and can delay or stop work; cursors, drafts and outbox
requests remain durable.

The public source/release excludes private operations, development history, signing stores, identity keys, live-record
snapshots and device evidence. Public upstream copyright attribution remains in the bundled license notices.

## Connection diagnostics

Settings shows Connected, Reconnecting or Connection lost, with an always-available Retry action. Read refresh jobs
retry transient timeouts, dropped connections and gateway failures at most three times per job/outage, signing each
attempt afresh. Authorization, validation and certificate failures do not enter this automatic read retry path.
Foreground resume and a debounced return of a validated default network refresh cached views, including Inbox.
The durable write queues retain their existing retry and idempotency rules.

The newest 300 diagnostic entries are retained in an app-private, no-backup file across process restarts. Entries
contain UTC time, a known route template, duration, HTTP status, a fixed error category and retry outcome. Only UUID
request/correlation IDs or constrained Cloudflare ray IDs are retained from response headers. Host names, dynamic
path segments, query values, unknown query names, request headers, keys, tokens, bodies and user identifiers are
excluded. Unknown routes become `/unknown`; a known word in a record-id slot is still redacted.

Failures and recovery decisions are saved immediately; successful traffic is saved at most every five seconds.
Older entries are replaced at capacity. Clear diagnostics empties the persistent log and removes the app's exported
copy. Uninstall/clear app storage also removes it. No diagnostic upload occurs automatically. Export diagnostics
creates a text file with the installed version/build, Android API level and UTC export time and opens Android's
share chooser. Its cache copy is replaced on the next export and removed on the next interactive app session or Clear
diagnostics; Android may evict it earlier. A chosen receiving app controls any copy it saves or shares onward.
