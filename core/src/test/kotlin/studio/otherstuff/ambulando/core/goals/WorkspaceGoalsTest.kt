package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*

class WorkspaceGoalsTest {
    @Test fun combinedListReadsAcrossChannelsWithoutChangingPersonalListOrWorkspace() = runBlocking {
        val h = SampleHarness(); val w = SampleIds.STUDIO
        val personal = GoalsController(h.api, w, h.app, TestKeys.NPUB) { true }
        personal.refresh(); personal.setUp(null, emptyList())
        val own = personal.create(Goal.create("Personal", "One"))
        val channels = h.api.scopes(w,h.app).flatMap { h.api.channels(w,it.id,h.app) }
        val other = channels.first { it.id != own.file.channelId }
        val shared = h.api.createGoal(w,h.app,other.id,Goal.create("Shared channel goal", "Two"))
        personal.refresh()
        assertEquals(listOf(own.id), personal.state.value.goals.map { it.id })
        val all = GoalsController(h.api,w,h.app,TestKeys.NPUB,allAccessible = true) { true }
        all.refresh()
        assertNull(all.state.value.error)
        assertEquals(setOf(own.id, shared.id), all.state.value.goals.map { it.id }.toSet())
        assertTrue(all.state.value.goals.all { it.file.workspaceId == w })
        all.edit(shared.id, GoalEdit.SetName("Shared channel goal", "Edited shared goal"))
        assertEquals("Edited shared goal", h.api.readGoal(w,h.app,shared.id).goal.name)
        val restrictedAPI=TowerAPI(TowerClient(h.client.origin,object:HttpTransport {
            override suspend fun send(request:HttpRequest):HttpResponse {
                if(java.net.URI(request.url).path.endsWith("/channels/${other.id}/files")) return HttpResponse(403,"{}".toByteArray())
                return h.tower.send(request)
            }
        },SampleSigners.primary))
        val restricted=GoalsController(restrictedAPI,w,h.app,TestKeys.NPUB,allAccessible=true) { true }
        restricted.refresh()
        assertNull(restricted.state.value.error)
        assertEquals(listOf(own.id),restricted.state.value.goals.map { it.id })
    }
    @Test fun workspaceGoalsRemainVisibleBeforePersonalSetupAndUnsafeSpaceDoesNotEnableCreation() = runBlocking {
        val h=SampleHarness(); val w=SampleIds.STUDIO
        val scopes=h.api.scopes(w,h.app)
        val channel=h.api.channels(w,scopes.first { it.kind != "dm" }.id,h.app).first()
        val shared=h.api.createGoal(w,h.app,channel.id,Goal.create("Visible before setup","Shared"))
        val all=GoalsController(h.api,w,h.app,TestKeys.NPUB,allAccessible=true) { true }
        all.refresh()
        assertTrue(all.state.value.space is GoalsSpaceState.NeedsSetup)
        assertEquals(listOf(shared.id),all.state.value.goals.map { it.id })
        assertTrue(runCatching { all.create(Goal.create("Private","Blocked")) }.isFailure)
        h.api.createGoalsSpace(w,h.app,TestKeys.NPUB,scopes.first { it.kind != "dm" }.id,emptyList())
        val api=TowerAPI(TowerClient(h.client.origin,object:HttpTransport {
            override suspend fun send(request:HttpRequest):HttpResponse {
                if(java.net.URI(request.url).path.endsWith("/grants")) return HttpResponse(403,"{}".toByteArray())
                return h.tower.send(request)
            }
        },SampleSigners.primary))
        val unsafe=GoalsController(api,w,h.app,TestKeys.NPUB,allAccessible=true) { true }
        unsafe.refresh()
        assertNull(unsafe.state.value.error)
        assertTrue(unsafe.state.value.space is GoalsSpaceState.Unsafe)
        assertEquals(listOf(shared.id),unsafe.state.value.goals.map { it.id })
        assertTrue(runCatching { unsafe.create(Goal.create("Private","Blocked")) }.isFailure)
    }

}
