# Browser NIP-44 v2

HTTPS top-level browser pages can call `window.nostr.nip44.encrypt(peerHex, plaintext)` and
`window.nostr.nip44.decrypt(peerHex, payload)`. The optional third argument `{version: 2}` is accepted;
explicit unsupported versions are refused. NIP-04 remains unsupported.

Each operation asks for native approval showing the requesting origin, active account and peer public key.
A remembered connection or authentication-signing grant never authorizes encryption or decryption. There
is no persistent cipher permission. Plaintext and ciphertext are not displayed in the approval sheet or logged.
The result goes only to the requesting document. Signing a resulting event is a separate approval.

The native listener checks the WebView-provided origin and main-frame flag against the tab's current HTTPS
origin. The broker rechecks the document, lock, account and permission revision around every suspension and
after crypto. Locking, closing, navigating, disconnecting or forgetting a site invalidates pending work.
IdentityService checks the expected account inside its existing vault mutex and requires the real unlocked
vault; background read credentials cannot encrypt or decrypt. No raw keys cross the browser bridge.

The shared bounded NIP-44 primitive retains existing private workspace-index encryption compatibility and uses
pinned libsecp256k1 ECDH, HKDF-SHA256, Bouncy Castle ChaCha20 and HMAC-SHA256. It authenticates before decryption,
checks padding and decodes UTF-8 strictly. Plaintext is limited to 1–65535 UTF-8 bytes, payloads to 132–87472
base64 characters. Larger messages and versions other than v2 are unsupported. Sites must validate signed events
and enforce their application ACL before asking for decryption; bridge consent does not grant service access.

Tests include the official [NIP-44 vectors](https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json)
(vendored verbatim in core test resources), valid self/peer Unicode and maximum-length messages, invalid payloads,
tampering, vault/account refusal and in-flight authorization changes. The Android `BrowserNip44Test` uses a disposable
public test identity and a local HTTPS sample page to encrypt, sign kind 10318, save in browser localStorage, reload,
read back, decrypt and reject unapproved calls. It verifies the signature independently on the native side.
The fixture uses the GRASP08 `[["g","wss://private.example/"]]` private-list shape. It is local browser persistence,
not full GitWorkshop UI parsing, a relay write or evidence about a real user's service list.
