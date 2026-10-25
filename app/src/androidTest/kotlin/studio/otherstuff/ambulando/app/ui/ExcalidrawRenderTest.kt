package studio.otherstuff.ambulando.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.canvas.ExcalidrawCanvas
import studio.otherstuff.ambulando.app.ui.canvas.PreparedBoard
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExcalidrawRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun el(id: String, type: String, x: Int, y: Int, w: Int, h: Int, extra: String = "") =
        """{"id":"$id","type":"$type","x":$x,"y":$y,"width":$w,"height":$h,"angle":0,"strokeColor":"#1e1e1e","backgroundColor":"#a5d8ff","fillStyle":"solid","strokeWidth":2,"strokeStyle":"solid","opacity":100,"version":1,"versionNonce":1,"isDeleted":false$extra}"""

    private val scene = """{"type":"excalidraw","version":2,"elements":[
        ${el("r1", "rectangle", 0, 0, 200, 100, ",\"roundness\":{\"type\":3}")},
        ${el("e1", "ellipse", 260, 0, 160, 100)},
        ${el("d1", "diamond", 480, 0, 120, 100)},
        ${el("a1", "arrow", 200, 50, 60, 0, ",\"points\":[[0,0],[60,0]],\"endArrowhead\":\"triangle\"")},
        ${el("t1", "text", 20, 140, 300, 30, ",\"text\":\"Hello board\",\"fontSize\":24,\"textAlign\":\"left\",\"verticalAlign\":\"top\"")},
        ${el("f1", "freedraw", 400, 140, 120, 60, ",\"points\":[[0,0],[30,40],[60,10],[120,60]]")}
    ],"appState":{"viewBackgroundColor":"#ffffff"},"files":{}}""".trimIndent()

    @Test fun drawsTheSupportedSubset() {
        val board = runBlocking { PreparedBoard.prepare(scene.toByteArray(), "v1", "v1") }
        assertEquals(PreparedBoard.State.Ready, board.state)
        assertEquals(6, board.scene.elements.size)
        compose.setContent { ExcalidrawCanvas(board, Modifier.size(360.dp, 200.dp).background(Color.White).testTag("board")) }
        val image = compose.onNodeWithTag("board").captureToImage().asAndroidBitmap()
        // Something other than the white background was drawn (shapes, fill, text).
        var nonWhite = 0
        for (x in 0 until image.width step 4) for (y in 0 until image.height step 4) if (image.getPixel(x, y) != android.graphics.Color.WHITE) nonWhite++
        assertTrue("drawn pixels: $nonWhite", nonWhite > 200)
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "excalidraw-render.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
