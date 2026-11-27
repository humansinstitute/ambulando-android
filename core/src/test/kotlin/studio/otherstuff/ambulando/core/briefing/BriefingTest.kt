package studio.otherstuff.ambulando.core.briefing

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.*
import java.time.*

class BriefingTest {
    private val bytes=javaClass.getResourceAsStream("/briefing/v1.json")!!.readBytes()
    private val raw=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    private val workspace=raw.string("workspace_id")!!; private val owner=raw.string("owner_npub")!!; private val author=raw.string("author_npub")!!
    @Test fun fixedPerthDayAndMorningBoundary() {
        assertEquals("2026-11-26",Briefing.today(Instant.parse("2026-11-26T15:59:59Z")).toString())
        assertEquals("2026-11-27",Briefing.today(Instant.parse("2026-11-26T16:00:00Z")).toString())
        assertTrue(Briefing.pending(Instant.parse("2026-11-26T21:29:59Z")))
        assertFalse(Briefing.pending(Instant.parse("2026-11-26T21:30:00Z")))
    }
    @Test fun sharedFixtureReadsSavedHistoricalAnalysis() {
        val b=Briefing.parse(bytes,workspace,owner,author)
        assertEquals(2,b.entries.getValue(LocalDate.parse("2026-11-27")).paragraphs.size)
        assertEquals("Synthetic task",b.entries.values.single().links.single().label)
        assertEquals(Instant.parse("2026-11-26T21:30:00Z"),b.entries.values.single().generatedAt)
    }
    @Test fun ownerWorkspaceAndAuthorMustMatch() {
        for(args in listOf(listOf(workspace,author,owner),listOf("44444444-4444-4444-8444-444444444444",owner,author))) {
            assertThrows(IllegalArgumentException::class.java) { Briefing.parse(bytes,args[0],args[1],args[2]) }
        }
    }
    @Test fun timestampCannotDriftIntoAnotherPerthDay() {
        val corrupt=bytes.toString(Charsets.UTF_8).replace("2026-11-26T21:30:00Z","2026-11-26T15:30:00Z")
        assertThrows(IllegalArgumentException::class.java) { Briefing.parse(corrupt.toByteArray(),workspace,owner,author) }
    }
    @Test fun ownerAndAuthorOnlyNoKindAssumption() {
        val members=listOf(MemberRecord(workspace,"o",owner,"human",null),MemberRecord(workspace,"a",author,"human",null))
        fun grant(id:String,level:String,type:String="actor")=buildJsonObject { put("principal_type",type);put("principal_id",id);put("access_level",level);put("permissions",JsonArray(listOf("channel.read","file.read","file.write","channel.manage","channel.grants.manage").map(::JsonPrimitive))) }
        val grants=listOf(grant("o","manage"),grant("a","contribute"))
        assertTrue(BriefingPrivacy.grants(grants,members,owner,author))
        val live=grants.map { JsonObject(it+mapOf("principal_type" to JsonPrimitive("person"),"stored_principal_type" to JsonPrimitive("actor"))) }
        assertTrue(BriefingPrivacy.grants(live,members,owner,author))
        val custom=live.map {JsonObject(it+("access_level" to JsonPrimitive("custom")))}
        assertTrue(BriefingPrivacy.grants(custom,members,owner,author))
        for(scopes in listOf(emptyList(),listOf("file.read"),listOf("file.write"),listOf("channel.read","file.read"))) {
            val incomplete=JsonObject(custom[1]+("permissions" to JsonArray(scopes.map(::JsonPrimitive))))
            assertFalse(BriefingPrivacy.grants(listOf(custom[0],incomplete),members,owner,author))
        }
        assertFalse(BriefingPrivacy.grants(listOf(custom[0],JsonObject(custom[1]-"permissions")),members,owner,author))
        assertFalse(BriefingPrivacy.grants(custom+custom[1],members,owner,author))
        assertFalse(BriefingPrivacy.grants(custom,members+members[0],owner,author))
        assertFalse(BriefingPrivacy.grants(custom,members,owner,owner))
        assertFalse(BriefingPrivacy.grants(grants+grant("g","read","group"),members,owner,author))
        assertFalse(BriefingPrivacy.grants(grants+grant("other","read"),members,owner,author))
        assertFalse(BriefingPrivacy.grants(grants.take(1),members,owner,author))
    }
}
