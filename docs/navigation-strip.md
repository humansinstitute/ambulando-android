# Workspace navigation strip

The bottom navigation scrolls as one row, including its leftmost globe (Home). Home uses the current workspace's existing Deck and clears scope/channel selection and view lock; it does not switch workspace or grant access. Deck's ordinary section button keeps its existing select/reselect behavior. The globe is selected only at the unfiltered Deck root.

Each button places an 18 dp icon to the left of its single-line 13 sp name, with a 6 dp gap and a minimum 48 dp touch target. Width follows the label, including larger accessibility text; the whole row scrolls horizontally on narrow phones without truncating names. Wide windows retain comfortably sized buttons instead of stretching a few across the window. The strip also remains available beside the tablet sidebar. Existing keyboard-open and focused-editor chrome rules continue to apply.

The scroll state belongs to workspace boundary, scope/channel IDs, section and record stack. It resets on actual navigation and starts at the left after Activity recreation. Changes to labels, sync status, live records or optional section visibility do not recreate it. Native Compose scrolling and clickable tab semantics handle touch cancellation, keyboard focus, accessibility scrolling and selected state. System navigation-bar padding keeps controls clear of the safe area.

Validation includes JVM Compose selection tests and `NavigationStripTest` on an isolated sample-data emulator. Sample data is debug-only; release upgrade and installed/store acceptance must be reported separately.

Chat is hidden by default. Settings › Sections › Show Chat in navigation restores it between Deck and Tasks. This device-local choice persists across restarts and compatible upgrades. Hiding a button does not change the current section, conversation stacks, drafts or explicit thread/message/notification entry; Deck keeps its name and routing.
