#!/usr/bin/env bun
/**
 * Ambulando Chief of Staff & Living Topic Ontology CLI.
 * Uses Autopilot capability broker signing exclusively (never raw keys).
 */
import os from 'node:os';
import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { LocalOntologyStore } from './store.mjs';
import * as M from './model.mjs';
import * as Sync from './sync.mjs';
import { DEFAULT_PIPELINE_ID, reconcileSchedule, resolveBoundSubscription } from './scheduler.mjs';

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const opts = {};
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i];
    if (!arg.startsWith('--')) throw new Error(`Unexpected positional argument: ${arg}`);
    const key = arg.slice(2);
    const next = rest[i + 1];
    if (next === undefined || next.startsWith('--')) {
      opts[key] = true;
    } else {
      opts[key] = next;
      i++;
    }
  }
  return { command, opts };
}

function need(opts, ...keys) {
  for (const k of keys) {
    if (!opts[k] || opts[k] === true) throw new Error(`--${k} is required`);
  }
}

function defaultDbPath(workspaceId, agentNpub) {
  const root = process.env.CHIEF_OF_STAFF_DB_DIR || path.join(os.homedir(), '.local', 'share', 'ambulando', 'chief-of-staff');
  const hash = M.sha256Hex(Buffer.from(`${workspaceId}:${agentNpub}`)).slice(0, 16);
  return path.join(root, `ontology-${hash}.sqlite`);
}

async function createTowerClient(opts) {
  const solRoot = process.env.AMBULANDO_SOL || path.join(os.homedir(), 'code', 'ambulando', 'sol');
  const { brokerSigner, towerClient } = await import(path.join(solRoot, 'scripts', 'html-artifact', 'tower.mjs'));
  const towerUrl = opts.tower || process.env.FLIGHTDECK_TOWER_URL || process.env.TOWER_URL;
  if (!towerUrl) throw new Error('--tower is required');
  return towerClient({ baseUrl: towerUrl, workspaceId: opts.workspace, sign: brokerSigner() });
}

function createAutopilotSchedulerClient(autopilotRepo, ownerNpub) {
  const cli = path.join(autopilotRepo, 'clis', 'scheduler.ts');
  const runJson = (args) => {
    const full = [cli, ...args, '--bot-crypto', '--json'];
    if (ownerNpub) full.push('--owner', ownerNpub);
    const out = execFileSync('bun', full, { encoding: 'utf8' });
    return JSON.parse(out);
  };
  return {
    async listJobs() {
      const res = runJson(['list']);
      return Array.isArray(res) ? res : res.jobs || [];
    },
    async createJob(input) {
      const res = runJson([
        'create',
        '--name', input.name,
        '--action-type', 'pipeline',
        '--pipeline-definition-id', input.pipelineDefinitionId,
        '--pipeline-input-json', input.pipelineInputJson,
        '--pipeline-agent', input.pipelineAgent || 'codex',
        '--bot-npub', input.botNpub,
        '--trigger-type', 'cron',
        '--cron', input.cronExpression,
        '--timezone', input.timezone,
        '--enabled', 'true',
      ]);
      return res.job || res;
    },
    async updateJob(id, patch) {
      const args = ['update', id];
      if (patch.enabled !== undefined) args.push('--enabled', String(patch.enabled));
      if (patch.cronExpression) args.push('--cron', patch.cronExpression);
      if (patch.timezone) args.push('--timezone', patch.timezone);
      if (patch.pipelineInputJson) args.push('--pipeline-input-json', patch.pipelineInputJson);
      return runJson(args);
    },
  };
}

async function main() {
  const { command, opts } = parseArgs(process.argv.slice(2));
  if (!command || command === '--help' || command === '-h') {
    console.log(
      'Usage: bun cli.mjs <discover|setup-assignment|assign|remove|setup-cohort|prepare-batch|apply-extraction|lookup|fetch-source|correct|reconcile-schedule|status> --workspace UUID --owner NPUB --app NPUB --tower URL [...]',
    );
    return;
  }

  need(opts, 'workspace', 'owner', 'app');

  if (command === 'lookup') {
    need(opts, 'agent');
    const dbPath = opts.db || defaultDbPath(opts.workspace, opts.agent);
    const store = new LocalOntologyStore(dbPath);
    try {
      const results = store.lookup(opts.query || '', {
        topicLimit: Number(opts['topic-limit'] || 10),
        evidenceLimit: Number(opts['evidence-limit'] || 15),
      });
      console.log(JSON.stringify({ ok: true, query: opts.query || '', count: results.length, topics: results }, null, 2));
    } finally {
      store.close();
    }
    return;
  }

  const client = await createTowerClient(opts);

  if (command === 'discover' || command === 'status') {
    const assignment = await Sync.readAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      verifyAcl: opts['skip-acl'] !== true,
    });
    const assignedNpub = assignment.assignment?.assigned_npub || opts.agent || null;
    const epoch = opts.epoch ? Number(opts.epoch) : assignment.assignment?.assignment_revision || 1;
    let cohort = null;
    if (assignedNpub && epoch >= 1) {
      cohort = await Sync.readCohortState(client, {
        workspaceId: opts.workspace,
        ownerNpub: opts.owner,
        appNpub: opts.app,
        agentNpub: assignedNpub,
        assignmentEpoch: epoch,
      }).catch((e) => ({ state: 'error', error: e.message }));
    }
    const cohortError = cohort?.state === 'error' ? cohort.error : null;
    console.log(
      JSON.stringify(
        {
          ok: !cohortError,
          assignment: {
            state: assignment.state,
            acl_verified: assignment.aclVerified,
            channel_id: assignment.channel?.id ?? null,
            file_id: assignment.file?.id ?? null,
            version_id: assignment.versionId,
            document: assignment.assignment,
          },
          cohort: cohort
            ? {
                state: cohort.state,
                assignment_epoch: epoch,
                channel_id: cohort.cohort?.channel?.id ?? null,
                job_status: cohort.jobStatus?.document ?? null,
                topic_count: cohort.ontology?.document?.topics?.filter((t) => !t.archived).length ?? 0,
                evidence_count: cohort.ontology?.document?.evidence?.length ?? 0,
                corrections_count: cohort.corrections?.document?.corrections?.length ?? 0,
                error: cohortError,
              }
            : null,
        },
        null,
        2,
      ),
    );
    if (cohortError) process.exitCode = 1;
    return;
  }

  if (command === 'setup-assignment') {
    need(opts, 'scope');
    const res = await Sync.setupAssignmentChannel(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      scopeId: opts.scope,
    });
    console.log(JSON.stringify({ ok: true, channel_id: res.channel.id, file_id: res.file.id, version_id: res.versionId, assignment: res.assignment }, null, 2));
    return;
  }

  if (command === 'assign') {
    need(opts, 'agent');
    const expectedRevision = opts['expected-revision'] !== undefined ? Number(opts['expected-revision']) : undefined;
    const runtimeBinding =
      (opts['autopilot-origin'] || opts['autopilot-url']) && opts.subscription
        ? {
            autopilot_origin: String(opts['autopilot-origin'] || opts['autopilot-url']).replace(/\/+$/, ''),
            reconcile_pipeline_id: opts['reconcile-pipeline'] || 'chief-of-staff-reconcile.v1',
            ontology_pipeline_id: opts['ontology-pipeline'] || DEFAULT_PIPELINE_ID,
            subscription_id: opts.subscription,
            workspace_id: opts.workspace,
            owner_npub: opts.owner,
            agent_npub: opts.agent,
            cron_expression: opts.cron || '0 * * * *',
            timezone: opts.timezone || 'Australia/Perth',
          }
        : undefined;
    const res = await Sync.setAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      agentNpub: opts.agent,
      expectedRevision,
      runtimeBinding,
    });
    console.log(JSON.stringify({ ok: true, version_id: res.savedVersionId, sha256: res.sha256, assignment: res.assignment }, null, 2));
    return;
  }

  if (command === 'remove') {
    const expectedRevision = opts['expected-revision'] !== undefined ? Number(opts['expected-revision']) : undefined;
    const res = await Sync.setAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      agentNpub: null,
      expectedRevision,
    });
    console.log(JSON.stringify({ ok: true, version_id: res.savedVersionId, sha256: res.sha256, assignment: res.assignment }, null, 2));
    return;
  }

  if (command === 'setup-cohort') {
    need(opts, 'scope', 'agent');
    const assignment = await Sync.readAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
    });
    const epoch = opts.epoch ? Number(opts.epoch) : assignment.assignment?.assignment_revision || 1;
    const res = await Sync.setupCohortChannel(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      agentNpub: opts.agent,
      scopeId: opts.scope,
      assignmentEpoch: epoch,
    });
    console.log(
      JSON.stringify(
        {
          ok: true,
          state: res.state,
          assignment_epoch: res.assignmentEpoch,
          channel_id: res.channel?.id,
          files: {
            job_status: res.files.jobStatus?.id,
            ontology: res.files.ontology?.id,
            corrections: res.files.corrections?.id,
          },
        },
        null,
        2,
      ),
    );
    return;
  }

  if (command === 'prepare-batch') {
    need(opts, 'agent');
    const dbPath = opts.db || defaultDbPath(opts.workspace, opts.agent);
    const store = new LocalOntologyStore(dbPath);
    try {
      const res = await Sync.syncSourcesAndPrepareBatch(client, store, {
        workspaceId: opts.workspace,
        ownerNpub: opts.owner,
        appNpub: opts.app,
        agentNpub: opts.agent,
        limits: {
          pageLimit: Number(opts['page-limit'] || 50),
          maxPagesPerRun: Number(opts['max-pages'] || 3),
          maxBatchSources: Number(opts['max-batch-sources'] || 40),
        },
      });
      if (!res.ok) {
        console.log(JSON.stringify({ ...res, db_path: dbPath }, null, 2));
        process.exitCode = 2;
        return;
      }
      console.log(
        JSON.stringify(
          {
            ok: true,
            assignment_epoch: res.assignmentEpoch,
            coverage: res.coverage,
            batch: res.batch,
            db_path: dbPath,
          },
          null,
          2,
        ),
      );
    } finally {
      store.close();
    }
    return;
  }

  if (command === 'apply-extraction') {
    need(opts, 'agent', 'proposals');
    const assignment = await Sync.readAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
    });
    const epoch = opts.epoch ? Number(opts.epoch) : assignment.assignment?.assignment_revision;
    if (!epoch) throw new Error('--epoch (or active assignment) is required');
    const proposals = JSON.parse(await readFile(opts.proposals, 'utf8'));
    const coverage = opts.coverage ? JSON.parse(await readFile(opts.coverage, 'utf8')) : null;
    const dbPath = opts.db || defaultDbPath(opts.workspace, opts.agent);
    const store = new LocalOntologyStore(dbPath);
    try {
      const res = await Sync.applyExtractionAndPublish(client, store, {
        workspaceId: opts.workspace,
        ownerNpub: opts.owner,
        appNpub: opts.app,
        agentNpub: opts.agent,
        assignmentEpoch: epoch,
        proposals,
        coverage,
        expectedBatchDigest: opts['batch-digest'] || null,
      });
      console.log(JSON.stringify({ ...res, db_path: dbPath }, null, 2));
      if (!res.ok) process.exitCode = 2;
    } finally {
      store.close();
    }
    return;
  }

  if (command === 'fetch-source') {
    need(opts, 'type', 'id');
    const store = opts.agent ? new LocalOntologyStore(opts.db || defaultDbPath(opts.workspace, opts.agent)) : null;
    try {
      const res = await Sync.fetchVerifiedSource(client, store, {
        workspaceId: opts.workspace,
        ownerNpub: opts.owner,
        agentNpub: opts.agent || null,
        sourceType: opts.type,
        recordId: opts.id,
        channelId: opts.channel || null,
      });
      console.log(JSON.stringify(res, null, 2));
    } finally {
      store?.close();
    }
    return;
  }

  if (command === 'correct') {
    need(opts, 'agent', 'kind', 'by');
    const assignment = await Sync.readAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
    });
    const epoch = opts.epoch ? Number(opts.epoch) : assignment.assignment?.assignment_revision || 1;
    const correction = {
      kind: opts.kind,
      created_by_npub: opts.by,
      ...(opts.topic ? { topic_id: opts.topic } : {}),
      ...(opts.name ? { name: opts.name } : {}),
      ...(opts.description !== undefined ? { description: opts.description } : {}),
      ...(opts.aliases ? { aliases: String(opts.aliases).split(',').map((s) => s.trim()).filter(Boolean) } : {}),
      ...(opts['topic-kind'] ? { topic_kind: opts['topic-kind'] } : {}),
      ...(opts['source-topic'] ? { source_topic_id: opts['source-topic'] } : {}),
      ...(opts['target-topic'] ? { target_topic_id: opts['target-topic'] } : {}),
      ...(opts['source-type'] ? { source_type: opts['source-type'] } : {}),
      ...(opts['record-id'] ? { record_id: opts['record-id'] } : {}),
    };
    const res = await Sync.appendCorrection(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      agentNpub: opts.agent,
      assignmentEpoch: epoch,
      correction,
    });
    console.log(JSON.stringify({ ok: true, changed: res.changed, version_id: res.versionId, count: res.document.corrections.length }, null, 2));
    return;
  }

  if (command === 'reconcile-schedule') {
    const autopilot = process.env.AUTOPILOT_REPO || path.join(os.homedir(), 'code', 'wm', 'autopilot');
    const assignment = await Sync.readAssignment(client, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
    });
    const assignedNpub = assignment.assignment?.assigned_npub ?? null;
    const epoch = assignment.assignment?.assignment_revision || 1;
    const binding = assignment.assignment?.runtime_binding ?? null;
    const requestedSubscription = typeof opts.subscription === 'string' && opts.subscription.trim() ? opts.subscription.trim() : null;
    const bound = resolveBoundSubscription(assignment.assignment, requestedSubscription);
    const schedulerClient = createAutopilotSchedulerClient(autopilot, opts.owner);
    const result = await reconcileSchedule(schedulerClient, {
      workspaceId: opts.workspace,
      ownerNpub: opts.owner,
      appNpub: opts.app,
      towerUrl: opts.tower,
      assignedAgentNpub: assignedNpub,
      subscriptionId: bound.subscriptionId,
      workingDirectory: opts.cwd || process.cwd(),
      scriptPath: path.resolve(new URL('./cli.mjs', import.meta.url).pathname),
      pipelineDefinitionId: opts.pipeline || binding?.ontology_pipeline_id || DEFAULT_PIPELINE_ID,
      cronExpression: opts.cron || binding?.cron_expression || '0 * * * *',
      timezone: opts.timezone || binding?.timezone || 'Australia/Perth',
    });
    if (bound.reason === 'subscription_mismatch' && result.schedule_state === 'pending') {
      result.last_error = 'Requested subscription does not match the assignment runtime_binding for the assigned agent.';
    }
    let jobStatusUpdate = null;
    if (assignedNpub) {
      const savedStatus = await Sync.publishJobStatus(client, {
        workspaceId: opts.workspace,
        ownerNpub: opts.owner,
        appNpub: opts.app,
        agentNpub: assignedNpub,
        assignmentEpoch: epoch,
        statusPatch: {
          assignment_revision_seen: assignment.assignment.assignment_revision,
          schedule_state: result.schedule_state,
          autopilot_job_id: result.autopilot_job_id,
          pipeline_definition_id: result.pipeline_definition_id,
          cron_expression: result.cron_expression ?? null,
          timezone: result.timezone ?? 'Australia/Perth',
          last_error: result.last_error,
        },
      });
      jobStatusUpdate = { version_id: savedStatus.versionId, schedule_state: savedStatus.document.schedule_state };
    }
    console.log(JSON.stringify({ ok: result.schedule_state !== 'error', ...result, job_status_update: jobStatusUpdate }, null, 2));
    if (result.schedule_state === 'error') process.exitCode = 1;
    return;
  }

  throw new Error(`Unknown command: ${command}`);
}

main().catch((err) => {
  console.error(err.message);
  process.exitCode = 1;
});
