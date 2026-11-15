# Unread and Mentions item types

Settings → Unread and Mentions chooses Chats, Documents, Tasks, Files and Other notifications for the signed-in
person in the selected workspace. The preference follows that person across Android, web, iPhone and Mac on the
same Tower and application. Each workspace has its own choice. All types stay visible until chosen.

The preference filters Unread and Mentions notification lists, their unread badge, sidebar unread activity and unread
rows in the mixed Deck Inbox. Read Deck activity, all-notification views, explicit type navigation and direct links remain
available. Filtering never writes a read, dismiss or archive state. Bulk mark-read operates only on the rows actually
shown, including the temporary Deck search and type filters.

## Shared contract

Use the existing actor-authenticated Tower route:

`GET` / `PUT /api/v4/flightdeck-pg/workspaces/:workspaceId/me/preferences/ambulando.attention-types`

The preference `value` is a string containing compact JSON, for example:

```json
{"version":1,"disabled":["task","file","other"]}
```

Canonical names are `chat`, `document`, `task`, `file`, `other`. Notification targets `thread`, `channel`, `chat` and
`message` and `dm` map to `chat`; `doc`, `document` and their `_comment` aliases map to `document`; `task`, `file` and their `_comment` aliases map directly; remaining targets map
to `other`. Deck and resource attention use their record kind. Store disabled types so newly introduced types stay
visible by default. Preserve unknown object fields and disabled names when editing. A missing value means all types;
malformed or future-schema values also show all types, with editing disabled to avoid overwriting a newer format.

PUT uses Tower's existing `expected_row_version` and immutable `mutation_id`. Do not resend an uncertain mutation
with a different body or ID. A conflict refreshes the saved preference and asks the person to choose again, instead of
silently replacing another device's choice. An authoritative GET with `preference: null` resets the acknowledged value.

## Android implementation

The existing sync engine owns the signed requests and stores the row, immutable pending request and subsequent desired
choice in the workspace partition. Settings and views observe the store. Multiple offline switch changes update the
persisted desired choice; once the immutable request is acknowledged, the newest desired choice gets its own CAS request.
If another device overtook that acknowledgement, subsequent desired changes are discarded with conflict feedback.
The first save requires one successful read. Cached preferences remain usable offline. Pending writes wait for unlock;
background credentials only read. Refresh/reconnect and the `private_preference` event hint use the existing sync owner.

Tests use synthetic accounts and the sample Tower. A passing local or sample test does not establish that deployed web
or installed Apple apps have been updated; release review must verify the intended app versions and real cross-device sync.
