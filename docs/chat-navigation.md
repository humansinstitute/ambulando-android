# Chat entry and Back

Conversations and channels opened from Deck stay on Deck's record stack. The visible Back action and Android
system Back pop the same stack, returning to the Deck card that opened the chat. Opening these routes does not
change Deck's scope/channel filter or Chat's independent stack. Opening a conversation within a Deck channel
adds it above that channel, so Back retraces the entry path.

The section root uses a saveable state holder while a record covers it. Deck's selected card, list positions,
Inbox search and type filters survive that transition. Context/workspace changes retain their existing boundaries.

Chat-list entry continues to use Chat's stack. Notifications/direct destinations use Chat with its root as the
fallback; no synthetic Deck route is inserted. Saved per-workspace route stacks also preserve a Deck parent.
