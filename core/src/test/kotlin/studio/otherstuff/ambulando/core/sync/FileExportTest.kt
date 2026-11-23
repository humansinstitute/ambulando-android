package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.Base64

class FileExportTest {
    @Test fun exportAwaitsAuthenticatedVerifiedBytesReusesCacheAndRejectsChangedHashAndAccessLoss(): Unit = runBlocking {
        val bytes = ByteArray(1025) { it.toByte() }
        val hash = Sha256Hex.of(bytes)
        val file = FileRecord(Fixtures.WORKSPACE_ID, Fixtures.newId(), null, Fixtures.CHANNEL_1, null, null,
            "installer.apk", contentType = "application/octet-stream", sizeBytes = bytes.size.toLong(), sha256 = hash, createdAt = "", updatedAt = "")
        val workspace = WorkspaceRecord(file.workspaceId, Fixtures.APP_NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        val transport = StubTransport { 200 to mapOf(
            "file" to mapOf("id" to file.id, "workspace_id" to file.workspaceId, "channel_id" to file.channelId, "display_name" to file.displayName),
            "object" to mapOf("encoding" to "base64", "base64_data" to Base64.getEncoder().encodeToString(bytes), "sha256_hex" to hash, "file_name" to file.displayName)) }
        val store = LocalStore(tempDir())
        val engine = SyncEngine()
        val cache = FileCache(tempDir())
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val sync = WorkspaceSync(store, engine, makeIdentity(), transport)
        try {
            val saved = sync.downloadForExport(connection, workspace, file, cache)
            assertArrayEquals(bytes, saved.readBytes())
            assertEquals("installer.apk", saved.name)
            assertEquals(1, transport.recorded.size)
            assertNotNull(transport.recorded.single().header("Authorization"))
            assertEquals(Fixtures.APP_NPUB, transport.recorded.single().header("x-flightdeck-pg-app-npub"))
            assertEquals(saved, sync.downloadForExport(connection, workspace, file, cache))
            assertEquals(1, transport.recorded.size)
            try { sync.downloadForExport(connection, workspace, file.copy(sha256 = "00".repeat(32)), cache); fail("changed hash") }
            catch (_: FileTransferError.Integrity) { }
            assertNull(cache.cached(file.copy(sha256 = "00".repeat(32))))
            val requests = transport.recorded.size
            try { sync.downloadForExport(connection, workspace, file.copy(accessRemoved = true), cache); fail("access lost") }
            catch (_: IllegalArgumentException) { }
            assertEquals(requests, transport.recorded.size)
        } finally { engine.close(); store.close(); cache.root.deleteRecursively() }
    }
    @Test fun richExportUsesExactSavedVersionInsteadOfOriginalObjectAndPreservesOldCache(): Unit = runBlocking {
        val original = "original HTML".toByteArray()
        val edited = "<h1>Saved on Android</h1>".toByteArray()
        val file = FileRecord(Fixtures.WORKSPACE_ID, Fixtures.newId(), null, Fixtures.CHANNEL_1, null, Fixtures.newId(),
            "saved.html", contentType = "text/html", sizeBytes = original.size.toLong(), sha256 = Sha256Hex.of(original), createdAt = "", updatedAt = "")
        val version = FileVersionRecord(Fixtures.newId(),file.id,2,Fixtures.newId(),edited.size.toLong(),"text/html",Sha256Hex.of(edited),null,null,null,"")
        val savedFile = file.copy(storageObjectId=version.storageObjectId,sha256=version.sha256,sizeBytes=version.sizeBytes,rowVersion=2)
        val workspace = WorkspaceRecord(file.workspaceId,Fixtures.APP_NPUB,"Synthetic",null,null,null,null,JsonObject(emptyMap()))
        val transport = StubTransport { request ->
            assertTrue("export must read the immutable saved version",request.url.endsWith("/versions/${version.id}/object"))
            200 to edited
        }
        val store=LocalStore(tempDir()); val engine=SyncEngine(); val cache=FileCache(tempDir())
        val connection=ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN),TestKeys.NPUB,null)
        val sync=WorkspaceSync(store,engine,makeIdentity(),transport)
        try {
            cache.store(original,file)
            val exported=sync.downloadForExport(connection,workspace,savedFile,cache,version)
            assertArrayEquals(edited,exported.readBytes())
            assertArrayEquals(original,cache.cached(file)!!.readBytes())
            assertEquals(1,transport.recorded.size)
            assertEquals(exported,sync.downloadForExport(connection,workspace,savedFile,cache,version))
            assertEquals(1,transport.recorded.size)
            assertTrue(runCatching { sync.downloadForExport(connection,workspace,savedFile.copy(accessRemoved=true),cache,version) }.isFailure)
            assertTrue(runCatching { sync.downloadForExport(connection,workspace,savedFile,cache,version.copy(fileId=Fixtures.newId())) }.isFailure)
            assertEquals(1,transport.recorded.size)
        } finally { engine.close(); store.close(); cache.root.deleteRecursively() }
    }

}
