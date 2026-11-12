package studio.otherstuff.ambulando.core.discovery

import kotlinx.coroutines.withTimeout
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary

/** Durable ciphertext-only retry state, keyed by the existing origin/signer/workspace/app boundary. */
class NativeIndexOutbox(private val store: LocalStore, private val publisher: NativeWorkspacePublisher) {
    companion object {
        fun pendingKey(boundaryId: String) = "native-index.pending.v1:$boundaryId"
        fun publishedKey(boundaryId: String) = "native-index.published.v1:$boundaryId"
    }
    suspend fun send(locator: PrivateWorkspaceLocator, boundary: WorkspaceBoundary) = withTimeout(45_000) {
        require(boundary.origin == locator.origin && boundary.workspaceId == locator.workspaceId && boundary.appNpub == locator.appNpub) { "Private index boundary mismatch" }
        publisher.requireSigner(boundary.signerNpub)
        val boundaryId = boundary.id
        val fingerprint = Sha256Hex.of(listOf(NativeIndexNamespace.PUBKEY,locator.origin,locator.workspaceId,locator.appNpub,locator.towerNpub,locator.serviceNpub,locator.ownerNpub).joinToString("|").toByteArray())
        val stored = store.reader.state(pendingKey(boundaryId))
        if (stored == null && store.reader.state(publishedKey(boundaryId)) == fingerprint) return@withTimeout
        store.writer.setState(pendingKey(boundaryId),stored ?: "pending")
        val cached = stored?.takeIf { it != "pending" }?.let { runCatching { WireJson.decodeFromString(NostrEvent.serializer(),it) }.getOrNull() }
        val event = if (cached != null && publisher.matches(locator,cached)) cached else publisher.prepare(locator).also {
            store.writer.setState(pendingKey(boundaryId),it.toJson())
        }
        publisher.publish(locator,event)
        store.writer.setState(pendingKey(boundaryId),null)
        store.writer.setState(publishedKey(boundaryId),fingerprint)
    }
}
