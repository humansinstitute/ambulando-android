# Garmin recorder remote

The Vivoactive 6 is a remote control, not a microphone. Ambulando records phone audio from a Bluetooth communication headset, then sends through its existing authenticated voice upload/outbox. No Tower contract changes are required.

## Set up

1. Install Ambulando and Garmin Connect on the same phone; pair the watch in Garmin Connect. The Connect IQ Store app by itself is not the phone transport.
2. Sign into Ambulando and verify the Tower. Open **Settings → Watch recorder → Set up watch recorder**. Select the trusted account/workspace, choose the destination channel or conversation from the searchable picker, select the connected watch, and tap **Save**. No conversation needs to be open. This stores account, origin, Tower, workspace, app and channel authority locally. There are no built-in private destination identifiers. Setup survives restart. Settings shows the saved destination and watch status, with recording permission grants, reconnect, optional screen access, change and disable controls. Changing is refused while recording or sending; failed audio drafts remain pinned to their original destination. Existing saved bindings are read without conversion or deletion. The ordinary chat composer has no watch setup row. Tower checks channel write authority when sending; unavailable/read-only destinations are excluded where the server supplies that metadata, and rejected sends retain the draft for review.
3. Choose **Grant recording permissions** for microphone, nearby-device/Bluetooth and notification permissions. Connect a Bluetooth headset that supports microphone communication audio (SCO or BLE headset), not just A2DP music output.
4. Open **Ambulando Remote** on the watch and choose Start. Normally the phone posts a notification: open Ambulando and confirm headset recording. The watch stays waiting until the real recorder starts and its routed input is verified.
5. Optional on Android 13+: in Watch recorder choose **Allow watch to open recording screen**, then select the same watch in Android's companion association dialog. This authorizes a visible, opaque recording screen to open on a watch request, including over the lock screen. It starts the microphone only after the Activity is resumed with window focus and required permissions already granted. This is an Activity-launch exception, **not** an exemption from microphone while-in-use permission. If launch is blocked or permissions are missing, use the normal notification/open/confirmation path. GrapheneOS and physical-watch behavior need device validation.
6. Wait for **recording**, speak, then choose Finish. Finish stops capture and automatically uploads to the original destination. **sending** is not **sent**: sent requires the existing store's server-acknowledged message. Cancel discards only this session's draft; a sending/enqueued message cannot be cancelled remotely.

The Garmin SDK registers its Android receiver in the running app process. This version does not claim process-death wake-up or continuous background availability: reopen Ambulando if the watch cannot reach it. A process restart never reopens the microphone or automatically sends an interrupted recording. Keep the original account unlocked and workspace available; otherwise the operation fails with retained audio. Association does not unlock signing authority.

Audio focus uses permanent `AUDIOFOCUS_GAIN`, speech/voice-communication attributes and no may-duck request. Cooperative phone players pause or stop on focus loss; arbitrary players cannot be forced to obey. Ambulando never sends Play or asks to resume music. Focus is requested only after foreground promotion (Android 15+ requirement). Routing and focus are released on stop, errors, cancellation and service destruction. Bluetooth selection waits up to five seconds for communication routing, and recording is acknowledged only after active Bluetooth input verification. Route loss interrupts capture and requires review. Selection APIs are not a hardware guarantee: an unverified initial buffer can exist briefly and is never auto-sent; do not claim zero phone-microphone samples without physical evidence.

## Wire protocol v1

App UUID in `garmin/manifest.xml` and Android `GARMIN_APP_ID` is exactly `4edbc3d41f924958b53a29734ba5a821` (hyphens omitted). A Garmin Store listing ID is separate and must not replace this UUID. Garmin supports one Android companion registration for a given watch app; another companion registration can replace it.

Watch command is one dictionary: `{"v":1,"s":"<session>","c":"<session>_<operation>","op":"start|finish|cancel|status|retry"}`. Session is 1–64 ASCII letters, digits, `_` or `-`; the watch persists it across app restarts. `c` is a stable command ID derived from session plus operation. Retransmissions reuse both IDs. New recording explicitly allocates a new session. Android accepts only its bound Garmin device, exact app UUID and version, with bounded IDs and known operations.

Phone acknowledgement is `{"v":1,"s":"<session>","r":<revision>,"state":"waiting|recording|sending|sent|error|cancelled","detail":"<bounded human status>"}`. The watch ignores another session and older revisions. Transport success never updates recording/sent status. Start creates a durable session before side effects; duplicate Start cannot reopen it. Finish/Cancel before Start creates a cancelled tombstone, so a delayed Start cannot capture. Active draft identity and phase, rather than stale displayed status, govern Finish. Cancel/Retry cannot affect another session's draft. Retained tombstones do not evict silently; at 4,096 sessions new capture fails closed. Preserve damaged journals for support; corruption disables rebinding rather than erasing replay protection.

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

Host journal/rule tests cover replay, pinned destination, recovery, stale Finish, sending cancellation and corrupt authority. Android integration tests use explicitly synthetic Garmin commands/CDM association and a synthetic Tower. The missing-headset test uses real Android Activity/foreground-service behavior. The send test uses the phone microphone as an explicit test stand-in; it is not Bluetooth evidence. Existing voice integration tests cover real AAC capture, screen-off, stop, failed upload and outbox retry against the synthetic server.

Physical acceptance still requires Vivoactive 6 + Garmin Connect + GrapheneOS + the intended headset: wrist Start while phone locked and unlocked; music pause; input verified as Bluetooth; audible headset recording; Finish and one server message in the configured DM; route loss, permission denial, offline retry, watch disconnect/reconnect and phone process death. Record each device/OS and distinguish observed results from emulator simulation.

## Official references consulted

- [Garmin Android mobile SDK guide](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/) (article content at `/connect-iq/articles/core-topics/Mobile_SDK_for_Android.html`), [SDK repository](https://github.com/garmin/connectiq-android-sdk), Maven SDK 2.2.0 POM and receiver API.
- [Android background FGS and while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus), [communication device selection](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)), [MediaRecorder routing](https://developer.android.com/reference/android/media/MediaRecorder#getRoutedDevice()).
- [Companion pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), [current Activity launch security](https://developer.android.com/guide/components/activities/secure-bal), [AOSP Android 16 companion BAL allowlist](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java) (`mCheckCallerAllowlistedComponent`). AOSP source is evidence for this path, not a promise for every OS/vendor.


## Release IPC regression

The Android shrinker must preserve canonical names and all members/CREATORs of the SDK Parcelables (`IQDevice`, `IQApp`, `IQMessage`) and their nested status enums. Garmin Connect is a different process and writes these names into Parcels. Debug-only or direct `receive(Map)` tests cannot detect this failure. `GarminSdkContext` supplies the app classloader before the SDK receiver reads extras and contains malformed runtime messages before app callbacks. SDK `IQApp` normalizes UUIDs to uppercase; app matching is case-insensitive and still requires the exact recorder UUID and bound device.

`garmin-ipc-fixture` is an emulator-only app implementing Garmin's SDK Binder interface and sending actual SDK Parcelable broadcasts from another process. It deliberately uses Garmin Connect's package name; never install it on a personal phone or publish its APK. Build `:garmin-ipc-fixture:assembleDebug`, install only on a dedicated disposable emulator, and use `scripts/test-garmin-release-ipc.py` with the signed production-minified APK after preparing the documented disposable saved binding. The fixture's `verify` mode loads the installed production DEX and round-trips all three canonical CREATOR implementations. Test original build33 failure, normal signed upgrade with saved binding/account retained, repeated valid messages, wrong device/app, malformed payloads, and a process restart. Settings UI tests separately use the same external connected-watch service with a synthetic Tower; neither proves physical Garmin, GrapheneOS or headset behavior.
