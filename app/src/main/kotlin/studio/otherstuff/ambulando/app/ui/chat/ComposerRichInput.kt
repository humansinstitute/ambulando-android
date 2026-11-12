package studio.otherstuff.ambulando.app.ui.chat

import android.content.ClipboardManager
import android.net.Uri
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat

/** Keeps the value field's text/selection/composition protocol, adding Android's rich-content protocol.
 * The permission lease belongs to the asynchronous staging read, not to the IME session or focus.
 */
@Composable
internal fun ComposerRichInput(
    enabled: Boolean,
    paste: (Uri, (() -> Unit)) -> Boolean,
    pasteClipboard: () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentPaste by rememberUpdatedState(paste)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentClipboard by rememberUpdatedState(pasteClipboard)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                val original = request.createInputConnection(info)
                EditorInfoCompat.setContentMimeTypes(info, if (currentEnabled) arrayOf("image/*") else emptyArray())
                val commit = InputConnectionCompat.OnCommitContentListener { input, flags, _ ->
                    if (!currentEnabled || !input.description.hasMimeType("image/*") || input.contentUri.scheme != "content") false
                    else {
                        var leased = false
                        try {
                            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                                input.requestPermission(); leased = true
                            }
                            val release = { if (leased) { leased = false; input.releasePermission() }; Unit }
                            val accepted = currentPaste(input.contentUri, release)
                            if (!accepted) release()
                            accepted
                        } catch (error: Exception) {
                            android.util.Log.w("ComposerRichInput", "Could not acquire image read permission", error)
                            if (leased) input.releasePermission()
                            false
                        }
                    }
                }
                val rich = InputConnectionCompat.createWrapper(original, info, commit)
                object : InputConnectionWrapper(rich, false) {
                    @Suppress("DEPRECATION")
                    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean {
                        val prefix = when (action) {
                            "androidx.core.view.inputmethod.InputConnectionCompat.COMMIT_CONTENT" -> "androidx.core.view.inputmethod.InputConnectionCompat."
                            "android.support.v13.view.inputmethod.InputConnectionCompat.COMMIT_CONTENT" -> "android.support.v13.view.inputmethod.InputConnectionCompat."
                            else -> return super.performPrivateCommand(action, data)
                        }
                        if (data == null) return false
                        var receiver: android.os.ResultReceiver? = null
                        var accepted = false
                        try {
                            receiver = data.getParcelable(prefix + "CONTENT_RESULT_RECEIVER")
                            val uri = data.getParcelable<Uri>(prefix + "CONTENT_URI")
                            val description = data.getParcelable<android.content.ClipDescription>(prefix + "CONTENT_DESCRIPTION")
                            val link = data.getParcelable<Uri>(prefix + "CONTENT_LINK_URI")
                            if (uri != null && description != null) accepted = commit.onCommitContent(InputContentInfoCompat(uri, description, link), data.getInt(prefix + "CONTENT_FLAGS"), data.getBundle(prefix + "CONTENT_OPTS"))
                            return accepted
                        } catch (_: Exception) { return false }
                        finally { receiver?.send(if (accepted) 1 else 0, null) }
                    }
                    override fun performContextMenuAction(id: Int): Boolean {
                        if (id == android.R.id.paste && currentEnabled) { currentClipboard(); return true }
                        return super.performContextMenuAction(id)
                    }
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Only inspect clipboard metadata while opening a menu. Read the URI on the explicit paste action. */
internal fun Modifier.composerImagePasteMenu(clipboard: ClipboardManager, enabled: Boolean, paste: () -> Unit): Modifier =
    filterTextContextMenuComponents { component ->
        component.key != TextContextMenuKeys.PasteKey || !enabled || clipboard.primaryClipDescription?.hasMimeType("image/*") != true
    }.appendTextContextMenuComponents {
        if (enabled && clipboard.primaryClipDescription?.hasMimeType("image/*") == true) {
            item(ImagePasteKey, "Paste") { paste(); close() }
        }
    }

private object ImagePasteKey
