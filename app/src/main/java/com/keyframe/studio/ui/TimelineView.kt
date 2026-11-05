package com.keyframe.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import com.keyframe.studio.engine.Document
import kotlin.math.floor

/**
 * Professional layer x frame grid. See UI doc for layout rules:
 * playhead line, keyframe diamonds, tween arrows, current-frame highlight.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var doc: Document? = null
    var frameWidth = 56f
    var frameHeight = 68f
    var onDocChanged: (() -> Unit)? = null // request full redraw of stage
    var callbacks: Callbacks? = null

    interface Callbacks {
        fun onFrameSelected(frame: Int)
        fun onLayerSelected(l: com.keyframe.studio.engine.Layer)
    }

    // ---- colors ----
    private val railBg = Color.rgb(26, 26, 28)
    private val colBg = Color.rgb(31, 31, 34)
    private val colAlt = Color.rgb(34, 34, 37)
    private val playheadColor = Color.rgb(74, 158, 218)
    private val keyColor = Color.rgb(74, 158, 218)
    private val currentNode = Color.rgb(52, 52, 58)

    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = railBg }
    private val cellPaint = Paint()
    private val playheadPaint = Paint().apply { color = playheadColor; strokeWidth = 2.5f }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = keyColor }
    private val ghostKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 70, 78) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 205); textSize = 34f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 156); textSize = 26f
    }

    var scrollX = 0f
    private fun maxScroll(): Float {
        val d = doc ?: return 0f
        val visible = (width - layerW) / frameWidth
        return ((d.frameCount - visible) * frameWidth).coerceAtLeast(0f)
    }
    private fun maxScrollY(): Float {
        val d = doc ?: return 0f
        return ((d.layers.size * frameHeight) - (height - rulerH)).coerceAtLeast(0f)
    }

    // visual geometry
    val layerW = 210f
    val rulerH = 64f

    private val gesture = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollX = (scrollX + dx).coerceIn(0f, maxScroll())
            scrollY = (scrollY + dy).coerceIn(0f, maxScrollY())
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val d = doc ?: return
        val gx = x - layerW + scrollX
        val gy = y - rulerH + scrollY
        if (y < rulerH && gx >= 0f) {
            // ruler tap = scrub
            val f = floor(gx / frameWidth).toInt().coerceIn(0, d.frameCount - 1)
            d.currentFrame = f
            callbacks?.onFrameSelected(f)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            return
        }
        if (gx < 0f) {
            // layer names column: select layer
            val i = (gy / frameHeight).toInt().coerceIn(0, d.layers.size - 1)
            // rendered top->bottom reversed
            val layerIdx = d.layers.size - 1 - i
            val l = d.layers[layerIdx]
            d.activeLayerId = l.id
            callbacks?.onLayerSelected(l)
            invalidate()
        } else {
            // selecting frame also selects the row's layer
            val row = (gy / frameHeight).toInt().coerceIn(0, d.layers.size - 1)
            val layerIdx = d.layers.size - 1 - row
            if (layerIdx in d.layers.indices) {
                val l = d.layers[layerIdx]
                d.activeLayerId = l.id
                callbacks?.onLayerSelected(l)
            }
            val f = floor(gx / frameWidth).toInt().coerceIn(0, d.frameCount - 1)
            d.currentFrame = f
            callbacks?.onFrameSelected(f)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = doc ?: return
        canvas.drawColor(colBg)
        val startY = rulerH - scrollY

        // rows background (top = smallest layer index)
        for (i in d.layers.indices) {
            val row = i // draw top->bottom with layers reversed
            val top = startY + row * frameHeight
            val l = d.layers[d.layers.size - 1 - row]
            cellPaint.color = if (l.id == d.activeLayerId) Color.rgb(40, 40, 45) else
                if (row % 2 == 0) colAlt else colBg
            canvas.drawRect(0f, top, width.toFloat(), top + frameHeight, cellPaint)
            // separator
            cellPaint.color = Color.rgb(24, 24, 26)
            canvas.drawRect(0f, top + frameHeight - 1f, width.toFloat(), top + frameHeight, cellPaint)
            // layer label (left rail)
            val maxW = layerW - 20f
            var name = l.name
            while (textPaint.measureText(name) > maxW && name.length > 1) name = name.dropLast(1)
            textPaint.color = if (l.id == d.activeLayerId) Color.rgb(255, 255, 255) else Color.rgb(205, 205, 210)
            canvas.drawText(name, 20f, top + frameHeight / 2 + 10f, textPaint)
        }

        // ruler
        cellPaint.color = railBg
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH - scrollY, cellPaint)
        // ruler ticks every 5 frames
        smallText.textAlign = Paint.Align.CENTER
        val firstVisible = floor(scrollX / frameWidth).toInt()
        val lastVisible = floor((scrollX + width - layerW) / frameWidth).toInt().coerceAtMost(d.frameCount - 1)
        for (f in firstVisible..lastVisible) {
            val fx = layerW + f * frameWidth - scrollX
            val major = f % 5 == 0
            cellPaint.color = Color.rgb(70, 70, 76)
            canvas.drawRect(fx + frameWidth - 1f, (rulerH - scrollY) * 0.75f, fx + frameWidth, rulerH - scrollY, cellPaint)
            if (major && f > 0) {
                smallText.color = Color.rgb(160, 160, 166)
                canvas.drawText(f.toString(), fx + frameWidth / 2, rulerH - scrollY - 26f, smallText)
            }
        }

        // keyframes
        for (r in 0 until d.layers.size) {
            val l = d.layers[d.layers.size - 1 - r]
            val top = startY + r * frameHeight
            val cy = top + frameHeight / 2
            for ((idx, k) in l.keyframes) {
                val kx = layerW + idx * frameWidth - scrollX
                if (k.isBlank) {
                    keyPaint.color = Color.rgb(60, 60, 66)
                    keyPaint.style = Paint.Style.STROKE
                    keyPaint.strokeWidth = 2.5f
                } else {
                    keyPaint.color = if (k.tween) keyColor else Color.rgb(150, 150, 158)
                    keyPaint.style = Paint.Style.FILL
                }
                drawDiamond(canvas, kx, cy, keyPaint)
                keyPaint.style = Paint.Style.FILL
                // tween link
                if (k.tween) {
                    val nextIdx = l.keyframes.ceilingKey(idx + 1)
                    if (nextIdx != null) {
                        val nx = layerW + nextIdx * frameWidth - scrollX
                        cellPaint.color = Color.rgb(74, 158, 218)
                        cellPaint.alpha = 70
                        canvas.drawRect(kx + 8f, cy - 4f, nx - 8f, cy + 4f, cellPaint)
                        cellPaint.alpha = 255
                        cellPaint.color = Color.rgb(22, 22, 24)
                    }
                }
            }
            // hold markers (subtle fill between keys)
            val entries = l.keyframes.entries.toList()
            for (j in entries.indices) {
                val e = entries[j]
                val next = entries.getOrNull(j + 1)
                if (e.value.isBlank) continue
                val endIdx = (next?.key ?: d.frameCount) - 1
                val sIdx = e.key
                if (endIdx < sIdx) continue
                val x1 = layerW + sIdx * frameWidth - scrollX
                val x2 = layerW + endIdx * frameWidth - scrollX
                val spanEnd = minOf(x2, (layerW + d.frameCount * frameWidth - scrollX))
                if (spanEnd > layerW && x2 > layerW) {
                    cellPaint.color = Color.rgb(26, 26, 30)
                    val rowBgTop = top + frameHeight * 0.32f
                    if (sIdx != endIdx || l.kind == com.keyframe.studio.engine.LayerKind.RASTER) {
                        canvas.drawRect(
                            kotlin.math.max(x1, layerW), rowBgTop,
                            spanEnd, top + frameHeight * 0.68f, cellPaint
                        )
                    }
                }
            }
        }

        // current frame highlight column
        val cx = layerW + d.currentFrame * frameWidth - scrollX
        if (d.currentFrame in 0 until d.frameCount) {
            cellPaint.color = currentNode
            canvas.drawRect(cx, rulerH - scrollY, cx + frameWidth, (height - scrollY).toFloat(), cellPaint)
            playheadPaint.color = playheadColor
            playheadPaint.strokeWidth = 2.5f
            canvas.drawRect(cx + frameWidth / 2 - 1.25f, 0f, cx + frameWidth / 2 + 1.25f, (height - scrollY).toFloat(), playheadPaint)
            // ruler playhead handle
            keyPaint.color = playheadColor
            canvas.drawRect(cx, 0f, cx + frameWidth, rulerH - scrollY, cellPaint.apply { color = Color.rgb(46, 46, 52) })
        }

        // left rail separator
        cellPaint.color = Color.rgb(22, 22, 24)
        canvas.drawRect(layerW - 1f, 0f, layerW, height.toFloat(), cellPaint)
    }

    private fun drawDiamond(c: Canvas, x: Float, y: Float, p: Paint) {
        val r = frameWidth * 0.16f
        c.drawPath(
            android.graphics.Path().apply {
                moveTo(x, y - r)
                lineTo(x + r, y)
                lineTo(x, y + r)
                lineTo(x - r, y)
                close()
            }, p
        )
    }

    fun scrollToFrame(f: Int) {
        val visible = (width - layerW) / frameWidth
        scrollX = ((f - visible / 2) * frameWidth).coerceIn(0f, maxScroll())
        invalidate()
    }
}
