package studio.otherstuff.ambulando.core.workspaces

import java.net.URI
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Local retained Files confer no authority for current metadata, versions, bytes or mutations. */
internal class RetainedFileAccessTransport(
    private val base: HttpTransport,
    private val store: LocalStore,
    private val workspaceId: String,
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = URI(request.url).path
        val prefix = "/api/v4/flightdeck-pg/workspaces/$workspaceId/files/"
        val fileId = path.takeIf { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)?.substringBefore('/')
            ?.takeIf { isUUID(it) }?.lowercase()
        suspend fun checkAccess() {
            if (fileId == null) return
            val file = store.reader.file(workspaceId,fileId) ?: return
            check(!file.accessRemoved && !store.reader.isChannelAccessRemoved(workspaceId,file.channelId)) {
                "This File is no longer available to you. Downloaded history and drafts are kept on this device."
            }
        }
        checkAccess()
        val response = base.send(request)
        checkAccess()
        return response
    }
}
