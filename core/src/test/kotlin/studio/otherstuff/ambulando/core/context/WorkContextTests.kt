package studio.otherstuff.ambulando.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.Ids
import studio.otherstuff.ambulando.core.TestKeys
import studio.otherstuff.ambulando.core.obj
import studio.otherstuff.ambulando.core.store.SqlConnection

class WorkContextTests {
    private val dmScope = Ids.uuid(9, "0002")
    private val dmChannel = Ids.uuid(9, "0003")
    /** A second synthetic participant (key derived from a fixed synthetic value). */
    private val other = studio.otherstuff.ambulando.core.nostr.NostrKeys.npub("ab".repeat(32))!!

    private fun channel(id: String, scope: String, name: String, position: Double? = null, created: String = "2026-10-01T00:00:00.000Z",
                        kind: String = "channel", participants: List<String>? = null) =
        NavChannel(id, scope, name, kind, position, obj("created_at" to created, "participant_npubs" to participants))

    // MARK: Context value

    @Test fun contextKeepsAChannelOnlyUnderAScope() {
        assertEquals(WorkContext.HOME, WorkContext(null, Ids.channel1))
        val context = WorkContext(Ids.scopeA, Ids.channel1)
        assertEquals(context, WorkContext.decode(context.encoded()))
        assertEquals("""{"scopeId":"${Ids.scopeA}","channelId":"${Ids.channel1}"}""", context.encoded())
        assertEquals(WorkContext.HOME, WorkContext.decode("{}"))
        assertNull(WorkContext.decode("not json"))
        assertEquals("context:c:w", WorkContext.stateKey("c", "w"))
        assertTrue(WorkContext.HOME.isHome)
    }

    /** Losing access (or deletion) narrows the context instead of keeping an id the member can no longer read. */
    @Test fun resolvedContextFallsBackWhenAccessIsLost() {
        val scopes = listOf(NavScope(Ids.scopeA, "Alpha"), NavScope(Ids.scopeB, "Beta"))
        val channels = listOf(channel(Ids.channel1, Ids.scopeA, "general"), channel(Ids.channel3, Ids.scopeB, "ops"))
        val selected = WorkContext(Ids.scopeA, Ids.channel1)
        assertEquals(selected, selected.resolved(scopes, channels))
        assertEquals(WorkContext(Ids.scopeA), selected.resolved(scopes, channels.drop(1)))
        assertEquals(WorkContext.HOME, selected.resolved(scopes.drop(1), channels))
        // A channel id paired with the wrong scope never survives.
        assertEquals(WorkContext(Ids.scopeB), WorkContext(Ids.scopeB, Ids.channel1).resolved(scopes, channels))
        assertTrue(WorkContext(Ids.scopeA, Ids.channel2).contains(null, Ids.channel2))
        assertFalse(WorkContext(Ids.scopeA, Ids.channel2).contains(Ids.scopeA, Ids.channel1))
        assertTrue(WorkContext(Ids.scopeA).contains(Ids.scopeA, null))
        assertTrue(WorkContext.HOME.contains(null, null))
    }

    /** The context filter keeps a scope's records, including ones that carry only their channel, inside one workspace. */
    @Test fun queriesStayInsideTheContext() {
        SqlConnection.memory().use { db ->
            db.execute("CREATE TABLE channels (workspace_id TEXT, id TEXT, scope_id TEXT)")
            db.execute("CREATE TABLE tasks (workspace_id TEXT, id TEXT, scope_id TEXT, channel_id TEXT)")
            val w = Ids.workspace
            db.run("INSERT INTO channels VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)", w, Ids.channel1, Ids.scopeA, w, Ids.channel2, Ids.scopeA, w, Ids.channel3, Ids.scopeB)
            // t2 carries only its channel (no scope id): it still belongs to its channel's scope.
            db.run("INSERT INTO tasks VALUES (?, 't1', ?, ?), (?, 't2', NULL, ?), (?, 't3', ?, ?)", w, Ids.scopeA, Ids.channel1, w, Ids.channel2, w, Ids.scopeB, Ids.channel3)
            fun ids(workspace: String, context: WorkContext): Set<String> {
                val filter = ContextQueries.contextFilter(context, workspace)
                val out = HashSet<String>()
                db.query("SELECT id FROM tasks WHERE workspace_id = ? AND ${filter.sql}", *(listOf<Any?>(workspace) + filter.bindings).toTypedArray()) { out.add(it.text(0)!!) }
                return out
            }
            assertEquals(setOf("t1", "t2", "t3"), ids(w, WorkContext.HOME))
            assertEquals(setOf("t1", "t2"), ids(w, WorkContext(Ids.scopeA)))
            assertEquals(setOf("t2"), ids(w, WorkContext(Ids.scopeA, Ids.channel2)))
            assertEquals(setOf("t3"), ids(w, WorkContext(Ids.scopeB)))
            assertTrue("another workspace with the same scope id sees none of them", ids(Ids.workspace2, WorkContext(Ids.scopeA)).isEmpty())
        }
    }

    // MARK: Sidebar projection

    @Test fun sidebarPutsDirectMessagesFirstThenPersonalOrderAndChannelPositions() {
        val scopes = listOf(NavScope(Ids.scopeA, "Alpha", "project"), NavScope(Ids.scopeB, "Beta", "project"), NavScope(dmScope, "DMs", "dm"))
        val channels = listOf(
            channel(Ids.channel1, Ids.scopeA, "zeta", position = 2.0),
            channel(Ids.channel2, Ids.scopeA, "alpha", created = "2026-09-01T00:00:00.000Z"),
            channel(Ids.channel3, Ids.scopeA, "mid", position = 1.0),
            channel(dmChannel, dmScope, "DM", kind = "dm", participants = listOf(TestKeys.npub, other)),
            // A channel whose scope the member cannot see is not listed.
            channel(Ids.uuid(99, "0003"), Ids.uuid(99, "0002"), "hidden"),
        )
        val groups = SidebarNavigation.groups(scopes, channels, listOf(Ids.scopeB, Ids.scopeA))
        assertEquals(listOf("DMs", "Beta", "Alpha"), groups.map { it.scope.name })
        assertTrue(groups[0].isDirectMessages)
        assertEquals(listOf("mid", "zeta", "alpha"), groups[2].channels.map { it.name })
        assertFalse(groups.flatMap { it.channels }.any { it.name == "hidden" })
        assertEquals(listOf("mid", "alpha"), SidebarNavigation.unreadChannels(groups, setOf(Ids.channel2, Ids.channel3)).map { it.name })
        assertEquals(listOf("b", "c", "a"), ScopeOrdering.apply(listOf("a", "b", "c"), listOf("b", "c")) { it })
        assertEquals(listOf("x", "y"), ScopeOrdering.decode("""["x","y"]"""))
        assertEquals(emptyList<String>(), ScopeOrdering.decode("oops"))
    }

    @Test fun directMessageLabelsUseParticipants() {
        val dm = channel(dmChannel, dmScope, "DM", kind = "dm", participants = listOf(TestKeys.npub, other))
        assertEquals("Helper", SidebarNavigation.label(dm, TestKeys.npub, mapOf(other to "Helper")))
        assertEquals(SidebarNavigation.compact(other), SidebarNavigation.label(dm, TestKeys.npub, emptyMap()))
        val note = channel(dmChannel, dmScope, "DM", kind = "dm", participants = listOf(TestKeys.npub))
        assertEquals("Note to self", SidebarNavigation.label(note, TestKeys.npub, emptyMap()))
        assertEquals("general", SidebarNavigation.label(channel(Ids.channel1, Ids.scopeA, "general"), TestKeys.npub, emptyMap()))
        val described = NavChannel(Ids.channel2, dmScope, "", raw = obj("description" to "dm:${TestKeys.npub}|$other"))
        assertTrue(SidebarNavigation.isDirectMessageChannel(described))
        assertEquals(listOf(TestKeys.npub, other).sorted(), SidebarNavigation.participants(described))
        val bare = NavChannel(Ids.channel2, dmScope, "DM: Project chat", kind = "dm")
        assertEquals("Project chat", SidebarNavigation.label(bare, TestKeys.npub, emptyMap()))
        assertEquals(Ids.channel2.take(8), SidebarNavigation.label(NavChannel(Ids.channel2, Ids.scopeA, ""), null, emptyMap()))
    }
}

class NavigationStateTests {
    private val w1 = Ids.uuid(1, "0001")
    private val w2 = Ids.uuid(2, "0001")

    @Test fun effectiveWorkspace() {
        val listed = listOf(w1, w2)
        val gone = Ids.uuid(99, "0001")
        assertEquals("no choice: the first listed workspace", w1, WorkspaceSelection.effective(null, listed, true))
        assertEquals("a listed choice is kept", w2, WorkspaceSelection.effective(w2, listed, true))
        assertEquals("a workspace Tower no longer lists falls back to one it does", w1, WorkspaceSelection.effective(gone, listed, true))
        assertEquals("before any list is stored the choice is kept", w2, WorkspaceSelection.effective(w2, emptyList(), false))
        assertNull("Tower answered with no workspaces", WorkspaceSelection.effective(w2, emptyList(), true))
        assertNull(WorkspaceSelection.effective(null, emptyList(), false))
        assertEquals("workspaces.listed:c", WorkspaceSelection.listedKey("c"))
    }

    @Test fun workspaceListChanges() {
        val listed = listOf(w1, w2)
        fun change(selected: String?, navigation: String?, list: List<String>, complete: Boolean = true) =
            WorkspaceSelection.change(selected, navigation, list, complete)
        assertEquals(WorkspaceSelection.Change.None, change(w2, w2, listed))
        assertEquals(WorkspaceSelection.Change.None, change(w1, w1, listed))
        assertEquals("nothing to show yet, nothing to clear", WorkspaceSelection.Change.None, change(null, null, emptyList(), complete = false))
        assertEquals(WorkspaceSelection.Change.Show(w1), change(null, null, listed))
        assertEquals(WorkspaceSelection.Change.StartNavigation(w1), change(w1, null, listed))
        assertEquals(WorkspaceSelection.Change.Show(w1), change(w2, w2, listOf(w1)))
        assertEquals(WorkspaceSelection.Change.Clear, change(w2, w2, emptyList(), complete = true))
        assertEquals("not loaded is not the same as removed", WorkspaceSelection.Change.None, change(w2, w2, emptyList(), complete = false))
    }

    @Test fun loadStateNeverShowsNothingWithoutAReason() {
        fun state(hasWorkspace: Boolean = true, workspacesLoaded: Boolean = true, navigationLoaded: Boolean = true, hasScopes: Boolean = false,
                  canRead: Boolean = true, refreshing: Boolean = false, error: String? = null) =
            NavigationLoadState.resolve(NavigationLoadState.Inputs(hasWorkspace, workspacesLoaded, navigationLoaded, hasScopes, canRead, refreshing, error))
        assertEquals(NavigationLoadState.FindingWorkspaces, state(hasWorkspace = false, workspacesLoaded = false))
        assertEquals(NavigationLoadState.FindingWorkspaces, state(hasWorkspace = false, refreshing = true))
        assertEquals(NavigationLoadState.WorkspacesFailed("HTTP 401"), state(hasWorkspace = false, error = "HTTP 401"))
        assertEquals(NavigationLoadState.Locked, state(hasWorkspace = false, canRead = false))
        assertEquals(NavigationLoadState.NoWorkspaces, state(hasWorkspace = false))
        assertEquals(NavigationLoadState.Loading, state(navigationLoaded = false))
        assertEquals(NavigationLoadState.Loading, state(refreshing = true))
        assertEquals(NavigationLoadState.Failed("HTTP 500"), state(error = "HTTP 500"))
        assertEquals(NavigationLoadState.Locked, state(canRead = false))
        assertEquals(NavigationLoadState.Empty, state())
        assertEquals(NavigationLoadState.Ready(null), state(hasScopes = true))
        assertEquals(NavigationLoadState.Ready("offline"), state(hasScopes = true, error = "offline"))
        assertEquals("a retry in progress hides the old error", NavigationLoadState.Ready(null), state(hasScopes = true, refreshing = true, error = "offline"))
    }

    @Test fun navigationKeysAreScopedToTheWorkspace() {
        val snapshot = NavigationSyncSnapshot(running = setOf("channels:$w2:s"), scheduled = setOf("scopes:$w1"),
            errors = mapOf("messages:$w1:c" to "unrelated", "scopes:$w2" to "other workspace"))
        assertFalse("a scheduled retry is not running", NavigationKeys.refreshing(snapshot, "c", w1))
        assertTrue(NavigationKeys.refreshing(snapshot, "c", w2))
        assertNull("message and other-workspace errors are not sidebar errors", NavigationKeys.error(snapshot, "c", w1))
        assertEquals("other workspace", NavigationKeys.error(snapshot, "c", w2))
        val discovery = snapshot.copy(errors = snapshot.errors + ("discover:c" to "HTTP 401"))
        assertEquals("HTTP 401", NavigationKeys.error(discovery, "c", w2))
    }
}
