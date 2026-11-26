# Chat conversations and offline search

Chat opens a compact conversation inbox for Home, the selected scope, or the selected channel. It uses the existing
thread and message IDs, including the existing direct-message and note-to-self channels. No server catalogue or
record migration is involved. New thread reuses Deck's destination-aware composer, durable outbox and signed sends.
An absent `settings.chat` preference now shows Chat; an explicit stored `false` remains respected. The previous code
only wrote this setting when a person used its toggle, so that stored choice is distinguishable from the old default.

All lists accessible cached conversations in latest-message order. Unread uses Tower's thread view state and attention
versions. Mine includes the thread creator and anyone with an accessible cached message in the effective transcript.
Inherited history uses `thread_message_members`, preserving each message's canonical owner. Metadata-only threads
remain visible and explain that their messages have not been downloaded. Participant and reply counts describe the
accessible cached transcript, not an invented server total.

Search clauses are ANDed across a conversation, including different replies:

| Query | Meaning |
| --- | --- |
| `deployment checklist` | Both exact words, anywhere in the title or accessible messages |
| `"deployment checklist"` | Adjacent words within one title or one message |
| `title:"deployment checklist"` | That phrase within the title |
| `body:"deployment checklist"` | That phrase within one message |
| `title:deployment checklist` | Deployment in the title, checklist anywhere |
| `title:”free text”` | Curly double quotes work like straight double quotes |

`title:` and `body:` affect the next clause only; whitespace after the colon is allowed. FTS operators are literal
words, not executable syntax. Unsupported fields, incomplete quotes, adjacent quoted fragments and excessive input
show a correction message. Every SQL value is bound; only parser-generated quoted expressions reach FTS5.
SQLite's existing Unicode tokenizer supplies case and diacritic matching. Ordinary words are exact, not prefixes.
A matching excerpt is highlighted; a body hit opens that exact message even before the latest 50-message window.
The thread retains its header, actions, Markdown/media renderer, anchored composer, draft and reading-position logic.

Schema 9 backfills cached titles and message text and maintains them transactionally on insert/update/delete.
Conversation search checks workspace/channel/scope/thread/message access before intersecting individual clause hits.
The separate existing global search retains Android's historical labelled, read-only cache behavior; the new Chat
inbox excludes inaccessible content. A retained local copy never grants server access.

Search works offline over cached history. Coverage shows cached message count and how many selected channel histories
have completed a download. Download history uses the existing forward-cursor channel catch-up, reports changing cached
counts, and exposes failures/retry. It also refreshes metadata for known message threads beyond the capped 200-record
thread-list response using existing individual thread reads. This is coverage of downloaded channel history, not a
promise that an offline cache is current or that the server thread catalogue was fully enumerated. Effective inherited
history becomes searchable after it has been fetched through the existing thread transcript route.

Mac shares the approved query semantics. Web and iPhone parity for this inbox/search presentation remain pending
approved-task nightly catch-up; this Android change does not establish delivery on either surface.

## Retained-history loading

The inbox projects scalar SQL summaries rather than decoding every canonical message JSON record and issuing one
thread lookup per result. Canonical/effective membership is deduplicated before aggregation. Only the first and latest
message bodies are fetched for each summary, with deterministic timestamp/id ordering. Cached records, identities,
FTS entries, drafts and pending writes remain unchanged; no database migration is required.

Each reader keeps at most eight recent context/search results, keyed by workspace, selected channels, viewer, query
and filter. Workspace, scope, channel, message, thread, view-state or access revisions invalidate those results.
Reactions, draft edits and unrelated activity do not rebuild the inbox. Slow live queries combine queued matching
change batches into one fresh snapshot; an unrelated later batch cannot hide a relevant invalidation.

`ConversationLoadingTests` exercises 1,200 threads and 48,000 retained messages through the real inbox and queued Deck
reader, with query/result-row budgets and measured cold/warm latency. `RetainedConversationLoadingTest` exercises
actual Android navigation and the same retained history, plus a server-backed synthetic thread, signed send,
menu reactions, draft retention and keyboard clearance. Timing output is evidence for the tested host/device, not a
guarantee for arbitrary devices or a substitute for measuring network/download latency.

Thread replies use flat incoming rows and subtle own-message surfaces. Channel conversation cards remain separate.
The message menu provides first-reaction actions; existing reaction chips remain visible. Full-screen threads own
the navigation-bar inset, while the section bar owns it for normal channel screens; the composer consumes the IME
inset. The header keeps one Back action and the original system text scale and palette.
