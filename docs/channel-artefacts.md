# Channel artefacts

The Artefacts section includes ordinary documents and rich Files in the selected channel or scope. Filter by document,
whiteboard or HTML, search by title, create a whiteboard or HTML File, and pin it for direct opening from Deck.
Documents retain their existing storage and editor. Canvases remain available and refer to the same Files.

Rich Files carry `metadata.artefact` with `schema_version:1`, `format_version:1`, `type` (`html` or `excalidraw`) and
`editor_id` (`builtin.html` or `builtin.excalidraw`). The registry is built in; metadata cannot install executable code.
Legacy MIME types, HTML markers and Excalidraw filenames remain discoverable. Unsupported format/editor versions
fall back to ordinary File handling. Presentations and spreadsheets are deferred.

Opening a whiteboard directly uses the existing Excalidraw editor, immutable File history and durable board drafts.
HTML opens through the authenticated `/files/:id/html` projection in the isolated, network-blocked WebView without a
signer or host bridge. Approved manifest image assets are fetched through exact private File-version routes, checked
for type, size and SHA-256, and supplied in memory. Unavailable assets do not receive bytes.

HTML's Versions menu browses saved revisions. Edit source saves an immutable version against the base that was read;
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
