# Wiki links (Android)

The same contract as iPhone, iPad, Mac and web.

- A link is `[Title](wiki:<document-id>)` in Markdown and an `fdWikiLink` node `{documentId, title}` in a rich document. The
  id is the identity; scope and channel are only where the page is now. A link grants no access.
- `[[Title]]` is a title-only reference, unresolved until a page is chosen or created. Inside code (inline, fenced,
  indented) it is plain text in the editor, search and binding.
- A bound id that is not among the readable saved pages is opened by id (Tower decides) and is never replaced by a title
  match or a new page. Deleted and archived targets are unavailable, not rewritten.
- Opening a document rewrites nothing.

| Action | Result |
| --- | --- |
| Tap a bound link (reader or editor) | The draft is stored first, then the page opens by id. If the text cannot be stored the other page is not opened. |
| Tap a title-only link matching one page in the source channel | The match is bound (a stable link) and opened. |
| …matching several | "Choose a page" lists each with Scope / channel; nothing is picked. |
| …matching none | "Create page": pick the destination (default the source channel), **Create and link**. |
| Type `[[` | A panel over the page: this channel first, **Search every channel and scope**, and "Create “q” or search more…". |
| Toolbar **Link** | Search sheet (this channel by default, every scope on request; same-titled pages listed separately), results labelled Scope / channel, and **Create and link**. |
| Document **Move…** | Unchanged (online only, consequences first). |
| **Linked from** | Unchanged (saved pages that link here, with how many could be checked). |

## Create and link

One durable `doc.create` write carries the link to put into the source (`wiki_source_doc`, title, occurrence, the number of
same-title references when asked, and a request id). It is idempotent: the upload is reused after a lost answer, a retry
finds the already created page, and a queued request for the same title and channel is never repeated. Before the write is
retired the source receives the link: an open editor binds it itself and stores a durable draft; otherwise the draft-first
save path stores it (and saves it when the source had no unsaved edits). If that cannot happen the write stays queued
(`wiki_link_pending`, with `wiki_created_doc`), and the retry never makes a second page. If the source's same-title
references changed after the request, nothing is bound by position and the person is told. The created page opens only on
the same visit, account and workspace.

A durable draft is local recovery, not a canonical save; **Save** still publishes unsaved edits.

## Differences from iPhone

A reader `\[[Title]]` is treated as a reference (as on iPhone). Links to unsaved text and "Linked from" use only pages saved
on this device. Cross-workspace links are not supported.

## Tests

Core: `WikiResolutionTest`, `WikiCreateAndLinkTest`, `WikiCreateWriteTest`. Editor page: `bun test` in `web-editors/`
(`document-wiki.test.js`). Screens: `DocumentReviewUiTest` (Robolectric). Device: `DocumentReconciliationFlowTest` on the
synthetic emulator.
