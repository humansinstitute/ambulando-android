package studio.otherstuff.ambulando.core.management

import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import java.net.URI

/**
 * An Autopilot origin the person typed and confirmed, in its normalized HTTPS form. The only way to make one is [of]; nothing in Tower's
 * metadata, the catalog or an earlier session can produce it, and every activation call takes one, so a host the person has not been
 * shown and confirmed is never signed to.
 */
class ConfirmedAutopilotOrigin private constructor(val url: String) {
    internal val origin: TowerOrigin get() = TowerOrigin(url)

    override fun equals(other: Any?) = other is ConfirmedAutopilotOrigin && other.url == url
    override fun hashCode() = url.hashCode()
    override fun toString() = url

    companion object {
        fun of(typed: String): ConfirmedAutopilotOrigin = try {
            ConfirmedAutopilotOrigin(TowerOrigin(typed.trim()).url)
        } catch (e: TowerError) {
            throw ManagementError.Invalid(e.message ?: "Use an HTTPS origin.")
        }
    }
}

/**
 * The install intent Tower returned, bound to the origin it was created for and the boundary (origin, signer, application, workspace)
 * that created it. It is what is saved before the separate claim, and a claim for any other origin or boundary is refused.
 */
data class ActivationIntent(val id: String, val origin: String, val boundary: String)

/** What Autopilot's catalog offered for one confirmed origin: apps that can be activated through it, and entries that name another Autopilot. */
data class ActivationCatalog(val apps: List<ActivationApp>, val otherOrigin: Int)

/**
 * The only request an activation step may make: one method and exact path at the one confirmed Autopilot origin, with no query, credentials,
 * app header or other body than the step's own. It wraps the workspace runtime's gated transport, so a paused or lost workspace still
 * refuses, and it is the last check before a signed request leaves for a host that is not Tower.
 */
internal class AutopilotFlowGuard(
    private val base: HttpTransport,
    private val origin: TowerOrigin,
    private val method: String,
    private val path: String,
    private val bodyAllowed: (ByteArray?) -> Boolean = { it == null || it.isEmpty() },
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val uri = runCatching { URI(request.url) }.getOrNull() ?: throw BoundaryViolation("activation: unreadable URL")
        val exact = request.method == method && uri.scheme == "https" && uri.host?.lowercase() == origin.host &&
            uri.port.takeIf { it >= 0 && it != 443 } == origin.port && uri.rawPath == path && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawUserInfo == null && request.header(TowerClient.APP_NPUB_HEADER) == null && bodyAllowed(request.body)
        if (!exact) throw BoundaryViolation("activation: only $method $path at ${origin.url}")
        return base.send(request)
    }

    companion object {
        const val CATALOG_PATH = "/api/wapps/activation-catalog"
        const val PROCESS_PATH = "/api/wapps/install-intents/process"

        /** The process body is exactly this workspace and this intent, nothing more. */
        fun processBody(workspaceId: String, intentId: String): (ByteArray?) -> Boolean = { body ->
            val parsed = body?.let { runCatching { TowerJson.parseToJsonElement(it.toString(Charsets.UTF_8)) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            parsed != null && parsed.keys == setOf("workspace_id", "intent_id") &&
                (parsed["workspace_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == workspaceId &&
                (parsed["intent_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == intentId
        }
    }
}
