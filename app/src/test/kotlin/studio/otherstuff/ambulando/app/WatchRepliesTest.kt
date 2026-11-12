package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.garmin.WatchReplies

class WatchRepliesTest {
    private class Harness {
        var time = 0L
        var sequence = 0
        val sent = mutableListOf<Map<String, Any>>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val confirmed = mutableListOf<Long>()
        val statuses = mutableListOf<String>()
        val lane = WatchReplies({ time }, { "nonce_${++sequence}" }, { _, packet, done -> sent += packet; callbacks += done }, { statuses += it }, { confirmed += it })
        fun packet(revision: Int = 1) = mapOf("v" to 1, "s" to "s1", "r" to revision, "state" to "waiting", "detail" to "ready", "b" to 38)
        fun receipt(index: Int = sent.lastIndex) = sent[index].filterKeys { it in setOf("s", "r", "n", "b") } + ("op" to "receipt")
    }
    @Test fun onlyExactApplicationReceiptConfirmsWrist() {
        val h = Harness(); h.lane.offer(7, h.packet()); h.callbacks[0](true)
        assertTrue(h.confirmed.isEmpty())
        assertFalse(h.lane.receipt(8, h.receipt()))
        for (bad in listOf(mapOf("s" to "other"), mapOf("n" to "old"), mapOf("r" to 0), mapOf("b" to 37), mapOf("b" to 38.5), mapOf("r" to 1.2))) {
            assertFalse(h.lane.receipt(7, h.receipt() + bad))
        }
        assertTrue(h.lane.receipt(7, h.receipt()))
        assertEquals(listOf(7L), h.confirmed)
        assertFalse(h.lane.receipt(7, h.receipt()))
    }
    @Test fun serializesAndCoalescesUntilBothSdkCallbackAndReceiptArrive() {
        val h = Harness(); h.lane.offer(7, h.packet()); h.lane.offer(7, h.packet())
        h.lane.offer(7, h.packet(2)); h.lane.offer(7, h.packet(3))
        assertEquals(1, h.sent.size)
        assertTrue(h.lane.receipt(7, h.receipt()))
        assertEquals(1, h.sent.size)
        h.callbacks[0](true)
        assertEquals(2, h.sent.size)
        assertEquals(3, h.sent[1]["r"])
    }
    @Test fun boundedResendsKeepNonceAndMissingSdkCallbackNeverOverlaps() {
        val h = Harness(); h.lane.offer(7, h.packet()); h.lane.offer(7, h.packet(2))
        h.time = 7_000; h.lane.tick(); assertEquals(1, h.sent.size)
        h.callbacks[0](true); h.lane.tick(); assertEquals(2, h.sent.size)
        h.callbacks[1](true); h.time = 14_000; h.lane.tick()
        assertEquals(3, h.sent.size); assertEquals(1, h.sent.map { it["n"] }.distinct().size)
        h.time = 22_000; h.lane.tick(); assertEquals(3, h.sent.size)
        h.callbacks[2](true); h.lane.tick(); assertEquals(4, h.sent.size)
    }
    @Test fun legacyWatchAdvancesOnTransportCallbackButNeverClaimsWristReceipt() {
        val h = Harness(); h.lane.offer(7, h.packet(), false); h.lane.offer(7, h.packet(2), false)
        assertEquals(1, h.sent.size); h.callbacks[0](true)
        assertEquals(2, h.sent.size); assertTrue(h.confirmed.isEmpty())
        assertTrue(h.statuses.any { it.contains("legacy reply; wrist unconfirmed") })
        assertFalse(h.lane.receipt(7, h.receipt()))
    }
    @Test fun closedTransportDropsMissingCallbackAndOldCallbackCannotReleaseNewSnapshot() {
        val h = Harness(); h.lane.offer(7, h.packet()); val old = h.receipt()
        h.lane.reset(transportClosed = true); h.lane.offer(7, h.packet(2))
        assertEquals(2, h.sent.size); h.callbacks[0](true)
        assertFalse(h.lane.receipt(7, old))
        h.time = 7_000; h.lane.tick(); assertEquals(2, h.sent.size)
        h.callbacks[1](true); h.lane.tick(); assertEquals(3, h.sent.size)
    }
    @Test fun receiptOfBoundedSameNonceResendRecoversLostFirstReceipt() {
        val h = Harness(); h.lane.offer(7, h.packet()); h.callbacks[0](true)
        h.time = 7_000; h.lane.tick(); h.callbacks[1](true)
        assertEquals(h.sent[0]["n"], h.sent[1]["n"])
        assertTrue(h.lane.receipt(7, h.receipt())); assertEquals(listOf(7L), h.confirmed)
        h.time = 22_000; h.lane.tick(); assertEquals(2, h.sent.size)
    }
    @Test fun resetRejectsOldReceiptsAndCallbacks() {
        val h = Harness(); h.lane.offer(7, h.packet()); val old = h.receipt()
        h.lane.reset(); h.lane.offer(8, h.packet(2)); assertEquals(1, h.sent.size)
        h.callbacks[0](true)
        assertFalse(h.lane.receipt(7, old)); assertEquals(2, h.sent.size)
        h.time = 7_000; h.lane.tick(); assertEquals(2, h.sent.size)
    }
}
