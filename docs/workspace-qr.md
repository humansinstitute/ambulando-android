# Shared NStack workspace QR

Manual connection accepts copied `nstack:workspace:v3:…` text and QR images from
the Android document picker. It uses ZXing locally, with no upload. Camera capture
is not implemented. Check service previews the signed name, workspace UUID,
Tower/authority keys and advertised transports before explicit confirmation.

The consumer verifies strict canonical base64url/JSON, the exact allowed shape,
8192-byte copy limit and kind-30383 signature. Confirmation uses the existing
unlocked identity to sign a fresh nonce-bound GET to the selected workspace's
connection-proof route. The returned kind-30384 signature, nonce, effective URL,
service, UUID, authority and expiry must match. Existing service pins are preserved.
No QR action creates a workspace, registers an app or grants membership.

After proof, the existing authorized workspace/app listing and app-specific `/me`
read must agree exactly. Where several app namespaces exist for the UUID, scanning
fails with an actionable namespace requirement; the existing chooser remains
available. Existing identities, connections, ACLs and app consent stay authoritative.
Only after validation is the exact workspace enabled and selected. A previously
failed connection listing is retried after explicit verification; its warning
clears only after the listing succeeds. Other failed operations retain their warnings.

HTTPS and the enrolled shared Nostr Browser VPN provider are implemented as
explicit transport choices. FIPS uses the existing system VPN, authenticated
endpoint-scoped consent/grants, peer-bound DNS/socket binding and revocation;
it does not copy JNI, create a runtime/identity or fall back to HTTPS. See
[shared native transport](shared-fips-transport.md) for the protocol and limits.
Missing provider, enrollment, native approval or VPN readiness gives an actionable
requirement. Production cross-app acceptance and publication remain release gates.

Core tests use the browser-generated shared signed fixture, signed invalid payloads,
proof mismatches and expiry; denial tests verify own-user exact-URL GET with no app
header or mutation. Build/unit/lint results do not establish real-device acceptance.
Release requires the established application certificate, a tested upgrade and
authorized signing and publication by the release custodian. Debug APKs and source commits are not delivery.
