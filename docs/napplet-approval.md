# Remembered napplet approval

The first direct open of a valid Daily Metrics File asks **Trust and open**.
Approval is remembered on this device. Subsequent opens check current access
before matching that approval or creating the isolated WebView. Ordinary HTML,
source and historical previews never gain the data bridge from a receipt.

The v1 policy binds the normalized Tower origin, connection and available Tower
identity, application namespace, workspace and app identity, current viewer,
code File ID, SHA-256 of verified HTML bytes, validated manifest version/app ID,
exact bound data File IDs, formats, privacy and permitted operations, and current
effective write access. Code must have exact verified UTF-8 bytes; malformed
encoding, digest or size mismatches fail closed. Changed code, binding or access requires new consent or
fails validation. The immutable code version is checked during authorization,
but does not enter the receipt: a new version with identical bytes can reuse
approval. Data contents and data version IDs never enter the receipt.

Only opaque boundary/capability fingerprints persist, beneath the production
app's no-backup storage. Receipts contain no tracker answers or signing keys,
are never uploaded, and do not follow an account to another device. Removing
the identity removes its local receipts. A missing, malformed, unsupported or
unreadable receipt cannot automatically approve code. If saving approval fails,
an explicit grant can run the current session with a visible notice that it
could not be remembered.

**Forget approval** is visible in the running app's controls. Pending saves block
it; unsaved edits require confirmation before discarding and forgetting. It
removes approval for the current app boundary and closes its bridge, leaving
saved NStack data intact. If removal fails, the UI reports that revocation could
not be persisted and asks the user to retry. Reopening a successfully forgotten
app asks for consent again.

Approval is consent, not authorization. Fresh signed File/self-DM checks run on
every open and existing read/save, foreground, focus, periodic and invalidation
paths remain enforced. Sandbox, signer separation, conflict checking and
background pending-save behavior are unchanged. Each bridge operation also
enforces the approved write capability: a read-only grant cannot save if server
permissions broaden before a sync notification or periodic check arrives.

Validation uses synthetic records: core tests exercise isolation and malformed
storage; native instrumentation exercises the actual Compose host, WebView,
reopen/recreation, data preservation, code changes, revoke and storage failure.
This does not establish behavior on a member's physical device or personal data.

## Explicit built-in code updates

For the exact original built-in Daily Metrics HTML (14,824 bytes, SHA-256
`192779e50335a83d90076fb17954059e466ccfecea25c5eb5b14d68e8dfa91cd`),
**Update app** offers the packaged replacement as an explicit host action.
Customized, unknown and already updated code is never overwritten. Existing
instances continue to run without updating. Pending saves block updating;
unsaved edits require explicit discard confirmation. The confirmation describes
that changed code will require fresh approval before running.

The host rechecks current code-write authority, exact immutable original bytes,
unchanged metadata and binding, and bound data self-DM access before uploading
and again before publishing. Only a new version of the original code File is
written, using its original base version. No data File, binding or metadata write
occurs. Success requires an acknowledged new version and exact object/byte
readback. Conflict, failed checks and uncertain acknowledgements require closing
and reopening; there is no blind retry. The old bridge is destroyed and the
updated code opens behind the existing changed-code approval prompt.
