package studio.otherstuff.ambulando.core.transport

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys

class FipsGrantBindingTest {
    private val npub = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    private val peer = FipsPeerEndpoint(npub, "$npub.fips", 4443)
    private val ready = FipsGrantBinding("consent", "consent", peer.origin, npub, "nstack", 1234, "generation1", peer.addressBytes, 160, 61_000)
    @Test fun identicalRenewalExtendsOnlyExpiry() {
        ready.validate(peer, "consent", null, 100, 1000)
        ready.copy(expiresAt = 170, expiresElapsedRealtime = 71_000).validate(peer, "consent", ready, 110, 11_000)
    }
    @Test fun rejectsNetworkRuntimeEndpointPeerAndExpiredGrants() {
        for (candidate in listOf(
            ready.copy(networkHandle = 0), ready.copy(networkHandle = 5678), ready.copy(generation = "generation2"),
            ready.copy(endpoint = peer.origin + "/"), ready.copy(peerNpub = "other"), ready.copy(peerIpv6Bytes = ByteArray(16)),
            ready.copy(purpose = "browser"), ready.copy(grant = "other"), ready.copy(expiresAt = 100),
            ready.copy(expiresAt = 161), ready.copy(expiresElapsedRealtime = 1000), ready.copy(expiresElapsedRealtime = 61_001),
        )) {
            try { candidate.validate(peer, "consent", ready, 100, 1000); fail("Expected grant rejection: $candidate") } catch (_: IllegalArgumentException) {}
        }
    }
}
