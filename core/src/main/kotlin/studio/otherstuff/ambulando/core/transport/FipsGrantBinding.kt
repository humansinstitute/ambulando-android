package studio.otherstuff.ambulando.core.transport

/** Readiness metadata is not app authentication. The provider Binder is checked by the Android adapter. */
data class FipsGrantBinding(
    val requestId: String, val grant: String, val endpoint: String, val peerNpub: String, val purpose: String,
    val networkHandle: Long, val generation: String, val peerIpv6Bytes: ByteArray,
    val expiresAt: Long, val expiresElapsedRealtime: Long,
) {
    fun validate(peer: FipsPeerEndpoint, expectedId: String, previous: FipsGrantBinding?, nowSeconds: Long, nowElapsed: Long) {
        require(requestId == expectedId && grant == expectedId && expectedId.isNotEmpty() && endpoint == peer.origin &&
            peerNpub == peer.peerNpub && purpose == "nstack" && networkHandle != 0L && generation.isNotEmpty() &&
            peerIpv6Bytes.contentEquals(peer.addressBytes)) { "FIPS grant endpoint, peer or network binding mismatch" }
        require(expiresAt > nowSeconds && expiresAt <= nowSeconds + 60 && expiresElapsedRealtime > nowElapsed && expiresElapsedRealtime <= nowElapsed + 60_000) { "FIPS grant expired or invalid lease" }
        if (previous != null) require(previous.requestId == requestId && previous.grant == grant && previous.endpoint == endpoint &&
            previous.peerNpub == peerNpub && previous.purpose == purpose && previous.networkHandle == networkHandle &&
            previous.generation == generation && previous.peerIpv6Bytes.contentEquals(peerIpv6Bytes)) { "FIPS runtime or network changed; request fresh consent" }
    }
}
