package studio.otherstuff.ambulando.core.goals

import studio.otherstuff.ambulando.core.transport.*

/** Goal discovery/content are reads; mutations still require the runtime's independently guarded write authority. */
class GoalsTransport(private val reads: HttpTransport, private val writes: HttpTransport) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse =
        (if (request.method in setOf("GET", "HEAD")) reads else writes).send(request)
}
