# Workspace navigation strip

The bottom navigation scrolls as one row, including its leftmost globe (Home). Home uses the current workspace's existing Deck and clears scope/channel selection and view lock; it does not switch workspace or grant access. Deck's ordinary section button keeps its existing select/reselect behavior. The globe is selected only at the unfiltered Deck root.

Buttons occupy one ninth of twice the phone width (approximately 4.5 visible), bounded to 72–96 dp and at least 56 dp tall. Wide windows retain comfortably sized buttons instead of stretching a few across the window. The strip also remains available beside the tablet sidebar. Existing keyboard-open and focused-editor chrome rules continue to apply.

The scroll state belongs to workspace boundary, scope/channel IDs, section and record stack. It resets on actual navigation and starts at the left after Activity recreation. Changes to labels, sync status, live records or optional section visibility do not recreate it. Native Compose scrolling and clickable tab semantics handle touch cancellation, keyboard focus, accessibility scrolling and selected state. System navigation-bar padding keeps controls clear of the safe area.

Validation includes JVM Compose selection tests and `NavigationStripTest` on an isolated sample-data emulator. Sample data is debug-only; release upgrade and installed/store acceptance must be reported separately.
