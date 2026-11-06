package studio.otherstuff.ambulando.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.nio.file.Files

class FamilyBindingTest {
    private val workspace = "10000000-0000-4000-8000-000000000001"
    private val scope = "10000000-0000-4000-8000-000000000002"
    private val channel = "10000000-0000-4000-8000-000000000003"
    private val thread = "10000000-0000-4000-8000-000000000004"
    private val connection = "10000000-0000-4000-8000-000000000005"
    private val other = "10000000-0000-4000-8000-000000000006"
    private val npub = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { 71 }))!!
    private val binding = FamilyBinding("https://family.invalid", npub, connection, npub, workspace, scope, channel, thread)
    private fun request(b: FamilyBinding = binding, body: String = """{"thread_id":"${b.threadId}","body":"Hello"}""") =
        HttpRequest("POST", "${b.origin}/api/v4/flightdeck-pg/workspaces/${b.workspaceId}/channels/${b.channelId}/messages", mapOf(TowerClient.APP_NPUB_HEADER to b.appNpub), body.toByteArray())
    private fun threadRow() = ThreadRecord(workspace, thread, channel, null, "Family", updatedAt = "")
    private class Memory : VaultSecretStore {
        private var value: ByteArray? = null
        override fun read() = value
        override fun write(data: ByteArray) { value = data.copyOf() }
    }

    @Test fun bindsExactScopeAndRejectsCorruption() {
        assertEquals(binding, FamilyBinding.decode(binding.encode()))
        assertNull(FamilyBinding.decode("{}"))
        assertNull(FamilyBinding.decode(binding.copy(threadId = "../other").encode()))
        assertNull(FamilyBinding.decode(binding.copy(origin = "http://family.invalid").encode()))
        val c = ConnectionRecord(connection, binding.origin, npub, null, "")
        val w = WorkspaceRecord(workspace, npub, "Family", null, null, null, null, JsonObject(emptyMap()))
        assertTrue(binding.owns(c, w))
        assertFalse(binding.owns(c.copy(origin = "https://another.invalid"), w))
        assertFalse(binding.owns(c.copy(signerNpub = "different"), w))
        assertFalse(binding.owns(c.copy(id = other), w))
        assertFalse(binding.owns(c, w.copy(appNpub = "another")))
        assertFalse(binding.owns(c, w.copy(id = other)))
    }

    @Test fun linksAndOutgoingTargetsCannotEscapeOrCreateThreads() {
        assertTrue(binding.allows(request()))
        for (changed in listOf(binding.copy(origin = "https://another.invalid"), binding.copy(workspaceId = other), binding.copy(channelId = other), binding.copy(threadId = other), binding.copy(appNpub = "another"))) {
            assertFalse(binding.allows(request(changed)))
        }
        assertFalse(binding.allows(request(body = """{"create_thread":true}""")))
        assertFalse(binding.allows(request(body = """{"thread_id":"$thread","create_thread":true}""")))
        assertFalse(binding.allows(HttpRequest("GET", request().url)))
        assertTrue(binding.allows(threadRow()))
        assertFalse(binding.allows(threadRow().copy(archived = true)))
        assertFalse(binding.allows(threadRow().copy(deleted = true)))
        assertFalse(binding.allows(threadRow().copy(accessRemoved = true)))
        assertFalse(binding.allows(threadRow().copy(channelId = other)))
        assertFalse(binding.allows(threadRow().copy(workspaceId = other)))
    }

    @Test fun restartAndChangingBindingPreserveDraftsAndFrozenOutbox() = runBlocking {
        val dir = Files.createTempDirectory("family-persistence").toFile()
        val target = ChatTarget(connection, workspace, channel, thread)
        val next = target.copy(threadId = other)
        var store = LocalStore(dir)
        store.writer.saveConnection(ConnectionRecord(connection, binding.origin, npub, null, ""))
        store.writer.setState(FamilyBinding.KEY, binding.encode())
        val item = store.writer.enqueueSend(target, binding.origin, npub, npub, "Unsent", emptyList())
        store.writer.freezeOutbox(item.id, request().body!!)
        store.writer.saveDraft(target, Draft("Draft with café 👪", emptyList()))
        store.writer.saveDraft(next, Draft("Another conversation draft", emptyList()))
        store.close(); store = LocalStore(dir)
        assertEquals(binding, FamilyBinding.decode(store.reader.state(FamilyBinding.KEY)))
        assertEquals("Draft with café 👪", store.reader.draft(target)?.text)
        assertArrayEquals(request().body, store.reader.outboxItem(item.id)?.request)
        store.writer.setState(FamilyBinding.KEY, binding.copy(threadId = other).encode())
        assertEquals("Draft with café 👪", store.reader.draft(target)?.text)
        assertEquals("Another conversation draft", store.reader.draft(next)?.text)
        assertNotNull(store.reader.outboxItem(item.id))
        store.close(); dir.deleteRecursively(); Unit
    }

    @Test fun everyAttemptRevalidatesAndOfflineDoesNotPostThenRetryUsesSameBytes() = runBlocking {
        val dir = Files.createTempDirectory("family-guard").toFile()
        val identity = IdentityService(File(dir, "vault"), Memory())
        identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 71 }), "246810")
        val store = LocalStore(File(dir, "store"))
        store.writer.saveConnection(ConnectionRecord(connection, binding.origin, npub, null, ""))
        store.writer.setState(FamilyBinding.KEY, binding.encode())
        val calls = mutableListOf<HttpRequest>()
        var offline = true
        var archived = false
        var wrongChannel = false
        var revoke = false
        var changeDuringRead = false
        val base = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                calls += request
                if (offline) throw java.io.IOException("offline")
                if (revoke) return HttpResponse(403, "{}".toByteArray())
                val path = java.net.URI(request.url).path
                val body = when {
                    path.endsWith("/workspaces") -> """{"workspaces":[{"identity":{"workspace_id":"$workspace","app_npub":"$npub"},"label":"Family"}]}"""
                    path.endsWith("/channels") -> """{"channels":[{"id":"$channel","workspace_id":"$workspace","scope_id":"$scope","name":"Family"}]}"""
                    path.endsWith("/threads/$thread") -> {
                        if (changeDuringRead) store.writer.setState(FamilyBinding.KEY, binding.copy(threadId = other).encode())
                        """{"thread":{"id":"$thread","workspace_id":"$workspace","channel_id":"${if (wrongChannel) other else channel}","record_state":"${if (archived) "archived" else "active"}"}}"""
                    }
                    path.endsWith("/messages") -> "{}"
                    else -> error("Unexpected canonical request $path")
                }
                return HttpResponse(200, body.toByteArray())
            }
        }
        val guard = FamilyGuardTransport(base, store, identity)
        val frozen = request()
        try { guard.send(frozen); fail("Offline validation must stop the POST") } catch (_: java.io.IOException) { }
        assertTrue(calls.none { it.method == "POST" })
        offline = false
        assertEquals(200, guard.send(frozen).status)
        assertSame(frozen, calls.last())
        assertEquals(200, guard.send(frozen).status)
        assertEquals(2, calls.count { it.method == "POST" })
        assertEquals(2, calls.count { it.url.endsWith("/threads/$thread") })
        archived = true
        assertEquals(403, guard.send(frozen).status)
        archived = false; revoke = true
        assertEquals(403, guard.send(frozen).status)
        revoke = false; wrongChannel = true
        assertEquals(403, guard.send(frozen).status)
        wrongChannel = false; changeDuringRead = true
        assertEquals(403, guard.send(frozen).status)
        assertEquals(2, calls.count { it.method == "POST" })
        assertEquals(403, guard.send(frozen).status) // Old outbox remains blocked after deliberate change.
        store.close(); dir.deleteRecursively(); Unit
    }

    @Test fun richLinksAndNavigationCallbacksStayInFamilyConversation() {
        val photos = mutableListOf<String>()
        val services = familyServices(null) { id, _ -> photos += id }
        services.open(studio.otherstuff.ambulando.app.ui.nav.WorkRoute.Thread(workspace, channel, other))
        services.open(studio.otherstuff.ambulando.app.ui.nav.WorkRoute.Doc(workspace, other))
        for (kind in listOf("thread", "channel", "task", "document", "person", "agent")) services.openMention(kind, other)
        services.openUrl("https://elsewhere.invalid/api/v4/thread/$other")
        assertTrue(photos.isEmpty())
        services.openUrl("storage://$other")
        assertEquals(listOf(other), photos)
    }

    @Test fun absentBindingAndOtherTargetAreDeniedWithoutNetwork() = runBlocking {
        val dir = Files.createTempDirectory("family-denied").toFile()
        val store = LocalStore(dir)
        val guard = FamilyGuardTransport(object : HttpTransport { override suspend fun send(request: HttpRequest): HttpResponse = error("Must not reach network") }, store, IdentityService(File(dir, "vault"), Memory()))
        assertEquals(403, guard.send(request()).status)
        store.writer.setState(FamilyBinding.KEY, binding.encode())
        assertEquals(403, guard.send(request(binding.copy(channelId = other))).status)
        store.close(); dir.deleteRecursively(); Unit
    }
}
