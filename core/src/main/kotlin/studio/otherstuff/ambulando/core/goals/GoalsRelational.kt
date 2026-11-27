package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID
import java.time.Instant

/** App-owned adapter over NStack native-v1. Scope/ACL/revision are always enforced by NStack. */
class GoalsRelational(private val api: TowerAPI, private val owner: String, private val workspace: String, private val app: String,
    private val viewer: String, private val rememberAuthority: suspend () -> Unit = {}, private val hadAuthority: suspend () -> Boolean = { false }, private val current: () -> Boolean = { true },
    private val loadPending: suspend () -> JsonObject? = { null },
    private val savePending: suspend (JsonObject?) -> Unit = {}) {
    companion object { private val mutationLocks=ConcurrentHashMap<String,Mutex>() }
    init { require(owner.startsWith("npub1") && owner.all { it.isLetterOrDigit() }); TowerAPI.uuid(workspace) }
    private val base = "/api/v4/workspaces/$owner/apps/$app/db/native-v1/$workspace"
    private suspend fun request(action: String, body: JsonObject): JsonObject {
        check(current()) { "Unlock the current workspace to open goals." }
        return api.obj(api.client.request(method="POST",path="$base/$action",appNpub=app,jsonBody=body.toString().toByteArray()), "relational goals").also { check(current()) }
    }
    /** Missing capability before cutover permits legacy; unavailable after observed SQL authority never downgrades. */
    suspend fun mode(channel: String): String {
        val reply = try { request("status", jsonObjectOf("channel_id" to channel,"content_type" to Goal.MIME)) }
        catch(e: TowerError.Http) {
            if(e.status==404 && !hadAuthority()) return "legacy"
            if(e.status==404) throw GoalSaveFailed("Goals storage is unavailable. Your saved content is retained.",true)
            throw e
        }
        if(flexibleInt(reply["version"])!=1) throw TowerError.Malformed("goals authority")
        val authority=reply.obj("authority")
        if(authority==null) { if(hadAuthority()) throw GoalSaveFailed("Goals storage is unavailable. Your saved content is retained.",true); return "legacy" }
        val mode=authority.string("mode") ?: throw TowerError.Malformed("goals authority")
        if(mode !in setOf("staging","active","frozen")) throw TowerError.Malformed("goals authority")
        if(mode=="active" || mode=="frozen") rememberAuthority()
        if(mode=="staging" && reply.obj("fence")?.bool("active")!=true && !hadAuthority()) return "legacy"
        return if(mode=="active") "relational" else "frozen"
    }
    data class Snapshot(val generation:String,val tables:Map<String,List<JsonObject>>)
    suspend fun snapshot(channel: String): Snapshot {
        repeat(3) { attempt ->
            try {
                var generation:String?=null;val tables=linkedMapOf<String,List<JsonObject>>()
                for(table in listOf("goals_authority","goals","goal_checkpoints","goal_associations")) {
                    val rows=mutableListOf<JsonObject>();var after:String?=null;val seen=hashSetOf<String>()
                    do {
                        val body=buildJsonObject { put("version",1);put("channel_id",channel);put("table",table);put("limit",200)
                            generation?.let{put("generation",it)};after?.let{put("after",it)} }
                        val page=request("query",body)
                        if(flexibleInt(page["version"])!=1) throw TowerError.Malformed("goals contract version")
                        val nextGeneration=page.string("generation") ?: throw TowerError.Malformed("goals generation")
                        if(generation!=null && generation!=nextGeneration) throw GoalSnapshotChanged()
                        generation=nextGeneration
                        val entries=page.objects("rows") ?: throw TowerError.Malformed("goals rows")
                        entries.forEach { row ->
                            if(row.string("workspace_id")!=workspace || row.string("channel_id")!=channel || row.string("id").isNullOrEmpty() || (flexibleLong(row["revision"])?:0)<=0 || !seen.add(row.string("id") ?: "")) throw TowerError.Malformed("goals scope/cursor")
                            rows+=row
                        }
                        val more=page.bool("has_more") ?: throw TowerError.Malformed("goals cursor")
                        val next=page.string("next_after")
                        if(more && (next.isNullOrEmpty() || next==after || entries.isEmpty())) throw TowerError.Malformed("goals cursor")
                        after=if(more)next else null
                        if(rows.size>100_000) throw TowerError.Malformed("goals snapshot limit")
                    }while(after!=null)
                    tables[table]=rows
                }
                request("query",jsonObjectOf("version" to 1,"channel_id" to channel,"table" to "goals","limit" to 1,"generation" to generation))
                return Snapshot(requireNotNull(generation),tables)
            }catch(e: TowerError.Http){if(e.status==404)throw GoalSaveFailed("Goals storage is unavailable. Your saved content is retained.",true);if(e.code!="snapshot_changed" || attempt==2)throw e}
            catch(e: GoalSnapshotChanged){if(attempt==2)throw GoalSaveFailed("Goals changed during loading. Try again.",true)}
        }
        error("unreachable")
    }
    fun documents(snapshot:Snapshot):List<GoalDocument> {
        val marker=snapshot.tables["goals_authority"]?.singleOrNull() ?: throw GoalError.Invalid("Goals migration metadata is unavailable. Your saved content is retained.")
        if(flexibleInt(marker["contract_version"])!=1 || marker.string("schema_version")!="ambulando-goals-relational-1") throw GoalError.Unsupported
        val parents=snapshot.tables.getValue("goals")
        val ids=parents.map{it.string("id")}.toSet()
        for(table in listOf("goal_checkpoints","goal_associations")) if(snapshot.tables.getValue(table).any{it.string("goal_id") !in ids}) throw TowerError.Malformed("orphan goal rows")
        return parents.filter{it["tombstoned_at"]==null || it["tombstoned_at"] is JsonNull}.map { row ->
            if(row.string("owner_npub")!=viewer)throw TowerError.Malformed("goal owner")
            val id=row.string("id") ?: throw TowerError.Malformed("goal id");TowerAPI.uuid(id)
            val cps=snapshot.tables.getValue("goal_checkpoints").filter{it.string("goal_id")==id}.sortedBy{flexibleInt(it["position"]) ?: -1}
            val refs=snapshot.tables.getValue("goal_associations").filter{it.string("goal_id")==id}
            fun links(lane:String)=JsonArray(refs.filter{it.string("lane")==lane}.sortedBy{flexibleInt(it["position"]) ?: -1}.map { r -> buildJsonObject {
                extensions(r).forEach{(k,v)->put(k,v)};put("type",r.getValue("kind"));put("record_id",r.getValue("record_id"));r.string("target_channel_id")?.let{put("channel_id",it)}
            } })
            val root=buildJsonObject {
                extensions(row).forEach{(k,v)->put(k,v)};put("schema",Goal.SCHEMA);put("schema_version",1)
                for(k in listOf("name","description","status","start_date","target_date","focus_rank","default_channel_id")) row[k]?.takeIf{it !is JsonNull}?.let{put(k,it)}
                put("checkpoints",JsonArray(cps.map { c -> buildJsonObject {
                    extensions(c).forEach{(k,v)->put(k,v)};put("id",c.getValue("checkpoint_id"));put("title",c.getValue("title"));put("reached",c.getValue("reached"))
                    for(k in listOf("date","description")) c[k]?.takeIf{it !is JsonNull}?.let{put(k,it)}
                } }));put("links",links("link"));put("pinned_refs",links("pin"))
            }
            val goal=Goal.parse(root.toString().toByteArray());val revision=flexibleLong(row["revision"])?.takeIf{it>0} ?: throw TowerError.Malformed("goal revision")
            val raw=buildJsonObject {
                put("id",id);put("workspace_id",workspace);put("channel_id",row.getValue("channel_id"));put("display_name",GoalFiles.fileName(goal));put("content_type",Goal.MIME)
                put("metadata",GoalFiles.metadata());put("row_version",revision);put("created_at",row.getValue("created_at"));put("updated_at",row.getValue("updated_at"));put("goals_authority","relational")
            }
            GoalDocument(FileRecord.parse(raw),"sql:$revision",goal)
        }
    }
    suspend fun list(channel:String):List<GoalDocument> = documents(snapshot(channel))
    suspend fun read(id:String):GoalDocument {
        val space=api.goalsSpace(workspace,app,viewer) as? GoalsSpaceState.Ready ?: throw GoalError.Invalid("Set up goals first.")
        return when(mode(space.channel.id)) {
            "legacy" -> api.readLegacyGoal(workspace,app,id)
            "relational" -> {
                val found=try {request("read",jsonObjectOf("version" to 1,"table" to "goals","id" to TowerAPI.uuid(id)))}
                catch(e:TowerError.Http){if(e.status==404&&e.code=="row_not_found")throw GoalError.Invalid("This goal is unavailable.");throw e}
                val row=found.obj("row") ?: throw TowerError.Malformed("goal reference")
                if(flexibleInt(found["version"])!=1 || found.string("channel_id")!=space.channel.id || row.string("workspace_id")!=workspace || row.string("id")!=id)throw GoalError.Invalid("This goal is unavailable in your Goals space.")
                list(space.channel.id).firstOrNull{it.id==id} ?: throw GoalError.Invalid("This goal is unavailable.")
            }
            else -> throw GoalSaveFailed("Goals are being migrated. Your saved content is retained.",true)
        }
    }
    suspend fun create(channel:String,goal:Goal):GoalDocument = when(mode(channel)) {
        "legacy"->api.createLegacyGoal(workspace,app,channel,goal)
        "relational"->{verifyNewTargets(null,goal);val id=UUID.randomUUID().toString();val rows=rows(goal,id,channel,1);submit(channel,insert(rows));list(channel).first{it.id==id}}
        else->throw GoalSaveFailed("Goals are read-only during migration.",true)
    }
    suspend fun edit(id:String,edits:List<GoalEdit>,attempts:Int=4):GoalDocument {
        val space=api.goalsSpace(workspace,app,viewer) as? GoalsSpaceState.Ready ?: throw GoalError.Invalid("Set up goals first.")
        when(mode(space.channel.id)) {"legacy"->return api.editLegacyGoal(workspace,app,id,edits,attempts);"relational"->Unit;else->throw GoalSaveFailed("Goals are read-only during migration.",true)}
        repeat(attempts) {
            val before=snapshot(space.channel.id);val doc=documents(before).firstOrNull{it.id==id} ?: throw GoalError.Invalid("This goal is unavailable.")
            val next=GoalEdit.applyAll(doc.goal,edits);if(next==doc.goal)return doc
            verifyNewTargets(doc.goal,next)
            val parent=before.tables.getValue("goals").first{it.string("id")==id};val revision=requireNotNull(flexibleLong(parent["revision"]))
            val after=rows(next,id,space.channel.id,revision+1,parent.string("created_at"))
            val ops=mutableListOf<JsonObject>()
            for(table in listOf("goals","goal_checkpoints","goal_associations")) {
                val old=before.tables.getValue(table).filter{if(table=="goals")it.string("id")==id else it.string("goal_id")==id}.associateBy{it.string("id")}.toMutableMap()
                for(row in after.getValue(table)) {
                    val prior=old.remove(row.string("id"));ops+=operation(if(prior==null)"insert" else "update",table,row,prior?.let{flexibleLong(it["revision"])})
                }
                old.values.forEach{ops+=operation("delete",table,it,flexibleLong(it["revision"]))}
            }
            ops+=insert(after.filterKeys{it.endsWith("history")})
            try {submit(space.channel.id,ops);return list(space.channel.id).first{it.id==id}}
            catch(e:TowerError.Http){if(e.code!="row_conflict" || it==attempts-1)throw e}
        }
        throw GoalSaveFailed("Goals changed elsewhere. Try again.",true)
    }

    private suspend fun verifyNewTargets(before:Goal?,after:Goal) {
        check(current())
        val previous=(before?.links.orEmpty()+before?.pinnedRefs.orEmpty()).map{it.type to it.recordId}.toSet()
        for(link in (after.links+after.pinnedRefs).filter{(it.type to it.recordId) !in previous}) {
            val (path,key)=when(link.type) {
                GoalLinkType.CHAT->"threads" to "thread";GoalLinkType.TASK->"tasks" to "task";GoalLinkType.DOC->"docs" to "doc";GoalLinkType.FILE->"files" to "file"
            }
            val row=api.obj(api.client.request(path=TowerAPI.base(workspace)+"/$path/"+TowerAPI.uuid(link.recordId),appNpub=app),"goal reference").obj(key)
                ?: throw GoalError.Invalid("This reference is unavailable.")
            check(current())
            if(row.string("id")!=link.recordId || row.string("workspace_id")!=workspace || row.string("deleted_at")!=null || row.string("archived_at")!=null || row.string("record_state") in setOf("deleted","archived") || link.channelId?.let{it!=row.string("channel_id")}==true)
                throw GoalError.Invalid("This reference is unavailable.")
        }
        if(after.defaultChannelId!=null && after.defaultChannelId!=before?.defaultChannelId) {
            val row=api.obj(api.client.request(path=TowerAPI.base(workspace)+"/channels/"+TowerAPI.uuid(after.defaultChannelId),appNpub=app),"goal discussion channel").obj("channel")
                ?: throw GoalError.Invalid("This discussion channel is unavailable.")
            check(current())
            if(row.string("id")!=after.defaultChannelId || row.string("workspace_id")!=workspace || row.string("deleted_at")!=null || row.string("archived_at")!=null || row.string("record_state") in setOf("deleted","archived"))throw GoalError.Invalid("This discussion channel is unavailable.")
        }
    }
    private fun extensions(row:JsonObject):JsonObject = row.string("extensions")?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    } ?: throw TowerError.Malformed("goal extensions")

    private fun operation(kind:String,table:String,row:JsonObject,expected:Long?=null)=buildJsonObject {
        put("kind",kind);put("table",table);put("id",row.getValue("id"));expected?.let{put("expected_revision",it)}
        if(kind!="delete") put("data",JsonObject(row.filterKeys{it !in setOf("id","workspace_id","channel_id","revision") && !(kind=="update" && it in setOf("goal_id","created_at"))}))
    }
    private fun insert(rows:Map<String,List<JsonObject>>)=rows.flatMap{(table,values)->values.map{operation("insert",table,it)}}
    internal suspend fun submit(channel:String,ops:List<JsonObject>) = mutationLocks.getOrPut("$base:$viewer") { Mutex() }.withLock {
        val body=buildJsonObject{put("version",1);put("channel_id",channel);put("operation_id",UUID.randomUUID().toString());put("operations",JsonArray(ops))}
        // Pending bytes survive process death and are replayed under the same scoped signer and operation ID.
        loadPending()?.let { pending ->
            request("batch",pending);savePending(null)
            throw GoalSaveFailed("The previous save was recovered. Reload goals before making another change.",false)
        }
        savePending(body)
        try { request("batch",body);savePending(null) }
        catch(e:TowerError.Http) {
            if(e.code=="row_conflict" || e.status in setOf(400,422)) savePending(null)
            throw e
        }
    }
    private fun rows(goal:Goal,id:String,channel:String,revision:Long,createdAt:String?=null):Map<String,List<JsonObject>> {
        goal.validated();val now=Instant.now().toString();val scope=jsonObjectOf("workspace_id" to workspace,"channel_id" to channel,"owner_npub" to viewer,"goal_id" to id,"revision" to revision)
        val g=goal.toJson();val known=setOf("schema","schema_version","name","description","status","start_date","target_date","focus_rank","default_channel_id","checkpoints","links","pinned_refs")
        val parent=buildJsonObject {
            scope.filterKeys{it!="goal_id"}.forEach{(k,v)->put(k,v)};put("id",id)
            for(k in listOf("name","description","status","start_date","target_date","default_channel_id")) put(k,g[k]?:JsonNull)
            val rank=GoalFocus.rank(goal);put("focus_rank",rank?.let(::JsonPrimitive)?:JsonNull)
            put("extensions",JsonObject(goal.extra.filterKeys{it!="focus_rank" || rank==null}).toString());put("present_fields",JsonArray(g.keys.filter{it in known}.map(::JsonPrimitive)))
            put("created_at",createdAt?:now);put("updated_at",now);put("updated_by_npub",viewer);put("tombstoned_at",JsonNull)
        }
        val cps=goal.checkpoints.mapIndexed { pos,c -> buildJsonObject {
            scope.forEach{(k,v)->put(k,v)};put("id","$id:cp:${c.id}");put("checkpoint_id",c.id);put("title",c.title);put("date",c.date?.let(::JsonPrimitive)?:JsonNull);put("description",c.description?.let(::JsonPrimitive)?:JsonNull);put("reached",c.reached);put("position",pos);put("extensions",c.extra.toString());put("present_fields",JsonArray(c.toJson().keys.map(::JsonPrimitive)))
        } }
        val refs=listOf("link" to goal.links,"pin" to goal.pinnedRefs).flatMap { (lane,list)->list.mapIndexed { pos,r ->buildJsonObject {
            scope.forEach{(k,v)->put(k,v)};put("id","$id:$lane:${r.type.wire}:${r.recordId}");put("lane",lane);put("kind",r.type.wire);put("record_id",r.recordId);put("target_channel_id",r.channelId?.let(::JsonPrimitive)?:JsonNull);put("position",pos);put("extensions",r.extra.toString());put("present_fields",JsonArray(r.toJson().keys.map(::JsonPrimitive)))
        } } }
        val historyId="$id:v:$revision"
        val history=buildJsonObject{parent.filterKeys{it!="created_at"}.forEach{(k,v)->put(k,v)};put("id",historyId);put("goal_id",id);put("content_revision",revision);put("revision",1)}
        fun historyChildren(values:List<JsonObject>)=values.map{r->buildJsonObject{r.forEach{(k,v)->put(k,v)};put("id",r.string("id")!!.replaceFirst(id,historyId));put("history_id",historyId);put("content_revision",revision);put("revision",1)}}
        return linkedMapOf("goals" to listOf(parent),"goal_checkpoints" to cps,"goal_associations" to refs,"goal_history" to listOf(history),"goal_checkpoint_history" to historyChildren(cps),"goal_association_history" to historyChildren(refs))
    }
}
private class GoalSnapshotChanged:Exception()
