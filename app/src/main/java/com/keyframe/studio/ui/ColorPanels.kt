package com.keyframe.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max

/** Saturation-value square + hue strip color picker. */
class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 1f
    private var value = 1f

    private val svPaint = Paint()
    private val huePaint = Paint()
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private var draggingHue = false

    private fun svStripTop() = max(62f, height - 62f)

    private fun rgb(h: Float, s: Float = 1f, v: Float = 1f): Int {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        return c
    }

    override fun onDraw(canvas: Canvas) {
        // SV square: hue base, then white->none plus none->black vertical gradient
        val squareBottom = svStripTop() - 20f
        svPaint.color = rgb(hue)
        canvas.drawRect(0f, 0f, width.toFloat(), squareBottom, svPaint)
        val steps = 24
        val rowH = squareBottom / steps
        for (row in 0 until steps) {
            val t = row / (steps - 1).toFloat()
            // white fading part
            svPaint.color = Color.argb((255 * (1 - t)).toInt(), 255, 255, 255)
            canvas.drawRect(0f, row * rowH, width.toFloat(), (row + 1) * rowH, svPaint)
            // black overlay increasing
            svPaint.color = Color.argb((255 * t).toInt(), 0, 0, 0)
            canvas.drawRect(0f, row * rowH, width.toFloat(), (row + 1) * rowH, svPaint)
        }
        // hue strip
        val seg = width / 24f
        for (i in 0..24) {
            huePaint.color = rgb(360f * i / 25f)
            canvas.drawRect(i * seg, svStripTop(), (i + 1) * seg, height.toFloat(), huePaint)
        }
        // cursors
        cursorFill.color = currentColor()
        canvas.translate(0f, 0f)
        canvas.drawCircle(width * sat, squareBottom * (1 - value), 14f, cursorFill)
        canvas.drawCircle(width * sat, squareBottom * (1 - value), 14f, cursor)
        canvas.drawCircle(width * hue / 360f, (svStripTop() + height) / 2f, 18f, cursorFill)
        canvas.drawCircle(width * hue / 360f, (svStripTop() + height) / 2f, 18f, cursor)
    }

    fun currentColor(): Int = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setColor(c: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            Color.red(c), Color.green(c), Color.blue(c), hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val stripTop = svStripTop()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) draggingHue = e.y > stripTop
        if (draggingHue) {
            hue = (e.x / max(1f, width.toFloat()) * 360f).coerceIn(0f, 359.9f)
        } else {
            val squareBottom = stripTop - 20f
            sat = (e.x / max(1f, width.toFloat())).coerceIn(0f, 1f)
            value = (1f - e.y / max(1f, squareBottom)).coerceIn(0f, 1f)
        }
        onColorChanged?.invoke(currentColor())
        invalidate()
        return true
    }
}

/** Simple color swatches row shown above the picker. */
class SwatchRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwatch: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        val colors = listOf(
            -1, -16777216, Color.rgb(230, 70, 70), Color.rgb(240, 140, 60), Color.rgb(235, 200, 70),
            Color.rgb(100, 200, 90), Color.rgb(70, 170, 230), Color.rgb(120, 90, 220), Color.rgb(200, 100, 190),
            Color.rgb(140, 140, 145), Color.rgb(90, 90, 95), Color.rgb(230, 190, 160)
        )
        colors.forEach { c ->
            addView(TextView(context).apply {
                val pad = 6
                setPadding(pad, pad, pad, pad)
                background = android.util.StateListDrawable().apply { addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = 10f }) }
                textSize = 28f
                gravity = Gravity.CENTER
                minWidth = 72
                minHeight = 72
                setOnClickListener { onSwatch?.invoke(c) }
            }, LinearLayout.LayoutParams(0, 96, 1f).apply { setMargins(0, 0, 0, 0) })
        }
    }
}

/** Reusable slider row: label + track. */
fun makeSlider(context: Context, label: String, from: Int, to: Int, initial: Int,
               onChange: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(context)
    row.orientation = HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val tv = TextView(context).apply {
        text = label
        setTextColor(Color.rgb(210, 210, 214))
        textSize = 15f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
        setPadding(8, 0, 0, 0)
    }
    val sb = SeekBar(context).apply {
        max = to - from
        progress = initial - from
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.65f)
        setPadding(12, 0, 12, 0)
    }
    val value = TextView(context).apply {
        setTextColor(Color.rgb(160, 160, 166))
        textSize = 13f
        minEms = 3
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(0, 0, 8, 0)
        text = initial.toString()
    }
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
            val v = p + from
            value.text = v.toString()
            onChange(v)
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    row.addView(tv)
    row.addView(sb)
    row.addView(value)
    return row
}
