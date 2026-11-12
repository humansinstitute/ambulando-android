# Ambulando Voice remote

The Vivoactive 6 is a remote control, not a microphone. Ambulando records phone audio from a Bluetooth communication headset, then sends through its existing authenticated voice upload/outbox. No Tower contract changes are required.

## Set up

1. Install Ambulando and Garmin Connect on the same phone; pair the watch in Garmin Connect. The Connect IQ Store app by itself is not the phone transport.
2. Sign into Ambulando and verify the Tower. Open **Settings → Ambulando Voice → Set up watch control → Choose destination and watch**. Select the trusted account/workspace, choose the destination channel or conversation from the searchable picker, select the connected watch, and tap **Save**. No conversation needs to be open. This stores account, origin, Tower, workspace, app and channel authority locally. There are no built-in private destination identifiers. Setup survives restart. The normal screen shows saved destination, voice status and the editable default prompt. A separate numbered setup sheet contains destination/watch selection, one-time permissions and Enable; connection diagnostics remain secondary. Changing is refused while recording or sending; failed audio drafts remain pinned to their original destination. Existing saved bindings are read without conversion or deletion. The ordinary chat composer has no watch setup row. Tower checks channel write authority when sending; unavailable/read-only destinations are excluded where the server supplies that metadata, and rejected sends retain the draft for review.
3. Choose **Grant recording permissions** for microphone, nearby-device/Bluetooth and notification permissions. Connect a Bluetooth headset that supports microphone communication audio (SCO or BLE headset), not just A2DP music output.
4. Allow **Display over other apps** using the Android Settings screen linked from the setup sheet, unless the saved watch already has an actual current Android companion association. This grant lets a wrist request open the real, opaque recorder screen over the lock screen. Ambulando does not draw an overlay, dismiss keyguard or acquire microphone access from this grant. No Bluetooth scan or new association is needed.
5. Choose **Enable watch control**, read the destination-specific consent, and confirm **Enable automatic headset recording** once. Existing installations and saved bindings do not silently acquire this consent. Changes to watch, account, Tower, workspace, conversation or association invalidate consent. Disable preserves the saved binding, tombstones and retained drafts. The persistent **Watch control** notification includes **Stop watch control**.
6. Open **Ambulando Voice** on the watch and tap the large red **Record** button. A fresh wrist Record automatically opens the recorder above keyguard, may wake the display in the pocket, and starts headset-only capture after the Activity is actually resumed and focused. There is no per-recording phone tap in this enabled flow. Start first requires a nonce receipt from the wrist for that exact session/revision/Start reply; SDK delivery success and hello receipts cannot authorize capture. Missing receipt times out without opening the recorder. Watch39 transport remains compatible; the Voice watch UI adds the simple Record/Stop experience while preserving the value-based String equality fix.
7. Wait for **recording**, speak, then tap the large **Stop** button. Stop stops capture and automatically uploads to the original destination. **sending** is not **sent**: sent requires the existing store's server-acknowledged message. Once recording is verified, the screen can sleep; capture and the pinned workspace delivery continue. The small delete/discard control abandons only this session's draft; a sending/enqueued message cannot be cancelled remotely.

The phone's **Default prompt** is included as text in the same message as each new watch audio recording. It initially reads: “Please review this message, fix errors in the voice recording and respond and action as appropriate.” Edit it and choose **Save prompt**. Changes apply to future recordings; the draft pins the prompt at Record, so stopped audio and upload/outbox retries keep their original instruction. Existing retained drafts and ordinary phone voice messages preserve their original attachment-only behavior. The Android app's main identity remains Ambulando.

A user-enabled `connectedDevice` foreground service owns the existing singleton model's Garmin listener lifetime. It keeps the process out of the cached state that can defer dynamic receiver broadcasts. It owns no microphone or second network/auth session. Stop/disable stops listening and capture authority; any interrupted audio stays available for review. Grants and the exact saved consent are rechecked on every automatic Start and again at focused capture. Revocation removes readiness and invalidates consent. The old explicit phone-operated request flow remains available when automatic control is off; it is not the pocket flow.

This service is best effort, not process immortality. It may restore only previously enabled listening with valid grants/binding, never an old Start or microphone. Force-stop, reboot, system service stop, Garmin Connect availability and locked credential storage can require reopening Ambulando and enabling again. A cold service creates the same Application model without restoring the interactive signing identity. Unlock the original account in Ambulando before fresh watch recording; no implicit identity unlock is performed by the listener. A process restart preserves the binding and journal but makes pending capture terminal.

Watch39 session IDs begin with watch Unix seconds. Automatic Start must be newer than the enable and current process cutoff, no older than two minutes and no more than five seconds ahead of the phone clock. A stale or mismatched clock fails clearly and requires New recording after setup/restart; synchronize watch and phone time. Tombstones remain durable and duplicate sessions never become fresh merely by enabling again.

Audio focus uses permanent `AUDIOFOCUS_GAIN`, speech/voice-communication attributes and no may-duck request. Cooperative phone players pause or stop on focus loss; arbitrary players cannot be forced to obey. Ambulando never sends Play or asks to resume music. Focus is requested only after foreground promotion (Android 15+ requirement). Routing and focus are released on stop, errors, cancellation and service destruction. Bluetooth selection waits up to five seconds for communication routing, and recording is acknowledged only after active Bluetooth input verification. Route loss interrupts capture and requires review. Selection APIs are not a hardware guarantee: an unverified initial buffer can exist briefly and is never auto-sent; do not claim zero phone-microphone samples without physical evidence.

## Wire protocol v1

App UUID in `garmin/manifest.xml` and Android `GARMIN_APP_ID` is exactly `4edbc3d41f924958b53a29734ba5a821` (hyphens omitted). A Garmin Store listing ID is separate and must not replace this UUID. Garmin supports one Android companion registration for a given watch app; another companion registration can replace it.

Watch command is one dictionary: `{"v":1,"s":"<session>","c":"<session>_<operation>","op":"start|finish|cancel|status|retry"}`. Session is 1–64 ASCII letters, digits, `_` or `-`; the watch persists it across app restarts. `c` is a stable command ID derived from session plus operation. Retransmissions reuse both IDs. New recording explicitly allocates a new session. Android accepts only its bound Garmin device, exact app UUID and version, with bounded IDs and known operations.

Phone acknowledgement is `{"v":1,"s":"<session>","r":<revision>,"state":"waiting|recording|sending|sent|error|cancelled","detail":"<bounded human status>"}`. The watch ignores another session and older revisions. Transport success never updates recording/sent status. Start creates a durable session before side effects; duplicate Start cannot reopen it. Finish/Cancel before Start creates a cancelled tombstone, so a delayed Start cannot capture. Active draft identity and phase, rather than stale displayed status, govern Finish. Cancel/Retry cannot affect another session's draft. Retained tombstones do not evict silently; at 4,096 sessions new capture fails closed. Preserve damaged journals for support; corruption disables rebinding rather than erasing replay protection.

### Application receipts (phone/watch build 38)

The optional `b` field identifies the sender build. New watch startup sends `{"v":1,"op":"hello","b":38}`. This is a side-effect-free connection probe: Android validates the bound device/app and replies with session `hello`, revision `0`, state `hello`, phone build and a random `n` nonce. It never creates a recording or an unknown-session cancellation tombstone. The watch remains Not connected until an actual validated phone reply arrives. With phone37, Probe is safely ignored; choose Status for an existing session or Start for a new one to obtain the legacy reply.

New phone state replies also contain `b`, `n` and the last command ID `c`. After applying a valid reply, watch39 sends `{"v":1,"op":"receipt","s":"<reply-session>","r":<reply-revision>,"n":"<reply-nonce>","b":38}` for each validated reply, including bounded same-nonce resends so a lost receipt can recover. Android confirms wrist receipt only for its active binding and exact outstanding nonce/session/revision and a supported watch build. Receipts produce no reply and never clear a pending user command. Hello replies do not clear commands either; command correlation prevents an older reply clearing a newer Finish. Legacy replies without optional fields remain accepted, explicitly labelled as having no receipt support.

Phone replies use one outgoing lane per active binding. State changes coalesce behind the current exchange. New-watch replies have at most two resends with the same nonce over 21 seconds. An outstanding SDK callback blocks further sends so SDK2.2's single listener slot cannot relabel an older callback. A soft reset drains a pending callback before sending to a new snapshot; SDK shutdown clears the retired lane because it removes API callbacks. if it never arrives, the app reports the stalled lane and requires process recovery. Legacy-watch commands without build38 advance after the SDK callback, without waiting for an impossible receipt or claiming wrist confirmation. SDK success never proves recording or delivery.

The watch timer retries only a pending command, at most twice, and preserves the last valid phone detail. Status and Probe are explicit retries after a timeout. Controls → Diagnostics shows watch/phone build, RX/rejection counters, last rejection code, accepted revision, reply age and phone detail. D1–D7 identify shape, version, field type, session, revision, state and optional nonce failures. Phone Settings shows payload-free P0–P6 diagnostics and application-receipt status. Connection evidence remains separate from recorder state; idle is not a connection claim. Physical transport and pocket recording acceptance are separate checks; emulator inputs never establish physical Garmin or headset success.

The journal pins the original VoiceDestination. A deterministic UUID derived from Garmin device and session becomes the voice draft/message request ID. Existing upload-object checkpoints survive failure. A synchronous SENDING reservation covers metadata preparation; the atomic outbox producer receipt and frozen request prevent duplicate messages across retry/crash. Stopped audio stays in app-private durable storage on upload failure. Retry uploads the same draft; interrupted/unverified audio needs explicit phone review instead of watch auto-send. Outbox rejection is shown as error and recovered in the original conversation. Polling server-acknowledged local messages, not outbox disappearance, proves sent.

## Build and install the watch app

Install the official [Connect IQ SDK Manager](https://developer.garmin.com/connect-iq/sdk/), sign into your Garmin developer account yourself, accept its terms, install the SDK and **Vivoactive 6** device definition. SDK downloads alone do not include device profiles. Device metadata downloads can require authentication; `Invalid device id vivoactive6` means the profile has not been installed. No password or account export is needed by the build script.

Generate a NEW Garmin-only RSA developer key outside the source tree using Garmin's documented developer-key flow. Keep it for updates; do not use Android or Nostr signing keys. The standard OpenSSL equivalent is:

```sh
umask 077
mkdir -p "$HOME/.config/ambulando-garmin"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 | \
  openssl pkcs8 -topk8 -nocrypt -outform DER \
    -out "$HOME/.config/ambulando-garmin/developer.der"
GARMIN_SDK="<installed-sdk-directory>" \
GARMIN_DEVELOPER_KEY="$HOME/.config/ambulando-garmin/developer.der" \
  scripts/build-garmin.sh
```

Do not regenerate an existing app's key. Build produces `garmin/build/AmbulandoRemote.prg` for Vivoactive 6 and signed `AmbulandoRemote.iq` for store upload, plus checksums. Generated packages and SDK files remain ignored. A successful compiler build is required before claiming either file is installable; source alone is not a package.

For developer sideload, connect the watch to a computer with a data-capable USB cable, open the watch's Garmin storage using its supported USB/MTP access, copy the **device-specific PRG** into `GARMIN/APPS`, then safely disconnect and find Ambulando Remote in the watch app list. macOS may require an MTP client; do not copy the `.iq` store archive into APPS. Follow Garmin's [first-app instructions](https://developer.garmin.com/connect-iq/connect-iq-basics/your-first-app/) for the current device/OS details. Sideloading a PRG is separate from a Connect IQ Store installation.

For store submission, the authorized developer signs in to [Garmin's submission portal](https://developer.garmin.com/connect-iq/submit-an-app/), uploads the `.iq` bundle, adds reviewed listing text, icon, real simulator/device screenshots and privacy/support URLs, then submits for Garmin review. Use `garmin/store-listing.md` as draft copy, not a claim of submission. Account approval, review and real-device installation must be reported separately. Never publish developer keys, private history or account/workspace destinations in screenshots/listing/source exports.

## Evidence requirements

Host journal/rule tests cover replay, pinned destination, recovery, stale Finish, sending cancellation and corrupt authority. Android integration tests use explicitly synthetic Garmin SDK commands and a synthetic Tower. The pocket test drives ordinary native permission screens and binding-specific consent on a separate debug application ID; it does not fabricate companion associations or use privileged grants. The missing-headset test uses real Android Activity/foreground-service behavior. The send test uses the phone microphone as an explicit test stand-in; it is not Bluetooth evidence. Existing voice integration tests cover real AAC capture, screen-off, stop, failed upload and outbox retry against the synthetic server.

Physical acceptance still requires Vivoactive 6 + Garmin Connect + GrapheneOS + the intended headset: wrist Start while phone locked and unlocked; music pause; input verified as Bluetooth; audible headset recording; Finish and one server message in the configured DM; route loss, permission denial, offline retry, watch disconnect/reconnect and phone process death. Record each device/OS and distinguish observed results from emulator simulation.

## Official references consulted

- [Garmin Android mobile SDK guide](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/) (article content at `/connect-iq/articles/core-topics/Mobile_SDK_for_Android.html`), [SDK repository](https://github.com/garmin/connectiq-android-sdk), Maven SDK 2.2.0 POM and receiver API.
- [Android background FGS and while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus), [communication device selection](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)), [MediaRecorder routing](https://developer.android.com/reference/android/media/MediaRecorder#getRoutedDevice()).
- [Companion pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), [current Activity launch security](https://developer.android.com/guide/components/activities/secure-bal), [AOSP Android 16 companion BAL allowlist](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java) (`mCheckCallerAllowlistedComponent`). AOSP source is evidence for this path, not a promise for every OS/vendor.


## Release IPC regression

The Android shrinker must preserve canonical names and all members/CREATORs of the SDK Parcelables (`IQDevice`, `IQApp`, `IQMessage`) and their nested status enums. Garmin Connect is a different process and writes these names into Parcels. Debug-only or direct `receive(Map)` tests cannot detect this failure. `GarminSdkContext` supplies the app classloader before the SDK receiver reads extras and contains malformed runtime messages before app callbacks. SDK `IQApp` normalizes UUIDs to uppercase; app matching is case-insensitive and still requires the exact recorder UUID and bound device.

`garmin-ipc-fixture` is an emulator-only app implementing Garmin's SDK Binder interface and sending actual SDK Parcelable broadcasts from another process. It deliberately uses Garmin Connect's package name; never install it on a personal phone or publish its APK. Build `:garmin-ipc-fixture:assembleDebug`, install only on a dedicated disposable emulator, and use `scripts/test-garmin-release-ipc.py` with the signed production-minified APK after preparing the documented disposable saved binding. The fixture's `verify` mode loads the installed production DEX and round-trips all three canonical CREATOR implementations. Test original build33 failure, normal signed upgrade with saved binding/account retained, repeated valid messages, wrong device/app, malformed payloads, and a process restart. Settings UI tests separately use the same external connected-watch service with a synthetic Tower; neither proves physical Garmin, GrapheneOS or headset behavior.


## Garmin connection and saved setup

The phone waits for SDK readiness before querying devices. Paired watches from `getKnownDevices()` are identities;
SDK 2.2 can return constructor-default `UNKNOWN` status even when `getDeviceStatus()` says connected. The phone
queries that separate status and publishes new snapshots on device callbacks. A known paired watch may be saved
while offline; saving does not claim a live connection. Removed watches and unavailable/read-only destinations
produce an actionable selection error. The original account, trusted workspace and exact watch/app checks remain required.

Reconnect explicitly renews the remote app registration: SDK 2.2 can swallow service errors inside
`registerForAppEvents()`. Repeated refreshes reuse device listeners; reconnect/disconnect renews app registration.
“Phone listener ready; awaiting watch message” describes local readiness only. Actual receipt is reported separately.
After 20 seconds without SDK readiness or a watch message, Settings gives retry guidance; no timeout claims recording
or delivery. Open Garmin Connect, use **Reconnect watch** in Settings, then **Status** (or Start for a new recording)
on the installed watch app. Existing saved bindings survive temporary connection loss. No app-data reset or watch rebuild
is required for this Android connection fix.

The external-process fixture intentionally leaves known-device status `UNKNOWN` and returns connectivity through its
separate Binder `getStatus()` implementation. Host lifecycle tests cover readiness, status snapshots, reconnect,
late callbacks, timeouts, vanished selections and offline save. These simulations do not establish physical device success.


## Platform authority and native validation

The supported background launch uses direct `Context.startActivity`, not an IntentSender or PendingIntent exemption. User-granted `SYSTEM_ALERT_WINDOW` or an existing actual CDM association permits the Activity launch. Actual resumed/focused visibility then permits microphone foreground-service startup. The listener declares `FOREGROUND_SERVICE_CONNECTED_DEVICE` and type `connectedDevice`; the ordinary `BLUETOOTH_CONNECT` grant satisfies its prerequisite. Overlay access alone never starts a microphone FGS.

Android17's background audio rule applies to all apps. The microphone service promotes itself before requesting permanent speech audio focus. A real visible recorder precedes microphone service startup. GrapheneOS17 source retains the direct SAW Activity launch branch without requiring an actual overlay: [pinned controller](https://github.com/GrapheneOS/platform_frameworks_base/blob/865521d49280706d6877d0a0636de2cdd8210c36/services/core/java/com/android/server/wm/BackgroundActivityStartController.java#L1332). The separate [while-in-use service check](https://github.com/GrapheneOS/platform_frameworks_base/blob/865521d49280706d6877d0a0636de2cdd8210c36/services/core/java/com/android/server/am/ActiveServices.java#L9194) accepts a foreground/visible Activity. These sources explain the design; actual GrapheneOS/watch/headset acceptance remains a hardware test.

For a separate disposable debug installation, build `:app:assembleDebug :app:assembleDebugAndroidTest -PisolatedWatchTest=true`. This uses the `.watchtest` application ID and excludes production Firebase config. Never overwrite a production/store-upgrade baseline with debug builds. `WatchPocketFlowTest` uses the real system permission UI, real listener service and recorder lifecycle, and external SDK Parcelable/broadcast inputs labelled synthetic. It must fail truthfully without a Bluetooth microphone. The retained-audio regression uses a phone microphone as an explicitly labelled stand-in, sleeps the screen, finishes remotely and checks one message at the original destination. Neither test proves Bluetooth capture.
