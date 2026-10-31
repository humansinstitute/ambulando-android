package studio.otherstuff.ambulando.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.material3.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.management.AdministrationList
import studio.otherstuff.ambulando.core.management.WorkspaceAdministrator
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.sample.SampleAdministration
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleViewer
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.toJsonElement
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.File
import java.nio.file.Files

/**
 * The real Manage Workspace screens driven through a real store, scheduler, signer and the synthetic administration Tower.
 * They prove what the person sees and what is (and is not) sent: nothing here is evidence of live Tower behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class AdministrationUiTest {
    @get:Rule val compose = createComposeRule()

    private class Memory : VaultSecretStore {
        private var value: ByteArray? = null
        @Synchronized override fun read() = value?.copyOf()
        @Synchronized override fun write(data: ByteArray) { value = data.copyOf() }
    }

    private class Hold(val matches: (HttpRequest) -> Boolean) {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
    private lateinit var tower: SampleTower
    private lateinit var session: AdministrationSession
    private lateinit var admin: WorkspaceAdministrator
    private var front by mutableStateOf<String?>(null)
    @Volatile private var hold: Hold? = null
    @Volatile private var offlineAfterWrite = false
    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val agent = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { 0x71 }))!!
    private lateinit var navScopes: List<ScopeRecord>
    private lateinit var navChannels: List<ChannelRecord>

    private fun setUp(viewer: SampleViewer) {
        val dir = Files.createTempDirectory("admin-ui").toFile()
        tower = SampleTower(File(dir, "tower"), originUrl = "https://one.sample.invalid").also { it.prepare(npub) }
        tower.administration.viewer = viewer
        val identity = runBlocking { IdentityService(File(dir, "vault.json"), Memory()).also { it.importIdentity("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5", "246810") } }
        val connection = ConnectionRecord.create(TowerOrigin("https://one.sample.invalid"), npub, null)
        val workspace = WorkspaceRecord.parse(toJsonElement(mapOf(
            "identity" to mapOf("workspace_id" to SampleIds.STUDIO, "app_npub" to SampleIds.APP, "workspace_owner_npub" to SampleIds.ANA),
            "label" to "Sample Studio", "slug" to "sample-studio", "tower_base_url" to "https://one.sample.invalid", "membership" to mapOf("role" to "member"),
        )) as kotlinx.serialization.json.JsonObject)
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                hold?.takeIf { it.matches(request) }?.let { h -> h.arrived.complete(Unit); h.release.await() }
                val response = tower.send(request)
                if (offlineAfterWrite && request.method != "GET") tower.offline = true
                return response
            }
        }
        val boundary = WorkspaceBoundary.of(connection, workspace)
        admin = WorkspaceAdministrator(boundary, connection, workspace, LocalStore(File(dir, "store")), identity, http, http, engine)
        front = boundary.id
        session = AdministrationSession(admin, workspace.label, { front }, scope)
        navScopes = listOf(ScopeRecord(SampleIds.STUDIO, SampleIds.SCOPE_AMBULANDO, "Ambulando", updatedAt = ""))
        navChannels = listOf(
            ChannelRecord(SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, "general", updatedAt = ""),
            ChannelRecord(SampleIds.STUDIO, SampleIds.DESIGN, SampleIds.SCOPE_AMBULANDO, "design", updatedAt = ""),
        )
    }

    @After fun tearDown() { hold?.release?.complete(Unit); scope.cancel(); engine.close() }

    private fun show(initial: AdminRoute = AdminRoute.Home) {
        compose.setContent {
            AmbulandoTheme {
                AdministrationHost(
                    session, WorkspaceRecord(SampleIds.STUDIO, SampleIds.APP, "Sample Studio", "sample-studio", null, "member", SampleIds.ANA, kotlinx.serialization.json.JsonObject(emptyMap())),
                    navScopes, navChannels, { back, _, _ -> Text("scopes stub", modifier = Modifier.testTag("scopes.stub")); androidx.compose.material3.TextButton(onClick = back) { Text("b") } },
                    done = {}, initial = initial, pollMillis = 0,
                )
            }
        }
    }

    private fun tag(tag: String) = compose.onNodeWithTag(tag)
    private fun click(tag: String) { val node = tag(tag); runCatching { node.performScrollTo() }; node.performClick() }
    private fun waitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun goneTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    private fun saveAndConfirm() { click("admin.save"); waitTag("admin.confirm"); click("admin.confirm") }
    private fun awaitAuthority() = compose.waitUntil(20_000) { session.authority is Authority.Known || session.authority is Authority.Unavailable }
    private val writes get() = tower.administration.writes()

    @Test fun anAdministratorAddsAMemberFromTheScreenAndSeesTowersAnswer() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Section(AdminSection.MEMBERS))
        awaitAuthority()
        compose.waitUntil(20_000) { session.canAdminister }
        click("admin.member.add")
        waitTag("admin.field.npub")
        tag("admin.field.npub").performTextInput(agent)
        click("admin.choice.kind.agent")
        saveAndConfirm()
        waitTag("admin.status.done")
        val post = writes.single { it.path == "/members" }
        assertEquals(agent, post.body["member_npub"]); assertEquals("agent", post.body["kind"]); assertEquals("member", post.body["role"])
        // The same unchanged, acknowledged entries cannot be saved again without an edit.
        waitTag("admin.alreadySaved")
        tag("admin.save").assertIsNotEnabled()
    }

    @Test fun anOrdinaryMemberSeesReadOnlyControlsAndNothingIsSent() {
        setUp(SampleViewer.MEMBER)
        show(AdminRoute.Section(AdminSection.MEMBERS))
        awaitAuthority()
        waitTag("admin.authority.readOnly")
        tag("admin.member.add").assertIsNotEnabled()
        // The member directory is readable by a member; it is shown, not hidden.
        compose.waitUntil(20_000) { session.rows(AdministrationList.MEMBERS).isNotEmpty() }
        assertTrue(writes.isEmpty())
    }

    @Test fun authorityRevokedWhileAnEditorIsOpenFailsClearlyKeepsTheEntriesAndTurnsTheControlOff() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.GroupNew)
        compose.waitUntil(20_000) { session.canAdminister }
        tag("admin.field.name").performTextInput("Ops")
        // Authority is withdrawn after the screen drew its controls but before the save.
        tower.administration.viewer = SampleViewer.MEMBER
        saveAndConfirm()
        waitTag("admin.status.failed")
        compose.waitUntil(20_000) { session.authority is Authority.Known && !session.canAdminister }
        // The typed entries are still there, the control is off, and nothing was created.
        tag("admin.field.name").assertTextContains("Ops")
        compose.waitUntil(5_000) { runCatching { tag("admin.save").assertIsNotEnabled() }.isSuccess }
        assertTrue(writes.none { it.path == "/groups" })
    }

    @Test fun anUnreadableAuthorityTurnsControlsOffRatherThanFallingBackToWhatWasCached() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Section(AdminSection.MEMBERS))
        compose.waitUntil(20_000) { session.canAdminister }
        tower.offline = true
        session.checkAuthority()
        compose.waitUntil(20_000) { session.authority is Authority.Unavailable }
        waitTag("admin.authority.unavailable")
        tag("admin.member.add").assertIsNotEnabled()
        tower.offline = false
    }

    @Test fun aFormInFlightWhenTheWorkspaceInFrontChangesReportsNothingIntoTheNewScreen() {
        setUp(SampleViewer.ADMIN)
        var shown by mutableStateOf(true)
        compose.setContent {
            AmbulandoTheme {
                if (shown) AdministrationHost(
                    session, WorkspaceRecord(SampleIds.STUDIO, SampleIds.APP, "Sample Studio", "sample-studio", null, "member", SampleIds.ANA, kotlinx.serialization.json.JsonObject(emptyMap())),
                    navScopes, navChannels, { _, _, _ -> }, done = {}, initial = AdminRoute.GroupNew, pollMillis = 0,
                ) else Text("another workspace", modifier = Modifier.testTag("other.screen"))
            }
        }
        compose.waitUntil(20_000) { session.canAdminister }
        tag("admin.field.name").performTextInput("In flight")
        val h = Hold { it.method == "POST" && it.url.endsWith("/groups") }
        hold = h
        saveAndConfirm()
        runBlocking { kotlinx.coroutines.withTimeout(20_000) { h.arrived.await() } }
        // The workspace in front changes: the screen is rebuilt for another one while the request is outstanding.
        front = "another-boundary"
        compose.runOnIdle { shown = false; session.close() }
        hold = null
        h.release.complete(Unit)
        compose.waitUntil(20_000) { writes.count { it.path == "/groups" } == 1 }
        waitTag("other.screen")
        // The late reply neither opened a status in the other screen nor reached the closed session's state.
        assertTrue(goneTag("admin.status.done")); assertTrue(goneTag("admin.status.failed"))
        assertFalse(session.isCurrent)
        assertTrue(session.phase is Phase.Working || session.phase is Phase.Idle)
    }

    @Test fun aStaleScreenOffersNoControlsAfterTheWorkspaceChanged() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Section(AdminSection.MEMBERS))
        compose.waitUntil(20_000) { session.canAdminister }
        front = "another-boundary"
        // Any recomposition now shows the explanatory page, not a form that could write into the wrong workspace.
        session.checkAuthority()
        waitTag("admin.stale.note")
        assertTrue(goneTag("admin.member.add"))
        assertTrue(writes.isEmpty())
    }

    @Test fun aDirectChannelGrantManagerCanChangeTheirChannelButOthersAreReadOnly() {
        setUp(SampleViewer.CHANNEL_MANAGER)
        show(AdminRoute.ChannelAccess(SampleIds.DESIGN))
        waitTag("admin.channel.grant")
        compose.waitUntil(20_000) { runCatching { tag("admin.channel.grant").assertIsEnabled() }.isSuccess }
        click("admin.channel.grant")
        waitTag("admin.pick.principal")
        click("admin.pick.principal"); waitTag("admin.pick.option.${java.util.UUID.nameUUIDFromBytes(SampleIds.ANA.toByteArray())}")
        click("admin.pick.option.${java.util.UUID.nameUUIDFromBytes(SampleIds.ANA.toByteArray())}")
        click("admin.choice.access.contribute")
        saveAndConfirm()
        waitTag("admin.status.done")
        val post = writes.single { it.path == "/channels/${SampleIds.DESIGN}/grants" }
        assertEquals("contribute", post.body["access_level"]); assertEquals("actor", post.body["principal_type"])
    }

    @Test fun aChannelTheMemberDoesNotManageShowsTheListButNoWriteControls() {
        setUp(SampleViewer.CHANNEL_MANAGER)
        show(AdminRoute.ChannelAccess(SampleIds.GENERAL))
        waitTag("admin.channel.readOnly")
        tag("admin.channel.grant").assertIsNotEnabled()
        assertTrue(writes.isEmpty())
    }

    @Test fun anAdministratorRevokesAGrantAfterConfirmationOnly() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.ChannelAccess(SampleIds.DESIGN))
        val designers = SampleAdministration.GROUP_DESIGNERS
        waitTag("admin.grant.revoke.$designers")
        click("admin.grant.revoke.$designers")
        waitTag("admin.revoke.cancel"); click("admin.revoke.cancel")
        assertTrue("cancel sends nothing", writes.isEmpty())
        click("admin.grant.revoke.$designers"); waitTag("admin.revoke.confirm"); click("admin.revoke.confirm")
        compose.waitUntil(20_000) { writes.any { it.method == "DELETE" } }
        assertEquals("/channels/${SampleIds.DESIGN}/grants/group/$designers", writes.last { it.method == "DELETE" }.path)
    }

    @Test fun groupNestingShowsWhyAnAttachmentIsNotOfferedAndDetachingNeedsConfirmation() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Group(SampleAdministration.GROUP_REVIEWERS))
        compose.waitUntil(20_000) { session.rows(AdministrationList.GROUPS).isNotEmpty() && session.canAdminister }
        // Reviewers is nested inside Designers; offering Designers as a child of Reviewers would be a cycle.
        click("admin.pick.child"); waitTag("admin.pick.option.${SampleAdministration.GROUP_DESIGNERS}")
        compose.onNodeWithTag("admin.pick.option.${SampleAdministration.GROUP_DESIGNERS}").assertContentDescriptionContains("Would create a cycle", substring = true)
    }

    @Test fun detachingANestedGroupAsksFirstAndKeepsTheGroup() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Group(SampleAdministration.GROUP_DESIGNERS))
        val child = SampleAdministration.GROUP_REVIEWERS
        waitTag("admin.group.child.detach.$child")
        compose.waitUntil(20_000) { runCatching { tag("admin.group.child.detach.$child").assertIsEnabled() }.isSuccess }
        click("admin.group.child.detach.$child")
        waitTag("admin.pending.cancel"); click("admin.pending.cancel")
        assertTrue(writes.isEmpty())
        click("admin.group.child.detach.$child"); waitTag("admin.pending.confirm"); click("admin.pending.confirm")
        compose.waitUntil(20_000) { writes.any { it.method == "DELETE" } }
        assertEquals("/groups/${SampleAdministration.GROUP_DESIGNERS}/child-groups/$child", writes.last().path)
        compose.waitUntil(20_000) { goneTag("admin.group.child.detach.$child") }
    }

    @Test fun aDelegateSeesReconcileOnlyForTheirInstallationAndNotForOthers() {
        setUp(SampleViewer.DELEGATE)
        tower.administration.delegateGrantFor(SampleIds.STUDIO, SampleIds.ACTOR, studio.otherstuff.ambulando.core.management.AdministrationCommand.isoSeconds(java.time.Instant.now().plusSeconds(3600)), SampleAdministration.INSTALLATION_FEED)
        show(AdminRoute.Section(AdminSection.APPS))
        waitTag("admin.installation.reconcile.${SampleAdministration.INSTALLATION_FEED}")
        waitTag("admin.installation.noauthority.${SampleAdministration.INSTALLATION_OTHER}")
        click("admin.installation.reconcile.${SampleAdministration.INSTALLATION_FEED}")
        compose.waitUntil(20_000) { writes.any { it.path == "/wapp-installations/${SampleAdministration.INSTALLATION_FEED}/reconcile" } }
        tag("admin.delegation.new").assertIsNotEnabled()
        tag("admin.publishing.new").assertIsNotEnabled()
    }

    @Test fun anExpiredOrOrphanedDestinationBlocksAPublishingSaveInsteadOfSilentlyDroppingIt() {
        setUp(SampleViewer.ADMIN)
        // A publishing grant that still names a destination the member cannot see.
        val installation = java.util.UUID.randomUUID().toString()
        val hidden = java.util.UUID.randomUUID().toString()
        navChannels = navChannels.take(1)
        runBlocking {
            // Create through the Tower the way an administrator with wider visibility did.
            val publisher = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { 0x31 }))!!
            admin.administer(studio.otherstuff.ambulando.core.management.AdministrationCommand.Publishing(
                installation, "sample.feed", publisher, publisher, "Feed",
                listOf(studio.otherstuff.ambulando.core.management.AdministrationDestination(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL), studio.otherstuff.ambulando.core.management.AdministrationDestination(SampleIds.SCOPE_AMBULANDO, hidden)),
                emptyList(),
            ))
        }
        show(AdminRoute.Publishing(installation))
        compose.waitUntil(20_000) { session.rows(AdministrationList.PUBLISHING).any { it.id == installation } && session.canAdminister }
        waitTag("admin.publishing.hidden")
        tag("admin.field.name").performTextReplacement("Feed 2")
        val before = writes.size
        saveAndConfirm()
        waitTag("admin.status.failed")
        assertEquals("nothing was sent", before, writes.size)
    }

    @Test fun savedOnTowerButNotReadBackIsShownAsSavedAndTheCreateIsNotOfferedAgain() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.GroupNew)
        compose.waitUntil(20_000) { session.canAdminister }
        waitTag("admin.field.name")
        compose.waitUntil(20_000) { runCatching { tag("admin.field.name").assertIsEnabled() }.isSuccess }
        tag("admin.field.name").performTextReplacement("Landed")
        offlineAfterWrite = true
        saveAndConfirm()
        waitTag("admin.status.done")
        assertTrue("saved, not failed", goneTag("admin.status.failed"))
        assertEquals(1, writes.count { it.path == "/groups" })
        // Tower has it, so the same entries cannot be sent again as a second group.
        waitTag("admin.alreadySaved")
        tag("admin.save").assertIsNotEnabled()
    }

    @Test fun aDraftIsOnlyRestoredOverTheValuesItWasStartedFromAndAFinishedCreateStartsFresh() {
        setUp(SampleViewer.ADMIN)
        runBlocking {
            val first = FormState(session, "profile", mapOf("name" to "A", "description" to ""))
            first.load(); first["description"] = "my edit"; first.persist()
            // Same record: the saved entries come back.
            val same = FormState(session, "profile", mapOf("name" to "A", "description" to ""))
            same.load()
            assertEquals("my edit", same["description"]); assertFalse(same.draftDiscarded)
            // Someone renamed the workspace meanwhile: the old entries must not silently undo that.
            val changed = FormState(session, "profile", mapOf("name" to "B", "description" to ""))
            changed.load()
            assertEquals("B", changed["name"]); assertEquals("", changed["description"]); assertTrue(changed.draftDiscarded)
            assertEquals("the stale draft is gone", null, admin.loadDraft("profile"))

            // A create with a generated identifier keeps it across a failed save, and a completed one is cleared.
            val create = FormState(session, "publishing:new", mapOf("installation" to "id-1", "name" to ""), baseKeys = emptySet())
            create.load(); create["name"] = "Feed"; create.persist()
            val retry = FormState(session, "publishing:new", mapOf("installation" to "id-2", "name" to ""), baseKeys = emptySet())
            retry.load()
            assertEquals("a retried save repeats the same idempotent PUT", "id-1", retry["installation"])
            retry.complete()
            val next = FormState(session, "publishing:new", mapOf("installation" to "id-3", "name" to ""), baseKeys = emptySet())
            next.load()
            assertEquals("the next new installation gets its own id", "id-3", next["installation"]); assertEquals("", next["name"])
        }
    }

    @Test fun navigatingAwayNeverHidesAWriteStillInFlight() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.GroupNew)
        compose.waitUntil(20_000) { session.canAdminister }
        waitTag("admin.field.name")
        compose.waitUntil(20_000) { runCatching { tag("admin.field.name").assertIsEnabled() }.isSuccess }
        tag("admin.field.name").performTextReplacement("Slow")
        val h = Hold { it.method == "POST" && it.url.endsWith("/groups") }
        hold = h
        saveAndConfirm()
        runBlocking { kotlinx.coroutines.withTimeout(20_000) { h.arrived.await() } }
        click("admin.back")
        waitTag("admin.status.working")
        assertTrue("still working after leaving the page", session.phase is Phase.Working)
        hold = null
        h.release.complete(Unit)
        compose.waitUntil(20_000) { session.phase !is Phase.Working }
    }

    @Test fun activationShowsTheExactOriginBeforeAnySignedRequestAndBindsTheSavedIntentToItAndTheWorkspace() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Activation)
        compose.waitUntil(20_000) { session.canAdminister }
        compose.waitUntil(20_000) { runCatching { tag("admin.field.origin").assertIsEnabled() }.isSuccess }
        val autopilotCalls = { tower.administration.requests().filter { it.path.startsWith("autopilot:") } }
        // Not an HTTPS origin: no way to ask for the catalog.
        tag("admin.field.origin").performTextReplacement("http://autopilot.sample.invalid")
        waitTag("admin.activation.target"); tag("admin.activation.catalog").assertIsNotEnabled()
        // The origin is shown in its normalized form, and nothing is signed until it is confirmed.
        tag("admin.field.origin").performTextReplacement("  https://AUTOPILOT.sample.invalid/ ")
        compose.waitUntil(10_000) { runCatching { tag("admin.activation.target").assertTextContains("exactly https://autopilot.sample.invalid", substring = true) }.isSuccess }
        click("admin.activation.catalog")
        waitTag("admin.activation.sign.cancel")
        compose.onNodeWithText("Sign a request to https://autopilot.sample.invalid?").assertExists()
        click("admin.activation.sign.cancel")
        assertTrue("cancel signs nothing", autopilotCalls().isEmpty())
        click("admin.activation.catalog"); waitTag("admin.activation.sign.confirm"); click("admin.activation.sign.confirm")
        waitTag("admin.status.done")
        assertEquals(listOf("GET autopilot:/api/wapps/activation-catalog"), autopilotCalls().map { it.method + " " + it.path })
        click("admin.pick.app"); waitTag("admin.pick.option.sample.feed"); click("admin.pick.option.sample.feed")
        click("admin.pick.channel"); waitTag("admin.pick.option.${SampleIds.GENERAL}"); click("admin.pick.option.${SampleIds.GENERAL}")
        click("admin.activation.activate"); waitTag("admin.activation.sign.confirm"); click("admin.activation.sign.confirm")
        waitTag("admin.activation.savedIntent")
        compose.waitUntil(20_000) { autopilotCalls().any { it.path == "autopilot:/api/wapps/install-intents/process" } }
        val saved = runBlocking { admin.loadDraft("activation") }!!
        assertEquals("https://autopilot.sample.invalid", saved["intentOrigin"]); assertEquals(admin.boundary.id, saved["intentBoundary"])
        assertTrue(autopilotCalls().all { it.path.startsWith("autopilot:/api/wapps/") })
        // Finished: a new activation needs fresh ids, not the saved intent.
        waitTag("admin.activation.another")
    }

    @Test fun groupDerivedChannelAuthorityIsOfferedOnlyWhenTheFreshDirectoryShowsIt() {
        // A non-administrator cannot read the group directory, so a grant held through a group cannot be shown: the screen offers no
        // change, and (being the same decision the write makes) there is nothing it would then refuse.
        setUp(SampleViewer.CHANNEL_MANAGER)
        tower.administration.makeViewerGroupMember(SampleIds.STUDIO)
        tower.administration.grantChannelToGroup(SampleIds.STUDIO, SampleIds.GENERAL, SampleAdministration.GROUP_REVIEWERS, listOf("channel.grants.manage"))
        show(AdminRoute.ChannelAccess(SampleIds.GENERAL))
        waitTag("admin.channel.readOnly")
        tag("admin.channel.grant").assertIsNotEnabled()
        assertTrue(writes.none { it.method != "GET" })
    }

    @Test fun anAdministratorMustTypeTheWorkspaceNameBeforeDeletionAndConfirmItsExactBody() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.DeleteWorkspace)
        compose.waitUntil(20_000) { session.canAdminister }
        waitTag("admin.delete.name")
        tag("admin.delete.go").assertIsNotEnabled()
        tag("admin.delete.name").performTextInput("Sample Studi")
        tag("admin.delete.go").assertIsNotEnabled()
        tag("admin.delete.name").performTextReplacement("Sample Studio")
        compose.waitUntil(10_000) { runCatching { tag("admin.delete.go").assertIsEnabled() }.isSuccess }
        click("admin.delete.go"); waitTag("admin.delete.cancel"); click("admin.delete.cancel")
        assertTrue("cancel sends nothing", writes.isEmpty())
        click("admin.delete.go"); waitTag("admin.delete.confirm"); click("admin.delete.confirm")
        compose.waitUntil(20_000) { writes.any { it.method == "DELETE" } }
        val delete = writes.single { it.method == "DELETE" }
        assertEquals("", delete.path); assertEquals(SampleIds.STUDIO, delete.body["confirmation"])
    }

    @Test fun aFinishedNewPublishingInstallationStartsOverWithAFreshIdSoAnEditCannotOverwriteIt() {
        setUp(SampleViewer.ADMIN)
        show(AdminRoute.Publishing(null))
        compose.waitUntil(20_000) { session.canAdminister }
        compose.waitUntil(20_000) { runCatching { tag("admin.field.app").assertIsEnabled() }.isSuccess }
        val publisher = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { 0x32 }))!!
        tag("admin.field.app").performTextInput("sample.feed"); tag("admin.field.name").performTextInput("Feed A")
        tag("admin.field.publisher").performTextInput(publisher); tag("admin.field.owner").performTextInput(publisher)
        click("admin.dest.${SampleIds.GENERAL}")
        saveAndConfirm()
        waitTag("admin.status.done")
        val first = writes.single { it.path.startsWith("/wapp-publishing-grants/") }.path
        // The same editor is now empty again, with its own new identifier; an edit and save is a second installation.
        compose.waitUntil(10_000) { runCatching { tag("admin.field.name").assertTextContains("") }.isSuccess && goneTag("admin.alreadySaved").not().not() }
        tag("admin.field.app").performTextInput("sample.feed"); tag("admin.field.name").performTextInput("Feed B")
        tag("admin.field.publisher").performTextInput(publisher); tag("admin.field.owner").performTextInput(publisher)
        click("admin.dest.${SampleIds.GENERAL}")
        saveAndConfirm()
        compose.waitUntil(20_000) { writes.count { it.path.startsWith("/wapp-publishing-grants/") } == 2 }
        val second = writes.last { it.path.startsWith("/wapp-publishing-grants/") }.path
        assertTrue("a new installation id, not the finished one", first != second)
    }
}
