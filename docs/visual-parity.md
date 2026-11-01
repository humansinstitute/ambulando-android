# Visual parity with the iOS app (Chat and Deck)

The Swift views are the design; Android draws the same components from the same tokens (`ui/theme/Amb.kt`, `AmbType`).
Functional tests do not show that the two look alike, so changes to Chat or Deck rows are checked on rendered screens.

## What is shared

| Swift | Android |
|---|---|
| `ConversationCard`, `MessageRow`, `ReactionBar`, replies bar, `DaySeparator` | `ui/chat/MessageCard.kt`, `ChatActions.kt` (`ChatMessageControls`, `ChatMessageReactions`) |
| ⋮ menu (React, Get it done, Branch, Copy, FD Ref, Edit, Delete) | `MessageMenu` in `ThreadActions.kt`; Edit and Delete live only in the menu |
| Composer: filled field, 30dp arrow-up send | `ConnectedChat` composer, `ui/chat/ChatComposer.kt` |
| `DeckInboxCard` rows, type toggles, Search, read menu | `ui/deck/DeckRows.kt`, `DeckCards.kt` |
| `AppLauncherCard`, Recent rows | `DeckRows.kt`, `DeckCards.kt` |
| `tabletReadingWidth` (860pt) | `readingWidth()` in `MessageCard.kt` |
| Text styles (`subheadline`, `caption`, …) | `AmbType`, in `sp`, so the user's font scale applies |

Touch targets stay 44dp or more where the Swift control is drawn smaller (reaction pills, type toggles, send).

## Tablet shell, New thread, composer, Canvas and Pinned

- **Tablet shell** (`WorkspaceShell`, `TabletSidebar`): a window of 760dp or more (the iPad's rule, read from the window
  configuration) keeps the sections list and the Home / scopes / channels content in a permanent 280dp sidebar and drops the
  bottom bar; the menu button folds the sidebar away (the bottom bar returns) instead of opening the drawer. A narrower window
  keeps the drawer and bottom bar. The sidebar is only added to or removed from a Row beside the content, so section hosts,
  stacks and drafts keep their state across a resize. Same navigation actions as the drawer; nothing is duplicated.
- **New thread** (`DeckNewThread`, `ConnectedChat(composeOnly)`): the floating 56dp "+" and the Inbox heading "+" open Deck's
  composer. The channel is preselected from the context (`DeckCompose`, ported from iOS) or chosen from the context's channels.
  The composer is the channel's own, so paste, mentions, attachments, drafts and IME are unchanged. The workspace and
  connection are those Deck showed when it opened; if either changes the composer closes. The send's request id (the outbox
  row id, Tower's `client_request_id`) is followed through `sentMessage`; the created thread opens when Tower answers, a
  failure or retry shows the outbox's own message, and a replayed request cannot duplicate. State survives rotation.
- **Composer**: the field starts two tool rows tall (80dp), text at the top, tools 44x40dp, send at the bottom.
- **Canvas**: Swift row (primary icon, title, visibility line, time), "+" with the title alert, footer note. **Pinned**:
  regular-weight title, "not on this device" fallbacks, saving spinner.

Not matched on purpose: the Android pin glyph (iOS draws a slashed pin on Pinned rows), the Hide keyboard button above Send
(Android's system back hides the keyboard), tool touch height 40dp (iOS 32pt), locale date format.

## Capturing screens

Match the logical viewport, not the pixels: dp = px / (dpi / 160). The iPad's 1620px is 810pt, so the Android window must be
810dp too (1620x2160 at **320** dpi). At 240 dpi the same pixels are 1080dp wide and every card and line of text looks about a
quarter smaller than the iPad's, which is a capture error, not a type-size difference. The phone is the emulator's own
1080x2400 at 420 dpi = 411dp (iPhone Pro widths are 393-430pt). Font scale 1.0 unless the capture is the 1.5 one. The wide
landscape capture (2560x1600 at 240 dpi = 1067dp) exercises the 860dp reading width cap. Do not change type from an image
whose density was not matched.

`app/src/androidTest/.../VisualCapture.kt` opens the synthetic sample data (never a real identity or Tower) and saves Chat and each
Deck card to `Download/ambulando-visual/<tag>-<screen>.png` on the emulator. Set the conditions from outside, then pull:

    adb shell cmd uimode night yes|no
    adb shell wm size 1620x2160; adb shell wm density 320      # iPad-sized window: 1620px / (320/160) = 810dp = the iPad's 810pt; `wm size reset; wm density reset` afterwards
    adb shell settings put system font_scale 1.5               # back to 1.0 afterwards
    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=studio.otherstuff.ambulando.app.VisualCapture \
      -Pandroid.testInstrumentationRunnerArguments.visualTag=before-phone-light
    adb pull /sdcard/Download/ambulando-visual/ <private folder>

Gradle uninstalls the debug app after the run, so the images are written to Downloads, which survives it. Keep the images
outside the repository (the private handoffs folder); compare them with the iOS captures of the same sample content.

Interaction checks for the same controls: `ChatDeckInteractionTest`.
