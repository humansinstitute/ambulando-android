package studio.otherstuff.ambulando.core.briefing

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*

/** Read-only Home reader; no provisioning, source sweep or goal changes in the client. */
suspend fun TowerAPI.personalBriefing(workspace: String,app: String,viewer: String,location: BriefingLocation? = null): Briefing? {
    val channel=if(location!=null) {
        val row=obj(client.request(path=TowerAPI.base(workspace)+"/channels/${TowerAPI.uuid(location.channelId)}",appNpub=app),"briefing channel").obj("channel")?.let(ChannelRecord::parse) ?: error("Briefing channel is unavailable.")
        require(row.workspaceId==workspace && row.id==location.channelId && BriefingPrivacy.owns(row,viewer))
        row
    } else {
        val found=ArrayList<ChannelRecord>()
        for(scope in scopes(workspace,app)) {
            if(scope.kind=="dm" || scope.accessRemoved || !BriefingPrivacy.active(scope.raw)) continue
            for(ch in channels(workspace,scope.id,app)) if(BriefingPrivacy.owns(ch,viewer)) {
                require(ch.workspaceId==workspace&&ch.scopeId==scope.id); found+=ch
            }
        }
        require(found.size<=1) { "Multiple Briefings spaces need reconciliation." }
        found.singleOrNull() ?: return null
    }
    val author=channel.raw.obj("metadata")?.obj("briefing")?.string("author_npub") ?: error("Briefing author is missing.")
    require(location==null || location.author==author)
    var authorName: String? = null
    suspend fun checkGrants() {
        val grants=obj(client.request(path=TowerAPI.base(workspace)+"/channels/${TowerAPI.uuid(channel.id)}/grants",appNpub=app),"briefing grants").objects("grants") ?: error("Briefing grants could not be checked.")
        val people=members(workspace,app)
        authorName=people.firstOrNull {it.npub==author}?.displayName?.takeIf {it.isNotBlank()}
        require(BriefingPrivacy.grants(grants,people,viewer,author)) { "This Briefings space is not private to you and its author." }
    }
    checkGrants()
    fun matches(file: FileRecord): Boolean = !file.archived && !file.accessRemoved && BriefingPrivacy.active(file.raw) && file.contentType?.substringBefore(';')?.trim()?.lowercase()==Briefing.MIME && file.raw.obj("metadata")?.obj("briefing")?.let {
        it["schema_version"]?.jsonPrimitive?.intOrNull==1 && it.string("owner_npub")==viewer && it.string("author_npub")==author
    }==true
    val fileId=location?.fileId ?: run {
        val files=client.list(TowerAPI.base(workspace)+"/channels/${TowerAPI.uuid(channel.id)}/files",app,"files").map(FileRecord::parse).filter(::matches)
        require(files.size<=1) { "Multiple briefing Files need reconciliation." }
        files.singleOrNull()?.id ?: return null
    }
    val file=obj(client.request(path=TowerAPI.base(workspace)+"/files/${TowerAPI.uuid(fileId)}",appNpub=app),"briefing").obj("file")?.let(FileRecord::parse) ?: error("Briefing could not be read.")
    require(matches(file)&&file.id==fileId&&file.workspaceId==workspace&&file.channelId==channel.id)
    val version=file.raw.string("current_version_id") ?: error("Briefing has no saved version.")
    val meta=file.raw.obj("object")?.obj("storage_object") ?: error("Briefing has no verified content.")
    val hash=meta.string("sha256_hex") ?: error("Briefing has no content digest.")
    val size=flexibleLong(meta["size_bytes"]) ?: error("Briefing size is missing.")
    require(size in 0..Briefing.MAX_BYTES.toLong() && meta.bool("is_public")==false)
    val bytes=client.request(path=TowerAPI.base(workspace)+"/files/${TowerAPI.uuid(file.id)}/versions/${TowerAPI.uuid(version)}/object",appNpub=app)
    require(bytes.size.toLong()==size && Sha256Hex.of(bytes)==hash.lowercase()) { "Briefing content could not be verified." }
    val result=Briefing.parse(bytes,workspace,viewer,author)
    checkGrants()
    return result.copy(location=BriefingLocation(channel.id,fileId,author),authorName=authorName)
}
