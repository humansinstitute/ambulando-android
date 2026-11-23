package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import java.time.Instant

/** Bounded generic administrative metrics. Metric IDs are data, never host dispatch or network authority. */
object WorkspaceStorage {
    private val identifier=Regex("[a-z][a-z0-9_-]{0,63}")
    private fun id(o: JsonObject,key: String): String = WorkspaceReport.text(o,key).also { require(identifier.matches(it)) }
    private fun timestamp(value: JsonElement) {
        val s=(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid timestamp")
        require(s.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")))
        require(java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.parse(s))==s)
    }
    fun response(response: JsonObject,identity: JsonObject,workspace: String,range: String): JsonObject {
        require(identity["workspace_id"]==JsonPrimitive(workspace) && response["identity"]==identity)
        return model(JsonObject(response.filterKeys { it!="identity" }),workspace,range)
    }
    fun model(value: JsonObject,workspace: String,range: String): JsonObject {
        require(value.toString().toByteArray().size<=65536)
        val base=WorkspaceReport.model(JsonObject(value.filterKeys { it!="storage" }),workspace,range)
        val s=value.getValue("storage").jsonObject
        require(s.toString().toByteArray().size<=65536)
        WorkspaceReport.exact(s,"format","workspace_id","scope","as_of","activity_range_applies","groups","provider_measured_at","last_object_completed_at","caveats")
        require(s["format"]==JsonPrimitive("workspace-storage-v1") && s["workspace_id"]==JsonPrimitive(workspace))
        require(s["scope"]==JsonPrimitive("current-workspace") && s["as_of"]==base["as_of"] && s["activity_range_applies"]==JsonPrimitive(false))
        val groups=s.getValue("groups").jsonArray
        require(groups.size<=8)
        val groupIds=mutableSetOf<String>()
        groups.forEach { item ->
            val g=item.jsonObject; WorkspaceReport.exact(g,"id","metrics"); require(groupIds.add(id(g,"id")))
            val metrics=g.getValue("metrics").jsonArray; require(metrics.size<=32)
            val metricIds=mutableSetOf<String>()
            metrics.forEach { item ->
                val m=item.jsonObject; WorkspaceReport.exact(m,"id","value","unit","method","status")
                require(metricIds.add(id(m,"id"))); id(m,"method")
                require(WorkspaceReport.text(m,"unit") in setOf("bytes","count"))
                val status=WorkspaceReport.text(m,"status"); require(status in setOf("estimated","actual","unavailable"))
                val number=m.getValue("value")
                if(status=="unavailable") require(number==JsonNull)
                else {
                    val p=number as? JsonPrimitive ?: error("Invalid metric")
                    val n=p.takeUnless { it.isString }?.doubleOrNull ?: error("Invalid metric")
                    require(n.isFinite() && n>=0 && n<=9007199254740991.0 && n%1.0==0.0 && n.toRawBits()!=(-0.0).toRawBits())
                }
            }
        }
        listOf("provider_measured_at","last_object_completed_at").forEach { key ->
            val date=s.getValue(key)
            if(date!=JsonNull) { timestamp(date); require(Instant.parse(date.jsonPrimitive.content)<=Instant.parse(WorkspaceReport.text(base,"as_of"))) }
        }
        val caveats=s.getValue("caveats").jsonArray; require(caveats.size<=16)
        caveats.forEach { item ->
            val text=(item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid caveat")
            require(GenericJson.scalar(text) && text.length<=1024 && text.none { it.code<32 || it.code in 127..159 })
        }
        return value
    }
}
