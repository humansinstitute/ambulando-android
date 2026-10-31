# 0.7.0 — Android build 18

Manage Workspace. A new **Manage workspace** entry in the sidebar (and in each workspace's own menu when several are enabled)
opens native screens for the workspace's name, address, description and a public avatar image (shown with an explicit consent that
it becomes publicly readable, metadata removed); deleting a workspace asks for its typed name. Members can be added and renamed;
groups can be created, nested and detached; a person outside the workspace can be enrolled into a group in one atomic step that never
changes an existing role. Channel access can be granted, changed and revoked, a new channel can be created and shared only with the
people and groups you tick, and scope roles are managed under the scope's edit lease and revision (a changed revision is reported,
never overwritten). App delegations, installation reconcile/revoke, managed-app activation and publishing installations (add, edit,
disable, revoke, rotate the public key) are included, as is a staged new-scope setup with the product template.

Every change is a confirmed online request and nothing is queued. Before each write the app re-reads your access: an administrator, or
exactly the grant you hold on that channel, scope or installation, with revoked and expired delegations refused; a saved list is never
permission. Managed-app activation signs only to the HTTPS Autopilot origin you type and confirm, only for its catalog read and its one
claim; redirects and any other path are refused, and the saved intent is tied to that origin and workspace so a retry never creates a
second installation. Entries are kept after a refusal or a lost connection, and an old draft cannot undo someone else's change.

Found in independent review before release: after adding a publishing installation the open form kept its generated id, so editing and
saving again would have overwritten it (it now starts over with a new id); the channel access screen and the write now make the same
decision about group-derived authority; deleting a workspace needs a non-blank name; the runtime's own guard on the two activation
paths also refuses http, credentials, fragments and Tower's app header; people are searchable by full public key; a holder of
`workspace.manage` is offered the scope and channel controls, as on the iPhone.

Not in this build: group rename/delete, removing or promoting a member and app uninstall (no supported contract, as on the iPhone), a
delegation with more than one app or destination (the iPhone is the same), group-derived authority for a non-administrator who cannot
read the group directory, and Android push. Tested against Tower stand-ins and a synthetic emulator account, not a live Tower, a
physical phone or real accounts. A few emulator chat and task-list tests are intermittent on this host, and behave the same on the
previous source; see docs/workspace-administration.md and docs/parity-matrix.md.

# 0.6.0 — Android build 17

Several workspaces, on one Tower or more, can now be enabled together. Each has its own storage partition, drafts, outbox,
cursors and sync, so identical workspace ids on two Towers (or identities) can never mix; a shared, bounded scheduler rotates
between them. A chooser grouped by Tower enables or pauses workspaces, sets a colour, and adds another Tower with the same
unlocked identity. The sidebar stacks the enabled workspaces under shared Unread & Active rows that keep their source
workspace; the composer says which workspace it is working in; the header takes a faint tint. Each workspace's section,
open records, context, drafts and queued sends are remembered separately across switching, locking and process death.
Pausing keeps everything and stops only that workspace; resuming revalidates access before anything queued is sent; losing
access closes writes and keeps what was already received, readable and labelled. Disconnecting a Tower moves its partitions to a
private recovery folder the app does not read. The first run after the upgrade keeps the remembered workspace (plus any with
unsent work) enabled. Before release, runtime lifecycle races were exercised with held requests: a workspace closed by
Disconnect can no longer be restarted by a late enable, and a workspace paused and resumed quickly is no longer left
unvalidated by a cancelled predecessor. Not in this build: workspace/group/grant administration and Android push.
Tested against Tower stand-ins and a synthetic emulator account, not a live Tower, a physical phone or real accounts;
see docs/multi-workspace-storage.md and docs/parity-matrix.md.

# 0.5.1 — Android build 16

Tasks. A subtask list that was once complete no longer keeps showing an exact count while its refresh is unfinished. If a
refresh fails, stops at its page budget or is cut off by closing the app, the last confirmed children stay visible, the
count carries a `+`, and the note says whether the list is refreshing, stopped part-way or could not be refreshed. A
child that a completed read removed does not come back, and a finished refresh restores the exact count.

Not in this build: several workspaces at once, workspace/group/grant administration and Android push. Tested against a Tower
stand-in and a synthetic emulator account, not a live Tower or a physical phone. See docs/parity-matrix.md.

# 0.5.0 — Android build 15

Tasks. A task's subtask lists are now kept on the device per parent, so what you saw stays visible offline and after the
app is closed. A list is replaced only when a full read of Tower's list finishes: a read that fails or stops part-way keeps
the last confirmed list and resumes where it stopped. New subtasks you create, tasks that move to another parent and tasks
that are deleted update the lists immediately. The count is exact only after a complete read; a trailing `+` and a plain-language
note say when the list is not loaded yet, saved from an earlier read, partly read, read offline, or limited because Tower hides
tasks you cannot see (hidden tasks are never counted). Each list has Refresh and re-reads when opened, expanded and about once
a minute while the screen stays open.

Fixes. Closing the local store while a save was still running could crash the app's database layer; it now waits.

Not in this build: several workspaces at once, workspace/group/grant administration and Android push. This package has been
tested against a Tower stand-in and a synthetic emulator account, not a live Tower or a physical phone. See
docs/parity-matrix.md.

# 0.4.0 — Android build 14

Documents. Android now keeps your unsaved page work when someone else changes the same page, and lets you review, merge or
accept their version without losing yours. Nothing is replaced or published without your choice. A newer shared version
opens Review changes with a line diff, Accept latest (asks first and keeps your copy under Preserved drafts), and Merge my
changes over a verified common saved base, with Use local / Use latest for each overlap and an editable preview. Edit merged
result makes a local draft that only an explicit Save publishes, even after the app is closed or its notes are lost.
Drafts survive offline, relaunch, locking and lost access, and a save whose answer was lost is recognised on retry without a
second version. Take over editing… (confirmed, after your changes are kept as a Tower recovery) works across people;
Versions chooses a stored previous version against the head you reviewed. Type [[ or use Link to search pages (this
channel first, every scope on request) and Create and link. Save keeps the editor open, and changed pages save by
themselves each minute, as on iPhone.

Not in this build: several workspaces at once, workspace/group/grant administration, durable task hierarchy tables and
Android push. This package has been tested against a Tower stand-in and a synthetic emulator account, not a live Tower with
a second real editor. See docs/parity-matrix.md and docs/document-reconciliation.md.

# 0.3.0 — Android build 13

Brings Android up to the current iPhone and Mac behaviour for chat, tasks, documents, search and management.

Fixes. Reactions are now sent by Tower's names (thumbs up, heart, …) and the picker offers the web's seven; earlier builds
sent the emoji character, which Tower does not accept. Editing a sent message keeps its @mentions (it used to drop them)
and offers the same @ lookahead as the composer.

Chat. Rename a conversation from its menu (a rename made elsewhere is reported, never overwritten). A bare @ lists the
people and agents you tagged most recently first.

Tasks. A task shows its parent, its subtasks with status, descendants that expand in place, whether the list is complete,
and Add subtask. Task and document comments offer @ and send structured mentions.

Documents. Settings can open documents in Edit by default. Comments show author photos, replies and the quoted selection.
Comment on selected text from the editor. Links between documents open the page; Linked from lists the saved pages that
link to a document. Move a document to another channel after reading what changes.

Search groups results by kind, shows where each lives, and opens files. Notifications offer Undo after Dismiss.
Management controls your role cannot use are hidden, and People and access shows members, groups and a channel's grants
(read only). The section and records you had open return after Android ends the app, and the sidebar refreshes every
minute while the app is in front.

Not in this build: several workspaces at once, workspace/group/grant administration, [[ create-and-link, taking over
another editor's document lease, three-way draft merge and Android push. See docs/parity-matrix.md.

# 0.2.5 — Android build 12

Pinned cards return to a compact resource icon, single-line title and independent Unpin button. Hold the main card
content to drag before or after another pin, including the end of the list. Insertion feedback appears only during a
drag. Move actions remain available through accessibility and Alt+Up/Down/Home/End on the focused card, without
permanent arrangement controls. Existing durable ordering, version checks and synchronization are unchanged.

# 0.2.4 — Android build 11

Tap blank composer padding to focus the editor without replacing a saved draft or intercepting attachment/send controls.
Personal mixed pins now have drag handles, keyboard buttons and accessibility move actions. Moves use Tower's existing
versioned relative order endpoint; another device's conflicting change remains visible and a move never re-pins an item.
Applicable record toolbars follow Search, Notifications, Pin, Browser, Avatar.

The isolated browser now offers Android document selection for file inputs and a native save picker for HTTPS downloads,
HTML download links and page-created blob/data files. Files are limited to 16 MB. Navigation and tab lifecycle changes
cancel pending selection. HTTPS downloads use only the browser profile's destination cookies, never Tower signing headers.
Camera/microphone/location, local-file navigation and FIPS remain unavailable. See the browser file-flow documentation
for tested behavior and remaining limitations.

# 0.2.3 — Android build 10

Threads now have a header menu beside Pin and Close. Copy the source message or its canonical FD Ref, turn the
conversation into a ready task with Get it done, open the latest Autopilot session in the HTTPS browser, or delete your
own source message after confirmation. Missing or deleted source messages never make the menu act on a later reply.
Message menus also offer Copy, FD Ref, Get it done, the applicable Autopilot link and Branch from here.
Branches keep the sending agent as the recipient when replies name nobody. Threads with no replies have an explicit
Open row, so selectable text never hides the way into a conversation.

Get it done includes output choice, assignee, extra instructions, a bounded latest-thread excerpt and source links.
The existing durable task sender now preserves thread and source metadata on send and retry. Sending while reading
older messages returns to the newest message. Build 9's composer, image viewer and canvas drawing behavior is retained.

Notification settings now describe the Android boundary accurately: the shared inbox is supported, while Android OS
alerts still need a supported provider and registration contract. iPhone APNs support does not enable Android alerts.

# 0.2.2 — Android build 9

Canvas drawings can now be edited on Android, as on the web. In Edit mode, choose Text, Rectangle, Ellipse, Diamond,
Arrow, Line or Frame, then tap to place one at its default size or drag to draw it. Tap a drawing to select it. Drag it to
move it, drag its corner to resize it, or drag either end of an arrow or line to reshape it; a bend is kept. Its options
let you:

- edit text and choose a text size;
- change the line style and the start and end arrowheads;
- duplicate, bring forward, send backward or delete.

Group… picks two or more items, drawings or windows, and frames them as a group; removing the group frame ungroups them.
Duplicates are placed 24 points down and right, and Bring forward and Send backward move one step at a time, both
matching the web.

# 0.2.1 — Android build 8

Chat and canvas fixes from overnight feedback. Each channel has one Messages list. Posts that started a conversation
show the reply count and latest activity, and tapping it opens the thread, so no conversation is hidden. Conversations
open at the newest message without scrolling, follow new messages while you are at the bottom, and keep your place when
you have scrolled back. Tapping an image in a message, or an image attachment, opens it full screen; pinch or double-tap
to zoom. Share is now a button inside that view, so a tap no longer opens the image as a file.

The composer's attach and mention buttons are stacked on the left, so the text field is wider. A pasted or picked image
goes in at the cursor as editable text, which becomes `![name](storage://…)` once uploaded, so you can move it within the
message. Images already placed in the message are not shown again as attachments.

Typing `@` opens a search over this device's data. It finds people and agents, documents, tasks, chat threads, channels
and scopes by name, and `doc:`, `task:`, `thread:`, `person:`, `agent:`, `channel:` or `scope:` narrows it to one type.
Choosing a result inserts the same tag the web uses, highlighted in the composer; only people and agents are sent as
structured mentions. Document and task titles are searched through a new local index, built once on upgrade.

A canvas now fills the screen: the app's top bar and section bar are hidden, and exit, the canvas title, Fit and Edit sit
at the bottom, above the keyboard and navigation bar. In Edit mode you can:

- add any kind of item from a searchable, filterable list, or create a new whiteboard in a channel;
- drag an item's handle to move it, or its corner to resize it;
- open, duplicate, bring forward, send backward, set an exact position and size, or remove an item.

# 0.2.0 — Android build 7

Notification inbox corrected for the final Tower contract. The inbox now shows only what this app may show: a category you
turn off here is hidden here without changing what your other Ambulando apps show, and read state stays shared across them.
The category switches in Settings change this app only. If you change them on another device, this app notices, reloads
the inbox and shows the latest choices; if two devices change the same setting at once, nothing is overwritten: the newest
choice is shown with a short note and you choose again. Build 7 supersedes build 6 (same version name).

# 0.2.0 — Android build 6

Adds the shared notification inbox. A bell in the top bar opens your Tower notifications with an unread badge; filter by
Inbox, Unread, Archived or category; mark read or unread, archive or dismiss; mark all read for what you have seen; tap
through to the thread, task or document. Saved notifications stay readable offline and changes you make offline are sent
when the connection returns. If something changed while you were looking (new activity, or another device), your tap is
not applied and the item shows as unread again. Items you can no longer access show no text. Settings adds per-category
inbox choices and says plainly what is not available: alerts, phone push, quiet hours and per-device settings need Tower
support and a push provider this build does not have. The inbox is empty from when your Tower activates it; older
activity is not imported. Older Towers without the inbox show a clear "not available" message.

# 0.1.3 — Android build 5

Large pasted text becomes an exact UTF-8 text attachment while preserving the composer and selection. Open draft
attachments to rename or edit them in bounded pages. Sent text attachments support cached reading, copying, downloading,
new versions and earlier history while preserving the original message attachment. Local drafts and unfinished sent
edits survive offline use and relaunch; conflicts keep your edit with immediate resolution actions. Keep on device
retains unsent edits until you explicitly save to Tower. Filename updates resume separately
from accepted text versions. Android clipboard size limits apply to Copy; Download preserves the full file.

# 0.1.2 — Android build 4

Recover transient read failures with bounded retries and fresh request signatures. Refresh on network return,
clear recovered Inbox warnings, and keep cached complete snapshots on failed refresh. Settings adds simple connection
status, Retry, and explicit export/clear of bounded private diagnostics. Diagnostics exclude content and identifiers;
see docs/privacy.md for retention and sharing. No automatic upload is added.

# 0.1.1 — Android build 3

Exclude private development revision metadata from the packaged APK. Application behavior is unchanged from build 2.

# 0.1.0 — Android build 2

Initial native Kotlin Android release. Adds identity/PIN/Keystore storage, native shared navigation, chat and durable
signed outbox, tasks, rich-document reading and bundled lossless editing, files/search/Deck/pins, canvas/task-tree/board
surfaces, Context/agents/Toolbox, isolated Nostr browser and scope/channel management.

Downloaded history remains read-only after access removal; server deletion tombstones still delete. Photos are
re-encoded without metadata, and backups/device transfer are excluded. See docs/parity-matrix.md for feature evidence,
unsupported features and validation limits. This release has synthetic-emulator and host read-only contract evidence;
authenticated live in-app member use remains for field review.
