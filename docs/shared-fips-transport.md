# Native shared FIPS consumer

Ambulando consumes the existing Nostr Browser Android system VPN. It does not copy
its JNI, run a node, create a machine key, start the VPN, or bind the global process.
The provider owns consent and runtime identity. The consumer owns NIP-98 signing,
workspace proof verification and application permissions.

## Provider trust and consent

The v1 protocol is `ContentResolver.call` against the exact enrolled package's
`content://<package>.fips.shared` authority. The `fips_shared_providers` resource
independently pins two production identities:

| Package | Current certificate SHA-256 |
| --- | --- |
| `com.wingmanbefree.wingman_app` | `6c0da09b9e2375d657645f197419be7b8227a7434e0225512fc760cedf383c8c` |
| `com.wingmanbefree.wingman_app.nightly` | `5edec981796544b29118b53d424ed5067b54cbb0b871d5c37b1cc9ccbc807be0` |

These public fingerprints are explicitly enrolled by the release owner. Package,
authority and exactly one current signer must all match the same identity; the
certificates are not interchangeable. Package names alone, signer history and
debug certificates are not accepted. The provider reciprocally enrolls the
consumer's actual package/current signer. Both directions must match the signed
release; changing enrollment requires review.

One installed trusted provider is selected unambiguously. If both trusted
providers are installed, the connection fails with instructions to keep only the
intended provider installed, restart Ambulando and verify the connection again.
There is no provider chooser or settings preference. The adapter never silently
replaces a selected provider. Each transport instance binds permanently to one
provider; a changed/ambiguous/missing roster invalidates it, closes existing I/O
and fences further calls, including calls through old API clients. Selected-package
change broadcasts also revoke immediately, even for a same-certificate upgrade.
The user must restart and repeat target proof and namespace consent for a new
connection; existing clients cannot acquire the replacement provider. Missing,
denied or mismatched trust fails without another transport.

`request` carries version 1, exact explicit-port endpoint, peerNpub, purpose
`nstack`, and a consumer-owned Messenger. `consent_required` returns an opaque
requestId and provider-created immutable PendingIntent. The adapter retains that
original capability only in process, bound to the exact provider URI, package,
current certificate, creator UID, caller UID, endpoint and request ID. A background
validation attempt never launches it. A later explicit workspace confirmation can
recover the original intent even when `check` returns only `pending`. Before the
single UI handoff, provider trust and creator ownership are revalidated; account,
revision and current QR input/mode fences must still hold. The screen must be
RESUMED immediately before taking and launching consent. A reply received after
leaving the screen retains the unclaimed capability for the next explicit tap;
returning to the screen never launches approval automatically.

The preview remains while the native screen asks for approval. After returning,
confirm again: `check` validates that exact pending request; an activity result is
not grant authority. A used/canceled intent is never handed off again. If its
request remains pending, the next confirmation discards/releases it and asks for
another explicit confirmation for fresh consent. Denial, revocation, readiness,
endpoint/provider/account changes and close discard cached consent. Late replies
after release cannot repopulate it. There is no automatic UI launch, request retry,
mutation replay, or reopening of a closed grant. Pending/back/deny never causes a
connection.

Android 31+ independently checks PendingIntent.isImmutable before caching and
again before UI handoff. Android 30 has no public immutability query: support
relies on the exact enrolled current release certificate, creator package/UID and
the audited provider contract, which always creates the intent with
FLAG_IMMUTABLE | FLAG_ONE_SHOT. No reflection or fill-in intent is used. A provider
implementation or enrollment change requires re-auditing this API30 contract.
Shared readiness needs Android 11/API 30 or later; Android 10
retains HTTPS and fails FIPS explicitly. No private keys or access grants are read from the provider.

## Grant and network boundary

A ready grant includes requestId/grant, exact endpoint/peer/purpose, peerIpv6 and
16 raw bytes, networkHandle, opaque generation, Unix-second and monotonic-ms
expiry, and provider lifecycle Binder. Fields are checked initially and on each
renewal; endpoint, peer, network, generation and Binder cannot change in place.
Renewal occurs before every operation, DNS lookup and new socket and every ten
seconds while alive. A separate main-handler monotonic deadline closes I/O even
if a provider call stalls. Close uses an atomic flag and never waits on the IPC
renewal lock. Binder death, Messenger revocation, VPN captured network loss/capability
change, identity lock, disconnect and client close revoke local I/O.

All DNS uses the captured Network.getAllByName, all sockets its socketFactory.
Every address must equal `0xfd || SHA256(raw x-only peer public key)[0:15]`, from
FIPS 0.5 revision `80f8f965aa872296edbce84ade9949ece2596602` identity/node_addr.rs
and identity/address.rs. Empty, public, mixed and different-peer answers fail.
Socket connect addresses and ports are checked as well. No proxy, redirects,
connection-failure retry, public DNS, ordinary socket factory or global network
binding is used. The normal core HTTP/SSE adapters refuse FIPS unless explicitly
invoked with the scoped adapter's client, including in background-only runtimes.

Protected hosts are classified with OkHttp HttpUrl, the sending parser, using
lowercase plus trailing-dot removal only for suffix detection. Any protected
alias (case, percent encoding, trailing dot or IDNA normalization) must then pass
canonical peer spelling before delegation. Default HTTP/SSE, presigned storage,
background transfers, browser downloads and avatar downloads refuse these hosts;
only canonical HTTP endpoints may enter the shared grant adapter.

HTTP and SSE share an endpoint-scoped lease and pool. Revocation closes captured
sockets, responses and streams and evicts that pool. Files use Tower's existing
signed storage routes over the same peer, avoiding external presigned URLs;
uploads are limited to the existing 8 MB signed fallback. Out-of-process transfers
and background-only refresh cannot acquire consent or bypass the grant. Native
WebSocket/WApp-browser FIPS is not implemented by this adapter.

Readiness is not backend identity proof. The fresh signed kind-30384 nonce proof
and exact authorized workspace/app checks still precede remembering a connection.

## Reuse and validation

`FipsPeerEndpoint`, `FipsGrantBinding` and `FipsProviderPin` in core are reusable
pure Kotlin policy units. `app/fips/SharedFipsTransport` implements the Android
provider, consent and lifecycle adapter for HTTP/SSE. Consumer apps can reuse this
contract with their own independently enrolled certificate and identity signer.
The browser continues to use the existing universal window.fipsTransport bridge.

Core tests cover pinned address vectors, DNS rejection, immutable grant binding,
expiry and exact package/current-certificate matching. Robolectric tests cover
missing/untrusted provider denial, default HTTP/SSE bypass prevention, background-first
pending consent recovery, single handoff, clearing/late-reply fences, API30
contract/creator checks, and API31 mutable-intent rejection. Native
QR tests cover signed malformed payloads, proof mismatches, denial and own-user
exact-URL authentication. These are source checks, not live cross-app acceptance.

Release requires the final durable-signed consumer/provider versions, reciprocal
production enrollment, real device consent/HTTP/SSE/file and cancellation checks,
upgrade preservation, and the release custodian’s authorized signing and publication. Debug APKs cannot
satisfy production certificate enrollment and are not delivery.

References: the NBrowser checkout’s `docs/android-shared-fips.md`, [Android scoped Network APIs](https://developer.android.com/reference/android/net/Network),
and [owner UID privacy restrictions](https://developer.android.com/reference/android/net/NetworkCapabilities#getOwnerUid()).
