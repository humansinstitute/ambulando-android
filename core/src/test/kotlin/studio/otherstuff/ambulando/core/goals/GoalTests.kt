package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalTests {
    private fun goal() = Goal.create("Ship Ambulando", "Use it every day", "2026-11-26", "2026-12-18")
    private fun cp(id: String, title: String = id) = GoalCheckpoint(id, title)
    private fun invalid(block: () -> Unit) = assertThrows(GoalError.Invalid::class.java) { block() }
    private val task = "11111111-1111-4111-8111-111111111111"
    private val chat = "22222222-2222-4222-8222-222222222222"

    @Test fun nameAndDescriptionAreRequired() {
        invalid { Goal.create(" ", "x") }
        invalid { Goal.create("x", "") }
    }

    @Test fun datesAreDateOnlyAndOrdered() {
        invalid { Goal.create("a", "b", "2026-12-18", "2026-11-26") }
        invalid { Goal.create("a", "b", "2026-13-01") }
        invalid { Goal.create("a", "b", "2026-12-18T00:00:00Z") }
        Goal.create("a", "b", null, "2026-12-18"); Goal.create("a", "b", "2026-12-18", "2026-12-18")
    }

    @Test fun roundTripKeepsUnknownFieldsFromNewerWriters() {
        val raw = goal().toJson().let { JsonObject(it + ("future" to JsonPrimitive("kept"))) }
        val parsed = Goal.parse(raw.toString().toByteArray())
        assertEquals("kept", parsed.toJson()["future"]!!.jsonPrimitive.content)
        assertEquals(goal(), parsed.copy(extra = JsonObject(emptyMap())))
    }

    @Test fun newerSchemaIsRefusedNotRewritten() {
        val raw = JsonObject(goal().toJson() + ("schema_version" to JsonPrimitive(2)))
        assertThrows(GoalError.Unsupported::class.java) { Goal.parse(raw.toString().toByteArray()) }
        invalid { Goal.parse("{}".toByteArray()) }
        invalid { Goal.parse("nope".toByteArray()) }
    }

    @Test fun progressIsCheckpointsReachedOnly() {
        var g = GoalEdit.applyAll(goal(), listOf(GoalEdit.AddCheckpoint(cp("a")), GoalEdit.AddCheckpoint(cp("b")), GoalEdit.AddCheckpoint(cp("c"))))
        g = GoalEdit.SetReached("a", false, true).apply(g)
        assertEquals(1, g.reachedCount); assertEquals("b", g.nextCheckpointId)
        g = GoalEdit.Link(GoalLink(GoalLinkType.TASK, task)).apply(g)
        assertEquals(1, g.reachedCount)
        assertNull(GoalEdit.SetStatus(GoalStatus.ACTIVE, GoalStatus.COMPLETED).apply(g).nextCheckpointId)
    }

    @Test fun checkpointsReorderAndInsert() {
        var g = GoalEdit.applyAll(goal(), listOf(GoalEdit.AddCheckpoint(cp("a")), GoalEdit.AddCheckpoint(cp("c")), GoalEdit.AddCheckpoint(cp("b"), afterId = "a")))
        assertEquals(listOf("a", "b", "c"), g.checkpoints.map { it.id })
        g = GoalEdit.MoveCheckpoint("c", 0).apply(g)
        assertEquals(listOf("c", "a", "b"), g.checkpoints.map { it.id })
        g = GoalEdit.MoveCheckpoint("c", 99).apply(g)
        assertEquals(listOf("a", "b", "c"), g.checkpoints.map { it.id })
        assertEquals(listOf("a", "c"), GoalEdit.RemoveCheckpoint("b").apply(g).checkpoints.map { it.id })
    }

    @Test fun staleScalarEditIsAConflictNotAnOverwrite() {
        val elsewhere = GoalEdit.SetName("Ship Ambulando", "Ship it").apply(goal())
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetName("Ship Ambulando", "Mine").apply(elsewhere) }
        // The same change already in effect is accepted.
        assertEquals(elsewhere, GoalEdit.SetName("Ship Ambulando", "Ship it").apply(elsewhere))
    }

    @Test fun independentEditsRebaseCleanly() {
        val base = GoalEdit.AddCheckpoint(cp("a")).apply(goal())
        val theirs = GoalEdit.SetReached("a", false, true).apply(base)
        val mine = GoalEdit.SetDescription("Use it every day", "Daily driver").apply(theirs)
        assertEquals("Daily driver", mine.description); assertTrue(mine.checkpoints.single().reached)
    }

    @Test fun editingARemovedCheckpointConflicts() {
        val base = GoalEdit.AddCheckpoint(cp("a")).apply(goal())
        val gone = GoalEdit.RemoveCheckpoint("a").apply(base)
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetReached("a", false, true).apply(gone) }
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.UpdateCheckpoint("a", cp("a"), cp("a", "New")).apply(gone) }
        assertEquals(gone, GoalEdit.RemoveCheckpoint("a").apply(gone))
    }

    @Test fun linksAreReferencesAndUnlinkLeavesOthers() {
        var g = GoalEdit.applyAll(goal(), listOf(GoalEdit.Link(GoalLink(GoalLinkType.TASK, task)), GoalEdit.Link(GoalLink(GoalLinkType.CHAT, chat, channelId = task))))
        g = GoalEdit.Link(GoalLink(GoalLinkType.TASK, task)).apply(g)
        assertEquals(2, g.links.size)
        g = GoalEdit.Unlink(GoalLinkType.TASK, task).apply(g)
        assertEquals(listOf(GoalLinkType.CHAT), g.links.map { it.type })
        assertEquals(g, GoalEdit.Unlink(GoalLinkType.TASK, task).apply(g))
        invalid { GoalEdit.Link(GoalLink(GoalLinkType.DOC, "not-a-uuid")).apply(g) }
    }

    @Test fun archivedGoalsAreReadOnlyUntilRestored() {
        val archived = GoalEdit.SetStatus(GoalStatus.ACTIVE, GoalStatus.ARCHIVED).apply(goal())
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.AddCheckpoint(cp("a")).apply(archived) }
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetName("Ship Ambulando", "x").apply(archived) }
        val restored = GoalEdit.SetStatus(GoalStatus.ARCHIVED, GoalStatus.ACTIVE).apply(archived)
        assertEquals(goal(), restored)
    }

    @Test fun completingKeepsCheckpointsAndLinks() {
        val g = GoalEdit.applyAll(goal(), listOf(GoalEdit.AddCheckpoint(cp("a")), GoalEdit.Link(GoalLink(GoalLinkType.TASK, task)), GoalEdit.SetStatus(GoalStatus.ACTIVE, GoalStatus.COMPLETED)))
        assertEquals(GoalStatus.COMPLETED, g.status); assertEquals(1, g.checkpoints.size); assertEquals(1, g.links.size)
    }

    @Test fun limitsAreEnforced() {
        invalid { Goal.create("x".repeat(121), "d") }
        invalid { goal().copy(checkpoints = List(101) { cp("c$it") }).validated() }
        invalid { goal().copy(checkpoints = listOf(cp("a"), cp("a"))).validated() }
        invalid { goal().copy(checkpoints = listOf(GoalCheckpoint("a", "t", date = "soon"))).validated() }
    }

    private fun parse(json: String) = Goal.parse(json.toByteArray())
    private val head = """"schema":"ambulando.goal","schema_version":1,"name":"n","description":"d","status":"active""""

    @Test fun wrongTypedKnownFieldsAreRefusedNotReadAsEmpty() {
        listOf(
            """{$head,"checkpoints":"oops"}""", """{$head,"links":{"a":1}}""", """{$head,"checkpoints":[{"id":"a","title":"t","reached":"yes"}]}""",
            """{$head,"checkpoints":[{"id":"a","title":7}]}""", """{$head,"links":[{"type":"task","record_id":5}]}""",
            """{$head,"start_date":20261201}""", """{"schema":"ambulando.goal","schema_version":1,"name":3,"description":"d","status":"active"}""",
        ).forEach { json -> invalid { parse(json) } }
    }

    @Test fun nullAndMissingOptionalFieldsAreAbsent() {
        val g = parse("""{$head,"start_date":null,"checkpoints":null,"links":[{"type":"task","record_id":"$task","channel_id":null}]}""")
        assertNull(g.startDate); assertTrue(g.checkpoints.isEmpty()); assertNull(g.links.single().channelId)
    }

    @Test fun sharedFixtureWrittenByAnotherClientRoundTripsEveryField() {
        val bytes = javaClass.getResourceAsStream("/goals/android-written-v1.json")!!.readBytes()
        val g = Goal.parse(bytes)
        assertEquals("Ship the beta 🚀", g.name); assertEquals(1, g.reachedCount); assertEquals(3, g.links.size)
        assertEquals("cp-2", g.nextCheckpointId)
        // Key order is not part of the contract; every field, including unknown ones and number forms, must survive.
        assertEquals(Json.parseToJsonElement(String(bytes, Charsets.UTF_8)), Json.parseToJsonElement(g.encode()))
    }
}

class GoalFormatTests {
    @Test fun datesAreDateOnlyAndStable() {
        assertEquals("18 Dec 2026", GoalFormat.date("2026-12-18", java.util.Locale.UK))
        assertEquals("5 Dec", GoalFormat.short("2026-12-05", java.time.LocalDate.of(2026, 11, 26), java.util.Locale.UK))
        assertEquals("5 Dec 2027", GoalFormat.short("2027-12-05", java.time.LocalDate.of(2026, 11, 26), java.util.Locale.UK))
        assertNull(GoalFormat.date(null)); assertNull(GoalFormat.date("junk"))
    }
    @Test fun progressIsCheckpointCountsOnly() {
        val g = Goal.create("a", "b").copy(checkpoints = listOf(GoalCheckpoint("a", "A", reached = true), GoalCheckpoint("b", "B"), GoalCheckpoint("c", "C")))
        assertEquals("1 of 3 checkpoints reached", GoalFormat.progress(g))
        assertEquals("No checkpoints yet", GoalFormat.progress(Goal.create("a", "b")))
    }
}
