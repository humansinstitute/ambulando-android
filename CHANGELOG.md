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
