# Personal daily briefing v1

Ambulando owns this format. NStack supplies existing channel ACLs, private storage and immutable File versions;
no backend enum, table, catalogue or endpoint is added. Android and Swift consume the same bytes.

## Canonical record

One non-DM channel carries metadata:

```json
{"system_key":"ambulando.briefings","briefing":{"schema_version":1,"owner_npub":"<human npub>","author_npub":"<agent npub>"}}
```

Verify grants against current workspace members before reading or publishing. Exactly the owner identity and the
named author identity may hold direct actor grants; groups and additional principals are refused. The supported response alias `principal_type:person` is accepted
only with `stored_principal_type:actor`, retaining the exact principal actor ID. Validate the explicit `permissions` array, not the derived `access_level` label (a valid bundle may be labelled `custom`).
Both identities require `channel.read`, `file.read` and `file.write`; the owner additionally requires `channel.manage`
and `channel.grants.manage`. Missing/empty/incomplete permissions fail closed even with a `manage` label.
Require one unambiguous member per npub, distinct actor IDs, exactly one direct grant per identity and consistent
principal IDs/npubs. Reject partial/paginated ACL envelopes before reading Files. Metadata is discovery, never authority. Member kind labels are not authority; verify exact owner/author npubs
and membership. The helper binds owner to verified workspace owner and author to current broker bot. Reject duplicate matching
channels or Files, incomplete discovery and unreadable grants. Never discover/read self-DM or Notes to Self content.
Do not change Goals grants. Administrators/storage operators retain the backend's existing authority.

One File in that channel uses MIME `application/vnd.ambulando.briefing+json`, metadata
`{"briefing":{"schema_version":1,"owner_npub":"<human npub>","author_npub":"<agent npub>"}}` and content:

```json
{
  "schema": "ambulando.briefing",
  "schema_version": 1,
  "workspace_id": "11111111-1111-4111-8111-111111111111",
  "owner_npub": "<human npub>",
  "author_npub": "<agent npub>",
  "timezone": "Australia/Perth",
  "entries": {
    "2026-11-27": {
      "generated_at": "2026-11-26T21:30:00Z",
      "paragraphs": ["One short paragraph.", "An optional second paragraph."],
      "links": [{"type":"task","record_id":"22222222-2222-4222-8222-222222222222","label":"Underlying task"}],
      "coverage_note": "Optional explanation of unavailable or incomplete sources."
    }
  }
}
```

`links` is required (may be empty); type is `task`, `thread`, `doc` or `file`. Optional `channel_id` is a UUID;
thread links require it. The publisher requires known `channel_id` on every source link so it can exclude Notes to Self before reading
the target. Historical readers allow it to be omitted except on threads. Labels are plain text, 1–160 characters. Max 20 links. Paragraphs are plain text, 1–2,
each nonblank and at most 4000 characters; no HTML execution. Coverage note is optional, at most 1000 characters.
Dates are canonical ISO calendar dates. `generated_at` is an RFC3339 instant with explicit offset and its Perth
calendar date must equal the entry key. Future-dated publication is refused. Body max 4 MiB, 3660 dated entries;
reaching the bound is an explicit maintenance issue, never silent history trimming. Unknown fields are retained
by writers. Invalid/unsupported bytes are never partially shown or rewritten.

## Publication and retry

The helper `scripts/briefing.mjs` uses Autopilot's capability broker signer, never raw keys. `discover` is read-only;
`setup --scope <uuid>` explicitly provisions a dedicated channel and empty File once. It rechecks discovery before
creating and verifies grants and exact bytes afterwards. Generic channel/File creation is not an atomic singleton:
after an uncertain setup outcome reconcile discovery; do not automatically rerun creation. Only the manager provisions.
A scheduler uses the preprovisioned canonical File and never runs setup.

`publish --entry <ignored-json-path> --date YYYY-MM-DD` inserts only today's Perth date. If that day exists with
identical entry JSON, return success without another version; different content is a conflict. Upload private bytes,
POST `/files/:id/versions` with `base_version_id`, reread on a stale-base response and merge only an absent date.
Preserve all previous entries and unknown fields. An uncertain POST is reconciled by reading current content before
any repeat; a different existing day is never overwritten. Confirm the acknowledged immutable version bytes against
the submitted SHA-256 and report File/version IDs. Current File readback must still contain the exact entry.

Read the current File's `current_version_id` and `object.storage_object` size/hash, then its exact version object;
this avoids dependence on bounded history listings after 200 mornings. Check workspace, channel, MIME, metadata,
body owner/author and exact-byte digest. ACL is checked again around publication; generic ACLs are authoritative.

## Home and responsibility

Use the fixed Perth date, independent of device timezone. Show selected saved day, author, generated time and source
links. The stepper stops at today. Before 05:30 an absent today entry is pending; after 05:30 it is unavailable.
Previous entries remain explicitly dated, never relabelled today. Failed refresh is unavailable, never an empty
success. Historical paragraphs describe that day's saved analysis; current goal facts are not substituted.

Rick writes a short chief-of-staff overview from authorized current chats/tasks/goals only: changes, saved-goal
progress, attention, gaps/stalled work and suggested focus. Mention coverage limitations. Do not read Notes to Self,
change tasks/goals, send hourly/evening messages or replace the separate 06:00 infographic. Rick owns scheduler and
publication review: `30 5 * * *`, timezone `Australia/Perth`, exact authorized workspace/channel/thread. A configured
schedule or passing unit test is not a real publication; first authenticated publication/readback is required.

Web and iPhone parity are separately tracked; this document does not imply their implementation or installation.

Foreground reads retain only an in-memory canonical location. Subsequent record events compare that File/channel’s
local metadata before a targeted read; unrelated uploads do not rescan scopes. Foreground resume and explicit refresh
rediscover safely. With no saved location, event-driven discovery is limited to once a minute. Existing central sync
continues to own SSE, cursor recovery and local record hydration. Android does not persist briefing prose offline;
failed reads show unavailable and do not present remembered text as current.

A missing today entry does not remove history. Home starts at today's Perth date and offers the latest previous
saved entry, with its original date, after a successful read. Previous/next day controls remain available.
A failed access/hash/network check hides all prose and shows an error; it must not fall back to unverified cached
history. The writer and each client need the same explicit-permission validation; repairing the scheduled helper
alone cannot repair an installed reader that rejects `custom` grants.
