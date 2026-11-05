package com.keyframe.studio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.keyframe.studio.engine.Document

/** Onion skin configuration. */
data class OnionSettings(
    var enabled: Boolean = false,
    var range: Int = 2,
    var beforeOpacity: Float = 0.4f,
    var afterOpacity: Float = 0.3f
)

/**
 * Draws the stage with onion-skin ghosts and selection overlays.
 * Canvas matrix (pan/zoom) must already be applied by the caller.
 */
class WorkspaceRenderer(val doc: Document) {

    private val stagePaint = Paint().apply { color = Color.WHITE }

    /** Full-frame composite (used for exports and eyedropper sampling). */
    fun renderFrame(frame: Int, transparent: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (!transparent) c.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), stagePaint)
        for (i in doc.layers.indices.reversed()) doc.layers[i].renderFrame(c, frame)
        return bmp
    }

    /** Draw the stage with all layers. */
    fun drawStage(canvas: Canvas, frame: Int, onion: OnionSettings?) {
        canvas.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), stagePaint)
        if (onion != null && onion.enabled) drawOnion(canvas, frame, onion)
        for (i in doc.layers.indices.reversed()) {
            doc.layers[i].renderFrame(canvas, frame)
        }
    }

    private fun drawOnion(canvas: Canvas, frame: Int, o: OnionSettings) {
        // before ghost frames, blue-tinted, from most recent backwards
        for (d in o.range downTo 1) {
            drawGhost(canvas, frame - d, o.beforeOpacity * fall(d, o.range), GHOST_BEFORE)
        }
        for (d in 1..o.range) {
            drawGhost(canvas, frame + d, o.afterOpacity * fall(d, o.range), GHOST_AFTER)
        }
    }

    private fun fall(d: Int, range: Int): Float = 1f - (d - 1) / range.toFloat() * 0.5f

    private fun drawGhost(canvas: Canvas, f: Int, alpha: Float, tint: Int) {
        if (alpha <= 0.03f || f < 0 || f >= doc.frameCount) return
        val save = canvas.saveLayer(null, null)
        for (i in doc.layers.indices.reversed()) doc.layers[i].renderFrame(canvas, f)
        val p = Paint()
        p.color = tint
        p.alpha = (alpha * 255).toInt()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ATOP)
        canvas.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), p)
        canvas.restoreToCount(save)
    }

    /** Selection bounds box + Bézier anchors for a single selected path. */
    fun drawSelectionOverlay(canvas: Canvas, bounds: RectF?, path: com.keyframe.studio.engine.PathShape?) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = ACCENT
        }
        bounds?.let { canvas.drawRect(it, p) }
        if (path != null) {
            val anchorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val anchorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT
                style = Paint.Style.FILL
            }
            path.segments.forEach {
                canvas.drawCircle(it.sx, it.sy, 5f, anchorRing)
                canvas.drawCircle(it.sx, it.sy, 5f, anchorFill)
                canvas.drawCircle(it.ex, it.ey, 5f, anchorRing)
                canvas.drawCircle(it.ex, it.ey, 5f, anchorFill)
            }
        }
    }

    companion object {
        private val ACCENT = Color.rgb(74, 158, 218)
        private val WHITE = Color.WHITE
        private val GHOST_BEFORE = Color.rgb(70, 130, 210)
        private val GHOST_AFTER = Color.rgb(215, 90, 130)
    }
}
