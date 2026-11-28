# Personal reminders

The pinned **Reminders** shortcut opens a dedicated list immediately, without a signed request,
board discovery or channel creation in the navigation path. It shows saved tasks while existing
workspace discovery, directory and per-channel task sync refresh in the background. Cold loading,
missing verified human membership and unavailable updates have explicit states. Empty sections
refer to saved tasks, rather than claiming that an offline result is complete.

Only New, Ready and In progress tasks assigned to the current human account appear, across all
accessible channels in the current workspace. The human must match the authorized member directory;
an agent signer never inherits the workspace owner's reminders. Selection excludes removed scopes,
channels and tasks, archived/deleted records, other assignees, unassigned tasks and every other state.
Primary and additional assignment rows are recognized without duplicate tasks. Pending assignment
and status writes update selection immediately; failed/conflicting writes restore the saved value
with the existing visible retry/discard controls. The local query has no 500-task prefilter cap.

Qualifying tasks from the existing private singleton-participant Note to Self DM appear first,
then a horizontal divider and other tasks. Rows retain source channel context, task identity and
the existing detail/actions. Back from a task opened here returns to the list. Account lock and
workspace changes retain the normal runtime partition and navigation boundaries.

The task detail assignee control shows the selected avatar/name and opens searchable people and
agent choices from authorized workspace membership. It reuses the profile/avatar cache and initials
fallback. Search matches displayed names and keys; keyboard arrows/Enter choose a result, Escape
or Cancel discards the draft, and Save uses the existing revision-aware assignment queue. The
single-assignee editor preserves its existing atomic replacement/unassignment semantics; reading
a multiply assigned task does not rewrite its assignments.

The chat header and selected-message menus still offer **Remind me**. This saves an ordinary Ready
task assigned to the signed-in human in their existing private Note to Self DM. It does not schedule
a notification. Saving verifies the real human with `/me`, discovers complete authorized scope/channel
pages, and rejects missing, ambiguous, archived or unavailable destinations. It never provisions a
channel or matches a display name. The selected source is read again through its authenticated route.
Tasks retain raw Markdown, links and a native reference back to the source message.

Before a create request is sent, its random request ID, destination and body are persisted under the
connection/workspace/human/source boundary. A mutex serializes saves. Retries reuse those exact bytes
and the existing task-create replay contract. Failed or uncertain requests never show success. The
returned task must match the workspace, destination and human before **Open task** becomes available.
Retries never reset an existing task's state. Independent devices can save separate copies.

Opening the source performs a fresh authorized message read and navigates to its conversation with
the message highlighted. Native references grant no additional access. Other clients require their
own parity validation; source tests, device checks and release delivery are separate evidence.
