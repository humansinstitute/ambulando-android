package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.chiefofstaff.AssignmentLoadResult
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffAssignment
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffCorrection
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffError
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffEvidence
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffJobStatus
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffOntologyLogic
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffPipelineClient
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffPipelineContract
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffReconcileConfig
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffReconcileOutcome
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffReconcilePlan
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffReconcileRequest
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffReconcileStore
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffStore
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffTopic
import studio.otherstuff.ambulando.core.chiefofstaff.CohortLoadResult
import studio.otherstuff.ambulando.core.chiefofstaff.appendChiefOfStaffCorrection
import studio.otherstuff.ambulando.core.chiefofstaff.setChiefOfStaffAssignment
import studio.otherstuff.ambulando.core.management.ConfirmedAutopilotOrigin
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.directoryMembers
import studio.otherstuff.ambulando.core.store.liveQuery
import studio.otherstuff.ambulando.core.transport.ConnectionFailure
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.shortLabel
import java.time.Instant

// --- Manage Workspace: Chief of Staff Assignment ---

@Composable
internal fun ChiefOfStaffAdminPage(
    model: AppModel,
    session: AdministrationSession,
    workspace: WorkspaceRecord,
    openTopics: () -> Unit,
    back: () -> Unit,
) {
    val boundaryId = model.activeBoundaryId ?: return
    val c = model.connection ?: return
    val ownerNpub = workspace.ownerNpub
    val scope = rememberCoroutineScope()

    var loading by remember(boundaryId, workspace.id) { mutableStateOf(true) }
    var working by remember(boundaryId, workspace.id) { mutableStateOf(false) }
    var assignment by remember(boundaryId, workspace.id) { mutableStateOf(model.chiefOfStaffAssignment?.takeIf { it.workspaceId == workspace.id }) }
    var needsSetup by remember(boundaryId, workspace.id) { mutableStateOf(false) }
    var cohortState by remember(boundaryId, workspace.id) { mutableStateOf<CohortLoadResult?>(null) }
    var selectedNpub by remember(boundaryId, workspace.id) { mutableStateOf<String?>(assignment?.assignedNpub) }
    var error by remember(boundaryId, workspace.id) { mutableStateOf<String?>(null) }
    var statusMessage by remember(boundaryId, workspace.id) { mutableStateOf<String?>(null) }
    var reconcileStatus by remember(boundaryId, workspace.id) { mutableStateOf<String?>(null) }
    var reconcileError by remember(boundaryId, workspace.id) { mutableStateOf<String?>(null) }
    var autopilotOriginInput by remember(boundaryId, workspace.id) { mutableStateOf("") }
    var pipelineIdInput by remember(boundaryId, workspace.id) { mutableStateOf(ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID) }
    var subscriptionIdInput by remember(boundaryId, workspace.id) { mutableStateOf("") }
    var confirmedOrigin by remember(boundaryId, workspace.id) { mutableStateOf<ConfirmedAutopilotOrigin?>(null) }
    var pendingConfirm by remember(boundaryId, workspace.id) { mutableStateOf<Pair<String?, String>?>(null) }

    val members by remember(model.store, workspace.id) {
        model.store.liveQuery({ it.workspaceId == workspace.id || it.table == StoreTable.PROFILES }) {
            model.store.reader.directoryMembers(workspace.id)
        }
    }.collectAsState(emptyList())
    val agentMembers = remember(members) { members.filter { it.isAgent }.sortedBy { it.label.lowercase() } }

    fun isCurrent(): Boolean =
        session.isCurrent && model.activeBoundaryId == boundaryId && model.workspace?.id == workspace.id && !workspace.accessRemoved

    LaunchedEffect(boundaryId, workspace.id, assignment?.runtimeBinding) {
        val binding = assignment?.runtimeBinding
        if (binding != null && isCurrent()) {
            autopilotOriginInput = binding.autopilotOrigin
            pipelineIdInput = binding.reconcilePipelineId
            subscriptionIdInput = binding.subscriptionId.orEmpty()
            confirmedOrigin = runCatching { ConfirmedAutopilotOrigin.of(binding.autopilotOrigin) }.getOrNull()
        } else {
            val savedCfg = ChiefOfStaffReconcileStore.load(model.store, c.id, workspace.id, workspace.appNpub)
            if (savedCfg != null && isCurrent()) {
                autopilotOriginInput = savedCfg.autopilotOrigin
                pipelineIdInput = savedCfg.pipelineDefinitionId
                subscriptionIdInput = savedCfg.subscriptionId.orEmpty()
                confirmedOrigin = runCatching { ConfirmedAutopilotOrigin.of(savedCfg.autopilotOrigin) }.getOrNull()
            }
        }
    }

    suspend fun reload() {
        val runtime = model.active
        if (!isCurrent() || ownerNpub == null || runtime == null) {
            loading = false
            return
        }
        loading = true
        error = null
        try {
            val loaded = model.refreshChiefOfStaffAssignment(
                runtime = runtime,
                workspace = workspace,
                priority = studio.otherstuff.ambulando.core.sync.SyncPriority.USER,
                verifyAcl = session.canAdminister,
            )
            if (!isCurrent()) return
            when (loaded) {
                null, AssignmentLoadResult.NeedsSetup, is AssignmentLoadResult.NeedsFile -> {
                    assignment = null
                    needsSetup = true
                    cohortState = null
                }
                is AssignmentLoadResult.Ready -> {
                    assignment = loaded.assignment
                    needsSetup = false
                    selectedNpub = loaded.assignment.assignedNpub
                    cohortState = if (loaded.assignment.isAssigned) {
                        model.loadChiefOfStaffCohort(
                            runtime = runtime,
                            workspace = workspace,
                            assignment = loaded.assignment,
                            priority = studio.otherstuff.ambulando.core.sync.SyncPriority.VISIBLE,
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent()) {
                error = (e as? ChiefOfStaffError)?.message ?: ConnectionFailure.userMessage(e) ?: "Could not load Chief of Staff assignment."
            }
        } finally {
            if (isCurrent()) loading = false
        }
    }

    LaunchedEffect(boundaryId, workspace.id, session.canAdminister) { reload() }

    suspend fun invokeReconcile(
        targetAssignment: ChiefOfStaffAssignment,
        previousBinding: studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffRuntimeBinding? = null,
    ) {
        val plan = ChiefOfStaffReconcilePlan.of(targetAssignment, previousBinding)
        if (plan == null) {
            reconcileError = null
            reconcileStatus = "Assignment saved. Schedule setup pending: no Autopilot runtime binding is saved for this agent."
            return
        }
        val origin = runCatching { ConfirmedAutopilotOrigin.of(plan.binding.autopilotOrigin) }.getOrNull()
        if (origin == null) {
            reconcileStatus = null
            reconcileError = "Schedule setup error: the saved Autopilot origin is not a clean HTTPS origin."
            return
        }
        val defId = plan.binding.reconcilePipelineId
        val subId = plan.subscriptionId
        reconcileError = null
        reconcileStatus = if (plan.isCleanup) {
            "Invoking Autopilot stale-schedule cleanup ($defId)…"
        } else {
            "Invoking Autopilot reconciliation ($defId)…"
        }
        val client = ChiefOfStaffPipelineClient(model.identity, model.workspaceTransport)
        val outcome = withContext(Dispatchers.IO) {
            client.reconcile(
                origin = origin,
                signerNpub = c.signerNpub,
                definitionId = defId,
                request = ChiefOfStaffReconcileRequest(
                    workspaceId = workspace.id,
                    ownerNpub = ownerNpub ?: return@withContext ChiefOfStaffReconcileOutcome.Failed(null, "missing_owner", "Workspace owner npub is unavailable."),
                    appNpub = workspace.appNpub,
                    assignedAgentNpub = targetAssignment.assignedNpub,
                    assignmentRevision = targetAssignment.assignmentRevision,
                    assignmentEpoch = targetAssignment.assignmentEpoch,
                    towerUrl = c.origin,
                    subscriptionId = subId,
                ),
            )
        }
        if (!isCurrent()) return
        when (outcome) {
            is ChiefOfStaffReconcileOutcome.Completed -> {
                reconcileError = null
                val run = outcome.runId?.let { " · run ${it.take(8)}" } ?: ""
                reconcileStatus = when (outcome.scheduleState) {
                    "pending" -> "Schedule setup pending${outcome.lastError?.let { ": $it" } ?: "."}$run"
                    null -> "Autopilot reconciliation completed (${outcome.status}$run)."
                    else -> "Autopilot reconciliation completed: schedule ${outcome.scheduleState}$run."
                }
            }
            is ChiefOfStaffReconcileOutcome.Accepted -> {
                reconcileError = null
                reconcileStatus = "Autopilot reconciliation accepted (${outcome.status}${outcome.runId?.let { " · run ${it.take(8)}" } ?: ""})."
            }
            is ChiefOfStaffReconcileOutcome.DefinitionNotFound -> {
                reconcileStatus = null
                reconcileError = "Schedule setup pending/error: ${outcome.message}"
            }
            is ChiefOfStaffReconcileOutcome.Failed -> {
                reconcileStatus = null
                reconcileError = "Schedule setup error: ${outcome.message}"
            }
        }
    }

    fun applyAssignmentChange(targetNpub: String?) {
        if (!isCurrent() || ownerNpub == null || working) return
        working = true
        error = null
        statusMessage = null
        reconcileStatus = null
        reconcileError = null
        scope.launch {
            try {
                val setupScopeId = model.scopes.firstOrNull { !it.accessRemoved && it.kind?.lowercase() != "dm" }?.id
                val expectedRev = assignment?.assignmentRevision
                val previousBinding = assignment?.runtimeBinding
                val sameAgent = targetNpub != null && targetNpub == assignment?.assignedNpub
                val typedSubUuid = studio.otherstuff.ambulando.core.transport.lowerUUID(subscriptionIdInput.trim())
                // Never carry a previous agent's subscription_id onto a newly assigned agent or removal!
                val explicitBinding = if (targetNpub != null && confirmedOrigin != null && typedSubUuid != null &&
                    (sameAgent || typedSubUuid != previousBinding?.subscriptionId)
                ) {
                    val defId = pipelineIdInput.trim().ifEmpty { ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID }
                    if (ChiefOfStaffPipelineContract.isValidDefinitionId(defId)) {
                        // Same agent: keep the saved optional/unknown fields (ontology pipeline, cadence, future options).
                        previousBinding?.takeIf { sameAgent }?.copy(
                            autopilotOrigin = confirmedOrigin!!.url,
                            reconcilePipelineId = defId,
                            subscriptionId = typedSubUuid,
                        ) ?: studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffRuntimeBinding(
                            autopilotOrigin = confirmedOrigin!!.url,
                            reconcilePipelineId = defId,
                            ownerNpub = ownerNpub,
                            workspaceId = workspace.id.lowercase(),
                            subscriptionId = typedSubUuid,
                            agentNpub = targetNpub,
                        )
                    } else null
                } else null
                val saved = withContext(Dispatchers.IO) {
                    model.towerAPI(c).setChiefOfStaffAssignment(
                        workspaceId = workspace.id,
                        ownerNpub = ownerNpub,
                        appNpub = workspace.appNpub,
                        agentNpub = targetNpub,
                        expectedRevision = expectedRev,
                        scopeIdForSetup = setupScopeId,
                        runtimeBinding = explicitBinding,
                    )
                }
                if (!isCurrent()) return@launch
                assignment = saved.assignment
                selectedNpub = saved.assignment.assignedNpub
                needsSetup = false
                // Clear old cohort state immediately when assignment changes so prior agent topics never survive a switch.
                cohortState = null
                model.chiefOfStaffAssignment = saved.assignment
                ChiefOfStaffStore.saveAssignment(model.store, c.id, workspace.id, workspace.appNpub, saved.assignment, saved.channel.id, saved.file.id)
                statusMessage = if (saved.assignment.isAssigned) {
                    "Chief of Staff updated (revision ${saved.assignment.assignmentRevision})."
                } else {
                    "Chief of Staff removed (revision ${saved.assignment.assignmentRevision})."
                }
                invokeReconcile(saved.assignment, previousBinding = previousBinding)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrent()) {
                    error = (e as? ChiefOfStaffError)?.message ?: ConnectionFailure.userMessage(e) ?: "Could not update Chief of Staff."
                }
            } finally {
                if (isCurrent()) working = false
            }
        }
    }

    AdminPage("Chief of Staff", session, back, "admin.chiefOfStaffPage") {
        Text(
            "Select an existing workspace agent as Chief of Staff. Assignment grants no new source permissions and mentions stay normal.",
            style = MaterialTheme.typography.bodySmall,
            color = Amb.colors.mutedForeground,
        )
        if (loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Checking workspace assignment…", modifier = Modifier.testTag("admin.chiefOfStaff.loading"))
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.chiefOfStaff.error"))
        }
        statusMessage?.let {
            Text(it, color = Amb.colors.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.chiefOfStaff.status"))
        }

        // Current assignment summary card
        val cur = assignment
        Column(
            Modifier.fillMaxWidth()
                .background(Amb.colors.card, RoundedCornerShape(Amb.radiusPanel))
                .border(1.dp, Amb.colors.border, RoundedCornerShape(Amb.radiusPanel))
                .padding(12.dp)
                .testTag("admin.chiefOfStaff.current"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Current assignment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (cur != null && cur.isAssigned) {
                val label = cur.assignedDisplayName?.takeIf { it.isNotBlank() } ?: shortLabel(cur.assignedNpub!!)
                Text("Assigned: $label (${shortLabel(cur.assignedNpub!!)})", fontWeight = FontWeight.Medium)
                Text("Revision ${cur.assignmentRevision} · Updated ${cur.updatedAt}", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
            } else if (needsSetup) {
                Text("Not initialized yet", color = Amb.colors.mutedForeground)
            } else {
                Text("No Chief of Staff assigned", color = Amb.colors.mutedForeground)
            }
            Spacer(Modifier.height(4.dp))
            JobStatusSummaryLine(cur, cohortState)
        }

        if (cur != null && cur.isAssigned) {
            OutlinedButton(
                onClick = openTopics,
                modifier = Modifier.testTag("admin.chiefOfStaff.openTopics"),
            ) {
                Text("Open Chief of Staff topics & status")
            }
        }

        HorizontalDivider()
        Text("Workspace agents", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!session.canAdminister) {
            Text(
                "Only workspace owners and admins can assign, change, or remove the Chief of Staff.",
                style = MaterialTheme.typography.bodySmall,
                color = Amb.colors.mutedForeground,
                modifier = Modifier.testTag("admin.chiefOfStaff.readOnly"),
            )
        }
        if (agentMembers.isEmpty()) {
            Text(
                "No agent members found in this workspace. Add an agent member first.",
                style = MaterialTheme.typography.bodySmall,
                color = Amb.colors.mutedForeground,
                modifier = Modifier.testTag("admin.chiefOfStaff.noAgents"),
            )
        } else {
            for (agent in agentMembers) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = session.canAdminister && !working) { selectedNpub = agent.npub }
                        .padding(vertical = 4.dp)
                        .testTag("admin.chiefOfStaff.agent.${agent.npub}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedNpub == agent.npub,
                        onClick = if (session.canAdminister && !working) ({ selectedNpub = agent.npub }) else null,
                        enabled = session.canAdminister && !working,
                    )
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(agent.label, fontWeight = FontWeight.Medium)
                        Text(shortLabel(agent.npub), style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canSave = session.canAdminister && !working && selectedNpub != null && selectedNpub != assignment?.assignedNpub
            TextButton(
                onClick = {
                    val chosen = agentMembers.firstOrNull { it.npub == selectedNpub }
                    pendingConfirm = selectedNpub to "Assign ${chosen?.label ?: shortLabel(selectedNpub!!)} as Chief of Staff for ${workspace.label}? This grants no new source permissions and starts a new isolated topic epoch."
                },
                enabled = canSave,
                modifier = Modifier.testTag("admin.chiefOfStaff.save"),
            ) {
                Text(if (assignment?.isAssigned == true) "Change Chief of Staff" else "Assign Chief of Staff")
            }
            if (assignment?.isAssigned == true) {
                TextButton(
                    onClick = {
                        pendingConfirm = null to "Remove the current Chief of Staff assignment for ${workspace.label}? Historical records are preserved."
                    },
                    enabled = session.canAdminister && !working,
                    modifier = Modifier.testTag("admin.chiefOfStaff.remove"),
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(
                onClick = { scope.launch { reload() } },
                enabled = !loading && !working,
                modifier = Modifier.testTag("admin.chiefOfStaff.refresh"),
            ) {
                Text("Refresh")
            }
        }

        HorizontalDivider()
        Text("Autopilot schedule reconciliation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Confirm your Autopilot HTTPS host to invoke the pinned reconciliation pipeline in the workspace owner's Autopilot space when saving or removing an assignment. Redirects are refused.",
            style = MaterialTheme.typography.bodySmall,
            color = Amb.colors.mutedForeground,
        )
        OutlinedTextField(
            value = autopilotOriginInput,
            onValueChange = { autopilotOriginInput = it },
            label = { Text("Autopilot HTTPS origin (e.g. https://autopilot.example)") },
            singleLine = true,
            enabled = session.canAdminister && !working,
            modifier = Modifier.fillMaxWidth().testTag("admin.chiefOfStaff.autopilotOrigin"),
        )
        OutlinedTextField(
            value = pipelineIdInput,
            onValueChange = { pipelineIdInput = it },
            label = { Text("Reconciliation pipeline definition ID") },
            singleLine = true,
            enabled = session.canAdminister && !working,
            modifier = Modifier.fillMaxWidth().testTag("admin.chiefOfStaff.pipelineId"),
        )
        OutlinedTextField(
            value = subscriptionIdInput,
            onValueChange = { subscriptionIdInput = it },
            label = { Text("Autopilot subscription ID (optional)") },
            singleLine = true,
            enabled = session.canAdminister && !working,
            modifier = Modifier.fillMaxWidth().testTag("admin.chiefOfStaff.subscriptionId"),
        )
        reconcileStatus?.let {
            Text(it, color = Amb.colors.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.chiefOfStaff.reconcileStatus"))
        }
        reconcileError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.chiefOfStaff.reconcileError"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            val confirmed = ConfirmedAutopilotOrigin.of(autopilotOriginInput)
                            val defId = pipelineIdInput.trim().ifEmpty { ChiefOfStaffPipelineContract.DEFAULT_RECONCILE_PIPELINE_ID }
                            require(ChiefOfStaffPipelineContract.isValidDefinitionId(defId)) { "Invalid pipeline definition ID." }
                            val cfg = ChiefOfStaffReconcileConfig(confirmed.url, defId, subscriptionIdInput.trim().ifEmpty { null })
                            ChiefOfStaffReconcileStore.save(model.store, c.id, workspace.id, workspace.appNpub, cfg)
                            confirmedOrigin = confirmed
                            reconcileError = null
                            reconcileStatus = "Confirmed Autopilot host ${confirmed.url} ($defId)."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            reconcileError = e.message ?: "Use a valid HTTPS origin and pipeline definition ID."
                        }
                    }
                },
                enabled = session.canAdminister && !working && autopilotOriginInput.isNotBlank(),
                modifier = Modifier.testTag("admin.chiefOfStaff.confirmAutopilot"),
            ) { Text("Confirm host") }
            val currentAssignmentForReconcile = assignment
            if (currentAssignmentForReconcile != null) {
                TextButton(
                    onClick = {
                        if (!working) {
                            working = true
                            scope.launch {
                                try {
                                    invokeReconcile(currentAssignmentForReconcile)
                                    reload()
                                } finally {
                                    if (isCurrent()) working = false
                                }
                            }
                        }
                    },
                    enabled = session.canAdminister && !working && confirmedOrigin != null,
                    modifier = Modifier.testTag("admin.chiefOfStaff.reconcile"),
                ) { Text("Reconcile schedule") }
            }
        }
    }

    pendingConfirm?.let { (targetNpub, message) ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(if (targetNpub == null) "Remove Chief of Staff?" else "Confirm Chief of Staff") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirm = null
                        applyAssignmentChange(targetNpub)
                    },
                    modifier = Modifier.testTag("admin.chiefOfStaff.confirm"),
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun JobStatusSummaryLine(assignment: ChiefOfStaffAssignment?, cohort: CohortLoadResult?) {
    val label = when {
        assignment == null || !assignment.isAssigned -> "Index status: Unassigned"
        cohort is CohortLoadResult.Restricted -> "Index status: Private to workspace owner & assigned agent"
        cohort is CohortLoadResult.PendingSetup -> "Index status: Assigned · Job setup pending (epoch ${cohort.assignmentEpoch})"
        cohort is CohortLoadResult.Ready -> {
            val s = cohort.jobStatus
            val base = "Index status: ${scheduleStateLabel(s.scheduleState)} · ${s.topicCount} topics, ${s.evidenceCount} evidence links"
            if (s.lastError != null) "$base · Error: ${s.lastError}" else base
        }
        else -> "Index status: Checking…"
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("chiefOfStaff.jobStatusSummary"))
}

internal fun scheduleStateLabel(state: String): String = when (state) {
    "unconfigured" -> "Unconfigured"
    "pending" -> "Pending setup"
    "scheduled" -> "Scheduled"
    "running" -> "Running"
    "healthy" -> "Healthy"
    "error" -> "Error"
    "disabled_stale" -> "Disabled (stale assignment)"
    else -> state
}

// --- Native Searchable Topic Reference, Details, Evidence Drillthrough & Corrections ---

@Composable
internal fun ConnectedChiefOfStaffScreen(model: AppModel) {
    val w = model.workspace ?: return
    val c = model.connection ?: return
    val boundaryId = model.activeBoundaryId ?: return
    val services = LocalAppServices.current
    val unlocked = services.unlocked && !w.accessRemoved
    val scope = rememberCoroutineScope()

    var loading by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf(true) }
    var assignment by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf<ChiefOfStaffAssignment?>(null) }
    var cohort by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf<CohortLoadResult?>(null) }
    var error by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf<String?>(null) }
    var selectedTopicId by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf<String?>(null) }
    var searchQuery by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf("") }
    var showArchived by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf(false) }
    var savingCorrection by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf(false) }
    var correctionNotice by remember(boundaryId, c.id, c.signerNpub, w.id, unlocked) { mutableStateOf<String?>(null) }

    fun current(): Boolean =
        model.activeBoundaryId == boundaryId &&
            model.connection?.id == c.id &&
            model.workspace?.id == w.id &&
            model.workspace?.accessRemoved == false &&
            (model.identityState as? IdentityState.Unlocked)?.npub == c.signerNpub

    suspend fun refresh() {
        val ownerNpub = w.ownerNpub
        val runtime = model.active
        if (!current() || !unlocked || ownerNpub == null || runtime == null) {
            assignment = null
            cohort = null
            selectedTopicId = null
            loading = false
            return
        }
        loading = true
        error = null
        try {
            val loadedAssignment = model.refreshChiefOfStaffAssignment(
                runtime = runtime,
                workspace = w,
                priority = studio.otherstuff.ambulando.core.sync.SyncPriority.VISIBLE,
                verifyAcl = false,
            )
            if (!current()) return
            val activeAssignment = (loadedAssignment as? AssignmentLoadResult.Ready)?.assignment
            // If the assigned agent or epoch changed, clear prior selected topic immediately.
            if (activeAssignment?.assignedNpub != assignment?.assignedNpub ||
                activeAssignment?.assignmentEpoch != assignment?.assignmentEpoch
            ) {
                selectedTopicId = null
            }
            assignment = activeAssignment

            cohort = if (activeAssignment != null && activeAssignment.isAssigned) {
                model.loadChiefOfStaffCohort(
                    runtime = runtime,
                    workspace = w,
                    assignment = activeAssignment,
                    priority = studio.otherstuff.ambulando.core.sync.SyncPriority.VISIBLE,
                )
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current()) {
                cohort = null
                error = (e as? ChiefOfStaffError)?.message ?: ConnectionFailure.userMessage(e) ?: "Could not load Chief of Staff topics."
            }
        } finally {
            if (current()) loading = false
        }
    }

    LaunchedEffect(boundaryId, c.id, c.signerNpub, w.id, unlocked, model.chiefOfStaffAssignment?.assignmentRevision) { refresh() }

    fun submitCorrection(correction: ChiefOfStaffCorrection) {
        val ready = cohort as? CohortLoadResult.Ready ?: return
        val curAssignment = assignment ?: return
        val ownerNpub = w.ownerNpub ?: return
        val agentNpub = curAssignment.assignedNpub ?: return
        if (savingCorrection || !current()) return
        savingCorrection = true
        correctionNotice = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    model.towerAPI(c).appendChiefOfStaffCorrection(
                        workspaceId = w.id,
                        ownerNpub = ownerNpub,
                        appNpub = w.appNpub,
                        viewerNpub = c.signerNpub,
                        agentNpub = agentNpub,
                        assignmentEpoch = ready.assignmentEpoch,
                        correction = correction,
                    )
                }
                if (!current()) return@launch
                cohort = ready.copy(corrections = updated)
                correctionNotice = "Correction saved and will persist across future rebuilds."
                if (correction.kind == "merge_topics" && selectedTopicId == correction.sourceTopicId) {
                    selectedTopicId = correction.targetTopicId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) {
                    correctionNotice = (e as? ChiefOfStaffError)?.message ?: ConnectionFailure.userMessage(e) ?: "Could not save correction."
                }
            } finally {
                if (current()) savingCorrection = false
            }
        }
    }

    fun openEvidence(ev: ChiefOfStaffEvidence) {
        when (ev.sourceType) {
            "task" -> services.open(WorkRoute.Task(w.id, ev.recordId))
            "doc" -> services.open(WorkRoute.Doc(w.id, ev.recordId))
            "file" -> services.open(WorkRoute.File(w.id, ev.recordId))
            "thread" -> services.open(WorkRoute.Thread(w.id, ev.channelId, ev.recordId))
            "message", "audio_note" -> {
                val tid = ev.threadId
                if (tid != null) services.open(WorkRoute.Thread(w.id, ev.channelId, tid, ev.recordId))
                else services.openMention("message", ev.recordId)
            }
        }
    }

    ChiefOfStaffTopicsContent(
        workspaceLabel = w.label,
        unlocked = unlocked,
        loading = loading,
        assignment = assignment,
        cohort = cohort,
        error = error,
        searchQuery = searchQuery,
        showArchived = showArchived,
        selectedTopicId = selectedTopicId,
        savingCorrection = savingCorrection,
        correctionNotice = correctionNotice,
        viewerNpub = c.signerNpub,
        onSearchChange = { searchQuery = it },
        onShowArchivedChange = { showArchived = it },
        onSelectTopic = { selectedTopicId = it },
        onRefresh = { scope.launch { refresh() } },
        onOpenDm = {
            model.activeEntry?.let { entry ->
                model.act {
                    val dm = model.openChiefOfStaffDirectMessage(entry)
                    services.open(WorkRoute.Channel(w.id, dm.id))
                }
            }
        },
        onOpenEvidence = ::openEvidence,
        onSubmitCorrection = ::submitCorrection,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChiefOfStaffTopicsContent(
    workspaceLabel: String,
    unlocked: Boolean,
    loading: Boolean,
    assignment: ChiefOfStaffAssignment?,
    cohort: CohortLoadResult?,
    error: String?,
    searchQuery: String,
    showArchived: Boolean,
    selectedTopicId: String?,
    savingCorrection: Boolean,
    correctionNotice: String?,
    viewerNpub: String,
    onSearchChange: (String) -> Unit,
    onShowArchivedChange: (Boolean) -> Unit,
    onSelectTopic: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenDm: () -> Unit,
    onOpenEvidence: (ChiefOfStaffEvidence) -> Unit,
    onSubmitCorrection: (ChiefOfStaffCorrection) -> Unit,
) {
    val amb = Amb.colors
    if (!unlocked) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("chiefOfStaff.locked")) {
            Text("Unlock this workspace to view Chief of Staff topics.", color = amb.mutedForeground)
        }
        return
    }

    if (cohort is CohortLoadResult.Ready && selectedTopicId != null) {
        val applied = remember(cohort.ontology, cohort.corrections) { cohort.applied }
        val topic = applied.topics.firstOrNull { it.id == selectedTopicId }
        if (topic != null) {
            ChiefOfStaffTopicDetail(
                topic = topic,
                allTopics = applied.topics,
                evidence = applied.evidence.filter { it.topicId == topic.id },
                viewerNpub = viewerNpub,
                saving = savingCorrection,
                notice = correctionNotice,
                onBack = { onSelectTopic(null) },
                onOpenEvidence = onOpenEvidence,
                onSubmitCorrection = onSubmitCorrection,
            )
            return
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("chiefOfStaff.screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Chief of Staff",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(workspaceLabel, style = MaterialTheme.typography.labelMedium, color = amb.mutedForeground)
                }
                if (assignment?.isAssigned == true) {
                    TextButton(onClick = onOpenDm, modifier = Modifier.testTag("chiefOfStaff.openDm")) {
                        Text("Message")
                    }
                }
                TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("chiefOfStaff.refresh")) {
                    Text("Refresh")
                }
            }
        }

        if (loading) {
            item("loading") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Loading Chief of Staff state…", modifier = Modifier.testTag("chiefOfStaff.loading"))
                }
            }
        }

        error?.let { err ->
            item("error") {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chiefOfStaff.error"))
            }
        }

        correctionNotice?.let { msg ->
            item("notice") {
                Text(msg, color = amb.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chiefOfStaff.notice"))
            }
        }

        if (!loading && (assignment == null || !assignment.isAssigned)) {
            item("unassigned") {
                Column(
                    Modifier.fillMaxWidth()
                        .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                        .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                        .padding(14.dp)
                        .testTag("chiefOfStaff.unassigned"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("No Chief of Staff assigned", fontWeight = FontWeight.SemiBold)
                    Text(
                        "A workspace manager can assign an existing workspace agent in Manage Workspace → Chief of Staff.",
                        style = MaterialTheme.typography.bodySmall,
                        color = amb.mutedForeground,
                    )
                }
            }
            return@LazyColumn
        }

        if (assignment != null && assignment.isAssigned) {
            item("status") {
                ChiefOfStaffStatusCard(assignment, cohort)
            }
        }

        when (cohort) {
            is CohortLoadResult.Restricted -> {
                item("restricted") {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                            .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                            .padding(14.dp)
                            .testTag("chiefOfStaff.restricted"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Private topic index", fontWeight = FontWeight.SemiBold)
                        Text(
                            "The workspace topic ontology is private to the workspace owner and the assigned Chief of Staff so private sources are never exposed workspace-wide. You can still message the Chief of Staff directly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = amb.mutedForeground,
                        )
                    }
                }
            }
            is CohortLoadResult.PendingSetup -> {
                item("pending") {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                            .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                            .padding(14.dp)
                            .testTag("chiefOfStaff.pendingSetup"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Assigned · Index setup pending", fontWeight = FontWeight.SemiBold)
                        Text(
                            "This workspace has an assigned Chief of Staff (epoch ${cohort.assignmentEpoch}), but the scheduled background index has not been provisioned or run on the agent host yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = amb.mutedForeground,
                        )
                    }
                }
            }
            is CohortLoadResult.Ready -> {
                val applied = cohort.applied
                val filtered = ChiefOfStaffOntologyLogic.searchTopics(applied.topics, applied.evidence, searchQuery, showArchived)
                val evidenceCounts = applied.evidence.groupingBy { it.topicId }.eachCount()

                item("search") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            label = { Text("Search topics, aliases, or evidence") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("chiefOfStaff.search"),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showArchived,
                                onCheckedChange = onShowArchivedChange,
                                modifier = Modifier.testTag("chiefOfStaff.showArchived"),
                            )
                            Text("Show archived topics", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item("emptyTopics") {
                        Text(
                            if (searchQuery.isBlank()) "No topics discovered yet." else "No topics match “$searchQuery”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = amb.mutedForeground,
                            modifier = Modifier.testTag("chiefOfStaff.emptyTopics"),
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { topic ->
                        val count = evidenceCounts[topic.id] ?: 0
                        Column(
                            Modifier.fillMaxWidth()
                                .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                                .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                                .clickable { onSelectTopic(topic.id) }
                                .padding(12.dp)
                                .testTag("chiefOfStaff.topic.${topic.id}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(topic.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(topic.kind, style = MaterialTheme.typography.labelSmall, color = amb.primary)
                                if (topic.sourceOrigin == "corrected") {
                                    Text("Edited", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                                }
                                if (topic.archived) {
                                    Text("Archived", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                                }
                            }
                            if (topic.description.isNotBlank()) {
                                Text(topic.description, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("$count evidence link${if (count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                                if (topic.aliases.isNotEmpty()) {
                                    Text("Aliases: ${topic.aliases.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            null -> {}
        }
    }
}

@Composable
private fun ChiefOfStaffStatusCard(assignment: ChiefOfStaffAssignment, cohort: CohortLoadResult?) {
    val amb = Amb.colors
    val label = assignment.assignedDisplayName?.takeIf { it.isNotBlank() } ?: shortLabel(assignment.assignedNpub!!)
    Column(
        Modifier.fillMaxWidth()
            .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
            .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
            .padding(12.dp)
            .testTag("chiefOfStaff.statusCard"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Assigned: $label", fontWeight = FontWeight.SemiBold)
        Text("Revision ${assignment.assignmentRevision} · Epoch ${assignment.assignmentEpoch}", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
        if (cohort is CohortLoadResult.Ready) {
            val s: ChiefOfStaffJobStatus = cohort.jobStatus
            Text(
                "Job status: ${scheduleStateLabel(s.scheduleState)}" + (s.cronExpression?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("chiefOfStaff.jobState"),
            )
            s.lastSuccessAt?.let {
                Text("Last indexed: $it", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
            }
            s.lastError?.let {
                Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chiefOfStaff.jobError"))
            }
            if (s.coverage.unsupportedInputs.isNotEmpty()) {
                Text(
                    "Unsupported inputs skipped: ${s.coverage.unsupportedInputs.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = amb.mutedForeground,
                )
            }
        }
        Text(
            "Storage: versioned private NStack Files (plaintext v1 envelope; encryption deferred).",
            style = MaterialTheme.typography.labelSmall,
            color = amb.mutedForeground,
        )
    }
}

@Composable
private fun ChiefOfStaffTopicDetail(
    topic: ChiefOfStaffTopic,
    allTopics: List<ChiefOfStaffTopic>,
    evidence: List<ChiefOfStaffEvidence>,
    viewerNpub: String,
    saving: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onOpenEvidence: (ChiefOfStaffEvidence) -> Unit,
    onSubmitCorrection: (ChiefOfStaffCorrection) -> Unit,
) {
    val amb = Amb.colors
    var showRename by remember(topic.id) { mutableStateOf(false) }
    var renameText by remember(topic.id, topic.name) { mutableStateOf(topic.name) }
    var showMerge by remember(topic.id) { mutableStateOf(false) }
    var mergeTargetId by remember(topic.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("chiefOfStaff.topicDetail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("chiefOfStaff.detail.back")) { Text("Back to topics") }
        Text(topic.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(
            "${topic.kind} · ${if (topic.sourceOrigin == "corrected") "Corrected" else "Discovered"}" + if (topic.archived) " · Archived" else "",
            style = MaterialTheme.typography.labelMedium,
            color = amb.mutedForeground,
        )
        if (topic.description.isNotBlank()) {
            Text(topic.description, style = MaterialTheme.typography.bodyMedium)
        }
        if (topic.aliases.isNotEmpty()) {
            Text("Aliases: ${topic.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
        }
        notice?.let { Text(it, color = amb.primary, style = MaterialTheme.typography.bodySmall) }

        // Durable correction actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { renameText = topic.name; showRename = true },
                enabled = !saving,
                modifier = Modifier.testTag("chiefOfStaff.action.rename"),
            ) { Text("Rename") }
            val mergeCandidates = allTopics.filter { it.id != topic.id && !it.archived }
            OutlinedButton(
                onClick = { mergeTargetId = mergeCandidates.firstOrNull()?.id; showMerge = true },
                enabled = !saving && mergeCandidates.isNotEmpty(),
                modifier = Modifier.testTag("chiefOfStaff.action.merge"),
            ) { Text("Merge") }
            OutlinedButton(
                onClick = {
                    onSubmitCorrection(
                        ChiefOfStaffCorrection(
                            kind = "archive_topic",
                            createdByNpub = viewerNpub,
                            createdAt = Instant.now().toString(),
                            topicId = topic.id,
                            archived = !topic.archived,
                        ),
                    )
                },
                enabled = !saving,
                modifier = Modifier.testTag("chiefOfStaff.action.archive"),
            ) { Text(if (topic.archived) "Unarchive" else "Archive") }
        }

        HorizontalDivider()
        Text("Supporting evidence (${evidence.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (evidence.isEmpty()) {
            Text("No active evidence links for this topic.", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
        }
        for (ev in evidence) {
            Column(
                Modifier.fillMaxWidth()
                    .background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                    .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                    .padding(12.dp)
                    .testTag("chiefOfStaff.evidence.${ev.recordId}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ev.sourceType.uppercase(), style = MaterialTheme.typography.labelSmall, color = amb.primary, fontWeight = FontWeight.SemiBold)
                    if (ev.pinned) Text("Pinned", style = MaterialTheme.typography.labelSmall, color = amb.primary)
                    if (ev.status != "active") Text(ev.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    ev.sourceTitle.ifBlank { "${ev.sourceType} ${ev.recordId.take(8)}" },
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onOpenEvidence(ev) }.testTag("chiefOfStaff.evidence.open.${ev.recordId}"),
                )
                if (ev.explanation.isNotBlank()) {
                    Text(ev.explanation, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onOpenEvidence(ev) },
                        modifier = Modifier.testTag("chiefOfStaff.evidence.drill.${ev.recordId}"),
                    ) { Text("Open source") }
                    if (!ev.pinned) {
                        TextButton(
                            onClick = {
                                onSubmitCorrection(
                                    ChiefOfStaffCorrection(
                                        kind = "pin_evidence",
                                        createdByNpub = viewerNpub,
                                        createdAt = Instant.now().toString(),
                                        topicId = topic.id,
                                        sourceType = ev.sourceType,
                                        recordId = ev.recordId,
                                    ),
                                )
                            },
                            enabled = !saving,
                            modifier = Modifier.testTag("chiefOfStaff.evidence.pin.${ev.recordId}"),
                        ) { Text("Pin") }
                    }
                    TextButton(
                        onClick = {
                            onSubmitCorrection(
                                ChiefOfStaffCorrection(
                                    kind = "reject_evidence",
                                    createdByNpub = viewerNpub,
                                    createdAt = Instant.now().toString(),
                                    topicId = topic.id,
                                    sourceType = ev.sourceType,
                                    recordId = ev.recordId,
                                ),
                            )
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("chiefOfStaff.evidence.reject.${ev.recordId}"),
                    ) { Text("Wrong evidence", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename topic") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Topic name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chiefOfStaff.rename.input"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        showRename = false
                        if (trimmed.isNotEmpty() && trimmed != topic.name) {
                            onSubmitCorrection(
                                ChiefOfStaffCorrection(
                                    kind = "rename",
                                    createdByNpub = viewerNpub,
                                    createdAt = Instant.now().toString(),
                                    topicId = topic.id,
                                    name = trimmed,
                                ),
                            )
                        }
                    },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.testTag("chiefOfStaff.rename.confirm"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showMerge) {
        val candidates = allTopics.filter { it.id != topic.id && !it.archived }
        AlertDialog(
            onDismissRequest = { showMerge = false },
            title = { Text("Merge “${topic.name}” into…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (cand in candidates) {
                        Row(
                            Modifier.fillMaxWidth().clickable { mergeTargetId = cand.id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mergeTargetId == cand.id, onClick = { mergeTargetId = cand.id })
                            Text(cand.name, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = mergeTargetId
                        showMerge = false
                        if (target != null && target != topic.id) {
                            onSubmitCorrection(
                                ChiefOfStaffCorrection(
                                    kind = "merge_topics",
                                    createdByNpub = viewerNpub,
                                    createdAt = Instant.now().toString(),
                                    sourceTopicId = topic.id,
                                    targetTopicId = target,
                                ),
                            )
                        }
                    },
                    enabled = mergeTargetId != null,
                    modifier = Modifier.testTag("chiefOfStaff.merge.confirm"),
                ) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { showMerge = false }) { Text("Cancel") } },
        )
    }
}
