package studio.otherstuff.ambulando.app.browser

/** Installed only in the test APK. No signing key or real signer protocol. */
class BrowserSignerFixtureActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        val layout=android.widget.LinearLayout(this).apply{orientation=android.widget.LinearLayout.VERTICAL}
        layout.addView(android.widget.TextView(this).apply{text="Disposable signer handoff"})
        layout.addView(android.widget.Button(this).apply{text="Return to browser";setOnClickListener{finish()}})
        layout.addView(android.widget.Button(this).apply{text="Cancel signer";setOnClickListener{setResult(RESULT_CANCELED);finish()}})
        setContentView(layout)
    }
}
