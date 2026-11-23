# Workspace Report

Apps includes Workspace Report alongside Daily Metrics. The same report opens from a relative or current Tower URL:
`/flight-deck?workspaceid=<workspace UUID>&napplet=workspace-report`. A link cannot switch identity, Tower or workspace.

The launcher discovers existing report code Files in the current viewer's private Note to self conversation. One
instance opens directly; several require a choice. Only an explicitly selected setup action creates the HTML File.
Incomplete, denied or malformed discovery cannot trigger replacement setup. No report JSON database is created.

## Read-only capability

The code File declares `workspace-report-v1` with only `read`, privacy `viewer`, and a metadata binding to the current
workspace. This has its own strict parser, transport and WebView owner. Daily Metrics retains its existing parser,
linked data File, save/history behavior and explicit code-update flow. Ordinary HTML remains bridge-free.

Before consent, mounting, reading and delivering results, the host verifies current File metadata, immutable version,
exact HTML bytes/hash, private storage and current self-channel authority using signed existing File APIs. Context,
lock, access and code changes stop execution; backgrounding destroys the report frame and its aggregates. Foreground
mounting, store invalidation and periodic checks reauthorize. No aggregate is persisted on disk.

Device-local receipts bind exact source bytes, service/connection/app/viewer, code File and workspace read capability.
Counts and the set of readable channels never enter consent fingerprints. Remembered consent grants no server access.
Forget approval removes the local receipt and frame. An authorization failure invalidates the receipt.

The host adapter supplies a cryptographically random UUID fallback for opaque-origin WebViews that lack the secure-context `crypto.randomUUID` method; the shared HTML stays unchanged.

The isolated frame may send only a bounded request ID, host nonce and one of `7d`, `30d`, `90d`, `all`. It has no signer,
credentials, URL, SQL, query, write or navigation capability. Requests from subframes, wrong origins/nonces, repeated
IDs, extra fields and unknown operations are rejected. One read runs at a time with a 45-second deadline and a
10,000-request session budget. Existing HTML sandbox restrictions block network, storage and navigation.

## Authoritative snapshot

The existing workspace sync scheduler owns a fixed signed GET to
`/api/v4/flightdeck-pg/workspaces/<workspace>/workspace-report`, fixing `top_n=10`. Response identity must match the
current workspace identity before whitelisted aggregates enter the frame. The client checks schema, safe integer
bounds, UTC rolling windows, complete coverage, unique stable ranking with exactly min(10, eligible channels) rows, exact remainders and all nine current
task-state buckets. Unknown/incomplete/malformed results fail closed.

Activity messages/conversations belong to the selected window. Inventory is current at the server's `as_of`,
independent of that window. Archived task states are explicit; completed means current Done state. Server ACLs apply
before counting. The host does not hydrate messages to calculate counts and does not widen access.

A missing backend route, offline state or failure shows unavailable and clears displayed counts. A valid zero snapshot
remains distinct from unavailable. Source and synthetic tests do not establish backend activation or live counts.

## Validation

Core tests cover model sums/windows/zero/errors, identity mismatch, strict capability and envelope, consent partitioning,
private File setup/adoption/chooser discovery, immutable-byte checks and revocation before result delivery. Native
instrumentation uses only an explicitly installed synthetic report fixture: unavailable before the hook, exact known
activity/inventory thereafter, remembered consent, foreground reconstruction, malformed response suppression and zero
writes after setup. The frozen HTML is shared byte-for-byte across clients; native code does not fork its renderer.
