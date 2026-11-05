package com.keyframe.studio.tools

/**
 * Tool input contract. Coordinates are in document space (already un-transformed by pan/zoom).
 */
interface ToolContext {
    /** mutable vector content of active layer at the current keyframe */
    fun activeVectorContent(): com.keyframe.studio.engine.VectorContent?
    fun activeRasterCanvas(): android.graphics.Canvas?
    fun ensureActiveLayerForCommit()
    fun setDirty()                      // request redraw
    fun commit(label: String)           // finalize + push undo record
}

sealed class EditMode {
    object None : EditMode()
}
