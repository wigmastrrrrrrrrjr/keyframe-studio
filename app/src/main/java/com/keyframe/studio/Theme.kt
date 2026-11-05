package com.keyframe.studio

import android.content.Context

/** Shared UI metrics: dark neutral palette; matches the design system doc. */
object Theme {
    const val BG = 0xFF1B1B1D.toInt()
    const val SURFACE = 0xFF232326.toInt()
    const val SURFACE2 = 0xFF2A2A2E.toInt()
    const val SEPARATOR = 0xFF151517.toInt()
    const val TEXT = 0xFFE1E1E4.toInt()
    const val TEXT_DIM = 0xFF96969C.toInt()
    const val ACCENT = 0xFF4A9EDA.toInt()
    const val ACCENT_DIM = 0xFF3A5F78.toInt()
}

/** Density helper. */
fun Context.dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
