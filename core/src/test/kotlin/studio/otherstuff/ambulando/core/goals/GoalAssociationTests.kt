package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalAssociationTests {
    private val goal = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"

    @Test fun absentAssociationAndNewReferencePreserveOtherApplicationMetadata() {
        val base = buildJsonObject {
            put("source_thread_id", other)
            put("another_app", buildJsonObject { put("version", 9); put("enabled", false) })
        }
        assertNull(GoalAssociation.read(base))
        val saved = GoalAssociation.metadata(goal, base)
        assertEquals(goal, GoalAssociation.read(saved))
        assertEquals(base, JsonObject(saved.filterKeys { it != GoalAssociation.KEY }))
        assertFalse("associations contain references rather than copied goal content", saved.getValue(GoalAssociation.KEY).jsonObject.containsKey("name"))
    }

    @Test fun repeatedAssociationPreservesForwardFieldsAndCannotReassignThePrimaryGoal() {
        val base = buildJsonObject {
            put("unrelated", "kept")
            put(GoalAssociation.KEY, buildJsonObject {
                put("version", 1); put("goal_file_id", goal)
                put("future_provenance", buildJsonObject { put("source", "writer-v2") })
            })
        }
        assertEquals(base, GoalAssociation.metadata(goal, base))
        assertThrows(IllegalArgumentException::class.java) { GoalAssociation.metadata(other, base) }
        assertEquals(goal, GoalAssociation.read(base))
    }

    @Test fun malformedAndUnsupportedMetadataIsRefusedInsteadOfSilentlyReplaced() {
        val values = listOf<JsonElement>(
            JsonNull, JsonPrimitive("goal"), JsonArray(emptyList()),
            buildJsonObject { put("version", 2); put("goal_file_id", goal) },
            buildJsonObject { put("version", "1"); put("goal_file_id", goal) },
            buildJsonObject { put("goal_file_id", goal) },
            buildJsonObject { put("version", 1); put("goal_file_id", 7) },
            buildJsonObject { put("version", 1); put("goal_file_id", "not-a-file-id") },
            buildJsonObject { put("version", 1); put("goal_file_id", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA") },
        )
        values.forEach { value ->
            val base = buildJsonObject { put("another_app", "untouched"); put(GoalAssociation.KEY, value) }
            assertThrows("invalid value $value", Exception::class.java) { GoalAssociation.read(base) }
            assertThrows("invalid value must not be overwritten: $value", Exception::class.java) { GoalAssociation.metadata(goal, base) }
            assertEquals(value, base[GoalAssociation.KEY])
        }
        assertThrows(IllegalArgumentException::class.java) { GoalAssociation.metadata("not-a-uuid") }
    }
}
