package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Fixed synthetic acceptance fixture. Installed explicitly by tests; never claims live workspace counts. */
object SampleWorkspaceReport {
    fun value(workspace: String, range: String, identity: JsonObject? = null): JsonObject {
        val end=Instant.parse("2026-11-22T12:00:00.000Z")
        val date=DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        return buildJsonObject {
            if(identity!=null) put("identity",identity)
            put("format","workspace-report-v1"); put("workspace_id",workspace); put("range",range)
            put("from",if(range=="all") JsonNull else JsonPrimitive(date.format(end.minusSeconds(range.removeSuffix("d").toLong()*86400))))
            put("to",date.format(end)); put("as_of",date.format(end)); put("timezone","UTC"); put("complete",true); put("channel_policy","active-project-channels")
            put("activity",buildJsonObject { put("messages",42); put("conversations",9) })
            put("inventory",buildJsonObject {
                put("channels",12); put("conversations",12); put("documents",5); put("files",6)
                put("tasks",buildJsonObject {
                    put("total",12); put("open",7); put("completed",3); put("archived",2)
                    put("by_state",buildJsonObject { put("new",1); put("ready",1); put("in_progress",2); put("review",1); put("done",3); put("archive",1); put("backlog",1); put("blocked",1); put("archived",1) })
                })
            })
            put("channels",JsonArray(listOf(buildJsonObject {
                put("channel_id",SampleIds.GENERAL); put("scope_id",SampleIds.SCOPE_AMBULANDO); put("name","General (synthetic)"); put("scope_name","Studio (synthetic)"); put("messages",20); put("conversations",5)
            })+(1..9).map { n -> buildJsonObject {
                put("channel_id","10000000-0000-4000-8000-${n.toString().padStart(12,'0')}"); put("scope_id",SampleIds.SCOPE_AMBULANDO)
                put("name","Channel $n (synthetic)"); put("scope_name","Studio (synthetic)"); put("messages",2); put("conversations",0)
            } }))
            put("ranking",buildJsonObject { put("total_channels",12); put("returned_channels",10); put("has_more",true); put("other_messages",4); put("other_conversations",4) })
            put("coverage",buildJsonObject { put("tasks_channels",1); put("documents_channels",12); put("files_channels",12) })
        }
    }
}
