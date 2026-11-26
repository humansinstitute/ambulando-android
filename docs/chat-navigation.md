# Chat entry and Back

Conversations and channels opened from Deck stay on Deck's record stack. The visible Back action and Android
system Back pop the same stack, returning to the Deck card that opened the chat. Opening these routes does not
change Deck's scope/channel filter or Chat's independent stack. Opening a conversation within a Deck channel
adds it above that channel, so Back retraces the entry path.

The section root uses a saveable state holder while a record covers it. Deck's selected card, list positions,
Inbox search and type filters survive that transition. Context/workspace changes retain their existing boundaries.

Chat-list entry continues to use Chat's stack. Notifications/direct destinations use Chat with its root as the
fallback; no synthetic Deck route is inserted. Saved per-workspace route stacks also preserve a Deck parent.

## Compact mobile conversation header

Below the 760dp tablet breakpoint, a conversation opened from Chat, Deck or Home owns the only top bar.
It keeps Back, the thread title and channel/participants, the accessible Thread artefacts button and Thread menu.
Pin/Unpin and Add to goal move into the labelled menu alongside the existing thread actions. Search, Notifications,
Browser, Settings and Open navigation remain available there. Android Back dismisses an open menu before leaving
the thread. The originating section stack, composer and artefact state remain mounted as before. No bottom
artefact switcher is added. Tablet chrome retains its existing layout.

When an attached HTML, whiteboard or document editor is ready, its own header replaces the artefact workspace
header on a narrow screen. Back, Artefacts and Discuss this remain directly accessible; versions and HTML comment
actions use labelled menus. Editor Save controls remain available. Loading, failed and pinned document views retain
a fallback header, including access to saved document versions and the current draft.

This is Android presentation behavior. iPhone and mobile-web single-bar parity are pending the approved overnight
catch-up; Android validation does not establish delivery on those clients.
