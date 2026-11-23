package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.*
import java.time.Instant

class AdministrationNamesTests {
    private val hex = "ab".repeat(32)
    private val npub = NostrKeys.npub(hex)!!
    private fun people(name: String) = PeopleDirectory(emptyList(), emptyMap(), listOf(
        ProfileRecord(hex, npub, displayName = name, status = ProfileRecord.Status.FOUND, checkedAt = Instant.EPOCH)))
    private fun member(title: String, identity: String = npub) = AdministrationRow.parse(buildJsonObject {
        put("actor_id", "actor-id"); put("npub", identity); put("display_name", title)
    })

    @Test fun profileProjectionPreservesStoredFieldsActorIdAndCustomLabels() {
        val raw = member(npub)
        val shown = raw.withPersonNames(people("Alice"))
        assertEquals("Alice", shown.title)
        assertEquals("actor-id", shown.id)
        assertEquals(raw.raw, shown.raw)
        assertEquals(raw.fields, shown.fields)
        assertEquals(npub, raw.title)
        assertEquals("My custom label", member("My custom label").withPersonNames(people("Alice")).title)
        assertEquals("Renamed", raw.withPersonNames(people("Renamed")).title)
        assertEquals(npub, raw.withPersonNames(PeopleDirectory()).title)
        assertEquals("Workspace Creator", member("Workspace Creator", npub.take(16)).withPersonNames(people("Alice")).title)
    }

    @Test fun groupPeopleResolveButGroupTitleAndRecordRemainUntouched() {
        val group = AdministrationRow.parse(buildJsonObject {
            put("group_id", "group-id"); put("name", "My group")
            put("members", JsonArray(listOf(member("Workspace Creator").raw)))
        })
        val shown = group.withPersonNames(people("Alice"))
        assertEquals("My group", shown.title)
        assertEquals("Alice", shown.children.single().title)
        assertEquals(group.raw, shown.raw)
        assertEquals(group.toJson(), shown.toJson())
    }
}
