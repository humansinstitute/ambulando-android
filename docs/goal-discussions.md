# Goal discussions and outputs

Goals remain ordinary channel-owned, versioned Files (`application/vnd.ambulando.goal+json`,
`ambulando.goal` schema version 1). Additive `default_channel_id` and `pinned_refs` fields hold a discussion
destination and key original Doc/File references. Empty optional fields are omitted to preserve legacy canonical
bytes. Unknown fields survive edits; pins and general `links` are independent. File versions provide conflict
detection and independent edits rebase through `GoalEdit`.

`Discuss goal` reads the current Goal and validates the selected channel's current scope and channel access.
An unset default opens a picker. An unavailable saved default stays visible and requires an explicit replacement;
it never selects another channel automatically. Choosing a one-off discussion destination does not save a default.
The server finally checks posting permission.

The existing native thread creation route saves its primary association atomically in application metadata:

```json
{"ambulando_goal":{"version":1,"goal_file_id":"<goal-file-uuid>"}}
```

The containing record supplies the authoritative workspace. No goal text or instructions are inserted into the
visible chat body. The generic thread create route has no idempotency contract. A device-local pending receipt is
saved before creation; an uncertain response switches the action to `Find discussion`, which looks for the original
request UUID in accessible recent threads instead of creating another. An unresolved receipt remains on this device.

The thread artefact list offers creation in that discussion's channel. New Docs/Files carry the authoritative
thread's goal reference plus `source_thread_id`, merged with template/native metadata through the durable write
queue. The goal reader combines explicit links with synchronized thread/Doc/File metadata, up to 500 recent outputs
per type. This is a view over existing records, not a second catalogue or copied output. An automatic association is
distinct from a removable manual link and from a pin. Other creation entry points do not infer a goal from global
navigation or a mention.

References grant no access. Opening uses the original native route. Deleted and inaccessible targets expose no
source title/content through resolution. Reading a primary Goal with HTTP 403 preserves only its reference on new
outputs; readable wrong-kind/unsupported Goals are refused. Explicit Goal File mentions supply additional agent
context and never select a different primary goal. Installed skills describe authoritative reads and generated
output association; Autopilot source and running dispatch/helper activation are verified separately.

Validation lives in `GoalDiscussionModelTests`, `GoalAssociationTests`, `GoalAssociationStoreTests`,
`GoalDiscussionAPITests`, and `GoalDiscussionsDeviceTest`. Synthetic transport/device tests establish client behavior;
they do not establish a live workspace's ACLs, agent receipt, or a public upgrade.
