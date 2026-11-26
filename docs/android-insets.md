# Android configuration safe areas

Settings and workspace management replace the normal workspace shell. They must own their safe area instead of relying
on the shell's status-bar padding. `WorkspaceManagement` applies `safeDrawingPadding` outside its entire route tree,
including the workspace opening state, administration forms, scope/channel management and scope setup. Apply padding
before a scrolling container so scrolling cannot move controls back beneath a system bar.

Compose's inset padding modifiers consume the applied insets for descendants. Use `safeDrawingPadding` at the owner;
do not turn insets into ordinary `padding` values and then apply them again in a child. Safe drawing accounts for
status/navigation bars, display cutouts (including landscape sides) and the IME using their union, rather than adding
navigation-bar height to keyboard height. Device settings uses the same policy.

Full-screen `WorkSheet`, document review, text sheets and the app runtime also declare safe-area ownership. A fitted
Android dialog can already exclude the bars: in that case its reported insets are zero and these modifiers add zero.
Text sheets fit their outer container before sizing the compact sheet or centered tablet modal. Standard Material
alert dialogs keep their platform sizing. The image lightbox and browser video intentionally allow media under system
bars; image controls have their own status-bar protection. Existing document/board/file editors, artefact actions,
workspace chooser and onboarding already provide insets at their respective roots.

## Device regression

On a dedicated arm64 emulator, build the isolated package to preserve any installed release:

```sh
./gradlew -PisolatedArtefactTest=true :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2
adb -s <dedicated-device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <dedicated-device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <dedicated-device> shell am instrument -w \
  -e class studio.otherstuff.ambulando.app.SettingsInsetsTest \
  -e insetsLabel gesture \
  studio.otherstuff.ambulando.android.artefacttest.test/androidx.test.runner.AndroidJUnitRunner
```

The test uses synthetic sample data and real Android window insets. It compares screen coordinates, accounting for a
dialog window's location; `boundsInWindow` from separate windows cannot be compared directly. It checks management
sections, scope setup, opening state, profile keyboard input, device settings and the shared configuration sheet.
Top-gap limits reject a second status inset. The sheet's content must meet the IME without a second navigation gap.
Screenshots are written to the dedicated device's Download directory with the supplied run label.

Repeat with available display-cutout overlays, gesture/three-button navigation and landscape. Restore any settings
changed on a persistent test device, pull required evidence and stop task-owned disposable emulators after testing.
A synthetic device pass does not establish physical-device or authenticated live-workspace behavior.
