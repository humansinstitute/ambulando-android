package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.sample.SampleAdministration
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleViewer
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WorkspaceStorePool
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.waitFor
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import studio.otherstuff.ambulando.core.workspaces.RuntimeEnvironment
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntime
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntimes
import java.io.File
import java.time.Instant

/**
 * Workspace administration against the synthetic Tower: the exact requests, the exact-resource authority each control needs
 * (administrator, direct or group-derived channel grant manager, scope manager, delegate), denied and stale authority,
 * failed writes with readback, revoked or expired delegation, the scope lease and revision, and a workspace switch while a form
 * is in flight. Synthetic data only; nothing here is evidence of live Tower behaviour.
 */
class AdministrationTests {
    private class Hold(val matches: (HttpRequest) -> Boolean) {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class World(val identity: IdentityService, val tower: SampleTower) {
        @Volatile var hold: Hold? = null
        @Volatile var afterWriteOffline = false
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                sent += request.method + " " + request.url.substringBefore('?').substringAfter("workspaces")
                hold?.takeIf { it.matches(request) }?.let { h -> h.arrived.complete(Unit); h.release.await() }
                val response = tower.send(request)
                if (afterWriteOffline && request.method != "GET") tower.offline = true
                return response
            }
        }
        val events = object : EventStreamTransport {
            override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> = tower.events.open(request)
        }
        val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
        val catalog = LocalStore(tempDir())
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val runtimes = WorkspaceRuntimes(catalog, pool) { hook ->
            RuntimeEnvironment(engine, identity, http, events, null, tempDir(), tempDir(), tempDir(), null, Dispatchers.Unconfined, hook)
        }
        val catalogSync = WorkspaceSync(catalog, engine, identity, http)
        val admin get() = tower.administration
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

        suspend fun runtime(): WorkspaceRuntime {
            val c = catalogSync.connect(tower.origin)
            runtimes.discover(c, SyncPriority.USER, true)
            withTimeout(15_000) { engine.waitUntilIdle() }
            val w = catalog.reader.workspaces(c.id).first { it.id == SampleIds.STUDIO }
            return runtimes.enable(c, w).also { waitFor(20_000) { it.canSend } }
        }

        fun administrator(r: WorkspaceRuntime, now: () -> Instant = Instant::now) = WorkspaceAdministrator(
            r.boundary, r.connection, r.workspace, r.store, identity, r.readTransport, r.directTransport, r.engine, now,
            workspaceChanged = { runtimes.discover(r.connection, SyncPriority.USER, true) },
        )

        /** Administration writes (and lease acquisitions) that reached the synthetic Tower. */
        fun writesSent(): List<String> = tower.administration.writes().map { it.method + " " + it.path }
    }

    private fun <T> test(viewer: SampleViewer = SampleViewer.ADMIN, block: suspend World.(WorkspaceRuntime, WorkspaceAdministrator) -> T): T = runBlocking {
        val tower = SampleTower(tempDir(), originUrl = "https://one.sample.invalid").also { it.prepare(TestKeys.NPUB) }
        tower.administration.viewer = viewer
        val w = World(makeIdentity(), tower)
        try {
            withTimeout(90_000) {
                val r = w.runtime()
                w.block(r, w.administrator(r))
            }
        } finally { w.hold?.release?.complete(Unit); w.scope.cancel(); runCatching { w.runtimes.close() }; w.engine.close() }
    }

    private suspend fun expectError(block: suspend () -> Unit): Exception {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return e }
        fail("expected a refusal")
        error("unreachable")
    }

    private val ANA_ACTOR get() = java.util.UUID.nameUUIDFromBytes(SampleIds.ANA.toByteArray()).toString()
    private val BEA_ACTOR get() = java.util.UUID.nameUUIDFromBytes(SampleIds.BEA.toByteArray()).toString()
    private val newNpub get() = TestKeys.syntheticNpub(0x71)

    // MARK: Members and the workspace profile

    @Test fun anAdministratorAddsAMemberWithTheExactBodyAndSeesItInTheRefreshedList() = test { _, admin ->
        val result = admin.administer(AdministrationCommand.AddMember(newNpub, "agent"))
        assertEquals(true, result.observed)
        val post = this.admin.writes().single { it.path == "/members" }
        assertEquals(newNpub, post.body["member_npub"]); assertEquals("member", post.body["role"]); assertEquals("agent", post.body["kind"])
        assertTrue(admin.cached(AdministrationList.MEMBERS).any { it.fields["npub"] == newNpub })
        // A display name is a separate, administrator-only edit.
        val actor = admin.cached(AdministrationList.MEMBERS).first { it.fields["npub"] == newNpub }.id
        assertEquals(true, admin.administer(AdministrationCommand.MemberProfile(actor, "  Relay  ")).observed)
        assertEquals("Relay", this.admin.writes().last { it.path.endsWith("/profile") }.body["display_name"])
    }

    @Test fun anOrdinaryMemberIsRefusedEverythingAndNothingIsSentAfterTheFreshMeRead() = test(SampleViewer.MEMBER) { _, admin ->
        for (command in listOf(
            AdministrationCommand.AddMember(newNpub, "human"), AdministrationCommand.CreateGroup("Ops"),
            AdministrationCommand.Profile("Renamed", "", ""), AdministrationCommand.RevokeDelegation(java.util.UUID.randomUUID().toString()),
            AdministrationCommand.DisablePublishing(java.util.UUID.randomUUID().toString()),
        )) {
            assertTrue(command.toString(), expectError { admin.administer(command) } is ManagementError.Forbidden)
        }
        assertTrue("no write left the device", writesSent().isEmpty())
        assertTrue("every attempt re-read /me", sent.count { it.endsWith("/me") } >= 5)
    }

    @Test fun theProfileWriteIsValidatedBeforeAnyRequestAndAskedTowerForCurrentDataAfterwards() = test { r, admin ->
        assertTrue(expectError { admin.administer(AdministrationCommand.Profile(" ", "", "")) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.Profile("A", "", "http://insecure.example")) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.Profile("A", "", "", slug = "Bad Slug")) } is ManagementError.Invalid)
        assertTrue(writesSent().isEmpty())
        admin.administer(AdministrationCommand.Profile("Renamed Studio", "About", "https://avatars.example.com/a.png", slug = "renamed-studio"))
        val body = this.admin.writes().single().body
        assertEquals("Renamed Studio", body["name"]); assertEquals("renamed-studio", body["slug"])
        // The catalog learns Tower's answer from a discovery read, not from the submitted text.
        var label = ""
        withTimeout(10_000) {
            while (label != "Renamed Studio") {
                label = catalog.reader.workspaces(r.connection.id).first { w -> w.id == SampleIds.STUDIO }.label
                if (label != "Renamed Studio") kotlinx.coroutines.delay(50)
            }
        }
    }

    // MARK: Groups and nesting

    @Test fun groupFlowsCreateAddRemoveAndNestWithReadbackAndATowerCycleRefusal() = test { _, admin ->
        assertEquals(true, admin.administer(AdministrationCommand.CreateGroup("  Ops  ")).observed)
        admin.refresh(AdministrationList.GROUPS)
        val ops = admin.cached(AdministrationList.GROUPS).first { it.title == "Ops" }.id
        assertEquals("custom", this.admin.writes().last().body["kind"])
        // An existing workspace member joins; a repeat is a no-op, never a second write.
        assertNotNull(admin.addExistingGroupPerson(ops, SampleIds.BEA))
        assertNull(admin.addExistingGroupPerson(ops, SampleIds.BEA))
        assertEquals(1, this.admin.writes().count { it.path == "/groups/$ops/members" })
        assertEquals(true, admin.administer(AdministrationCommand.RemoveGroupMember(ops, BEA_ACTOR)).observed)
        // Nesting: Ops contains Designers (which already contains Reviewers). Tower refuses a cycle even if a stale view allowed it.
        val designers = SampleAdministration.GROUP_DESIGNERS; val reviewers = SampleAdministration.GROUP_REVIEWERS
        assertEquals(true, admin.administer(AdministrationCommand.ChildGroup(ops, designers, remove = false)).observed)
        val groups = admin.cached(AdministrationList.GROUPS)
        assertEquals("Would create a cycle", GroupDirectory.attachmentState(reviewers, ops, groups))
        assertEquals("Already nested", GroupDirectory.attachmentState(ops, designers, groups))
        assertEquals("This group", GroupDirectory.attachmentState(ops, ops, groups))
        val refused = expectError { admin.administer(AdministrationCommand.ChildGroup(reviewers, ops, remove = false)) }
        assertTrue(refused.toString(), refused is ManagementError.Conflict)
        assertEquals(true, admin.administer(AdministrationCommand.ChildGroup(ops, designers, remove = true)).observed)
        // Detaching keeps the group and its members.
        assertTrue(admin.cached(AdministrationList.GROUPS).any { it.id == designers })
    }

    @Test fun enrolmentOfAnOutsiderUsesTheAtomicContractAndAnOlderTowersRefusalKeepsTheDraftWithoutFallback() = test { _, admin ->
        val designers = SampleAdministration.GROUP_DESIGNERS
        // A plain add of someone outside the workspace is refused up front, with no write.
        val outsider = TestKeys.syntheticNpub(0x72)
        assertTrue(expectError { admin.addExistingGroupPerson(designers, outsider) } is ManagementError.Invalid)
        assertTrue(writesSent().isEmpty())
        // An older Tower answers 404 to the atomic body: the person is told, and POST /members is never used as a fallback.
        this.admin.failWrites = 404 to "member_not_found"
        val e = expectError { admin.enrolGroupPerson(designers, outsider, "human") }
        assertTrue(e is ManagementError.NotFound && e.message!!.contains("Your draft is retained"))
        this.admin.failWrites = null
        assertTrue("no POST /members fallback", this.admin.writes().none { it.path == "/members" })
        assertEquals("one attempt, no automatic retry", 1, this.admin.writes().count { it.path == "/groups/$designers/members" })
        // A supported Tower enrols and adds in one request that preserves nothing but the member role.
        assertNotNull(admin.enrolGroupPerson(designers, outsider, "agent"))
        val body = this.admin.writes().last().body
        assertEquals(true, body["ensure_workspace_member"]); assertEquals("agent", body["kind"])
        assertTrue(admin.cached(AdministrationList.MEMBERS).any { it.fields["npub"] == outsider })
    }

    @Test fun groupDirectoryResolvesNestedMembershipAndToleratesCycles() {
        fun group(id: String, members: List<String>, children: List<String>) = AdministrationRow.parse(
            Json.obj("group_id" to id, "name" to id, "child_group_ids" to children, "members" to members.map { mapOf("npub" to it, "actor_id" to "a-$it") }),
        )
        val groups = listOf(group("top", listOf("npub-top"), listOf("mid")), group("mid", listOf("npub-mid"), listOf("leaf")), group("leaf", listOf("npub-leaf"), listOf("top")))
        assertEquals(setOf("npub-top", "npub-mid", "npub-leaf"), GroupDirectory.effectiveMemberNpubs("top", groups))
        assertEquals("a cycle yields the union and terminates", setOf("npub-leaf", "npub-top", "npub-mid"), GroupDirectory.effectiveMemberNpubs("leaf", groups))
        val acyclic = listOf(group("p", emptyList(), listOf("c")), group("c", listOf("npub-c"), emptyList()))
        assertEquals(setOf("npub-c"), GroupDirectory.effectiveMemberNpubs("p", acyclic))
        assertEquals(emptySet<String>(), GroupDirectory.effectiveMemberNpubs("c", listOf(group("c", emptyList(), emptyList()))))
        assertEquals(listOf("Alpha", "beta"), GroupDirectory.search(listOf(row("beta"), row("Alpha")), "").map { it.title })
    }

    private fun row(title: String) = AdministrationRow.parse(Json.obj("id" to title, "name" to title))

    // MARK: Channel grants: exact-resource authority

    private val design = SampleIds.DESIGN

    @Test fun anAdministratorGrantsChangesAndRevokesAChannelGrantWithExactBodiesAndPaths() = test { _, admin ->
        assertEquals(true, admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "view", update = false)).observed)
        val create = this.admin.writes().last()
        assertEquals("/channels/$design/grants", create.path); assertEquals("actor", create.body["principal_type"]); assertEquals("view", create.body["access_level"])
        assertEquals(true, admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "manage", update = true)).observed)
        assertEquals("/channels/$design/grants/actor/$ANA_ACTOR", this.admin.writes().last().path)
        assertEquals(true, admin.administer(AdministrationCommand.RevokeChannelGrant(design, "actor", ANA_ACTOR)).observed)
        assertEquals("DELETE", this.admin.writes().last().method)
        assertTrue(expectError { admin.administer(AdministrationCommand.ChannelGrant(design, "robot", ANA_ACTOR, "view", false)) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.ChannelGrant(design, "actor", "not-a-uuid", "view", false)) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "owner", false)) } is ManagementError.Invalid)
    }

    @Test fun aDirectChannelManagerChangesOnlyTheChannelTheyManageAndNeedsNoWorkspaceAdministration() = test(SampleViewer.CHANNEL_MANAGER) { _, admin ->
        assertEquals(true, admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "view", false)).observed)
        // The same person holds the broad channel.manage permission and a manage level elsewhere, but not grant management on #general.
        val denied = expectError { admin.administer(AdministrationCommand.ChannelGrant(SampleIds.GENERAL, "actor", ANA_ACTOR, "view", false)) }
        assertTrue(denied.toString(), denied is ManagementError.Forbidden)
        assertTrue("only the allowed channel received a write", this.admin.writes().all { it.path.startsWith("/channels/$design/") })
        // Workspace administration stays closed to them.
        assertTrue(expectError { admin.administer(AdministrationCommand.CreateGroup("x")) } is ManagementError.Forbidden)
    }

    @Test fun channelGrantAuthorityPureRulesCoverDirectGroupNestedAndInsufficientGrants() {
        val me = WorkspaceMembership(role = "member", permissions = setOf("channel.manage"), actorId = "me")
        val npub = "npub-me"
        fun grant(type: String, id: String, level: String, permissions: List<String>? = null) = AdministrationRow.parse(
            Json.obj("principal_type" to type, "principal_id" to id, "access_level" to level, "permissions" to permissions),
        )
        fun group(id: String, members: List<String>, children: List<String> = emptyList()) = AdministrationRow.parse(
            Json.obj("group_id" to id, "name" to id, "child_group_ids" to children, "members" to members.map { mapOf("npub" to it) }),
        )
        val can = { grants: List<AdministrationRow>, groups: List<AdministrationRow> -> AdministrationAuthority.canManageChannelGrants(me, grants, groups, npub) }
        assertFalse("channel.manage alone never proves grant management", can(emptyList(), emptyList()))
        assertTrue("direct grant naming the permission", can(listOf(grant("actor", "me", "custom", listOf("channel.read", "channel.grants.manage"))), emptyList()))
        assertTrue("direct manage level with no permission list", can(listOf(grant("actor", "me", "manage")), emptyList()))
        assertFalse("another actor's grant", can(listOf(grant("actor", "someone", "manage", listOf("channel.grants.manage"))), emptyList()))
        assertFalse("view/contribute never manage", can(listOf(grant("actor", "me", "contribute", listOf("channel.read", "channel.write"))), emptyList()))
        assertFalse("a listed permission set without it is judged by the list, not the level", can(listOf(grant("actor", "me", "manage", listOf("channel.read"))), emptyList()))
        val groups = listOf(group("outer", emptyList(), listOf("inner")), group("inner", listOf(npub)))
        assertTrue("group grant through a nested child group", can(listOf(grant("group", "outer", "manage", listOf("channel.grants.manage"))), groups))
        assertFalse("group grant when the viewer is in no listed group (directory unreadable)", can(listOf(grant("group", "outer", "manage", listOf("channel.grants.manage"))), emptyList()))
        assertFalse("child does not inherit up to the parent's grant", can(listOf(grant("group", "inner2", "manage", listOf("channel.grants.manage"))), groups))
        assertTrue("an administrator needs no grant", AdministrationAuthority.canManageChannelGrants(WorkspaceMembership(role = "admin"), emptyList(), emptyList(), npub))
        assertTrue("workspace.manage counts as administration, as on the web", AdministrationAuthority.canManageChannelGrants(WorkspaceMembership(role = "member", permissions = setOf("workspace.manage")), emptyList(), emptyList(), npub))
    }

    @Test fun aSavedGroupListNeverAuthorisesAGroupDerivedChannelGrantWhenTheFreshReadIsRefused() = test(SampleViewer.MEMBER) { r, admin ->
        // The group holds grant management on #design and the viewer really is in it, but only an administrator can read groups now.
        this.admin.grantChannelToGroup(SampleIds.STUDIO, design, SampleAdministration.GROUP_REVIEWERS, listOf("channel.read", "channel.grants.manage"))
        this.admin.makeViewerGroupMember(SampleIds.STUDIO)
        // This device once held a wider role and saved the group list that names the viewer.
        val saved = AdministrationRow.parse(Json.obj(
            "group_id" to SampleAdministration.GROUP_REVIEWERS, "name" to "Reviewers",
            "members" to listOf(mapOf("npub" to TestKeys.NPUB, "actor_id" to SampleIds.ACTOR)),
        ))
        r.store.writer.setState(AdministrationKeys.list(r.connection.id, r.workspace.id, AdministrationList.GROUPS), AdministrationRow.encode(listOf(saved)))
        assertEquals(1, admin.cached(AdministrationList.GROUPS).size)
        val e = expectError { admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "view", false)) }
        assertTrue(e.toString(), e is ManagementError.Forbidden)
        assertTrue("nothing was sent on the strength of a saved list", writesSent().isEmpty())
    }

    @Test fun authorityLostBetweenTheFreshReadAndTheSendIsRefusedByTowerAndTheDraftSurvives() = test { _, admin ->
        val h = Hold { it.method == "POST" && it.url.endsWith("/members") }
        hold = h
        val pending = scope.async { runCatching { admin.administer(AdministrationCommand.AddMember(newNpub, "human")) } }
        withTimeout(10_000) { h.arrived.await() }
        this.admin.viewer = SampleViewer.MEMBER
        h.release.complete(Unit)
        val failure = pending.await().exceptionOrNull()
        assertTrue(failure.toString(), failure is ManagementError.Forbidden)
        admin.saveDraft("member", mapOf("npub" to newNpub, "kind" to "human"))
        assertEquals(newNpub, admin.loadDraft("member")?.get("npub"))
        assertTrue("the member list never shows it", admin.cached(AdministrationList.MEMBERS).none { it.fields["npub"] == newNpub })
    }

    @Test fun aFailedWriteLeavesDraftsAndAnUncertainOneIsNotRetried() = test { _, admin ->
        this.admin.failWrites = 500 to "internal_error"
        val e = expectError { admin.administer(AdministrationCommand.CreateGroup("Flaky")) }
        assertTrue(e is ManagementError.Unexpected)
        assertTrue(e.message!!.contains("Nothing was changed"))
        assertEquals("exactly one attempt", 1, this.admin.writes().size)
        this.admin.failWrites = null
        afterWriteOffline = true
        val readback = expectError { admin.administer(AdministrationCommand.CreateGroup("Landed")) }
        assertTrue("saved but the readback failed is its own state", readback is ManagementError.SavedButNotRefreshed)
        assertEquals(1, this.admin.writes().count { it.body["name"] == "Landed" })
    }

    @Test fun ownerStepUpIsARefusalNeverAnEscalation() = test { _, admin ->
        this.admin.stepUpOnNextWrite = true
        val e = expectError { admin.administer(AdministrationCommand.AddMember(newNpub, "human")) }
        assertTrue(e is ManagementError.Forbidden && e.message!!.contains("additional approval"))
        assertEquals(1, this.admin.writes().size)
    }

    // MARK: Scope access

    @Test fun aScopeManagerGrantsARoleUnderTheLeaseAtTheCurrentRevisionAndStaleOrDeniedEditsSendNothing() = test(SampleViewer.SCOPE_MANAGER) { _, admin ->
        val scope = SampleIds.SCOPE_AMBULANDO
        val access = admin.scopeAccess(scope)
        assertTrue(access.canManage); assertEquals(4L, access.revision)
        admin.saveScopeAccess(scope, "actor", BEA_ACTOR, "scope_manager", revoke = false, expectedRevision = access.revision)
        val put = this.admin.writes().last { it.method == "PUT" }
        assertEquals("scope_manager", put.body["role"]); assertEquals(4L, (put.body["expected_revision"] as Number).toLong()); assertNotNull(put.body["lease_token"])
        assertEquals("the stored view advances", 5L, admin.cachedScopeAccess(scope)!!.revision)
        // The same stale editor is never rebased over the new revision.
        val stale = expectError { admin.saveScopeAccess(scope, "actor", ANA_ACTOR, "context_editor", false, access.revision) }
        assertTrue(stale is ManagementError.Conflict)
        assertEquals(1, this.admin.writes().count { it.method == "PUT" })
        // A change by someone else between the editor's read and the save.
        val before = admin.scopeAccess(scope)
        this.admin.concurrentScopeChange(SampleIds.STUDIO, scope)
        assertTrue(expectError { admin.saveScopeAccess(scope, "actor", ANA_ACTOR, "context_editor", false, before.revision) } is ManagementError.Conflict)
        // Revoking removes the role grants; another editor holding the lease blocks the write.
        val fresh = admin.scopeAccess(scope)
        this.admin.scopeLeaseHeld = true
        assertTrue(expectError { admin.saveScopeAccess(scope, "actor", BEA_ACTOR, "scope_manager", revoke = true, expectedRevision = fresh.revision) } is ManagementError.Conflict)
        this.admin.scopeLeaseHeld = false
        admin.saveScopeAccess(scope, "actor", BEA_ACTOR, "scope_manager", revoke = true, expectedRevision = fresh.revision)
        assertEquals(true, this.admin.writes().last { it.method == "PUT" }.body["revoke"])
        assertTrue(admin.cachedScopeAccess(scope)!!.grants.none { it.fields["principal_id"] == BEA_ACTOR })
    }

    @Test fun anOrdinaryMemberOrAManagerOfAnotherScopeCannotEditScopeAccessAndNoLeaseIsTaken() = test(SampleViewer.MEMBER) { _, admin ->
        val access = admin.scopeAccess(SampleIds.SCOPE_AMBULANDO)
        assertFalse(access.canManage)
        assertTrue(expectError { admin.saveScopeAccess(SampleIds.SCOPE_AMBULANDO, "actor", ANA_ACTOR, "context_editor", false, access.revision) } is ManagementError.Forbidden)
        assertTrue("no lease acquire and no write", writesSent().isEmpty())
    }

    @Test fun scopeRolesDoNotGrantChannelParticipationAuthority() = test(SampleViewer.SCOPE_MANAGER) { _, admin ->
        val denied = expectError { admin.administer(AdministrationCommand.ChannelGrant(design, "actor", ANA_ACTOR, "view", false)) }
        assertTrue(denied is ManagementError.Forbidden)
        assertTrue(writesSent().isEmpty())
    }

    // MARK: Delegations, installations, activation

    private fun future(hours: Long = 24) = AdministrationCommand.isoSeconds(Instant.now().plusSeconds(hours * 3600))

    @Test fun delegationsNeedAFutureExpiryAnExplicitAppAndDestinationAndRevokeKeepsTheRow() = test { _, admin ->
        val past = AdministrationCommand.isoSeconds(Instant.now().minusSeconds(60))
        assertTrue(expectError { admin.administer(AdministrationCommand.Delegation(ANA_ACTOR, past, "sample.feed", SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.Delegation(ANA_ACTOR, future(), " ", SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(AdministrationCommand.Delegation(ANA_ACTOR, future(), "sample.feed", "nope", SampleIds.GENERAL)) } is ManagementError.Invalid)
        assertTrue(writesSent().isEmpty())
        assertEquals(true, admin.administer(AdministrationCommand.Delegation(ANA_ACTOR, future(), "sample.feed", SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)).observed)
        val filters = (this.admin.writes().last().body["filters"] as Map<*, *>)
        assertEquals(listOf("sample.feed"), filters["app_ids"]); assertEquals(listOf(SampleIds.GENERAL), filters["channel_ids"])
        assertEquals(listOf("activity.publish"), filters["capabilities"]); assertEquals(emptyList<Any>(), filters["installation_ids"])
        val id = admin.cached(AdministrationList.DELEGATIONS).first().id
        assertEquals(true, admin.administer(AdministrationCommand.RevokeDelegation(id)).observed)
        assertNotNull("revoked rows stay visible with their revocation", admin.cached(AdministrationList.DELEGATIONS).first { it.id == id }.fields["revoked_at"])
    }

    @Test fun aDelegateReconcilesOnlyTheExactInstallationTheirActiveDelegationNames() = test(SampleViewer.DELEGATE) { _, admin ->
        this.admin.delegateGrantFor(SampleIds.STUDIO, SampleIds.ACTOR, future(), installation = SampleAdministration.INSTALLATION_FEED)
        assertNotNull(admin.administer(AdministrationCommand.Installation(SampleAdministration.INSTALLATION_FEED, revoke = false)))
        val other = expectError { admin.administer(AdministrationCommand.Installation(SampleAdministration.INSTALLATION_OTHER, revoke = false)) }
        assertTrue(other.toString(), other is ManagementError.Forbidden)
        assertEquals("only the delegated installation was written", 1, this.admin.writes().count { it.path.startsWith("/wapp-installations/") })
        // Group administration is still closed to a delegate.
        assertTrue(expectError { admin.administer(AdministrationCommand.CreateGroup("x")) } is ManagementError.Forbidden)
    }

    @Test fun anExpiredOrRevokedDelegationStopsInstallationManagementOnTheNextFreshRead() = test(SampleViewer.DELEGATE) { r, _ ->
        this.admin.delegateGrantFor(SampleIds.STUDIO, SampleIds.ACTOR, future(2), installation = SampleAdministration.INSTALLATION_FEED)
        // Time passes beyond the delegation's expiry (the device clock is the one used to judge it).
        val later = administrator(r) { Instant.now().plusSeconds(3 * 3600) }
        val expired = expectError { later.administer(AdministrationCommand.Installation(SampleAdministration.INSTALLATION_FEED, revoke = true)) }
        assertTrue(expired.toString(), expired is ManagementError.Forbidden)
        assertTrue(writesSent().isEmpty())
        // A revoked one: Tower now lists it revoked.
        val now = administrator(r)
        assertNotNull(now.administer(AdministrationCommand.Installation(SampleAdministration.INSTALLATION_FEED, revoke = false)))
        this.admin.viewer = SampleViewer.ADMIN
        val delegation = now.refresh(AdministrationList.DELEGATIONS).let { now.cached(AdministrationList.DELEGATIONS) }.first().id
        now.administer(AdministrationCommand.RevokeDelegation(delegation))
        this.admin.viewer = SampleViewer.DELEGATE
        val denied = expectError { now.administer(AdministrationCommand.Installation(SampleAdministration.INSTALLATION_FEED, revoke = false)) }
        assertTrue(denied is ManagementError.Forbidden)
    }

    @Test fun installationDelegationRulesAreExactAndNeverWildcard() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val actor = "actor-1"; val feed = "inst-feed"
        fun delegation(filters: Map<String, Any?>, expires: String = "2026-12-01T00:00:00Z", revoked: String? = null, delegate: String = actor) =
            AdministrationRow.parse(Json.obj("id" to "d", "delegate_actor_id" to delegate, "expires_at" to expires, "revoked_at" to revoked, "filters" to filters))
        val installation = AdministrationRow.parse(Json.obj(
            "wapp_installation_id" to feed, "app_id" to "sample.feed", "destinations" to listOf(mapOf("scope_id" to "s1", "channel_id" to "c1")),
        ))
        fun has(d: AdministrationRow, data: AdministrationRow? = installation) = AdministrationAuthority.hasInstallationDelegation(actor, feed, listOf(d), data, now)
        assertTrue("names the installation", has(delegation(mapOf("installation_ids" to listOf(feed)))))
        assertFalse("names another installation", has(delegation(mapOf("installation_ids" to listOf("other")))))
        assertFalse("no filters is not a wildcard", has(delegation(emptyMap<String, Any?>())))
        assertTrue("app and covering destination", has(delegation(mapOf("app_ids" to listOf("sample.feed"), "scope_ids" to listOf("s1"), "channel_ids" to listOf("c1")))))
        assertFalse("destination outside the delegation", has(delegation(mapOf("app_ids" to listOf("sample.feed"), "channel_ids" to listOf("c2")))))
        assertFalse("another app", has(delegation(mapOf("app_ids" to listOf("sample.other")))))
        assertFalse("expired", has(delegation(mapOf("installation_ids" to listOf(feed)), expires = "2026-10-01T00:00:00Z")))
        assertFalse("revoked", has(delegation(mapOf("installation_ids" to listOf(feed)), revoked = "2026-10-31T00:00:00Z")))
        assertFalse("someone else's", has(delegation(mapOf("installation_ids" to listOf(feed)), delegate = "actor-2")))
        assertFalse("unparseable expiry", has(delegation(mapOf("installation_ids" to listOf(feed)), expires = "tomorrow")))
        assertFalse("app filter with no installation data to compare", has(delegation(mapOf("app_ids" to listOf("sample.feed"))), data = null))
        val active = AdministrationAuthority.activeDelegations(actor, listOf(delegation(mapOf("app_ids" to listOf("a"))), delegation(emptyMap<String, Any?>(), revoked = "x")), now)
        assertEquals(1, active.size)
        assertTrue(AdministrationAuthority.delegationCovers(actor, "d", "a", "s1", "c1", listOf(delegation(mapOf("app_ids" to listOf("a")))), now))
        assertFalse(AdministrationAuthority.delegationCovers(actor, "d", "b", "s1", "c1", listOf(delegation(mapOf("app_ids" to listOf("a")))), now))
        assertFalse("a delegation that names no app is not a wildcard for activation", AdministrationAuthority.delegationCovers(actor, "d", "a", "s1", "c1", listOf(delegation(emptyMap<String, Any?>())), now))
        assertFalse("destination filters, when present, must contain the destination", AdministrationAuthority.delegationCovers(actor, "d", "a", "s1", "c2", listOf(delegation(mapOf("app_ids" to listOf("a"), "channel_ids" to listOf("c1")))), now))
    }

    private val autopilot get() = ConfirmedAutopilotOrigin.of("https://autopilot.sample.invalid")
    private fun World.autopilotCalls() = this.admin.requests().filter { it.path.startsWith("autopilot:") }
    private fun ids() = java.util.UUID.randomUUID().toString()

    @Test fun activationRecoversTheSavedIntentWithoutCreatingASecondInstallation() = test { _, admin ->
        val catalog = admin.activationCatalog(autopilot)
        val app = catalog.apps.single { it.id == "sample.feed" }
        assertEquals("https://autopilot.sample.invalid", app.origin)
        val installation = ids(); val request = ids()
        val intent = admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, installation, request, null)
        assertEquals("the intent is bound to its origin and boundary", autopilot.url, intent.origin)
        admin.saveDraft("activation", mapOf("intent" to intent.id, "intentOrigin" to intent.origin, "intentBoundary" to intent.boundary, "installation" to installation, "request" to request))
        this.admin.failNextProcess = true
        assertTrue(expectError { admin.processActivationIntent(autopilot, intent) } is ManagementError.Offline)
        // The interrupted claim is retried against the saved intent, and a repeated create is Tower's idempotent replay.
        assertEquals(intent, admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, installation, request, null))
        admin.processActivationIntent(autopilot, intent)
        admin.refresh(AdministrationList.INSTALLATIONS)
        assertEquals(1, admin.cached(AdministrationList.INSTALLATIONS).count { it.id == installation })
        admin.refresh(AdministrationList.INTENTS)
        assertEquals("one intent", 1, admin.cached(AdministrationList.INTENTS).size)
        assertEquals(intent.id, admin.loadDraft("activation")!!["intent"])
    }

    @Test fun anAppThatNamesAnotherAutopilotIsNeverOfferedAndNeverActivatedThroughThisOrigin() = test { _, admin ->
        this.admin.catalogForeignEntry = true
        val catalog = admin.activationCatalog(autopilot)
        assertEquals(listOf("sample.feed"), catalog.apps.map { it.id }); assertEquals(1, catalog.otherOrigin)
        // Even a hand-built entry for another Autopilot is refused before any request is made.
        val foreign = catalog.apps.single().copy(id = "sample.foreign", origin = "https://other-autopilot.sample.invalid")
        val before = this.admin.requests().size
        assertTrue(expectError { admin.createActivationIntent(foreign, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null) } is ManagementError.Invalid)
        assertEquals("nothing was sent", before, this.admin.requests().size)
    }

    @Test fun aSavedIntentCannotBeClaimedThroughAnotherOriginAnotherBoundaryOrAClosedScreen() = test { r, admin ->
        val app = admin.activationCatalog(autopilot).apps.single()
        val intent = admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null)
        val calls = autopilotCalls().size
        // A different origin than the one the intent was made for.
        val elsewhere = ConfirmedAutopilotOrigin.of("https://other-autopilot.sample.invalid")
        assertTrue(expectError { admin.processActivationIntent(elsewhere, intent) } is ManagementError.Invalid)
        // An intent that belongs to another boundary (account, Tower, application or workspace).
        assertTrue(expectError { admin.processActivationIntent(autopilot, intent.copy(boundary = "another-boundary")) } is BoundaryViolation)
        // A closed screen sends nothing, including a delayed claim.
        admin.close()
        assertTrue(expectError { admin.processActivationIntent(autopilot, intent) } is AdministrationSuperseded)
        assertTrue(expectError { admin.activationCatalog(autopilot) } is AdministrationSuperseded)
        assertEquals("no claim left for Autopilot", calls, autopilotCalls().size)
        assertTrue(autopilotCalls().none { it.path == "autopilot:/api/wapps/install-intents/process" })
    }

    @Test fun aPausedWorkspaceCannotLoadACatalogCreateAnIntentOrClaimOne() = test { r, admin ->
        val app = admin.activationCatalog(autopilot).apps.single()
        val intent = admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null)
        val calls = this.admin.requests().size
        runtimes.disable(r.boundary.id)
        assertTrue(expectError { admin.activationCatalog(autopilot) } is ManagementError)
        assertTrue(expectError { admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null) } is ManagementError)
        assertTrue(expectError { admin.processActivationIntent(autopilot, intent) } is ManagementError)
        assertEquals("the closed gates stopped every request", calls, this.admin.requests().size)
    }

    @Test fun aRedirectFromAutopilotIsRefusedAndNeverFollowed() = test { _, admin ->
        this.admin.autopilotRedirect = true
        val e = expectError { admin.activationCatalog(autopilot) }
        assertTrue(e.toString(), e is ManagementError.Unexpected && e.message!!.contains("redirect"))
        assertEquals("one request, and the Location was not followed", 1, autopilotCalls().size)
        this.admin.autopilotRedirect = false
        val app = admin.activationCatalog(autopilot).apps.single()
        val intent = admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null)
        this.admin.autopilotRedirect = true
        val claim = expectError { admin.processActivationIntent(autopilot, intent) }
        assertTrue(claim is ManagementError.Unexpected && claim.message!!.contains("nothing was claimed"))
        assertTrue("every Autopilot call reached only the confirmed host", autopilotCalls().all { it.path.startsWith("autopilot:/api/wapps/") })
    }

    @Test fun onlyTheConfirmedOriginsExactCatalogAndProcessRequestsPassTheActivationGuards() = runBlocking {
        val origin = studio.otherstuff.ambulando.core.transport.TowerOrigin("https://autopilot.sample.invalid")
        val sent = ArrayList<String>()
        val base = object : HttpTransport { override suspend fun send(request: HttpRequest): HttpResponse { sent += request.url; return HttpResponse(200, "{}".toByteArray()) } }
        fun req(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()) = HttpRequest(method, url, headers, body?.toByteArray())
        val catalog = AutopilotFlowGuard(base, origin, "GET", AutopilotFlowGuard.CATALOG_PATH)
        catalog.send(req("GET", "https://autopilot.sample.invalid/api/wapps/activation-catalog"))
        for (bad in listOf(
            req("POST", "https://autopilot.sample.invalid/api/wapps/activation-catalog"), req("GET", "https://autopilot.sample.invalid/api/wapps/activation-catalog/"),
            req("GET", "https://autopilot.sample.invalid/api/wapps/activation-catalog?x=1"), req("GET", "https://autopilot.sample.invalid/api/wapps/other"),
            req("GET", "https://autopilot.sample.invalid/api/wapps/../v4/x"), req("GET", "https://elsewhere.sample.invalid/api/wapps/activation-catalog"),
            req("GET", "https://autopilot.sample.invalid:8443/api/wapps/activation-catalog"), req("GET", "http://autopilot.sample.invalid/api/wapps/activation-catalog"),
            req("GET", "https://autopilot.sample.invalid/api/wapps/activation-catalog", headers = mapOf(TowerClient.APP_NPUB_HEADER to "npub")),
            req("GET", "https://user@autopilot.sample.invalid/api/wapps/activation-catalog"),
        )) assertTrue(bad.url, runCatching { catalog.send(bad) }.exceptionOrNull() is BoundaryViolation)
        val workspace = SampleIds.STUDIO; val intent = "5a000000-0000-4000-8000-0000000000e9"
        val process = AutopilotFlowGuard(base, origin, "POST", AutopilotFlowGuard.PROCESS_PATH, AutopilotFlowGuard.processBody(workspace, intent))
        process.send(req("POST", "https://autopilot.sample.invalid/api/wapps/install-intents/process", """{"intent_id":"$intent","workspace_id":"$workspace"}"""))
        for (body in listOf(null, "{}", """{"workspace_id":"$workspace","intent_id":"other"}""", """{"workspace_id":"other","intent_id":"$intent"}""", """{"workspace_id":"$workspace","intent_id":"$intent","extra":1}""", "not json")) {
            assertTrue("body $body", runCatching { process.send(req("POST", "https://autopilot.sample.invalid/api/wapps/install-intents/process", body)) }.exceptionOrNull() is BoundaryViolation)
        }
        assertEquals("only the two exact requests reached the transport", 2, sent.size)
        // The runtime's own boundary guard also refuses any other Autopilot-style path or a query, whatever the host.
        val guard = studio.otherstuff.ambulando.core.workspaces.BoundaryGuardTransport(base, studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary("https://one.sample.invalid", "npub-x", "npub-app", workspace))
        for ((m, u) in listOf("GET" to "https://autopilot.sample.invalid/api/wapps/other", "POST" to "https://autopilot.sample.invalid/api/wapps/activation-catalog", "GET" to "https://autopilot.sample.invalid/api/wapps/activation-catalog?x=1", "DELETE" to "https://autopilot.sample.invalid/api/wapps/install-intents/process")) {
            assertTrue("$m $u", runCatching { guard.send(req(m, u)) }.exceptionOrNull() is BoundaryViolation)
        }
        // Even an exact path is refused over HTTP, with credentials in the URL, or carrying Tower's application header.
        for (bad in listOf(req("GET", "http://autopilot.sample.invalid/api/wapps/activation-catalog"), req("GET", "https://u@autopilot.sample.invalid/api/wapps/activation-catalog"),
            req("GET", "https://autopilot.sample.invalid/api/wapps/activation-catalog", headers = mapOf(TowerClient.APP_NPUB_HEADER to "npub-app")))) {
            assertTrue(bad.url, runCatching { guard.send(bad) }.exceptionOrNull() is BoundaryViolation)
        }
        assertTrue(runCatching { studio.otherstuff.ambulando.core.transport.TowerOrigin("https://autopilot.sample.invalid").url("/api/wapps/activation-catalog/x") }.exceptionOrNull() is studio.otherstuff.ambulando.core.transport.TowerError)
        assertEquals("https://autopilot.sample.invalid/api/wapps/activation-catalog", studio.otherstuff.ambulando.core.transport.TowerOrigin("https://autopilot.sample.invalid").url("/api/wapps/activation-catalog"))
        // And Tower's own /api/v4 checks are untouched: another host's workspace path still violates the boundary.
        assertTrue(runCatching { guard.send(req("GET", "https://two.sample.invalid/api/v4/flightdeck-pg/workspaces/$workspace/scopes")) }.exceptionOrNull() is BoundaryViolation)
    }

    @Test fun anOriginMustBeTypedAsAnHttpsOriginBeforeItCanBeConfirmed() {
        for (bad in listOf("http://autopilot.example.com", "autopilot.example.com", "https://autopilot.example.com/path", "https://u:p@autopilot.example.com", "https://x.fips", "")) {
            assertTrue(bad, runCatching { ConfirmedAutopilotOrigin.of(bad) }.exceptionOrNull() is ManagementError.Invalid)
        }
        assertEquals("https://autopilot.example.com", ConfirmedAutopilotOrigin.of("  https://AUTOPILOT.example.com/ ").url)
    }

    @Test fun activationByANonAdministratorNeedsAnActiveDelegationThatCoversTheAppAndDestination() = test(SampleViewer.DELEGATE) { _, admin ->
        val app = admin.activationCatalog(autopilot).apps.single()
        assertTrue(expectError { admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), null) } is ManagementError.Forbidden)
        this.admin.delegateGrantFor(SampleIds.STUDIO, SampleIds.ACTOR, future())
        admin.refresh(AdministrationList.DELEGATIONS)
        val delegationId = admin.cached(AdministrationList.DELEGATIONS).single().id
        // Delegation lists are visible to a delegate; the exact delegation is chosen explicitly and Tower still checks it.
        assertNotNull(admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), delegationId))
        assertTrue(expectError { admin.createActivationIntent(app, autopilot, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, ids(), ids(), ids()) } is ManagementError.Forbidden)
    }

    // MARK: Publishing

    @Test fun publishingAddEditDisableRevokeAndRotateUseTheWebBodiesWithoutPrivateKeys() = test { _, admin ->
        val installation = java.util.UUID.randomUUID().toString()
        val publisher = TestKeys.syntheticNpub(0x31); val owner = TestKeys.syntheticNpub(0x32); val replacement = TestKeys.syntheticNpub(0x33)
        val destinations = listOf(AdministrationDestination(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL), AdministrationDestination(SampleIds.SCOPE_AMBULANDO, design))
        val add = AdministrationCommand.Publishing(installation, "sample.feed", publisher, owner, "Feed", destinations, listOf("https://view.sample.invalid"))
        assertEquals(true, admin.administer(add).observed)
        val put = this.admin.writes().last()
        assertEquals("PUT", put.method); assertEquals("/wapp-publishing-grants/$installation", put.path)
        assertEquals(2, (put.body["destinations"] as List<*>).size); assertEquals(listOf("activity.publish"), put.body["capabilities"])
        assertEquals(listOf("https://view.sample.invalid"), put.body["registered_open_origins"])
        assertTrue(expectError { admin.administer(add.copy(origins = listOf("http://insecure.example.com"))) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(add.copy(destinations = emptyList())) } is ManagementError.Invalid)
        assertTrue(expectError { admin.administer(add.copy(publisher = "npub-bad")) } is ManagementError.Invalid)
        assertEquals("invalid forms sent nothing", 1, writesSent().size)
        // Rotation carries a nonce and a short expiry; a current key that no longer matches is Tower's conflict.
        assertEquals(true, admin.administer(AdministrationCommand.RotatePublisher(installation, publisher, replacement)).observed)
        val rotate = this.admin.writes().last().body
        assertNotNull(rotate["nonce"]); assertNotNull(rotate["expires_at"]); assertEquals(replacement, rotate["new_publisher_npub"])
        assertTrue(expectError { admin.administer(AdministrationCommand.RotatePublisher(installation, publisher, TestKeys.syntheticNpub(0x34))) } is ManagementError.Conflict)
        admin.administer(AdministrationCommand.DisablePublishing(installation))
        admin.administer(AdministrationCommand.RevokePublishing(installation))
        assertEquals("revoked", admin.cached(AdministrationList.PUBLISHING).first { it.id == installation }.fields["publishing_status"])
        assertEquals(true, this.admin.writes().last().body["disable_open_links"])
    }

    @Test fun commandRequestsAreCheckedBeforeAnythingIsSent() {
        fun invalid(command: AdministrationCommand) = try { command.request(SampleIds.STUDIO); fail("expected invalid: $command") } catch (_: ManagementError.Invalid) {}
        invalid(AdministrationCommand.AddMember("not-an-npub", "human"))
        invalid(AdministrationCommand.AddMember(newNpub, "robot"))
        invalid(AdministrationCommand.AddMember(newNpub.uppercase(), "human"))
        invalid(AdministrationCommand.MemberProfile("x", "name"))
        invalid(AdministrationCommand.CreateGroup("  "))
        invalid(AdministrationCommand.ChildGroup("p", "c", false))
        invalid(AdministrationCommand.DeleteWorkspace("workspace"))
        val request = AdministrationCommand.DeleteWorkspace(SampleIds.STUDIO).request(SampleIds.STUDIO)
        assertEquals("DELETE", request.method); assertEquals(SampleIds.STUDIO, request.body!!.string("confirmation"))
    }

    // MARK: Boundary switch and in-flight forms

    @Test fun aFormInFlightWhenTheWorkspaceInFrontChangesNeverSendsAndALateReplyIsDropped() = test { r, admin ->
        // The authority read is held; the screen is closed (workspace switch) before it returns.
        val h = Hold { it.method == "GET" && it.url.substringBefore('?').endsWith("/me") }
        hold = h
        val pending = scope.async { runCatching { admin.administer(AdministrationCommand.CreateGroup("Late")) } }
        withTimeout(10_000) { h.arrived.await() }
        admin.close()
        hold = null
        h.release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is AdministrationSuperseded)
        assertTrue("nothing was sent after the switch", this.admin.writes().isEmpty())
        // A closed administrator also refuses new work at every entry point.
        assertTrue(expectError { admin.administer(AdministrationCommand.CreateGroup("Again")) } is AdministrationSuperseded)
        assertTrue(expectError { admin.membership() } is AdministrationSuperseded)
        assertEquals(ListRefresh.Fresh, administrator(r).refresh(AdministrationList.MEMBERS))
    }

    @Test fun aSwitchDuringTheExactResourceReadStopsTheWriteThatWasAboutToLeave() = test(SampleViewer.CHANNEL_MANAGER) { _, admin ->
        // The grant manager's authority is being re-read for the channel when the screen is closed: the verdict arrives too late to send.
        val h = Hold { it.method == "GET" && it.url.substringBefore('?').endsWith("/channels/${SampleIds.DESIGN}/grants") }
        hold = h
        val pending = scope.async { runCatching { admin.administer(AdministrationCommand.ChannelGrant(SampleIds.DESIGN, "actor", ANA_ACTOR, "view", false)) } }
        withTimeout(10_000) { h.arrived.await() }
        admin.close()
        hold = null
        h.release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is AdministrationSuperseded)
        assertTrue("the allowed verdict was not acted on after the switch", this.admin.writes().isEmpty())
    }

    @Test fun theWorkspaceInFrontChangingStopsAWriteWithoutWaitingForTheScreenToBeClosed() = test { r, _ ->
        var inFront = true
        val admin = WorkspaceAdministrator(
            r.boundary, r.connection, r.workspace, r.store, identity, r.readTransport, r.directTransport, r.engine, stillInFront = { inFront },
        )
        val h = Hold { it.method == "GET" && it.url.substringBefore('?').endsWith("/me") }
        hold = h
        val pending = scope.async { runCatching { admin.administer(AdministrationCommand.CreateGroup("Not in front")) } }
        withTimeout(10_000) { h.arrived.await() }
        inFront = false
        hold = null
        h.release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is AdministrationSuperseded)
        assertTrue("nothing left after the switch", this.admin.writes().isEmpty())
        assertFalse(admin.isOpen)
    }

    @Test fun aWriteAlreadyOnItsWayCompletesOnTowerButItsReadbackAndStateDoNotLandInTheClosedScreen() = test { _, admin ->
        val h = Hold { it.method == "POST" && it.url.endsWith("/groups") }
        hold = h
        val pending = scope.async { runCatching { admin.administer(AdministrationCommand.CreateGroup("In flight")) } }
        withTimeout(10_000) { h.arrived.await() }
        admin.close()
        h.release.complete(Unit)
        pending.await()
        assertEquals("Tower committed the one request that was already on its way", 1, this.admin.writes().size)
        assertFalse("the closed screen's lists were not rewritten", admin.isOpen)
        assertTrue(admin.cached(AdministrationList.GROUPS).none { it.title == "In flight" })
    }

    @Test fun administrationRefusesAnotherBoundaryAndAPausedWorkspace() = test { r, _ ->
        val other = r.workspace.copy(id = SampleIds.LAB)
        try {
            WorkspaceAdministrator(r.boundary, r.connection, other, r.store, identity, r.readTransport, r.directTransport, r.engine)
            fail("a different workspace is another boundary")
        } catch (_: BoundaryViolation) {}
        val wrongSigner = r.connection.copy(signerNpub = TestKeys.syntheticNpub(0x55))
        try {
            WorkspaceAdministrator(r.boundary, wrongSigner, r.workspace, r.store, identity, r.readTransport, r.directTransport, r.engine)
            fail("a different signer is another boundary")
        } catch (_: BoundaryViolation) {}
        val admin = administrator(r)
        runtimes.disable(r.boundary.id)
        val before = sent.size
        val e = expectError { admin.administer(AdministrationCommand.CreateGroup("Paused")) }
        assertTrue(e.toString(), e is ManagementError)
        assertEquals("no request left a paused workspace", before, sent.size)
        assertTrue(writesSent().isEmpty())
    }

    @Test fun listsKeepCachedRowsAndNameAnUnavailableTowerRestrictedOrUnsupported() = test(SampleViewer.MEMBER) { _, admin ->
        val members = admin.refresh(AdministrationList.MEMBERS)
        assertEquals(ListRefresh.Fresh, members)
        assertTrue(admin.cached(AdministrationList.MEMBERS).isNotEmpty())
        val groups = admin.refresh(AdministrationList.GROUPS)
        assertTrue("groups are administrator-only", groups is ListRefresh.Restricted)
        tower.offline = true
        assertTrue(admin.refresh(AdministrationList.MEMBERS) is ListRefresh.Failed)
        assertTrue("the failed refresh keeps what was saved", admin.cached(AdministrationList.MEMBERS).isNotEmpty())
        tower.offline = false
    }

    @Test fun draftsAndTheAcknowledgedFingerprintArePerFormAndSurviveReload() = test { r, admin ->
        admin.saveDraft("grant:a", mapOf("access" to "manage"))
        admin.confirmDraft("grant:a", "abc")
        val second = administrator(r)
        assertEquals("manage", second.loadDraft("grant:a")!!["access"]); assertEquals("abc", second.confirmedFingerprint("grant:a"))
        assertNull(second.loadDraft("grant:b"))
        second.clearDraft("grant:a")
        assertNull(second.loadDraft("grant:a")); assertNull(second.confirmedFingerprint("grant:a"))
    }

    @Test fun rowsProjectTheNestedShapesTowerUsesAndKeepUnknownFields() {
        val grant = AdministrationRow.parse(Json.obj(
            "principal" to mapOf("actor_id" to "act-1", "display_name" to "Ana", "npub" to "npub-a", "kind" to "human"),
            "principal_type" to "person", "access_level" to "contribute", "permissions" to listOf("channel.write", "channel.read"), "future_field" to "kept",
        ))
        assertEquals("act-1", grant.id); assertEquals("Ana", grant.title); assertEquals("actor", grant.fields["principal_type"])
        assertEquals(listOf("channel.read", "channel.write"), grant.channelPermissions); assertEquals("kept", grant.fields["future_field"])
        val installation = AdministrationRow.parse(Json.obj("installation" to mapOf("wapp_installation_id" to "i1", "app_id" to "a"), "grant" to mapOf("status" to "active")))
        assertEquals("the installation fields are merged under the grant, as on the iPhone", "active", installation.fields["status"])
        val unnamed = AdministrationRow.parse(Json.obj("odd" to 1))
        assertTrue(unnamed.id.startsWith("unavailable:"))
        val again = AdministrationRow.decode(AdministrationRow.encode(listOf(grant)))
        assertEquals(grant.raw, again.single().raw)
        val delegation = AdministrationRow.parse(Json.obj("id" to "d", "delegate_actor_id" to "x", "revoked_at" to "t", "filters" to mapOf("scope_ids" to listOf("s"))))
        assertEquals(listOf("s"), delegation.filters["scope_ids"]); assertTrue(delegation.detail.contains("Revoked: t"))
    }

    @Test fun aPersonRecipientIsAlwaysAnActorWhicheverShapeCarriedIt() {
        val viaActor = AdministrationRow.parse(Json.obj("principal_type" to "person", "actor" to mapOf("actor_id" to "a1", "display_name" to "Ana")))
        assertEquals("actor", viaActor.fields["principal_type"]); assertEquals("a1", viaActor.id)
        val viaGrant = AdministrationRow.parse(Json.obj("grant" to mapOf("principal_type" to "person", "principal_id" to "p1")))
        assertEquals("actor", viaGrant.fields["principal_type"])
    }

    @Test fun expiriesAreWholeSecondsWithExactlyOneZ() {
        assertEquals("2026-11-01T10:00:00Z", AdministrationCommand.isoSeconds(Instant.parse("2026-11-01T10:00:00Z")))
        assertEquals("2026-11-01T10:00:00Z", AdministrationCommand.isoSeconds(Instant.parse("2026-11-01T10:00:00.789Z")))
        assertTrue(AdministrationCommand.isFuture(AdministrationCommand.isoSeconds(Instant.now().plusSeconds(60)), Instant.now()))
    }

    private object Json {
        fun obj(vararg pairs: Pair<String, Any?>) = studio.otherstuff.ambulando.core.transport.jsonObjectOf(*pairs)
    }
}

/** Channel creation carries explicit grants atomically; nothing widens the audience on its own. */
class InitialChannelGrantTests {
    private val workspace = studio.otherstuff.ambulando.core.transport.WorkspaceRecord.parse(studio.otherstuff.ambulando.core.support.Fixtures.obj(studio.otherstuff.ambulando.core.support.Fixtures.workspace(studio.otherstuff.ambulando.core.support.Fixtures.WORKSPACE_ID, "Studio")))
    private val a = "00000000-0000-4000-8000-0000000000a1"
    private val b = "00000000-0000-4000-8000-0000000000B2"
    private val g = "00000000-0000-4000-8000-0000000000c3"

    private suspend fun api(bodies: MutableList<studio.otherstuff.ambulando.core.transport.HttpRequest>, me: String? = "00000000-0000-4000-8000-0000000000a1"): ScopeChannelApi {
        val identity = studio.otherstuff.ambulando.core.support.makeIdentity()
        val transport = studio.otherstuff.ambulando.core.support.StubTransport { r ->
            bodies += r
            if (r.url.endsWith("/me")) 200 to mapOf("membership" to mapOf("role" to "member"), "actor" to (me?.let { mapOf("actor_id" to it) } ?: emptyMap<String, Any>()))
            else 201 to mapOf("channel" to studio.otherstuff.ambulando.core.support.Fixtures.channel("00000000-0000-4000-8000-0000000000d4", studio.otherstuff.ambulando.core.support.Fixtures.SCOPE_A, name = "x", position = 1.0))
        }
        return ScopeChannelApi(identity, studio.otherstuff.ambulando.core.support.TestKeys.NPUB, studio.otherstuff.ambulando.core.transport.TowerClient(studio.otherstuff.ambulando.core.transport.TowerOrigin(studio.otherstuff.ambulando.core.support.Fixtures.ORIGIN), transport, identity), workspace)
    }

    private fun sent(requests: List<studio.otherstuff.ambulando.core.transport.HttpRequest>) =
        studio.otherstuff.ambulando.core.transport.parseJsonObject(requests.last { it.method == "POST" }.body!!, "body")

    @org.junit.Test fun anEmptyListSendsAnEmptyGrantsKeyAndReadsNoMe() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<studio.otherstuff.ambulando.core.transport.HttpRequest>()
        api(requests).createChannel(studio.otherstuff.ambulando.core.support.Fixtures.SCOPE_A, "x", null, null)
        org.junit.Assert.assertEquals(0, (sent(requests)["grants"] as kotlinx.serialization.json.JsonArray).size)
        org.junit.Assert.assertTrue(requests.none { it.url.endsWith("/me") })
    }

    @org.junit.Test fun exactBodyNormalisesAliasAndIdsAndDropsTheCreatorsOwnBundle() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<studio.otherstuff.ambulando.core.transport.HttpRequest>()
        api(requests).createChannel(
            studio.otherstuff.ambulando.core.support.Fixtures.SCOPE_A, "x", null, null,
            listOf(InitialChannelGrant("person", a, "manage"), InitialChannelGrant("person", b, "contribute"), InitialChannelGrant("group", g)),
        )
        val grants = (sent(requests)["grants"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
        org.junit.Assert.assertEquals(listOf("actor" to b.lowercase(), "group" to g), grants.map { it.string("principal_type")!! to it.string("principal_id")!! })
        org.junit.Assert.assertEquals(listOf("contribute", "view"), grants.map { it.string("access_level") })
        org.junit.Assert.assertTrue("creator identity was read fresh", requests.any { it.url.endsWith("/me") })
    }

    @org.junit.Test fun duplicateInvalidOrUnidentifiedRecipientsAreRefusedBeforeCreation() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<studio.otherstuff.ambulando.core.transport.HttpRequest>()
        val api = api(requests)
        val s = studio.otherstuff.ambulando.core.support.Fixtures.SCOPE_A
        for (bad in listOf(
            listOf(InitialChannelGrant("actor", b), InitialChannelGrant("person", b.uppercase())), listOf(InitialChannelGrant("robot", b)),
            listOf(InitialChannelGrant("actor", b, "owner")), listOf(InitialChannelGrant("actor", "nope")),
        )) {
            try { api.createChannel(s, "x", null, null, bad); org.junit.Assert.fail("expected refusal") } catch (_: ManagementError.Invalid) {}
        }
        org.junit.Assert.assertTrue(requests.isEmpty())
        try { api(requests, me = null).createChannel(s, "x", null, null, listOf(InitialChannelGrant("actor", b))); org.junit.Assert.fail() } catch (_: ManagementError.Unexpected) {}
        org.junit.Assert.assertTrue("no create without a known creator", requests.none { it.method == "POST" })
        // A group-only list needs no creator identity.
        api(requests).createChannel(s, "x", null, null, listOf(InitialChannelGrant("group", g, "manage")))
        Unit
    }
}
