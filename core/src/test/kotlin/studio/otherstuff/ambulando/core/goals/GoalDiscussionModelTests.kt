package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalDiscussionModelTests {
    private val channel = "11111111-1111-4111-8111-111111111111"
    private val otherChannel = "22222222-2222-4222-8222-222222222222"
    private val doc = GoalLink(GoalLinkType.DOC, "33333333-3333-4333-8333-333333333333")
    private val file = GoalLink(GoalLinkType.FILE, "44444444-4444-4444-8444-444444444444")
    private fun base() = Goal.create("Purpose", "Make progress")
    private fun parse(value: JsonObject) = Goal.parse(value.toString().toByteArray())
    private fun invalid(block: () -> Unit) = assertThrows(GoalError.Invalid::class.java) { block() }

    @Test fun oldGoalsReadWithoutDiscussionFields() {
        val old = JsonObject(base().toJson().filterKeys { it !in setOf("default_channel_id", "pinned_refs") })
        assertEquals(base(), parse(old))
        assertNull(parse(old).defaultChannelId)
        assertTrue(parse(old).pinnedRefs.isEmpty())
    }

    @Test fun additiveFieldsRoundTripWithUnknownDataAndNativeReferences() {
        val pin = file.copy(channelId = channel, extra = buildJsonObject { put("future_ref", "retained") })
        val goal = base().copy(
            defaultChannelId = channel, pinnedRefs = listOf(doc, pin), links = listOf(file),
            extra = buildJsonObject { put("future_goal", buildJsonObject { put("key", 7) }) },
        ).validated()
        assertEquals(goal, Goal.parse(goal.encode().toByteArray()))
        assertEquals(1, goal.toJson()["schema_version"]!!.jsonPrimitive.int)
        assertEquals(channel, goal.toJson()["default_channel_id"]!!.jsonPrimitive.content)
        assertEquals(2, goal.toJson()["pinned_refs"]!!.jsonArray.size)
        assertEquals(listOf(file), GoalEdit.Unpin(GoalLinkType.FILE, file.recordId).apply(goal).links)
    }

    @Test fun malformedDefaultsAndPinnedReferencesAreRefused() {
        listOf<JsonElement>(JsonPrimitive(123), JsonPrimitive(""), JsonPrimitive("not-a-uuid"), JsonArray(emptyList()))
            .forEach { value -> invalid { parse(JsonObject(base().toJson() + ("default_channel_id" to value))) } }
        listOf<JsonElement>(JsonPrimitive("bad"), JsonObject(emptyMap()), buildJsonArray { add(JsonNull) },
            buildJsonArray { add(doc.copy(recordId = "bad").toJson()) },
            buildJsonArray { add(GoalLink(GoalLinkType.CHAT, channel).toJson()) },
            buildJsonArray { add(GoalLink(GoalLinkType.TASK, channel).toJson()) },
            buildJsonArray { add(doc.toJson()); add(doc.toJson()) })
            .forEach { value -> invalid { parse(JsonObject(base().toJson() + ("pinned_refs" to value))) } }
        invalid { base().copy(defaultChannelId = "invalid").validated() }
        invalid { base().copy(pinnedRefs = List(101) { file.copy(recordId = "%08x-4444-4444-8444-444444444444".format(it)) }).validated() }
        invalid { GoalEdit.Pin(doc.copy(channelId = "bad")).apply(base()) }
    }

    @Test fun nullOptionalFieldsAreLegacyAbsence() {
        assertEquals(base(), parse(JsonObject(base().toJson() + mapOf("default_channel_id" to JsonNull, "pinned_refs" to JsonNull))))
    }

    @Test fun defaultChannelEditDetectsAConflictingConcurrentChoice() {
        val elsewhere = GoalEdit.SetDefaultChannel(null, channel).apply(base())
        assertThrows(GoalError.Conflict::class.java) { GoalEdit.SetDefaultChannel(null, otherChannel).apply(elsewhere) }
        assertEquals(elsewhere, GoalEdit.SetDefaultChannel(null, channel).apply(elsewhere))
        assertNull(GoalEdit.SetDefaultChannel(channel, null).apply(elsewhere).defaultChannelId)
        invalid { GoalEdit.SetDefaultChannel(null, "bad").apply(base()) }
    }

    @Test fun independentPinsDefaultAndExistingEditsSurviveRebase() {
        val elsewhere = GoalEdit.applyAll(base(), listOf(GoalEdit.Pin(doc), GoalEdit.SetDescription("Make progress", "Shared progress")))
        val merged = GoalEdit.applyAll(elsewhere, listOf(GoalEdit.SetDefaultChannel(null, channel), GoalEdit.Pin(file)))
        assertEquals(listOf(doc, file), merged.pinnedRefs)
        assertEquals(channel, merged.defaultChannelId)
        assertEquals("Shared progress", merged.description)
        assertEquals(merged, GoalEdit.Pin(file).apply(merged))
        val removed = GoalEdit.Unpin(doc.type, doc.recordId).apply(merged)
        assertEquals(listOf(file), removed.pinnedRefs)
        assertEquals(removed, GoalEdit.Unpin(doc.type, doc.recordId).apply(removed))
    }

    @Test fun pinsAndAssociatedWorkAreIndependent() {
        val associated = GoalEdit.Link(file).apply(base())
        val pinned = GoalEdit.Pin(file).apply(associated)
        assertEquals(listOf(file), GoalEdit.Unlink(file.type, file.recordId).apply(pinned).pinnedRefs)
        assertEquals(associated, GoalEdit.Unpin(file.type, file.recordId).apply(pinned))
        invalid { GoalEdit.Pin(GoalLink(GoalLinkType.CHAT, channel)).apply(base()) }
        invalid { GoalEdit.Pin(GoalLink(GoalLinkType.TASK, channel)).apply(base()) }
    }

    @Test fun archivedGoalsRefuseDiscussionPreferenceAndPinChanges() {
        val archived = base().copy(status = GoalStatus.ARCHIVED, pinnedRefs = listOf(file))
        listOf(GoalEdit.SetDefaultChannel(null, channel), GoalEdit.Pin(doc), GoalEdit.Unpin(file.type, file.recordId))
            .forEach { edit -> assertThrows(GoalError.Conflict::class.java) { edit.apply(archived) } }
    }
}
