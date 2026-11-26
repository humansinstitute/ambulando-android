package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.FileRecord

/** The shared focus-order fixture (also asserted by the Mac app's GoalFocusTests and scripts/goals-lib.test.mjs). */
class GoalFocusTests {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/goals/focus-order-v1.json")!!.readText()).jsonObject
    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun docs(items: JsonElement) = items.jsonArray.map { item ->
        val o = item.jsonObject; val id = str(o["id"])!!
        val goal = Goal.parse(o["goal"]!!.toString().toByteArray())
        GoalDocument(FileRecord("11111111-1111-4111-8111-111111111111", id, null, "22222222-2222-4222-8222-222222222222", null, null, "g.json", contentType = Goal.MIME, sizeBytes = null, sha256 = null, createdAt = "", updatedAt = ""), "v", goal)
    }
    private fun withRank(raw: JsonElement) = Goal.parse(buildJsonObject {
        put("schema", "ambulando.goal"); put("schema_version", 1); put("name", "n"); put("description", "d"); put("status", "active")
        put("checkpoints", JsonArray(emptyList())); put("links", JsonArray(emptyList())); put("focus_rank", raw)
    }.toString().toByteArray())

    @Test fun alphabetAndBounds() {
        assertEquals(str(fixture["digits"]), GoalFocus.DIGITS)
        assertEquals(fixture["max_length"]!!.jsonPrimitive.int, GoalFocus.MAX_LENGTH)
    }

    @Test fun validAndInvalidRanks() {
        fixture["valid"]!!.jsonArray.forEach { v -> assertTrue(v.toString(), GoalFocus.isValid(str(v)!!)); assertEquals(str(v), GoalFocus.rank(withRank(v))) }
        fixture["invalid"]!!.jsonArray.forEach { v ->
            val goal = withRank(v)
            assertNull("$v reads as unranked", GoalFocus.rank(goal))
            assertEquals("$v is kept verbatim", v, Goal.parse(goal.encode().toByteArray()).extra["focus_rank"] ?: JsonNull)
        }
    }

    @Test fun keysBetween() {
        fixture["between"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            assertEquals(c.toString(), str(o["key"]), GoalFocus.keyBetween(str(o["a"]), str(o["b"])))
        }
        fixture["between_errors"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            assertThrows(c.toString(), GoalError.Invalid::class.java) { GoalFocus.keyBetween(str(o["a"]), str(o["b"])) }
        }
    }

    @Test fun evenKeysForRebalance() {
        fixture["even_keys"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            assertEquals(o["keys"]!!.jsonArray.map { str(it) }, GoalFocus.evenKeys(o["n"]!!.jsonPrimitive.int))
        }
    }

    @Test fun oneOrderOnEveryClient() {
        val order = fixture["order"]!!.jsonObject
        assertEquals(order["expected"]!!.jsonArray.map { str(it) }, GoalFocus.order(docs(order["items"]!!).shuffled(java.util.Random(7))).map { it.id })
    }

    @Test fun movePlans() {
        fixture["moves"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            val writes = GoalFocus.planMove(docs(o["items"]!!), str(o["id"])!!, o["to_index"]!!.jsonPrimitive.int)
            val expected = o["writes"]!!.jsonArray.map { w -> w.jsonObject.let { GoalFocus.Write(str(it["id"])!!, str(it["expected"]), str(it["value"])!!) } }
            assertEquals(str(o["name"]), expected, writes)
        }
    }

    @Test fun canonicalBytesAndConflicts() {
        val c = fixture["canonical"]!!.jsonObject
        val before = Goal.parse(str(c["before"])!!.toByteArray())
        for (step in listOf("set", "move", "clear")) {
            val o = c[step]!!.jsonObject
            val start = if (step == "set") before else GoalFocus.setting(before, "V")
            assertEquals(step, str(o["after"]), GoalEdit.SetFocusRank(str(o["expected"]), str(o["value"])).apply(start).encode())
        }
        val kept = c["invalid_kept"]!!.jsonObject
        val invalid = Goal.parse(str(kept["bytes"])!!.toByteArray())
        assertEquals(str(kept["round_trip"]), invalid.encode())
        assertNull(GoalFocus.rank(invalid))
        assertEquals(str(kept["replaced"]), GoalEdit.SetFocusRank(null, "V").apply(invalid).encode())
        val stale = c["stale"]!!.jsonObject
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetFocusRank(str(stale["expected"]), str(stale["value"])).apply(Goal.parse(str(stale["bytes"])!!.toByteArray())) }
        val bad = c["invalid_value"]!!.jsonObject
        assertThrows(GoalError.Invalid::class.java) { GoalEdit.SetFocusRank(str(bad["expected"]), str(bad["value"])).apply(Goal.parse(str(bad["bytes"])!!.toByteArray())) }
    }

    @Test fun alreadyInEffectAcceptedAndArchivedRefused() {
        val ranked = GoalFocus.setting(Goal.create("a", "b"), "V")
        assertSame(ranked, GoalEdit.SetFocusRank("k", "V").apply(ranked))
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetFocusRank(null, "k").apply(ranked.copy(status = GoalStatus.ARCHIVED)) }
    }
}
