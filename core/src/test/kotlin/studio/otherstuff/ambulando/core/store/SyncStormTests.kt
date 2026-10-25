package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.MessageRecord
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Budget test (architecture §5): a 20k-message sync storm (decode + batched upserts of ≤ 500 rows) must neither stall
 * the thread that starts the writes (it only hops to the writer's dispatcher) nor starve the screen's reads (WAL: the
 * reader connection is never blocked by the writer). The JVM thresholds are deliberately looser than the iOS device
 * budgets (16 ms main p99, 25 ms read p95) so the test is not flaky on a loaded build host; it still fails if reads
 * were serialized behind whole write transactions or the caller thread did SQL work.
 */
class SyncStormTests {
    private val total = 20_000
    private val batch = 500

    private fun payloads(): List<ByteArray> = (0 until total).map { i ->
        Fixtures.json(mapOf(
            "id" to UUID.randomUUID().toString(), "workspace_id" to W, "channel_id" to if (i % 4 == 0) Fixtures.CHANNEL_1 else Fixtures.CHANNEL_2,
            "thread_id" to null, "body" to "Storm message $i " + "lorem ipsum ".repeat(12), "sender_npub" to TestKeys.NPUB,
            "created_at" to "2026-10-01T%02d:%02d:%02d.%03dZ".format((i / 3600) % 24, (i / 60) % 60, i % 60, i % 1000),
            "updated_at" to "2026-10-01T00:00:00.000Z", "row_version" to 1, "record_state" to "active", "attachments" to emptyList<Any>(),
            "metadata" to mapOf("mentions" to emptyList<Any>()),
        ))
    }

    private fun percentile(samples: List<Double>, p: Double): Double {
        val sorted = samples.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    @Test fun readsAndTheCallingThreadStayWithinBudgetDuringAStorm() = runBlocking {
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-probe") }.asCoroutineDispatcher()
        try {
            StoreFixtures.store().use { store ->
                val raw = payloads()
                // Warm up the code paths the measurement uses (JIT), outside the measured window.
                store.writer.upsertMessages(listOf(MessageRecord.parse(studio.otherstuff.ambulando.core.transport.parseJsonObject(raw[0], "message"))))
                repeat(20) { store.reader.latestMessages(W, Fixtures.CHANNEL_1, 50) }
                val done = AtomicBoolean(false)

                // Writer: started from the UI thread, as a screen-triggered sync would; decoding and SQL run elsewhere.
                val writer = async(main) {
                    val began = System.nanoTime()
                    for (start in 0 until total step batch) {
                        val records = withContext(Dispatchers.Default) {
                            raw.subList(start, minOf(start + batch, total)).map {
                                MessageRecord.parse(studio.otherstuff.ambulando.core.transport.parseJsonObject(it, "message"))
                            }
                        }
                        store.writer.upsertMessages(records)
                    }
                    done.set(true)
                    (System.nanoTime() - began) / 1e6
                }

                // UI probe: a 2 ms tick on the same thread; overshoot is time the thread could not run.
                val probe = async(main) {
                    val lags = ArrayList<Double>()
                    while (!done.get()) {
                        val t = System.nanoTime()
                        delay(2)
                        lags.add((System.nanoTime() - t) / 1e6 - 2)
                    }
                    lags
                }

                // Reader: the visible chat screen re-querying its window, as a coalesced observer would.
                val reader = async(Dispatchers.Default) {
                    val samples = ArrayList<Double>()
                    while (!done.get()) {
                        val t = System.nanoTime()
                        store.reader.latestMessages(W, Fixtures.CHANNEL_1, 50)
                        samples.add((System.nanoTime() - t) / 1e6)
                        delay(5)
                    }
                    samples
                }

                val writeMs = writer.await()
                val lags = probe.await()
                val reads = reader.await()
                val stored = store.reader.messageCount(W)
                val readP95 = percentile(reads, 0.95)
                val lagP99 = percentile(lags, 0.99)
                println("SyncStorm: $total messages in ${"%.0f".format(writeMs)} ms (${total / batch} transactions); " +
                    "reads n=${reads.size} p50=${"%.1f".format(percentile(reads, 0.5))} p95=${"%.1f".format(readP95)} ms; " +
                    "ui lag n=${lags.size} p99=${"%.1f".format(lagP99)} ms")
                assertEquals("the warm-up row is the first storm row", total, stored)
                assertTrue("reader ran throughout the storm", reads.size > 10)
                assertTrue("probe ran throughout the storm", lags.size > 20)
                assertTrue("screen read p95 ${"%.1f".format(readP95)} ms", readP95 <= 50.0)
                assertTrue("ui thread p99 lag ${"%.1f".format(lagP99)} ms", lagP99 <= 50.0)
            }
        } finally {
            main.close()
        }
    }

    /** Negative control: the probe must detect a deliberately blocked thread, or the budget test proves nothing. */
    @Test fun probeDetectsAStalledThread() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val done = AtomicBoolean(false)
            val probe = async(main) {
                val lags = ArrayList<Double>()
                while (!done.get()) {
                    val t = System.nanoTime()
                    delay(2)
                    lags.add((System.nanoTime() - t) / 1e6 - 2)
                }
                lags
            }
            launch(main) { delay(50); Thread.sleep(60) }.join()
            delay(50)
            done.set(true)
            assertTrue((probe.await().maxOrNull() ?: 0.0) >= 30.0)
        } finally {
            main.close()
        }
    }
}
