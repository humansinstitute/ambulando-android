package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys

class GoalMentionTests {
    @Test fun browseAllAndCompleteMultiwordNameWithCanonicalFileIdentity() = runBlocking {
        val h = SampleHarness(); val w = SampleIds.STUDIO
        val space = h.api.createGoalsSpace(w, h.app, TestKeys.NPUB, null, emptyList())
        val title = "Training sign up onboarding to Ambulando"
        val target = h.api.createGoal(w, h.app, space.id, Goal.create(title, "Train"))
        val goals = (0 until 14).map { h.api.createGoal(w, h.app, space.id, Goal.create("Goal $it", "Aim")) } + target
        assertTrue(GoalMentions.isQuery(" GoAl: "))
        assertFalse(GoalMentions.isQuery("goalkeeper"))
        assertEquals(15, GoalMentions.candidates(goals, "goal:").size)
        val picked = GoalMentions.candidates(goals, "tRaInInG sign up").single().mention
        assertEquals(MentionType.FILE, picked.type)
        assertEquals(target.id, picked.id)
        val input = "Ask @Training sign up onboarding to Amb"
        assertNotNull(MentionCodec.activeQuery(input))
        val completed = MentionCodec.complete(input, picked)
        assertEquals("Ask @$title ", completed)
        val encoded = MentionCodec.encode(completed.trim(), listOf(picked))
        assertEquals("Ask @[$title](mention:file:${target.id})", encoded.body)
        assertTrue(encoded.mentions.isEmpty())
        assertEquals(BodySegment.Reference(title, "file", target.id), MentionCodec.segments(encoded.body).last())
        assertEquals(encoded.body, MentionCodec.decode(encoded.body).text)
        val renamed = h.api.editGoal(w, h.app, target.id, listOf(GoalEdit.SetName(title, "Join Ambulando training")))
        assertEquals(target.id, renamed.id)
        assertEquals("Join Ambulando training", h.api.readGoal(w, h.app, picked.id).goal.name)
        assertNull(MentionCodec.activeQuery("@goal:line\nbreak"))
        assertNull(MentionCodec.activeQuery("@" + "x".repeat(161)))
        assertNotNull(MentionCodec.activeQuery("@" + "x".repeat(160)))
        assertTrue(GoalMentions.candidates(goals, "goal:missing").isEmpty())
        assertTrue(GoalMentions.candidates(listOf(target.copy(file = target.file.copy(accessRemoved = true))), "goal:").isEmpty())
    }

    @Test fun discoveryReadsOwnerSpaceAndEveryFilePage() = runBlocking {
        val h = SampleHarness(); val w = SampleIds.STUDIO
        val space = h.api.createGoalsSpace(w, h.app, TestKeys.NPUB, null, emptyList())
        h.api.createGoal(w, h.app, space.id, Goal.create("First", "Aim"))
        val resolved = h.api.goalsSpace(w, h.app, TestKeys.NPUB) as GoalsSpaceState.Ready
        assertEquals(space.id, resolved.channel.id)
        assertTrue(resolved.ownerOnly)
        val rows = listOf(h.api.goalFiles(w, h.app, space.id).single()) + (1 until 205).map {
            h.api.createGoal(w, h.app, space.id, Goal.create("Goal $it", "Aim")).file
        }
        // The current server's capped/no-cursor response must fail, never look complete.
        assertThrows(TowerError.ListLimitReached::class.java) { runBlocking { h.api.goalFiles(w, h.app, space.id) } }
        val requests = mutableListOf<String>()
        val paginated = TowerAPI(TowerClient(h.tower.origin, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                requests += request.url
                val second = "cursor=page2" in request.url
                return HttpResponse(200, buildJsonObject {
                    put("files", JsonArray((if (second) rows.drop(200) else rows.take(200)).map { it.raw }))
                    put("next_cursor", if (second) JsonNull else JsonPrimitive("page2"))
                }.toString().toByteArray())
            }
        }, SampleSigners.primary))
        assertEquals(205, paginated.goalFiles(w, h.app, space.id).size)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("cursor=page2"))
        assertTrue(h.api.goalsSpace(w, h.app, SampleSigners.otherNpub) is GoalsSpaceState.NeedsSetup)
    }
}
