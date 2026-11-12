# Chat composer input and message editing

Keyboard image commits, Android clipboard Paste and the explicit + menu Paste stage images through the existing
composer upload coordinator. They insert upload placeholders at the selection, then private storage Markdown when
uploading finishes. Remove cancels a pending upload and removes its placeholder; failed staged uploads retain Retry.
No content URI is inserted as message text. Reading, privacy stripping and staging run on the IO dispatcher with the
existing 32 MiB bound. No media-library permission is needed.

The value-based Compose field retains TextFieldValue for caret, selection, IME composition, mention pills and
accessibility. `ComposerRichInput` wraps its platform input request: EditorInfo advertises `image/*`, native
InputConnection.commitContent and AndroidX/support compatibility commands use the same image handler. An IME
read-permission lease stays alive until staging completes, including cancellation before reading starts. The text
context menu supplies image Paste without coercing a URI to text. Ordinary text paste still follows the character
cap/large-text attachment policy; the explicit Paste action also handles plain text.

## Editing a sent message

Edit opens the main composer in channels and threads, with an Editing message banner, Save and Cancel. Mention
lookahead, @ insertion, image/file picking and paste remain available. Voice recording is disabled during an edit.
Stored mention tokens are decoded for editing and re-encoded into the body plus structured mentions when saved.
The original message id, channel, thread and row version go through the existing MESSAGE_EDIT durable write path.
The body update leaves existing attachment metadata intact. Text-file content editing remains a separate editor.

The reply draft's TextFieldValue and mentions are suspended while editing. Edit keystrokes never write over its stored
draft or staged text attachments. Save waits for durable enqueue; an enqueue failure leaves the edit available.
After enqueue or Cancel the reply text, selection and mentions return. Queued edits show the existing status,
Retry/Apply mine/Discard controls beside their message; a second edit is blocked until that write is resolved.

Edit-owned uploads are cancelled before restoring the reply, switching edited messages, or leaving the target.
A reply upload completing during an edit updates the suspended reply. Late callbacks cannot restore an old edit
into a newer session. Account/workspace ownership and current read-only/access checks also run at enqueue.
Unsaved message edits are screen-local and are cancelled on navigation; queued edits survive through the durable
write queue. There is no backend or schema change.

## Regression checks

`ImagePasteInlineFlowTest` uses the actual composer and a separate-UID test keyboard, with synthetic Tower/storage.
Install a source app such as 2clip, share a synthetic image into it, install the debug app and test APK, and select
`studio.otherstuff.ambulando.android.test/studio.otherstuff.ambulando.app.integration.ComposerProbeIme` using `adb shell ime`.
After reinstalling the test APK, select another keyboard and then reselect the probe so Android rebinds the service.
Use a dedicated emulator only. The IME and its broadcast receiver exist solely in the test APK.

The tests cover MIME advertisement, native and compatibility commits, input-connection and floating-toolbar Paste,
explicit image/text Paste, channel/thread edit/tag/save/cancel, selection/draft restoration, queued offline edits and
upload cancellation across message switches. `ComposerUploadIsolationTest` forces late IO and cancellation before
read start to check permission release, draft isolation and staged-file cleanup. `ParityFlowTest` keeps the scrolling
regression for an edit whose original message leaves the viewport. `TextAttachmentFlowTest` exercises the existing
large-paste/file-content workflow.

These fixture tests prove Android delivery and canonical mutation construction, not live authenticated Tower acceptance
or a particular third-party keyboard's UI. Release upgrades must separately verify the stable certificate and retained
application data.
