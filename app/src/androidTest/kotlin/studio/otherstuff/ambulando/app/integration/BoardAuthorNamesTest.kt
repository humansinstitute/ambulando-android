package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.AppEnvironment
import studio.otherstuff.ambulando.app.MainActivity
import studio.otherstuff.ambulando.app.web.EditorWebHost
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.*
import studio.otherstuff.ambulando.core.surfaces.BoardAuthorNames
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic local editor only. Run in the release manager's reserved device slot. */
@RunWith(AndroidJUnit4::class)
class BoardAuthorNamesTest {
    @Test fun discussionRefreshesExactNamesKeepsReplyDraftAndRejectsStaleViewNames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val hex = "ab".repeat(32)
        val author = NostrKeys.npub(hex)!!
        val other = NostrKeys.npub("cd".repeat(32))!!
        val outgoing = CopyOnWriteArrayList<String>()
        val projection = BoardAuthorNames()
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var host: EditorWebHost
            scenario.onActivity { activity ->
                host = EditorWebHost.create(activity, "index.html", setOf("board"), 32 * 1024 * 1024) { _, raw -> outgoing.add(raw) }.getOrThrow()
                activity.setContentView(host.webView)
                host.load()
            }
            fun evaluate(script: String): String {
                val latch = CountDownLatch(1)
                var result = ""
                instrumentation.runOnMainSync { host.webView.evaluateJavascript(script) { result = it; latch.countDown() } }
                assertTrue("Editor JavaScript callback", latch.await(10, TimeUnit.SECONDS))
                return result
            }
            fun waitFor(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(50)
                }
                fail(label)
            }
            fun deliver(message: String) {
                instrumentation.runOnMainSync { host.call("window.ambulandoBoard.deliver", message) }
            }
            fun people(name: String) = PeopleDirectory(emptyList(), emptyMap(), listOf(
                ProfileRecord(hex, author, displayName = name, status = ProfileRecord.Status.FOUND, checkedAt = Instant.EPOCH)))
            try {
                waitFor("Board ready") { outgoing.any { it.contains("\"type\":\"ready\"") } }
                val load = buildJsonObject {
                    put("type", "load"); put("key", "synthetic-board"); put("readOnly", false)
                    put("scene", buildJsonObject {
                        put("type", "excalidraw"); put("version", 2); put("elements", JsonArray(emptyList()))
                        put("appState", JsonObject(emptyMap())); put("files", JsonObject(emptyMap()))
                        put("wingman", buildJsonObject { put("comments", JsonArray(listOf(buildJsonObject {
                            put("id", "thread"); put("resolved", false)
                            put("anchor", buildJsonObject { put("x", 0); put("y", 0) })
                            put("messages", JsonArray(listOf(buildJsonObject {
                                put("id", "message"); put("body", "Original comment"); put("author_npub", author)
                                put("created_at", "2026-01-01T00:00:00Z")
                            })))
                        }))) })
                    })
                }.toString()
                projection.sent(load); deliver(load)
                waitFor("Author identity request") { outgoing.any { projection.request(it) } }
                evaluate("document.querySelector('.board-discussion-toggle').click()")
                evaluate("document.querySelector('.board-comment-card button').click()")
                waitFor("Unknown fallback") { evaluate("document.querySelector('.board-comment-messages small')?.textContent").contains("Unknown person") }
                // A name refresh must not remount the panel or discard unsent reply text.
                evaluate("(() => { const input = document.querySelector('.board-comment-thread textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, 'Keep unsent reply'); input.dispatchEvent(new Event('input', { bubbles: true })); })()")
                deliver(projection.message(people("<Alice & safe>"))!!)
                waitFor("Cached kind-0 name") { evaluate("document.querySelector('.board-comment-messages small')?.textContent").contains("Alice & safe") }
                assertEquals("\"Keep unsent reply\"", evaluate("document.querySelector('.board-comment-thread textarea').value"))
                assertEquals("null", evaluate("document.querySelector('.board-comment-messages small img')"))
                val wrong = buildJsonObject { put("type", "author-names"); put("key", "synthetic-board"); put("names", buildJsonObject { put(other, "Wrong person") }) }.toString()
                deliver(wrong)
                waitFor("Wrong identity stays unknown") { evaluate("document.querySelector('.board-comment-messages small')?.textContent").contains("Unknown person") }
                deliver(projection.message(people("Renamed person"))!!)
                waitFor("Rename arrives") { evaluate("document.querySelector('.board-comment-messages small')?.textContent").contains("Renamed person") }
                deliver("""{"type":"author-names","key":"old-view","names":{"$author":"Stale person"}}""")
                assertTrue(evaluate("document.querySelector('.board-comment-messages small')?.textContent").contains("Renamed person"))
                assertTrue(outgoing.none { it.contains("\"type\":\"change\"") || it.contains("\"type\":\"comment-op\"") })
                assertTrue(load.contains(author)); assertFalse(load.contains("Renamed person"))
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null), "android71-board-renamed-author.png").outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                bitmap.recycle()
            } finally {
                instrumentation.runOnMainSync { host.destroy() }
            }
        }
    }
}
