# Personal chat reminders

The chat header and selected-message menus offer **Remind me**. This saves an ordinary Ready task assigned to the signed-in human in their existing private Note to self DM channel. The pinned **Reminders** shortcut above the sidebar channels opens that channel's normal Tasks view in the current workspace. This action does not schedule a notification.

Discovery reads complete authorized scope/channel pages, matches a singleton participant array to the actual human verified by `/me`, and rejects missing, ambiguous, archived or unavailable destinations. It never provisions a channel or matches a display name. The selected source is read again through its normal authenticated message route. Tasks retain raw Markdown, links and an authenticated native reference back to that message. Thread actions use the root message and current thread title. Titles are bounded to 200 characters, with a first-message-line or “Saved conversation” fallback.

Before a create request is sent, its random request ID, destination and complete body are persisted in the existing workspace partition’s local state store under the connection/workspace/human/source boundary. A model-owned mutex serializes saves. Later taps and retries reuse those exact bytes and the existing task-create `client_request_id` replay contract. A failed or uncertain request never shows success. A returned task must match the workspace, destination and human assignment before **Open task** becomes available. Retrying never resets a previously saved task's workflow state. Separate devices can independently save the same source as separate tasks; global duplicate elimination is not promised.

Opening the source performs a fresh authorized message read and navigates to its conversation with the selected message as the scroll/highlight anchor, including channel messages outside threads. Native references do not grant access to a source after its permissions change.

Android implements this path. Other clients require their own parity work; release and authenticated device verification are recorded separately on the implementation task.
