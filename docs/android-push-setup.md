# Android push setup (UnifiedPush)

Android push requires both a Tower release that implements `unifiedpush` and a configured UnifiedPush distributor on
the phone. An installed APK or a saved registration alone does not establish live delivery. The existing inbox still
works without push. This implementation uses the official UnifiedPush connector 3.3.5; it includes no FCM SDK or Google
Play Services. Its local Web Push decryption uses the connector's open-source cryptographic library.

## Server operator

Use the Tower notification push contract shipped with the matching server release. Keep the existing inbox projector
and alert worker enabled. APNs settings do not configure Android delivery.

Configure the following in the **Tower service's protected deployment environment**, not in the Android source, an APK,
a chat, a task comment, or a public repository:

| Setting | Value / purpose |
| --- | --- |
| `UNIFIEDPUSH_ALLOWED_ORIGINS` | Comma-separated exact HTTPS origins of approved push servers, e.g. `https://ntfy.sh` or `https://push.example.org`. No endpoint paths, wildcards, credentials or private hosts. |
| `UNIFIEDPUSH_VAPID_PUBLIC_KEY` | The public P-256 VAPID key advertised to the phone. |
| `UNIFIEDPUSH_VAPID_PRIVATE_KEY` | Matching private VAPID key, available only to the Tower service. |
| `UNIFIEDPUSH_VAPID_SUBJECT` | Operator contact, e.g. `mailto:push-operator@example.org`. |
| `NOTIF_PUBLIC_ORIGIN` | Public HTTPS Tower origin used in hints; it must match the phone's stored Tower connection. |
| `NOTIF_ALERTS` | Must not be `off`. |

An authorized operator must generate a dedicated VAPID pair using the server's existing Web Push tooling in a protected environment; this implementation does not generate or install credentials.
Install it through your deployment secret facility. Do not reuse or export Nostr identity keys, APNs signing material,
or browser Web Push credentials. The public VAPID key is safe to advertise; the private key is not. No Firebase project,
Google account, cloud purchase, or new external account is required by Ambulando itself.

Deploy the matching Tower code/schema using its release workflow, with the manager coordinating activation. Missing
configuration must report not ready. Confirm authenticated `GET /api/v4/flightdeck-pg/workspaces/<workspace>/descriptor`
contains `notifications.transport_status.unifiedpush.ready=true` and a `vapid_public_key`. This confirms provider
configuration, not that a particular phone can receive a notification. Do not post the full registration response or
endpoint in troubleshooting records.

Tower must allow only configured public HTTPS endpoint origins. Registration and every send validate destination
addresses; redirects and private/link-local/loopback destinations are rejected. An allowlist entry does not authorize
an internal server. Self-hosted distributors therefore need a publicly resolvable, publicly routable HTTPS origin with
a valid certificate. A local LAN HTTP ntfy instance is deliberately unsupported by this contract.

## Phone

1. Install a Google-free UnifiedPush distributor. For ntfy, use its F-Droid build or the upstream Google-free APK;
   see [ntfy Android installation](https://docs.ntfy.sh/subscribe/phone/). Open and configure it before Ambulando setup.
2. Choose the push server in the distributor. Public `https://ntfy.sh` avoids operating a server; it remains an external
   service with its own availability and policies. The Tower operator must explicitly allow that origin. For a server
   you operate, configure the distributor with your public HTTPS server and allow that origin in Tower instead.
3. Open Ambulando, unlock, connect to the trusted Tower, and select the workspace. Your identity must already be a member.
4. Open **Settings → Notifications → Set up push**. On Android 13 or later, allow notifications. The UnifiedPush system
   picker may ask you to choose your distributor. If none is available, install/configure it and choose **Retry setup**.
5. Read the status. A server or distributor error means setup is incomplete. Android permissions alone do not enable
   server delivery. Each workspace is separately enrolled on this phone; repeat setup for each workspace you want.
6. For alerts while the identity is locked or Android has closed the app, turn on **Settings → Background → Refresh
   while locked**. This is the existing optional GET-only credential for the connected Tower/workspaces. It cannot
   register devices, change read state, send messages, or unlock the vault. Without it, push fails closed while locked
   and the inbox catches up when you next open/unlock the app. **Lock now revokes it**; enable it again after unlocking
   if you want locked alerts. Add the background read permission again after enrolling additional workspaces/Towers.
7. Under **Android notification settings**, check Ambulando's **Workspace activity** channel. Android can block the
   entire app or this channel independently. Denial never blocks access to the inbox; retry permission or change it in
   system settings. Do Not Disturb may silence an otherwise delivered notification.

No battery exemption is requested by Ambulando. A distributor may need its own persistent connection/foreground
notification; follow that distributor's documented setup. Android can delay event-triggered follow-up work during
Doze or restrict force-stopped apps. Reopen Ambulando and the distributor after force stop or restrictive battery
settings. Timely delivery is not guaranteed by either a saved registration or periodic background refresh.

## Self-hosted ntfy option

Prepare a publicly reachable HTTPS ntfy deployment separately. Configure its external `base-url`, TLS/reverse proxy,
and persistent storage using [ntfy's operator guide](https://docs.ntfy.sh/config/). Use the guide's UnifiedPush access
control example if the instance otherwise denies anonymous topics: the distributor's randomly generated UnifiedPush
endpoint must accept server publishing. Keep administrative APIs protected. Android's ntfy distributor must connect
to that deployment. Ambulando/Tower uses the endpoint returned by the distributor; do not paste an arbitrary topic
URL into Ambulando or put an ntfy administrative password in Tower's transport body. Endpoint URLs are bearer
capabilities and must remain private even though payload contents are encrypted.

## Registration and ongoing behavior

The phone generates a random installation ID for each saved connection/identity/workspace. It signs the canonical
installation PUT with `platform=android`, `client_app_id=ambulando`, current app version and permission state, then
PUTs `transports/unifiedpush` with the distributor endpoint and RFC8291 `keys.p256dh`/`keys.auth`, `preview=generic`,
and a seven-day lease. Endpoint/key changes are saved locally and registered after unlock; a locked phone cannot
perform those signed writes. Foreground/unlock renews within a day of lease expiry. Open and unlock at least weekly;
a lease can expire while the app is unused. The status reports renewal failures rather than treating old setup as live.

Only decrypted pushes for a locally enabled instance are accepted. A push triggers bounded one-off WorkManager work
with retry for temporary read failures; this is event-driven push follow-up, **not polling masquerading as push**.
The phone validates IDs and matches the exact stored Tower origin/service identity/workspace/actor, then fetches the
current inbox item using its own permitted credential. Removed, expired, inaccessible, dismissed, archived, read or
stale items do not produce OS notifications. The foreground app refreshes the inbox and suppresses a duplicate OS
alert. Item sequence numbers deduplicate provider retries; a later generation replaces that item's system alert.

Notifications show purpose-only text, never server-provided names, titles, message text or arbitrary links. A tap is
matched to the locally accepted alert, waits for unlock if needed, re-fetches the item and opens its supported native
thread/channel/task/document destination. Unavailable/producer items fall back to the inbox. A tap does not itself mark
the notification read; existing subject-view and inbox actions retain control of read state. No payload URL is followed.

**Turn off** disables this workspace immediately on this device, removes the distributor registration, cancels pending
local delivery work/notifications and attempts signed Tower revocation. Disconnect and identity removal do the same.
Offline revocation remains pending for the same identity while available; a deleted account cannot sign a later
revocation, but local rejection remains immediate and the server lease expires. Another phone is unaffected.

## Verify delivery before calling setup complete

With two existing authorized identities, have the other member create a real mention/task event covered by the
recipient's alert rules. Put the recipient app in the background. Verify the event appears in Tower's inbox, a provider
attempt succeeds, an OS notification appears, and tapping it opens the correct workspace/record after any unlock.
Repeat with the screen locked and a cold app using the optional background credential. Check denied permission,
channel-off, read-before-send, revoked access, duplicate delivery and local turn-off. Preserve existing APNs delivery
when validating the server update. Never use a manual local notification or a provider-only ping as proof of this full
path. Keep unit/simulated checks separate from real member/device evidence and report the exact APK version/build.
