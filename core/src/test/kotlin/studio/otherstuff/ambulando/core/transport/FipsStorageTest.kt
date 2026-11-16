package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.support.*

class FipsStorageTest {
    private val peer = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    private val origin = TowerOrigin("http://$peer.fips:4443")
    @Test fun uploadsStayOnSignedPeerEvenWhenMetadataOffersExternalHttps() = runBlocking {
        val transport = StubTransport { 200 to emptyMap<String, Any>() }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val id = "11111111-2222-3333-4444-555555555555"
        api.upload(PreparedObject(id, "https://objects.example/bucket/file?token=x"), "data".toByteArray(), "text/plain")
        val request = transport.recorded.single()
        assertEquals("PUT", request.method)
        assertEquals(origin.url + "/api/v4/storage/" + id, request.url)
        assertNotNull(request.header("Authorization"))
        assertTrue(decodeAuthorization(request).tags.contains(listOf("u", request.url)))
    }
    @Test fun externalDownloadsAndOversizedUploadsCannotEscapeGrant() = runBlocking {
        val transport = StubTransport { error("No request expected") }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        assertNull(api.presignedDownloadURL("11111111-2222-3333-4444-555555555555"))
        try { TransferPolicy.validate("https://objects.example/x", origin.host); fail("Expected rejection") } catch (_: TransferError.UnsafeURL) {}
        try { api.upload(PreparedObject("11111111-2222-3333-4444-555555555555", "https://objects.example/x"), ByteArray(FALLBACK_UPLOAD_LIMIT + 1), "x"); fail("Expected size limit") } catch (_: FileTransferError.FallbackTooLarge) {}
        assertTrue(transport.recorded.isEmpty())
    }
    @Test fun protectedAliasesCannotEnterAnyOrdinaryFileRoute() = runBlocking {
        val transport = StubTransport { error("No network request expected") }
        val client = TowerClient(TowerOrigin("https://tower.example"), transport, makeIdentity())
        val api = TowerAPI(client)
        val id = "11111111-2222-3333-4444-555555555555"
        for (host in listOf("$peer.FIPS", "$peer.fips.", "$peer.%66ips", "%6e${peer.drop(1)}.fips", "$peer．fips", "$peer.ｆｉｐｓ")) {
            val url = "https://$host:4443/file?token=x"
            try { TransferPolicy.validate("https://objects.example/file", host); fail("FIPS-origin external transfer must reject") } catch (_: TransferError.UnsafeURL) {}
            try { TransferPolicy.validate(url, null); fail("background upload/download must reject") } catch (_: TransferError.UnsafeURL) {}
            try { client.putUnsigned(url, byteArrayOf(1), "x"); fail("unsigned upload must reject") } catch (_: FileTransferError.UnsafeUploadURL) {}
            try { api.upload(PreparedObject(id, url), byteArrayOf(1), "x"); fail("prepared upload must reject") } catch (_: FileTransferError.UnsafeUploadURL) {}
            try { TowerOrigin("https://$host:4443"); fail("origin must reject") } catch (_: TowerError) {}
        }
        assertTrue(transport.recorded.isEmpty())
    }

}
