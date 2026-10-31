# Release procedure and public history boundary

The public repository contains reviewed source snapshots, not private development history. Never push a private
checkout's branches, tags or other refs to the public destination. Never force-push or mirror private refs.

## Validate the source

1. Preserve existing work and commit the tested tracked tree. Review the feature matrix and known limitations.
2. Run core/app tests, editor preservation tests, lint and the dedicated-emulator workflows. Separate synthetic,
   read-only host contract checks and actual authenticated member UI evidence.
3. Check source, licenses/artwork notices, public metadata and the final APK for secrets/private identifiers.
4. Increment the Android version code. Keep the application ID and signing certificate stable so upgrades retain data.

`release-build.sh` reads the APK-only PKCS12 signing store from outside Git and its password from the macOS Keychain.
It does not print credentials. A release must pass `apksigner verify --verbose --print-certs`; record its public
certificate fingerprint and APK SHA-256. Signing material, passwords and recovery details must never be committed.

## Create or update a clean export

Use a separate, verified Git-ignored staging directory under `tmp/docs/handoffs/public-export`. Export only the tracked
tree using `git archive` into that directory. Initialize its own `main` branch for the first snapshot, with the public
project name and GitHub noreply email. Review the exported tree before committing it. Do not copy `.git`, ignored files,
private refs or the development log. Do not add coauthor trailers.

Audit the export's entire history and tree, then push **only the export repository's main branch** to the public
repository using a normal push. Later snapshots are ordinary commits in that same export repository. Private
source-to-snapshot mappings belong only in private operational evidence, never in the public tree or commit message.

## Publish assets and Zapstore events

Publish the verified APK and checksum as GitHub release assets. Keep `zapstore.yaml`, including the intended public
publisher npub, at the public default-branch HEAD. Use the official [zsp tool](https://github.com/zapstore/zsp) and
[Zapstore publishing guide](https://zapstore.dev/docs/publish).

`zsp` is built from its pinned release into a Git-ignored directory (`GOBIN=<ignored dir> go install github.com/zapstore/zsp@v0.4.17`), never installed from an unverified download. `SIGN_WITH=<public-npub> zsp publish <config> --offline` emits the unsigned events and the upload manifest without any key. An externally held signer can sign the unsigned events emitted that way. Do not export a private
identity, use an owner's identity as a substitute, or place a bunker secret in the repository. The app/release/asset
events are kinds 32267/30063/3063; Blossom upload authorization uses 24242. Optional certificate linking uses 30509
and needs its own signer authority.

Publication is complete only after the relay accepts the signed events, the release can be retrieved, asset URLs work,
and the application listing resolves. A signed local APK or local event file is not publication. Keep validation and
publication evidence separate from product source.

Release builds exclude Android generated VCS metadata so private development revisions stay local.
The APK signing certificate SHA-256 is `95ae1a491e106931a25296797a6e99a9efe95a9e935609e98403afb8e91b816d`.

## Zapstore specifics learned in practice

- `zsp publish --offline` accepts only a local APK path (remote GitHub sources make network calls and are refused). Emit the
  unsigned events from the local APK, then replace the asset's `url` tag with the immutable GitHub release asset URL before
  signing; the `x` hash and `size` tags keep describing the same bytes. Verify the download hash and size before publishing.
- Sign the asset event first, then the release naming the signed asset's real id, then the app event. The release event
  should carry an `a` tag naming the app (`32267:<publisher pubkey>:<app id>`), as the first accepted release did; `zsp`
  0.4.17 does not emit it.
- The relay may answer `pending: the listing will be saved once its checks pass`. Acceptance and relay read-back are not store
  visibility: check the public listing separately and report it as it is.
- Build 18 check of what a person actually sees: the Zapstore Android client (opened once on the emulator) listed the new version under
  Latest releases and its app page showed 0.7.0 with Open and Uninstall (the package was installed), while the zapstore.dev web page,
  rendered in a headless browser, said the app was not found. The client reads the relay events; the web listing evidently does not
  follow the same path yet. Report each as observed; neither is proof of general store availability or of what the release `a` tag changes.
