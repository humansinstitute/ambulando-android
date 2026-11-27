package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

class LocalNappletContentTest {
    @Test fun codeAndDataSurviveStoreReopenWithIsolationCorruptionVersionAndRemovalChecks() = runBlocking {
        val h=SampleHarness(); val w=h.api.workspaces().first { it.id==SampleIds.STUDIO }
        val created=h.api.createDailyTracker(w.id,w.appNpub,TestKeys.NPUB,"<h1>Verified cached code</h1>")
        val file=created.code
        val version=h.api.genericNappletHead(w.id,w.appNpub,file.id).versions.single()
        val dir=Files.createTempDirectory("napplet-content").toFile()
        var unlocked=true
        try {
            LocalStore(dir).use { store ->
                store.writer.cacheVerifiedArtefact(file) { true }
                val cache=LocalNappletContent(store,w,"owner") { unlocked }
                cache.saveCode(file,version,"<h1>Verified cached code</h1>")
                cache.put(file.id,"${version.id}:data",buildJsonObject {put("value",buildJsonObject {put("zero",0);put("answered",false)})})
            }
            h.tower.offline=true
            LocalStore(dir).use { store ->
                val cache=LocalNappletContent(store,w,"owner") { unlocked }
                cache.forgetCode(file.id,"11111111-1111-4111-8111-111111111111")
                assertEquals(version.id,cache.code(file.id)!!.version.id)
                assertEquals(JsonPrimitive(false),cache.get(file.id,"${version.id}:data")!!.getValue("value").jsonObject["answered"])
                assertNull(LocalNappletContent(store,w,"another-owner") {true}.code(file.id))
                assertNull(cache.get(file.id,"different-version:data"))
                unlocked=false;assertNull(cache.code(file.id));unlocked=true
                store.writer.setState("napplet.content.v1:owner:${w.appNpub}:${w.id}:${file.id}:code","{\"value\":{}}")
                assertNull(cache.code(file.id))
                cache.saveCode(file,version,"<h1>Verified cached code</h1>")
                val updated=FileRecord.parse(JsonObject(file.raw+mapOf("current_version_id" to JsonPrimitive("11111111-1111-4111-8111-111111111111"),"row_version" to JsonPrimitive(file.rowVersion+1))))
                store.writer.upsertFiles(w.id,listOf(updated));assertNull(cache.code(file.id))
                val newer=version.copy(id="11111111-1111-4111-8111-111111111111",versionNumber=version.versionNumber+1)
                cache.saveCode(updated,newer,"<h1>Verified cached code</h1>")
                cache.saveCode(file,version,"<h1>Verified cached code</h1>")
                assertEquals(newer.id,cache.code(file.id)!!.version.id)
                store.writer.markAccessRemoved(w.id,AccessTarget.File(file.id))
                assertNull(cache.get(file.id,"${version.id}:data"))
            }
        } finally {dir.deleteRecursively()}
    }
}
