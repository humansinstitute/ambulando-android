package studio.otherstuff.ambulando.core.briefing

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.*
import java.time.*

/** Immutable dated agent analysis, never inferred from the current goals. Format owned by Ambulando. */
data class Briefing(val workspace: String, val owner: String, val author: String, val entries: Map<LocalDate, BriefingEntry>, val location: BriefingLocation? = null, val authorName: String? = null) {
    companion object {
        const val MIME = "application/vnd.ambulando.briefing+json"
        const val SYSTEM_KEY = "ambulando.briefings"
        const val MAX_BYTES = 4 * 1024 * 1024
        val ZONE: ZoneId = ZoneId.of("Australia/Perth")
        fun today(now: Instant = Instant.now()): LocalDate = now.atZone(ZONE).toLocalDate()
        fun pending(now: Instant = Instant.now()): Boolean = now.atZone(ZONE).toLocalTime().isBefore(LocalTime.of(5,30))
        fun parse(bytes: ByteArray, workspace: String, owner: String, author: String): Briefing {
            require(bytes.size <= MAX_BYTES) { "This briefing is too large." }
            val root = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()).jsonObject
            require(root.string("schema") == "ambulando.briefing" && root["schema_version"]?.jsonPrimitive?.let { !it.isString && it.intOrNull==1 } == true && root.string("timezone") == ZONE.id) { "This briefing format is not supported." }
            require(root.string("workspace_id") == workspace && lowerUUID(workspace) == workspace && root.string("owner_npub") == owner && root.string("author_npub") == author && owner != author && NostrKeys.publicKeyHex(owner) != null && NostrKeys.publicKeyHex(author) != null) { "This briefing belongs to another owner." }
            val entries = root.getValue("entries").jsonObject
            require(entries.size <= 3660) { "Briefing history needs maintenance." }
            return Briefing(workspace, owner, author, entries.map { (day, raw) ->
                val date = LocalDate.parse(day); require(date.toString() == day)
                val e=raw.jsonObject
                val timestamp=e.string("generated_at") ?: error("Briefing timestamp is missing.")
                require(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?(?:Z|[+-]\\d\\d:\\d\\d)").matches(timestamp))
                val generated=OffsetDateTime.parse(timestamp).toInstant()
                require(today(generated)==date) { "Briefing date does not match its Perth timestamp." }
                val paragraphs=e.getValue("paragraphs").jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
                require(paragraphs.size in 1..2 && paragraphs.all { it.isNotBlank() && it.length <= 4000 })
                val links=e.getValue("links").jsonArray.map { item ->
                    val l=item.jsonObject; val type=l.string("type") ?: ""; val id=l.string("record_id") ?: ""; val channel=l.string("channel_id"); require(!l.containsKey("channel_id") || channel!=null); val label=l.string("label") ?: ""
                    require(type in setOf("task","thread","doc","file") && lowerUUID(id)==id && label.isNotBlank() && label.length<=160 && (channel==null || lowerUUID(channel)==channel) && (type!="thread" || channel!=null))
                    BriefingLink(type,id,channel,label)
                }
                require(links.size<=20)
                val coverage=e.string("coverage_note"); require(!e.containsKey("coverage_note") || coverage!=null); require(coverage==null || coverage.isNotBlank()&&coverage.length<=1000)
                date to BriefingEntry(generated,paragraphs,links,coverage)
            }.toMap())
        }
    }
}
data class BriefingEntry(val generatedAt: Instant,val paragraphs: List<String>,val links: List<BriefingLink>,val coverageNote: String? = null)
data class BriefingLink(val type: String,val recordId: String,val channelId: String?,val label: String)

/** Metadata can locate a record, but never grants access. */
object BriefingPrivacy {
    fun owns(channel: ChannelRecord, viewer: String): Boolean = channel.kind == "channel" && !channel.accessRemoved && active(channel.raw) && channel.raw.obj("metadata")?.let { m ->
        m.string("system_key")==Briefing.SYSTEM_KEY && m.obj("briefing")?.let { it["schema_version"]?.jsonPrimitive?.intOrNull==1 && it.string("owner_npub")==viewer }==true
    }==true
    fun active(raw: JsonObject): Boolean = raw.string("record_state") !in setOf("deleted","archived") && raw.string("deleted_at")==null && raw.string("archived_at")==null
    fun grants(grants: List<JsonObject>, members: List<MemberRecord>, owner: String, author: String): Boolean {
        val o=members.singleOrNull { it.npub==owner } ?: return false
        val a=members.singleOrNull { it.npub==author } ?: return false
        if(owner==author || o.actorId.isBlank() || a.actorId.isBlank() || o.actorId==a.actorId) return false
        var own=false; var agent=false
        val seen=HashSet<String?>()
        for(g in grants) {
            val p=g.obj("principal"); val id=p?.string("actor_id") ?: p?.string("id") ?: g.string("principal_id")
            if(!(g.string("principal_type")=="actor" || g.string("principal_type")=="person" && g.string("stored_principal_type")=="actor") || p?.string("type")?.let {it !in setOf("actor","person")}==true || g.string("stored_principal_type")?.let {it!="actor"}==true) return false
            if(!seen.add(id) || listOfNotNull(p?.get("actor_id"),p?.get("id"),g["principal_id"]).any {it !is JsonPrimitive || !it.isString || it.content!=id}) return false
            if(p?.containsKey("npub")==true && p.string("npub")!=if(id==o.actorId)owner else author) return false
            val permissions=(g["permissions"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf {v->v.isString}?.content ?: return false } ?: return false
            val required=setOf("channel.read","file.read","file.write") + if(id==o.actorId)setOf("channel.manage","channel.grants.manage") else emptySet()
            if(!permissions.containsAll(required)) return false
            when {
                id==o.actorId -> own=true
                id==a.actorId -> agent=true
                else -> return false
            }
        }
        return own&&agent
    }
}

data class BriefingLocation(val channelId: String,val fileId: String,val author: String)
