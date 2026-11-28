/**
 * Autopilot scheduled pipeline reconciliation for Ambulando Chief of Staff.
 *
 * Uses Autopilot's existing /api/scheduler/jobs contract with `actionType: 'pipeline'`
 * and exact `flightdeckContext` (ownerNpub, subscriptionId, workspaceId) validated by
 * Autopilot's `scheduler/session-context.ts`.
 *
 * Enforces:
 * - One scheduled pipeline per (workspaceId, assignment).
 * - Idempotent re-application without duplicates.
 * - Disables old schedule on reassignment or removal.
 * - Reports honest `pending` / `error` / `disabled_stale` status when no
 *   active Autopilot subscription/bot identity or pipeline definition matches.
 */

export const DEFAULT_PIPELINE_ID = 'chief-of-staff-ontology.v1';
export const DEFAULT_CRON_EXPRESSION = '0 * * * *'; // 60-minute periodic cadence (configurable 15-60 min)

export function jobNameFor(workspaceId, agentNpub) {
  return `Ambulando Chief of Staff Ontology [${workspaceId}] (${agentNpub.slice(0, 14)})`;
}

function parseJobPipelineInput(job) {
  try {
    return typeof job?.pipelineInputJson === 'string' ? JSON.parse(job.pipelineInputJson) : null;
  } catch {
    return null;
  }
}

export function isWorkspaceChiefOfStaffJob(job, workspaceId) {
  const name = String(job?.name || '');
  const parsedInput = parseJobPipelineInput(job);
  return (
    name.startsWith(`Ambulando Chief of Staff Ontology [${workspaceId}]`) &&
    (job?.flightdeckContext?.workspaceId === workspaceId || parsedInput?.workspace_id === workspaceId)
  );
}

/**
 * The only subscription a reconcile may schedule with is the authoritative assignment's own canonical
 * `runtime_binding.subscription_id`, bound to the currently assigned agent. A caller-supplied subscription must match
 * it exactly; anything else (removal, reassignment without a new binding, stale or foreign subscription) yields
 * `null`, so reconciliation can still disable stale jobs but never creates or re-enables one.
 */
export function resolveBoundSubscription(assignment, requestedSubscriptionId = null) {
  const assignedNpub = assignment?.assigned_npub ?? null;
  const binding = assignment?.runtime_binding ?? null;
  if (!assignedNpub) return { subscriptionId: null, reason: 'unassigned' };
  if (!binding || binding.agent_npub !== assignedNpub || typeof binding.subscription_id !== 'string') {
    return { subscriptionId: null, reason: 'no_runtime_binding_for_assigned_agent' };
  }
  const bound = binding.subscription_id.toLowerCase();
  if (requestedSubscriptionId && String(requestedSubscriptionId).toLowerCase() !== bound) {
    return { subscriptionId: null, reason: 'subscription_mismatch' };
  }
  return { subscriptionId: bound, reason: null };
}

export function buildPipelineInput({ workspaceId, ownerNpub, appNpub, agentNpub, towerUrl, scriptPath, subscriptionId = null }) {
  return {
    workspace_id: workspaceId,
    workspace_owner_npub: ownerNpub,
    app_npub: appNpub,
    assigned_agent_npub: agentNpub,
    tower_url: towerUrl,
    cli_script_path: scriptPath,
    ...(subscriptionId ? { subscription_id: subscriptionId } : {}),
  };
}

export async function reconcileSchedule(
  schedulerClient,
  {
    workspaceId,
    ownerNpub,
    appNpub,
    towerUrl,
    assignedAgentNpub,
    subscriptionId,
    workingDirectory,
    scriptPath,
    pipelineDefinitionId = DEFAULT_PIPELINE_ID,
    cronExpression = DEFAULT_CRON_EXPRESSION,
    timezone = 'Australia/Perth',
    pipelineAgent = 'codex',
  },
) {
  const existingJobs = await schedulerClient.listJobs();
  const matchingWorkspaceJobs = existingJobs.filter((j) => isWorkspaceChiefOfStaffJob(j, workspaceId));

  const disabledJobIds = [];

  // Unassigned -> disable every existing Chief of Staff schedule for this workspace.
  if (!assignedAgentNpub) {
    for (const job of matchingWorkspaceJobs) {
      if (job.enabled) {
        await schedulerClient.updateJob(job.id, { enabled: false });
        disabledJobIds.push(job.id);
      }
    }
    return {
      schedule_state: disabledJobIds.length ? 'disabled_stale' : 'unconfigured',
      autopilot_job_id: null,
      pipeline_definition_id: null,
      disabled_job_ids: disabledJobIds,
      created: false,
      updated: false,
      last_error: null,
    };
  }

  const expectedInputJson = JSON.stringify(
    buildPipelineInput({ workspaceId, ownerNpub, appNpub, agentNpub: assignedAgentNpub, towerUrl, scriptPath, subscriptionId }),
  );

  // Disable schedules bound to any DIFFERENT agent or non-pipeline legacy jobs for this workspace.
  const targetMatches = [];
  for (const job of matchingWorkspaceJobs) {
    const parsedInput = parseJobPipelineInput(job);
    const exactMatch =
      job.actionType === 'pipeline' &&
      job.pipelineDefinitionId === pipelineDefinitionId &&
      job.botNpub === assignedAgentNpub &&
      job.userNpub === ownerNpub &&
      (job.flightdeckContext?.workspaceId === workspaceId || parsedInput?.workspace_id === workspaceId) &&
      (!subscriptionId || job.flightdeckContext?.subscriptionId === subscriptionId || parsedInput?.subscription_id === subscriptionId);
    if (exactMatch) {
      targetMatches.push(job);
    } else if (job.enabled) {
      await schedulerClient.updateJob(job.id, { enabled: false });
      disabledJobIds.push(job.id);
    }
  }

  // If no subscriptionId is available, report honest pending setup without faking a binding.
  if (!subscriptionId) {
    return {
      schedule_state: 'pending',
      autopilot_job_id: targetMatches[0]?.id ?? null,
      pipeline_definition_id: pipelineDefinitionId,
      disabled_job_ids: disabledJobIds,
      created: false,
      updated: false,
      last_error: 'No active Autopilot workspace subscription is bound for the assigned agent yet.',
    };
  }

  // Keep only the primary exact match enabled; disable any duplicates.
  if (targetMatches.length > 0) {
    const [primary, ...duplicates] = targetMatches;
    for (const dup of duplicates) {
      if (dup.enabled) {
        await schedulerClient.updateJob(dup.id, { enabled: false });
        disabledJobIds.push(dup.id);
      }
    }
    let updated = false;
    if (
      !primary.enabled ||
      primary.cronExpression !== cronExpression ||
      primary.timezone !== timezone ||
      primary.pipelineInputJson !== expectedInputJson
    ) {
      await schedulerClient.updateJob(primary.id, {
        enabled: true,
        cronExpression,
        timezone,
        pipelineInputJson: expectedInputJson,
      });
      updated = true;
    }
    return {
      schedule_state: 'scheduled',
      autopilot_job_id: primary.id,
      pipeline_definition_id: pipelineDefinitionId,
      disabled_job_ids: disabledJobIds,
      created: false,
      updated,
      cron_expression: cronExpression,
      timezone,
      last_error: null,
    };
  }

  // Create one exact-bound scheduled pipeline via Autopilot's scheduler API.
  try {
    const created = await schedulerClient.createJob({
      name: jobNameFor(workspaceId, assignedAgentNpub),
      botNpub: assignedAgentNpub,
      agent: pipelineAgent,
      workingDirectory,
      initialPrompt: '',
      triggerType: 'cron',
      cronExpression,
      timezone,
      actionType: 'pipeline',
      pipelineDefinitionId,
      pipelineInputJson: expectedInputJson,
      pipelineAgent,
      enabled: true,
      flightdeckContext: {
        ownerNpub,
        subscriptionId,
        workspaceId,
      },
    });
    return {
      schedule_state: 'scheduled',
      autopilot_job_id: created.id,
      pipeline_definition_id: pipelineDefinitionId,
      disabled_job_ids: disabledJobIds,
      created: true,
      updated: false,
      cron_expression: cronExpression,
      timezone,
      last_error: null,
    };
  } catch (err) {
    return {
      schedule_state: 'error',
      autopilot_job_id: null,
      pipeline_definition_id: pipelineDefinitionId,
      disabled_job_ids: disabledJobIds,
      created: false,
      updated: false,
      last_error: err instanceof Error ? err.message : String(err),
    };
  }
}
