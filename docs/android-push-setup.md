# Android push setup (Firebase Cloud Messaging)

Ambulando uses Firebase Cloud Messaging (FCM) on standard Android phones with Google Play services. Install
Ambulando and grant notification permission; no distributor or second phone app is required. Push requires a
matching Firebase Android project configuration and a configured Tower server. A successful APK build or saved
registration is not evidence of live delivery. The inbox continues to work without push.

## Required public Android configuration

An authorized Firebase project operator must download the actual `google-services.json` for Android package
`studio.otherstuff.ambulando.android` from the existing Firebase project and supply it as
`app/google-services.json` before building. This file is Git-ignored. The Google services Gradle plugin checks the
package and generates the SDK resources. Do not invent project IDs, sender IDs, API keys or app IDs.

The required values are `project_info.project_id`, `project_info.project_number` (FCM sender ID),
`client[].client_info.mobilesdk_app_id`, `client[].client_info.android_client_info.package_name`, and
`client[].api_key[].current_key` for the matching Android client. These are public Android client identifiers,
not server signing credentials. Configure any API-key Android restrictions for this package and its actual signing
certificate. Keep the established APK certificate when building an upgrade.

Without this file the app still builds, but settings report **no Firebase Android configuration; phone push is
unavailable**. Missing/outdated Google Play services also report unavailable. Installing a configuration file after
an APK was built does not configure that APK: rebuild and install the signed candidate. No Firebase Analytics
SDK is included. Messaging auto-initialization, delivery metrics export and notification delegation are disabled;
the token is requested explicitly when the person enables a workspace.

## Tower operator

Enable the matching server's canonical `fcm` transport using its own FCM setup contract. Configure the actual
project ID, numeric sender ID and Android package, plus dedicated service-account HTTP v1 OAuth signing credentials
through the protected server deployment configuration. Never place service-account private keys, OAuth access
tokens or Nostr signing material in Android config, source, task comments or APKs. Firebase Cloud Messaging API
access and permissions must already be authorized for the configured project; do not use legacy server keys.

Keep the existing notification projector, rules and alert worker enabled, and set the public notification origin
according to the Tower contract. APNs, browser push and UnifiedPush settings do not configure FCM.
Authenticated `GET /api/v4/flightdeck-pg/workspaces/<workspace>/descriptor` must advertise
`notifications.transport_status.fcm` with `ready=true`, `project_id`, `sender_id`, and `package_name`
matching the compiled Android client. This confirms server configuration, not actual phone delivery.

## On the phone

1. Install the configured signed Ambulando build and unlock your existing identity.
2. Connect to the trusted Tower and select a workspace where you are already a member.
3. Open **Settings → Notifications → Set up push** and allow Android notifications. Repeat for each workspace.
4. Check the status. Missing Firebase configuration, missing Google services, server/project mismatch or registration
   failure means setup is incomplete. Android permission alone does not establish server delivery.
5. In **Android notification settings**, allow Ambulando and its **Workspace activity** channel. Android can block
   the app and the channel separately. Denial does not block inbox access. Do Not Disturb may silence alerts.

Generic alerts can be shown by FCM/Android while the app is backgrounded, cold or its identity is locked. They do
not need **Refresh while locked** or any background GET credential. Receiving an alert never unlocks the identity
or fetches private content. Tapping waits for unlock, rechecks the stored trusted connection and fetches the
current item before routing. An alert or tap itself never changes read state; existing subject/inbox actions do.

Force-stopping the app, missing Google services, network restrictions, Android power settings and provider delays
can prevent or delay delivery. Reopen after force stop. No battery exemption is requested and no guaranteed
arrival time is promised.

## Wire contract and isolation

A random installation ID is shared only among this device's workspace enrollments for the same stored Tower
origin and signer. Separate local workspace rows retain independent opt-in and trusted routing. The signed
installation PUT sends `client_app_id=ambulando`, `platform=android`, app version and current permission state.
The signed `PUT .../notifications/installations/<installation>/transports/fcm` sends:

```json
{
  "device_token": "<actual SDK token>",
  "project_id": "<actual project ID>",
  "sender_id": "<actual numeric sender ID>",
  "package_name": "studio.otherstuff.ambulando.android",
  "preview": "generic",
  "lease_seconds": 604800
}
```

FCM uses normal HTTP v1 notification+data: purpose-only fixed text plus `data.fd`, a JSON string with `v:1`,
`installation_id`, `origin`, `tower_service_npub`, `workspace_id`, `notification_id` and `change_seq`.
The Android notification uses `channel_id=workspace_activity_v1`,
`click_action=studio.otherstuff.ambulando.PUSH_TAP`, and a stable tag scoped to installation/workspace/item.
No private message, title, author name or arbitrary navigation URL is sent to Google. The generic hint identifiers
and delivery token are still visible to the delivery provider; this is not end-to-end encrypted push.

In the foreground, FCM callbacks defer presentation to the existing live inbox instead of duplicating an OS alert.
Background notification+data is displayed automatically by the SDK/OS; it does not run the app's content fetch.
Repeated sends for the same item replace its existing OS notification rather than adding another card.
A tap's payload is only an untrusted selector: a locally enabled enrollment, matching origin/service/workspace/
identity/app and a fresh authenticated item read are required. No payload URL is followed.

Token changes are registered after unlock; receiving a token callback cannot restore a signing session. Foreground
reconciliation renews within a day of seven-day lease expiry. Open/unlock at least weekly. Status reports expiry or
renewal failure. Android permission/channel changes are reconciled on return to the app.

**Turn off** immediately rejects local delivery callbacks/taps and cancels displayed alerts for that workspace,
then removes only its signed server enrollment. Other workspace enrollments remain enabled. Disconnect does the
same for that Tower. Offline server revocation stays pending while the original identity/connection remains.
When no workspace is enabled, the Firebase token is deleted. Identity removal additionally records a durable token
reset before deletion; if offline, the next identity cannot register using the old token until reset succeeds.
Another actor's active token is never taken over through a server registration.

An already handed-off generic OS alert cannot always be recalled: after offline opt-out/account removal, an
in-flight notification may still appear. The local tap check rejects it. Provider TTL/registration leases and signed
server revocation bound further delivery; the UI must not claim immediate recall of provider-queued alerts.

## Verify before releasing as working push

Use a configured release build and two existing authorized members. Have the sender create a real event covered
by the recipient's notification rules. Verify Tower inbox projection, successful FCM send, an OS alert on the
recipient and a trusted authenticated destination after tapping/unlocking. Repeat while backgrounded, cold,
screen-locked and identity-locked **without a background read credential**. Verify permission denial/channel-off,
read-before-send, revoked membership/access, duplicate sends, token refresh, workspace opt-out, two-workspace
independence, disconnect and identity replacement. Preserve server APNs/browser behavior.

Unit tests, mocked provider responses, local notifications and onboarding emulator checks are separate evidence;
none establishes live event-to-phone delivery. Report the exact version/build and remaining configuration gates.
Do not publish to Zapstore as working push until real delivery and tap behavior are verified.

References: [Firebase Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started),
[receiving notification and data messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[FirebaseMessaging SDK](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessaging).
