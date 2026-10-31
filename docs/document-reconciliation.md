# Preserved document drafts, review and takeover (Android)

Android follows the current iPhone/Mac behaviour (native baseline `4495c20`, `7574bc6`). An open document's working copy is
never replaced by a newer shared save. The latest successful server save stays the shared document; Android only offers to
review it.

## Where the logic lives

All of it is in `core` so it is tested on the JVM without a UI:

- `DocumentEditController`: working copy, durable draft, authority, review and merge for one open document.
- `DocumentSaver` / `DocumentSaveRunner`: leases (one per editor session), claimed-draft saves, takeover, saved versions.
- `DocumentMerge`, `DocumentReviewJournal`, `DocumentReviewCodec`: the three-way merge and its durable journal.
- Compose screens in `DocumentEditor.kt` and `DocumentReview.kt` only render controller state.

## Drafts

Every edit is a durable SQLite draft keyed by connection, workspace and document, so workspaces and accounts never share
one. A draft survives offline periods, process death, locking, an expired lease and loss of access; none of those retire
it. A save first *claims* exactly the draft snapshot it submits (compare-and-set); typing that arrives during the request
keeps its text and moves to the version the save produced. A second save of the same draft is refused while one runs.

Save checks the latest head before uploading. A newer head leaves the draft untouched and opens review (no Tower recovery
copy is made). If a save's answer is lost, the next Save finds that Tower already holds exactly the submitted body and title
and acknowledges it without another version or upload; an upload that completed is reused. The editor also checks the head on
return to the foreground, on its minute timer and when review opens. The minute timer saves a changed document only when
nothing is waiting for review, authority is held and no merge preview exists. **Save no longer closes the editor.**

## Review

A persistent **Newer version available** notice offers **Review changes**: a line diff of your copy and the latest, both full
copies for reading and copying, and the two version numbers.

- **Keep editing** closes review and changes nothing.
- **Accept latest** asks first. The replaced copy (and any preview text, even invalid) is kept under **Preserved drafts (on
  this device)**, the head is fetched again, and no lease is taken and no version is created. If the head moved again, accepting
  stops for another review.
- **Merge my changes** needs a verified retained common base (exact version, hash and storage identity). Without one the
  screen says so and offers manual comparison only. Markdown merges keep whitespace and deletions; rich merges work on
  document nodes, text, marks, attributes and unknown envelope fields, and regenerate Markdown/block fields from the result.
  Text is compared by Unicode character (emoji sequences, combining marks and surrogate pairs are never split). Overlaps, and
  inputs too large to align safely, become named conflicts with **Use local** / **Use latest**; partial choices and a
  hand-edited Markdown preview are persisted.
- **Edit merged result** only creates a local draft based on the reviewed head. It is published only by an explicit **Save**,
  with the usual lease, expected row version and hash checks. The minute timer and the background flush never publish it: the
  hold is stored in the draft row itself (state `held`), so it commits atomically with the merged text and survives a crash
  before the review journal is written, a deleted or stale journal, and further typing or a failed Save. A copy restored from
  Preserved drafts takes the same hold, and a journal that cannot be read also keeps it (the unreadable text is retained). Only
  a successful Save, Discard or Accept latest releases it; later ordinary edits autosave as usual. If the head moved while reviewing, **Reconcile preview with
  latest** uses the previous reviewed head as the next common base; earlier previews stay recoverable.
- Preserved copies can be previewed, copied or restored as a local draft (never published).

## Authority

Android inspects `GET …/edit-leases` first. `document_concurrent_edit_v1` is used only when `document_edit_protocols`
advertises it, and then only if acquire and renew echo the protocol and this editor's session; it is never silently downgraded
to exclusive editing. Servers without inspection keep the ordinary exclusive acquire. In concurrent mode the exclusive
takeover is not offered.

On an exclusive server, when another editor holds the document the editor shows who and **Take over editing…**. Confirming:
(1) preserves divergent local work through the existing recovery-body save (`base_available: false`) and requires its
acknowledgement; (2) re-inspects and sends the explicit acquire with `takeover: true` and the `expected_lease_id` the person
saw; (3) fetches and verifies the latest body; (4) retires the matching local draft only after both. Tower checks that the
actor may write; the holder's identity is not compared on the client, so takeover works across people. A changed lease, a
403, an unsupported server or a failed recovery keeps the draft and asks for a new decision; authority is never taken back
automatically. If the other lease expired, the refreshed inspection shows nobody and the same action starts an ordinary
fresh session. The local draft also stays under Preserved drafts, so it is not reachable only through a Tower recovery id. Lease calls of one
editor session run in order, and a different session's calls never replace them: when Android recreates the activity (rotation,
theme or font change) the old editor stores its draft and releases its lease, and the reopened editor takes the document again
without waiting for the old session to expire. A session taken over elsewhere is fenced by Tower and cannot resume; an editor whose lease merely lapsed
offline resumes only its own session, only while nobody holds the document and its base is still the head.

**Versions** lists Tower's stored previous versions with a text preview. Keeping one names the reviewed head's row version
and hash and needs a valid lease; a stale choice fails and keeps both. Dismiss only retires that stored copy.

## Limits

Source and synthetic tests do not establish live behaviour. Serving Tower capabilities, a second real editor, a physical
phone and a real account must be verified separately. Most connected review tests store a draft the way the editor does and
drive the screens by touch; `typingIntoTheEditorPageBecomesADurableDraftAndOnlySaveCanPublishIt` instead taps into the real
editor page and types with key events, then reopens the editor, recreates the activity and saves. Typing that includes an
IME composition (suggestions, other languages) is not covered.
