# Public agent updates in conversations

An open conversation shows the current agent's status and latest public update. Tap a truncated header update to read
its full text. Updates attached to the triggering message offer ordered history and an explicit load-earlier action.
Completed, failed and cancelled runs remain readable locally, including after the conversation or app is reopened.
These are producer-published public updates, not private model reasoning. The composer’s coloured workspace marker
is labelled “Workspace:” and indicates the reply destination, independently of agent status.

Activity must match the workspace, channel, owning thread and triggering message. An inherited message does not import
its parent conversation's active agent status into a fork. The conversation header selects the latest non-queued turn
per agent plus live queued turns; a queued request cannot displace the turn ahead of it, and an older working snapshot
cannot replace a newer completed turn. Earlier unconfirmed runs are labelled as earlier activity. Agents and Deck
use the same current-turn and lease rules, so retained history cannot keep an old working badge alive. A visible Deck
Inbox reprojects cached lease deadlines once a second while working, and on reopen, without needing a network or
database event.

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
