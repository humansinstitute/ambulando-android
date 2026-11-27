# Personal goal mentions

Android's chat, task comments and document comments offer personal goals from the selected workspace's existing Goals home. `@goal:` browses every returned goal in a scrollable list; `@goal:training` and unprefixed `@Training sign up` match titles case-insensitively. Multiword queries allow up to 160 graphemes. The typed prefix is a discovery control, not a new record type.

Selection inserts `@[Title](mention:file:<File UUID>)`. The UUID is the canonical File ID, never the goal content ID, version ID or storage object ID. Record references stay in message/comment bodies; only people and agents enter structured notification mentions. Rendering follows the existing File reader, whose GoalOrFile dispatch opens an owned personal goal with its native goal UI. A reference grants no access and must remain usable after a title change.

Discovery reuses GoalsSpace's owner metadata and grant checks, its designated Goals channel or existing owner-only self conversation, and GoalsController's boundary checks and verified File content. Other people's authorized goal files are not added to personal goal discovery. Bare and unprefixed searches exclude goal Files from the generic File fallback; an explicit `file:` search keeps its ordinary authorized-File behavior. Duplicate owned Goals channels are outside the resolver's current designated home. This behavior matches the Goals list; expanding discovery is separate work.

The UI cancels superseded lookups and clears rows immediately when query or boundary changes. Loading and failure are distinct from an empty result. Failure retains ordinary local mention matches where possible, with a personal-goal error. Failed/incomplete goal reads never masquerade as a complete goal list.

## Pagination limit

TowerClient.list follows supplied continuation cursors, rejects repeated/incomplete pages, and refuses a full page without a cursor. The inspected Tower channel Files route currently caps requests at 200 and always returns `next_cursor: null`. Consequently a full 200-File home cannot yet be declared complete: Android displays a failure. A generic authorized Files pagination capability is needed for larger homes. No new backend contract is assumed by this client change.

## Coverage audit

This source audit covers Android and the native Mac implementation; it does not establish live server availability or iPhone parity.

| Record or surface | Android picker / reference | Mac picker / reference |
| --- | --- | --- |
| People and agents | Supported; notification mentions | Supported; notification mentions |
| Documents and tasks | Supported by canonical IDs | Supported by canonical IDs |
| Threads | Supported as channel ID + root-message ID | Supported as channel ID + root-message ID |
| Channels and scopes | Supported | Supported |
| Personal goals | File reference with owner-checked discovery | File reference; Mac worker owns implementation validation |
| Generic Files, HTML, whiteboards, System Flows | File discovery and reader; no separate typed prefixes beyond file/artefact | Local MentionLookup has no generic File query; goal-specific discovery does not establish generic File parity |
| Individual messages / task or document comments | Thread reference exists; no individual comment/message search target | Thread reference exists; no individual comment/message search target |
| Folders | No picker kind or dedicated mention navigation | No picker kind or dedicated mention navigation |
| Context components and canvases / boards | No independent picker kind; a File-backed whiteboard remains a File | No independent picker kind; a File-backed whiteboard remains a File |
| Pins, notifications, toolbox app entries | Not independent mention targets | Not independent mention targets |
| Approvals, opportunities, standalone workflow records | No enum/picker/navigation contract established | `flow:` and `opportunity:` explicitly unavailable; no approval mention kind |
| Daily metrics, reports and other app data inside Files | File identity where available; no semantic subrecord discovery | No general semantic subrecord discovery established |

Supported wire references are `mention:person:<npub>`, `mention:agent:<npub>`, `mention:doc:<UUID>`, `mention:task:<UUID>`, `mention:file:<UUID>`, `mention:chat:<channel UUID>#<root-message UUID>`, `mention:channel:<UUID>` and `mention:scope:<UUID>`. Goal discovery produces the File form. The omitted independent record kinds have no verified dedicated mention contract in these clients; do not invent one from a table name.

Android source references: [picker](../core/src/main/kotlin/studio/otherstuff/ambulando/core/store/MentionStore.kt), [codec](../core/src/main/kotlin/studio/otherstuff/ambulando/core/chat/Mentions.kt), [goal discovery](../app/src/main/kotlin/studio/otherstuff/ambulando/app/GoalMentionLookup.kt), [navigation](../app/src/main/kotlin/studio/otherstuff/ambulando/app/AmbulandoApp.kt), [File reader](../app/src/main/kotlin/studio/otherstuff/ambulando/app/NativeFiles.kt), [goal dispatch](../app/src/main/kotlin/studio/otherstuff/ambulando/app/GoalsScreens.kt), [record inventory](../core/src/main/kotlin/studio/otherstuff/ambulando/core/store/ChangeCoalescer.kt).

Mac source references (sibling `native-ip` repository): `AmbulandoCore/Sources/AmbulandoCore/Chat/MentionLookup.swift`, `Chat/Mentions.swift` and `Goals/GoalMentions.swift`. Mac source was inspected read-only while its worker was active; final Mac evidence belongs to that worker.

Web and iPhone goal-picker parity remain follow-up work. Do not infer those experiences from a shared codec or the ability to render a File reference.
