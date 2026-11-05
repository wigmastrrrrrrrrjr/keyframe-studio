package com.keyframe.studio.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Custom easing curve. u0..u3 map to cubic-bezier(0,0,x1,y1,1,1)-style control points. */
data class Easing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    companion object {
        val LINEAR = Easing(0f, 0f, 1f, 1f)
        val EASE_IN = Easing(0.42f, 0f, 1f, 1f)
        val EASE_OUT = Easing(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = Easing(0.42f, 0f, 0.58f, 1f)
        val presets = mapOf(
            "Linear" to LINEAR, "Ease In" to EASE_IN, "Ease Out" to EASE_OUT,
            "Ease In Out" to EASE_IN_OUT, "Fast Out" to Easing(0.1f, 0.9f, 0.2f, 1f),
            "Overshoot" to Easing(0.2f, 1.4f, 0.3f, 1f)
        )

        /** Solve bezier y for parameter t then interpolate x mapped progress. */
        fun evaluate(e: Easing, t: Float): Float {
            val n = 24
            var bestT = 0f
            var best = 1f
            // solve x(t) = t via binary-ish scan (cheap enough, called rarely)
            for (i in 0..n) {
                val tt = i / n.toFloat()
                val xx = bez(e.x1, e.x2, tt)
                val d = abs(xx - t)
                if (d < best) { best = d; bestT = tt }
            }
            return bez(e.y1, e.y2, bestT)
        }

        private fun bez(a: Float, b: Float, t: Float): Float {
            val it = 1f - t
            return 3 * it * it * t * a + 3 * it * t * t * b + t * t * t
        }
    }
}

/** 2D transform: translate, rotate(deg around pivot), scale. */
data class Transform(
    var tx: Float = 0f, var ty: Float = 0f,
    var rotation: Float = 0f, var scaleX: Float = 1f, var scaleY: Float = 1f,
    var pivotX: Float = 0f, var pivotY: Float = 0f
) {
    fun isIdentity() = tx == 0f && ty == 0f && rotation == 0f && scaleX == 1f && scaleY == 1f

    fun interpolate(b: Transform, t: Float): Transform = Transform(
        tx + (b.tx - tx) * t, ty + (b.ty - ty) * t,
        rotation + (b.rotation - rotation) * t,
        scaleX + (b.scaleX - scaleX) * t, scaleY + (b.scaleY - scaleY) * t,
        pivotX + (b.pivotX - pivotX) * t, pivotY + (b.pivotY - pivotY) * t
    )

    fun applyTo(canvas: Canvas) {
        if (isIdentity()) return
        canvas.translate(pivotX + tx, pivotY + ty)
        if (rotation != 0f) canvas.rotate(rotation)
        if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY)
        canvas.translate(-pivotX, -pivotY)
    }

    fun toJson() = JSONObject().put("tx", tx.toDouble()).put("ty", ty.toDouble())
        .put("r", rotation.toDouble()).put("sx", scaleX.toDouble()).put("sy", scaleY.toDouble())
        .put("px", pivotX.toDouble()).put("py", pivotY.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Transform(
            (o.optDouble("tx", 0.0)).toFloat(), (o.optDouble("ty", 0.0)).toFloat(),
            (o.optDouble("r", 0.0)).toFloat(), (o.optDouble("sx", 1.0)).toFloat(),
            (o.optDouble("sy", 1.0)).toFloat(), (o.optDouble("px", 0.0)).toFloat(),
            (o.optDouble("py", 0.0)).toFloat()
        )
    }
}

/** Stroke style shared by vector shapes. */
data class StrokeStyle(var color: Int, var width: Float, var opacity: Float, var filled: Boolean, var fillColor: Int) {
    fun toJson() = JSONObject().put("c", color).put("w", width.toDouble())
        .put("o", opacity.toDouble()).put("f", filled).put("fc", fillColor)
    companion object {
        fun fromJson(o: JSONObject) = StrokeStyle(
            o.optInt("c", -1), (o.optDouble("w", 6.0)).toFloat(),
            (o.optDouble("o", 1.0)).toFloat(), o.optBoolean("f", false), o.optInt("fc", -16777216)
        )
    }
}

/** One cubic bezier segment: start point + two controls + end point (all anchor-editable). */
data class CubicSeg(
    var sx: Float, var sy: Float,
    var c1x: Float, var c1y: Float,
    var c2x: Float, var c2y: Float,
    var ex: Float, var ey: Float
)

sealed class Shape {
    abstract var style: StrokeStyle
    open fun bounds(): RectF = RectF()
    open fun toJson(): JSONObject = JSONObject().put("type", javaClass.simpleName)
    open fun clone(): Shape = this
}

/** Smooth freehand / pen path built from cubic segments. */
class PathShape(override var style: StrokeStyle) : Shape() {
    val segments = mutableListOf<CubicSeg>()

    override fun clone(): PathShape {
        val s = PathShape(style.copy())
        s.segments.addAll(segments.map { it.copy() })
        return s
    }

    override fun bounds(): RectF {
        val r = RectF(1e9f, 1e9f, -1e9f, -1e9f)
        fun inc(x: Float, y: Float) { r.union(x, y) }
        segments.forEach { inc(it.sx, it.sy); inc(it.c1x, it.c1y); inc(it.c2x, it.c2y); inc(it.ex, it.ey) }
        if (r.width() <= 0 || r.height() <= 0) r.union(0f, 0f)
        return r
    }

    fun buildPath(): Path {
        val p = Path()
        if (segments.isEmpty()) return p
        val s0 = segments[0]
        p.moveTo(s0.sx, s0.sy)
        segments.forEach { p.cubicTo(it.c1x, it.c1y, it.c2x, it.c2y, it.ex, it.ey) }
        return p
    }

    /** Morph toward another path shape; requires same segment count (caller checks). */
    fun morph(other: PathShape, t: Float): PathShape {
        val out = clone()
        for (i in out.segments.indices) {
            val a = out.segments[i]; val b = (other.segments.getOrNull(i) ?: continue)
            a.c1x += (b.c1x - a.c1x) * t; a.c1y += (b.c1y - a.c1y) * t
            a.c2x += (b.c2x - a.c2x) * t; a.c2y += (b.c2y - a.c2y) * t
            a.ex += (b.ex - a.ex) * t; a.ey += (b.ey - a.ey) * t
        }
        return out
    }

    override fun toJson() = super.toJson().put("segs", JSONArray().apply {
        segments.forEach { put(JSONObject().put("s", JSONArray(listOf(it.sx, it.sy)))
            .put("c1", JSONArray(listOf(it.c1x, it.c1y)))
            .put("c2", JSONArray(listOf(it.c2x, it.c2y)))
            .put("e", JSONArray(listOf(it.ex, it.ey)))) }
    }).put("style", style.toJson())

    companion object {
        /** Convert raw input polyline to a smoothed path with Catmull-Rom cubic construction. */
        fun fromPoints(pts: List<android.graphics.PointF>, style: StrokeStyle): PathShape {
            val sh = PathShape(style.copy())
            if (pts.isEmpty()) return sh
            if (pts.size == 1) {
                val p = pts[0]
                sh.segments.add(CubicSeg(p.x, p.y, p.x, p.y, p.x, p.y, p.x, p.y))
                return sh
            }
            val n = pts.size
            for (i in 0 until n - 1) {
                val p0 = pts[max(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]
                val p3 = pts[min(n - 1, i + 2)]
                val c1x = p1.x + (p2.x - p0.x) / 6f
                val c1y = p1.y + (p2.y - p0.y) / 6f
                val c2x = p2.x - (p3.x - p1.x) / 6f
                val c2y = p2.y - (p3.y - p1.y) / 6f
                sh.segments.add(CubicSeg(p1.x, p1.y, c1x, c1y, c2x, c2y, p2.x, p2.y))
            }
            return sh
        }
    }
}

class RectShape(override var style: StrokeStyle, var x: Float = 0f, var y: Float = 0f,
                var w: Float = 0f, var h: Float = 0f) : Shape() {
    override fun bounds() = RectF(x, y, x + w, y + h)
    override fun clone() = RectShape(style.copy(), x, y, w, h)
    override fun toJson() = super.toJson().put("style", style.toJson())
        .put("g", JSONArray(listOf(x, y, w, h)))
}

class EllipseShape(override var style: StrokeStyle, var x: Float = 0f, var y: Float = 0f,
                   var w: Float = 0f, var h: Float = 0f) : Shape() {
    override fun bounds() = RectF(x, y, x + w, y + h)
    override fun clone() = EllipseShape(style.copy(), x, y, w, h)
    override fun toJson() = super.toJson().put("style", style.toJson())
        .put("g", JSONArray(listOf(x, y, w, h)))
}

class LineShape(override var style: StrokeStyle, var x1: Float = 0f, var y1: Float = 0f,
                var x2: Float = 0f, var y2: Float = 0f) : Shape() {
    override fun bounds() = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    override fun clone() = LineShape(style.copy(), x1, y1, x2, y2)
    override fun toJson() = super.toJson().put("style", style.toJson())
        .put("g", JSONArray(listOf(x1, y1, x2, y2)))
}

/** Editable text item. */
class TextShape(override var style: StrokeStyle, var x: Float = 0f, var y: Float = 0f,
                var text: String = "", var size: Float = 48f) : Shape() {
    override fun clone() = TextShape(style.copy(), x, y, text, size)
    override fun bounds(): RectF {
        val m = android.graphics.Paint().apply { textSize = size }
        val r = android.graphics.Rect()
        m.getTextBounds(text, 0, text.length, r)
        return RectF(x, y + r.top, x + m.measureText(text), y + r.bottom)
    }
    override fun toJson() = super.toJson().put("style", style.toJson())
        .put("t", text).put("x", x.toDouble()).put("y", y.toDouble()).put("s", size.toDouble())

    companion object {
        fun fromJson(o: JSONObject, st: StrokeStyle) =
            TextShape(st, (o.optDouble("x", 0.0)).toFloat(), (o.optDouble("y", 0.0)).toFloat(),
                o.optString("t", ""), (o.optDouble("s", 48.0)).toFloat())
    }
}

/** Per-layer content at a frame. */
sealed class Content {
    abstract fun render(canvas: Canvas, layerOpacity: Float)
    abstract fun toJson(): JSONObject?
    abstract fun clone(): Content
    open fun transformAll(t: Transform) {}

    fun morphTo(b: Content?, t: Float): Content = this
}

class RasterContent(val frameW: Int, val frameH: Int) : Content() {
    lateinit var bitmap: Bitmap

    override fun render(canvas: Canvas, layerOpacity: Float) {
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (layerOpacity * 255).toInt()
        canvas.drawBitmap(bitmap, 0f, 0f, p)
    }

    override fun toJson(): JSONObject? = null // raster handled via external file ref by ProjectIO

    override fun clone(): RasterContent {
        val c = RasterContent(frameW, frameH)
        val b = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        Canvas(b).drawBitmap(bitmap, 0f, 0f, null)
        c.bitmap = b
        return c
    }
}

class VectorContent : Content() {
    val shapes = mutableListOf<Shape>()
    var transform = Transform()

    override fun render(canvas: Canvas, layerOpacity: Float) {
        val save = canvas.save()
        transform.applyTo(canvas)
        for (s in shapes) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.alpha = (layerOpacity * s.style.opacity.coerceIn(0f, 1f) * 255).toInt()
            when (s) {
                is PathShape -> {
                    p.style = if (s.style.filled) Paint.Style.FILL else Paint.Style.STROKE
                    p.strokeWidth = if (s.style.filled) 0f else s.style.width
                    p.color = s.style.color
                    canvas.drawPath(s.buildPath(), p)
                }
                is RectShape -> {
                    p.style = if (s.style.filled) Paint.Style.FILL else Paint.Style.STROKE
                    p.strokeWidth = if (s.style.filled) 0f else s.style.width
                    p.color = if (s.style.filled) s.style.fillColor else s.style.color
                    canvas.drawRect(s.x, s.y, s.x + s.w, s.y + s.h, p)
                }
                is EllipseShape -> {
                    p.style = if (s.style.filled) Paint.Style.FILL else Paint.Style.STROKE
                    p.strokeWidth = if (s.style.filled) 0f else s.style.width
                    p.color = if (s.style.filled) s.style.fillColor else s.style.color
                    canvas.drawOval(s.x, s.y, s.x + s.w, s.y + s.h, p)
                }
                is LineShape -> {
                    p.style = Paint.Style.STROKE
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeWidth = s.style.width
                    p.color = s.style.color
                    canvas.drawLine(s.x1, s.y1, s.x2, s.y2, p)
                }
                is TextShape -> {
                    p.style = Paint.Style.FILL
                    p.color = s.style.color
                    p.textSize = s.size
                    canvas.drawText(s.text, s.x, s.y, p)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    override fun clone(): VectorContent {
        val c = VectorContent()
        c.shapes.addAll(shapes.map { it.clone() })
        c.transform = transform.copy()
        return c
    }

    override fun transformAll(t: Transform) {
        transform = t
    }

    override fun toJson(): JSONObject {
        val o = JSONObject().put("shapes", JSONArray())
        shapes.forEach { o.getJSONArray("shapes").put(it.toJson()) }
        if (!transform.isIdentity()) o.put("tr", transform.toJson())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VectorContent {
            val v = VectorContent()
            val arr = o.optJSONArray("shapes") ?: return v
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val st = s.optJSONObject("style")?.let { StrokeStyle.fromJson(it) }
                    ?: StrokeStyle(-16777216, 6f, 1f, false, -16777216)
                when (s.optString("type")) {
                    "PathShape" -> {
                        val sh = PathShape(st)
                        val segs = s.optJSONArray("segs") ?: continue
                        for (j in 0 until segs.length()) {
                            val sg = segs.getJSONObject(j)
                            sh.segments.add(CubicSeg(
                                sg.getJSONArray("s").getDouble(0).toFloat(), sg.getJSONArray("s").getDouble(1).toFloat(),
                                sg.getJSONArray("c1").getDouble(0).toFloat(), sg.getJSONArray("c1").getDouble(1).toFloat(),
                                sg.getJSONArray("c2").getDouble(0).toFloat(), sg.getJSONArray("c2").getDouble(1).toFloat(),
                                sg.getJSONArray("e").getDouble(0).toFloat(), sg.getJSONArray("e").getDouble(1).toFloat()))
                        }
                        v.shapes.add(sh)
                    }
                    "RectShape" -> { val g = s.getJSONArray("g"); v.shapes.add(RectShape(st, g.getDouble(0).toFloat(), g.getDouble(1).toFloat(), g.getDouble(2).toFloat(), g.getDouble(3).toFloat())) }
                    "EllipseShape" -> { val g = s.getJSONArray("g"); v.shapes.add(EllipseShape(st, g.getDouble(0).toFloat(), g.getDouble(1).toFloat(), g.getDouble(2).toFloat(), g.getDouble(3).toFloat())) }
                    "LineShape" -> { val g = s.getJSONArray("g"); v.shapes.add(LineShape(st, g.getDouble(0).toFloat(), g.getDouble(1).toFloat(), g.getDouble(2).toFloat(), g.getDouble(3).toFloat())) }
                    "TextShape" -> v.shapes.add(TextShape.fromJson(s, st))
                }
            }
            o.optJSONObject("tr")?.let { v.transform = Transform.fromJson(it) }
            return v
        }
    }
}
