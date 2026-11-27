package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

class GoalsLocalContentTest {
    @Test fun emptyVerifiedOwnerOnlyLocationSurvivesNewController() = runBlocking {
        val directory=Files.createTempDirectory("goals-location").toFile()
        try { LocalStore(directory).use { store ->
            val h=SampleHarness(); val w=SampleIds.STUDIO; val local=GoalsLocalContent(store,w,TestKeys.NPUB)
            val first=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) {true}
            first.refresh(); first.setUp(null,emptyList())
            assertTrue((first.state.value.space as GoalsSpaceState.Ready).ownerOnly)
            val reopened=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) {true}
            reopened.refresh()
            assertTrue("A verified empty owner-only location must not return to setup",reopened.state.value.space is GoalsSpaceState.Ready)
        } } finally {directory.deleteRecursively()}
    }

    @Test fun durableGoalsRenderBeforeDelayedRefreshAndSurviveOffline() = runBlocking {
        val directory=Files.createTempDirectory("goals-content").toFile()
        val h=SampleHarness(); val w=SampleIds.STUDIO
        try {
            lateinit var id: String
            LocalStore(directory).use { store ->
                val local=GoalsLocalContent(store,w,TestKeys.NPUB)
                val c=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) { true }
                c.refresh(); c.setUp(null,emptyList())
                val goal=c.create(Goal.create("Durable goal","Progress")); id=goal.id
                c.edit(id,GoalEdit.AddCheckpoint(GoalCheckpoint("a","Checkpoint")),GoalEdit.SetReached("a",false,true),GoalEdit.Link(GoalLink(GoalLinkType.TASK,"11111111-1111-4111-8111-111111111111")))
            }
            LocalStore(directory).use { store ->
                val local=GoalsLocalContent(store,w,TestKeys.NPUB)
                val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
                h.tower.requestGate={ entered.complete(Unit); release.await() }
                val relaunched=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) { true }
                val job=launch { relaunched.refresh() }; entered.await()
                assertTrue(relaunched.state.value.loaded)
                assertEquals(1,relaunched.goal(id)!!.goal.reachedCount)
                assertEquals(1,relaunched.goal(id)!!.goal.links.size)
                job.cancelAndJoin(); h.tower.requestGate=null; h.tower.offline=true
                relaunched.refresh(); assertEquals("Durable goal",relaunched.goal(id)!!.goal.name)
                assertNotNull(relaunched.state.value.error)
                assertNull(GoalsLocalContent(store,w,"another-account").load())
                val channel=(relaunched.state.value.space as GoalsSpaceState.Ready).channel.id
                store.writer.markAccessRemoved(w,AccessTarget.Channel(channel))
                relaunched.refresh(); assertTrue(relaunched.state.value.goals.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun deniedSingleGoalDoesNotClearOtherGoals() = runBlocking {
        val directory=Files.createTempDirectory("goals-removal").toFile()
        try { LocalStore(directory).use { store ->
            val h=SampleHarness(); val w=SampleIds.STUDIO; val local=GoalsLocalContent(store,w,TestKeys.NPUB)
            val first=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) {true}
            first.refresh();first.setUp(null,emptyList())
            val missing=first.create(Goal.create("Missing","d")); val retained=first.create(Goal.create("Retained","d"))
            val wire=object: HttpTransport { override suspend fun send(request: HttpRequest): HttpResponse {
                if(java.net.URI(request.url).path.contains("/files/${missing.id}/")) return HttpResponse(404,"{}".toByteArray())
                return h.tower.send(request)
            } }
            val api=TowerAPI(TowerClient(h.client.origin,wire,h.client.signer))
            val reopened=GoalsController(api,w,h.app,TestKeys.NPUB,local) {true}
            reopened.refresh()
            assertEquals(listOf(retained.id),reopened.state.value.goals.map {it.id})
            assertEquals(listOf(retained.id),local.load()!!.goals.map {it.id})
        } } finally {directory.deleteRecursively()}
    }
    @Test fun hydrateNewSavedStateDoesNotWaitForOlderOnlineRefresh() = runBlocking {
        val directory=Files.createTempDirectory("goals-race").toFile()
        try { LocalStore(directory).use { store ->
            val h=SampleHarness(); val w=SampleIds.STUDIO; val local=GoalsLocalContent(store,w,TestKeys.NPUB)
            val c=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) {true}
            c.refresh();c.setUp(null,emptyList()); val a=c.create(Goal.create("A","d"))
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            h.tower.requestGate={ request -> if(java.net.URI(request.url).path.endsWith("/scopes")) {entered.complete(Unit);release.await()} }
            val job=launch {c.refresh()};entered.await()
            val saved=h.api.editGoal(w,h.app,a.id,listOf(GoalEdit.SetName("A","B")))
            local.save(c.state.value.copy(goals=listOf(saved)))
            withTimeout(1000) {c.hydrate()}
            assertEquals("B",c.goal(a.id)!!.goal.name);assertTrue(c.state.value.loading)
            h.tower.requestGate=null;release.complete(Unit);job.join()
            assertEquals("B",c.goal(a.id)!!.goal.name);assertFalse(c.state.value.loading)
        } } finally {directory.deleteRecursively()}
    }
    @Test fun corruptContentIsFirstLoadAndLockCannotHydrate() = runBlocking {
        val directory=Files.createTempDirectory("goals-corrupt").toFile()
        try { LocalStore(directory).use { store ->
            val h=SampleHarness(); val w=SampleIds.STUDIO
            store.writer.setState("goals.content.v1:${TestKeys.NPUB}:$w:","broken")
            val local=GoalsLocalContent(store,w,TestKeys.NPUB)
            assertNull(local.load())
            val locked=GoalsController(h.api,w,h.app,TestKeys.NPUB,local) { false }
            locked.refresh(); assertFalse(locked.state.value.loaded); assertTrue(locked.state.value.goals.isEmpty())
        } } finally { directory.deleteRecursively() }
    }
}
