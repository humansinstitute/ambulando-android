# 0.1.3 — Android build 5

Large pasted text becomes an exact UTF-8 text attachment while preserving the composer and selection. Open draft
attachments to rename or edit them in bounded pages. Sent text attachments support cached reading, copying, downloading,
new versions and earlier history while preserving the original message attachment. Local drafts and unfinished sent
edits survive offline use and relaunch; conflicts keep your edit with immediate resolution actions. Keep on device
retains unsent edits until you explicitly save to Tower. Filename updates resume separately
from accepted text versions. Android clipboard size limits apply to Copy; Download preserves the full file.

# 0.1.2 — Android build 4

Recover transient read failures with bounded retries and fresh request signatures. Refresh on network return,
clear recovered Inbox warnings, and keep cached complete snapshots on failed refresh. Settings adds simple connection
status, Retry, and explicit export/clear of bounded private diagnostics. Diagnostics exclude content and identifiers;
see docs/privacy.md for retention and sharing. No automatic upload is added.

# 0.1.1 — Android build 3

Exclude private development revision metadata from the packaged APK. Application behavior is unchanged from build 2.

# 0.1.0 — Android build 2

Initial native Kotlin Android release. Adds identity/PIN/Keystore storage, native shared navigation, chat and durable
signed outbox, tasks, rich-document reading and bundled lossless editing, files/search/Deck/pins, canvas/task-tree/board
surfaces, Context/agents/Toolbox, isolated Nostr browser and scope/channel management.

Downloaded history remains read-only after access removal; server deletion tombstones still delete. Photos are
re-encoded without metadata, and backups/device transfer are excluded. See docs/parity-matrix.md for feature evidence,
unsupported features and validation limits. This release has synthetic-emulator and host read-only contract evidence;
authenticated live in-app member use remains for field review.
