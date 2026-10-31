package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.sample.SampleAdministration
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.sample.SampleViewer

/**
 * Manage Workspace on a dedicated emulator against the in-process synthetic Tower: the real screens, signer, store and
 * scheduler. Authority levels are the synthetic Tower's, never a real account's, and nothing here is evidence of live Tower
 * or physical-phone behaviour.
 */
@RunWith(AndroidJUnit4::class)
class AdministrationFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(viewer: SampleViewer, block: (AppModel, SampleTower) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            val model = app.activeModel!!
            val tower = model.sampleTower!!
            tower.administration.viewer = viewer
            tower.administration.clearRequests()
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            try { block(model, tower) } finally {
                tower.administration.viewer = SampleViewer.MEMBER
                tower.latencyMillis = 0
            }
        }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun click(t: String) { val n = tag(t); runCatching { n.performScrollTo() }; n.performClick() }
    private fun waitTag(t: String, timeout: Long = 30_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(t: String) = compose.onAllNodesWithTag(t).fetchSemanticsNodes().isEmpty()
    /** Waits for the form to finish loading its saved entries (a draft from an earlier run is restored), then replaces the text. */
    private fun type(t: String, text: String) {
        waitTag(t)
        compose.waitUntil(30_000) { runCatching { tag(t).assertIsEnabled() }.isSuccess }
        tag(t).performTextReplacement(text)
    }
    private fun saveAndConfirm() { click("admin.save"); waitTag("admin.confirm"); click("admin.confirm") }

    /** From the workspace's own menu in the sidebar, which names the exact workspace (two are enabled, so the sidebar is stacked). */
    private fun openManagement(label: String = "Sample Studio") {
        waitTag("context.bar")
        tag("context.bar").performClick()
        waitTag("workspace.options.$label")
        click("workspace.options.$label")
        waitTag("workspace.manage.$label")
        click("workspace.manage.$label")
        waitTag("admin.home", 60_000)
    }

    private fun open(section: AdminSection) { click(section.tag); }

    @Test fun anAdministratorAddsAMemberCreatesAGroupAndGrantsChannelAccessThroughTheScreens() = launch(SampleViewer.ADMIN) { model, tower ->
        openManagement()
        open(AdminSection.MEMBERS)
        compose.waitUntil(30_000) { runCatching { tag("admin.member.add").assertIsEnabled() }.isSuccess }
        click("admin.member.add")
        waitTag("admin.field.npub")
        val agent = NostrKeys.npub(NostrKeys.publicKeyHex(ByteArray(32) { (System.nanoTime() % 200 + 20).toByte() }))!!
        type("admin.field.npub", agent)
        click("admin.choice.kind.agent")
        saveAndConfirm()
        waitTag("admin.status.done")
        assertEquals(agent, tower.administration.writes().single { it.path == "/members" }.body["member_npub"])
        click("admin.back"); click("admin.back")

        open(AdminSection.GROUPS)
        waitTag("admin.group.new"); click("admin.group.new")
        waitTag("admin.field.name")
        val groupName = "Emulator ops ${System.nanoTime()}"
        type("admin.field.name", groupName)
        saveAndConfirm()
        waitTag("admin.status.done")
        assertEquals(groupName, tower.administration.writes().last { it.path == "/groups" }.body["name"])
        click("admin.back"); click("admin.back")

        open(AdminSection.PERMISSIONS)
        waitTag("admin.channelAccess.${SampleIds.DESIGN}")
        click("admin.channelAccess.${SampleIds.DESIGN}")
        waitTag("admin.channel.grant")
        compose.waitUntil(30_000) { runCatching { tag("admin.channel.grant").assertIsEnabled() }.isSuccess }
        click("admin.channel.grant")
        waitTag("admin.pick.principal"); click("admin.pick.principal")
        val ana = java.util.UUID.nameUUIDFromBytes(SampleIds.ANA.toByteArray()).toString()
        waitTag("admin.pick.option.$ana"); click("admin.pick.option.$ana")
        click("admin.choice.access.manage")
        saveAndConfirm()
        waitTag("admin.status.done")
        val grant = tower.administration.writes().last { it.path == "/channels/${SampleIds.DESIGN}/grants" }
        assertEquals("manage", grant.body["access_level"])
    }

    @Test fun anOrdinaryMemberSeesTheScreensReadOnlyAndNothingIsSent() = launch(SampleViewer.MEMBER) { _, tower ->
        openManagement()
        open(AdminSection.MEMBERS)
        waitTag("admin.authority.readOnly")
        tag("admin.member.add").assertIsNotEnabled()
        click("admin.back")
        open(AdminSection.APPS)
        waitTag("admin.authority.readOnly")
        tag("admin.delegation.new").assertIsNotEnabled()
        tag("admin.publishing.new").assertIsNotEnabled()
        assertTrue(tower.administration.writes().isEmpty())
    }

    @Test fun aFormInFlightWhenTheWorkspaceInFrontChangesSendsNothingAndKeepsItsEntries() = launch(SampleViewer.ADMIN) { model, tower ->
        openManagement()
        open(AdminSection.GROUPS)
        waitTag("admin.group.new"); click("admin.group.new")
        val typed = "Switching ops ${System.nanoTime()}"
        type("admin.field.name", typed)
        compose.waitUntil(30_000) { runCatching { tag("admin.save").assertIsEnabled() }.isSuccess }
        // The authority read behind the save is held at Tower's door, so the switch certainly happens before it is answered.
        val seen0 = 0
        val arrived = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        tower.requestGate = { r -> seen.add(r.method + " " + r.url.substringBefore('?').substringAfter("sample.invalid") + " @" + System.currentTimeMillis() % 100000); if (r.method == "GET" && r.url.substringBefore('?').endsWith("/workspaces/${SampleIds.STUDIO}/me") && !arrived.isCompleted) { arrived.complete(Unit); release.await() } }
        try {
            saveAndConfirm()
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(30_000) { arrived.await() } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.LAB }) }
            compose.waitUntil(30_000) { model.workspace?.id == SampleIds.LAB }
            release.complete(Unit)
            // The closed screen's request never leaves: nothing is created, and nothing is reported into the new screen.
            Thread.sleep(4_000)
        } finally { tower.requestGate = null; release.complete(Unit) }
        assertTrue("no group write after the switch: " + tower.administration.writes().map { it.method + " " + it.path } + " seen=" + synchronized(seen) { seen.toList() }, tower.administration.writes().none { it.path == "/groups" })
        assertTrue("no saved/failed status leaks into the new workspace's screen", gone("admin.status.done") && gone("admin.status.failed"))
        assertEquals("Sample Lab", model.workspace?.label)
        // Back in the first workspace the entries are still there, because they were kept before anything was sent.
        compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
        compose.waitUntil(30_000) { model.workspace?.id == SampleIds.STUDIO }
        runCatching { click("admin.back") }
        openManagement()
        open(AdminSection.GROUPS)
        waitTag("admin.group.new"); click("admin.group.new")
        waitTag("admin.field.name")
        compose.waitUntil(30_000) { runCatching { tag("admin.field.name").assertTextContains(typed) }.isSuccess }
    }

    @Test fun aDelegateReconcilesOnlyTheirOwnInstallationOnTheEmulator() = launch(SampleViewer.DELEGATE) { _, tower ->
        tower.administration.delegateGrantFor(SampleIds.STUDIO, SampleIds.ACTOR, studio.otherstuff.ambulando.core.management.AdministrationCommand.isoSeconds(java.time.Instant.now().plusSeconds(3600)), SampleAdministration.INSTALLATION_FEED)
        openManagement()
        open(AdminSection.APPS)
        waitTag("admin.installation.reconcile.${SampleAdministration.INSTALLATION_FEED}")
        waitTag("admin.installation.noauthority.${SampleAdministration.INSTALLATION_OTHER}")
        click("admin.installation.reconcile.${SampleAdministration.INSTALLATION_FEED}")
        waitTag("admin.status.done")
        assertEquals("/wapp-installations/${SampleAdministration.INSTALLATION_FEED}/reconcile", tower.administration.writes().last().path)
        tag("admin.delegation.new").assertIsNotEnabled()
    }

    @Test fun scopeSetupCreatesTheScopeThenOnlyTheTemplateChannelsAndAChannelCanBeSharedExplicitly() = launch(SampleViewer.ADMIN) { model, tower ->
        val name = "Emulator product ${System.currentTimeMillis()}"
        openManagement()
        open(AdminSection.SCOPES)
        waitTag("admin.scopeSetup.open"); click("admin.scopeSetup.open")
        waitTag("admin.scopeSetup.name")
        compose.waitUntil(30_000) { runCatching { tag("admin.scopeSetup.name").assertIsEnabled() }.isSuccess }
        tag("admin.scopeSetup.name").performTextInput(name)
        click("admin.scopeSetup.template.product")
        waitTag("admin.scopeSetup.directory")
        tag("admin.scopeSetup.directory").performTextInput("/work/emulator-product")
        click("admin.scopeSetup.go"); waitTag("admin.scopeSetup.confirm"); click("admin.scopeSetup.confirm")
        waitTag("admin.scopeSetup.status", 60_000)
        val created = model.scopes.first { it.name == name }
        compose.waitUntil(60_000) { model.channels.count { it.scopeId == created.id } == 4 }
        assertEquals(setOf("features", "implementation", "bugs", "refactoring"), model.channels.filter { it.scopeId == created.id }.map { it.name }.toSet())
        // Cleanup of the synthetic workspace: the scope this test made is archived again.
        kotlinx.coroutines.runBlocking {
            studio.otherstuff.ambulando.core.management.ScopeChannelManager(model.store, model.identity, model.workspaceTransport).archiveScope(model.connection!!, model.workspace!!, created.id)
        }
        assertTrue(tower.administration.writes().isEmpty())
    }

    @Test fun activationSignsOnlyToTheConfirmedOriginThroughTheRuntimesGuardedTransport() = launch(SampleViewer.ADMIN) { _, tower ->
        openManagement()
        open(AdminSection.APPS)
        waitTag("admin.activation.open")
        compose.waitUntil(30_000) { runCatching { tag("admin.activation.open").assertIsEnabled() }.isSuccess }
        click("admin.activation.open")
        type("admin.field.origin", " https://AUTOPILOT.sample.invalid/ ")
        compose.waitUntil(10_000) { runCatching { tag("admin.activation.target").assertTextContains("exactly https://autopilot.sample.invalid", substring = true) }.isSuccess }
        val calls = { tower.administration.requests().filter { it.path.startsWith("autopilot:") }.map { it.method + " " + it.path } }
        click("admin.activation.catalog"); waitTag("admin.activation.sign.cancel"); click("admin.activation.sign.cancel")
        assertTrue("nothing is signed before the origin is confirmed", calls().isEmpty())
        click("admin.activation.catalog"); waitTag("admin.activation.sign.confirm"); click("admin.activation.sign.confirm")
        waitTag("admin.status.done")
        assertEquals(listOf("GET autopilot:/api/wapps/activation-catalog"), calls())
        click("admin.pick.app"); waitTag("admin.pick.option.sample.feed"); click("admin.pick.option.sample.feed")
        click("admin.pick.channel"); waitTag("admin.pick.option.${SampleIds.GENERAL}"); click("admin.pick.option.${SampleIds.GENERAL}")
        click("admin.activation.activate"); waitTag("admin.activation.sign.confirm"); click("admin.activation.sign.confirm")
        waitTag("admin.activation.another", 60_000)
        assertEquals(listOf("GET autopilot:/api/wapps/activation-catalog", "POST autopilot:/api/wapps/install-intents/process"), calls())
        assertEquals(1, tower.administration.writes().count { it.path == "/wapp-install-intents" })
    }

    @Test fun aNewChannelIsSharedOnlyWithTheRecipientsTickedAndAtTheLevelChosen() = launch(SampleViewer.ADMIN) { model, tower ->
        openManagement()
        open(AdminSection.SCOPES)
        // Opening a workspace's management goes Home; the scope is chosen as the sidebar's "New channel" does before it opens this page.
        compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, null) }
        waitTag("management.createChannel")
        val name = "shared-${System.currentTimeMillis()}"
        compose.waitUntil(30_000) { runCatching { tag("management.createChannel").assertIsNotEnabled() }.isSuccess }
        val shareTag = androidx.compose.ui.test.SemanticsMatcher("a person to share with") { node ->
            (androidx.compose.ui.semantics.SemanticsProperties.TestTag in node.config) && node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("management.share.actor:")
        }
        compose.waitUntil(30_000) { compose.onAllNodes(shareTag).fetchSemanticsNodes().isNotEmpty() }
        // Nothing is ticked for the person: the list starts empty.
        val options = compose.onAllNodes(shareTag).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        for (option in options) compose.onNodeWithTag(option).assertIsOff()
        val chosen = options.first()
        val actorId = chosen.removePrefix("management.share.actor:")
        click(chosen)
        click("management.shareLevel.actor:$actorId")
        compose.onNodeWithText("Contribute").assertExists()
        // The first control of the form is the name field.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(name)
        compose.waitUntil(30_000) { runCatching { tag("management.createChannel").assertIsEnabled() }.isSuccess }
        click("management.createChannel")
        compose.waitUntil(60_000) { model.channels.any { it.name == name } }
        val created = model.channels.first { it.name == name }
        val grants = tower.administration.channelGrantsOf(SampleIds.STUDIO, created.id)
        assertEquals("only the ticked recipient, at the chosen level", listOf(Triple("actor", actorId, "contribute")), grants)
        // The synthetic workspace is left as found.
        kotlinx.coroutines.runBlocking {
            studio.otherstuff.ambulando.core.management.ScopeChannelManager(model.store, model.identity, model.workspaceTransport).archiveChannel(model.connection!!, model.workspace!!, created)
        }
    }
}
