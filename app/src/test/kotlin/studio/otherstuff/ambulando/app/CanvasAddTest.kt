package studio.otherstuff.ambulando.app

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.CanvasEditor
import studio.otherstuff.ambulando.core.surfaces.CanvasScene
import studio.otherstuff.ambulando.core.surfaces.CanvasSourceChoice

class CanvasAddTest {
    private val choices = listOf(
        CanvasSourceChoice(CanvasEditor.SourceType.DOCUMENT, "d1", "Roadmap notes"),
        CanvasSourceChoice(CanvasEditor.SourceType.EXCALIDRAW, "b1", "Road sketch"),
        CanvasSourceChoice(CanvasEditor.SourceType.CHAT_THREAD, "t1", "Standup"),
    )

    @Test fun theAddListFiltersByTypeAndNameSubstring() {
        assertEquals(listOf("d1", "b1"), filterChoices(choices, AddKind.ALL, "ROAD").map { it.sourceId })
        assertEquals(listOf("b1"), filterChoices(choices, AddKind.BOARD, "").map { it.sourceId })
        assertEquals(listOf("t1"), filterChoices(choices, AddKind.ALL, "  stand ").map { it.sourceId })
        assertEquals(emptyList<String>(), filterChoices(choices, AddKind.CANVAS, "").map { it.sourceId })
    }

    @Test fun newItemsGoBesideEverythingAndOnTop() {
        val empty = CanvasScene.parse("""{"naplets":[],"elements":[]}""".toByteArray())
        assertEquals(40.0, nextLayout(empty).x, 0.0)
        val scene = CanvasScene.parse("""{"naplets":[{"id":"00000000-0000-4000-8000-000000000001","access":"ready","title":"A","row_version":1,
            "layout":{"x":100,"y":50,"width":400,"height":300,"z":4},"source":{"type":"document","id":"00000000-0000-4000-8000-000000000002"},"content":{}}],"elements":[]}""".toByteArray())
        val next = nextLayout(scene)
        assertEquals(540.0, next.x, 0.0)
        assertEquals(50.0, next.y, 0.0)
        assertEquals(5, next.z)
    }

    @Test fun drawingToolsMakeSolsDefaultsOnTapAndFollowTheDrag() {
        val (rect, layout) = CanvasTool.RECTANGLE.element(listOf(10.0, 20.0), null, 5)
        assertEquals(studio.otherstuff.ambulando.core.surfaces.NewCanvasElement.Shape(studio.otherstuff.ambulando.core.surfaces.CanvasElement.Kind.RECTANGLE), rect)
        assertEquals(studio.otherstuff.ambulando.core.surfaces.CanvasLayout(10.0, 20.0, 160.0, 100.0, 5), layout)
        val (_, dragged) = CanvasTool.FRAME.element(listOf(300.0, 200.0), listOf(100.0, 50.0), 2)
        assertEquals("a drag from either corner", studio.otherstuff.ambulando.core.surfaces.CanvasLayout(100.0, 50.0, 200.0, 150.0, 2), dragged)
        val (arrow, _) = CanvasTool.ARROW.element(listOf(0.0, 0.0), null, 1)
        assertEquals(studio.otherstuff.ambulando.core.surfaces.NewCanvasElement.Arrow(listOf(0.0, 0.0), listOf(160.0, 0.0)), arrow)
        val (line, _) = CanvasTool.LINE.element(listOf(0.0, 0.0), listOf(5.0, 9.0), 1)
        assertEquals(true, (line as studio.otherstuff.ambulando.core.surfaces.NewCanvasElement.Arrow).line)
    }

    @Test fun groupKeysFindTheirItems() {
        val scene = CanvasScene.parse("""{"naplets":[{"id":"00000000-0000-4000-8000-000000000001","access":"ready","title":"A","row_version":1,
            "layout":{"x":100,"y":50,"width":400,"height":300,"z":4},"source":{"type":"document","id":"00000000-0000-4000-8000-000000000002"},"content":{}}],
            "elements":[{"id":"00000000-0000-4000-8000-000000000003","kind":"text","row_version":1,"layout":{"x":1,"y":2,"width":30,"height":40,"z":1},"data":{"text":"Hi"}}]}""".toByteArray())
        assertEquals(400.0, itemLayout(scene, "naplet:00000000-0000-4000-8000-000000000001")!!.width, 0.0)
        assertEquals(30.0, itemLayout(scene, "element:00000000-0000-4000-8000-000000000003")!!.width, 0.0)
        assertEquals(null, itemLayout(scene, "element:missing"))
    }
}
