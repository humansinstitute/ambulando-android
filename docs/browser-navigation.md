# Android browser navigation and popups

The browser uses the device's Android System WebView, its normal user agent and a persistent `browser` profile.
Editors and artefacts retain separate profiles. Updating Android System WebView or Chrome is required when the
provider lacks the existing multi-profile, document-start script or message-listener features; isolation fails closed.

HTTPS navigations stay with WebView, including server redirects and history. Browser-valid characters such as braces
and pipes in paths/queries are escaped for Java URI policy checks without decoding existing escapes or changing URL
structure. Invalid authorities, embedded credentials, backslashes, control characters, HTTP, local files and FIPS
addresses remain refused. This does not add a native networking proxy or a replacement user agent.

User-initiated `target=_blank`, `window.open` and POST form windows use `WebViewTransport`, preserving the actual
window, POST body and opener relationship. Each child has the same browser profile and its own document/signing
state. An opener-written `about:blank` document may emit no navigation or title callback; it must still be displayed
as a web page rather than the new-tab favourites screen. Such documents have no native Nostr signing origin.
Closing a selected child returns to its opener when that tab remains open. At the tab limit, popups are refused with
an explanation instead of evicting an existing tab. Automatic windows without a user gesture remain blocked.

Main-frame network, HTTP and certificate failures appear above the page. Subresource failures do not replace the
main page's status. HTTP error callbacks can precede page-start callbacks, so page-start must not erase their message.
Provisional URLs are tracked because certificate failures may precede page-start entirely. TLS failures are cancelled,
never bypassed. Reload, back, forward and new navigations clear the previous notice. Camera, microphone and location
requests remain denied, and existing file-flow and native Nostr permission checks remain in force.

## Repeatable validation

`BrowserNavigationTest` runs a real loopback TLS server with redirects, secure HttpOnly cookies, POST bodies, popup
opener communication, HTTP/subframe failures, invalid TLS and tab-limit fixtures. Its deliberately public disposable
key is in Android-test assets. Only the debug manifest trusts its test certificate, only for `localhost`; release
builds contain neither that trust configuration nor the private test key. The same certificate reached through
`127.0.0.1` must fail validation. This is a fixture trust anchor, never an SSL error-handler bypass.

Build `:app:assembleDebug :app:assembleDebugAndroidTest`, install both APKs on a dedicated emulator, then run
`androidx.test.runner.AndroidJUnitRunner` with the `BrowserNavigationTest`, `BrowserNostrTest`, `BrowserFilesTest`,
`EditorWebHostTest` and `SandboxedHtmlTest` classes. `BrowserLiveNavigationTest` is opt-in with instrumentation
argument `liveBrowserNavigation=true`; it reads public HTTPS pages and a cross-origin server redirect without signing
or account credentials. Public-site availability is separate from deterministic fixture correctness.

Device/vendor differences, third-party login providers, anti-embedded-browser restrictions, authenticated sites and
sites that require automatic popups still need field-specific reports. A passing public landing page does not verify
an authenticated workflow. No particular failing URL was supplied for the original report.
