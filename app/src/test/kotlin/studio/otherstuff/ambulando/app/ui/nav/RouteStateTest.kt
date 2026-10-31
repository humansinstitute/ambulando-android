package studio.otherstuff.ambulando.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteStateTest {
    private val w = "11111111-1111-1111-1111-111111111111"

    @Test fun savesAndRestoresEverySectionStack() {
        val stacks = mapOf(
            "chat" to listOf(WorkRoute.Thread(w, "chan", "thread")),
            "tasks" to listOf(WorkRoute.Task(w, "t1"), WorkRoute.Task(w, "t2")),
            "docs" to listOf(WorkRoute.Doc(w, "d1")),
            "files" to listOf(WorkRoute.File(w, "f1")),
            "deck" to listOf(WorkRoute.Canvas(w, "c1"), WorkRoute.Board(w, "c1", "n1")),
            "context" to listOf(WorkRoute.ContextComponent(w, "s", "k")),
        )
        val restored = RouteState.decode(RouteState.encode("tasks", stacks), w)!!
        assertEquals("tasks", restored.section)
        assertEquals(stacks, restored.stacks)
    }

    @Test fun routesOfAnotherWorkspaceAreNeverRestored() {
        val encoded = RouteState.encode("docs", mapOf("docs" to listOf(WorkRoute.Doc("other", "d1"), WorkRoute.Doc(w, "d2"))))
        assertEquals(listOf(WorkRoute.Doc(w, "d2")), RouteState.decode(encoded, w)!!.stacks["docs"])
    }

    @Test fun damagedOrAbsentStateRestoresNothing() {
        assertNull(RouteState.decode(null, w))
        assertNull(RouteState.decode("not json", w))
        assertEquals(emptyMap<String, List<WorkRoute>>(), RouteState.decode("{\"section\":\"chat\"}", w)!!.stacks)
        assertEquals(emptyList<WorkRoute>(), RouteState.decode("{\"stacks\":{\"docs\":[{\"t\":\"doc\"},{\"w\":\"$w\",\"t\":\"nope\",\"a\":\"x\"}]}}", w)!!.stacks["docs"])
    }

    @Test fun publisherHtmlIsNotReopenedByARestart() {
        val encoded = RouteState.encode("deck", mapOf("deck" to listOf(WorkRoute.HtmlArtefact(w, "c", "n", "Page"))))
        assertEquals(emptyList<WorkRoute>(), RouteState.decode(encoded, w)!!.stacks["deck"])
    }
}
