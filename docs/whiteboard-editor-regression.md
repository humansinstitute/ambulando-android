# Focused whiteboard editor regression

Use isolated synthetic data, never a personal scene or identity. A local build or JVM test does not establish device or upgrade acceptance.

The JVM `FocusedEditorNavigationTest` exercises the real shell with an editor stand-in: both Files and Artefacts origins, exclusive toolbar ownership, useful viewport height, toolbar and system Back dispatch, restored filter and list scroll, and ordinary file chrome. `DeckRootStateTest` covers retained root state. Core board controller tests cover suspended drafts, conflict handling and save/discard behavior.

`ChannelArtefactsFlowTest` exercises actual whiteboard routes and rendering on Android. Run it only on an available, healthy dedicated device. Compile-only results must be labelled as such.

Before release, verify on the target device:

- Open the same synthetic whiteboard from Files and Artefacts. Only its native Back/title/status/options toolbar remains; global header, section navigation and tablet sidebar are absent.
- Drawing tools, Save, undo/redo and canvas remain visible and usable in portrait, landscape and a wide window. Capture screenshots and check actual drawn pixels and viewport geometry, not only DOM readiness.
- Leave unchanged via toolbar Back and system Back. Confirm the originating folder/list, text/type filter and scroll position return, with global navigation restored. Reopen repeatedly; switching to another section must not inherit focused editor chrome or an inactive editor's Back handler.
- Make an unsaved edit, leave via each Back action, then reopen. Confirm the draft and unsaved/version status survive, without publishing a version on exit. Save explicitly and confirm the new version; reopen and check content. Exercise discard/cancel, a newer remote version, read-only history and restore using existing board controls.
- Open an ordinary file, HTML artefact, Chat, Tasks and Deck. Confirm their normal global navigation remains.
- Upgrade the previously delivered app using the same signing certificate. Confirm identity, connections, list navigation and drafts remain, then repeat both entry routes and Back actions.

Device screenshot and upgrade acceptance are required before store delivery. A crashing emulator host is a validation blocker; do not repeatedly restart it or count a successful source build as runtime acceptance.

## Profile-name presentation

Run `bun test web-editors/test` for the editor helper, text escaping, exact-identity fallback, bounds and
unchanged comment/scene data. `BoardAuthorNamesTests` covers checksum validation, local-cache arrival,
rename/removal and stale document/history keys. `AdministrationNamesTests` checks custom labels,
group children and unchanged raw actor/record fields. These JVM tests require the normal core test slot.

On the reserved synthetic device, run `BoardAuthorNamesTest` for the actual bundled WebView discussion:
unknown author, cached name, rename, wrong identity, stale view, escaped text, retained reply draft and
no content write caused by presentation updates. Also run existing board editing/history/draft tests.
Manually verify late profile updates in member/group/delegate lists, the channel audience picker,
document comment recipient resolution and agent labels. An open member edit form must keep the stored
value and unsaved edits while its surrounding display label updates. A profile rename must not change
a selected assignee, ACL recipient, channel route or board/comment identity. Keep key/copy/security
fields visible as before. Do not treat source checks or test compilation as device acceptance.
