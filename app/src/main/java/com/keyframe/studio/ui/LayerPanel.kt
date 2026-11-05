package com.keyframe.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import com.keyframe.studio.engine.Document
import com.keyframe.studio.engine.Layer

/**
 * Vertical layers panel: thumbnails ordering on the left of the
 * timeline. Row-tap selects layer; long-press shows ordering handles.
 */
class LayerPanel @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var doc: Document? = null
    var rowHeight = 76f
    var callbacks: Callbacks? = null
    private var selectedRow = -1
    private var dragFrom = -1
    private var dragTo = -1

    interface Callbacks {
        fun onLayerSelected(l: Layer)
        fun onLayerReordered(from: Int, to: Int)
        fun onVisibleToggle(l: Layer)
        fun onLockToggle(l: Layer)
    }

    private val bgColor = Color.rgb(32, 32, 35)
    private val rowColor = Color.rgb(40, 40, 44)
    private val selColor = Color.rgb(56, 56, 62)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 228)
        textSize = 32f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 156)
        textSize = 24f
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 205); textSize = 34f }

    override fun onDraw(c: Canvas) {
        val d = doc ?: return
        c.drawColor(bgColor)
        c.translate(0f, scrollY)
        val w = width.toFloat()
        for (i in d.layers.indices) {
            val l = d.layers[i]
            val top = (d.layers.size - 1 - i) * rowHeight
            c.drawRect(0f, top, w, top + rowHeight, Paint().apply { color = if (i == selectedRow) selColor else rowColor })
            if (i < d.layers.size - 1) {
                c.drawRect(0f, top + rowHeight - 1, w, top + rowHeight, Paint().apply { color = Color.rgb(22, 22, 24) })
            }
            // visibility eye
            eyePaint.color = if (l.visible) Color.rgb(210, 210, 215) else Color.rgb(90, 90, 96)
            c.drawText(if (l.visible) "◉" else "○", 28f, top + rowHeight / 2 + 12f, eyePaint)
            // lock
            eyePaint.color = if (l.locked) Color.rgb(210, 210, 215) else Color.rgb(90, 90, 96)
            c.drawText(if (l.locked) "▣" else "▢", 130f, top + rowHeight / 2 + 12f, eyePaint)
            // name + kind badge
            textPaint.color = if (l.id == d.activeLayerId) Color.rgb(255, 255, 255) else Color.rgb(210, 210, 214)
            c.drawText(l.name, 224f, top + 44f, textPaint)
            smallPaint.color = Color.rgb(140, 140, 146)
            c.drawText(if (l.kind == com.keyframe.studio.engine.LayerKind.RASTER) "RASTER" else "VECTOR",
                224f, top + rowHeight - 16f, smallPaint)
        }
    }

    private fun rowAt(y: Float): Int {
        val d = doc ?: return -1
        // layers rendered top->bottom reversed
        return (d.layers.size - 1 - ((y - scrollY) / rowHeight).toInt())
    }

    private val gesture = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val d = doc ?: return false
            val max = (d.layers.size * rowHeight - height).coerceAtLeast(0f)
            scrollY = (scrollY + dy).coerceIn(-max, 0f)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val d = doc ?: return false
            val idx = rowAt(e.y)
            if (idx !in d.layers.indices) return false
            val x = e.x
            val top = (d.layers.size - 1 - idx) * rowHeight + scrollY
            val cy = top + rowHeight / 2
            val l = d.layers[idx]
            when {
                cy - e.y in -44f..44f && x < 190f -> {
                    // eye/lock zone: split columns
                    if (x < 190f && x >= 100f) callbacks?.onLockToggle(l)
                    else callbacks?.onVisibleToggle(l)
                }
                else -> {
                    d.activeLayerId = l.id
                    callbacks?.onLayerSelected(l)
                }
            }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            return true
        }
    })

    var scrollY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        return true
    }
}
