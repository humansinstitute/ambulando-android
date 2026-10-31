# Native feature status

The reference is the current native iPhone client, including its later local document-editor changes, rather than the
full web application. This matrix distinguishes implementation from verification. It is a review checklist, not a claim
that an emulator is an authenticated live member session.

| Area | Android implementation | Evidence / qualification |
| --- | --- | --- |
| Identity/PIN/Keystore | Import, trusted-session resume, unlock/lock, unavailable-vault retention, explicit removal | Core and dedicated-device identity tests |
| HTTPS/Tower | Descriptor, exact-body NIP-98, workspace app binding, redirects/FIPS refused | Core transport tests; broker-read live scopes/channels/task parsed by Kotlin |
| Shared navigation | Workspace/scope/channel, DM labels, sidebar/order/collapse, independent section stacks, view lock. The open section and each section's record stack (including open conversations) are saved per connection and workspace and restored after Android ends the process; the sidebar's scopes and channels are re-read every minute while the app is in front, even when the event stream looks healthy | Synthetic app workflow retains channel across navigation and Activity recreation; `RouteStateTest` (save/restore every stack, other-workspace routes never restored, damaged state restores nothing); the 60-second reconcile is source-reviewed, not separately timed on a device |
| Chat | One Messages list with per-thread reply summaries opening threads; opens at newest with near-bottom follow and per-conversation reading position; drafts, durable signed outbox, retry/discard, reactions (the web's seven, sent by Tower wire name; earlier builds sent the emoji character, which Tower's schema does not accept), signed edit that keeps structured mentions and offers the `@` lookahead, delete; a bare `@` lists the people and agents you tagged most recently first | Synthetic send/recreation; core interrupted-send/idempotency and access-removal tests; Robolectric first-layout visibility, follow and restore tests |
| Thread/message menus | Header beside Pin/Close: Rename thread (online `PATCH /threads/:t` with the stored `row_version`; a stale version is refreshed once and retried only if the title is still the one being edited, otherwise the other change is reported), Copy message, canonical root-based FD Ref, Get it done, latest-session Autopilot Link and author-only confirmed Delete. Per-message menus share the actions and offer Branch from here through the existing typed Tower route, retaining the inherited agent for unaddressed replies. Ready-task output/assignee/instructions/excerpt/source metadata use the durable write queue. Sending returns to newest even from older history | Root lookup is independent of the 300 visible messages; missing/deleted sources never substitute replies. HTTPS session links use workspace metadata or the sending agent's own launcher. Android threads occupy a full section, so no modal zoom control is needed. Core MessageFlowsTests, app follow/restore tests and synthetic ThreadMenuFlowTest; no authenticated member validation |
| Mentions | Inline `@` lookahead over the local store: people/agents, documents, tasks, chat threads (the web's canonical `chat:<channelId>#<rootMessageId>` token), channels, scopes, with type prefixes; web tokens, composer pills, structured `mentions` for people/agents only; trigram FTS5 title index (schema 6) | Core lookahead/index tests including a 25,000-record timing bound; flows and opportunities are not stored on Android and are not offered |
| Attachments | SAF and clipboard URI uploads, resumable placeholders inserted at the caret (editable, positionable `![name](storage://…)`), stripped image metadata, cached inline images with a full-screen zoomable overlay, standalone attachments only when not inline, share from the overlay | Photo privacy/device tests; upload coordinator implementation; end-to-end platform variations need field testing |
| Text attachments | Automatic exact UTF-8 paste files, retained composer selection, numbered names/Undo, bounded draft/sent editors, original/version history, offline cache and durable edit recovery | Core boundary, byte preservation, commit failure, context isolation, retry/rename and conflict tests; synthetic emulator paste/recreation/send/edit/history/conflict flow and multi-megabyte log/Unicode paging. Large clipboard copies use Download instead; authenticated member use remains for field review |
| Tasks | Native list/board, create, state/priority/assignee, edit/comments, pending writes and conflicts. Task detail shows the parent (labelled unavailable rather than guessed), direct subtasks with status (archived included, dependency successors excluded), descendants that expand in place with cycle protection, a count whose `+` marks an unconfirmed list, relationship completeness (complete, partial, restricted, offline) and Add subtask through the durable create queue with the parent's channel fixed. Comment fields offer the `@` lookahead and send structured actor mentions | `TaskHierarchyTest`, `CommentMentionWriteTest`, `CommentAuthorTest`; synthetic emulator flow adds a subtask and opens it. Each parent's confirmed child membership, completeness, restriction and refresh state are stored (`task_relationships`, schema 7): a complete paged read replaces membership without deleting or re-parenting task records, authorized upserts, write acknowledgements and tombstones update it in the same transaction independent of clock skew, completeness-only changes are observed, and unloaded/cached/partial/offline/restricted lists carry an uncertain `+` count, including a once-complete list whose refresh failed, stopped at its page budget or was interrupted by relaunch (its last confirmed children stay, removed members do not return). `TaskRelationshipStoreTests`, `TaskHierarchyStatesTest`, `TaskHierarchyFlowTest` (synthetic emulator) |
| Documents | Native rich reader; same bundled Tiptap editor/preservation code; leases, formatting, uploads. Default opening mode (View, or Edit from Settings, once per visit). Comments show author photo/name/time, replies, quoted selections and resolve/reopen; comment on selected text. **Preserved drafts and reconciliation** (see [document-reconciliation.md](document-reconciliation.md)): durable per-workspace drafts that survive offline, relaunch, lock, an expired lease and lost access; a persistent Newer version available notice and Review changes (line diff, copy, Accept latest with confirmation and preservation, Merge my changes over a verified retained common base with per-overlap Use local/Use latest, edited preview, Reconcile preview with latest, Edit merged result as a local draft that only Save publishes); claimed-draft saves with latest-head preflight, lost-answer acknowledgement and upload receipts. **Authority**: per-session leases, `document_concurrent_edit_v1` only when advertised (never downgraded), explicit confirmed takeover of the inspected lease (across people; Tower authorizes) after preserving local work as a recovery, saved-version chooser. **Wiki** (see [wiki-links.md](wiki-links.md)): stable links open by id, title-only links resolve/choose/create, `[[` panel and Link picker (this channel first, every scope on request), create-and-link as one durable write that links the source before it is retired, Linked from, Move | Core: merge/journal/saver/controller/wiki/write-hook tests incl. Unicode and rich envelopes, concurrent and disjoint and conflicting edits, stale versions, retry/lost answer, offline/relaunch, ambiguous and deleted links, workspace separation; Tower stand-in tests of the production save adapter; Robolectric screen tests; bun tests of the page bridge; synthetic-emulator flows (`DocumentReconciliationFlowTest`). A connected test types into the real editor page with key events. Not exercised against a live Tower or a second real editor; Tower must serve the capability markers for concurrent editing |
| Files | Context lists/folders, upload, hash-verified download, image/text preview, external viewer/share | Core storage/integrity tests; FileProvider scoped to download cache; platform chooser paths need field testing |
| Deck/search/pins | Native card pager, activity filters/read state, pins, canvases/recent/launchers; local FTS. Search groups results as Tasks, Documents, Files and Messages, debounces typing, says where each result lives (scope · #channel) and opens file results. Deck's New thread opens the channel's Chat composer (Android has one composer, in Chat) | Core projection/store tests and synthetic inbox; synthetic emulator search flow; broad card/action UI regression coverage remains limited |
| Sync | Bounded/coalesced scheduler, reserved user work, foreground resumable events, opt-in WorkManager reads, durable outbox/writes | Core scheduler/store tests; synthetic send workflow; cold-process WorkManager transfer recovery is implemented but not physically power-loss tested |
| Profiles | Verified kind-0 relay profiles, names/avatars, separate credential-free cache and opt-out | Core profile policy/signature tests and avatar device tests |
| Canvas | Full-screen canvas with bottom exit/fit/edit bar (global bars hidden, inset- and keyboard-aware); native pan/zoom, live native naplets; create/rename; typed searchable add dialog including Create board; naplets: drag to move/resize, open, duplicate, reorder, exact frame, remove; drawings: create text, rectangles, ellipses, diamonds, arrows, lines and frames (tap or drag), edit text and text size, move/resize, drag arrow ends (bends kept), line style and arrowheads, duplicate, reorder, delete, and group selected items in a group frame (ungroup by removing it) | Core scene/edit/element/geometry and board-creation tests against a fake and the sample Tower; synthetic emulator flow draws, edits, duplicates, reshapes and groups. Freehand drawing, undo/redo and multi-item move are not offered on Android; broad canvas regression coverage remains limited |
| Task tree/boards | Native relationship graph and create child/successor; native board previews and bundled Excalidraw editing/versions | Core graph/board tests and editor-host/device rendering tests; full multi-device conflict exercise remains a field-test item |
| Context/agents/Toolbox | Native tree/references, agents/activity and HTTPS launchers | Core parsers/policies, synthetic records; WApp and personal-agent configuration stays in the web app |
| Browser files | Android document picker for file inputs; native save for HTTPS responses/download links and page-created blob/data; bounded files and document-scoped cancellation | See [browser file flows](browser-files.md) for support, security boundaries and remaining limits |
| Browser/NIP-07/HTML | Isolated browser/editor/artefact profiles; native consent; no-network HTML; renderer-exit recovery | Device bridge/sandbox tests and core consent/policy tests; unsupported WebViews fail closed |
| Management | Manage Workspace ([workspace-administration.md](workspace-administration.md)): workspace profile/avatar/delete, scope and channel create/rename/prompt/order/archive, channel creation with explicit atomic grants, scope setup with the product template, members, groups with nesting and atomic enrolment, channel grants, scope roles under the edit lease and revision, app delegations, installation reconcile/revoke, managed-app activation, publishing grants and key rotation. Online and confirmed only. Each write re-reads `/me`; non-administrators are allowed only by the exact resource authority just read (a grant naming `channel.grants.manage`, the scope's `can_manage`, a delegation naming that installation). Unknown, restricted, unsupported and offline states are named; cached lists are never authority | Core `AdministrationTests`, `ScopeSetupTests`, `AvatarUploadTests`, Robolectric `AdministrationUiTest`, emulator `AdministrationFlowTest`; the synthetic Tower is not Tower. Group and grant response shapes follow the iPhone/web contract and were not exercised against a live Tower |
| Notifications | Shared Tower inbox (`org.wingman.notification.item` v1): bell with unread badge, list with Inbox/Unread/Archived and category filters, read/unread, dismiss (with a six-second Undo), archive, mark-all-read bounded to what was seen, tap routing to thread/task/doc/channel with a generic card for unknown kinds, redacted "no longer available" items, per-app view (`client_app_id`, shared read state, `rules_revision` reset), per-category inbox settings written by per-key compare-and-set on the app-specific rule, honest empty/unsupported/access-lost states, offline reading, durable CAS writes | Core model/store/replay/CAS/recovery tests against a stubbed Tower and a contract-following sample stand-in; Robolectric screen tests. Not exercised against a live activated Tower in this build. Android alerts and phone push need a supported Android provider/registration contract; quiet hours and per-device settings are not offered. iPhone APNs support is separate |
| Access loss | Keep previously received content read-only, stop queued fetches/mutations; true tombstones delete | Core store and new retained-document/unsent-message integration tests; intentional difference from iPhone purge behavior |

## Unsupported on Android

Feed, Android OS push notifications (the in-app notification inbox is supported; see above), direct Autopilot pipeline/session views, FIPS transport, workrooms and reports. Not offered on any native client (no supported contract): group rename/delete, removing or promoting a workspace member, managed-app uninstall/runtime teardown. Web-owned
WApp and personal-agent configuration is not recreated. The app does not promise indefinite background execution.

iPhone now has APNs-specific permission, registration and cold-tap validation code. That is not an Android registration
contract and does not enable Android alerts. Android needs a supported Tower device-registration/delivery contract and
a configured Android push provider before permission/token handling or OS notification taps can be offered. This build
registers no token and claims no OS delivery. In-app inbox taps use the normal record routes and shared read-state CAS;
cached inbox reading remains available offline. They are distinct from unimplemented OS cold-start tap handling.

Thread archive has no confirmed Tower route (iPhone does not offer it either). Branch creation and inherited-agent reply metadata are covered by synthetic typed-route tests; live branch history remains for member review. These are remaining differences, not claims of complete web-feature parity.

## Parity audit against the current iPhone/Mac apps (iPhone build 61 / Mac build 52)

Reference: the Swift apps and the web client at the dates in the release notes. Each workflow was compared by source
inspection on both sides; "Evidence" names the tests or files, and the validation section says what kind of run each is.

| Area | Status on Android | Evidence / reason |
| --- | --- | --- |
| Navigation and context | Parity for workspace/scope/channel, DM labels, section stacks, restore after process end, now **per enabled workspace** (see the multi-workspace row) | `RouteStateTest`, shell tests, `MultiWorkspaceRuntimeTests`, `MultiWorkspaceFlowTest` |
| Multiple workspaces and Towers | Parity with the iPhone/Mac runtime design ([multi-workspace-storage.md](multi-workspace-storage.md)): several workspaces on one or more Towers enabled at once, each with its own partition and runtime behind one bounded fair scheduler; chooser grouped by Tower with enable/pause, colour, order, collapse and Add Tower with the same unlocked identity; shared Unread & Active rows keep their source workspace; "Working in" composer indicator and faint header tint; per-workspace navigation, drafts, outbox and cursors persisted; empty selection is explicit; pause/resume with revalidation before any send; boundary guard on every runtime request; disconnect retires one Tower's partitions; background windows rotate between enabled workspaces. Intentional differences: access loss keeps received content read-only (write gate closed, pending work kept) instead of purging; presigned transfers started before a pause may finish in the system (bytes only); the optional locked read credential covers only its pinned Tower; no combined Home dashboard (also absent on iPhone/Mac) | Core: `WorkspaceStorePoolTests` (unique import, identical ids on two Towers, ambiguity withheld, idempotent and interrupted imports, retire/reconnect), `ScopedSchedulerTests` (namespaces, pause isolation, stale answers after pause, rotation, per-Tower occupancy), `MultiWorkspaceRuntimeTests` (two synthetic Towers with identical workspace ids: separate stores and writes, pause/offline/queued sends, revalidation gate, access loss and regain, disconnect, boundary guard), `WorkspaceCatalogTests`; emulator `MultiWorkspaceFlowTest` (synthetic Towers only). Not exercised: a live Tower, a physical phone, background windows granted by Android, real accounts |
| Account / NStack persistence | Parity for descriptor, identity vault, trusted session, removal, reconnect | Core identity/store tests; unchanged this build |
| Chat, threads, Markdown, media, mentions | Parity, including the full reaction set, edit with mentions, rename, branch, Get it done, tables and inline media. Not offered: thread archive (no confirmed route) | `ReactionEmojiTest`, `MentionTest`, `MessageFlowsTests`, synthetic emulator flow |
| Tasks | Parity for list/board/detail/comments and subtask hierarchy. Per-parent membership table persisted as on iPhone | `TaskHierarchyTest` |
| Documents | Parity with iPhone/Mac build 64 / Mac 55 for preserved drafts, three-way review/merge, concurrent-protocol negotiation, explicit takeover, saved versions, the `[[` picker and create-and-link. Intentional differences: the minute checkpoint runs only while the editor is in front (Android may end a backgrounded process; local drafts are written on every edit and on stop); Save keeps the editor open, as on iPhone; the editor toolbar scrolls horizontally | Core, Robolectric and synthetic-emulator tests (see above). The take-over, concurrent and recovery paths follow `document_edit_session_v1` / `document_concurrent_edit_v1` and need live Tower validation; they were exercised against an in-process stand-in only |
| Files, versions, text attachments | Parity for folders (read), upload, download, preview, share, text attachments with history. No folder create/move or file rename/delete (iPhone has none either) | Core storage/text attachment tests |
| Inbox and notifications | Parity for the shared inbox, filters, CAS actions and Undo. No OS push (no Tower registration contract for Android) | `NotificationsScreenTest`, core notification tests |
| Canvas, naplets, boards | Parity for create/rename, live naplets, drawing edit, boards with versions. Android adds drawing edits iPhone does not have; no freehand, undo/redo or multi-item move | Core canvas tests, synthetic emulator canvas flow |
| Permissions | Parity for the administration workflows the iPhone offers (members, groups and nesting, channel and scope access, delegations, installations, activation, publishing, workspace profile/avatar/delete, scope setup, channel creation with grants). Authority is re-read before every write and decided on the exact resource; every write is still decided by Tower. Not mirrored: a delegation is created for one app and one destination (other filter combinations are shown, not authored); the group directory is administrator-only so a non-administrator's group-derived channel authority is unprovable and treated as absent | `AdministrationTests`, `AdministrationUiTest`, `AdministrationFlowTest`; see the administration validation record below |
| Search | Parity: local index, grouped results with location, opens every kind | Synthetic emulator search flow |
| Pins and order | Parity, including versioned relative ordering and hold-to-drag | Existing pin-order tests |
| Offline and outbox | Parity: durable signed outbox and write queue, retry/discard, idempotent resend, resume after unlock | Core outbox/write tests |
| Background sync and resume | Parity for foreground stream, catch-up, WorkManager reads, network-return refresh; periodic structure reconcile added. No OS push wake | Core scheduler tests |
| Error and retry states | Parity for send/write/save failures, access loss (Android keeps read-only copies by design), relationship and group refusals explained | Core tests, screen tests |

## Validation limits

Emulator data and identities are synthetic and stored separately. No real identity was imported into an emulator and
no fixture was sent to a real channel. Broker-authorized host reads verify some live response contracts; the broker's
workspace-wide discovery route is outside this worker's authority. Authenticated live in-app member workflows, real
device vendor WebView differences, multi-device conflicts and OS process/power-loss transfer behavior require field
review. Those are verification limits, not evidence of tested live parity. See the release notes for the exact build.

## Build 13 (0.3.0) validation record

- Source: core JVM suite 533 tests (0 failures, 1 optional live-contract skip), app JVM suite 29, editor suite 11 tests,
  `lintDebug` clean.
- Dedicated Android emulator (API 36, synthetic sample data, no real identity or Tower): the connected suite ran 34 tests;
  33 passed and one (the live HTTPS download check) was skipped because it needs network reachability. The new
  `ParityFlowTest` covers wire-name reactions, edit, thread rename, subtask creation and parent navigation, grouped search,
  default Edit mode (once per visit), document move, and saved-screen restore after a new start. This run also caught and
  fixed two defects before release (a crash saving screen state inside Compose's read-only snapshot, and a comment field
  that expanded to fill its row).
- Update path: the signed 0.2.5 (12) APK was installed on the emulator and the signed 0.3.0 (13) APK installed over it with
  `adb install -r`; the install time stayed the same, the version became 13 and the app started with an empty crash log. No
  authenticated account or physical phone was used, so upgrade retention of a real account's data is not shown by this run.
- Not exercised against a live Tower: group and channel-grant response shapes, thread rename, document move and subtask
  relationship reads were exercised against the in-process sample Tower and fakes only.


## Build 14 (0.4.0) document package validation record

- Source: core JVM 634 tests (0 failures, 1 optional live-contract skip), app JVM 37, editor bun suite 19, `lint` 0 errors.
  Core tests cover merge, journal, saver, controller, wiki and write-hook behaviour with a Tower fake for the exclusive and
  concurrent protocols, lost answers, offline and relaunch; the production save adapter against the sample Tower;
  Robolectric screens. The explicit-Save-only hold of a merged or restored draft is stored in the draft row, with tests for a
  deleted, stale or unreadable review journal, further typing, a failed Save and the return to ordinary autosave.
- Dedicated Android emulator (API 36, synthetic sample data, no real identity or Tower): the full connected suite ran 39
  tests; 38 passed and the live HTTPS download check was skipped (needs network). `DocumentReconciliationFlowTest` covers
  review, merge, Edit merged result and Save, Accept latest with confirmation and preservation, confirmed takeover after
  preserving local work as a recovery, the Link picker with create-and-link, and typing into the real editor page with key
  events, reopening, recreating the activity and saving. Several earlier full runs on a loaded host showed one-off timeouts
  in unrelated UI tests that passed alone and in a later full run; one `ParityFlowTest` failure was test pollution (the
  sample document stays where an earlier run moved it) and is fixed.
- Not exercised: a live Tower, a second real editor, the concurrent protocol on a server that advertises it, a physical
  phone, typing with an IME composition, upgrade retention of a real account's drafts. Several workspaces, administration,
  and push remain outside this package. Task-hierarchy persistence is now in source (not yet in a numbered release); it was not exercised against a live Tower or on a physical phone.

## Build 15 (0.5.0) task hierarchy validation record

- Source: core JVM 654+ tests, app JVM 45, editor bun suite 19, `lint` 0 errors. Independent review of per-parent membership
  found and fixed: a replayed or late relationship page could reopen a finished read in the same generation (pages now name
  the cursor they were fetched from); closing the store freed the native SQLite handle under a running statement (JVM crash,
  now waits behind the in-flight statement); the HELD-relaunch document tests saved before the background lease pass finished.
  Added a schema 6 to 7 upgrade test.
- Dedicated Android emulator (API 36, synthetic data): every connected test class passed run one class at a time (the live
  HTTPS download check is skipped without network). Running all classes in a single instrumentation process on this loaded host
  crashed inside Compose's runtime in unrelated screens, so the classes were run separately.
- Not exercised: a live Tower, a physical phone, real accounts. Several workspaces, administration and push remain open.

## Build 16 (0.5.1) uncertain-count validation record

- Fix: a once-complete subtask list no longer keeps an exact count while its refresh is partial, failed or interrupted
  (core 660 and app 48 JVM tests, zero failures; `lint` 0 errors).
- An earlier stale-cache/skewed-child connected failure was diagnosed as test timing, not a product race: the sample
  workspace's own channel task-list sync is authoritative, so it correctly flagged tasks the test injected into the channel
  (Tower never lists them) when it landed after the injection, and a relationship read begun after an injected child replaced the
  membership. About 2 in 5 reruns failed before, 0 in 15 after the tests waited for task syncs and the screen's own refresh to settle.
- Dedicated emulator, every connected class run separately: all passed except one intermittent `ParityFlowTest` chat-edit
  dialog that did not open in about 1 of 6 reruns (unrelated to tasks; open) and the live HTTPS download check, skipped
  without network. Running all classes in one instrumentation process still crashes in Compose on this loaded host.
- Not exercised: a live Tower, a physical phone, real accounts.


## Multi-workspace runtime validation record (build 17, 0.6.0)

- Source: core 680 JVM tests (0 failures, 1 optional live-contract skip), app 48 JVM, `lint` 0 errors. New core coverage:
  `WorkspaceStorePoolTests` (5: unique legacy import with drafts, outbox bytes and navigation moved once; identical workspace
  ids on two Towers with ambiguous cache withheld; application namespace and origin normalisation; rolled-back and retried
  import with a corrupt backup failing closed; disconnect retiring one Tower and a reconnect starting fresh) and
  `BoardDraftRestoreTests`; `ScopedSchedulerTests` (6: identical keys never coalesce across workspaces, pause isolation and
  stale-answer refusal, rotation, per-Tower occupancy, per-workspace idle); `MultiWorkspaceRuntimeTests` (6, two synthetic
  Towers serving the same workspace ids: separate stores and writes, pause/offline/resume sends once, no send before
  revalidation (mutation-checked), access loss and regain, disconnect/reconnect, boundary guard); `WorkspaceCatalogTests` (2).
- Dedicated Android emulator (API 36, synthetic data, no real identity, Tower or physical phone), every connected class run
  one at a time on the final source: `ConnectedAppTest` 2, `ConnectionSettingsTest` 1, `ParityFlowTest` 6, `NotificationInboxFlowTest` 1,
  `ThreadMenuFlowTest` 1, `TaskHierarchyFlowTest` 3, `DocumentReconciliationFlowTest` 5, `TextAttachmentFlowTest` 1,
  `BatchInteractionTest` 2, `OvernightFeedbackFlowTest` 3, `MultiWorkspaceFlowTest` 4 (stacked sidebar with shared unread and
  workspace switching without stopping the other, send isolation with offline queue/pause/re-enable sent once, a second Tower with
  identical workspace ids added through the chooser, access loss labelled with writes closed), plus browser, web, avatar and
  identity classes (the live HTTPS download check is skipped without network). A separate force-stop run
  (`MultiWorkspaceProcessDeathTest` phase 1, `am force-stop`, phase 2 in a fresh process) confirmed both workspaces still enabled,
  the one in front, each workspace's own section and each workspace's draft restored.
- Upgrade in place on the emulator (debug signer, synthetic data): the previous build (`f1a8b88`, single database) was left with a saved
  draft and an unsent message, this build was installed over it (`install -r`), and `UpgradeRetentionTest` confirmed the remembered
  workspace enabled and in front, the draft present in its partition, and the unsent message sent exactly once after revalidation.
- Found by those runs and fixed: the route state of a workspace was saved after the switch had reset the section (a switch lost
  the section it left); the sidebar's channels could briefly show the previous workspace's under identical ids; an explicit
  open of a workspace now goes Home as the old switcher did.
- Release-gate runtime race validation (core 696 JVM tests, 1 optional skip; app 48): `RuntimeRaceTests` holds a request in flight
  and runs the competing lifecycle step before releasing it (pause during the access listing, access lost during validation,
  regained access needing a fresh listing, reads/stream/direct writes/queued jobs refused after loss, 40-round random pause
  stress, a closed runtime refusing a late start). It found two real defects, both fixed with a failing-first test: a runtime
  closed by Disconnect could be reopened by a late enable, and `SyncEngine` dropped a job re-issued after `cancelAll` when its
  cancelled predecessor unwound late (a fast disable/enable could leave a workspace unvalidated, writes stayed closed).
  Mutation checks: the closed flag, the write-gate re-check and `closeAccess` clearing are each caught; the runtime's own
  lease re-checks are not isolated (the transport and scheduler guards overlap and stop a stale result first), so they are
  defence in depth without a separate proof.
- Known unresolved, not release-blocking: `DocumentEditControllerTest` lease-timing waits flake intermittently (about 3 of 8
  runs on the pre-release base as well); `ParityFlowTest` chat reaction/edit failures on reused installs (cold-process timing is
  a hypothesis, not diagnosed; a 3/6 versus 4/6 small sample proves neither absence of regression nor of leakage); hugeUnicode
  `TextSheetTest` remains an open separate task.
- Not exercised: a live Tower, a physical phone, real accounts, Android-granted background windows (the rotation is covered by
  source review and the shared refresher path, not timed), `TextSheetTest` hugeUnicode (separate task), push and administration.
  `ParityFlowTest` showed the previously known intermittent chat-edit failure shape only when repeated runs reused one install
  (consistent with accumulated sample state; not separately diagnosed); runs on clean installs passed three times in a row.

## Workspace administration and grants validation record (build 18, 0.7.0)

Scope: [workspace-administration.md](workspace-administration.md). Compared with the iPhone/Mac contract by reading its core, views and
documents; Android adds no backend route and changes no shared schema.

- Source: core 746 JVM tests (0 failures, 1 optional live-contract skip), app 65 JVM tests (0 failures), `lint` 0 errors. New: `AdministrationTests`
  (40, including channel-create grants and the activation guards), `ScopeSetupTests` (6), `AvatarUploadTests` (4) and Robolectric `AdministrationUiTest` (17, the real
  screens against the synthetic administration Tower with a real store, scheduler and signer). Coverage: exact request bodies and paths,
  validation before send, fresh `/me` before every write, an ordinary member refused with nothing sent, a direct channel grant manager limited to
  the channel they manage (`channel.manage` and scope roles never count), nested-group and cyclic-group rules, scope roles under the edit lease
  with a stale revision, a changed revision and a held lease refused without a write, delegation expiry/revocation/wrong installation/wildcard,
  activation recovery without a second installation, publishing add/rotate/disable/revoke, owner step-up as a refusal, failed writes with
  entries kept, saved-but-not-read-back shown as saved, authority revoked between the screen and the save, an unreadable `/me` turning controls
  off, a workspace switch while a form is in flight (including while the exact-resource read is outstanding) sending nothing and reporting
  nothing into another screen, a draft restored only over the values it was started from, and a finished create starting fresh.
- Mutation checks: 25 deliberate regressions (channel.manage counting as grant management, the non-administrator exact-resource check
  removed, the last look before send removed, the in-front check ignored, scope revision not compared, delegation expiry/revocation ignored,
  nested groups ignored, a saved group list authorising, a wildcard delegation covering activation, a stale draft restored, a finished create
  keeping its draft, a stale screen still current, an unreadable authority keeping the cached one, an activation claim ignoring the intent's
  origin or boundary, the activation guards ignoring path or host, the runtime guard letting any `/api/wapps/` path through, a foreign catalog
  entry being offered, and others) were each caught by a test.
- Independent review (a separate reader, code only) found real defects, all fixed with tests: a new publishing installation could reuse the
  previous installation's id and overwrite it; an old draft could undo another person's change; a saved-but-not-read-back create was shown
  as a failure and could be sent twice; a saved group list could count toward group-derived authority; a finished scope setup or activation
  could not be started again; a read-back could still send after a switch; an expiry could be built as `…ZZ`; an activation delegation with
  no app filter covered any app. Lint also found `InputStream.readNBytes` (API 33) in the avatar picker, now replaced.
- Dedicated Android emulator (API 36, synthetic data, no real identity, Tower or physical phone), each connected class run one at a time on the
  final source: `AdministrationFlowTest` 6 (member, group and channel grant through the screens; read-only member; a form in flight when the
  workspace in front changes sends nothing and keeps its entries; a delegate limited to its own installation; scope setup with the product
  template; activation signing only to the confirmed origin through the runtime's guarded transport), `MultiWorkspaceFlowTest` 4, `ConnectedAppTest` 2, `ParityFlowTest` 6, `ConnectionSettingsTest` 1, `NotificationInboxFlowTest` 1,
  `ThreadMenuFlowTest` 1, `BatchInteractionTest` 2, `OvernightFeedbackFlowTest` 3, `TaskHierarchyFlowTest` 3, `TextAttachmentFlowTest` 1,
  `DocumentReconciliationFlowTest` 5. In an earlier pass one `MultiWorkspaceFlowTest` test and one `OvernightFeedbackFlowTest` chat test timed out
  while the host was also building; both passed on two reruns and in the final pass (chat-timing signature of the known intermittents; not
  diagnosed further). In the final pass one `TaskHierarchyFlowTest` test (a task-list sync wait) timed out and passed on two reruns, the same
  task-list timing family as the known reused-install failures (not diagnosed). `BatchInteractionTest` failed once only because the test's own screenshot names had used up Android's 32 unique-name
  limit in shared storage; the captures were archived off the device and cleared. Browser, web, avatar-store, identity, upgrade-retention and
  process-death classes were not re-run for this change.
- Sidebar: the stacked sidebar had no entry for workspace management at all; each workspace's menu now has **Manage workspace**, which opens
  management for that exact workspace once it is in front.
- Not offered (no supported contract, as on the iPhone): group rename/delete, removing or promoting a workspace member, managed-app uninstall.
- Known limits of this work: no authenticated live Tower, physical phone or real account; the synthetic Tower follows the documented contract
  and proves the client, not Tower. A non-administrator cannot read the group directory, so group-derived channel authority they cannot show is
  treated as absent; a delegation is authored for one app and one destination (other filter combinations are displayed, not created); a grant
  row that lists no permissions and has level `manage` is accepted as grant management as on the iPhone (Tower still decides). Long member/group lists are drawn in full
  (search narrows them). Android push remains unsupported. The earlier known items are unchanged: `ParityFlowTest` chat reaction/edit failures on
  reused installs (cold-timing hypothesis only), `DocumentEditControllerTest` lease-timing intermittents, `TextSheetTest` hugeUnicode (separate
  task), the combined connected run crashing in Compose on this host so classes run one at a time.

## Independent review and release validation (build 18, 0.7.0)

A second reader, who had not written the code, reviewed the administration and activation code against the iPhone/Mac sources
(`WorkspaceAdministration.swift`, `GroupDirectory.swift`, its workspace-management and channel-access documents and views) and ran the
checks below on the final source.

- **Reported limits compared with the iPhone.** A delegation is authored for exactly one app, one scope and one channel on the iPhone too
  (`AdministrationCommand.delegation`), so that narrowness is shared, not an Android omission. Group-derived grant management: the iPhone's
  write path reads the administrator-only group list from its saved cache, and its screen feeds the scope's group rows into the same decision.
  Neither can show a non-administrator's group membership unless Tower returns it, so it is effectively absent there as well. Android now
  makes the screen and the write the same decision from one fresh directory read (the scope's groups fill the recipient picker only), and
  documents it. No iPhone-supported workflow was found missing; group rename/delete, member removal/promotion and app uninstall have no
  contract on the iPhone either.
- **Defects found and fixed** (with tests; each was a real behaviour, not style): a finished new publishing installation kept its generated
  id in the open editor, so an edit and a second save would have overwritten it (a failing-first test, and a mutation check that restores the
  old behaviour is caught); the channel access screen could offer a change for a group-derived grant that the write then refused; deleting
  a workspace accepted an empty typed name when the label was blank; the runtime's boundary guard let the two exact activation paths through
  over http, with credentials or with Tower's application header (the flow guard already refused them); `workspace.manage` did not offer the
  scope and channel controls the iPhone offers; member pickers did not search a full npub.
- **Activation reviewed.** The only way to obtain a signing target is `ConfirmedAutopilotOrigin.of`, from text the person typed; every
  activation call takes one. The flow guard checks method, scheme, host, port, exact path, no query, fragment, credentials or application
  header, and an exact body for the claim; the runtime guard refuses every other `/api/wapps/` path and any query; the transport never
  follows a redirect; a catalog entry naming another Autopilot is counted and not offered; the saved intent carries its origin and the full
  boundary (origin, signer, application, workspace) and is refused for any other, or from a closed screen; a retry processes that exact
  intent and repeats the same client request id. Claiming a saved intent reads no `/me` (it carries no authority of its own; the intent was
  created under administrator or delegation authority, and Autopilot and Tower decide it), as on the iPhone.
- **New coverage.** `AvatarPickerUiTest` (3, Robolectric with a stand-in system picker and a real PNG that carries a metadata chunk: consent
  before anything is prepared, cancel sends nothing, the stored object has no metadata chunk, the 8 MB limit, backing out). It does not
  exercise an unreadable (non-image) file, which Robolectric's graphics accept; the on-device `ImagePrivacyTest` covers that decoder.
  `AdministrationUiTest` grew to 20 (group-derived authority, typed-name deletion with exact body, publishing restart). The emulator
  `AdministrationFlowTest` grew to 7: a new channel is shared only with the person ticked, at the level chosen, with nothing pre-ticked.
- **Final source.** Core 746 JVM tests (1 optional live-contract skip), app 71 (0 failures), `lint` 0 errors, editor bun 19. Dedicated emulator
  (API 36, synthetic data), each class one at a time with fresh app data: `AdministrationFlowTest` 7, `BatchInteractionTest` 2, `ConnectedAppTest` 2,
  `ConnectionSettingsTest` 1, `DocumentReconciliationFlowTest` 5, `MultiWorkspaceFlowTest` 4, `NotificationInboxFlowTest` 1,
  `OvernightFeedbackFlowTest` 3, `TaskHierarchyFlowTest` 3, `TextAttachmentFlowTest` 1, `ThreadMenuFlowTest` 1, `AvatarStoreTest`, `BrowserFilesTest`,
  `BrowserNostrTest`, `EditorWebHostTest`, `ExcalidrawRenderTest`, `IdentityOnDeviceTest`, `ImagePrivacyTest`, `KeystoreBoxTest`, `RichContentTest`,
  `SandboxedHtmlTest` and `TextSheetTest` all passed on the single run made; `ParityFlowTest` failed one test (below).
- **The intermittent emulator timeouts, diagnosed to a bounded question: regression or not.** The same classes were run repeatedly with fresh app
  data on an idle host, on this source and on the source before the administration work (`25afc49`, built in a temporary worktree):
  `TaskHierarchyFlowTest` 1 failure in 5 on each (the same test, the same `waitUntil` for a live upsert to draw); `MultiWorkspaceFlowTest` 1
  failure in 17 on each (the same test, a message composed in the second Tower's workspace that never reached its store, with an empty outbox and
  a sendable workspace); `OvernightFeedbackFlowTest` 1 failure in 13 on this source and 1 in 8 on the earlier one (the same chat-newest-message test). The earlier reports of timeouts
  while the host was also building are therefore the same intermittents, present before this work and at similar rates; they are not caused
  by it. Their cause is not identified (the Multi failure with an empty outbox looks like a lost tap on the send control rather than a product
  race, which is a hypothesis only), they remain open, and these small samples neither prove the absence of a small difference nor justify a
  claim that every test passes. `ParityFlowTest` `reactionsGoByWireNameAnd…` failed in 4 of 6 runs on this source with fresh data (the same
  family and rate as the earlier 3/6 and 4/6 on the previous source); it, the `DocumentEditControllerTest` lease-timing waits and the
  `TextSheetTest` hugeUnicode test (which passed here) stay open.
- Not exercised: a live Tower, a physical phone, real accounts; delivery of the delegations, activations and publishing grants to a real Autopilot.

## Intermittent failure diagnosis (task c812ecc1, after build 18; source only, not released)

Method: each failure was reproduced first (class repeats on the dedicated emulator with fresh data per run; JVM repeats under CPU load),
the actual state was captured (semantics tree, store contents, composition enter/leave events, stack traces of the duplicate callback), and
the change was kept only where the captured cause was removed and the rate fell. Small samples; none of this proves live behaviour.

- **Chat edit dialog (`ParityFlowTest`): product defect, fixed.** The edit/delete dialog state belonged to the message row. A row that
  left the list (follow scroll, keyboard resizing the list) took the open dialog and the typed edit with it ("actions leave … edit=true"
  half a second after "dialog compose"). The dialogs now belong to the chat screen. Test `anOpenEditDialogSurvivesItsMessageScrollingOutOfTheList`
  fails without the change (3 of 3 runs).
- **Chat reaction chip (`ParityFlowTest`): product defect, fixed.** The follow-newest effect reacted only to `canScrollForward` flips; keyboard
  resize steps could overwrite its scroll request and leave the list short of the newest message (first item 38 of 41, follow on, no flip to
  ask again), so the sent message was not composed. It also observes the viewport height now. No test fails without this part (the Robolectric
  attempts did not reproduce the lost request); it is supported by the captured log only.
- **`ParityFlowTest` after the fixes: not clean.** Class runs on the final source: 4 of 20 failed (3 reactions test, 1 the new dialog test),
  against about half before. Not diagnosed: a tap that did not open the dialog although the button was on screen; an attempt to record the
  button's bounds before the tap changed the timing and the failure did not occur. Next step: log raw touch events and the button's laid-out
  bounds from the app (no test-side reads) in a failing run.
- **`DocumentEditControllerTest` lease timing: product defect, fixed.** `save()` and `takeover()` returned false when a person asked during
  the background version check (`reconciling`). Reproduced under load (6 of 40 and 3 of 40 repeats), 0 of 150 after; a new test holds the
  check in flight (fails without the wait). The wait is bounded (15 s) and no new background check starts until the action ends.
- **`TextSheetTest` hugeUnicode duplicate `INSERTED`: product defect, fixed.** After the editor split an over-long page it handed the field a
  shorter value; the input method then re-reported the field's longer buffer (`finishComposingText`) and the same edit was applied twice
  (stack traces captured). Fixed with `ChunkPageEdit`; unit test fails without the echo check; 2 of 20 under load before, 0 of 30 after.
  Separately the review-screenshot helper accumulated `name (n).png` until MediaStore refused a new copy (about 32), failing the text tests
  on a reused emulator (`pm clear` also removes ownership, so only a shell can delete the old rows); it now replaces its image.
- **`MultiWorkspaceFlowTest` / `TaskHierarchyFlowTest` / `OvernightFeedbackFlowTest`: not diagnosed.** Ten fresh-data runs each on the final
  source: Multi 0 failures, Hierarchy 1 (a "Subtasks 1" count after a live upsert did not settle in 20 s), Overnight 2 (a canvas swipe
  "Failed to inject touch input", and `aCanvasIsFullScreenWithItsControlsAtTheBottom`). Different places each time, no common cause found;
  small samples show neither a regression nor its absence. Next step: capture the count's source state at the Hierarchy timeout and the
  window/focus state at the injection failure.
- JVM/lint on this source: core 747 tests (1 optional skip), app 74, lint 0 errors.

## Independent review of the four fixes and release validation (build 19, 0.7.1)

The four stabilisation commits were read line by line against the risks below before release.

- **Chat dialogs:** state sits in the chat screen keyed by the conversation target (connection, workspace, channel, thread), so a switch
  drops it and one conversation's edit can never be shown in another. The edit keeps the message as it was when Edit was tapped. Added in
  review: the dialogs close if access to the conversation is removed (they used to stay open).
- **Follow scroll:** the added viewport-height trigger still requires follow on, no drag or fling in progress and room to scroll; it changes
  nothing when the reader has scrolled up, and a request does not change the height, so it cannot loop. No test fails without this part
  (see above); this is review and the captured log only.
- **Save/Takeover wait:** cancellation and close end the wait and release the counter; the guards run after the wait, so a lease taken in
  the meantime is still refused (`HELD`) and takeover re-reads the lease it acts on. Found in review and fixed: a person's action could
  still be dropped in the instant between the background check's guard and its flag (the check now sets the flag and then looks for a
  waiting action), and when the 15 s bound passed the action returned false in silence (it now says "Still checking for a newer version…"
  and changes nothing). Tests: decline is visible and nothing is written; closing ends a waiting Save; a later Save works.
- **Text editor echo:** only a value equal to the text the page was just split from is treated as the echo, and the next different edit clears
  it. Tests: a real second identical insert is applied, caret moves and a composition are left alone, a caret after a split stays on a
  whole character, undo/page-split content is preserved. Known edge: typing exactly the characters that were just cut off a split page, as
  the next input, is treated as the echo.
- Not resolved and not cleared by this release: the Parity tap that did not open the dialog (4 of 20), Hierarchy count settling (1 of 10),
  Overnight touch injection/full-screen (2 of 10), Multi (0 of 10). Small samples; the bounded next diagnosis stays as written above.
- JVM on the release source: core 749 tests (1 optional skip), app 77, zero failures.

