package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleViewer
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.toJsonElement
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.IOException

/** The public workspace-avatar contract: administrator only, bounded, prepared public, uploaded, completed, returned as storage://. */
class AvatarUploadTests {
    private val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
    @After fun close() { engine.close() }

    private class Rig(engine: SyncEngine, viewer: SampleViewer, owner: String? = SampleIds.ANA) {
        val tower = SampleTower(tempDir(), originUrl = "https://one.sample.invalid").also { it.prepare(TestKeys.NPUB); it.administration.viewer = viewer }
        val requests = java.util.Collections.synchronizedList(ArrayList<HttpRequest>())
        @Volatile var failUpload = false
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                requests += request
                if (failUpload && request.method == "PUT") throw IOException("The connection was lost.")
                return tower.send(request)
            }
        }
        val connection = ConnectionRecord.create(TowerOrigin("https://one.sample.invalid"), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord.parse(toJsonElement(mapOf(
            "identity" to buildMap {
                put("workspace_id", SampleIds.STUDIO); put("app_npub", SampleIds.APP); owner?.let { put("workspace_owner_npub", it) }
            },
            "label" to "Studio", "membership" to mapOf("role" to "member"),
        )) as JsonObject)
        val admin = WorkspaceAdministrator(WorkspaceBoundary.of(connection, workspace), connection, workspace, LocalStore(tempDir()), runBlocking { makeIdentity() }, http, http, engine)
        fun storageRequests() = requests.filter { it.url.contains("/api/v4/storage") || it.url.contains("/bucket/") }
    }

    private val png = ByteArray(2048) { (it % 251).toByte() }

    @Test fun anAdministratorUploadsAPublicAvatarWithTheExactPrepareBody() = runBlocking<Unit> {
        val rig = Rig(engine, SampleViewer.ADMIN)
        val address = rig.admin.uploadWorkspaceAvatar(png)
        assertTrue(address.startsWith("storage://"))
        val prepare = rig.requests.single { it.method == "POST" && it.url.endsWith("/api/v4/storage/prepare") }
        val body = parseJsonObject(prepare.body!!, "prepare")
        assertEquals(SampleIds.ANA, body.string("owner_npub")); assertEquals("image/png", body.string("content_type"))
        assertEquals("workspace-avatar.png", body.string("file_name")); assertEquals("true", body["is_public"].toString())
        assertEquals(png.size.toString(), body["size_bytes"].toString())
        val metadata = body["metadata"] as JsonObject
        assertEquals("workspace-profile/avatar", metadata.string("purpose")); assertEquals("public", metadata.string("visibility")); assertEquals(SampleIds.STUDIO, metadata.string("workspace_id"))
        val id = address.removePrefix("storage://")
        // Uploaded to the presigned object store (unsigned) and completed with the bytes' hash.
        val put = rig.requests.single { it.method == "PUT" }
        assertTrue(put.url.contains("/bucket/$id")); assertEquals(null, put.header("Authorization"))
        val complete = rig.requests.single { it.url.endsWith("/api/v4/storage/$id/complete") }
        assertEquals(Sha256Hex.of(png), parseJsonObject(complete.body!!, "complete").string("sha256_hex"))
        // The address is accepted by the profile command; the person still saves the workspace.
        AdministrationCommand.Profile("Studio", "", address).request(SampleIds.STUDIO)
    }

    @Test fun aNonAdministratorOrAnInvalidInputSendsNoStorageRequest() = runBlocking<Unit> {
        val member = Rig(engine, SampleViewer.MEMBER)
        assertTrue(runCatching { member.admin.uploadWorkspaceAvatar(png) }.exceptionOrNull() is ManagementError.Forbidden)
        assertTrue(member.storageRequests().isEmpty())
        val admin = Rig(engine, SampleViewer.ADMIN)
        assertTrue(runCatching { admin.admin.uploadWorkspaceAvatar(ByteArray(WorkspaceAdministrator.MAX_AVATAR_BYTES + 1)) }.exceptionOrNull() is ManagementError.Invalid)
        assertTrue(runCatching { admin.admin.uploadWorkspaceAvatar(ByteArray(0)) }.exceptionOrNull() is ManagementError.Invalid)
        val noOwner = Rig(engine, SampleViewer.ADMIN, owner = null)
        assertTrue(runCatching { noOwner.admin.uploadWorkspaceAvatar(png) }.exceptionOrNull() is ManagementError.Invalid)
        assertTrue(admin.requests.isEmpty() && noOwner.requests.isEmpty())
    }

    @Test fun aFailedUploadIsReportedWithoutCompletingTheObjectOrReturningAnAddress() = runBlocking<Unit> {
        val rig = Rig(engine, SampleViewer.ADMIN)
        rig.failUpload = true
        val failure = runCatching { rig.admin.uploadWorkspaceAvatar(png) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ManagementError.Offline)
        assertTrue("an object that was never uploaded is not completed", rig.requests.none { it.url.endsWith("/complete") })
    }

    @Test fun aClosedAdministratorSendsNothing() = runBlocking<Unit> {
        val rig = Rig(engine, SampleViewer.ADMIN)
        rig.admin.close()
        try { rig.admin.uploadWorkspaceAvatar(png); fail("expected closed") } catch (_: AdministrationSuperseded) {}
        assertTrue(rig.requests.isEmpty())
    }
}
