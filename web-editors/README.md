# Embedded web editors (boards and documents)

Ambulando's screens are native Compose. Two editors are reused from the web client exactly as the iPhone app reuses
them: the Excalidraw board editor and the Tiptap document editor. This folder builds those two pages; the Gradle task
`:app:buildWebEditors` runs the build and packages `dist/` into the APK as `assets/editors/`.

- `web/sol/`: the web client's board editor and document schema/Markdown conversion, vendored unchanged with their
  provenance in `web/sol/PROVENANCE.md`.
- `web/main.js`: connects the board editor to the host app. Outgoing messages go to
  `window.webkit.messageHandlers.board.postMessage(json)`; incoming ones arrive through
  `window.ambulandoBoard.deliver(json)`.
- `web/document.html`, `web/document.js`, `web/document.css`: the document editor page. Outgoing messages use
  `messageHandlers.document` (small JSON messages) and `messageHandlers.documentFile` (one pasted file per message);
  incoming calls go to `window.ambulandoDocument`.
- `web/document-preserve.js`: lossless editing. Unknown nodes become kept atoms, unknown attributes and marks are added
  to the schema for that document, and unchanged nodes and marks are written back as the stored JSON. Tests:
  `bun test test`.

On Android the host injects a document-start script that defines `window.webkit.messageHandlers.*` and forwards each
message to an origin-scoped `WebMessageListener`, so these pages run unmodified. The pages are served from the app's
assets through `WebViewAssetLoader`; they have no signer, keys, `window.nostr` or network access (`connect-src 'none'`),
and the host refuses every navigation away from them.

`package.json` and `bun.lock` pin exact dependency versions. `LICENSES.md` lists the notices for everything bundled.

Build manually: `bun install --frozen-lockfile && bun run build` (about 21 MB, 13 MB of which are Excalidraw's fonts).
`dist/` and `node_modules/` are not tracked.
