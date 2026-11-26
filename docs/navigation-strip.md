# Workspace navigation strip

The bottom navigation scrolls as one row, including its leftmost globe (Home). Home is its own surface (`AppSection.HOME`, see `goals-home.md`): goals in focus, the Daily Briefing and the latest chats. Choosing it (globe or sidebar Home) clears scope/channel selection and view lock; it does not switch workspace or grant access. Deck keeps its own section button with its existing select/reselect behavior, and choosing a scope or channel opens Deck. The globe is selected while Home is shown. An unfiltered workspace opens on Home; a restored section, a saved scope/channel context, deep links and notifications still open where they point.

Each button stacks a 24 dp icon above its single-line 11 sp name, with a minimum 56 dp height. Button widths follow the available strip width divided by 4.5, bounded to 72–96 dp, restoring the approved Android layout. The whole strip scrolls horizontally on narrow phones, and wide windows keep 96 dp buttons instead of stretching them. The strip also remains available beside the tablet sidebar. Existing keyboard-open and focused-editor chrome rules continue to apply.

The scroll state belongs to workspace boundary, scope/channel IDs, section and record stack. It resets on actual navigation and starts at the left after Activity recreation. Changes to labels, sync status, live records or optional section visibility do not recreate it. Native Compose scrolling and clickable tab semantics handle touch cancellation, keyboard focus, accessibility scrolling and selected state. System navigation-bar padding keeps controls clear of the safe area.

Validation includes JVM Compose selection tests and `NavigationStripTest` on an isolated sample-data emulator. Sample data is debug-only; release upgrade and installed/store acceptance must be reported separately.

Chat is hidden by default. Settings › Sections › Show Chat in navigation restores it between Deck and Tasks. This device-local choice persists across restarts and compatible upgrades. Hiding a button does not change the current section, conversation stacks, drafts or explicit thread/message/notification entry; Deck keeps its name and routing.
