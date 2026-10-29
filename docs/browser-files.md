# Browser file flows

The browser keeps its own WebView profile, separate from Tower credentials, editors and artefacts. The native download
message bridge accepts only the committed HTTPS top frame. Standard WebView file inputs use Android's explicit picker:
only selected document-provider content URIs return through Chromium's file chooser callback. Android does not expose
an input frame origin in that callback; normal Chromium file-input permission handling applies, including embedded
inputs. File input selection grants no NIP-07 permission or native local-file navigation. Cancellation returns null exactly once; navigation, tab close, browser exit,
identity lock and switching tabs cancel pending requests. File and content navigation stay disabled.

## Supported paths

- Standard `input type=file`, including multiple selection and MIME accept filters, opens Android DocumentsUI.
- WebView download responses (including attachment disposition and non-displayable types) open a create-document picker.
- Trusted clicks on HTML download links are saved without replacing the page.
- Trusted clicks on blob/data links read bytes in the page and offer the native save picker. Top-frame HTTPS origin
  checks remain native; no local file path, signing identity or native network API is exposed to JavaScript.
- Gesture-created new windows open isolated tabs and still pass the existing navigation policy.
- Native network downloads require HTTPS without embedded credentials and at most five redirects. Every destination
  reads only its own browser-profile cookies. No Tower signature, app identity or Authorization header is forwarded.
- All downloads have a 16 MB bound, finite connect/read timeouts, a native cancellation path before transfer,
  and a visible saved/error message. The native picker handles naming collisions and overwrite confirmation.

The parity reference is Nostr Browser's standard Android document picker and native download/save flows. Ambulando uses
the same platform concepts while retaining its stricter HTTPS-only navigation and isolated browser profile.

## Limits

Downloads show byte progress (a percentage when the length is known) and a Cancel button that disconnects the active
network transfer and removes the unfinished document. Leaving the browser or changing documents cancels pending work.
Programmatic blob downloads without a trusted anchor click, blob/data navigation in a popup, directory picking,
File System Access API (`showOpenFilePicker` / `showSaveFilePicker`) and camera capture are not offered. File inputs
are the supported fallback. Cross-origin frames cannot use the native file bridge. Native downloads cannot reconstruct
POST bodies or page-only bearer headers; sites should supply HTTPS download URLs or page-created blob links.
Camera, microphone, geolocation, local file navigation and FIPS permissions remain unchanged.

## Validation

Run `BrowserFilesTest` on a dedicated emulator for real Chromium file reads, native picker cancellation, and a data-file
save through DocumentsUI. The opt-in `liveBrowserDownloads=true` instrumentation argument additionally verifies a live HTTPS save, visible HTTP 404 failure and cancellation during transfer. `BatchInteractionTest` exercises full-area composer focus, toolbar geometry and pin drag/move
persistence against the synthetic Tower. `BrowserNostrTest` verifies approved signing and locked-state rejection in a
real WebView. Synthetic results do not certify a real account, remote Tower or a disconnected physical phone.
