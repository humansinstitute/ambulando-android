# Thread artefact membership

Ambulando stores a thread’s shared attachment identities in the existing generic thread metadata. Editors, drafts, historical revisions, selected tabs and viewports remain local to each device. A thread reference never grants source access.

## Application format

```json
{"ambulando_thread_artefacts":{"version":1,"items":[{"kind":"doc","id":"00000000-0000-4000-8000-000000000001"},{"kind":"file","id":"00000000-0000-4000-8000-000000000002"}]}}
```

IDs are canonical native Doc or File UUIDs. A File may be a supported whiteboard or HTML editor source. There are at most 32 distinct members. The codec rejects unsupported versions and fields instead of overwriting them. The metadata key is owned by Ambulando, not an NStack server type or catalogue.

An absent key is different from initialized `items:[]`. Only the former permits one-time migration of verified legacy open references. A client losing that initialization race adopts the winner. Initialized empty membership and the local migration-observed marker prevent stale local tabs from resurrecting removed attachments.

## Generic storage and concurrency

Read the canonical thread through the existing thread GET. PATCH the same thread with `metadata` and its actual numeric `row_version`; metadata is a complete replacement, so preserve every unrelated root field. Existing channel-write and participant permissions apply. No custom membership endpoint is required.

For each explicit add/remove, fetch the latest row, apply only that record’s intent and send CAS. A stale-version response permits bounded refetch/reconciliation. This preserves unrelated additions and metadata. A dropped response or malformed acknowledgement requires canonical readback. Confirm only an observed matching intent; otherwise adopt the observed list, show uncertainty and require an explicit retry. Never automatically replay an ambiguous action over a later opposite action.

Refresh from the server on entry/resume, and consume the existing thread updates through the local store. Cached membership may be displayed with an unconfirmed label; it is not a successful live refresh. Source labels are resolved through authorized metadata reads without downloading bodies or mounting editors. Opening or adding verifies source access again.

## Local editing and dispatch

Opening an attached record focuses an existing local view when available. A fresh client opens the source’s current version; shared membership never changes another device’s historical selection. Up to 32 views may be open; closed lightweight references and recovery are retained beyond that limit.

Close view affects this device only. **Remove from thread** removes the shared association, never the source or its assets. Dirty detached editors retain durable recovery and require an explicit source Save before publication, including after reattachment or opening the same Doc through global Docs. Backgrounding and disposal checkpoint recovery without publishing it. Failed checkpoints keep the view and expose an actionable error.

Detached sources are excluded from future Send targets and context. Preparing a new dispatch refreshes membership and validates it again after saving open attached editors. Already frozen messages and retries retain their captured exact source versions. Unopened or inaccessible attachments do not block plain chat. Unsupported File mention formats retain their native reader route without becoming editor context.

The bottom Chat/Artefacts/+ switch is absent at every attachment count. The accessible top artefact icon beside Pin opens the attachment list/browser, including at zero. The artefact header also offers the list icon and Back to chat; embedded canvas gestures stay owned by the editor.

## Verification

Core tests cover independent stores, CAS races, ambiguous writes, access failures, migration and the shared cross-client JSON fixture. App tests cover local history, detached recovery, prepare-before-Send, native File routing and closed-reference/open-view limits. Synthetic Android instrumentation covers Doc/board/HTML discovery and removal, independent-store hydration, retained dirty recovery, explicit Save and historical board context. No real-account mutation is required by these tests.
