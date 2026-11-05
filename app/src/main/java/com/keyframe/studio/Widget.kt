package com.keyframe.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Icon tool button used in toolbar. */
@SuppressLint("ViewConstructor")
class ToolButton(context: Context, val icon: String, val label: String) :
    LinearLayout(context) {

    private val iconView = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        iconView.text = icon
        iconView.textSize = 20f
        iconView.gravity = Gravity.CENTER
        addView(iconView)
        val labelView = TextView(context).apply {
            text = label
            setTextColor(Theme.TEXT_DIM)
            textSize = 9f
            gravity = Gravity.CENTER
        }
        addView(labelView)
        isClickable = true
        background = GradientDrawable().apply { setColor(0x00000000) }
        setDeselected()
    }

    fun setSelected() = applyState(true)
    fun setDeselected() = applyState(false)

    private fun applyState(sel: Boolean) {
        iconView.setTextColor(if (sel) Theme.ACCENT else Theme.TEXT)
        (background as? GradientDrawable)?.setColor(
            if (sel) 0x244A9EDA else 0x00000000
        )
    }
}

/** Small square icon chip used in transport bars. */
class Chip(context: Context, icon: String, accent: Boolean = false) : TextView(context) {
    init {
        text = icon
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(if (accent) Theme.ACCENT else Theme.TEXT)
        background = GradientDrawable().apply {
            setColor(Theme.SURFACE)
            cornerRadius = context.dip(8).toFloat()
        }
    }

    fun setAccent(on: Boolean) {
        setTextColor(if (on) Theme.ACCENT else Theme.TEXT)
        (background as? GradientDrawable)?.setColor(if (on) Theme.SURFACE2 else Theme.SURFACE)
    }
}

fun dividerV(context: Context, view: LinearLayout, size: Int = 1) {
    view.addView(View(context), LinearLayout.LayoutParams(context.dip(size), ViewGroup.LayoutParams.MATCH_PARENT))
}
