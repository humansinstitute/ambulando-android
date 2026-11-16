# Active workspace read recovery

Completing an explicit signed workspace QR proof for the already active workspace retries a closed set of failed reads: scopes (without refreshing descendants), pins, attention preferences, notifications, stored-channel task/document/file collections and workspace resource view states (inbox). It does not replay arbitrary failed jobs or retained work closures. Each key must be present in the active scoped scheduler's actual error snapshot. Collection readers enumerate that workspace's syncable stored channels and accept an optional exact failed-job-key filter before enqueueing; their third argument remains a scope ID. Ordinary refresh behavior is unchanged.

Recovery retains errors until the corresponding real read succeeds. Healthy keys, detail reads, unrelated writes, other workspaces and other connections are outside this retry set. The runtime must still be enabled, validated and open in the captured activity generation, with the exact account, connection, workspace, app namespace and QR context. Checks surround job execution and every signed GET request, including pagination and notification page-budget continuations. Recovery cannot resume a paused or revoked runtime. No permission or transport fallback is introduced.

Attention recovery preserves the pending mutation and desired preference while reading the current server value. Notification recovery retains strict error propagation through cold start, reseed and continuation, including normal ACCESS_LOST cleanup. Inbox's primary view-state request is mandatory; optional missing-record hydration retains its existing `readOrNull` behavior. The existing store readers/writers preserve local draft and pending-write state.

## Fixed diagnostic vocabulary

Only failures in the actual active `ScopedSyncScheduler` snapshot are observed. Categories are enums; arbitrary keys, suffixes, IDs, URLs, hashes, exception payloads and data never enter the observation sink. Unknown types remain `ACTIVE_FAILED_OTHER`. Existing category meanings remain unchanged. Diagnostic coverage does not authorize retries.

The inventory derives from these production factories and scheduling sites:

| Source | Finite types |
| --- | --- |
| `WorkspaceSync` factories; chat, work, live and surface readers | discover, scopes, channels, directory, messages, thread, scope-order, scope-collapsed, tasks, task, task-relationships, docs, doc, files, inbox, notifications, reactions, download, attachment, context, context-refs, canvases, canvas, board, agents, agent-activity, toolbox, pins, thread-record, events |
| `ProfileSync`, `AttentionPreferenceSync`, `NotificationSync`, `LauncherSync` | profiles, attention-types, notifications-archive, notification-item, notification-prefs, personal-agents |
| Runtime and durable senders | resume-access (exact key), writes, outbox |
| Core document runner, move, uploads, message flows, surface HTML | doc-save, doc-lease, doc-inspect, doc-latest, doc-versions, doc-move, paste, branch, rename-thread, export, naplet-html |
| `WorkspaceAdministrator`, app workspace management | admin, manage |
| App voice, ConnectedDeck, board editor, task tree, native canvas, canvas management | voice, pin-order, canvas-create, board-versions, board-read, board-publish, board-rename, task-tree, tree-create, tree-view, canvas-edit |

Each prefix maps to its corresponding `ACTIVE_FAILED_*` enum, with the established attention-types → ATTENTION and scope-collapsed → COLLAPSED names. Tests invoke every actual `WorkspaceSync` key factory, inspect core/app scheduling literals and forwarding factories, and exercise every finite prefix through a real scoped scheduler. Adversarial suffixes and lookalikes remain categorical. Observations share the existing Android `AmbulandoStages` / `Log.i` sink and 256-event process-local bound; optimized artifact verification must confirm all finite strings and the sink survive R8.

The signed-QR AppModel regression uses the normal cached foreground refresh with workspace reads denied by CONSENT, then completes fresh proof/access for the same active runtime while remaining in Chat. Runtime tests separately hold individual collection reads, retain denied primary reads and unrelated failures, skip healthy channel keys, preserve pending attention intent, and fence identity/context/pause/revocation changes.
