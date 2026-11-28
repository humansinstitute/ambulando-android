package studio.otherstuff.ambulando.core.chiefofstaff

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.management.ConfirmedAutopilotOrigin
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.workspaces.BoundaryGuardTransport
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.File
import java.nio.file.Files

/**
 * Shared v1 `runtime_binding` contract: the golden fixture is byte-identical with the engine copy
 * (`scripts/chief-of-staff/fixtures/`) and the Mac repo copy, and every consumer parses the same canonical names.
 */
class ChiefOfStaffRuntimeBindingTest {
    private val fixturePath = "/fixtures/chief-of-staff/assignment-runtime-binding.v1.json"
    private val fixtureBytes: ByteArray = javaClass.getResourceAsStream(fixturePath)!!.use { it.readBytes() }
    private val payload: JsonObject = ChiefOfStaffEnvelope.decode(fixtureBytes)
    private val ws = payload.string("workspace_id")!!
    private val owner = payload.string("workspace_owner_npub")!!
    private val app = payload.string("app_npub")!!
    private val agent = payload.string("assigned_npub")!!
    private val context = ExpectedWorkspaceContext(ws, owner, app)
    private val otherAgent = Bech32.encode("npub", ByteArray(32) { 9 })

    private fun binding(): JsonObject = payload["runtime_binding"]!!.jsonObject

    private fun withBinding(edit: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val rb = binding().toMutableMap().apply(edit)
        return JsonObject(payload.toMutableMap().apply { put("runtime_binding", JsonObject(rb)) })
    }

    private fun assertRejected(label: String, doc: JsonObject) {
        try {
            ChiefOfStaffAssignment.parse(doc, context)
            fail("$label must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun goldenFixtureIsByteIdenticalWithEngineCopy() {
        val engineCopy = File("../scripts/chief-of-staff/fixtures/assignment-runtime-binding.v1.json")
        assertTrue("engine fixture copy must exist at ${engineCopy.absolutePath}", engineCopy.isFile)
        assertArrayEquals(engineCopy.readBytes(), fixtureBytes)
    }

    @Test fun goldenFixtureRoundTripsCanonicalBindingOptionalAndUnknownFields() {
        val envelope = parseJsonObjectOrNull(fixtureBytes)!!
        assertEquals(JsonPrimitive(1), envelope["envelope_version"])
        assertEquals("none", envelope["encryption"]!!.jsonObject.string("mode"))

        val assignment = ChiefOfStaffAssignment.parse(payload, context)
        val rb = assignment.runtimeBinding!!
        assertEquals("https://autopilot.example.invalid", rb.autopilotOrigin)
        assertEquals("chief-of-staff-reconcile.v1", rb.reconcilePipelineId)
        assertEquals("chief-of-staff-ontology.v1", rb.ontologyPipelineId)
        assertEquals(owner, rb.ownerNpub)
        assertEquals(ws, rb.workspaceId)
        assertEquals(agent, rb.agentNpub)
        assertEquals("44444444-4444-4444-8444-444444444444", rb.subscriptionId)
        assertNull(rb.cronExpression)
        assertNull(rb.timezone)
        assertEquals(mapOf("future_option" to buildJsonObject { put("example", true) }), rb.extra)

        // Writing back yields exactly the fixture payload (unknown field preserved, nothing invented).
        assertEquals(payload, assignment.toJson())
        assertEquals(payload, ChiefOfStaffEnvelope.decode(ChiefOfStaffEnvelope.encode(assignment.toJson())))

        // ontology_pipeline_id is optional.
        val noOntology = ChiefOfStaffAssignment.parse(withBinding { remove("ontology_pipeline_id") }, context)
        assertNull(noOntology.runtimeBinding!!.ontologyPipelineId)
        assertFalse("ontology_pipeline_id" in noOntology.toJson()["runtime_binding"]!!.jsonObject)
    }

    @Test fun bindingRejectsUncleanOriginsMissingSubscriptionAliasesSecretsAndAgentMismatch() {
        for (origin in listOf(
            "http://autopilot.example.invalid",
            "https://autopilot.example.invalid/extra",
            "https://user:pass@autopilot.example.invalid",
            "https://autopilot.example.invalid?x=1",
            "https://autopilot.example.invalid#frag",
            "http://$agent.fips:8080",
            "",
        )) {
            assertRejected("origin '$origin'", withBinding { put("autopilot_origin", JsonPrimitive(origin)) })
        }
        assertRejected("missing subscription_id", withBinding { remove("subscription_id") })
        assertRejected("non-UUID subscription_id", withBinding { put("subscription_id", JsonPrimitive("sub-1")) })
        assertRejected("legacy autopilot_base_url/bot_npub aliases", withBinding {
            remove("autopilot_origin")
            remove("agent_npub")
            put("autopilot_base_url", JsonPrimitive("https://autopilot.example.invalid"))
            put("bot_npub", JsonPrimitive(agent))
        })
        assertRejected("missing agent_npub while assigned", withBinding { remove("agent_npub") })
        assertRejected("agent_npub for another agent", withBinding { put("agent_npub", JsonPrimitive(otherAgent)) })
        assertRejected("foreign workspace_id", withBinding { put("workspace_id", JsonPrimitive("22222222-2222-4222-8222-222222222222")) })
        assertRejected("foreign owner_npub", withBinding { put("owner_npub", JsonPrimitive(otherAgent)) })
        for (secret in listOf("token", "nsec", "authorization", "cohort_id")) {
            assertRejected("secret/private field $secret", withBinding { put(secret, JsonPrimitive("x")) })
        }
    }

    @Test fun reconcilePlanNeverCarriesAnOldSubscriptionToAnotherAgentOrRemoval() {
        val assigned = ChiefOfStaffAssignment.parse(payload, context)
        val oldBinding = assigned.runtimeBinding!!

        // Saved assignment with its own binding for the assigned agent: schedule with that subscription.
        val active = ChiefOfStaffReconcilePlan.of(assigned, previousBinding = null)!!
        assertFalse(active.isCleanup)
        assertEquals(oldBinding.subscriptionId, active.subscriptionId)

        // Reassigned to another agent without a new binding: cleanup only, on the old Autopilot, no subscription.
        val reassigned = assigned.copy(
            assignmentRevision = assigned.assignmentRevision + 1,
            assignedNpub = otherAgent,
            previousAssignedNpub = agent,
            runtimeBinding = null,
        )
        val cleanup = ChiefOfStaffReconcilePlan.of(reassigned, previousBinding = oldBinding)!!
        assertTrue(cleanup.isCleanup)
        assertNull(cleanup.subscriptionId)
        assertEquals(oldBinding.autopilotOrigin, cleanup.binding.autopilotOrigin)

        // A stale binding still naming the previous agent is not the new agent's binding.
        val staleCarried = reassigned.copy(runtimeBinding = oldBinding)
        val staleCleanup = ChiefOfStaffReconcilePlan.of(staleCarried, previousBinding = oldBinding)!!
        assertTrue(staleCleanup.isCleanup)
        assertNull(staleCleanup.subscriptionId)

        // Removal: cleanup only.
        val removed = assigned.copy(assignedActorId = null, assignedNpub = null, runtimeBinding = null)
        val removal = ChiefOfStaffReconcilePlan.of(removed, previousBinding = oldBinding)!!
        assertTrue(removal.isCleanup)
        assertNull(removal.subscriptionId)

        // No binding at all: nothing to invoke (honest pending), and a foreign-boundary previous binding is ignored.
        assertNull(ChiefOfStaffReconcilePlan.of(reassigned, previousBinding = null))
        assertNull(ChiefOfStaffReconcilePlan.of(reassigned, previousBinding = oldBinding.copy(workspaceId = "22222222-2222-4222-8222-222222222222")))
    }

    private class MemoryVault : VaultSecretStore {
        private var stored: ByteArray? = null
        override fun read() = stored?.copyOf()
        override fun write(data: ByteArray) { stored = data.copyOf() }
    }

    @Test fun pipelineClientReadsAutopilotRunEnvelopeHonestly() = runBlocking {
        val identity = IdentityService(File(Files.createTempDirectory("cos-rb").toFile(), "vault"), MemoryVault())
        identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 7 }), "123456")
        val signer = (identity.state() as IdentityState.Unlocked).npub
        val origin = ConfirmedAutopilotOrigin.of("https://autopilot.example.invalid")
        var nextStatus = 200
        var nextBody = ""
        var lastBody: JsonObject? = null
        val inner = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                lastBody = request.body?.let { parseJsonObjectOrNull(it) }
                return HttpResponse(nextStatus, nextBody.toByteArray())
            }
        }
        val client = ChiefOfStaffPipelineClient(
            identity,
            BoundaryGuardTransport(inner, WorkspaceBoundary("https://tower.example.invalid", signer, app, ws)),
        )
        fun request(subscription: String?) = ChiefOfStaffReconcileRequest(
            workspaceId = ws, ownerNpub = owner, appNpub = app, assignedAgentNpub = agent,
            assignmentRevision = 7, assignmentEpoch = 7, towerUrl = "https://tower.example.invalid", subscriptionId = subscription,
        )
        suspend fun call(status: Int, body: String, subscription: String? = "44444444-4444-4444-8444-444444444444"): ChiefOfStaffReconcileOutcome {
            nextStatus = status
            nextBody = body
            return client.reconcile(origin, signer, "chief-of-staff-reconcile.v1", request(subscription))
        }
        fun envelope(status: String, result: String = "null", error: String = "null") =
            """{"runId":"run-1","status":"$status","terminal":true,"result":$result,"error":$error,"pollingUrl":"/api/pipelines/runs/run-1","deliveryUrl":"/api/pipelines/runs/run-1/delivery"}"""

        // Terminal ok + scheduled.
        val scheduled = call(200, envelope("ok", """{"reconciled":{"schedule_state":"scheduled","autopilot_job_id":"job-1"}}"""))
        assertTrue("$scheduled", scheduled is ChiefOfStaffReconcileOutcome.Completed)
        assertEquals("scheduled", (scheduled as ChiefOfStaffReconcileOutcome.Completed).scheduleState)
        assertEquals("run-1", scheduled.runId)
        val input = lastBody!!["input"]!!.jsonObject
        assertEquals("44444444-4444-4444-8444-444444444444", input.string("subscription_id"))
        assertEquals(agent, input.string("assigned_agent_npub"))
        assertEquals("chief-of-staff:reconcile:$ws:rev:7:$agent", lastBody!!.string("idempotencyKey"))

        // Terminal ok but honest pending (no binding for agent): not healthy, carries the reason.
        val pending = call(200, envelope("ok", """{"reconciled":{"schedule_state":"pending","last_error":"No active Autopilot workspace subscription is bound for the assigned agent yet."}}"""))
        assertEquals("pending", (pending as ChiefOfStaffReconcileOutcome.Completed).scheduleState)
        assertTrue(pending.lastError!!.contains("subscription"))

        // Terminal ok whose reconcile step reports a schedule error is a failure.
        val scheduleError = call(200, envelope("ok", """{"reconciled":{"schedule_state":"error","last_error":"create failed"}}"""))
        assertTrue(scheduleError is ChiefOfStaffReconcileOutcome.Failed && scheduleError.code == "schedule_error" && scheduleError.message == "create failed")

        // HTTP 200 with terminal error / needs_input / cancelled statuses are failures, not completions.
        for (status in listOf("error", "needs_input", "cancelled")) {
            val failed = call(200, envelope(status, error = "\"step failed\""))
            assertTrue("$status -> $failed", failed is ChiefOfStaffReconcileOutcome.Failed && failed.code == "pipeline_run_$status")
        }

        // Unreadable 200 is not success.
        val unreadable = call(200, "not json")
        assertTrue(unreadable is ChiefOfStaffReconcileOutcome.Failed && unreadable.code == "unreadable_run_envelope")

        // 202 accepted / wait expired: still running, never retried as a new run.
        val accepted = call(202, """{"runId":"run-1","status":"running","terminal":false,"result":null,"error":null,"timedOut":true}""")
        assertTrue(accepted is ChiefOfStaffReconcileOutcome.Accepted && accepted.timedOut && accepted.runId == "run-1")

        // 409 idempotency conflict stays an honest error.
        val conflict = call(409, """{"error":"Idempotency key reused with different input","code":"idempotency_conflict"}""")
        assertTrue(conflict is ChiefOfStaffReconcileOutcome.Failed && conflict.code == "idempotency_conflict")

        // Cleanup-only reconcile sends no subscription at all; a non-UUID subscription is never sent either.
        call(200, envelope("ok", """{"reconciled":{"schedule_state":"disabled_stale"}}"""), subscription = null)
        assertFalse("subscription_id" in lastBody!!["input"]!!.jsonObject)
        call(200, envelope("ok", """{"reconciled":{"schedule_state":"pending"}}"""), subscription = "sub-1")
        assertFalse("subscription_id" in lastBody!!["input"]!!.jsonObject)
    }

    @Test fun topLevelUnknownFieldsArePreservedAndPrivateFieldsRefused() {
        val withFuture = JsonObject(payload.toMutableMap().apply { put("future_top", buildJsonObject { put("x", 1) }) })
        val parsed = ChiefOfStaffAssignment.parse(withFuture, context)
        assertEquals(buildJsonObject { put("x", 1) }, parsed.extra["future_top"])
        assertEquals(withFuture, parsed.toJson())
        for (forbidden in listOf("topics", "evidence", "cohort_id", "cohort_channel_id", "job_status")) {
            assertRejected("top-level $forbidden", JsonObject(payload.toMutableMap().apply { put(forbidden, JsonPrimitive("x")) }))
        }
    }

    @Test fun reconcileAddressesOwnerSpaceForDelegatedSignerAndOwnSpaceForOwner() = runBlocking {
        // Route selection.
        assertEquals("/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs", ChiefOfStaffPipelineContract.runPath("chief-of-staff-reconcile.v1"))
        assertNull(ChiefOfStaffPipelineContract.ownerSpaceFor(owner, owner))
        assertEquals(owner, ChiefOfStaffPipelineContract.ownerSpaceFor(agent, owner))
        assertEquals("/api/owners/$owner/pipelines/definitions/chief-of-staff-reconcile.v1/runs", ChiefOfStaffPipelineContract.runPath("chief-of-staff-reconcile.v1", owner))
        try {
            ChiefOfStaffPipelineContract.runPath("chief-of-staff-reconcile.v1", "not-an-npub")
            fail("invalid owner space must be refused")
        } catch (_: IllegalArgumentException) {}

        // A delegated signer (not the owner) posts to the exact owner-space route and nowhere else.
        val identity = IdentityService(File(Files.createTempDirectory("cos-rb2").toFile(), "vault"), MemoryVault())
        identity.importIdentity(Bech32.encode("nsec", ByteArray(32) { 8 }), "123456")
        val delegate = (identity.state() as IdentityState.Unlocked).npub
        assertTrue(delegate != owner)
        var lastUrl: String? = null
        var lastAuth: studio.otherstuff.ambulando.core.nostr.NostrEvent? = null
        val inner = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                lastUrl = request.url
                lastAuth = studio.otherstuff.ambulando.core.support.decodeAuthorization(request)
                return HttpResponse(200, """{"runId":"r","status":"ok","terminal":true,"result":{"reconciled":{"schedule_state":"scheduled"}},"error":null}""".toByteArray())
            }
        }
        val guarded = BoundaryGuardTransport(inner, WorkspaceBoundary("https://tower.example.invalid", delegate, app, ws))
        val outcome = ChiefOfStaffPipelineClient(identity, guarded).reconcile(
            ConfirmedAutopilotOrigin.of("https://autopilot.example.invalid"), delegate, "chief-of-staff-reconcile.v1",
            ChiefOfStaffReconcileRequest(ws, owner, app, agent, 7, 7, "https://tower.example.invalid", "44444444-4444-4444-8444-444444444444"),
        )
        assertTrue("$outcome", outcome is ChiefOfStaffReconcileOutcome.Completed)
        assertEquals("https://autopilot.example.invalid/api/owners/$owner/pipelines/definitions/chief-of-staff-reconcile.v1/runs", lastUrl)
        // The signed NIP-98 event binds exactly that owner-space URL and method, by the delegated signer.
        val auth = lastAuth!!
        assertTrue(studio.otherstuff.ambulando.core.nostr.SignatureVerifier.verify(auth))
        assertEquals(lastUrl, auth.tags.first { it[0] == "u" }[1])
        assertEquals("POST", auth.tags.first { it[0] == "method" }[1])
        assertEquals(delegate, studio.otherstuff.ambulando.core.nostr.NostrKeys.npub(auth.pubkey))

        // The Chief guard can never be pointed at any owner space other than the workspace owner's.
        try {
            ChiefOfStaffPipelineGuard(inner, studio.otherstuff.ambulando.core.transport.TowerOrigin("https://autopilot.example.invalid"),
                "chief-of-staff-reconcile.v1", ws, owner, app, ownerSpaceNpub = otherAgent)
            fail("foreign owner space must be refused")
        } catch (_: IllegalArgumentException) {}

        // The runtime boundary refuses every other /api/owners/ shape.
        for ((method, url) in listOf(
            "GET" to "https://autopilot.example.invalid/api/owners/$owner/pipelines/definitions/chief-of-staff-reconcile.v1/runs",
            "POST" to "https://autopilot.example.invalid/api/owners/$owner/scheduler/jobs",
            "POST" to "https://autopilot.example.invalid/api/owners/$owner/pipelines/definitions/chief-of-staff-reconcile.v1/runs?x=1",
            "POST" to "http://autopilot.example.invalid/api/owners/$owner/pipelines/definitions/chief-of-staff-reconcile.v1/runs",
        )) {
            try {
                guarded.check(url, null, method)
                fail("must refuse $method $url")
            } catch (_: BoundaryViolation) {}
        }
    }
}
