package studio.otherstuff.ambulando.core.transport

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*

class FipsPeerEndpointTest {
    @Test fun pinnedFipsRevisionVectors() {
        val vectors = listOf(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" to "fd132f39a98c31baaddba6525f5d43f2",
            "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e" to "fd74534fb5959a5d89cd740363ba2737",
        )
        for ((key, expected) in vectors) {
            val peer = NostrKeys.npub(key)!!
            val endpoint = FipsPeerEndpoint(peer, "$peer.fips", 4443)
            assertEquals(expected, Hex.encode(endpoint.addressBytes))
            endpoint.requirePeerAddresses(listOf(Hex.decode(expected)!!))
            assertEquals("http://$peer.fips:4443", endpoint.origin)
        }
    }
    @Test fun rejectsPublicOtherPeerAndMixedDnsAnswers() {
        val peer = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
        val endpoint = FipsPeerEndpoint(peer, "$peer.fips", 4443)
        for (answers in listOf(emptyList(), listOf(byteArrayOf(127,0,0,1)), listOf(endpoint.addressBytes, ByteArray(16)))) {
            try { endpoint.requirePeerAddresses(answers); fail("Expected peer pin rejection") } catch (_: IllegalArgumentException) {}
        }
        try { FipsPeerEndpoint(peer, "other.fips", 4443); fail("Expected host rejection") } catch (_: IllegalArgumentException) {}
    }
}
