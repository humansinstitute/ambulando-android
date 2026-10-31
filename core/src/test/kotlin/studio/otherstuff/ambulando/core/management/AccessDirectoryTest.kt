package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessDirectoryTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun groupsListMembersAndNestedGroups() {
        val group = AccessDirectory.group(obj("""{"group_id":"ABC","name":"Core team","kind":"custom","child_group_ids":["g2","g3"],
            "members":[{"actor":{"actor_id":"a1","npub":"npub1aaa","display_name":"Ana","kind":"human"}},{"npub":"npub1bbb","kind":"agent"}]}"""))!!
        assertEquals("abc", group.id)
        assertEquals(listOf("g2", "g3"), group.childGroupIds)
        assertEquals(listOf("Ana", "npub1bbb"), group.members.map { it.name })
        assertEquals("agent", group.members[1].kind)
    }

    @Test fun aGroupWithoutAnIdIsSkippedAndAnUnnamedOneIsLabelled() {
        assertNull(AccessDirectory.group(obj("""{"name":"No id"}""")))
        assertEquals("Untitled group", AccessDirectory.group(obj("""{"id":"g1"}"""))!!.name)
    }

    @Test fun grantsNameTheRecipientAndSortPermissions() {
        val flat = AccessDirectory.grant(obj("""{"principal_type":"actor","principal_id":"a1","display_name":"Ana","permissions":["write","read"]}"""))
        assertEquals(listOf("read", "write"), flat.permissions)
        assertEquals("Ana", flat.name)
        val nested = AccessDirectory.grant(obj("""{"principal_type":"person","principal":{"actor_id":"a2","name":"Bea","npub":"npub1bea"},"permission":"manage"}"""))
        assertEquals("actor", nested.principalType)
        assertEquals("Bea", nested.name)
        assertEquals(listOf("manage"), nested.permissions)
        assertEquals("Unknown", AccessDirectory.grant(obj("""{"permission":"read"}""")).name)
    }
}
