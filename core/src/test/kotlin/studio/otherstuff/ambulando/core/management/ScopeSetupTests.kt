package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.io.IOException

/** Staged scope setup against the synthetic Tower: progress is kept, interruptions recover, nothing is created twice. */
class ScopeSetupTests {
    private val template = """{"scope":{"description":"Product scope for ${'$'}title. Code: ${'$'}workingdir."},"channels":[
        {"name":"features","description":"Features of ${'$'}title","basePrompt":"Dir ${'$'}workingdir"},
        {"name":"bugs","description":"Bugs of ${'$'}title","basePrompt":"Dir ${'$'}workingdir"},
        {"name":"refactoring","description":"Refactoring","basePrompt":"Clean ${'$'}title"}]}"""

    private class Rig {
        val tower = SampleTower(tempDir(), originUrl = "https://one.sample.invalid").also { it.prepare(TestKeys.NPUB) }
        /** Fail this request before it is sent. */
        @Volatile var failBefore: (HttpRequest) -> Boolean = { false }
        /** Let Tower commit this request, then lose the answer. */
        @Volatile var loseAfter: (HttpRequest) -> Boolean = { false }
        val posts = java.util.Collections.synchronizedList(ArrayList<String>())
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (request.method == "POST") posts += request.url.substringAfter("workspaces/")
                if (failBefore(request)) throw IOException("The network is unreachable.")
                val response = tower.send(request)
                if (loseAfter(request)) throw IOException("The connection was lost.")
                return response
            }
        }
        val identity = runBlocking { makeIdentity() }
        val store = LocalStore(tempDir())
        val connection = ConnectionRecord.create(TowerOrigin("https://one.sample.invalid"), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord.parse(toJsonElement(mapOf(
            "identity" to mapOf("workspace_id" to SampleIds.STUDIO, "app_npub" to SampleIds.APP, "workspace_owner_npub" to SampleIds.ANA),
            "label" to "Studio", "membership" to mapOf("role" to "member"),
        )) as JsonObject)
        val manager = ScopeChannelManager(store, identity, http)
        val setup = ScopeSetup(manager, store, connection, workspace)
        suspend fun scopes() = TowerAPI(TowerClient(TowerOrigin("https://one.sample.invalid"), http, identity)).scopes(workspace.id, workspace.appNpub)
        suspend fun channels(scope: String) = TowerAPI(TowerClient(TowerOrigin("https://one.sample.invalid"), http, identity)).channels(workspace.id, scope, workspace.appNpub)
    }

    private fun draft() = mutableMapOf("name" to "Orbit", "template" to "product", "directory" to "/work/orbit")

    @Test fun templateRendersTitleAndDirectoryAndValidatesLikeTheIphone() {
        val t = ScopeSetupTemplate.load(template, true, "Orbit", "/work/orbit")
        assertEquals("Product scope for Orbit. Code: /work/orbit.", t.description)
        assertEquals(listOf("features", "bugs", "refactoring"), t.channels.map { it.name })
        assertEquals("Dir /work/orbit", t.channels[0].prompt)
        assertTrue(ScopeSetupTemplate.load(null, false, "Blank", "").channels.isEmpty())
        assertEquals("Scope name is required.", (runCatching { ScopeSetupTemplate.load(template, true, " ", "x") }.exceptionOrNull() as ManagementError.Invalid).message)
        assertEquals("The product template requires a working directory.", (runCatching { ScopeSetupTemplate.load(template, true, "A", " ") }.exceptionOrNull() as ManagementError.Invalid).message)
        assertTrue(runCatching { ScopeSetupTemplate.load("not json", true, "A", "b") }.exceptionOrNull() is ManagementError.Unexpected)
    }

    @Test fun setupCreatesTheScopeThenEachChannelAndSavesProgress() = runBlocking {
        val rig = Rig(); val d = draft()
        rig.setup.execute(d, template)
        val scope = rig.scopes().single { it.name == "Orbit" }
        assertEquals(scope.id, d["scope"]); assertEquals("refactoring", d["lastChannel"])
        assertEquals(listOf("features", "bugs", "refactoring"), rig.channels(scope.id).map { it.name })
        assertEquals(d, rig.setup.load())
        assertEquals("Product scope for Orbit. Code: /work/orbit.", scope.description)
    }

    @Test fun anInterruptedChannelCreateResumesWithoutDuplicatesAndKeepsExistingChannels() = runBlocking {
        val rig = Rig(); val d = draft()
        var channelPosts = 0
        rig.failBefore = { it.method == "POST" && it.url.endsWith("/channels") && ++channelPosts == 2 }
        try { rig.setup.execute(d, template); fail("expected an interruption") } catch (_: ManagementError.Offline) {}
        val scope = d["scope"]!!
        assertEquals("the scope id was saved the moment Tower returned it", scope, rig.setup.load()["scope"])
        assertEquals(listOf("features"), rig.channels(scope).map { it.name })
        // Someone adds a channel by hand meanwhile; setup keeps it as it is and adds only what is missing.
        rig.manager.createChannel(rig.connection, rig.workspace, scope, "bugs", "My own bugs channel", "mine")
        rig.failBefore = { false }
        rig.setup.execute(rig.setup.load(), template)
        assertEquals(1, rig.scopes().count { it.name == "Orbit" })
        val names = rig.channels(scope)
        assertEquals(listOf("features", "bugs", "refactoring"), names.map { it.name })
        assertEquals("the pre-existing channel was not recreated or rewritten", "mine", names.first { it.name == "bugs" }.contextPrompt)
        // Running it again changes nothing.
        val before = rig.posts.size
        rig.setup.execute(rig.setup.load(), template)
        assertEquals(before, rig.posts.size)
    }

    @Test fun aLostScopeCreateReplyIsNeverRepeatedBlindlyAndCanBeAdoptedByName() = runBlocking {
        val rig = Rig(); val d = draft()
        rig.loseAfter = { it.method == "POST" && it.url.endsWith("/scopes") }
        try { rig.setup.execute(d, template); fail("expected a lost reply") } catch (_: ManagementError) {}
        rig.loseAfter = { false }
        assertNull(d["scope"]); assertEquals("true", d["attempted"])
        assertEquals("Tower did commit it", 1, rig.scopes().count { it.name == "Orbit" })
        val again = runCatching { rig.setup.execute(rig.setup.load(), template) }.exceptionOrNull()
        assertTrue(again is ManagementError.Conflict)
        assertEquals("no second scope was created", 1, rig.scopes().count { it.name == "Orbit" })
        // Recovery: Tower's list is checked by case-insensitive name and the scope adopted; setup then adds the channels.
        val reloaded = rig.setup.load()
        reloaded["name"] = "orbit"
        val found = rig.setup.recover(reloaded)
        assertNotNull(found)
        rig.setup.adopt(reloaded, found!!.first)
        rig.setup.execute(reloaded, template)
        assertEquals(1, rig.scopes().count { it.name == "Orbit" })
        assertEquals(3, rig.channels(found.first).size)
    }

    @Test fun recoveringWhenTowerHasNoSuchScopeClearsTheAttemptSoItCanBeTriedAgainExplicitly() = runBlocking {
        val rig = Rig(); val d = draft(); d["attempted"] = "true"
        assertNull(rig.setup.recover(d))
        assertFalse(d.containsKey("attempted"))
        rig.setup.execute(d, template)
        assertEquals(1, rig.scopes().count { it.name == "Orbit" })
    }

    @Test fun aMemberWithoutScopeCreateAuthorityCreatesNothingAndAClosedWorkspaceStopsTheNextStep() = runBlocking {
        val rig = Rig()
        // An unusable template is refused before any request.
        assertTrue(runCatching { rig.setup.execute(mutableMapOf("name" to "X", "template" to "product", "directory" to ""), template) }.exceptionOrNull() is ManagementError.Invalid)
        assertTrue(rig.posts.isEmpty())
        var current = true
        val guarded = ScopeSetup(rig.manager, rig.store, rig.connection, rig.workspace) { current }
        current = false
        assertTrue(runCatching { guarded.execute(draft(), template) }.exceptionOrNull() is AdministrationSuperseded)
        assertTrue("no write after the workspace in front changed", rig.posts.isEmpty())
    }
}
