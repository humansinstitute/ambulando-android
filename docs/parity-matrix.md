# Native feature status

The reference is the current native iPhone client, including its later local document-editor changes, rather than the
full web application. This matrix distinguishes implementation from verification. It is a review checklist, not a claim
that an emulator is an authenticated live member session.

| Area | Android implementation | Evidence / qualification |
| --- | --- | --- |
| Identity/PIN/Keystore | Import, trusted-session resume, unlock/lock, unavailable-vault retention, explicit removal | Core and dedicated-device identity tests |
| HTTPS/Tower | Descriptor, exact-body NIP-98, workspace app binding, redirects/FIPS refused | Core transport tests; broker-read live scopes/channels/task parsed by Kotlin |
| Shared navigation | Workspace/scope/channel, DM labels, sidebar/order/collapse, independent section stacks, view lock | Synthetic app workflow retains channel across navigation and Activity recreation |
| Chat | One Messages list with per-thread reply summaries opening threads; opens at newest with near-bottom follow and per-conversation reading position; drafts, durable signed outbox, retry/discard, reactions, signed edit/delete | Synthetic send/recreation; core interrupted-send/idempotency and access-removal tests; Robolectric first-layout visibility, follow and restore tests |
| Thread/message menus | Header beside Pin/Close: Copy message, canonical root-based FD Ref, Get it done, latest-session Autopilot Link and author-only confirmed Delete. Per-message menus share the actions and offer Branch from here through the existing typed Tower route, retaining the inherited agent for unaddressed replies. Ready-task output/assignee/instructions/excerpt/source metadata use the durable write queue. Sending returns to newest even from older history | Root lookup is independent of the 300 visible messages; missing/deleted sources never substitute replies. HTTPS session links use workspace metadata or the sending agent's own launcher. Android threads occupy a full section, so no modal zoom control is needed. Core MessageFlowsTests, app follow/restore tests and synthetic ThreadMenuFlowTest; no authenticated member validation |
| Mentions | Inline `@` lookahead over the local store: people/agents, documents, tasks, chat threads (the web's canonical `chat:<channelId>#<rootMessageId>` token), channels, scopes, with type prefixes; web tokens, composer pills, structured `mentions` for people/agents only; trigram FTS5 title index (schema 6) | Core lookahead/index tests including a 25,000-record timing bound; flows and opportunities are not stored on Android and are not offered |
| Attachments | SAF and clipboard URI uploads, resumable placeholders inserted at the caret (editable, positionable `![name](storage://…)`), stripped image metadata, cached inline images with a full-screen zoomable overlay, standalone attachments only when not inline, share from the overlay | Photo privacy/device tests; upload coordinator implementation; end-to-end platform variations need field testing |
| Text attachments | Automatic exact UTF-8 paste files, retained composer selection, numbered names/Undo, bounded draft/sent editors, original/version history, offline cache and durable edit recovery | Core boundary, byte preservation, commit failure, context isolation, retry/rename and conflict tests; synthetic emulator paste/recreation/send/edit/history/conflict flow and multi-megabyte log/Unicode paging. Large clipboard copies use Download instead; authenticated member use remains for field review |
| Tasks | Native list/board, create, state/priority/assignee, edit/comments, pending writes and conflicts | Recovered native task screens and tested core/store/write contracts; detailed UI regression coverage is limited |
| Documents | Native rich reader; same bundled Tiptap editor/preservation code; leases, drafts, formatting, uploads, comments/resolve/reopen, recovery | Ten editor preservation tests; synthetic on-device no-op rich-envelope save is byte-for-byte preserved |
| Files | Context lists/folders, upload, hash-verified download, image/text preview, external viewer/share | Core storage/integrity tests; FileProvider scoped to download cache; platform chooser paths need field testing |
| Deck/search/pins | Native card pager, activity filters/read state, pins, canvases/recent/launchers; local FTS | Core projection/store tests and synthetic inbox; broad card/action UI regression coverage remains limited |
| Sync | Bounded/coalesced scheduler, reserved user work, foreground resumable events, opt-in WorkManager reads, durable outbox/writes | Core scheduler/store tests; synthetic send workflow; cold-process WorkManager transfer recovery is implemented but not physically power-loss tested |
| Profiles | Verified kind-0 relay profiles, names/avatars, separate credential-free cache and opt-out | Core profile policy/signature tests and avatar device tests |
| Canvas | Full-screen canvas with bottom exit/fit/edit bar (global bars hidden, inset- and keyboard-aware); native pan/zoom, live native naplets; create/rename; typed searchable add dialog including Create board; naplets: drag to move/resize, open, duplicate, reorder, exact frame, remove; drawings: create text, rectangles, ellipses, diamonds, arrows, lines and frames (tap or drag), edit text and text size, move/resize, drag arrow ends (bends kept), line style and arrowheads, duplicate, reorder, delete, and group selected items in a group frame (ungroup by removing it) | Core scene/edit/element/geometry and board-creation tests against a fake and the sample Tower; synthetic emulator flow draws, edits, duplicates, reshapes and groups. Freehand drawing, undo/redo and multi-item move are not offered on Android; broad canvas regression coverage remains limited |
| Task tree/boards | Native relationship graph and create child/successor; native board previews and bundled Excalidraw editing/versions | Core graph/board tests and editor-host/device rendering tests; full multi-device conflict exercise remains a field-test item |
| Context/agents/Toolbox | Native tree/references, agents/activity and HTTPS launchers | Core parsers/policies, synthetic records; WApp and personal-agent configuration stays in the web app |
| Browser files | Android document picker for file inputs; native save for HTTPS responses/download links and page-created blob/data; bounded files and document-scoped cancellation | See [browser file flows](browser-files.md) for support, security boundaries and remaining limits |
| Browser/NIP-07/HTML | Isolated browser/editor/artefact profiles; native consent; no-network HTML; renderer-exit recovery | Device bridge/sandbox tests and core consent/policy tests; unsupported WebViews fail closed |
| Management | Scope/channel create/rename/prompt/order/archive through the shared engine | Core API/management tests; server decides authority |
| Notifications | Shared Tower inbox (`org.wingman.notification.item` v1): bell with unread badge, list with Inbox/Unread/Archived and category filters, read/unread, dismiss, archive, mark-all-read bounded to what was seen, tap routing to thread/task/doc/channel with a generic card for unknown kinds, redacted "no longer available" items, per-app view (`client_app_id`, shared read state, `rules_revision` reset), per-category inbox settings written by per-key compare-and-set on the app-specific rule, honest empty/unsupported/access-lost states, offline reading, durable CAS writes | Core model/store/replay/CAS/recovery tests against a stubbed Tower and a contract-following sample stand-in; Robolectric screen tests. Not exercised against a live activated Tower in this build. Android alerts and phone push need a supported Android provider/registration contract; quiet hours and per-device settings are not offered. iPhone APNs support is separate |
| Access loss | Keep previously received content read-only, stop queued fetches/mutations; true tombstones delete | Core store and new retained-document/unsent-message integration tests; intentional difference from iPhone purge behavior |

## Unsupported on Android

Feed, Android OS push notifications (the in-app notification inbox is supported; see above), direct Autopilot pipeline/session views, FIPS transport, workrooms and reports. Web-owned
WApp and personal-agent configuration is not recreated. The app does not promise indefinite background execution.

iPhone now has APNs-specific permission, registration and cold-tap validation code. That is not an Android registration
contract and does not enable Android alerts. Android needs a supported Tower device-registration/delivery contract and
a configured Android push provider before permission/token handling or OS notification taps can be offered. This build
registers no token and claims no OS delivery. In-app inbox taps use the normal record routes and shared read-state CAS;
cached inbox reading remains available offline. They are distinct from unimplemented OS cold-start tap handling.

Web additionally offers thread rename/archive; those remain outside Android's current native menu. Branch creation
and inherited-agent reply metadata are covered by synthetic typed-route tests; live branch history remains for member review. iPhone document routes can start editing automatically and support selected-text
comments; Android retains its explicit Edit action and existing document comments. These are remaining differences,
not claims of complete web-feature parity.

## Validation limits

Emulator data and identities are synthetic and stored separately. No real identity was imported into an emulator and
no fixture was sent to a real channel. Broker-authorized host reads verify some live response contracts; the broker's
workspace-wide discovery route is outside this worker's authority. Authenticated live in-app member workflows, real
device vendor WebView differences, multi-device conflicts and OS process/power-loss transfer behavior require field
review. Those are verification limits, not evidence of tested live parity. See the release notes for the exact build.
