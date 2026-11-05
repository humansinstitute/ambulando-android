# Branch history

Opening a conversation requests Tower's effective transcript on every forward page. The server owns the saved
lineage and cutoff: titles are labels, not evidence of the branch point. The client never repairs conversation data
based on a title.

Schema 8 stores effective thread membership separately from canonical message ownership. A parent refresh cannot
erase inherited membership or make inherited rows editable. A complete refresh prunes only that conversation's
membership; interruption leaves prior history available. Canonical parent rows remain independent. Workspace removal
and server message/thread tombstones clean membership. Android's existing non-destructive access-loss policy remains:
previously received rows stay readable with access disabled.

Inherited messages show “Inherited history · read only”; reactions, author editing and deletion are unavailable.
Copying, attachments and branching remain available. A nested branch from an inherited row uses the active effective
conversation and the exact selected message ID. It does not branch again from the message's canonical parent.

The thread starts with its latest 50 rows. “Load earlier messages” adds another 50 with no fixed history ceiling,
so conversations longer than 500 rows remain reachable. Channel feed and bounded header excerpts are unchanged.

Core regressions cover a 620-message prefix, excluded later parent rows, canonical refresh, cold reopen, membership
pruning, tombstones, foreign-view rejection, attachment metadata, every effective page and nested branch selection.
These isolated tests do not establish a real user's saved cutoff or physical-device acceptance.
