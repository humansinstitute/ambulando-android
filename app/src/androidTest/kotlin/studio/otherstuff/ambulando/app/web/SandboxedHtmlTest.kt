package studio.otherstuff.ambulando.app.web

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SandboxedHtmlTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun artefactScriptRunsButCannotFetchOrSign() {
        val html = """
            <p id="x">artefact</p>
            <script>
              const nostr = typeof window.nostr;
              fetch('https://example.com/').then(() => { document.title = 'ran|reached|' + nostr; })
                .catch(() => { document.title = 'ran|blocked|' + nostr; });
            </script>
        """.trimIndent()
        lateinit var view: WebView
        instrumentation.runOnMainSync { view = createSandboxedHtmlView(instrumentation.targetContext, html).getOrThrow() }
        var title = ""
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && !title.startsWith("ran|")) {
            instrumentation.runOnMainSync { title = view.title.orEmpty() }
            Thread.sleep(100)
        }
        assertEquals("ran|blocked|undefined", title)
        instrumentation.runOnMainSync { view.destroy() }
    }
}
