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
