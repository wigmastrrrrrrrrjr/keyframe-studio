package com.keyframe.studio.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.keyframe.studio.engine.*

/** Shared stroke settings (brush panel). */
class BrushSettings {
    var color = Color.BLACK
    var width = 8f
    var opacity = 1f
    var filled = false
    var fillColor = Color.GRAY
    var pressureSensitive = true
    var smoothing = 0.5f
}

abstract class Tool(val id: String, val label: String, val icon: String) {
    open val isPersistent: Boolean = true
    open fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {}
    open fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {}
    open fun onUp(x: Float, y: Float, ctx: ToolContext) {}
    open fun drawPreview(canvas: Canvas) {}
}

class Brush(var settings: BrushSettings) : Tool("brush", "Pencil", "✎") {
    val pts = mutableListOf<PointF>()
    val pressures = mutableListOf<Float>()

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        pts.clear(); pressures.clear()
        pts.add(PointF(x, y)); pressures.add(pressure)
    }

    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (!down) return
        // light smoothing
        val last = pts.lastOrNull()
        val nx = last?.let { it.x + (x - it.x) * settings.smoothing } ?: x
        val ny = last?.let { it.y + (y - it.y) * settings.smoothing } ?: y
        pts.add(PointF(nx, ny)); pressures.add(pressure)
        ctx.setDirty()
    }

    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (pts.isEmpty()) return
        if (pts.size == 1) pts.add(PointF(pts[0].x + 0.5f, pts[0].y))
        ctx.ensureActiveLayerForCommit()
        val content = ctx.activeVectorContent()
        val style = StrokeStyle(settings.color, settings.width, settings.opacity, false, settings.fillColor)
        val shape = PathShape.fromPoints(pts, style)
        if (content is VectorContent) {
            content.shapes.add(shape)
        } else {
            // raster layer: paint stroke directly onto bitmap
            val rc = ctx.activeRasterCanvas()
            rc?.let {
                paintStrokeOnRaster(it, pts, pressures)
            }
        }
        ctx.commit(if (settings.pressureSensitive) "Brush stroke" else "Stroke")
        pts.clear(); pressures.clear()
    }

    override fun drawPreview(canvas: Canvas) {
        if (pts.isNotEmpty()) paintStrokeOnRaster(canvas, pts, pressures)
    }

    private fun paintStrokeOnRaster(canvas: Canvas, pts: List<PointF>, pressures: List<Float>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = settings.color
        p.strokeWidth = settings.width
        if (settings.pressureSensitive && pressures.size > 1) {
            for (i in 1 until pts.size) {
                val w = settings.width * (0.4f + 0.6f * pressures[i])
                p.strokeWidth = w
                path.quickReset()
                path.moveTo(pts[i - 1].x, pts[i - 1].y)
                path.lineTo(pts[i].x, pts[i].y)
                canvas.drawPath(path, p)
            }
        } else {
            path.quickReset()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, p)
        }
    }

    companion object { val path = Path() }
}

private fun Path.quickReset() { rewind() }

class Eraser(var settings: BrushSettings) : Tool("eraser", "Eraser", "⌫") {
    val pts = mutableListOf<PointF>()

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        pts.clear(); pts.add(PointF(x, y))
    }

    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (down) { pts.add(PointF(x, y)); ctx.setDirty() }
    }

    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (pts.isEmpty()) return
        ctx.ensureActiveLayerForCommit()
        val rc = ctx.activeRasterCanvas()
        if (rc != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = settings.width
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            rc.drawPath(path, p)
        } else {
            // vector eraser: delete shapes under stroke path
            val content = ctx.activeVectorContent()
            if (content != null) {
                val r = RectBounds()
                pts.forEach { r.add(it.x, it.y, settings.width) }
                content.shapes.removeAll { boundsAt(it, r) }
            }
        }
        ctx.commit("Erase")
        pts.clear()
    }

    override fun drawPreview(canvas: Canvas) {
        if (pts.isEmpty()) return
        val p = Paint()
        p.strokeWidth = settings.width; p.color = Color.rgb(200, 200, 210); p.alpha = 120
        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.STROKE
        val path = Path(); path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        canvas.drawPath(path, p)
    }

    private class RectBounds { var l = 1e9f; var t = 1e9f; var r = -1e9f; var b = -1e9f
        fun add(x: Float, y: Float, w: Float) { l = minOf(l, x - w); t = minOf(t, y - w); r = maxOf(r, x + w); b = maxOf(b, y + w) }
        val rect get() = android.graphics.RectF(l, t, r, b)
    }

    private fun boundsAt(shape: Shape, r: RectBounds): Boolean {
        val b = shape.bounds()
        return b.intersects(r.rect.left, r.rect.top, r.rect.right, r.rect.bottom)
    }
}

class Pen(var settings: BrushSettings) : Tool("pen", "Pen", "✒") {
    val pts = mutableListOf<PointF>()

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        pts.clear(); pts.add(PointF(x, y))
    }
    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (down) { pts.add(PointF(x, y)); ctx.setDirty() }
    }
    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (pts.isEmpty()) return
        ctx.ensureActiveLayerForCommit()
        val style = StrokeStyle(settings.color, settings.width, settings.opacity, settings.filled, settings.fillColor)
        (ctx.activeVectorContent() as? VectorContent)?.shapes?.add(PathShape.fromPoints(pts, style))
        ctx.commit("Pen stroke")
        pts.clear()
    }
    override fun drawPreview(canvas: Canvas) {
        if (pts.size > 1) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = settings.color; p.style = Paint.Style.STROKE; p.strokeWidth = settings.width
            val path = Path(); path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, p)
        }
    }
}

class LineTool(var settings: BrushSettings) : Tool("line", "Line", "／") {
    var start = PointF(); var cur = PointF(); var active = false
    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        start.set(x, y); cur.set(x, y); active = true
    }
    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) {
        if (down) { cur.set(x, y); ctx.setDirty() }
    }
    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (!active) return
        active = false
        ctx.ensureActiveLayerForCommit()
        val style = StrokeStyle(settings.color, settings.width, settings.opacity, false, settings.fillColor)
        (ctx.activeVectorContent() as? VectorContent)?.shapes?.add(
            LineShape(style, start.x, start.y, cur.x, cur.y))
        ctx.commit("Line")
    }
    override fun drawPreview(canvas: Canvas) {
        if (!active) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb((settings.opacity * 255).toInt(), Color.red(settings.color), Color.green(settings.color), Color.blue(settings.color))
        p.style = Paint.Style.STROKE; p.strokeWidth = settings.width; p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(start.x, start.y, cur.x, cur.y, p)
    }
}

abstract class RectBase(id: String, label: String, icon: String, var settings: BrushSettings) :
    Tool(id, label, icon) {
    var start = PointF(); var cur = PointF(); var active = false
    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) { start.set(x, y); cur.set(x, y); active = true }
    override fun onMove(x: Float, y: Float, pressure: Float, ctx: ToolContext, down: Boolean) { if (down) { cur.set(x, y); ctx.setDirty() } }
    override fun onUp(x: Float, y: Float, ctx: ToolContext) {
        if (!active) return
        active = false
        if (kotlin.math.abs(cur.x - start.x) < 2f || kotlin.math.abs(cur.y - start.y) < 2f) return
        ctx.ensureActiveLayerForCommit()
        val style = StrokeStyle(settings.color, settings.width, settings.opacity, settings.filled, settings.fillColor)
        val content = ctx.activeVectorContent()
        if (content is VectorContent) {
            addShape(style, content)
            ctx.commit(label)
        }
    }
    protected abstract fun addShape(style: StrokeStyle, vector: VectorContent)
}

class RectangleTool(s: BrushSettings) : RectBase("rect", "Rectangle", "▭", s) {
    override fun addShape(style: StrokeStyle, vector: VectorContent) {
        vector.shapes.add(
            RectShape(style, minOf(start.x, cur.x), minOf(start.y, cur.y), absW(), absH()))
    }
    override fun drawPreview(canvas: Canvas) {
        if (!active) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (settings.filled) { p.style = Paint.Style.FILL; p.color = settings.fillColor }
        else { p.style = Paint.Style.STROKE; p.color = settings.color; p.strokeWidth = settings.width }
        canvas.drawRect(minOf(start.x, cur.x), minOf(start.y, cur.y),
            maxOf(start.x, cur.x), maxOf(start.y, cur.y), p)
    }
    fun absW() = kotlin.math.abs(cur.x - start.x)
    fun absH() = kotlin.math.abs(cur.y - start.y)
}

class EllipseTool(s: BrushSettings) : RectBase("ellipse", "Ellipse", "◯", s) {
    override fun addShape(style: StrokeStyle, vector: VectorContent) {
        vector.shapes.add(
            EllipseShape(style, minOf(start.x, cur.x), minOf(start.y, cur.y),
                kotlin.math.abs(cur.x - start.x), kotlin.math.abs(cur.y - start.y)))
    }
    override fun drawPreview(canvas: Canvas) {
        if (!active) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (settings.filled) { p.style = Paint.Style.FILL; p.color = settings.fillColor }
        else { p.style = Paint.Style.STROKE; p.color = settings.color; p.strokeWidth = settings.width }
        canvas.drawOval(minOf(start.x, cur.x), minOf(start.y, cur.y),
            maxOf(start.x, cur.x), maxOf(start.y, cur.y), p)
    }
}
