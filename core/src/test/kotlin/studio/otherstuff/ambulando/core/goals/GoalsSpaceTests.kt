package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class GoalsSpaceTests {
    private val w = SampleIds.STUDIO
    private val me = TestKeys.NPUB

    /** The sample Tower, with channel grants answered from [grants] (it models no per-channel ACL of its own). */
    private class Harness(val grants: MutableList<JsonObject> = mutableListOf(), var denyGrantRead: Boolean = false, var denyCreate: Boolean = false) {
        val h = SampleHarness()
        val requests = mutableListOf<Pair<String, String>>()
        val api = TowerAPI(TowerClient(h.tower.origin, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                requests += request.method to path
                if (request.method == "GET" && path.endsWith("/grants") && "/channels/" in path)
                    return if (denyGrantRead) HttpResponse(403, "{}".toByteArray()) else HttpResponse(200, buildJsonObject { put("grants", JsonArray(grants)) }.toString().toByteArray())
                if (denyCreate && request.method == "POST" && path.endsWith("/channels") && "/scopes/" in path) return HttpResponse(403, """{"code":"permission_denied"}""".toByteArray())
                return h.tower.send(request)
            }
        }, SampleSigners.primary))
    }

    private fun grant(npub: String, kind: String, actor: String) = buildJsonObject {
        put("principal_type", "person"); put("access_level", "contribute")
        put("principal", buildJsonObject { put("type", "person"); put("actor_id", actor); put("npub", npub); put("kind", kind) })
    }

    @Test fun setupCreatesOwnerChannelWithExplicitAgentGrantsAndMetadata() = runBlocking {
        val t = Harness(); val api = t.api
        val state = api.goalsSpace(w, t.h.app, me) as GoalsSpaceState.NeedsSetup
        val scope = state.scopes.first()
        val agent = api.members(w, t.h.app).firstOrNull { it.isAgent }
        val channel = api.createGoalsSpace(w, t.h.app, me, scope.id, listOfNotNull(agent?.actorId))
        assertTrue(GoalsSpace.isOwnedBy(channel, me))
        assertEquals(GoalsSpace.SYSTEM_KEY, channel.raw.obj("metadata")!!.string("system_key"))
    }

    @Test fun nobodyBesidesOwnerAndAgentsMayBeInTheSpace() = runBlocking {
        val t = Harness(); val api = t.api
        val scope = (api.goalsSpace(w, t.h.app, me) as GoalsSpaceState.NeedsSetup).scopes.first()
        api.createGoalsSpace(w, t.h.app, me, scope.id, emptyList())
        t.grants += grant(me, "human", "00000000-0000-4000-8000-000000000001")
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.Ready)
        // A colleague (or a group) appears on the channel: it must not be used for private goals.
        t.grants += grant("npub1" + "q".repeat(58), "human", "00000000-0000-4000-8000-000000000002")
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.Unsafe)
        t.grants.clear(); t.grants += grant(me, "human", "00000000-0000-4000-8000-000000000001")
        t.grants += buildJsonObject { put("principal_type", "group"); put("principal", buildJsonObject { put("type", "group"); put("id", "g1") }) }
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.Unsafe)
    }

    @Test fun unreadableGrantsOrMissingOwnerFailClosed() = runBlocking {
        val t = Harness(); val api = t.api
        val scope = (api.goalsSpace(w, t.h.app, me) as GoalsSpaceState.NeedsSetup).scopes.first()
        api.createGoalsSpace(w, t.h.app, me, scope.id, emptyList())
        t.denyGrantRead = true
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.Unsafe)
        t.denyGrantRead = false   // no grant naming the owner at all
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.Unsafe)
    }

    @Test fun anotherPersonsSpaceIsNeverMine() = runBlocking {
        val t = Harness(); val api = t.api
        val scope = (api.goalsSpace(w, t.h.app, me) as GoalsSpaceState.NeedsSetup).scopes.first()
        api.createGoalsSpace(w, t.h.app, me, scope.id, emptyList())
        t.grants += grant(me, "human", "00000000-0000-4000-8000-000000000001")
        // Someone else resolves goals: the channel names me as its owner, so they are offered setup, not my space.
        assertTrue(api.goalsSpace(w, t.h.app, SampleSigners.otherNpub) is GoalsSpaceState.NeedsSetup)
    }

    @Test fun refusedChannelCreationIsReportedAndCreatesNothingElse() = runBlocking {
        val t = Harness(denyCreate = true); val api = t.api
        val scope = (api.goalsSpace(w, t.h.app, me) as GoalsSpaceState.NeedsSetup).scopes.first()
        val failure = runCatching { api.createGoalsSpace(w, t.h.app, me, scope.id, emptyList()) }.exceptionOrNull()
        assertTrue(failure is TowerError.Http && failure.status == 403)
        assertTrue(api.goalsSpace(w, t.h.app, me) is GoalsSpaceState.NeedsSetup)
    }

    @Test fun suggestedScopeIsOnlyAnExactlyNamedSingleHome() {
        fun scope(name: String, id: String) = ScopeRecord(w, id, name, "project", updatedAt = "x", raw = JsonObject(emptyMap()))
        assertEquals("a", GoalsSpace.suggestedScope(listOf(scope("Home", "a"), scope("Features", "b")))!!.id)
        assertNull(GoalsSpace.suggestedScope(listOf(scope("Home", "a"), scope("Home", "b"))))
        assertNull(GoalsSpace.suggestedScope(listOf(scope("home", "a"), scope("Homes", "b"))))
    }
}
