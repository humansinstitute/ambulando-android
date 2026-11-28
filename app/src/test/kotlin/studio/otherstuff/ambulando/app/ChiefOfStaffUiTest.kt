package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.nav.RouteState
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.SidebarActions
import studio.otherstuff.ambulando.app.ui.shell.SidebarContent
import studio.otherstuff.ambulando.app.ui.shell.SidebarState
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffAssignment
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffContract
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffCorrection
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffCorrections
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffCoverage
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffEvidence
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffJobStatus
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffOntology
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffTopic
import studio.otherstuff.ambulando.core.chiefofstaff.CohortLoadResult
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.NavScope
import studio.otherstuff.ambulando.core.context.NavigationLoadState
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.transport.ChannelRecord

@RunWith(RobolectricTestRunner::class)
class ChiefOfStaffUiTest {
    @get:Rule val compose = createComposeRule()

    private val ws = "11111111-1111-4111-8111-111111111111"
    private val ownerNpub = Bech32.encode("npub", ByteArray(32) { 1 })
    private val agentNpub = Bech32.encode("npub", ByteArray(32) { 2 })
    private val appNpub = Bech32.encode("npub", ByteArray(32) { 4 })
    private val ownerActorId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
    private val agentActorId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"

    private val sampleAssignment = ChiefOfStaffAssignment(
        workspaceId = ws,
        workspaceOwnerNpub = ownerNpub,
        appNpub = appNpub,
        assignmentRevision = 2,
        assignedActorId = agentActorId,
        assignedNpub = agentNpub,
        assignedDisplayName = "Rick",
        previousAssignedNpub = null,
        updatedByActorId = ownerActorId,
        updatedByNpub = ownerNpub,
        updatedAt = "2026-11-28T00:00:00Z",
    )

    @Test fun sidebarShowsNearTopChiefOfStaffShortcutWhenAssigned() {
        var dmTapped = false
        var topicsTapped = false
        val state = SidebarState(
            workspaceLabel = "Studio",
            selectedWorkspaceId = ws,
            workspaces = listOf(ws to "Studio"),
            workspacesLoaded = true,
            groups = emptyList(),
            unread = emptySet(),
            collapsed = emptySet(),
            context = WorkContext.HOME,
            labels = emptyMap(),
            canCreateChannelIn = emptySet(),
            loadState = NavigationLoadState.Ready(null),
            identityUnavailable = false,
            chiefOfStaffLabel = "Rick",
        )
        val actions = object : SidebarActions {
            override fun openChiefOfStaffDm() { dmTapped = true }
            override fun openChiefOfStaffTopics() { topicsTapped = true }
            override fun chooseHome() {}
            override fun chooseScope(scopeId: String) {}
            override fun chooseChannel(scopeId: String, channelId: String) {}
            override fun toggleCollapsed(scopeId: String) {}
            override fun newChannel(scope: NavScope) {}
            override fun channelSettings(channel: NavChannel) {}
            override fun manageScopes() {}
            override fun selectWorkspace(workspaceId: String) {}
            override fun retry() {}
            override fun close() {}
        }
        compose.setContent { AmbulandoTheme { SidebarContent(state, actions) } }
        compose.onNodeWithTag("sidebar.chiefOfStaff.dm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sidebar.chiefOfStaff.topics").assertIsDisplayed().performClick()
        assertTrue(dmTapped)
        assertTrue(topicsTapped)
    }

    @Test fun restrictedAndPendingStatesAreDistinctAndNeverLeakPrivateTopics() {
        compose.setContent {
            AmbulandoTheme {
                ChiefOfStaffTopicsContent(
                    workspaceLabel = "Studio",
                    unlocked = true,
                    loading = false,
                    assignment = sampleAssignment,
                    cohort = CohortLoadResult.Restricted,
                    error = null,
                    searchQuery = "",
                    showArchived = false,
                    selectedTopicId = null,
                    savingCorrection = false,
                    correctionNotice = null,
                    viewerNpub = Bech32.encode("npub", ByteArray(32) { 9 }),
                    onSearchChange = {},
                    onShowArchivedChange = {},
                    onSelectTopic = {},
                    onRefresh = {},
                    onOpenDm = {},
                    onOpenEvidence = {},
                    onSubmitCorrection = {},
                )
            }
        }
        compose.onNodeWithTag("chiefOfStaff.restricted").assertIsDisplayed()
        compose.onNodeWithTag("chiefOfStaff.openDm").assertIsDisplayed()
        compose.onNodeWithTag("chiefOfStaff.search").assertDoesNotExist()
    }

    @Test fun pendingSetupDistinguishesAssignedFromError() {
        compose.setContent {
            AmbulandoTheme {
                ChiefOfStaffTopicsContent(
                    workspaceLabel = "Studio",
                    unlocked = true,
                    loading = false,
                    assignment = sampleAssignment,
                    cohort = CohortLoadResult.PendingSetup(2),
                    error = null,
                    searchQuery = "",
                    showArchived = false,
                    selectedTopicId = null,
                    savingCorrection = false,
                    correctionNotice = null,
                    viewerNpub = ownerNpub,
                    onSearchChange = {},
                    onShowArchivedChange = {},
                    onSelectTopic = {},
                    onRefresh = {},
                    onOpenDm = {},
                    onOpenEvidence = {},
                    onSubmitCorrection = {},
                )
            }
        }
        compose.onNodeWithTag("chiefOfStaff.pendingSetup").assertIsDisplayed()
        compose.onNodeWithText("Assigned · Index setup pending").assertIsDisplayed()
    }

    @Test fun topicDetailDrillsThroughEvidenceAndSubmitsCorrections() {
        val taskId = "99999999-9999-4999-8999-999999999999"
        val cohort = CohortLoadResult.Ready(
            channel = ChannelRecord(ws, "66666666-6666-4666-8666-666666666666", "33333333-3333-4333-8333-333333333333", "Index", "channel", updatedAt = ""),
            assignmentEpoch = 2,
            jobStatus = ChiefOfStaffJobStatus(
                workspaceId = ws, workspaceOwnerNpub = ownerNpub, appNpub = appNpub,
                cohortId = ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2),
                assignmentEpoch = 2, boundAgentNpub = agentNpub, assignmentRevisionSeen = 2,
                scheduleState = "healthy", autopilotJobId = "job-1", pipelineDefinitionId = "pipe-1",
                cronExpression = "*/30 * * * *", timezone = "Australia/Perth",
                lastRunAt = "2026-11-28T01:00:00Z", lastSuccessAt = "2026-11-28T01:00:00Z", lastError = null,
                topicCount = 1, evidenceCount = 1, cursorSummary = "ok",
                coverage = ChiefOfStaffCoverage(), updatedAt = "2026-11-28T01:00:00Z",
            ),
            jobStatusVersionId = "v1",
            ontology = ChiefOfStaffOntology(
                workspaceId = ws, workspaceOwnerNpub = ownerNpub, appNpub = appNpub,
                cohortId = ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2),
                assignmentEpoch = 2, agentNpub = agentNpub, snapshotRevision = 1, assignmentRevisionSeen = 2,
                generatedAt = "2026-11-28T01:00:00Z", coverage = ChiefOfStaffCoverage(),
                topics = listOf(ChiefOfStaffTopic("t1", "Release Readiness", "project", listOf("Ship"), "Tracks release blockers")),
                evidence = listOf(ChiefOfStaffEvidence("e1", "t1", "task", taskId, sourceTitle = "Checklist task", explanation = "Primary release checklist")),
            ),
            ontologyVersionId = "v1",
            corrections = ChiefOfStaffCorrections(ws, ownerNpub, appNpub, ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2), 2, emptyList(), "2026-11-28T01:00:00Z"),
            correctionsFileId = "f1",
            correctionsVersionId = "v1",
        )
        var openedEvidence: ChiefOfStaffEvidence? = null
        val submitted = mutableListOf<ChiefOfStaffCorrection>()

        compose.setContent {
            AmbulandoTheme {
                ChiefOfStaffTopicsContent(
                    workspaceLabel = "Studio",
                    unlocked = true,
                    loading = false,
                    assignment = sampleAssignment,
                    cohort = cohort,
                    error = null,
                    searchQuery = "",
                    showArchived = false,
                    selectedTopicId = "t1",
                    savingCorrection = false,
                    correctionNotice = null,
                    viewerNpub = ownerNpub,
                    onSearchChange = {},
                    onShowArchivedChange = {},
                    onSelectTopic = {},
                    onRefresh = {},
                    onOpenDm = {},
                    onOpenEvidence = { openedEvidence = it },
                    onSubmitCorrection = { submitted += it },
                )
            }
        }
        compose.onNodeWithTag("chiefOfStaff.topicDetail").assertIsDisplayed()
        compose.onNodeWithTag("chiefOfStaff.evidence.drill.$taskId").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(taskId, openedEvidence?.recordId)

        compose.onNodeWithTag("chiefOfStaff.evidence.reject.$taskId").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("reject_evidence", submitted.last().kind)
        assertEquals(taskId, submitted.last().recordId)

        compose.onNodeWithTag("chiefOfStaff.action.rename").performScrollTo().performClick()
        compose.onNodeWithTag("chiefOfStaff.rename.input").performTextInput(" v2")
        compose.onNodeWithTag("chiefOfStaff.rename.confirm").performClick()
        compose.waitForIdle()
        assertEquals("rename", submitted.last().kind)
    }

    @Test fun routeStateRoundTripsChiefOfStaffWithinWorkspaceBoundary() {
        val encoded = RouteState.encode("home", mapOf("home" to listOf(WorkRoute.ChiefOfStaff(ws))))
        val restored = RouteState.decode(encoded, ws)!!
        assertEquals(listOf(WorkRoute.ChiefOfStaff(ws)), restored.stacks["home"])
        assertEquals(emptyList<WorkRoute>(), RouteState.decode(encoded, "22222222-2222-4222-8222-222222222222")!!.stacks["home"])
    }
}
