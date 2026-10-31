package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import java.net.URI

/**
 * Sample data with more than one Tower: every request goes to the synthetic Tower whose origin host it names (anything else,
 * such as a presigned object-store host, goes to the first). Each Tower serves the same synthetic content and the same workspace
 * ids at its own origin, which is exactly the case multiple-workspace isolation has to survive. Labelled sample data only.
 */
class SampleTowerRouter(private val towers: List<SampleTower>) : HttpTransport {
    private fun pick(url: String): SampleTower {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
        return towers.firstOrNull { it.origin.host == host } ?: towers.first()
    }

    override suspend fun send(request: HttpRequest): HttpResponse = pick(request.url).send(request)

    val events: EventStreamTransport = object : EventStreamTransport {
        override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> = pick(request.url).events.open(request)
    }
}
