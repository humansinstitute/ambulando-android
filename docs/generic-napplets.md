# Generic File napplets v2

Apps → Add napplet installs an existing NStack HTML File as a personal File pin.
Choose a locally available File or paste a same-backend File link (a canonical
File UUID is also supported). The local picker is not a complete inventory.
Uninstall removes the pin only. Files, data and revision history remain in NStack.
Opening a File directly uses the same runtime, independently of installation.

A v2 HTML manifest provides the title, local text/emoji icon and one named
capability binding. Arbitrary app IDs and binding names require no native code
changes. The host dispatches by capability ID/version: `private-json-file@1`
provides bounded JSON object read and declared CAS save; `workspace-aggregate@1`
provides fixed read-only workspace report ranges. A new host capability requires
runtime support. Existing v1 Daily Metrics and Workspace Report remain separate.

Code bytes and the current version, metadata, File/channel authority and explicit
resource bindings are verified before consent and data delivery. Code uses normal
File ACLs; JSON data must be private storage in the viewer's single-participant
self conversation. A top-level boolean `can_write` takes precedence; only an absent
top-level field permits the nested File boolean. Missing or malformed flags fail
closed. Declared save and live write authority must both be present. Installation never provisions or rebinds data. Ordinary HTML
has no bridge; malformed v2 cannot become an ordinary bridged document.

Approval is device-local in the separate `napplet-approval-v2:` namespace. Changed
code, resources or effective write grant require consent. Check for update reopens
the same code File without modifying bound data. Forget approval leaves saved data
intact. Healthy periodic authority checks preserve the document's unsaved draft;
context loss, backgrounding, navigation or authority loss removes private DOM.

The opaque WebView has no signer, native credentials, storage or network access.
Requests use a 128-bit nonce, exact versioned envelopes, a shared 10,000-ID replay
budget, one operation in flight and a 45-second deadline. Busy requests do not
consume request IDs. Save uses canonical JSON bytes, a base version and exact
acknowledged readback; conflicts and uncertain acknowledgements do not retry.
Unavailable aggregate APIs remain unavailable and never fabricate zero counts.

The portable protocol and golden fixture are shared with the web runtime. Tests
using synthetic Tower storage are fixture evidence, not a claim of real NStack
installation or public Android delivery.

`workspace-aggregate@2` adds an explicit administrative storage opt-in on the
same bound current workspace and unchanged read/range bridge. The host alone
adds `storage=workspace` to the fixed report route through the workspace sync
owner. Version 1 continues to use its exact original query and response parser.
The capability version is part of the remembered approval, so this expansion
requires review. Consent includes retained private-scope storage totals; no
message or File content, object keys, database names or provider credentials
enter the frame. Effective server `workspace.manage` is required for every read.

The common `workspace-storage-v1` fixture defines bounded groups and metrics,
with safe IDs, units, methods, nonnegative safe integers and explicit unavailable
nulls. Hosts accept future metric IDs within this structure without a rebuild;
NStack-loaded cartridge code controls labels and rendering. Storage is current
at the enclosing report's exact workspace/as-of, independently of activity dates.
Logical PostgreSQL row estimates and deduplicated retained object metadata are
not physical database or provider measurements. Missing measurements stay
unavailable. A denied administrative read returns `forbidden` for version 2;
version 1 retains its existing unavailable response. Failed reads clear the
cartridge's displayed data. These source/fixture checks do not establish backend
activation, publication of a new report revision or Android store delivery.

Administrative totals are re-read on window focus, relevant local access changes,
and each 30-second foreground authority check. A failed or denied re-read sends
an error to the cartridge so it clears prior totals. Backgrounding destroys this
v2 frame; foregrounding verifies access and reconstructs it. The current workspace
service identity must match the pinned Tower and registered application before
an administrative request is sent. Aggregates remain transient; no storage count
cache is persisted. The shared cartridge, complete report and error goldens in
`core/src/test/resources/napplets/` are also used by native instrumentation.
