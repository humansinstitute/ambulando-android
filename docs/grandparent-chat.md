# Grandparent Chat for Android

Grandparent Chat is a separate native Android app for one existing family conversation. It shares Ambulando's Kotlin
core and Compose source, with a small product entry point rather than a copied client. Normal Ambulando commands
and its application identity are unchanged.

## Build and identities

Use the same JDK 21, Android SDK and Gradle wrapper as Ambulando:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :grandparent:testDebugUnitTest :grandparent:lintDebug :grandparent:assembleDebug --max-workers=2
```

The debug packages are `app/build/outputs/apk/debug/app-debug.apk` and
`grandparent/build/outputs/apk/debug/grandparent-debug.apk`.

| Product | Application ID | Initial version |
| --- | --- | --- |
| Ambulando | `studio.otherstuff.ambulando.android` | Existing independent version |
| Grandparent Chat | `studio.otherstuff.grandparentchat.android` | 0.1.0 (1) |

The products can be installed together. Android application sandboxes isolate identity, no-backup storage, SQLite,
attachments, transfers and caches. Grandparent Chat additionally uses a `.grandparent` Keystore alias suffix. It never
imports the other app's local account or data. Sign in separately with your own Ambulando identity and PIN.
The family module ignores sample-mode launch extras in every build and has no deep-link registration.

Grandparent Chat uses debug signing for local review. Its release build has **no configured release signer** and does
not consume Ambulando's release signing environment. Establish its own reviewed release signing/distribution workflow
before distributing updates. This feature does not publish APKs or store listings. Public source snapshots must follow
[the clean-history export procedure](releasing.md); never push private development refs to a public repository.

## Setup and everyday use

Sign in through the existing Ambulando identity and NStack connection screens. Explicitly choose a workspace, channel
and existing conversation, then confirm the conversation. Multiple conversations are shown as separate choices;
nothing automatically selects the first one. If a channel has no active conversation, start one in Ambulando and
refresh the list here. This version deliberately does not create conversations or assert global thread uniqueness.

The app remembers one versioned binding in its private catalog. The binding includes the normalized Tower origin,
signing identity, connection, application namespace, workspace, scope, channel and canonical thread. Opening the app
returns to that binding. Missing membership, a corrupt binding or an archived/deleted target never selects another
conversation. Settings offers reconnect, sign out/lock, and confirmed change-family-chat. Changing the preference
preserves cached history, drafts and outbox work. An unsent message for a previous chat stays blocked; choosing that
chat again permits deliberate retry. Signing out locks the existing identity and preserves drafts; it does not erase it.

The family screen shares the chronological chat list, sender names/avatars, stored photo rendering, privacy-stripped
photo picker, large-text support, keyboard handling, per-thread drafts and durable retrying outbox. The family variant
adds an obvious labelled Send button, keeps long drafts within three visible lines so messages remain readable with
the keyboard open, and hides thread/task/agent menus, context bars, reactions, message actions and
other app navigation. Record links and arbitrary URLs cannot open other Ambulando screens. Stored images may open
a photo overlay. No notifications or incoming intent parameters can choose a different conversation.

## Canonical data and access

There is no new message backend and no message copying. `FamilyCoordinator` obtains canonical thread metadata through
existing signed Tower reads and hydrates replies with `WorkspaceSync.refreshThread`. The shared `ConnectedChat` observes
the same local core records and sends through `AppModel.send`, `OutboxSender` and `OutboxRequest`. The canonical POST is
`/api/v4/flightdeck-pg/workspaces/:workspace/channels/:channel/messages` with the selected `thread_id` and stable
`client_request_id`. The existing instruction signature and frozen request bytes are reused on retries.

`FamilyGuardTransport` intercepts every message POST, including background retry. It verifies the exact bound origin,
application header, workspace/channel path and thread payload, rejects thread creation, and performs fresh workspace,
channel and thread reads. Archived, deleted, missing, mismatched or inaccessible targets are refused. It checks the
binding and unlocked signing identity again after those reads. Offline validation never posts; the durable outbox keeps
the request. Tower still authorizes the final POST; client checks add a restricted UX and never grant server permission.

A missing cached thread does not mount the composer until validated metadata is stored. Existing cached content can
be read offline with a clear status and durable drafts. Known access denial disables composition without erasing local
work. The existing model lifecycle handles refresh/reconnect; no composable owns a polling loop or network API call.

## Validation and limits

`FamilyBindingTest`, shared by both product builds, checks full-boundary isolation, malformed bindings, exact message
target containment, closed navigation callbacks, archived/deleted/access-removed metadata, persisted Unicode drafts,
frozen outbox recovery after reopening the store, non-destructive chat changes, offline refusal, stable retry bytes,
permission denial, and a binding changed during in-flight validation. `FamilyIdentityTest` verifies the family package,
storage/Keystore identity and disabled sample selection. `FamilyOnboardingTest` runs on a dedicated empty emulator and
checks product identity and accessible sign-in without entering an account or sample mode. `FamilyConversationTest`
uses constructor-only injected identity/transport/stream dependencies, a separate temporary production store and a
test-APK-only fake Tower. It renders the actual family/chat UI with a synthetic-test banner, exercises long drafts at
150% text size with the keyboard, restores the bound screen and draft after remount, and queues offline then retries
a message through the canonical signed outbox path and re-fetches the same reply with a separate canonical API reader. No intent or setting can enable this harness; sample launch extras
remain ignored. The screen remount is not a process-death/relaunch test.

These tests and shared canonical API/code paths provide local interoperability evidence. They are not a live two-client
message exchange. Real-account setup, live Ambulando read/write interoperability, process-restart restoration with a real account,
real photo transfer and offline send/retry on a real authorized conversation require a
coordinated account test. Never send unsolicited test messages to a family channel or install on a personal phone
without coordinated review. Android tablet captures are Android evidence; iPad validation belongs to the Swift target.
