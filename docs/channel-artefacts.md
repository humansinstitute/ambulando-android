# Channel artefacts

The Artefacts section includes ordinary documents and rich Files in the selected channel or scope. Filter by document,
whiteboard or HTML, search by title, use the prominent + (“New artefact”) to choose Document, Whiteboard or HTML, and pin it for direct opening from Deck.
Documents retain their existing storage and editor. Canvases remain available and refer to the same Files.

Rich Files carry `metadata.artefact` with `schema_version:1`, `format_version:1`, `type` (`html` or `excalidraw`) and
`editor_id` (`builtin.html` or `builtin.excalidraw`). The registry is built in; metadata cannot install executable code.
Legacy MIME types, HTML markers and Excalidraw filenames remain discoverable. Unsupported format/editor versions
fall back to ordinary File handling. Presentations and spreadsheets are deferred.

Opening a whiteboard directly uses the existing Excalidraw editor, immutable File history and durable board drafts.
HTML opens through the authenticated `/files/:id/html` projection in the isolated, network-blocked WebView without a
signer or host bridge. Approved manifest image assets are fetched through exact private File-version routes, checked
for type, size and SHA-256, and supplied in memory. Unavailable assets do not receive bytes.

Rich artefacts opened from Files or Artefacts use one focused toolbar with Back, a single-line title and an ellipsis menu.
Version history, imported-source provenance and the original-source link live in that menu, without a permanent secondary
strip. Whiteboards retain their drawing tools and primary Save; System Flows keep Save in the title row. Dirty, read-only,
loading and conflict information remains available when relevant. Ordinary HTML uses its own toolbar instead of the outer
app navigation and duplicate viewer title; pinning and source editing are in its overflow.

HTML's overflow menu browses saved revisions. Edit source saves an immutable version against the base that was read;
a stale base leaves the draft intact. Restore source starts from historical bytes and saves against the current base.
Unsaved source and its base are preserved within the workspace's private draft directory; closing the source dialog
keeps the draft, and reopening restores it. Source editing is limited to ordinary HTML bytes; manifest JSON is edited
through its authoring tool. Permission checks remain on the server for every read and mutation.

Personal File pins use the existing actor-private CAS pin routes with `resource_type:file`. Chat File mentions,
channel rows, pins and ordinary File navigation resolve the same File UUID. No catalogue database or migration is added.

For synthetic device tests, build with `-PisolatedArtefactTest=true`. This changes only the debug package to
`studio.otherstuff.ambulando.android.artefacttest` and omits Firebase configuration for that disposable package, so an
installed signed release can remain in place for upgrade validation. Production package and Firebase configuration
are unchanged. `ChannelArtefactsFlowTest` exercises direct whiteboard navigation and HTML create/edit/version browsing.
`CompactArtefactToolbarFlowTest` checks imported-source overflow and read-only controls for whiteboard, HTML and System Flow
fixtures through both Files and Artefacts, recording compact-phone screenshots.

Daily tracker Files can run the shared inline HTML bundle through a deliberately narrow File data host. The
Apps → Daily Metrics → Set up Daily Metrics action explicitly creates an empty JSON File and a separate HTML File in the signed-in viewer's
self-conversation. Runtime code always comes from the NStack HTML File; the packaged template is only for creation.
A direct current File open requires the exact v1 tracker declaration and host-owned File binding, then asks the user
to trust the code and its data access. Ordinary HTML, canvas HTML and historical/source views receive no bridge.

The host exposes only fixed read/save/dirty messages with a per-document nonce, main-frame checks, a 1 MiB model
limit and schema validation. All network/navigation restrictions remain active. Native signed requests recheck the
code File, bound JSON File and self-DM participants, verify private storage and exact-version hashes, and save with
base-version CAS followed by readback. A conflict retains the in-memory draft until an explicit reload; errors or
revocation destroy the page. Access is checked every 30 seconds, on foreground and relevant store invalidation.
Ordinary backgrounding hides/pauses the same in-memory page and blocks new commands; foreground reauthorization is required before resuming it. A pending explicit save resolves against its original base and its result waits for reauthorization. Unsaved edits are not a durable offline queue. Close/Back confirms
unsaved discard and is disabled during a pending save. NStack's normal administrator authority still applies.


Daily Metrics is visible in Apps before setup. Opening it lists the signed-in viewer’s self-DM Files by
`metadata.napplet.app_id == daily-tracker`; one instance is reused, several require a chooser, and only a complete
successful empty discovery offers setup. The bounded Files endpoint cannot prove completeness at 200 rows, so that
case stops discovery and offers no setup. Failed or invalid discovery never creates a replacement. Setup checks again
before creating empty Files. App mode opens the existing trusted File host directly without source/history chrome.
Legacy File links, pins and inspection remain available. The template, storage format and bridge are unchanged.

The logical app route is `/flight-deck?workspaceid=<workspace UUID>&napplet=daily-tracker`, with launcher identity
`napplet:daily-tracker`. Native in-app links support relative routes and HTTPS routes on the selected Tower origin;
other frontend origins continue to open in the existing browser. Android has no new OS domain association or shared
web placement preferences. Apps retains existing WApp placement behavior (configured in the web app).

The primary + has the accessibility label “New artefact” and test tag `artefacts.new`. Its single scrollable dialog
shows all three type choices before Title and Channel. Channel destinations are limited to the current channel,
or current scope when browsing a scope, and exclude removed access. Blank titles cannot be submitted.
Cancel keeps the entered title without queuing anything. Create queues the existing document or staged File write;
pending/retrying/failed rich creations stay visible in Artefacts with the normal Retry/Discard actions.
No “created” confirmation is shown before server acknowledgement. `ChannelArtefactsFlowTest` clicks this primary +
to create all three types, checks persistence through the fixture API, draws/saves/reopens the created whiteboard,
and checks cancellation and an offline upload retry without a duplicate File.

## Controlled starters and System Flows

The primary + also offers System Flow. Document starters are Blank document and Working brief; documents keep
normal app typography. Whiteboard, Interactive explorer HTML and System Flow use maintained Ambulando/Paper styles.
Rich File metadata retains the web's versioned `template_id`, `template_version`, `style_id` and `style_version` descriptor.
These are packaged choices, never downloadable editor plugins. HTML explorer controls run locally in the existing
sandbox; form submission and extra network access remain unavailable.

System Flow Files use `application/vnd.ambulando.system-flow+json` and `builtin.system-flow`. The native editor
validates web schema 1–3 and shows a diagram overview using saved positions, containers and directed relationships.
Native fields edit node label, kind, owner, description and relationship labels; action nodes and flow relationships can
be added with validated IDs. Complete labels remain inspectable below the overview. All diagrams, scene collections,
edge styling, parent relationships and unedited source fields remain in the model. Linked whiteboard scene editing
and feedback require the original editor; they are retained rather than flattened. Incomplete schema-3 indexes cannot
be edited: an editable snapshot must contain every linked scene. The shared Flow source limit is 32 MiB.

The focused flow editor owns its Back/title/save/version controls; section navigation is hidden until Back returns
to the originating list. Each accepted edit is serialized into a boundary-private durable draft with its original base version, without
publishing on Back. Save rechecks authority and creates a private immutable File version with CAS, then verifies
readback. Historical versions are read-only; Latest resumes the preserved draft. Conflict, read-only or unavailable
save leaves the draft and base intact. Discard requires explicit confirmation. Local field state preserves keyboard
focus while ordered edits persist; network operations disable editing until finished.

Migrated rich Files show selected source version, limitations and an original-source link. This is navigation outside
the editor, not new network authority. Pending legacy embedded PNG/JPEG/GIF/WebP images receive a view-only saved
status after checking their embedded data signature; files hydrate after scene elements. Opening does not write a
revision or rewrite source bytes. Private assets continue through existing authenticated File/version routes.

`SystemFlowTests`, bundled `board-images.test.js` and `SystemFlowFlowTest` cover the new contracts. The private-pilot
clone instrumentation case expects operator-supplied read-only snapshots in the isolated package's private files
`artefact66/`; they must never be bundled in the APK or committed. It tests device-local synthetic destinations and
captures rendered pixels. Passing synthetic checks does not establish live member permissions or store delivery.
