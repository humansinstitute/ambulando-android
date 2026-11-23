# Public agent updates in conversations

An open conversation shows compact agent activity after the newest replies, using native caption text and a small
chevron disclosure. Live work shows its status and at most two lines of the latest public update. Expand the row to
read ordered public history and load earlier entries. Finished turns default to “Earlier activity” with no preview or
body; completion resets an open disclosure. Expiry also collapses the body and says “Stopped responding”, never
“Completed”. Disclosure survives row recycling while the conversation is open; revisiting starts collapsed.
These are producer-published public updates, not private model reasoning. Ordinary final replies remain messages.
The composer’s coloured “Workspace:” marker indicates the reply destination, independently of agent status.

Activity must match the workspace, channel, owning thread and triggering message. An inherited message does not import
its parent conversation's active agent status into a fork. The tail projects at most three current lifecycles and only
the newest finished turn with retained content. Older rows stay stored rather than being rendered between historical
messages. Current selection takes the latest non-queued turn per agent plus live queued turns; queued requests cannot
displace the turn ahead of them, and older working snapshots cannot replace newer completed turns. Agents and Deck
use the same current-turn and lease rules, so retained history cannot keep an old working badge alive. A visible Deck
Inbox reprojects cached lease deadlines once a second while working, and on reopen, without a network or database event.

Activity updates follow the newest message only while the reader is at the bottom. Expanding history pauses automatic
following; reading earlier messages preserves the viewport. The activity strip uses the conversation list rather than
a duplicate fixed header. Composer drafts, keyboard resizing and navigation keep the existing conversation behavior.

Liveness follows the server lease deadline, calibrated using the authenticated response's server time. Missing leases
and explicit expirations never prove that an agent is working. Deadline expiry has the same 15-second grace and
10-minute stopped-status window as the web client. Accepted requests say “Message received”; waiting requests say
“Waiting for you”. A connection or recovery error is displayed separately and does not turn cached history into a live
status. Refreshes use the existing keyed scheduler and its retry backoff.

The implementation uses the existing `GET /agent-activities` contract, with `channel_id`, optional `thread_id` and
`activity_id`, activity-page `cursor`, history `before_sequence`, and forward `after_commentary_cursor`. It requests up
to 50 public commentary entries per page. Reconnect recovery follows delivery cursors so late, lower-sequence deliveries
and multi-page gaps can be recovered. It persists progress after each recovery page; a failed later page resumes from
that saved cursor. A normal newest-page snapshot cannot advance recovery past an unread gap.

SQLite keeps the existing activity table and canonical raw record. Merging retains older history, deduplicates by turn
and sequence, keeps terminal state over replayed working events, and orders leases by epoch and lease sequence.
Expiration is sticky within its lease epoch; a later epoch can recover the same turn. Rolling pages and network failures
do not delete offline history. Existing access removal retains readable records without granting further server access;
server tombstones continue through the normal deletion path.

Regression coverage lives in `AgentProgressTests`, `AgentProgressSyncTests`, `SurfacesAPITests`,
`AgentProgressUiTest` and `AgentProgressConversationTest`. The latter uses the labelled synthetic app mode and tests the
conversation, history, completion, reopen and draft-preservation flow. Production member/device verification is separate
from these synthetic tests.
