# Channel voice messages

Tap the **+** menu above **@** on the left of the message box, then choose the microphone **Record voice** action.
The same menu holds **Photo or file…** and **Paste attachment**. **@** stays outside the menu.
Menu selection, Back and tapping outside dismiss the popup. The idle recorder adds no separate row or empty panel.
The controls have at least 48dp touch targets and work in channel chat, new conversations and replies.
On short screens, the editor keeps its space above the keyboard and supplementary controls scroll; the section
bar returns when the Chat keyboard closes. Choose **System default** or an Android-reported microphone, then **Start recording**. Android asks for microphone and notification permissions. Recording starts
only while Ambulando is visible. A foreground microphone service owns the recorder, so rotation, navigation,
backgrounding and a locked/off screen do not transfer ownership to a composable or activity. A private recording
notification shows elapsed time and **Stop**. Return to channel chat to review, play, send or cancel the recording.
Notifications must be enabled so the recording and Stop action remain visible. A bounded partial wake lock is held
only during capture and released when capture ends. Recording stops automatically after one hour or 32 MiB.

The file is AAC in an MPEG-4 container (`.m4a`, `audio/mp4`). Stopped audio and a flushed journal live in app-private,
backup-excluded persistent storage, outside the download/cache directories. Audio is validated before Send. An
interrupted recorder can leave an unfinished M4A: playback and sending can fail because Android did not finalize
its container. Such a recording must be canceled and recorded again. The service uses `START_NOT_STICKY` and never
starts a microphone automatically after process death or boot. Android force-stop ends recording; continued capture
after force-stop is not supported. A stopped journal can be recovered on the next app launch.

## Destination and delivery

Before the permission request, the UI captures the trusted connection, pinned Tower, signer, application namespace,
workspace, channel and optional thread. Send finds that exact runtime and unlocked identity; current navigation
cannot supply or replace the destination. Account switches hide another identity's recording. Disconnect stops capture
and keeps a locally retained, destination-bound copy that cannot be sent through a replacement connection. Removing
the identity explicitly deletes local recovery copies, consistent with the existing account-removal behavior.

Send uses the existing private workspace storage prepare → upload → complete path, then attaches the object directly
to the message. Tower authorizes membership and channel association; the client never carries S3 credentials or requests
a public object. A failed upload keeps both the audio and any prepared object for explicit retry.

Once uploaded, the audio becomes a normal file chat attachment through the durable outbox. An atomic producer receipt
and stable recording/request UUID prevent an interrupted enqueue from making another message, including after sync
retired the first outbox row. Audio is copied atomically into durable outbox staging before the recorder journal is
cleared, and stays there until message acknowledgement. The voice message does not clear or replace a typed draft.
Queued message delivery and retry use the existing outbox controls. Cancel before Send deletes the journal and audio;
once Send is running, wait for its outcome. Cancel never deletes an uploaded Tower object or an existing channel File.

## Android requirements and validation

The manifest declares `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`
and `WAKE_LOCK`; the non-exported service declares `foregroundServiceType="microphone"`. Runtime microphone permission
and a visible/resumed activity are required before starting. These follow the official Android guidance on
[microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone),
[background start and while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [MediaRecorder](https://developer.android.com/media/platform/mediarecorder).

`ComposerMenuFlowTest` covers the anchored menu, visible mention control, real attachment picker entry,
keyboard layout, retained drafts, main/new/reply microphone launches and denied permission. Run the denied-permission
method separately after revoking microphone permission; grant microphone and notification permissions for the other
methods. Capture keyboard evidence on a dedicated 360×640dp emulator as well as the normal screen.

JVM tests cover retained state, interrupted/retry journals, destination mismatches, cancellation, denied microphone
permission and atomic replay-safe outbox handoff. `VoiceRecordingFlowTest` uses real Android MediaRecorder,
notification Stop, screen-off capture, activity recreation, navigation and playback, followed by synthetic Tower
offline/retry upload and duplicate-send assertions. The synthetic Tower is not evidence of live S3 or physical-phone
behavior. Run on a dedicated emulator using:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=studio.otherstuff.ambulando.app.integration.VoiceRecordingFlowTest
```

Before claiming phone readiness, verify microphone sound quality, extended screen-off capture, notification controls,
battery restrictions and authenticated private upload on the intended device/account. A local build or synthetic
upload does not establish those results.

### Rejected sends and prior-build recovery

Recordings upload as private message attachments, without first creating a channel File. The outbox retains
local audio bytes until Tower acknowledges the message; typed text stays in its original composer. New thread
shows the saved recording and **Retry saved send** after rejection. Reopen New thread in the original channel
and choose **Review saved send** to recover a pending send after navigation or relaunch.

For recordings rejected by earlier builds with `message_attachment_conflict` (HTTP 409), explicit Retry checks
that the uploaded object is still an active File in the original channel using the original identity. It then
keeps the exact signed body, destination and request ID while removing only the duplicate audio attachment
claim. The body remains a link to that existing File. This guarded recovery never uploads again or creates
another File. A missing or unreadable original File leaves the failed send intact. Other conflicts and uncertain
responses retain their original frozen request; a 409 is never considered acceptance. Old builds may already
have deleted the local audio after handoff, but the uploaded File and durable send are preserved by recovery.

## Watch message prompt

Ambulando Voice settings provide a persistent default instruction for watch audio. The watch recording captures
that text into the durable VoiceDraft at Record. Send places the pinned instruction above the audio link in the same
message. Editing the default cannot change a retained recording or retry; its object checkpoint, message request ID
and original destination remain unchanged. Older retained drafts and ordinary phone recordings keep their existing
attachment-only content. The watch's Stop sends; discard abandons the unsent recording.


## Microphone choice and live input level

The pre-record chooser lists Android-reported built-in, wired, USB and Bluetooth microphone devices and **System default**. Telephony, FM and remote-submix sources are excluded. Opening it does not capture
sound. Device callbacks refresh that list while it is open and are unregistered on dismissal. Device IDs are session
values, never saved across app restarts. A disconnected preference falls back to system routing with a warning.
Android can refuse or override a preferred device; **Requested** names the choice and **Active microphone** uses only
`MediaRecorder.routedDevice` evidence during capture. An unknown route remains explicitly unknown. Bluetooth input
availability and acceptance depend on the OS/headset; the ordinary chooser does not force a communication route.
The existing watch recorder retains its separate strict verified-Bluetooth requirement.

The level bar reads `maxAmplitude` from the same recording MediaRecorder every 150 ms, mapped across a 60 dB display
range. It starts at zero, has no decorative pulse, interpolation or animated history, and stays still in silence.
Its accessible progress semantics name the microphone input level. Android-reported client silencing produces a zero
bar and a privacy/other-app warning. The route is refreshed on routing callbacks and sample ticks; a different route
is labelled honestly while recording continues, preserving the clip. Meter ticks and routing callbacks stop and are
removed when capture stops, cancels, errors or the service is destroyed. They also detach when the recording panel is hidden or the activity pauses, and resume only for a visible resumed panel; capture continues through navigation as above.
Microphone choice is locked during recording, with an explanation to stop/review and choose for the next clip.

`VoiceInputTest` covers silence, bounded monotonic level mapping and requested-versus-actual wording.
`VoiceRecordingFlowTest` additionally covers choosing a real OS-listed built-in input, observed route, live sample
updates, rotation, callback cleanup, retained audio and a deliberately absent device ID falling back to an observed
route. The absent-ID test is a simulation, not a physical unplug test. Physical USB/Bluetooth selection, unplugging,
speech/silence response, privacy switching and live authenticated upload/transcription require intended-device review.
