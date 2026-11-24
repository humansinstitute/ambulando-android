# Android chat keyboard layout

Chat can be opened in its own section or as a thread/channel route above Deck and other sections. The section label
is navigation provenance; it does not identify whether the visible content is a conversation.

`ChatComposerLayout` owns the conversation's IME padding. When the keyboard opens on a conversation, the shell must
hide its bottom section bar for both the Chat section and `WorkRoute.Thread` / `WorkRoute.Channel` routes. Reserving
that bar outside the IME-padded conversation leaves a blank strip above the keyboard equal to the bar's height.
Keyboard dismissal restores the section bar. Do not remove the chat inset to compensate for the shell reservation.

Deck's New thread (+) is a third conversation host: an overlay on Deck with no `WorkRoute` of its own. `DeckNewThread`
declares itself with `ConversationKeyboardOwner()`, and the shell then hides its section bar while the keyboard is up
(only on the Deck section), exactly as for thread/channel routes. When the entry context already names a channel (or
only one is eligible) the screen shows it as `in #channel` and offers no selector; the `To #channel` picker
(`deck.compose.channel`) appears only when no channel is known, e.g. the global Deck with several channels.

The empty/short composer starts with a 48 dp field and two adjacent 48 dp attachment/mention targets. Its outer row
adds 8 dp above and below. Text can grow to the existing multiline limit; controls remain available alongside it.

`KeyboardSpaceTest` exercises the full sample-data Deck → thread, Deck (+) → New thread (known channel and global
destination) and Chat flows against the real Android keyboard.
It compares the composer's window-coordinate bottom edge with the platform IME top, checks the compact idle size,
multiline growth, dismissal/reopening, navigation restoration, mentions, attachment menu and sending. Run on a
disposable emulator in both gesture and three-button navigation modes. The supported `-PisolatedArtefactTest=true`
variant keeps test data separate from an installed release.

```sh
./gradlew -PisolatedArtefactTest=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class studio.otherstuff.ambulando.app.KeyboardSpaceTest \
  studio.otherstuff.ambulando.android.artefacttest.test/androidx.test.runner.AndroidJUnitRunner
```

The test saves screenshots in the emulator's Download folder and reports geometry as `KEYBOARD_SPACE` logcat lines.
Copy run-specific evidence into verified ignored handoff storage. `ComposerMenuFlowTest` additionally covers the
Android document picker, recording, draft retention and new-thread controls. Grant the isolated test app both
`RECORD_AUDIO` and (on Android 13+) `POST_NOTIFICATIONS` before those recording tests; otherwise they wait at
the permission dialog instead of reaching recording.
