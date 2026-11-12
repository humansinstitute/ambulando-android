package studio.otherstuff.ambulando.app.integration;

import android.content.*;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.TextView;

/** A separate-UID real IME, driven only by tests. Java keeps it independent of the target APK's Kotlin runtime. */
public class ComposerProbeIme extends InputMethodService {
    public static final String ACTION = "studio.otherstuff.ambulando.test.COMPOSER_IME";
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                InputConnection connection = getCurrentInputConnection();
                if (connection == null) throw new IllegalStateException("No focused InputConnection");
                String mime = String.join(",", getCurrentInputEditorInfo().contentMimeTypes == null ? new String[0] : getCurrentInputEditorInfo().contentMimeTypes);
                String op = intent.getStringExtra("op");
                boolean accepted;
                if ("text".equals(op)) accepted = connection.commitText("IME text", 1);
                else if ("context".equals(op)) accepted = connection.performContextMenuAction(android.R.id.paste);
                else {
                    ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
                    if (clip == null || clip.getItemAt(0).getUri() == null) throw new IllegalStateException("No image URI");
                    if ("legacy".equals(op)) {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable("androidx.core.view.inputmethod.InputConnectionCompat.CONTENT_URI", clip.getItemAt(0).getUri());
                        bundle.putParcelable("androidx.core.view.inputmethod.InputConnectionCompat.CONTENT_DESCRIPTION", clip.getDescription());
                        bundle.putInt("androidx.core.view.inputmethod.InputConnectionCompat.CONTENT_FLAGS", 0);
                        accepted = connection.performPrivateCommand("androidx.core.view.inputmethod.InputConnectionCompat.COMMIT_CONTENT", bundle);
                    } else accepted = connection.commitContent(new InputContentInfo(clip.getItemAt(0).getUri(), clip.getDescription(), null), InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
                }
                setResultCode(accepted ? 1 : 0);
                setResultData("mime=" + mime + "; op=" + op + "; accepted=" + accepted);
            } catch (Exception e) { setResultCode(-1); setResultData(e.toString()); }
        }
    };
    @Override public void onCreate() { super.onCreate(); registerReceiver(receiver, new IntentFilter(ACTION), RECEIVER_EXPORTED); }
    @Override public void onDestroy() { unregisterReceiver(receiver); super.onDestroy(); }
    @Override public View onCreateInputView() {
        TextView view = new TextView(this); view.setText("Synthetic composer test keyboard"); view.setPadding(12,12,12,12); return view;
    }
}
