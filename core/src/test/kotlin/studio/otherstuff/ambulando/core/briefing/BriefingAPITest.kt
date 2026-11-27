package studio.otherstuff.ambulando.core.briefing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.sample.SampleSigners
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class BriefingAPITest {
    private val bytes=javaClass.getResourceAsStream("/briefing/v1.json")!!.readBytes()
    private val d=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    private val w=d.string("workspace_id")!!; private val owner=d.string("owner_npub")!!;private val author=d.string("author_npub")!!
    private val s="44444444-4444-4444-8444-444444444444";private val dm="55555555-5555-4555-8555-555555555555"
    private val ch="66666666-6666-4666-8666-666666666666";private val f="77777777-7777-4777-8777-777777777777";private val v="88888888-8888-4888-8888-888888888888"
    private inner class Harness(val unsafe:Boolean=false,val corrupt:Boolean=false,val duplicate:Boolean=false,val denied:Boolean=false) {
        val paths=ArrayList<String>()
        val api=TowerAPI(TowerClient(TowerOrigin("https://synthetic.invalid"),object:HttpTransport {
            override suspend fun send(request:HttpRequest):HttpResponse {
                val p=URI(request.url).path;paths+=p
                fun json(value:JsonObject)=HttpResponse(200,value.toString().toByteArray())
                fun obj(vararg p:Pair<String,Any?>)=jsonObjectOf(*p)
                val meta=obj("briefing" to obj("schema_version" to 1,"owner_npub" to owner,"author_npub" to author))
                fun file()=obj("id" to f,"workspace_id" to w,"channel_id" to ch,"content_type" to Briefing.MIME,"metadata" to meta,"current_version_id" to v,"object" to obj("storage_object" to obj("is_public" to false,"size_bytes" to bytes.size,"sha256_hex" to Sha256Hex.of(bytes))))
                return when {
                    p.endsWith("/scopes") -> json(obj("scopes" to JsonArray(listOf(obj("id" to s,"workspace_id" to w,"kind" to "scope"),obj("id" to dm,"workspace_id" to w,"kind" to "dm")))))
                    p.endsWith("/channels/$ch") -> json(obj("channel" to obj("id" to ch,"workspace_id" to w,"scope_id" to s,"kind" to "channel","metadata" to JsonObject(meta+("system_key" to JsonPrimitive(Briefing.SYSTEM_KEY))))))
                    p.endsWith("/scopes/$s/channels") -> {
                        val channel=obj("id" to ch,"workspace_id" to w,"scope_id" to s,"kind" to "channel","metadata" to JsonObject(meta+("system_key" to JsonPrimitive(Briefing.SYSTEM_KEY))))
                        json(obj("channels" to JsonArray(if(duplicate)listOf(channel,channel) else listOf(channel))))
                    }
                    p.endsWith("/members") -> json(obj("members" to JsonArray(listOf(obj("actor" to obj("id" to "o","npub" to owner,"kind" to "human")),obj("actor" to obj("id" to "a","npub" to author,"kind" to "human"))))))
                    p.endsWith("/grants") -> json(obj("grants" to JsonArray(listOf(obj("principal_type" to "person","stored_principal_type" to "actor","principal_id" to "o","access_level" to "manage"),obj("principal_type" to (if(unsafe)"group" else "person"),"stored_principal_type" to "actor","principal_id" to "a","access_level" to "contribute")))))
                    p.endsWith("/channels/$ch/files") -> json(obj("files" to JsonArray(listOf(file()))))
                    p.endsWith("/files/$f") -> if(denied)HttpResponse(403,"{}".toByteArray()) else json(obj("file" to file()))
                    p.endsWith("/versions/$v/object") -> HttpResponse(200,if(corrupt)"{}".toByteArray() else bytes)
                    else -> error("Unexpected route $p")
                }
            }
        },SampleSigners.primary))
    }
    @Test fun currentVersionReadsWithoutHistoryOrNotesToSelf()= runBlocking {
        val h=Harness();assertNotNull(h.api.personalBriefing(w,"app",owner))
        assertFalse(h.paths.any {it.contains("/scopes/$dm/")||it.endsWith("/versions")})
        assertEquals(2,h.paths.count {it.endsWith("/grants")})
    }
    @Test fun pinnedRefreshDoesNotRescanUnrelatedScopesOrFiles()=runBlocking {
        val h=Harness();val first=h.api.personalBriefing(w,"app",owner)!!;h.paths.clear()
        assertNotNull(h.api.personalBriefing(w,"app",owner,first.location))
        assertFalse(h.paths.any {it.endsWith("/scopes")||it.endsWith("/files")})
        assertTrue(h.paths.any {it.endsWith("/channels/$ch")})
    }
    @Test fun unsafeGrantsFailBeforeFileContent()= runBlocking {
        val h=Harness(unsafe=true)
        assertThrows(IllegalArgumentException::class.java){runBlocking {h.api.personalBriefing(w,"app",owner)}}
        assertFalse(h.paths.any {it.endsWith("/object")||it.endsWith("/files")})
    }
    @Test fun duplicateSpacesAndCorruptOrDeniedContentAreUnavailable()= runBlocking {
        for(h in listOf(Harness(duplicate=true),Harness(corrupt=true),Harness(denied=true))) {
            assertThrows(Exception::class.java){runBlocking {h.api.personalBriefing(w,"app",owner)}}
        }
    }
}
