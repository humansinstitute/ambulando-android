package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.StoreFixtures
import studio.otherstuff.ambulando.core.store.enqueueSend
import studio.otherstuff.ambulando.core.store.replaceWorkspaces
import studio.otherstuff.ambulando.core.store.setState

class WorkspaceCatalogTests {
    private val w1 = "00000000-0000-4000-8000-000000000001"
    private val w2 = "00000000-0000-4000-8000-000000000002"
    private val w3 = "00000000-0000-4000-8000-000000000003"

    @Test fun explicitFreshConnectionStaysUnselectedAndExistingPreferencesSurvive() = runBlocking {
        val fresh = StoreFixtures.store()
        WorkspaceCatalog.prepareExplicitConnection(fresh)
        assertTrue(WorkspaceCatalog.preferences(fresh)!!.enabled.isEmpty())
        assertNull(WorkspaceCatalog.selected(fresh))
        val one = StoreFixtures.connect(fresh)
        val two = StoreFixtures.connect(fresh, "https://second.example.com")
        fresh.writer.replaceWorkspaces(one.id, listOf(StoreFixtures.workspace(w1, "One")))
        fresh.writer.replaceWorkspaces(two.id, listOf(StoreFixtures.workspace(w1, "Same ID elsewhere")))
        val listings = WorkspaceCatalog.listings(fresh, one.signerNpub)
        assertEquals(2, listings.map { it.boundary.id }.toSet().size)
        assertTrue(WorkspaceCatalog.preferences(fresh)!!.enabled.isEmpty())
        val chosen = WorkspacePreferences().withEnabled(listings.first().id, true)
        WorkspaceCatalog.save(fresh, chosen)
        WorkspaceCatalog.saveSelected(fresh, listings.first().id)
        WorkspaceCatalog.prepareExplicitConnection(fresh)
        assertEquals(chosen, WorkspaceCatalog.preferences(fresh))
        assertEquals(listings.first().id, WorkspaceCatalog.selected(fresh))
        val legacy = StoreFixtures.store()
        StoreFixtures.connect(legacy)
        WorkspaceCatalog.prepareExplicitConnection(legacy)
        assertNull(WorkspaceCatalog.preferences(legacy)) // Existing upgrade migration retains ownership.
    }

    @Test fun preferencesRoundTripAndDamagedStateRestoresNothing() {
        val p = WorkspacePreferences().withEnabled("a", true).withEnabled("b", true).withEnabled("a", false)
            .withCollapsed("b", true).withColour("b", WorkspaceColour.PURPLE).withOrder(listOf("b", "a", "c"))
        val back = WorkspacePreferences.decode(p.encode())!!
        assertEquals(listOf("b"), back.enabled)
        assertEquals(setOf("b"), back.collapsed)
        assertEquals(WorkspaceColour.PURPLE, back.colour("b"))
        assertEquals(WorkspaceColour.BLUE, back.colour("zzz"))
        assertEquals(listOf("b", "a", "c", "x", "y"), back.sorted(listOf("y", "a", "c", "b", "x")).let { sorted -> listOf("b", "a", "c") + sorted.filter { it !in listOf("b", "a", "c") } })
        assertNull(WorkspacePreferences.decode("not json"))
        assertNull(WorkspacePreferences.decode(null))
        // An intentionally empty selection stays empty: nothing is picked for the person.
        assertTrue(WorkspacePreferences.decode(WorkspacePreferences().encode())!!.enabled.isEmpty())
    }

    @Test fun anUpgradedInstallKeepsItsRememberedWorkspaceAndAdoptsWorkspacesWithUnsentWork() = runBlocking {
        val catalog = StoreFixtures.store()
        val c = StoreFixtures.connect(catalog)
        catalog.writer.replaceWorkspaces(c.id, listOf(StoreFixtures.workspace(w1, "Alpha"), StoreFixtures.workspace(w2, "Beta"), StoreFixtures.workspace(w3, "Gamma")))
        catalog.writer.setState("workspace:${c.id}", w2)
        catalog.writer.enqueueSend(ChatTarget(c.id, w3, "00000000-0000-4000-8000-000000000c01"), c.origin, c.signerNpub, StoreFixtures.workspace(w3, "Gamma").appNpub, "unsent", emptyList())
        val listings = WorkspaceCatalog.listings(catalog, c.signerNpub)
        val prefs = WorkspaceCatalog.initialPreferences(catalog, listings)
        val byId = listings.associate { it.workspace.id to it.id }
        assertEquals(setOf(byId[w2], byId[w3]), prefs.enabled.toSet())
        assertFalse(byId[w1] in prefs.enabled)
        assertEquals(listings.map { it.id }, prefs.order)
        // Another identity's connection is never listed.
        val other = StoreFixtures.connect(catalog, "https://other.example.com", "npub1" + "z".repeat(58))
        catalog.writer.replaceWorkspaces(other.id, listOf(StoreFixtures.workspace(w1, "Alpha elsewhere")))
        assertEquals(3, WorkspaceCatalog.listings(catalog, c.signerNpub).size)
    }
}
