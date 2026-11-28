package studio.otherstuff.ambulando.core.chiefofstaff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.NavScope
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleDouble
import studio.otherstuff.ambulando.core.transport.flexibleInt
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.lowerUUID
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.string
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ambulando Chief of Staff wire contract (v1) — matches `docs/chief-of-staff.md` and `scripts/chief-of-staff/model.mjs`.
 *
 * Storage uses existing Ambulando-app-bound versioned NStack Files with server-enforced channel grants and
 * `base_version_id` CAS. Encryption is deferred in v1 (`envelope_version = 1`, `encryption.mode = "none"`); the
 * [ChiefOfStaffEnvelopeAdapter] seam allows future client/agent encryption without changing record IDs or routes.
 */
object ChiefOfStaffContract {
    const val SYSTEM_KEY_ASSIGNMENT = "ambulando.chief_of_staff"
    const val SYSTEM_KEY_ONTOLOGY = "ambulando.chief_of_staff.ontology"

    const val MIME_ASSIGNMENT = "application/vnd.ambulando.chief-of-staff-assignment+json"
    const val MIME_JOB_STATUS = "application/vnd.ambulando.chief-of-staff-job-status+json"
    const val MIME_ONTOLOGY = "application/vnd.ambulando.chief-of-staff-ontology+json"
    const val MIME_CORRECTIONS = "application/vnd.ambulando.chief-of-staff-corrections+json"

    const val FILE_NAME_ASSIGNMENT = "Chief of Staff.assignment.json"
    const val FILE_NAME_JOB_STATUS = "Chief of Staff.job-status.json"
    const val FILE_NAME_ONTOLOGY = "Chief of Staff.ontology.json"
    const val FILE_NAME_CORRECTIONS = "Chief of Staff.corrections.json"

    const val SCHEMA_ASSIGNMENT = "ambulando.chief_of_staff.assignment"
    const val SCHEMA_JOB_STATUS = "ambulando.chief_of_staff.job_status"
    const val SCHEMA_ONTOLOGY = "ambulando.chief_of_staff.ontology"
    const val SCHEMA_CORRECTIONS = "ambulando.chief_of_staff.corrections"

    const val MAX_BYTES = 4 * 1024 * 1024

    val SCHEDULE_STATES = setOf(
        "unconfigured",
        "pending",
        "scheduled",
        "running",
        "healthy",
        "error",
        "disabled_stale",
    )

    val SOURCE_TYPES = setOf(
        "message",
        "thread",
        "audio_note",
        "task",
        "doc",
        "file",
    )

    val EVIDENCE_STATUSES = setOf("active", "stale", "deleted", "inaccessible")

    val CORRECTION_KINDS = setOf(
        "rename",
        "set_description",
        "set_aliases",
        "set_kind",
        "merge_topics",
        "reject_evidence",
        "pin_evidence",
        "archive_topic",
    )

    private val ISO_RE = Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?(?:Z|[+-]\\d\\d:\\d\\d)$")

    fun isIsoTimestamp(value: String?): Boolean {
        if (value == null || !ISO_RE.matches(value)) return false
        return runCatching { OffsetDateTime.parse(value).toInstant() }.isSuccess
    }

    fun isValidNpub(value: String?): Boolean = value != null && NostrKeys.isValidNpub(value)

    fun cohortId(ownerNpub: String, agentNpub: String, assignmentEpoch: Int = 1): String {
        val epoch = if (assignmentEpoch >= 1) assignmentEpoch else 1
        return "owner:$ownerNpub:agent:$agentNpub:epoch:$epoch"
    }

    fun isActiveRecord(raw: JsonObject): Boolean =
        raw.string("record_state") !in setOf("deleted", "archived") &&
            raw.string("archived_at") == null &&
            raw.string("deleted_at") == null &&
            raw.bool("access_removed") != true

    fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> element.toString()
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonicalJson(v)}" }
    }

    fun decodeStrictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    // --- Metadata builders ---

    fun assignmentChannelMetadata(workspaceId: String, appNpub: String): JsonObject = buildJsonObject {
        put("system_key", SYSTEM_KEY_ASSIGNMENT)
        put("chief_of_staff", buildJsonObject {
            put("schema_version", 1)
            put("kind", "assignment")
            put("workspace_id", workspaceId)
            put("app_npub", appNpub)
        })
    }

    fun assignmentFileMetadata(workspaceId: String, appNpub: String): JsonObject = buildJsonObject {
        put("chief_of_staff", buildJsonObject {
            put("schema_version", 1)
            put("kind", "assignment")
            put("workspace_id", workspaceId)
            put("app_npub", appNpub)
        })
    }

    fun cohortChannelMetadata(
        workspaceId: String,
        appNpub: String,
        ownerNpub: String,
        agentNpub: String,
        assignmentEpoch: Int = 1,
    ): JsonObject = buildJsonObject {
        val epoch = if (assignmentEpoch >= 1) assignmentEpoch else 1
        put("system_key", SYSTEM_KEY_ONTOLOGY)
        put("chief_of_staff", buildJsonObject {
            put("schema_version", 1)
            put("kind", "ontology_cohort")
            put("cohort_kind", "owner_agent_private")
            put("cohort_id", cohortId(ownerNpub, agentNpub, epoch))
            put("assignment_epoch", epoch)
            put("workspace_id", workspaceId)
            put("app_npub", appNpub)
            put("owner_npub", ownerNpub)
            put("agent_npub", agentNpub)
        })
    }

    fun cohortFileMetadata(
        kind: String,
        workspaceId: String,
        appNpub: String,
        ownerNpub: String,
        agentNpub: String,
        assignmentEpoch: Int = 1,
    ): JsonObject = buildJsonObject {
        val epoch = if (assignmentEpoch >= 1) assignmentEpoch else 1
        put("chief_of_staff", buildJsonObject {
            put("schema_version", 1)
            put("kind", kind)
            put("cohort_kind", "owner_agent_private")
            put("cohort_id", cohortId(ownerNpub, agentNpub, epoch))
            put("assignment_epoch", epoch)
            put("workspace_id", workspaceId)
            put("app_npub", appNpub)
            put("owner_npub", ownerNpub)
            put("agent_npub", agentNpub)
        })
    }

    // --- Metadata matchers ---

    fun isAssignmentChannel(channel: ChannelRecord, workspaceId: String, appNpub: String): Boolean {
        if (channel.accessRemoved || !isActiveRecord(channel.raw)) return false
        if (channel.kind != "channel" || channel.workspaceId != workspaceId) return false
        val metadata = channel.raw.obj("metadata") ?: return false
        if (metadata.string("system_key") != SYSTEM_KEY_ASSIGNMENT) return false
        val m = metadata.obj("chief_of_staff") ?: return false
        return flexibleInt(m["schema_version"]) == 1 &&
            m.string("kind") == "assignment" &&
            m.string("workspace_id")?.lowercase() == workspaceId.lowercase() &&
            m.string("app_npub") == appNpub
    }

    fun isCohortChannel(
        channel: ChannelRecord,
        workspaceId: String,
        appNpub: String,
        ownerNpub: String,
        agentNpub: String,
        assignmentEpoch: Int? = null,
    ): Boolean {
        if (channel.accessRemoved || !isActiveRecord(channel.raw)) return false
        if (channel.kind != "channel" || channel.workspaceId != workspaceId) return false
        val metadata = channel.raw.obj("metadata") ?: return false
        if (metadata.string("system_key") != SYSTEM_KEY_ONTOLOGY) return false
        val m = metadata.obj("chief_of_staff") ?: return false
        val epoch = flexibleInt(m["assignment_epoch"])
        val epochMatches = if (assignmentEpoch == null) {
            epoch != null && epoch >= 1
        } else {
            epoch == assignmentEpoch && m.string("cohort_id") == cohortId(ownerNpub, agentNpub, assignmentEpoch)
        }
        return flexibleInt(m["schema_version"]) == 1 &&
            m.string("kind") == "ontology_cohort" &&
            m.string("cohort_kind") == "owner_agent_private" &&
            m.string("workspace_id")?.lowercase() == workspaceId.lowercase() &&
            m.string("app_npub") == appNpub &&
            m.string("owner_npub") == ownerNpub &&
            m.string("agent_npub") == agentNpub &&
            epochMatches
    }

    private fun normalizedMime(file: FileRecord): String =
        file.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    fun isAssignmentFile(file: FileRecord, workspaceId: String, appNpub: String): Boolean {
        if (file.archived || file.accessRemoved || !isActiveRecord(file.raw)) return false
        if (file.workspaceId != workspaceId || normalizedMime(file) != MIME_ASSIGNMENT) return false
        val m = file.raw.obj("metadata")?.obj("chief_of_staff") ?: return false
        return flexibleInt(m["schema_version"]) == 1 &&
            m.string("kind") == "assignment" &&
            m.string("workspace_id")?.lowercase() == workspaceId.lowercase() &&
            m.string("app_npub") == appNpub
    }

    fun isCohortFile(
        file: FileRecord,
        kind: String,
        mime: String,
        workspaceId: String,
        appNpub: String,
        ownerNpub: String,
        agentNpub: String,
        assignmentEpoch: Int,
    ): Boolean {
        if (file.archived || file.accessRemoved || !isActiveRecord(file.raw)) return false
        if (file.workspaceId != workspaceId || normalizedMime(file) != mime) return false
        val m = file.raw.obj("metadata")?.obj("chief_of_staff") ?: return false
        return flexibleInt(m["schema_version"]) == 1 &&
            m.string("kind") == kind &&
            m.string("cohort_kind") == "owner_agent_private" &&
            flexibleInt(m["assignment_epoch"]) == assignmentEpoch &&
            m.string("cohort_id") == cohortId(ownerNpub, agentNpub, assignmentEpoch) &&
            m.string("workspace_id")?.lowercase() == workspaceId.lowercase() &&
            m.string("app_npub") == appNpub &&
            m.string("owner_npub") == ownerNpub &&
            m.string("agent_npub") == agentNpub
    }
}

/**
 * Encryption adapter seam.
 * Encryption is deferred in v1 (`mode = "none"`): no client or agent may claim payloads are encrypted today.
 */
interface ChiefOfStaffEnvelopeAdapter {
    val mode: String
    fun encrypt(payload: JsonObject): JsonObject
    fun decrypt(envelope: JsonObject): JsonObject
}

object PlaintextEnvelopeAdapter : ChiefOfStaffEnvelopeAdapter {
    override val mode: String = "none"

    override fun encrypt(payload: JsonObject): JsonObject = buildJsonObject {
        put("envelope_version", 1)
        put("encryption", buildJsonObject { put("mode", "none") })
        put("payload", payload)
    }

    override fun decrypt(envelope: JsonObject): JsonObject {
        val version = flexibleInt(envelope["envelope_version"])
        val encryption = envelope.obj("encryption")
        require(version == 1 && encryption != null) { "Unsupported Chief of Staff envelope version." }
        val mode = encryption.string("mode")
        require(mode == "none") { "Encrypted Chief of Staff payload ($mode) requires a matching decryption adapter." }
        return envelope.obj("payload") ?: throw IllegalArgumentException("Invalid Chief of Staff envelope payload.")
    }
}

object ChiefOfStaffEnvelope {
    fun encode(payload: JsonObject, adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter): ByteArray {
        val env = adapter.encrypt(payload)
        val bytes = ChiefOfStaffContract.canonicalJson(env).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ChiefOfStaffContract.MAX_BYTES) { "Chief of Staff document exceeds 4 MiB limit." }
        return bytes
    }

    fun decode(bytes: ByteArray, adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter): JsonObject {
        require(bytes.size <= ChiefOfStaffContract.MAX_BYTES) { "Chief of Staff document exceeds 4 MiB limit." }
        val parsed = Json.parseToJsonElement(ChiefOfStaffContract.decodeStrictUtf8(bytes)).jsonObject
        return adapter.decrypt(parsed)
    }
}

// --- Domain models ---

data class ExpectedWorkspaceContext(
    val workspaceId: String,
    val ownerNpub: String,
    val appNpub: String,
)

data class ChiefOfStaffRuntimeBinding(
    val autopilotOrigin: String,
    val reconcilePipelineId: String,
    val ownerNpub: String,
    val workspaceId: String,
    val subscriptionId: String,
    val agentNpub: String? = null,
    val ontologyPipelineId: String? = null,
    val cronExpression: String? = null,
    val timezone: String? = null,
    /** Unknown/future compatible fields preserved across round-trips. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        for ((k, v) in extra) {
            if (k !in KNOWN_KEYS) put(k, v)
        }
        put("autopilot_origin", autopilotOrigin)
        put("reconcile_pipeline_id", reconcilePipelineId)
        ontologyPipelineId?.let { put("ontology_pipeline_id", it) }
        put("owner_npub", ownerNpub)
        put("workspace_id", workspaceId)
        put("subscription_id", subscriptionId.lowercase())
        put("agent_npub", agentNpub?.let(::JsonPrimitive) ?: JsonNull)
        cronExpression?.let { put("cron_expression", it) }
        timezone?.let { put("timezone", it) }
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            "autopilot_origin",
            "reconcile_pipeline_id",
            "ontology_pipeline_id",
            "owner_npub",
            "workspace_id",
            "subscription_id",
            "agent_npub",
            "cron_expression",
            "timezone",
        )

        /** Same refusal list as `scripts/chief-of-staff/model.mjs`: no credentials or private cohort content. */
        private val FORBIDDEN_KEYS = listOf(
            "token", "secret", "key", "nsec", "authorization", "topics", "evidence", "cohort_id", "cohort_channel_id",
        )

        fun parse(
            raw: JsonObject,
            expectedWorkspaceId: String,
            expectedOwnerNpub: String,
            expectedAssignedNpub: String?,
        ): ChiefOfStaffRuntimeBinding {
            for (forbidden in FORBIDDEN_KEYS) {
                require(forbidden !in raw) { "runtime_binding must not contain private or secret field: $forbidden" }
            }
            val rawOrigin = raw.string("autopilot_origin")?.trim()
                ?: throw IllegalArgumentException("runtime_binding.autopilot_origin is required.")
            // Clean HTTPS origin only (no credentials/path/query/fragment, no plain-HTTP FIPS peer).
            val normalizedOrigin = runCatching { studio.otherstuff.ambulando.core.transport.TowerOrigin(rawOrigin) }.getOrNull()
                ?.takeIf { !it.isFips }?.url
                ?: throw IllegalArgumentException("runtime_binding.autopilot_origin must be a clean https:// origin.")
            val pipelineId = raw.string("reconcile_pipeline_id")?.trim()
                ?.takeIf(ChiefOfStaffPipelineContract::isValidDefinitionId)
                ?: throw IllegalArgumentException("Invalid runtime_binding.reconcile_pipeline_id.")
            val ontologyPipelineId = raw.string("ontology_pipeline_id")?.trim()?.takeIf { it.isNotEmpty() }
            if (ontologyPipelineId != null) {
                require(ChiefOfStaffPipelineContract.isValidDefinitionId(ontologyPipelineId)) {
                    "Invalid runtime_binding.ontology_pipeline_id."
                }
            }
            val ownerNpub = raw.string("owner_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid runtime_binding.owner_npub.")
            val workspaceId = lowerUUID(raw.string("workspace_id"))
                ?: throw IllegalArgumentException("Invalid runtime_binding.workspace_id.")
            val subId = lowerUUID(raw.string("subscription_id"))
                ?: throw IllegalArgumentException("runtime_binding.subscription_id must be a valid UUID.")
            val agentNpub = raw.string("agent_npub")?.takeIf { it.isNotBlank() }
            if (agentNpub != null) require(ChiefOfStaffContract.isValidNpub(agentNpub)) { "Invalid runtime_binding.agent_npub." }
            require(workspaceId == expectedWorkspaceId.lowercase()) { "runtime_binding workspace_id mismatch." }
            require(ownerNpub == expectedOwnerNpub) { "runtime_binding owner_npub mismatch." }
            if (expectedAssignedNpub != null) {
                require(agentNpub == expectedAssignedNpub) { "runtime_binding agent_npub must match assigned_npub when assigned." }
            }
            val extra = raw.filterKeys { it !in KNOWN_KEYS }
            return ChiefOfStaffRuntimeBinding(
                autopilotOrigin = normalizedOrigin,
                reconcilePipelineId = pipelineId,
                ownerNpub = ownerNpub,
                workspaceId = workspaceId,
                subscriptionId = subId,
                agentNpub = agentNpub,
                ontologyPipelineId = ontologyPipelineId,
                cronExpression = raw.string("cron_expression")?.trim()?.takeIf { it.isNotEmpty() },
                timezone = raw.string("timezone")?.trim()?.takeIf { it.isNotEmpty() },
                extra = extra,
            )
        }
    }
}

data class ChiefOfStaffAssignment(
    val workspaceId: String,
    val workspaceOwnerNpub: String,
    val appNpub: String,
    val assignmentRevision: Int,
    val assignedActorId: String?,
    val assignedNpub: String?,
    val assignedDisplayName: String?,
    val previousAssignedNpub: String?,
    val updatedByActorId: String,
    val updatedByNpub: String,
    val updatedAt: String,
    val runtimeBinding: ChiefOfStaffRuntimeBinding? = null,
    /** Unknown/future compatible top-level fields preserved across round-trips and re-saves. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val isAssigned: Boolean get() = assignedNpub != null && assignedActorId != null

    /** Per-assignment epoch for isolated owner+agent cohort lookup. */
    val assignmentEpoch: Int get() = if (assignmentRevision >= 1) assignmentRevision else 1

    fun toJson(): JsonObject = buildJsonObject {
        for ((k, v) in extra) {
            if (k !in KNOWN_KEYS) put(k, v)
        }
        put("schema", ChiefOfStaffContract.SCHEMA_ASSIGNMENT)
        put("schema_version", 1)
        put("workspace_id", workspaceId)
        put("workspace_owner_npub", workspaceOwnerNpub)
        put("app_npub", appNpub)
        put("assignment_revision", assignmentRevision)
        put("assigned_actor_id", assignedActorId?.let(::JsonPrimitive) ?: JsonNull)
        put("assigned_npub", assignedNpub?.let(::JsonPrimitive) ?: JsonNull)
        put("assigned_display_name", assignedDisplayName?.let(::JsonPrimitive) ?: JsonNull)
        put("previous_assigned_npub", previousAssignedNpub?.let(::JsonPrimitive) ?: JsonNull)
        put("updated_by_actor_id", updatedByActorId)
        put("updated_by_npub", updatedByNpub)
        put("updated_at", updatedAt)
        runtimeBinding?.let { put("runtime_binding", it.toJson()) }
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            "schema", "schema_version", "workspace_id", "workspace_owner_npub", "app_npub", "assignment_revision",
            "assigned_actor_id", "assigned_npub", "assigned_display_name", "previous_assigned_npub",
            "updated_by_actor_id", "updated_by_npub", "updated_at", "runtime_binding",
        )

        /** Same refusal list as `scripts/chief-of-staff/model.mjs`: the shared assignment never carries private cohort content. */
        private val FORBIDDEN_KEYS = listOf("topics", "evidence", "cohort_id", "cohort_channel_id", "job_status")

        fun parse(doc: JsonObject, expected: ExpectedWorkspaceContext? = null): ChiefOfStaffAssignment {
            require(doc.string("schema") == ChiefOfStaffContract.SCHEMA_ASSIGNMENT && flexibleInt(doc["schema_version"]) == 1) {
                "Invalid Chief of Staff assignment schema."
            }
            for (forbidden in FORBIDDEN_KEYS) {
                require(forbidden !in doc) { "Shared assignment must not contain private field: $forbidden" }
            }
            val workspaceId = lowerUUID(doc.string("workspace_id")) ?: throw IllegalArgumentException("Invalid assignment workspace_id.")
            val ownerNpub = doc.string("workspace_owner_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid assignment workspace_owner_npub.")
            val appNpub = doc.string("app_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid assignment app_npub.")
            val revision = flexibleInt(doc["assignment_revision"])?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Invalid assignment_revision.")
            val updatedByActorId = lowerUUID(doc.string("updated_by_actor_id"))
                ?: throw IllegalArgumentException("Invalid updated_by_actor_id.")
            val updatedByNpub = doc.string("updated_by_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid updated_by_npub.")
            val updatedAt = doc.string("updated_at")?.takeIf(ChiefOfStaffContract::isIsoTimestamp)
                ?: throw IllegalArgumentException("Invalid assignment updated_at.")

            val rawAssignedActorId = doc.string("assigned_actor_id")
            val rawAssignedNpub = doc.string("assigned_npub")
            val assignedActorId: String?
            val assignedNpub: String?
            if (rawAssignedActorId != null || rawAssignedNpub != null) {
                assignedActorId = lowerUUID(rawAssignedActorId)
                    ?: throw IllegalArgumentException("Assigned Chief of Staff requires a valid actor_id.")
                assignedNpub = rawAssignedNpub?.takeIf(ChiefOfStaffContract::isValidNpub)
                    ?: throw IllegalArgumentException("Assigned Chief of Staff requires a valid npub.")
            } else {
                assignedActorId = null
                assignedNpub = null
            }
            val prev = doc.string("previous_assigned_npub")
            if (prev != null) require(ChiefOfStaffContract.isValidNpub(prev)) { "Invalid previous_assigned_npub." }

            if (expected != null) {
                require(workspaceId == expected.workspaceId.lowercase()) { "Assignment workspace_id mismatch." }
                require(ownerNpub == expected.ownerNpub) { "Assignment workspace_owner_npub mismatch." }
                require(appNpub == expected.appNpub) { "Assignment app_npub mismatch." }
            }
            val runtimeBinding = doc.obj("runtime_binding")?.let {
                ChiefOfStaffRuntimeBinding.parse(it, workspaceId, ownerNpub, assignedNpub)
            }
            return ChiefOfStaffAssignment(
                workspaceId = workspaceId,
                workspaceOwnerNpub = ownerNpub,
                appNpub = appNpub,
                assignmentRevision = revision,
                assignedActorId = assignedActorId,
                assignedNpub = assignedNpub,
                assignedDisplayName = doc.string("assigned_display_name"),
                previousAssignedNpub = prev,
                updatedByActorId = updatedByActorId,
                updatedByNpub = updatedByNpub,
                updatedAt = updatedAt,
                runtimeBinding = runtimeBinding,
                extra = doc.filterKeys { it !in KNOWN_KEYS },
            )
        }

        fun empty(
            workspaceId: String,
            ownerNpub: String,
            appNpub: String,
            actorId: String,
            actorNpub: String,
            now: Instant = Instant.now(),
        ): ChiefOfStaffAssignment = ChiefOfStaffAssignment(
            workspaceId = workspaceId.lowercase(),
            workspaceOwnerNpub = ownerNpub,
            appNpub = appNpub,
            assignmentRevision = 0,
            assignedActorId = null,
            assignedNpub = null,
            assignedDisplayName = null,
            previousAssignedNpub = null,
            updatedByActorId = actorId.lowercase(),
            updatedByNpub = actorNpub,
            updatedAt = now.toString(),
        )
    }
}

data class ChiefOfStaffCoverage(
    val supportedTypes: List<String> = listOf("message", "thread", "audio_note", "task", "doc", "file"),
    val scannedCounts: Map<String, Int> = emptyMap(),
    val unsupportedInputs: List<String> = emptyList(),
) {
    companion object {
        fun parse(raw: JsonObject?): ChiefOfStaffCoverage {
            if (raw == null) return ChiefOfStaffCoverage()
            val supported = (raw["supported_types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: listOf("message", "thread", "audio_note", "task", "doc", "file")
            val counts = raw.obj("scanned_counts")?.entries?.associate { (k, v) -> k to (flexibleInt(v) ?: 0) } ?: emptyMap()
            val unsupported = (raw["unsupported_inputs"] as? JsonArray)?.mapNotNull { el ->
                when (el) {
                    is JsonPrimitive -> el.content
                    is JsonObject -> el.string("reason") ?: el.string("kind") ?: el.toString()
                    else -> null
                }
            } ?: emptyList()
            return ChiefOfStaffCoverage(supported, counts, unsupported)
        }
    }
}

data class ChiefOfStaffJobStatus(
    val workspaceId: String,
    val workspaceOwnerNpub: String,
    val appNpub: String,
    val cohortId: String?,
    val assignmentEpoch: Int,
    val boundAgentNpub: String,
    val assignmentRevisionSeen: Int,
    val scheduleState: String,
    val autopilotJobId: String?,
    val pipelineDefinitionId: String?,
    val cronExpression: String?,
    val timezone: String?,
    val lastRunAt: String?,
    val lastSuccessAt: String?,
    val lastError: String?,
    val topicCount: Int,
    val evidenceCount: Int,
    val cursorSummary: String?,
    val coverage: ChiefOfStaffCoverage,
    val updatedAt: String,
) {
    companion object {
        fun parse(doc: JsonObject, expected: ExpectedWorkspaceContext? = null): ChiefOfStaffJobStatus {
            require(doc.string("schema") == ChiefOfStaffContract.SCHEMA_JOB_STATUS && flexibleInt(doc["schema_version"]) == 1) {
                "Invalid Chief of Staff job status schema."
            }
            val workspaceId = lowerUUID(doc.string("workspace_id")) ?: throw IllegalArgumentException("Invalid job status workspace_id.")
            val ownerNpub = doc.string("workspace_owner_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid job status owner.")
            val appNpub = doc.string("app_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid job status app.")
            val agentNpub = doc.string("bound_agent_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid job status bound_agent_npub.")
            val state = doc.string("schedule_state")?.takeIf { it in ChiefOfStaffContract.SCHEDULE_STATES }
                ?: throw IllegalArgumentException("Invalid job status schedule_state.")
            val revSeen = flexibleInt(doc["assignment_revision_seen"])?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Invalid job status assignment_revision_seen.")
            val updatedAt = doc.string("updated_at")?.takeIf(ChiefOfStaffContract::isIsoTimestamp)
                ?: throw IllegalArgumentException("Invalid job status updated_at.")
            if (expected != null) {
                require(workspaceId == expected.workspaceId.lowercase()) { "Job status workspace_id mismatch." }
                require(ownerNpub == expected.ownerNpub) { "Job status owner mismatch." }
                require(appNpub == expected.appNpub) { "Job status app mismatch." }
            }
            return ChiefOfStaffJobStatus(
                workspaceId = workspaceId,
                workspaceOwnerNpub = ownerNpub,
                appNpub = appNpub,
                cohortId = doc.string("cohort_id"),
                assignmentEpoch = flexibleInt(doc["assignment_epoch"]) ?: 1,
                boundAgentNpub = agentNpub,
                assignmentRevisionSeen = revSeen,
                scheduleState = state,
                autopilotJobId = doc.string("autopilot_job_id"),
                pipelineDefinitionId = doc.string("pipeline_definition_id"),
                cronExpression = doc.string("cron_expression"),
                timezone = doc.string("timezone"),
                lastRunAt = doc.string("last_run_at"),
                lastSuccessAt = doc.string("last_success_at"),
                lastError = doc.string("last_error"),
                topicCount = flexibleInt(doc["topic_count"]) ?: 0,
                evidenceCount = flexibleInt(doc["evidence_count"]) ?: 0,
                cursorSummary = doc.string("cursor_summary"),
                coverage = ChiefOfStaffCoverage.parse(doc.obj("coverage")),
                updatedAt = updatedAt,
            )
        }
    }
}

data class ChiefOfStaffTopic(
    val id: String,
    val name: String,
    val kind: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val archived: Boolean = false,
    val sourceOrigin: String = "discovered",
    val updatedAt: String = "",
)

data class ChiefOfStaffEvidence(
    val id: String,
    val topicId: String,
    val sourceType: String,
    val recordId: String,
    val channelId: String? = null,
    val threadId: String? = null,
    val sourceRevision: String? = null,
    val sourceTitle: String = "",
    val explanation: String = "",
    val score: Double = 0.5,
    val status: String = "active",
    val pinned: Boolean = false,
    val updatedAt: String = "",
)

data class ChiefOfStaffOntology(
    val workspaceId: String,
    val workspaceOwnerNpub: String,
    val appNpub: String,
    val cohortId: String,
    val assignmentEpoch: Int,
    val agentNpub: String,
    val snapshotRevision: Int,
    val assignmentRevisionSeen: Int,
    val generatedAt: String,
    val coverage: ChiefOfStaffCoverage,
    val topics: List<ChiefOfStaffTopic>,
    val evidence: List<ChiefOfStaffEvidence>,
) {
    companion object {
        fun parse(doc: JsonObject, expected: ExpectedWorkspaceContext? = null): ChiefOfStaffOntology {
            require(doc.string("schema") == ChiefOfStaffContract.SCHEMA_ONTOLOGY && flexibleInt(doc["schema_version"]) == 1) {
                "Invalid Chief of Staff ontology schema."
            }
            val workspaceId = lowerUUID(doc.string("workspace_id")) ?: throw IllegalArgumentException("Invalid ontology workspace_id.")
            val ownerNpub = doc.string("workspace_owner_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid ontology owner.")
            val appNpub = doc.string("app_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid ontology app.")
            val agentNpub = doc.string("agent_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid ontology agent_npub.")
            val cohortId = doc.string("cohort_id")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid ontology cohort_id.")
            val snapshotRev = flexibleInt(doc["snapshot_revision"])?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Invalid ontology snapshot_revision.")
            val assignmentRevSeen = flexibleInt(doc["assignment_revision_seen"])?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Invalid ontology assignment_revision_seen.")
            val generatedAt = doc.string("generated_at")?.takeIf(ChiefOfStaffContract::isIsoTimestamp)
                ?: throw IllegalArgumentException("Invalid ontology generated_at.")
            val topicsArray = doc["topics"] as? JsonArray ?: throw IllegalArgumentException("Ontology topics missing.")
            val evidenceArray = doc["evidence"] as? JsonArray ?: throw IllegalArgumentException("Ontology evidence missing.")
            val coverageObj = doc.obj("coverage") ?: throw IllegalArgumentException("Ontology coverage missing.")

            val topics = topicsArray.map { el ->
                val o = el.jsonObject
                val id = o.string("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid topic id.")
                val name = o.string("name")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid topic name.")
                val kind = o.string("kind") ?: throw IllegalArgumentException("Invalid topic kind.")
                val aliasesArr = o["aliases"] as? JsonArray ?: throw IllegalArgumentException("Invalid topic aliases.")
                val aliases = aliasesArr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }
                ChiefOfStaffTopic(
                    id = id,
                    name = name,
                    kind = kind,
                    aliases = aliases,
                    description = o.string("description") ?: "",
                    archived = o.bool("archived") == true,
                    sourceOrigin = o.string("source_origin") ?: "discovered",
                    updatedAt = o.string("updated_at") ?: generatedAt,
                )
            }

            val evidence = evidenceArray.map { el ->
                val o = el.jsonObject
                val id = o.string("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid evidence id.")
                val topicId = o.string("topic_id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid evidence topic_id.")
                val sourceType = o.string("source_type")?.takeIf { it in ChiefOfStaffContract.SOURCE_TYPES }
                    ?: throw IllegalArgumentException("Invalid evidence source_type.")
                val recordId = lowerUUID(o.string("record_id")) ?: throw IllegalArgumentException("Invalid evidence record_id.")
                val status = o.string("status")?.takeIf { it in ChiefOfStaffContract.EVIDENCE_STATUSES }
                    ?: throw IllegalArgumentException("Invalid evidence status.")
                val explanation = o.string("explanation") ?: throw IllegalArgumentException("Invalid evidence explanation.")
                ChiefOfStaffEvidence(
                    id = id,
                    topicId = topicId,
                    sourceType = sourceType,
                    recordId = recordId,
                    channelId = lowerUUID(o.string("channel_id")),
                    threadId = lowerUUID(o.string("thread_id")),
                    sourceRevision = o.string("source_revision") ?: flexibleLong(o["source_revision"])?.toString(),
                    sourceTitle = o.string("source_title") ?: "",
                    explanation = explanation,
                    score = flexibleDouble(o["score"]) ?: 0.5,
                    status = status,
                    pinned = o.bool("pinned") == true,
                    updatedAt = o.string("updated_at") ?: generatedAt,
                )
            }

            if (expected != null) {
                require(workspaceId == expected.workspaceId.lowercase()) { "Ontology workspace_id mismatch." }
                require(ownerNpub == expected.ownerNpub) { "Ontology owner mismatch." }
                require(appNpub == expected.appNpub) { "Ontology app mismatch." }
            }

            return ChiefOfStaffOntology(
                workspaceId = workspaceId,
                workspaceOwnerNpub = ownerNpub,
                appNpub = appNpub,
                cohortId = cohortId,
                assignmentEpoch = flexibleInt(doc["assignment_epoch"]) ?: 1,
                agentNpub = agentNpub,
                snapshotRevision = snapshotRev,
                assignmentRevisionSeen = assignmentRevSeen,
                generatedAt = generatedAt,
                coverage = ChiefOfStaffCoverage.parse(coverageObj),
                topics = topics,
                evidence = evidence,
            )
        }
    }
}

data class ChiefOfStaffCorrection(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val kind: String,
    val createdByNpub: String,
    val createdAt: String = Instant.now().toString(),
    val topicId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val aliases: List<String>? = null,
    val topicKind: String? = null,
    val sourceTopicId: String? = null,
    val targetTopicId: String? = null,
    val sourceType: String? = null,
    val recordId: String? = null,
    val archived: Boolean? = null,
    val reason: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("created_by_npub", createdByNpub)
        put("created_at", createdAt)
        topicId?.let { put("topic_id", it) }
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        aliases?.let { list -> put("aliases", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
        topicKind?.let { put("topic_kind", it) }
        sourceTopicId?.let { put("source_topic_id", it) }
        targetTopicId?.let { put("target_topic_id", it) }
        sourceType?.let { put("source_type", it) }
        recordId?.let { put("record_id", it) }
        archived?.let { put("archived", it) }
        reason?.let { put("reason", it) }
    }

    companion object {
        fun parse(o: JsonObject): ChiefOfStaffCorrection {
            val id = o.string("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid correction id.")
            val kind = o.string("kind")?.takeIf { it in ChiefOfStaffContract.CORRECTION_KINDS }
                ?: throw IllegalArgumentException("Invalid correction kind.")
            val createdBy = o.string("created_by_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid correction created_by_npub.")
            val createdAt = o.string("created_at")?.takeIf(ChiefOfStaffContract::isIsoTimestamp)
                ?: throw IllegalArgumentException("Invalid correction created_at.")
            val aliases = (o["aliases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            return ChiefOfStaffCorrection(
                id = id,
                kind = kind,
                createdByNpub = createdBy,
                createdAt = createdAt,
                topicId = o.string("topic_id"),
                name = o.string("name"),
                description = o.string("description"),
                aliases = aliases,
                topicKind = o.string("topic_kind"),
                sourceTopicId = o.string("source_topic_id"),
                targetTopicId = o.string("target_topic_id"),
                sourceType = o.string("source_type"),
                recordId = lowerUUID(o.string("record_id")),
                archived = o["archived"]?.jsonPrimitive?.booleanOrNull,
                reason = o.string("reason"),
            )
        }
    }
}

data class ChiefOfStaffCorrections(
    val workspaceId: String,
    val workspaceOwnerNpub: String,
    val appNpub: String,
    val cohortId: String,
    val assignmentEpoch: Int,
    val corrections: List<ChiefOfStaffCorrection>,
    val updatedAt: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schema", ChiefOfStaffContract.SCHEMA_CORRECTIONS)
        put("schema_version", 1)
        put("workspace_id", workspaceId)
        put("workspace_owner_npub", workspaceOwnerNpub)
        put("app_npub", appNpub)
        put("cohort_id", cohortId)
        put("assignment_epoch", assignmentEpoch)
        put("corrections", buildJsonArray { corrections.forEach { add(it.toJson()) } })
        put("updated_at", updatedAt)
    }

    companion object {
        fun parse(doc: JsonObject, expected: ExpectedWorkspaceContext? = null): ChiefOfStaffCorrections {
            require(doc.string("schema") == ChiefOfStaffContract.SCHEMA_CORRECTIONS && flexibleInt(doc["schema_version"]) == 1) {
                "Invalid Chief of Staff corrections schema."
            }
            val workspaceId = lowerUUID(doc.string("workspace_id")) ?: throw IllegalArgumentException("Invalid corrections workspace_id.")
            val ownerNpub = doc.string("workspace_owner_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid corrections owner.")
            val appNpub = doc.string("app_npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
                ?: throw IllegalArgumentException("Invalid corrections app.")
            val cohortId = doc.string("cohort_id")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid corrections cohort_id.")
            val updatedAt = doc.string("updated_at")?.takeIf(ChiefOfStaffContract::isIsoTimestamp)
                ?: throw IllegalArgumentException("Invalid corrections updated_at.")
            val list = (doc["corrections"] as? JsonArray)?.map { ChiefOfStaffCorrection.parse(it.jsonObject) }
                ?: throw IllegalArgumentException("Invalid corrections list.")
            if (expected != null) {
                require(workspaceId == expected.workspaceId.lowercase()) { "Corrections workspace_id mismatch." }
                require(ownerNpub == expected.ownerNpub) { "Corrections owner mismatch." }
                require(appNpub == expected.appNpub) { "Corrections app mismatch." }
            }
            return ChiefOfStaffCorrections(
                workspaceId = workspaceId,
                workspaceOwnerNpub = ownerNpub,
                appNpub = appNpub,
                cohortId = cohortId,
                assignmentEpoch = flexibleInt(doc["assignment_epoch"]) ?: 1,
                corrections = list,
                updatedAt = updatedAt,
            )
        }
    }
}

// --- Explicit-permission ACL & authoritative provenance verification ---

object ChiefOfStaffAcl {
    data class MemberRoleEntry(
        val actorId: String,
        val npub: String,
        val role: String?,
        val permissions: Set<String> = emptySet(),
        /** Exact server-derived `member.authority.can_manage_workspace` boolean from `GET /members`. */
        val canManageWorkspace: Boolean = false,
    ) {
        /**
         * Verified owner identity or NStack's strict server boolean only. [role] and [permissions] are directory labels
         * and never authority; an older server that omits `authority` fails closed for delegated managers.
         */
        fun isWorkspaceManager(ownerNpub: String): Boolean = npub == ownerNpub || canManageWorkspace
    }
    data class GroupEntry(
        val id: String,
        val kind: String?,
        val name: String?,
        val effectiveMemberIds: Set<String>? = null,
    )

    private fun grantPrincipalId(g: JsonObject): String? {
        val p = g.obj("principal")
        return lowerUUID(p?.string("actor_id") ?: p?.string("group_id") ?: p?.string("id") ?: g.string("principal_id"))
    }

    private fun isActorGrant(g: JsonObject): Boolean {
        val p = g.obj("principal")
        val pType = g.string("principal_type")
        val stored = g.string("stored_principal_type")
        val nestedType = p?.string("type")
        return (pType == "actor" || (pType == "person" && stored == "actor")) &&
            (nestedType == null || nestedType == "actor" || nestedType == "person") &&
            (stored == null || stored == "actor")
    }

    private fun isGroupGrant(g: JsonObject): Boolean = g.string("principal_type") == "group"

    private fun permissionsOf(g: JsonObject): Set<String>? {
        val arr = g["permissions"] as? JsonArray ?: return null
        val perms = HashSet<String>()
        for (el in arr) {
            val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            perms += s
        }
        return perms
    }

    /**
     * Verifies authoritative NStack server provenance on the assignment channel, file, and current version
     * against workspace manager roles from `/members`, without requiring admin-only `/groups` or `/grants`.
     *
     * Self-asserted actor fields inside the JSON payload are never trusted on their own: the actual NStack
     * channel creator, file creator, and latest version creator must all be verified workspace managers,
     * and the payload's `updated_by_actor_id` / `updated_by_npub` must match the latest version's server actor.
     */
    fun verifyManagerProvenance(
        channelRaw: JsonObject,
        fileRaw: JsonObject,
        members: List<MemberRoleEntry>,
        ownerNpub: String,
        payloadUpdatedByActorId: String,
        payloadUpdatedByNpub: String,
        additionalVerifiedManagers: Map<String, String> = emptyMap(),
    ) {
        val managerById = LinkedHashMap<String, String>()
        for (m in members) {
            if (m.isWorkspaceManager(ownerNpub)) {
                managerById[m.actorId.lowercase()] = m.npub
            }
        }
        for ((id, npub) in additionalVerifiedManagers) {
            managerById[id.lowercase()] = npub
        }
        require(managerById.isNotEmpty()) { "No authoritative workspace managers found to verify Chief of Staff provenance." }

        fun verifyActor(label: String, actorId: String?, actorNpub: String?): Pair<String, String> {
            val id = lowerUUID(actorId)
                ?: throw IllegalArgumentException("Chief of Staff $label is missing authoritative server actor_id.")
            val managerNpub = managerById[id]
                ?: throw IllegalArgumentException("Chief of Staff $label was not created by a workspace manager.")
            if (actorNpub != null) {
                require(actorNpub == managerNpub) {
                    "Chief of Staff $label actor npub does not match workspace manager identity."
                }
            }
            return id to managerNpub
        }

        // 1. Channel creator (when exposed by Tower; live GET /scopes/:id/channels omits created_by_actor_id).
        val channelCreatorId = channelRaw.string("created_by_actor_id")
        if (channelCreatorId != null) {
            verifyActor("assignment channel", channelCreatorId, channelRaw.string("created_by_actor_npub"))
        }
        // 2. File creator must be a verified workspace manager.
        verifyActor(
            "assignment file",
            fileRaw.string("created_by_actor_id"),
            fileRaw.string("created_by_actor_npub"),
        )
        // 3. Current version creator (or file updated_by_actor_id when current_version is null on GET /files/:id).
        val currentVersion = fileRaw.obj("current_version")
        val versionActorId = currentVersion?.string("created_by_actor_id")
            ?: fileRaw.string("updated_by_actor_id")
            ?: fileRaw.string("created_by_actor_id")
        val versionActorNpub = currentVersion?.string("created_by_actor_npub")
            ?: fileRaw.string("updated_by_actor_npub")
            ?: fileRaw.string("created_by_actor_npub")
        val (verifiedWriterId, verifiedWriterNpub) = verifyActor("assignment version", versionActorId, versionActorNpub)

        require(payloadUpdatedByActorId.lowercase() == verifiedWriterId && payloadUpdatedByNpub == verifiedWriterNpub) {
            "Chief of Staff assignment payload author does not match authoritative NStack version provenance."
        }
    }

    /**
     * Verifies the workspace Assignment channel ACL (used on manager reads/writes, never required for ordinary
     * read-only workspace members who do not have `/groups` or `/grants` admin inventory access).
     *
     * When `effectiveMemberIds` is present on a system group, structurally verifies every member is a manager
     * rather than trusting group display name alone.
     */
    fun verifyAssignmentChannelGrants(
        grants: List<JsonObject>,
        members: List<MemberRoleEntry>,
        groups: List<GroupEntry>,
        ownerNpub: String,
        additionalVerifiedManagerIds: Set<String> = emptySet(),
    ): Pair<Boolean, Boolean> {
        val managerActorIds = members
            .filter { it.isWorkspaceManager(ownerNpub) }
            .map { it.actorId.lowercase() }
            .toSet() + additionalVerifiedManagerIds.map { it.lowercase() }
        val groupById = groups.associateBy { it.id.lowercase() }

        fun isVerifiedAdminGroup(group: GroupEntry?): Boolean {
            if (group == null || group.kind != "system") return false
            val eff = group.effectiveMemberIds
            // Structural only: a group's display name is never authority, so a group without effective members fails closed.
            return eff != null && eff.isNotEmpty() && eff.all { it in managerActorIds }
        }

        fun isWorkspaceGroup(group: GroupEntry?): Boolean =
            group != null && group.kind == "system" && !isVerifiedAdminGroup(group) && group.name?.lowercase() == "workspace"

        var hasManagerWriter = false
        var hasWorkspaceReader = false
        val writePerms = setOf("file.write", "channel.manage", "channel.grants.manage")

        for (g in grants) {
            val pid = grantPrincipalId(g) ?: throw IllegalArgumentException("Malformed Chief of Staff assignment grant.")
            val perms = permissionsOf(g) ?: throw IllegalArgumentException("Malformed Chief of Staff grant permissions.")
            val canWrite = perms.any { it in writePerms }
            when {
                isGroupGrant(g) -> {
                    val group = groupById[pid]
                    when {
                        isVerifiedAdminGroup(group) -> {
                            if (perms.containsAll(setOf("channel.read", "file.read", "file.write"))) hasManagerWriter = true
                        }
                        isWorkspaceGroup(group) -> {
                            require(!canWrite) { "Workspace member group must not have write permission on Chief of Staff assignment." }
                            if (perms.containsAll(setOf("channel.read", "file.read"))) hasWorkspaceReader = true
                        }
                        canWrite -> throw IllegalArgumentException("Non-admin group cannot hold write permission on Chief of Staff assignment.")
                    }
                }
                isActorGrant(g) -> if (canWrite) {
                    require(pid in managerActorIds) { "Non-manager actor cannot hold write permission on Chief of Staff assignment." }
                    if (perms.containsAll(setOf("channel.read", "file.read", "file.write"))) hasManagerWriter = true
                }
                else -> throw IllegalArgumentException("Unsupported grant principal type on Chief of Staff assignment channel.")
            }
        }
        require(hasManagerWriter) { "Chief of Staff assignment channel has no verified manager write grant." }
        return hasManagerWriter to hasWorkspaceReader
    }

    /**
     * Verifies the owner+agent private ontology cohort channel ACL:
     * Strictly 2 direct actor grants (owner + assigned agent), zero groups, zero extra principals.
     */
    fun verifyCohortChannelGrants(
        grants: List<JsonObject>,
        members: List<MemberRecord>,
        ownerNpub: String,
        agentNpub: String,
    ): Pair<String, String> {
        val owners = members.filter { it.npub == ownerNpub }
        val agents = members.filter { it.npub == agentNpub }
        require(owners.size == 1 && agents.size == 1 && ownerNpub != agentNpub) {
            "Ontology cohort owner and agent must be distinct current workspace members."
        }
        val ownerId = owners.single().actorId.lowercase()
        val agentId = agents.single().actorId.lowercase()
        require(ownerId.isNotBlank() && agentId.isNotBlank() && ownerId != agentId) {
            "Ambiguous ontology cohort identities."
        }
        var hasOwner = false
        var hasAgent = false
        val seen = HashSet<String>()
        for (g in grants) {
            require(isActorGrant(g)) { "Private ontology cohort must not grant access to groups." }
            val p = g.obj("principal")
            val pid = grantPrincipalId(g) ?: throw IllegalArgumentException("Ambiguous ontology cohort grant identity.")
            require(seen.add(pid)) { "Duplicate ontology cohort grant." }
            val candidateIds = listOfNotNull(p?.string("actor_id"), p?.string("id"), g.string("principal_id"))
            require(candidateIds.all { it.lowercase() == pid }) { "Ambiguous ontology cohort grant identity." }
            val expectedNpub = if (pid == ownerId) ownerNpub else agentNpub
            if (p?.containsKey("npub") == true) {
                require(p.string("npub") == expectedNpub) { "Ontology cohort grant npub mismatch." }
            }
            val perms = permissionsOf(g) ?: throw IllegalArgumentException("Ontology cohort permissions missing.")
            val required = buildSet {
                add("channel.read")
                add("file.read")
                add("file.write")
                if (pid == ownerId) {
                    add("channel.manage")
                    add("channel.grants.manage")
                }
            }
            require(perms.containsAll(required)) { "Ontology cohort effective permissions are incomplete." }
            when (pid) {
                ownerId -> hasOwner = true
                agentId -> hasAgent = true
                else -> throw IllegalArgumentException("Private ontology cohort must only include owner and assigned agent.")
            }
        }
        require(hasOwner && hasAgent) { "Ontology cohort owner or agent grant is missing." }
        return ownerId to agentId
    }
}

// --- Deterministic correction replay & search ---

data class ChiefOfStaffAppliedOntology(
    val topics: List<ChiefOfStaffTopic>,
    val evidence: List<ChiefOfStaffEvidence>,
)

object ChiefOfStaffOntologyLogic {
    /**
     * Replays durable corrections deterministically on top of discovered topics & evidence,
     * matching `applyCorrections` in `scripts/chief-of-staff/model.mjs`.
     */
    fun applyCorrections(
        discoveredTopics: List<ChiefOfStaffTopic>,
        discoveredEvidence: List<ChiefOfStaffEvidence>,
        corrections: List<ChiefOfStaffCorrection>,
    ): ChiefOfStaffAppliedOntology {
        val topicMap = LinkedHashMap<String, ChiefOfStaffTopic>()
        for (t in discoveredTopics) {
            topicMap[t.id] = t.copy(aliases = t.aliases.filter { it.isNotBlank() }.distinct())
        }
        val mergeRedirects = HashMap<String, String>()
        fun resolveId(id: String): String {
            var cur = id
            val visited = HashSet<String>()
            while (mergeRedirects.containsKey(cur) && visited.add(cur)) {
                cur = mergeRedirects.getValue(cur)
            }
            return cur
        }
        val rejectedEvidence = HashSet<String>()
        val pinnedEvidence = HashSet<String>()

        val ordered = corrections.sortedWith(
            compareBy<ChiefOfStaffCorrection> { runCatching { OffsetDateTime.parse(it.createdAt).toInstant().toEpochMilli() }.getOrDefault(0L) }
                .thenBy { it.id },
        )

        for (c in ordered) {
            when (c.kind) {
                "rename" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val nextName = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val t = topicMap[tid] ?: continue
                    val nextAliases = if (t.name != nextName && t.name !in t.aliases) t.aliases + t.name else t.aliases
                    topicMap[tid] = t.copy(
                        name = nextName,
                        aliases = nextAliases.filter { it != nextName }.distinct(),
                        sourceOrigin = "corrected",
                        updatedAt = c.createdAt,
                    )
                }
                "set_description" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val desc = c.description ?: continue
                    val t = topicMap[tid] ?: continue
                    topicMap[tid] = t.copy(description = desc.trim(), sourceOrigin = "corrected", updatedAt = c.createdAt)
                }
                "set_aliases" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val list = c.aliases ?: continue
                    val t = topicMap[tid] ?: continue
                    topicMap[tid] = t.copy(
                        aliases = list.map { it.trim() }.filter { it.isNotEmpty() && it != t.name }.distinct(),
                        sourceOrigin = "corrected",
                        updatedAt = c.createdAt,
                    )
                }
                "set_kind" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val kind = c.topicKind?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val t = topicMap[tid] ?: continue
                    topicMap[tid] = t.copy(kind = kind, sourceOrigin = "corrected", updatedAt = c.createdAt)
                }
                "merge_topics" -> {
                    val srcId = c.sourceTopicId?.let(::resolveId) ?: continue
                    val dstId = c.targetTopicId?.let(::resolveId) ?: continue
                    if (srcId != dstId) {
                        mergeRedirects[srcId] = dstId
                        val src = topicMap[srcId]
                        val dst = topicMap[dstId]
                        if (src != null && dst != null) {
                            val mergedAliases = (dst.aliases + src.name + src.aliases).filter { it.isNotBlank() && it != dst.name }.distinct()
                            val mergedDesc = dst.description.ifBlank { src.description }
                            topicMap[dstId] = dst.copy(
                                aliases = mergedAliases,
                                description = mergedDesc,
                                sourceOrigin = "corrected",
                                updatedAt = c.createdAt,
                            )
                            topicMap.remove(srcId)
                        } else if (src != null) {
                            topicMap[dstId] = src.copy(id = dstId, sourceOrigin = "corrected", updatedAt = c.createdAt)
                            topicMap.remove(srcId)
                        }
                    }
                }
                "archive_topic" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val t = topicMap[tid] ?: continue
                    topicMap[tid] = t.copy(archived = c.archived != false, sourceOrigin = "corrected", updatedAt = c.createdAt)
                }
                "reject_evidence" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val st = c.sourceType ?: continue
                    val rid = c.recordId ?: continue
                    val key = "$tid::$st::$rid"
                    rejectedEvidence += key
                    pinnedEvidence -= key
                }
                "pin_evidence" -> {
                    val tid = c.topicId?.let(::resolveId) ?: continue
                    val st = c.sourceType ?: continue
                    val rid = c.recordId ?: continue
                    val key = "$tid::$st::$rid"
                    rejectedEvidence -= key
                    pinnedEvidence += key
                }
            }
        }

        val deduped = LinkedHashMap<String, ChiefOfStaffEvidence>()
        for (ev in discoveredEvidence) {
            val tid = resolveId(ev.topicId)
            if (!topicMap.containsKey(tid)) continue
            val key = "$tid::${ev.sourceType}::${ev.recordId}"
            if (key in rejectedEvidence) continue
            val pinned = key in pinnedEvidence || ev.pinned
            val nextScore = if (pinned) maxOf(ev.score, 0.99) else ev.score
            val existing = deduped[key]
            if (existing == null || nextScore > existing.score) {
                val idHash = Sha256Hex.of(key.toByteArray(Charsets.UTF_8)).take(16)
                deduped[key] = ev.copy(
                    id = "ev_$idHash",
                    topicId = tid,
                    score = nextScore,
                    pinned = pinned,
                )
            }
        }

        val sortedTopics = topicMap.values.sortedWith(compareBy<ChiefOfStaffTopic> { it.archived }.thenBy { it.name.lowercase() })
        val sortedEvidence = deduped.values.sortedWith(
            compareByDescending<ChiefOfStaffEvidence> { it.pinned }
                .thenByDescending { it.score }
                .thenBy { it.id },
        )
        return ChiefOfStaffAppliedOntology(sortedTopics, sortedEvidence)
    }

    /** Native searchable topic reference filter across name, aliases, kind, description and evidence titles/explanations. */
    fun searchTopics(
        topics: List<ChiefOfStaffTopic>,
        evidence: List<ChiefOfStaffEvidence>,
        query: String,
        includeArchived: Boolean = false,
    ): List<ChiefOfStaffTopic> {
        val visible = if (includeArchived) topics else topics.filter { !it.archived }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return visible
        val byTopic = evidence.groupBy { it.topicId }
        return visible.filter { t ->
            t.name.lowercase().contains(q) ||
                t.kind.lowercase().contains(q) ||
                t.description.lowercase().contains(q) ||
                t.aliases.any { it.lowercase().contains(q) } ||
                byTopic[t.id].orEmpty().any { ev ->
                    ev.sourceTitle.lowercase().contains(q) || ev.explanation.lowercase().contains(q)
                }
        }
    }

    /**
     * Finds the viewer's OWN authorized 2-party DM channel with [agentNpub] inside the workspace's DM scope.
     * Never returns another member's DM, a self-DM, a group DM, or an access-removed channel.
     */
    fun findViewerDirectMessage(
        scopes: List<ScopeRecord>,
        channels: List<ChannelRecord>,
        viewerNpub: String,
        agentNpub: String,
    ): ChannelRecord? {
        if (viewerNpub == agentNpub || !ChiefOfStaffContract.isValidNpub(viewerNpub) || !ChiefOfStaffContract.isValidNpub(agentNpub)) {
            return null
        }
        val dmScopeIds = scopes
            .filter { !it.accessRemoved && SidebarNavigation.isDirectMessageScope(NavScope(it.id, it.name, it.kind)) }
            .map { it.id }
            .toSet()
        val expected = setOf(viewerNpub, agentNpub)
        return channels.firstOrNull { ch ->
            if (ch.accessRemoved || ch.scopeId !in dmScopeIds) return@firstOrNull false
            val nav = NavChannel(ch.id, ch.scopeId, ch.name, ch.kind, ch.position, ch.raw)
            if (!SidebarNavigation.isDirectMessageChannel(nav)) return@firstOrNull false
            val participants = SidebarNavigation.participants(nav).toSet()
            participants.size == 2 && participants == expected
        }
    }

    fun directMessageScope(scopes: List<ScopeRecord>): ScopeRecord? =
        scopes.firstOrNull { !it.accessRemoved && SidebarNavigation.isDirectMessageScope(NavScope(it.id, it.name, it.kind)) }
}
