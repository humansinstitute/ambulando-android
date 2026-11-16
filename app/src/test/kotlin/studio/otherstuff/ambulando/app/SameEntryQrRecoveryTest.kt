package studio.otherstuff.ambulando.app

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.refreshPins
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections

/** Full signed QR/proof completion through AppModel, with its existing same-entry observation alive. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SameEntryQrRecoveryTest {
    private class Memory : VaultSecretStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(data: ByteArray) { bytes = data.copyOf() }
    }
    private class World(root: File) {
        val key = ByteArray(32) { 47 } // Synthetic server fixture only.
        val service = NostrKeys.npub(NostrKeys.publicKeyHex(key))!!
        val identity = IdentityService(File(root, "identity/vault"), Memory())
        val tower = SampleTower(File(root, "tower"))
        val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
        @Volatile var consentBlocked = false
        @Volatile var hold: CompletableDeferred<Unit>? = null
        val arrived = CompletableDeferred<Unit>()
        var afterProof: (suspend () -> Unit)? = null
        fun targeted(r: HttpRequest) = r.url.contains("/${SampleIds.STUDIO}/") &&
            (r.url.contains("/record-sync/priority?") || r.url.contains("/me/preferences/") || r.url.contains("/notifications/") || r.url.contains("/resource-view-states") || r.url.substringBefore('?').endsWith("/scopes") ||
                Regex("/channels/[^/]+/(tasks|docs|files)(\\?|$)").containsMatchIn(r.url))
        fun signed(kind: Int, tag: String, content: JsonObject, now: Long = System.currentTimeMillis() / 1000): String = NostrKeys.sign(
            NostrEventTemplate(kind, listOf(listOf("d", tag)), content.toString(), createdAt = now), key).toJson()
        fun identityRow(app: Boolean = true) = buildJsonObject {
            put("workspace_id", SampleIds.STUDIO); put("workspace_service_npub", service); put("tower_service_npub", service)
            if (app) put("app_npub", SampleIds.APP)
        }
        fun envelope(): String {
            val payload = buildJsonObject {
                put("type", "nstack-workspace"); put("version", 3); put("service_npub", service)
                put("workspace_id", SampleIds.STUDIO); put("workspace_service_npub", service)
                put("transports", buildJsonObject { put("https", buildJsonObject { put("url", tower.origin.url) }) })
                put("policy", buildJsonObject { put("preferred", "https"); put("fallback", "none") })
            }
            return WorkspaceConnection.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(signed(30383, "nstack-workspace-connection-v3", payload).toByteArray())
        }
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                requests.add(request)
                if (consentBlocked && request.method == "GET" && request.url.startsWith(tower.origin.url)) throw SharedConnectionException(ConnectionIssue.CONSENT)
                if (targeted(request)) {
                    hold?.let { arrived.complete(Unit); it.await() }
                }
                val path = java.net.URI(request.url).path
                fun reply(body: String) = HttpResponse(200, body.toByteArray())
                if (path.endsWith("/connection-proof")) {
                    val nonce = java.net.URI(request.url).query.substringAfter("nonce=")
                    val now = System.currentTimeMillis() / 1000
                    val proof = signed(30384, "nstack-connection-challenge-v1", buildJsonObject {
                        put("nonce", nonce); put("service_npub", service); put("workspace_id", SampleIds.STUDIO)
                        put("workspace_service_npub", service); put("request_url", request.url); put("expires_at", now + 60)
                    }, now)
                    return reply("{\"proof\":$proof}")
                }
                if (path.endsWith("/${SampleIds.STUDIO}/me")) {
                    afterProof?.invoke()
                    return reply(buildJsonObject { put("identity", identityRow()) }.toString())
                }
                val response = tower.send(request)
                if (path.endsWith("/.well-known/nstack.json")) {
                    val obj = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
                    return reply(JsonObject(obj + ("stack" to buildJsonObject { put("tower_npub", service) })).toString())
                }
                if (path.endsWith("/api/v4/flightdeck-pg/workspaces")) {
                    val obj = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
                    val rows = obj.getValue("workspaces").jsonArray.map { value ->
                        val row = value.jsonObject
                        val old = row.getValue("identity").jsonObject
                        JsonObject(row + ("identity" to JsonObject(old + mapOf("workspace_service_npub" to JsonPrimitive(service), "tower_service_npub" to JsonPrimitive(service)))))
                    }
                    return reply(JsonObject(obj + ("workspaces" to JsonArray(rows))).toString())
                }
                return response
            }
        }
    }
    private fun test(block: suspend (World, AppModel) -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("qr-recovery-test").toFile()
        val base = RuntimeEnvironment.getApplication() as Context
        val context = object : ContextWrapper(base) { override fun getCacheDir() = File(root, "cache").also { it.mkdirs() } }
        val world = World(root)
        world.identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 29 }), "123456")
        world.tower.prepare(world.identity.state().npub!!)
        val model = AppModel(AppEnvironment.Sample(context), context, AppSessionDependencies(world.identity, world.http, world.tower.events), restoreInteractiveIdentity = false)
        try {
            withTimeout(30_000) {
                while (model.active?.validated != true) delay(20)
                model.sharedEngine.waitUntilIdle()
                // Establish the exact signed namespace first, then stay in Chat throughout the recovery.
                model.connectWorkspaceQr(world.envelope(), "https")
                model.sharedEngine.waitUntilIdle()
                block(world, model)
            }
        } finally {
            world.hold?.complete(Unit)
            (AppModel::class.java.getDeclaredField("viewModelScope").apply { isAccessible = true }.get(model) as CoroutineScope).cancel()
            model.runtimes.close(); model.sharedEngine.close(); model.catalog.close()
            Dispatchers.resetMain()
        }
    }
    private suspend fun failReads(world: World, model: AppModel): Map<String, String> {
        val r = model.active!!
        world.consentBlocked = true
        r.sync.refreshPins(r.connection, r.workspace, force = true)
        // The actual cached foreground/startup refresh schedules the complete normal read set.
        // Pins is the ordinary initially visible Deck read; recovery stays on Chat afterward.
        model.refresh(force = true)
        val channels = r.store.reader.syncableChannels(r.workspace.id)
        val expected = setOf(WorkspaceSync.scopesKey(r.workspace.id), "pins:${r.workspace.id}", "attention-types:${r.workspace.id}", "notifications:${r.workspace.id}", WorkspaceSync.inboxKey(r.workspace.id)) +
            channels.flatMap { listOf(WorkspaceSync.tasksKey(r.workspace.id, it.id), WorkspaceSync.docsKey(r.workspace.id, it.id), WorkspaceSync.filesKey(r.workspace.id, it.id)) }
        withTimeout(15_000) { while (!model.engine.snapshot().errors.keys.containsAll(expected)) delay(10) }
        model.sharedEngine.waitUntilIdle()
        val failed = model.engine.snapshot().errors // Capture every actual failure, independent of the recovery whitelist.
        assertTrue("Known startup reads must actually fail: $failed", failed.keys.containsAll(expected))
        world.consentBlocked = false
        model.section = AppSection.CHAT
        return failed
    }

    @Test fun sameActiveQrRecoversWholeNormalCachedStartupReadSetWithoutDeckOrSettings() = test { world, model ->
        val r = model.active!!; val failed = failReads(world, model)
        r.engine.enqueueRead(WorkspaceSync.taskKey(r.workspace.id, "unrelated-detail"), SyncPriority.USER, true) { error("unrelated detail") }
        r.engine.enqueue("voice:unrelated-operation", SyncPriority.USER, true) { error("unrelated operation") }
        r.engine.enqueueRead(WorkspaceSync.filesKey("other-workspace", "channel"), SyncPriority.USER, true) { error("other workspace") }
        model.sharedEngine.enqueueRead("unrelated-other-connection", SyncPriority.USER, true) { error("other connection") }
        model.sharedEngine.waitUntilIdle()
        val retained = r.engine.snapshot().errors.filterKeys { it !in failed }
        val pending = buildJsonObject { put("mutation_id", "original-intent"); put("expected_row_version", 4); put("value", "{\"version\":1,\"disabled\":[\"chat\"]}") }
        val desired = "{\"version\":1,\"disabled\":[\"file\"]}"
        r.store.writer.setState(AttentionPreferenceState.key(r.connection.id, r.workspace.id), AttentionPreferenceState(ready = true, pending = pending, desired = desired).encode())
        r.sync.api(r.connection).workspaces()
        model.sharedEngine.waitUntilIdle()
        assertEquals(failed + retained, model.engine.snapshot().errors)
        val release = CompletableDeferred<Unit>(); world.hold = release
        model.connectWorkspaceQr(world.envelope(), "https")
        world.arrived.await()
        assertSame(r, model.active)
        assertEquals(AppSection.CHAT, model.section) // Actual activate early return, no Deck LaunchedEffect.
        assertEquals(failed + retained, model.engine.snapshot().errors)
        release.complete(Unit); world.hold = null
        model.sharedEngine.waitUntilIdle()
        assertEquals(retained, model.engine.snapshot().errors)
        assertTrue(model.sharedEngine.snapshot().errors.containsKey("unrelated-other-connection"))
        val preference = r.store.reader.attentionPreference(r.connection.id, r.workspace.id)
        assertEquals(pending, preference.pending); assertEquals(desired, preference.desired)
        assertEquals(AppSection.CHAT, model.section)
        assertTrue(world.requests.all { it.method == "GET" })
    }

    @Test fun accountChangeDuringProofCannotRequestRecovery() = test { world, model ->
        val r = model.active!!; val failed = failReads(world, model)
        val before = world.requests.count { world.targeted(it) }
        world.afterProof = {
            world.identity.remove()
            world.identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 31 }), "123456")
        }
        assertTrue(runCatching { model.connectWorkspaceQr(world.envelope(), "https") }.isFailure)
        model.sharedEngine.waitUntilIdle()
        assertEquals(before, world.requests.count { world.targeted(it) })
        assertEquals(failed, r.engine.snapshot().errors)
    }

    @Test fun contextChangeDuringProofCannotRequestRecovery() = test { world, model ->
        val failed = failReads(world, model)
        val before = world.requests.count { world.targeted(it) }
        // Explicit selection increments the same context epoch fenced by QR confirmation.
        world.afterProof = { model.openWorkspace(model.entries.first { it.workspace.id != SampleIds.STUDIO });
            while (model.workspace?.id == SampleIds.STUDIO) delay(10) }
        assertTrue(runCatching { model.connectWorkspaceQr(world.envelope(), "https") }.isFailure)
        model.sharedEngine.waitUntilIdle()
        assertEquals(before, world.requests.count { world.targeted(it) })
        val original = model.runtimes.runtimes.value.values.first { it.workspace.id == SampleIds.STUDIO }
        assertEquals(failed, original.engine.snapshot().errors)
    }
}
