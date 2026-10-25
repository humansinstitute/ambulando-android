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
