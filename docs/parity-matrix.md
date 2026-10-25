# Native feature status

The reference is the current native iPhone client, including its later local document-editor changes, rather than the
full web application. This matrix distinguishes implementation from verification. It is a review checklist, not a claim
that an emulator is an authenticated live member session.

| Area | Android implementation | Evidence / qualification |
| --- | --- | --- |
| Identity/PIN/Keystore | Import, trusted-session resume, unlock/lock, unavailable-vault retention, explicit removal | Core and dedicated-device identity tests |
| HTTPS/Tower | Descriptor, exact-body NIP-98, workspace app binding, redirects/FIPS refused | Core transport tests; broker-read live scopes/channels/task parsed by Kotlin |
| Shared navigation | Workspace/scope/channel, DM labels, sidebar/order/collapse, independent section stacks, view lock | Synthetic app workflow retains channel across navigation and Activity recreation |
| Chat | Native messages/threads/replies, drafts, durable signed outbox, retry/discard, mentions, reactions, signed edit/delete | Synthetic send/recreation; core interrupted-send/idempotency and access-removal tests |
| Attachments | SAF and clipboard URI uploads, resumable placeholders, stripped image metadata, cached image display, storage-link sharing | Photo privacy/device tests; upload coordinator implementation; end-to-end platform variations need field testing |
| Tasks | Native list/board, create, state/priority/assignee, edit/comments, pending writes and conflicts | Recovered native task screens and tested core/store/write contracts; detailed UI regression coverage is limited |
| Documents | Native rich reader; same bundled Tiptap editor/preservation code; leases, drafts, formatting, uploads, comments/resolve/reopen, recovery | Ten editor preservation tests; synthetic on-device no-op rich-envelope save is byte-for-byte preserved |
| Files | Context lists/folders, upload, hash-verified download, image/text preview, external viewer/share | Core storage/integrity tests; FileProvider scoped to download cache; platform chooser paths need field testing |
| Deck/search/pins | Native card pager, activity filters/read state, pins, canvases/recent/launchers; local FTS | Core projection/store tests and synthetic inbox; broad card/action UI regression coverage remains limited |
| Sync | Bounded/coalesced scheduler, reserved user work, foreground resumable events, opt-in WorkManager reads, durable outbox/writes | Core scheduler/store tests; synthetic send workflow; cold-process WorkManager transfer recovery is implemented but not physically power-loss tested |
| Profiles | Verified kind-0 relay profiles, names/avatars, separate credential-free cache and opt-out | Core profile policy/signature tests and avatar device tests |
| Canvas | Native pan/zoom and drawing, live native naplets, create/rename/add/remove/layout edits | Core scene/edit tests; broad integrated canvas editing regression coverage remains limited |
| Task tree/boards | Native relationship graph and create child/successor; native board previews and bundled Excalidraw editing/versions | Core graph/board tests and editor-host/device rendering tests; full multi-device conflict exercise remains a field-test item |
| Context/agents/Toolbox | Native tree/references, agents/activity and HTTPS launchers | Core parsers/policies, synthetic records; WApp and personal-agent configuration stays in the web app |
| Browser/NIP-07/HTML | Isolated browser/editor/artefact profiles; native consent; no-network HTML; renderer-exit recovery | Device bridge/sandbox tests and core consent/policy tests; unsupported WebViews fail closed |
| Management | Scope/channel create/rename/prompt/order/archive through the shared engine | Core API/management tests; server decides authority |
| Access loss | Keep previously received content read-only, stop queued fetches/mutations; true tombstones delete | Core store and new retained-document/unsent-message integration tests; intentional difference from iPhone purge behavior |

## Unsupported on both native clients

Feed, push notifications, direct Autopilot pipeline/session views, FIPS transport, workrooms and reports. Web-owned
WApp and personal-agent configuration is not recreated. The app does not promise indefinite background execution.

## Validation limits

Emulator data and identities are synthetic and stored separately. No real identity was imported into an emulator and
no fixture was sent to a real channel. Broker-authorized host reads verify some live response contracts; the broker's
workspace-wide discovery route is outside this worker's authority. Authenticated live in-app member workflows, real
device vendor WebView differences, multi-device conflicts and OS process/power-loss transfer behavior require field
review. Those are verification limits, not evidence of tested live parity. See the release notes for the exact build.
