package studio.otherstuff.ambulando.app

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys

class ConnectionTransportLabelTest {
    private val npub = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    @Test fun usesCanonicalSendingParser() {
        assertEquals("Shared FIPS + NIP-98", connectionTransportLabel("http://$npub.fips:4443"))
        assertEquals("HTTPS + NIP-98", connectionTransportLabel("https://example.invalid"))
        assertEquals("Invalid connection endpoint", connectionTransportLabel("https://$npub.fips:4443"))
        assertEquals("Invalid connection endpoint", connectionTransportLabel("http://$npub.fips.:4443"))
    }
}
