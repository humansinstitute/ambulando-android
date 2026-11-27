package studio.otherstuff.ambulando.core.goals

import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

/** Goal discovery/content are reads; mutations retain the runtime's independently guarded write readiness. */
class GoalsTransport(private val reads: HttpTransport, private val writes: HttpTransport) : HttpTransport {
    private val nativeRead=Regex("/api/v4/workspaces/npub1[a-z0-9]+/apps/npub1[a-z0-9]+/db/native-v1/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/(?:query|status|read)")
    override suspend fun send(request: HttpRequest): HttpResponse {
        val isRead=request.method in setOf("GET","HEAD") || request.method=="POST" && runCatching { nativeRead.matches(URI(request.url).path) }.getOrDefault(false)
        return (if(isRead)reads else writes).send(request)
    }
}
