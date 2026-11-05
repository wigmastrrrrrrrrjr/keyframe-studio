package com.keyframe.studio.history

import kotlin.properties.Delegates

/** Snapshot-based undo stack: caller supplies undo/redo lambdas taken at action boundaries. */
class History(private val onChange: (Boolean) -> Unit) {
    private class Entry(val name: String, val undo: () -> Unit, val redo: () -> Unit)
    private val stack = mutableListOf<Entry>()
    private var index = -1

    val canUndo: Boolean get() = index >= 0
    val canRedo: Boolean get() = index < stack.size - 1
    val size: Int get() = stack.size

    fun push(name: String, undo: () -> Unit, redo: () -> Unit) {
        while (stack.size > index + 1) stack.removeAt(stack.size - 1)
        stack.add(Entry(name, undo, redo))
        if (stack.size > MAX) stack.removeAt(0)
        index = stack.size - 1
        onChange(true)
    }

    fun undo() {
        if (canUndo) { stack[index].undo(); index--; onChange(false) }
    }

    fun redo() {
        if (canRedo) { index++; stack[index].redo(); onChange(false) }
    }

    companion object { const val MAX = 60 }
}
