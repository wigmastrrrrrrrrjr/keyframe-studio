package com.keyframe.studio.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.keyframe.studio.engine.RectShape
import com.keyframe.studio.engine.Shape
import com.keyframe.studio.engine.VectorContent

/** Lasso / marquee selection over vector shapes. Tapping empty space clears selection. */
class SelectionTool : Tool("select", "Select", "⬚") {
    val sel = mutableListOf<Shape>()
    var start = PointF(); var cur = PointF(); var active = false

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        start.set(x, y); cur.set(x, y); active = true
        if (ctx.activeVectorContent() == null && ctx.activeRasterCanvas() != null) {
            // raster: selection box only
        }
    }

    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (down) { cur.set(x, y); ctx.setDirty() }
    }

    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        active = false
        val vc = ctx.activeVectorContent()
        if (vc != null) {
            val r = selectionRect()
            if (r.width() < 4f && r.height() < 4f) {
                // tap: pick topmost shape under the point
                sel.clear()
                val hit = vc.shapes.lastOrNull { it.bounds().contains(x, y) }
                if (hit != null) sel.add(hit)
            } else {
                sel.clear()
                vc.shapes.forEach { if (it.bounds().rect.intersects(r.left, r.top, r.right, r.bottom)) sel.add(it) }
            }
        }
        ctx.setDirty()
    }

    fun selectionRect(): RectF =
        RectF(minOf(start.x, cur.x), minOf(start.y, cur.y), maxOf(start.x, cur.x), maxOf(start.y, cur.y))

    override fun drawPreview(canvas: Canvas) {
        if (active) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f
            p.color = Color.rgb(79, 158, 218)
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
            val r = selectionRect()
            canvas.drawRect(r, p)
            // marching-ants fill
            p.alpha = 40; p.style = Paint.Style.FILL; canvas.drawRect(r, p)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 3f), 0f)
            p.color = Color.rgb(79, 158, 218)
            sel.forEach { canvas.drawRect(it.bounds(), p) }
        }
    }
}

/** Move / transform selected shapes or a box on raster layers. Supports anchor editing of paths. */
class TransformTool(private val selection: SelectionTool) : Tool("transform", "Transform", "⤡") {
    var last = PointF()
    var mode = 0 // 0 move, 1 scale, 2 rotate (kept simple)
    var draggingAnchor:PointF? = null
    var anchorSeg = -1
    var anchorIdx = -1

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        last.set(x, y)
        // check anchors of single selected path shape
        val path = selection.sel.singleOrNull() as? com.keyframe.studio.engine.PathShape
        if (path != null) {
            for (i in path.segments.indices) {
                val s = path.segments[i]
                val anchors = arrayOf(s.sx to s.sy, s.ex to s.ey)
                for (j in anchors.indices) {
                    if (kotlin.math.hypot(x - anchors[j].first, y - anchors[j].second) < 18f) {
                        anchorSeg = i; anchorIdx = j; draggingAnchor = PointF(x, y)
                        return
                    }
                }
            }
        }
    }

    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (!down) return
        val dx = x - last.x; val dy = y - last.y
        if (draggingAnchor != null && anchorSeg >= 0) {
            val path = selection.sel.singleOrNull() as? com.keyframe.studio.engine.PathShape ?: return
            val s = path.segments[anchorSeg]
            if (anchorIdx == 0) { s.sx += dx; s.sy += dy; if (anchorSeg > 0) { val p = path.segments[anchorSeg - 1]; p.ex += dx; p.ey += dy } }
            else { s.ex += dx; s.ey += dy; val n = path.segments.getOrNull(anchorSeg + 1); n?.let { it.sx += dx; it.sy += dy } }
        } else {
            selection.sel.forEach { move(it, dx, dy) }
        }
        last.set(x, y)
        ctx.setDirty()
    }

    private fun move(s: Shape, dx: Float, dy: Float) {
        when (s) {
            is com.keyframe.studio.engine.PathShape -> s.segments.forEach {
                it.sx += dx; it.sy += dy; it.c1x += dx; it.c1y += dy; it.c2x += dx; it.c2y += dy; it.ex += dx; it.ey += dy
            }
            is RectShape -> { s.x += dx; s.y += dy }
            is com.keyframe.studio.engine.EllipseShape -> { s.x += dx; s.y += dy }
            is com.keyframe.studio.engine.LineShape -> { s.x1 += dx; s.y1 += dy; s.x2 += dx; s.y2 += dy }
            is com.keyframe.studio.engine.TextShape -> { s.x += dx; s.y += dy }
        }
    }

    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (draggingAnchor != null || selection.sel.isNotEmpty()) ctx.commit("Transform")
        draggingAnchor = null; anchorSeg = -1
    }

    /** Scale selected shapes proportionally around selection center. */
    fun scale(factor: Float, ctx: ToolContext) {
        val sel = selection.sel
        if (sel.isEmpty()) return
        val r = RectF(1e9f, 1e9f, -1e9f, -1e9f)
        sel.forEach { r.union(it.bounds()) }
        val cx = r.centerX(); val cy = r.centerY()
        sel.forEach { scaleShape(it, cx, cy, factor, factor) }
        ctx.setDirty()
    }

    private fun scaleShape(s: Shape, cx: Float, cy: Float, sx: Float, sy: Float) {
        fun f(v: Float, c: Float) = c + (v - c) * sx
        fun fy(v: Float, c: Float) = c + (v - c) * sy
        when (s) {
            is com.keyframe.studio.engine.PathShape -> s.segments.forEach {
                it.sx = f(it.sx, cx); it.sy = fy(it.sy, cy)
                it.c1x = f(it.c1x, cx); it.c1y = fy(it.c1y, cy)
                it.c2x = f(it.c2x, cx); it.c2y = fy(it.c2y, cy)
                it.ex = f(it.ex, cx); it.ey = fy(it.ey, cy)
            }
            is RectShape -> { s.x = f(s.x, cx); s.y = fy(s.y, cy); s.w *= sx; s.h *= sy }
            is com.keyframe.studio.engine.EllipseShape -> { s.x = f(s.x, cx); s.y = fy(s.y, cy); s.w *= sx; s.h *= sy }
            is com.keyframe.studio.engine.LineShape -> { s.x1 = f(s.x1, cx); s.y1 = fy(s.y1, cy); s.x2 = f(s.x2, cx); s.y2 = fy(s.y2, cy) }
            is com.keyframe.studio.engine.TextShape -> { s.x = f(s.x, cx); s.y = fy(s.y, cy); s.size *= sy }
        }
    }
}

/** Fill bucket: flood fill on raster layers. */
class FillTool(var settings: BrushSettings) : Tool("fill", "Fill", "◧") {
    var rasterSource: com.keyframe.studio.engine.RasterContent? = null
    fun setRasterSource(rc: com.keyframe.studio.engine.RasterContent?) { rasterSource = rc }

    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        val target = rasterSource ?: return
        floodFill(target.bitmap, x.toInt(), y.toInt(), settings.color)
        ctx.commit("Fill")
    }

    // flood fill implemented in CanvasView which wires ctxRaster per tap
    fun floodFill(bmp: android.graphics.Bitmap, x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val target = pixels[y * w + x]
        if (target == color) return
        val stack = ArrayDeque<Int>()
        stack.add(y * w + x)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (pixels[i] != target) continue
            pixels[i] = color
            val px = i % w; val py = i / w
            if (px > 0) stack.add(i - 1)
            if (px < w - 1) stack.add(i + 1)
            if (py > 0) stack.add(i - w)
            if (py < h - 1) stack.add(i + w)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

/** Color eyedropper: CanvasView implements pixel sampling and applies result. */
class ColorPickerTool(settings: BrushSettings) : Tool("picker", "Eyedropper", "◉") {
    override val isPersistent = false
}
