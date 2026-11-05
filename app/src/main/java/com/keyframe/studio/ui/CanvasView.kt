package com.keyframe.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.GestureDetectorCompat
import com.keyframe.studio.engine.Document
import com.keyframe.studio.engine.Keyframe
import com.keyframe.studio.engine.Layer
import com.keyframe.studio.engine.LayerKind
import com.keyframe.studio.engine.RasterContent
import com.keyframe.studio.engine.VectorContent
import com.keyframe.studio.render.OnionSettings
import com.keyframe.studio.render.WorkspaceRenderer
import com.keyframe.studio.tools.Brush
import com.keyframe.studio.tools.BrushSettings
import com.keyframe.studio.tools.ColorPickerTool
import com.keyframe.studio.tools.EllipseTool
import com.keyframe.studio.tools.Eraser
import com.keyframe.studio.tools.FillTool
import com.keyframe.studio.tools.LineTool
import com.keyframe.studio.tools.Pen
import com.keyframe.studio.tools.RectangleTool
import com.keyframe.studio.tools.SelectionTool
import com.keyframe.studio.tools.TextTool
import com.keyframe.studio.tools.Tool
import com.keyframe.studio.tools.ToolContext
import com.keyframe.studio.tools.TransformTool
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Central drawing surface. Owns doc-space <-> view-space mapping (pinch zoom,
 * two-finger pan), tool event routing and frame rendering.
 */
class CanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    lateinit var doc: Document
    lateinit var renderer: WorkspaceRenderer
    lateinit var history: com.keyframe.studio.history.History

    val brushSettings = BrushSettings()
    val onion = OnionSettings()

    val brushTool = Brush(brushSettings)
    val penTool = Pen(brushSettings)
    val eraserTool = Eraser(brushSettings)
    val lineTool = LineTool(brushSettings)
    val rectTool = RectangleTool(brushSettings)
    val ellipseTool = EllipseTool(brushSettings)
    val fillTool = FillTool(brushSettings)
    val selectionTool = SelectionTool()
    val transformTool = TransformTool(selectionTool)
    var textTool: TextTool? = null
    val pickerTool = ColorPickerTool(brushSettings)

    var tool: Tool = brushTool
        private set
    var onColorChanged: ((Int) -> Unit)? = null
    var onToolChanged: (Tool) -> Unit = {}

    // view transform
    private val matrix = Matrix()
    private val inverse = Matrix()
    var scale = 1f
    var panX = 0f
    var panY = 0f

    private var dirty = false
    private val docPoint = PointF()

    // raster editing state (lives while a raster stroke is in progress)
    private var rasterCanvas: Canvas? = null
    private var pendingBefore: com.keyframe.studio.engine.Content? = null

    // ============================================================
    // Tool context
    // ============================================================

    private val toolCtx = object : ToolContext {
        override fun activeVectorContent(): VectorContent? {
            val l = doc.activeLayer()
            if (l.kind == LayerKind.RASTER) return null
            l.ensureKey(doc.currentFrame)
            return l.frameAt(doc.currentFrame)?.content as? VectorContent
        }

        override fun activeRasterCanvas(): Canvas? = rasterCanvas

        override fun ensureActiveLayerForCommit() {
            val l = doc.activeLayer()
            if (l.kind == LayerKind.RASTER) {
                val rc = l.ensureRasterFor(doc.currentFrame)
                rasterCanvas = Canvas(rc.bitmap)
            } else {
                l.ensureKey(doc.currentFrame)
            }
            // capture undo snapshot after ensuring the key exists but before shape insertion
            pendingBefore = snapshotContent(l, doc.currentFrame)
        }

        override fun setDirty() {
            dirty = true
            postInvalidate()
        }

        override fun commit(label: String) {
            val l = doc.activeLayer()
            val idx = doc.currentFrame
            val before = pendingBefore
            val after = snapshotContent(l, idx)
            pendingBefore = null
            if (contentChanged(before, after)) {
                val beforeC = before
                val afterC = after?.clone()
                history.push(label, {
                    restoreContent(l, idx, beforeC)
                    dirty = true; invalidate()
                }, {
                    restoreContent(l, idx, afterC)
                    dirty = true; invalidate()
                })
            }
            rasterCanvas = null
            dirty = true
            invalidate()
        }
    }

    private fun snapshotContent(l: Layer, idx: Int): com.keyframe.studio.engine.Content? =
        l.frameAt(idx)?.content?.clone()

    private fun restoreContent(l: Layer, idx: Int, snap: com.keyframe.studio.engine.Content?) {
        if (snap == null) {
            l.deleteKey(idx)
        } else {
            val existing = l.keyframes[idx]
            if (existing != null) existing.content = snap.clone()
            else l.keyframes[idx] = Keyframe(idx, snap.clone(), isBlank = false)
        }
    }

    private fun contentChanged(a: com.keyframe.studio.engine.Content?, b: com.keyframe.studio.engine.Content?): Boolean {
        if (a == null && b == null) return false
        if (a == null || b == null) return true
        if (a is VectorContent && b is VectorContent) {
            return a.shapes.size != b.shapes.size || !transformEqual(a, b)
        }
        return true
    }

    private fun transformEqual(a: VectorContent, b: VectorContent): Boolean =
        a.transform == b.transform

    // ============================================================
    // Public API
    // ============================================================

    fun invalidateDoc() {
        dirty = true
        setWillNotDraw(false)
        invalidate()
    }

    fun setTool(t: Tool) {
        tool = t
        onToolChanged(t)
        invalidate()
    }

    fun fitToScreen() {
        post {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            scale = minOf(w / doc.width, h / doc.height) * 0.92f
            panX = (w - doc.width * scale) / 2f
            panY = (h - doc.height * scale) / 2f
            updateMatrix()
            dirty = true
            invalidate()
        }
    }

    fun zoom(by: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        scale = (scale * by).coerceIn(0.08f, 16f)
        panX = focusX - (focusX - panX) * by
        panY = focusY - (focusY - panY) * by
        updateMatrix()
        dirty = true
        invalidate()
    }

    fun pan(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        updateMatrix()
        dirty = true
        invalidate()
    }

    fun updateMatrix() {
        matrix.reset()
        matrix.postScale(scale, scale, 0f, 0f)
        matrix.postTranslate(panX, panY)
        matrix.invert(inverse)
    }

    fun viewToDoc(vx: Float, vy: Float, out: PointF): PointF {
        val p = floatArrayOf(vx, vy)
        inverse.mapPoints(p)
        out.set(p[0], p[1])
        return out
    }

    // ============================================================
    // Rendering
    // ============================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            drawStageInternal(canvas)
        } catch (t: Throwable) {
            crashReporter?.invoke("Canvas render", t)
            drawCrashed = true
            if (drawCrashed) {
                canvas.drawColor(Color.rgb(40, 30, 30))
                canvas.drawText("⚠ Canvas module crashed (reported)", 40f, 80f, android.graphics.Paint().apply {
                    textSize = 36f; setARGB(255, 230, 200, 200)
                })
            }
        }
    }

    private var drawCrashed = false

    private fun drawStageInternal(canvas: Canvas) {
        canvas.drawColor(Color.rgb(28, 28, 30))
        val save = canvas.save()
        canvas.concat(matrix)
        renderer.drawStage(canvas, doc.currentFrame, canvasViewOnion())
        tool.drawPreview(canvas)
        if (tool === selectionTool || tool === transformTool) {
            val selBounds = if (selectionTool.sel.isNotEmpty()) unionBounds(selectionTool.sel) else null
            renderer.drawSelectionOverlay(
                canvas, selBounds,
                selectionTool.sel.singleOrNull() as? com.keyframe.studio.engine.PathShape
            )
        }
        canvas.restoreToCount(save)
    }

    private fun unionBounds(shapes: List<com.keyframe.studio.engine.Shape>): RectF {
        val r = RectF(0f, 0f, 0f, 0f)
        shapes.forEachIndexed { i, s ->
            val b = s.bounds()
            if (i == 0) r.set(b) else r.union(b)
        }
        return r
    }

    private fun canvasViewOnion(): OnionSettings = this.onion

    // ============================================================
    // Touch
    // ============================================================

    private val gesture = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tool === pickerTool) {
                    viewToDoc(e.x, e.y, docPoint)
                    pickColorAt(docPoint.x.toInt().coerceIn(0, doc.width - 1),
                        docPoint.y.toInt().coerceIn(0, doc.height - 1))
                    return true
                }
                return false
            }
        })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    /** 2-finger pan progress; cancelled once a stroke is in flight on the active tool. */
    private var inStroke = false
    private var multiStarted = false
    private var lastMultiX = 0f
    private var lastMultiY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var totalMovement = 0f

    private fun applyZoom(by: Float, fx: Float, fy: Float) {
        scale = (scale * by).coerceIn(0.08f, 16f)
        panX = fx - (fx - panX) * by
        panY = fy - (fy - panY) * by
        updateMatrix()
        dirty = true
        invalidate()
    }

    var crashReporter: ((String, Throwable) -> Unit)? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (crashReporter != null) {
            try {
                return onTouchEventInner(e)
            } catch (t: Throwable) {
                crashReporter?.invoke("Drawing", t)
                inStroke = false
                invalidate()
                return false
            }
        }
        return onTouchEventInner(e)
    }

    private fun onTouchEventInner(e: MotionEvent): Boolean {
        if (multiTouchPossible(e)) scaleDetector.onTouchEvent(e)
        gesture.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                maxPointers = 1
                totalMovement = 0f
                inStroke = true
                viewToDoc(e.x, e.y, docPoint)
                tool.onDown(docPoint.x, docPoint.y, e.pressure.coerceIn(0.05f, 1f), toolCtx)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) {
                    // second finger: cancel any running stroke, switch to pan/zoom
                    if (inStroke) {
                        tool.onUp(docPoint.x, docPoint.y, toolCtx) // may commit partial
                        inStroke = false
                    }
                    multiStarted = true
                    lastMultiX = (e.getX(0) + e.getX(1)) / 2f
                    lastMultiY = (e.getY(0) + e.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiStarted && e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    totalMovement += abs(mx - lastMultiX) + abs(my - lastMultiY)
                    pan(mx - lastMultiX, my - lastMultiY)
                    lastMultiX = mx; lastMultiY = my
                    return true
                }
                if (inStroke && e.pointerCount == 1) {
                    val prev = floatArrayOf(docPoint.x, docPoint.y)
                    viewToDoc(e.x, e.y, docPoint)
                    totalMovement += hypot(docPoint.x - prev[0], docPoint.y - prev[1]) * scale
                    val pressure = e.pressure.coerceIn(0.05f, 1f)
                    tool.onMove(docPoint.x, docPoint.y, pressure, toolCtx, true)
                    dirty = true
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount <= 2) multiStarted = false
                else multiStarted = e.pointerCount > 2
            }

            MotionEvent.ACTION_UP -> {
                val duration = e.eventTime - downTime
                val wasMulti = maxPointers >= 2
                if (e.pointerCount > maxPointers) maxPointers = e.pointerCount

                // two-finger tap shortcut => undo
                if (wasMulti && duration < 350 && totalMovement < 28f) {
                    multiStarted = false
                    history.undo()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                multiStarted = false
                if (inStroke && totalMovement < 10f && tool is ColorPickerTool) {
                    inStroke = false
                    return true
                }
                if (inStroke) {
                    viewToDoc(e.x, e.y, docPoint)
                    if (tool is FillTool) {
                        val l = doc.activeLayer()
                        fillTool.rasterSource = if (l.kind == LayerKind.RASTER) {
                            l.ensureRasterFor(doc.currentFrame)
                        } else null
                    }
                    tool.onUp(docPoint.x, docPoint.y, toolCtx)
                }
                inStroke = false
            }

            MotionEvent.ACTION_CANCEL -> {
                multiStarted = false
                if (inStroke) tool.onUp(docPoint.x, docPoint.y, toolCtx)
                inStroke = false
            }
        }
        return true
    }

    private fun multiTouchPossible(e: MotionEvent): Boolean = e.pointerCount >= 2 || multiStarted

    private fun pickColorAt(x: Int, y: Int) {
        val bmp = renderer.renderFrame(doc.currentFrame, transparent = false)
        val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
        bmp.recycle()
        if (c != Color.WHITE && Color.alpha(c) > 20) {
            brushSettings.color = c
            onColorChanged?.invoke(c)
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
