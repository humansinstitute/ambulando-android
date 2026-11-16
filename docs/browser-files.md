# Browser files and page capabilities

The browser uses its own WebView profile, separate from Tower credentials, editors and artefacts. Native page messages
are accepted only from the selected committed HTTPS top frame. File input selection grants no Nostr permission.
File/content navigation and mixed content stay disabled; TLS failures are never bypassed.

## Downloads

HTTPS GET downloads stream directly to the Android create-document destination, with a **1 GiB** bound, finite
connect/read timeouts, byte progress, cancellation and a visible saved/error result. Cancellation/error removes the
unfinished document where its provider permits deletion. The picker handles naming collisions and overwrite consent.
Each redirect is separately checked for HTTPS and receives only the destination's browser-profile cookies; no Tower
signature, identity header, original Cookie header or page bearer token is forwarded. At most five redirects are followed.
Native GET cannot reconstruct an attachment response's original POST body or arbitrary page Authorization header.

Blob/data download links and detached programmatic `anchor.click()` exports stream from the page in acknowledged
32 KiB chunks to a private temporary file, with a **256 MiB** limit. The native save picker then asks for a destination.
The spool is deleted after save, cancellation, navigation, tab switch, close or lock. Chunk writes run off the UI thread.
Messages and transfers are tied to a particular tab/document; a stale page cannot finish another document's export.

For POST or bearer-dependent responses, sites can export the response they already fetched:

```js
const response = await fetch('/export', {
  method: 'POST',
  headers: { Authorization: `Bearer ${pageToken}` },
  body: formData,
});
await window.ambulandoDownload(response, 'archive.zip');
```

This explicit page API retains the request's browser context without exposing a native network proxy. Existing sites
can instead download a blob through a download anchor. Native attachment handling cannot recover a POST already consumed
by Chromium; such sites need one of these page export paths. Blob navigation in a new window and the File System Access
API are not implemented.

## Uploads

Standard file inputs use DocumentsUI, including multiple selection and MIME filters. Capture inputs requesting image,
video or audio launch the corresponding Android capture app; camera capture first asks for Android camera permission.
No installed capture handler, OS denial and cancellation return a null result. Private camera/video temporary files
remain available for Chromium to read and are removed when the requesting document is left, closed or locked.

Top-frame `webkitdirectory` inputs use an explicit read-only Android folder picker. Enumeration is limited to 1000 files
and 16 directory levels; no persistable provider access is retained. The browser returns provider URIs through Chromium
and attaches relative paths to its file objects before the input's change handlers. Ordinary iframe file inputs still
use Chromium's native chooser; the custom directory bridge is limited to the HTTPS top frame. Android's restricted
storage roots remain restricted. Provider-specific URI ordering/name behavior needs checking on each supported provider.

Picker results complete exactly once and are invalidated by navigation, tab close/switch, browser exit and identity lock.
Android does not expose an input frame's origin in the native chooser callback; selected document URIs never confer
signing rights or permission to navigate local files.

## Signer handoff

User-started top-frame `nostrconnect:` and `nostrsigner:` links ask before opening a compatible installed app. Android
`intent:` links are restricted to those two signer data schemes and reconstructed as browsable VIEW intents. Components,
selectors and non-VIEW actions are refused; flags, extras, package targeting and fallback URLs are not forwarded.
Confirmations belong to the requesting tab, document and origin and expire when that context changes. No-handler and
cancel outcomes return to the page with an explanation. Return to Ambulando preserves the originating live tab.

These are external launch flows, not a native NIP-55 activity-result-to-JavaScript bridge. NIP-46 pages own their relay
connection, timeout, account check and cancellation; clipboard-based signers own their clipboard result. Ambulando does
not read clipboard keys or import external signer results into its native identity. Native NIP-07 remains the embedded
login path and retains its existing explicit consent and origin checks.

## Dialogs, fullscreen and permissions

Alert, confirm, prompt and before-unload are explicit native dialogs labelled with the requesting HTTPS origin. Only the
selected visible document can present one. A competing request is cancelled instead of overwriting the current result.
Completion is exactly once; switching tabs, navigation, close and lock cancel pending questions. HTML dialogs continue
to render in the webpage and are distinct from these JavaScript callbacks.

Fullscreen content uses WebView's custom-view callback. Back/dismiss, navigation, tab switch, close and lock exit it and
complete its callback once. Camera/microphone/location require both a per-page origin confirmation and Android runtime
permission. Unsupported permission resources are denied. Location permits approximate Android grants. No page grants
are persisted or restored. The menu can revoke current page capabilities. Leaving/locking a granted page reloads its URL
as a fresh GET to end even page-modified media/watch code; this can discard unsaved page state. A site's own Permissions-
Policy can still forbid features. Grants never override it or the OS permission setting.

## Cold restoration

Allowed committed HTTPS GET tab URLs and the selected tab are saved locally. Cold browser launch makes fresh GETs;
POST documents, about:blank/opener contents, history bodies, pending dialogs, permission/signing requests and session
signing grants are not restored. The existing browser profile retains cookies/storage. Clearing browser data or resetting
the native identity removes the saved tabs. Restoration is local, not synced across devices.

## Validation

The browser instrumentation suite covers native picker save/cancel, renderer navigation/popup/POST/cookie isolation,
NIP-07/NIP-44 and viewport layout. Streaming, lifecycle and restoration fixtures use synthetic data and public disposable
identities. `liveGitWorkshop=true` opts into the actual public Git Workshop login modal and Extension login check;
`liveBrowserDownloads=true` opts into public HTTPS download/error/cancel checks. Run these on an isolated emulator.
Synthetic identity success does not establish access to an owner's private repository or physical-phone behavior.
