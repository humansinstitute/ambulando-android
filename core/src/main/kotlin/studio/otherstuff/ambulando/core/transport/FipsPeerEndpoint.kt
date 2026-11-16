package studio.otherstuff.ambulando.core.transport

import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

/** Peer binding for the shared OS-VPN adapter. No key custody or runtime is created.
 * FIPS 0.5 pinned rev 80f8f965: NodeAddr = SHA256(x-only pubkey)[0..16];
 * FipsAddress = 0xfd || NodeAddr[0..15]. Compare raw bytes, never DNS text aliases.
 */
class FipsPeerEndpoint(val peerNpub: String, val host: String, val port: Int) {
    private val key = NostrKeys.publicKeyHex(peerNpub)?.takeIf { NostrKeys.npub(it) == peerNpub }
        ?: throw IllegalArgumentException("Invalid canonical FIPS peer")
    init { require(host == "$peerNpub.fips" && port in 1..65535) { "FIPS peer/host mismatch" } }
    val origin: String get() = "http://$host:$port"
    val addressBytes: ByteArray get() = byteArrayOf(0xfd.toByte()) + Sha256Hex.digest(Hex.decode(key)!!).copyOfRange(0, 15)
    fun requirePeerAddresses(addresses: List<ByteArray>) {
        require(addresses.isNotEmpty() && addresses.all { it.contentEquals(addressBytes) }) { "FIPS DNS result does not match the pinned peer" }
    }
}
