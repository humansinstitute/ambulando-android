package studio.otherstuff.ambulando.app.ui.canvas

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.ensureActive
import studio.otherstuff.ambulando.core.surfaces.CanvasElement
import studio.otherstuff.ambulando.core.surfaces.ExcalidrawScene
import kotlin.coroutines.coroutineContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Native drawing of the supported Excalidraw subset ([ExcalidrawScene]) and of Canvas drawing elements. Scenes are
// parsed and their images decoded off the main thread ([PreparedBoard.prepare]); the main thread only draws prepared
// values.

/** World → view transform: a uniform scale and an offset (pan/zoom are viewer-local and never saved). */
@Immutable
data class ViewTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun apply(x: Double, y: Double) = Offset((x * scale + offsetX).toFloat(), (y * scale + offsetY).toFloat())
}

/** Decoded, downsampled board images by file id. */
@Immutable
class BoardImages(val images: Map<String, ImageBitmap> = emptyMap()) {
    companion object {
        const val MAX_PIXEL = 1024

        suspend fun decode(scene: ExcalidrawScene): BoardImages {
            val out = HashMap<String, ImageBitmap>()
            for ((id, image) in scene.images) {
                coroutineContext.ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image.data, 0, image.data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXEL) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(image.data, 0, image.data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: continue
                out[id] = bitmap.asImageBitmap()
            }
            return BoardImages(out)
        }
    }
}

/** A board ready to draw: the parsed scene (or why it cannot be shown) and its decoded images. */
@Immutable
data class PreparedBoard(
    val state: State = State.NotStored,
    val scene: ExcalidrawScene = ExcalidrawScene(),
    val images: BoardImages = BoardImages(),
    val versionId: String? = null,
) {
    sealed interface State {
        data object Ready : State
        data object NotStored : State
        data class Failed(val reason: String) : State
    }

    companion object {
        /** Runs on the caller's (background) dispatcher; cancellation stops parsing and decoding. */
        suspend fun prepare(bytes: ByteArray?, versionId: String?, expectedVersionId: String?): PreparedBoard {
            if (bytes == null) return PreparedBoard()
            return try {
                val scene = ExcalidrawScene.parseCancellable(bytes)
                PreparedBoard(State.Ready, scene, BoardImages.decode(scene), versionId.takeIf { it == expectedVersionId })
            } catch (e: ExcalidrawScene.ParseException.TooLarge) {
                PreparedBoard(State.Failed("This board is larger than the 24 MB / 20,000 element limit."))
            } catch (e: ExcalidrawScene.ParseException.Invalid) {
                PreparedBoard(State.Failed(e.detail))
            }
        }
    }
}

object BoardColor {
    /** `#rgb`, `#rrggbb`, `#rrggbbaa`; "transparent" and anything unknown are null. */
    fun parse(text: String?): Color? {
        var hex = text?.trim()?.lowercase() ?: return null
        if (!hex.startsWith("#")) return null
        hex = hex.drop(1)
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 8) Color(((value shr 24) and 0xff).toInt(), ((value shr 16) and 0xff).toInt(), ((value shr 8) and 0xff).toInt(), (value and 0xff).toInt())
        else Color(((value shr 16) and 0xff).toInt(), ((value shr 8) and 0xff).toInt(), (value and 0xff).toInt())
    }
}

object BoardFit {
    /** Fits the whole scene into [size] with a margin, never enlarging more than 4×. */
    fun fit(scene: ExcalidrawScene, size: Size, margin: Float = 12f): ViewTransform {
        val w = max(1.0, scene.maxX - scene.minX).toFloat()
        val h = max(1.0, scene.maxY - scene.minY).toFloat()
        val s = minOf((size.width - 2 * margin) / w, (size.height - 2 * margin) / h, 4f).coerceAtLeast(0.01f)
        return ViewTransform(s, (size.width - w * s) / 2 - scene.minX.toFloat() * s, (size.height - h * s) / 2 - scene.minY.toFloat() * s)
    }
}

/** Draws a prepared scene, fitted (with margin) unless a transform is given. */
@Composable
fun ExcalidrawCanvas(board: PreparedBoard, modifier: Modifier = Modifier, transform: ViewTransform? = null) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.clearAndSetSemantics { }) {
        val scene = board.scene
        BoardColor.parse(scene.viewBackgroundColor)?.let { drawRect(it) }
        val t = transform ?: BoardFit.fit(scene, size)
        withTransform({ translate(t.offsetX, t.offsetY); scale(t.scale, t.scale, Offset.Zero) }) {
            for (element in scene.elements) drawElement(element, board.images, measurer)
        }
    }
}

private fun dashFor(style: String, scale: Float = 1f): PathEffect? = when (style) {
    "dashed" -> PathEffect.dashPathEffect(floatArrayOf(8f * scale, 6f * scale))
    "dotted" -> PathEffect.dashPathEffect(floatArrayOf(1.5f * scale, 5f * scale))
    else -> null
}

// Canvas coordinates and the outer transform already supply scale; remove sp density/font-scale expansion here.
private fun DrawScope.drawElement(e: ExcalidrawScene.Element, images: BoardImages, measurer: TextMeasurer) {
    val rect = Rect(e.x.toFloat(), e.y.toFloat(), (e.x + e.width).toFloat(), (e.y + e.height).toFloat())
    val alpha = e.opacity.toFloat().coerceIn(0f, 1f)
    val strokeColor = BoardColor.parse(e.strokeColor) ?: Color.Black
    val stroke = Stroke(width = e.strokeWidth.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashFor(e.strokeStyle))
    val fill = BoardColor.parse(e.backgroundColor)?.let { if (e.fillStyle == "solid") it else it.copy(alpha = it.alpha * 0.35f) }
    rotateRad(e.angle.toFloat(), pivot = rect.center) {
        fun shape(path: Path) {
            fill?.let { drawPath(path, it, alpha, Fill) }
            drawPath(path, strokeColor, alpha, stroke)
        }
        when (e.kind) {
            ExcalidrawScene.Kind.RECTANGLE -> shape(Path().apply {
                if (e.rounded) {
                    val r = min(rect.width, rect.height) * 0.12f
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(r, r)))
                } else addRect(rect)
            })
            ExcalidrawScene.Kind.ELLIPSE -> shape(Path().apply { addOval(rect) })
            ExcalidrawScene.Kind.DIAMOND -> shape(Path().apply {
                moveTo(rect.center.x, rect.top); lineTo(rect.right, rect.center.y); lineTo(rect.center.x, rect.bottom); lineTo(rect.left, rect.center.y); close()
            })
            ExcalidrawScene.Kind.LINE, ExcalidrawScene.Kind.ARROW, ExcalidrawScene.Kind.FREEDRAW -> {
                val points = e.points.mapNotNull { p -> if (p.size >= 2) Offset((e.x + p[0]).toFloat(), (e.y + p[1]).toFloat()) else null }
                if (points.size < 2) return@rotateRad
                val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                if (e.kind == ExcalidrawScene.Kind.LINE && points.first() == points.last()) fill?.let { drawPath(path, it, alpha, Fill) }
                drawPath(path, strokeColor, alpha, stroke)
                if (e.kind == ExcalidrawScene.Kind.ARROW) {
                    e.endArrowhead?.let { arrowhead(it, points[points.size - 1], points[points.size - 2], strokeColor, e.strokeWidth.toFloat(), alpha) }
                    e.startArrowhead?.let { arrowhead(it, points[0], points[1], strokeColor, e.strokeWidth.toFloat(), alpha) }
                }
            }
            ExcalidrawScene.Kind.TEXT -> {
                val text = e.text.orEmpty()
                if (text.isEmpty()) return@rotateRad
                val align = when (e.textAlign) { "center" -> TextAlign.Center; "right" -> TextAlign.Right; else -> TextAlign.Left }
                val width = max(1f, rect.width + 4f).toInt()
                val layout = measurer.measure(text, TextStyle(color = strokeColor.copy(alpha = strokeColor.alpha * alpha), fontSize = (e.fontSize.toFloat() / density / fontScale).sp, textAlign = align),
                    constraints = Constraints(maxWidth = width))
                val top = when (e.verticalAlign) {
                    "middle" -> rect.center.y - layout.size.height / 2f
                    "bottom" -> rect.bottom - layout.size.height
                    else -> rect.top
                }
                drawText(layout, topLeft = Offset(rect.left - 2f, top))
            }
            ExcalidrawScene.Kind.IMAGE -> {
                val bitmap = e.fileId?.let { images.images[it] }
                if (bitmap != null) {
                    drawImage(bitmap, srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()), dstSize = IntSize(max(1, rect.width.toInt()), max(1, rect.height.toInt())), alpha = alpha)
                } else {
                    drawRect(Color.Gray, rect.topLeft, rect.size, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
                }
            }
            ExcalidrawScene.Kind.FRAME -> {
                drawRoundRect(Color.Gray, rect.topLeft, rect.size, CornerRadius(4f, 4f), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
                e.name?.takeIf { it.isNotEmpty() }?.let { name ->
                    val layout = measurer.measure(name, TextStyle(color = Color.Gray, fontSize = (12f / density / fontScale).sp))
                    drawText(layout, topLeft = Offset(rect.left, rect.top - 4f - layout.size.height))
                }
            }
        }
    }
}

private fun DrawScope.arrowhead(kind: String, tip: Offset, previous: Offset, color: Color, width: Float, alpha: Float) {
    val angle = atan2(tip.y - previous.y, tip.x - previous.x)
    val length = max(10f, width * 5f)
    val left = Offset(tip.x - length * cos(angle - Math.PI.toFloat() / 7), tip.y - length * sin(angle - Math.PI.toFloat() / 7))
    val right = Offset(tip.x - length * cos(angle + Math.PI.toFloat() / 7), tip.y - length * sin(angle + Math.PI.toFloat() / 7))
    when (kind) {
        "triangle", "triangle_outline" -> {
            val p = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }
            if (kind == "triangle") drawPath(p, color, alpha, Fill) else drawPath(p, color, alpha, Stroke(width))
        }
        "dot", "circle", "circle_outline" -> {
            val r = length / 3
            if (kind == "circle_outline") drawCircle(color, r, tip, alpha, Stroke(width)) else drawCircle(color, r, tip, alpha)
        }
        "bar" -> drawLine(color, left, right, width, alpha = alpha)
        else -> drawPath(Path().apply { moveTo(left.x, left.y); lineTo(tip.x, tip.y); lineTo(right.x, right.y) }, color, alpha,
            Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Draws Canvas drawing elements (text, shapes, arrows, lines, freehand, frames) at their saved world positions. */
@Composable
fun CanvasElementsLayer(elements: List<CanvasElement>, transform: ViewTransform, ink: Color, muted: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.clearAndSetSemantics { }) {
        withTransform({ translate(transform.offsetX, transform.offsetY); scale(transform.scale, transform.scale, Offset.Zero) }) {
            for (e in elements) drawCanvasElement(e, ink, muted, measurer)
        }
    }
}

private fun DrawScope.drawCanvasElement(e: CanvasElement, ink: Color, muted: Color, measurer: TextMeasurer) {
    val l = e.layout
    val rect = Rect(l.x.toFloat(), l.y.toFloat(), (l.x + l.width).toFloat(), (l.y + l.height).toFloat())
    val dash = when (e.lineStyle) {
        "dashed" -> PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
        "dotted" -> PathEffect.dashPathEffect(floatArrayOf(1f, 7f))
        else -> null
    }
    when (e.kind) {
        CanvasElement.Kind.RECTANGLE -> drawRoundRect(ink, rect.topLeft, rect.size, CornerRadius(6f, 6f), style = Stroke(2f))
        CanvasElement.Kind.ELLIPSE -> drawOval(ink, rect.topLeft, rect.size, style = Stroke(2f))
        CanvasElement.Kind.DIAMOND -> drawPath(Path().apply {
            moveTo(rect.center.x, rect.top); lineTo(rect.right, rect.center.y); lineTo(rect.center.x, rect.bottom); lineTo(rect.left, rect.center.y); close()
        }, ink, style = Stroke(2f))
        CanvasElement.Kind.ARROW, CanvasElement.Kind.LINE -> {
            if (e.start.size < 2 || e.end.size < 2) return
            val start = Offset((l.x + e.start[0]).toFloat(), (l.y + e.start[1]).toFloat())
            val end = Offset((l.x + e.end[0]).toFloat(), (l.y + e.end[1]).toFloat())
            val control = e.curve?.takeIf { it.size >= 2 }?.let { Offset((l.x + it[0]).toFloat(), (l.y + it[1]).toFloat()) }
            val path = Path().apply { moveTo(start.x, start.y); if (control != null) quadraticTo(control.x, control.y, end.x, end.y) else lineTo(end.x, end.y) }
            drawPath(path, ink, style = Stroke(2f, cap = StrokeCap.Round, pathEffect = dash))
            if (e.endArrowhead != "none") filledHead(end, control ?: start, ink)
            if (e.startArrowhead != "none") filledHead(start, control ?: end, ink)
        }
        CanvasElement.Kind.DRAW -> {
            val points = e.points.mapNotNull { p -> if (p.size >= 2) Offset((l.x + p[0]).toFloat(), (l.y + p[1]).toFloat()) else null }
            if (points.size < 2) return
            drawPath(Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }, ink,
                style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        CanvasElement.Kind.TEXT -> {
            val text = e.text.orEmpty()
            if (text.isEmpty()) return
            val layout = measurer.measure(text, TextStyle(color = ink, fontSize = (e.fontSize.toFloat() / density / fontScale).sp), constraints = Constraints(maxWidth = max(1, rect.width.toInt())))
            drawText(layout, topLeft = rect.topLeft)
        }
        CanvasElement.Kind.FRAME -> if (!e.isGroup) {
            drawRoundRect(muted, rect.topLeft, rect.size, CornerRadius(8f, 8f), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
        }
        null -> Unit
    }
}

private fun DrawScope.filledHead(tip: Offset, previous: Offset, ink: Color) {
    val angle = atan2(tip.y - previous.y, tip.x - previous.x)
    val a = Math.PI.toFloat() / 7
    drawPath(Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - 12 * cos(angle - a), tip.y - 12 * sin(angle - a))
        lineTo(tip.x - 12 * cos(angle + a), tip.y - 12 * sin(angle + a))
        close()
    }, ink)
}
