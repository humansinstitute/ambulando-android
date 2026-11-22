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
