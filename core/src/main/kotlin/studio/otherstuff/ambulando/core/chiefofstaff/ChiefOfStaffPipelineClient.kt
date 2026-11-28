package studio.otherstuff.ambulando.core.chiefofstaff

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.management.ConfirmedAutopilotOrigin
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.lowerUUID
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import java.net.URI

/**
 * Narrow authenticated client for Autopilot's existing generic pipeline invocation route:
 * `POST /api/pipelines/definitions/:definitionId/runs` (NIP-98 signed, exact confirmed HTTPS host,
 * no redirects, no Tower `X-App-Npub` header, no exported credentials, no arbitrary shell endpoint).
 */
object ChiefOfStaffPipelineContract {
    const val DEFAULT_RECONCILE_PIPELINE_ID = "chief-of-staff-reconcile.v1"
    private val SAFE_DEFINITION_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun isValidDefinitionId(id: String): Boolean = SAFE_DEFINITION_ID.matches(id)

    fun runPath(definitionId: String): String {
        require(isValidDefinitionId(definitionId)) { "Invalid Autopilot pipeline definition ID." }
        return "/api/pipelines/definitions/$definitionId/runs"
    }
}

data class ChiefOfStaffReconcileRequest(
    val workspaceId: String,
    val ownerNpub: String,
    val appNpub: String,
    val assignedAgentNpub: String?,
    val assignmentRevision: Int,
    val assignmentEpoch: Int,
    val towerUrl: String,
    val subscriptionId: String? = null,
    val waitSeconds: Int = 20,
) {
    val idempotencyKey: String
        get() = "chief-of-staff:reconcile:${workspaceId.lowercase()}:rev:$assignmentRevision:${assignedAgentNpub ?: "unassigned"}"

    fun toBodyJson(): JsonObject = buildJsonObject {
        put("mode", "wait")
        put("waitSeconds", waitSeconds.coerceIn(0, 60))
        put("idempotencyKey", idempotencyKey)
        put("input", buildJsonObject {
            put("workspace_id", workspaceId.lowercase())
            put("workspace_owner_npub", ownerNpub)
            put("app_npub", appNpub)
            put("assigned_agent_npub", assignedAgentNpub?.let(::JsonPrimitive) ?: JsonNull)
            put("assignment_revision", assignmentRevision)
            put("assignment_epoch", assignmentEpoch)
            put("tower_url", towerUrl)
            // Only a canonical UUID subscription is ever sent; cleanup-only reconciles send none.
            lowerUUID(subscriptionId?.trim())?.let { put("subscription_id", it) }
        })
    }
}

sealed interface ChiefOfStaffReconcileOutcome {
    /**
     * Terminal `ok` run. [scheduleState] is the reconcile step's own `schedule_state` (e.g. `scheduled`, `pending`,
     * `unconfigured`, `disabled_stale`) when the run result carried one; `pending` is honest setup-pending, not healthy.
     */
    data class Completed(
        val runId: String?,
        val status: String,
        val replayed: Boolean,
        val scheduleState: String? = null,
        val lastError: String? = null,
    ) : ChiefOfStaffReconcileOutcome
    data class Accepted(val runId: String?, val status: String, val timedOut: Boolean) : ChiefOfStaffReconcileOutcome
    data class DefinitionNotFound(val definitionId: String, val message: String) : ChiefOfStaffReconcileOutcome
    data class Failed(val status: Int?, val code: String?, val message: String) : ChiefOfStaffReconcileOutcome
}

/**
 * Exact-host, exact-path, exact-body guard mirroring [studio.otherstuff.ambulando.core.management.AutopilotFlowGuard]
 * for invoking a pinned Autopilot pipeline definition.
 */
internal class ChiefOfStaffPipelineGuard(
    private val base: HttpTransport,
    private val origin: TowerOrigin,
    private val definitionId: String,
    private val workspaceId: String,
    private val ownerNpub: String,
    private val appNpub: String,
) : HttpTransport {
    private val expectedPath = ChiefOfStaffPipelineContract.runPath(definitionId)

    override suspend fun send(request: HttpRequest): HttpResponse {
        val uri = runCatching { URI(request.url) }.getOrNull() ?: throw BoundaryViolation("pipeline: unreadable URL")
        val exactUrl = request.method == "POST" &&
            uri.scheme == "https" &&
            uri.host?.lowercase() == origin.host &&
            uri.port.takeIf { it >= 0 && it != 443 } == origin.port &&
            uri.rawPath == expectedPath &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawUserInfo == null &&
            request.header(TowerClient.APP_NPUB_HEADER) == null
        if (!exactUrl) {
            throw BoundaryViolation("pipeline: only POST $expectedPath at ${origin.url}")
        }
        val bodyObj = request.body?.let { parseJsonObjectOrNull(it) }
            ?: throw BoundaryViolation("pipeline: request body must be a JSON object")
        val input = bodyObj.obj("input")
            ?: throw BoundaryViolation("pipeline: request input is missing")
        if (input.string("workspace_id")?.lowercase() != workspaceId.lowercase() ||
            input.string("workspace_owner_npub") != ownerNpub ||
            input.string("app_npub") != appNpub
        ) {
            throw BoundaryViolation("pipeline: request workspace context mismatch")
        }
        return base.send(request)
    }
}

class ChiefOfStaffPipelineClient(
    private val identity: IdentityService,
    private val transport: HttpTransport,
) {
    /**
     * Invokes the pinned reconciliation pipeline on a user-confirmed Autopilot origin using NIP-98 signing.
     * Redirects (`3xx`) are refused; errors are returned honestly without pretending schedule setup succeeded.
     */
    suspend fun reconcile(
        origin: ConfirmedAutopilotOrigin,
        signerNpub: String,
        definitionId: String = ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID,
        request: ChiefOfStaffReconcileRequest,
    ): ChiefOfStaffReconcileOutcome {
        if (!ChiefOfStaffPipelineContract.isValidDefinitionId(definitionId)) {
            return ChiefOfStaffReconcileOutcome.Failed(null, "invalid_definition_id", "Invalid pipeline definition ID.")
        }
        identity.requireSigner(signerNpub)
        val normalizedOrigin = TowerOrigin(origin.url)
        val guard = ChiefOfStaffPipelineGuard(
            base = transport,
            origin = normalizedOrigin,
            definitionId = definitionId,
            workspaceId = request.workspaceId,
            ownerNpub = request.ownerNpub,
            appNpub = request.appNpub,
        )
        val path = ChiefOfStaffPipelineContract.runPath(definitionId)
        val reply = try {
            TowerClient(normalizedOrigin, guard, identity).exchange(
                method = "POST",
                path = path,
                appNpub = null,
                jsonBody = jsonBody(request.toBodyJson()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BoundaryViolation) {
            throw e
        } catch (e: Exception) {
            return ChiefOfStaffReconcileOutcome.Failed(null, "network_error", e.message ?: "Could not reach Autopilot.")
        }
        val body = parseJsonObjectOrNull(reply.body)
        // Autopilot RunEnvelope: top-level runId/status/terminal/result/error; `run` is a legacy summary only.
        val run = body?.obj("run")
        val runId = body?.string("runId") ?: run?.string("id") ?: body?.string("id")
        val runStatus = (body?.string("status") ?: run?.string("status") ?: "").lowercase()
        val runError = body?.string("error") ?: run?.string("error")
        val code = body?.string("code")
        val result = body?.obj("result")
        val reconciled = result?.obj("reconciled") ?: result
        val scheduleState = reconciled?.string("schedule_state")?.takeIf { it in ChiefOfStaffContract.SCHEDULE_STATES }
        val scheduleError = reconciled?.string("last_error")
        return when {
            reply.status in 300..399 -> ChiefOfStaffReconcileOutcome.Failed(
                reply.status,
                "redirect_refused",
                "Autopilot answered with a redirect (HTTP ${reply.status}). Redirects are refused.",
            )
            reply.status == 200 -> when {
                runStatus.isEmpty() -> ChiefOfStaffReconcileOutcome.Failed(
                    reply.status,
                    "unreadable_run_envelope",
                    "Autopilot answered without a readable pipeline run status.",
                )
                runStatus != "ok" && runStatus != "completed" -> ChiefOfStaffReconcileOutcome.Failed(
                    reply.status,
                    code ?: "pipeline_run_$runStatus",
                    runError ?: "Reconciliation pipeline run ended with status '$runStatus'.",
                )
                scheduleState == "error" -> ChiefOfStaffReconcileOutcome.Failed(
                    reply.status,
                    "schedule_error",
                    scheduleError ?: "Autopilot could not set up the Chief of Staff schedule.",
                )
                else -> ChiefOfStaffReconcileOutcome.Completed(
                    runId = runId,
                    status = runStatus,
                    replayed = body?.bool("replayed") == true,
                    scheduleState = scheduleState,
                    lastError = scheduleError,
                )
            }
            reply.status == 202 -> ChiefOfStaffReconcileOutcome.Accepted(
                runId = runId,
                status = runStatus.ifEmpty { "running" },
                timedOut = body?.bool("timedOut") == true,
            )
            reply.status == 404 -> ChiefOfStaffReconcileOutcome.DefinitionNotFound(
                definitionId = definitionId,
                message = runError ?: "Pipeline definition '$definitionId' is not installed on ${origin.url}.",
            )
            reply.status == 401 || reply.status == 403 -> ChiefOfStaffReconcileOutcome.Failed(
                reply.status,
                code ?: "permission_denied",
                runError ?: "Autopilot refused the reconciliation request (HTTP ${reply.status}).",
            )
            reply.status == 409 -> ChiefOfStaffReconcileOutcome.Failed(
                reply.status,
                code ?: "idempotency_conflict",
                runError ?: "Autopilot reported an idempotency conflict (HTTP 409).",
            )
            else -> ChiefOfStaffReconcileOutcome.Failed(
                reply.status,
                code,
                runError ?: "Autopilot rejected the reconciliation request (HTTP ${reply.status}).",
            )
        }
    }
}

/**
 * Which saved runtime binding a reconcile after an assignment save may use, and with which subscription.
 *
 * - The saved assignment's own binding for its currently assigned agent schedules with that binding's subscription.
 * - Otherwise (removal, or a new agent without its own binding) the previous binding may only drive stale-schedule
 *   cleanup on its Autopilot, with NO subscription, so the old subscription can never create a job for another agent.
 * - With neither, there is nothing to invoke: schedule setup is honestly pending.
 */
data class ChiefOfStaffReconcilePlan(
    val binding: ChiefOfStaffRuntimeBinding,
    val subscriptionId: String?,
    val isCleanup: Boolean,
) {
    companion object {
        fun of(target: ChiefOfStaffAssignment, previousBinding: ChiefOfStaffRuntimeBinding?): ChiefOfStaffReconcilePlan? {
            fun sameBoundary(b: ChiefOfStaffRuntimeBinding) =
                b.workspaceId.lowercase() == target.workspaceId.lowercase() && b.ownerNpub == target.workspaceOwnerNpub
            val active = target.runtimeBinding?.takeIf {
                target.isAssigned && it.agentNpub == target.assignedNpub && sameBoundary(it)
            }
            if (active != null) return ChiefOfStaffReconcilePlan(active, active.subscriptionId.lowercase(), isCleanup = false)
            val cleanup = previousBinding?.takeIf(::sameBoundary) ?: return null
            return ChiefOfStaffReconcilePlan(cleanup, subscriptionId = null, isCleanup = true)
        }
    }
}

/**
 * Per-workspace-boundary persisted user-confirmed Autopilot reconciliation target.
 */
data class ChiefOfStaffReconcileConfig(
    val autopilotOrigin: String,
    val pipelineDefinitionId: String = ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID,
    val subscriptionId: String? = null,
)

object ChiefOfStaffReconcileStore {
    fun configKey(connectionId: String, workspaceId: String, appNpub: String): String =
        "chief-of-staff:reconcile-config:$connectionId:${workspaceId.lowercase()}:$appNpub"

    suspend fun load(store: LocalStore, connectionId: String, workspaceId: String, appNpub: String): ChiefOfStaffReconcileConfig? {
        val raw = store.reader.state(configKey(connectionId, workspaceId, appNpub))?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val o = Json.parseToJsonElement(raw).jsonObject
            val origin = o.string("autopilot_origin")?.let { ConfirmedAutopilotOrigin.of(it).url } ?: return null
            val defId = o.string("pipeline_definition_id")?.takeIf(ChiefOfStaffPipelineContract::isValidDefinitionId)
                ?: ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID
            ChiefOfStaffReconcileConfig(origin, defId, o.string("subscription_id")?.takeIf { it.isNotBlank() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun save(
        store: LocalStore,
        connectionId: String,
        workspaceId: String,
        appNpub: String,
        config: ChiefOfStaffReconcileConfig?,
    ) {
        val key = configKey(connectionId, workspaceId, appNpub)
        if (config == null) {
            store.writer.setState(key, "")
            return
        }
        val normalized = ConfirmedAutopilotOrigin.of(config.autopilotOrigin).url
        require(ChiefOfStaffPipelineContract.isValidDefinitionId(config.pipelineDefinitionId)) { "Invalid pipeline definition ID." }
        val json = buildJsonObject {
            put("autopilot_origin", normalized)
            put("pipeline_definition_id", config.pipelineDefinitionId)
            config.subscriptionId?.takeIf { it.isNotBlank() }?.let { put("subscription_id", it.trim()) }
        }
        store.writer.setState(key, json.toString())
    }
}
