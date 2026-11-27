package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI
import java.nio.file.Files

class HtmlAssetCacheTest {
    @Test fun privateImageHtmlLoadsThroughPayloadAndVerifiedCache() = runBlocking {
        val h = SampleHarness()
        val w = h.api.workspaces().first { it.id == SampleIds.STUDIO }
        val source = "<h1>Daily report</h1><img data-asset=\"chart.png\">"
        val objectId = h.api.store(w.id,h.app,source.toByteArray(),"text/html","report.html",null) { }
        val created = h.exchange("POST",h.base()+"/channels/${SampleIds.GENERAL}/files",mapOf("storage_object_id" to objectId,"display_name" to "Report","metadata" to ArtefactType.HTML.metadata()))
        val file = FileRecord.parse(created.json().getValue("file").jsonObject)
        val version = h.api.fileVersions(w.id,h.app,file.id).versions.single()
        val imageId = "33333333-3333-4333-8333-333333333333"
        val imageVersion = "44444444-4444-4444-8444-444444444444"
        val image = byteArrayOf(1,2,3,4)
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                if (path.endsWith("/files/$imageId/versions/$imageVersion/object")) return HttpResponse(200,image)
                val response = h.tower.send(request)
                if (!path.endsWith("/files/${file.id}/html")) return response
                val root = Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                val content = root.getValue("content").jsonObject
                val asset = buildJsonObject {
                    put("status","ready"); put("name","chart.png"); put("file_id",imageId); put("version_id",imageVersion)
                    put("content_type","image/png"); put("size_bytes",image.size); put("sha256",Sha256Hex.of(image))
                }
                return HttpResponse(200,JsonObject(root+ ("content" to JsonObject(content+("assets" to JsonArray(listOf(asset)))))).toString().toByteArray())
            }
        }
        val api = TowerAPI(TowerClient(h.tower.origin,transport,SampleSigners.primary))
        val payload = api.artefactHTMLPayload(w.id,h.app,file.id,version.id)
        assertTrue(payload.html.contains("data:image/png;base64,"))
        assertEquals(source,payload.sourceHtml)
        assertEquals(version.sha256,Sha256Hex.of(payload.sourceHtml.toByteArray()))
        val dir = Files.createTempDirectory("html-asset-cache").toFile()
        try {
            LocalStore(dir).use { store ->
                store.writer.cacheVerifiedArtefact(file) { true }
                val cache = LocalNappletContent(store,w,"owner") { true }
                cache.saveCode(file,version,payload.sourceHtml)
                assertEquals(source,cache.code(file.id)!!.html)
                assertTrue("Same-length source tampering must still fail its digest", runCatching { cache.saveCode(file,version,payload.sourceHtml.replace("Daily","Other")) }.isFailure)
                assertTrue("Rendered bootstrap must never pass as saved source",runCatching { cache.saveCode(file,version,payload.html) }.isFailure)
            }
        } finally { dir.deleteRecursively() }
    }
}
