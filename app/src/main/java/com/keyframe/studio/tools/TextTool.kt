package com.keyframe.studio.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import com.keyframe.studio.engine.TextShape
import com.keyframe.studio.engine.VectorContent

/** Text tool: tap position -> placeholder text block, edited via callback dialog in CanvasView/MainActivity. */
class TextTool(
    val getText: (onOk: (String) -> Unit) -> Unit,
    var settings: BrushSettings,
    var lastText: String = "Text"
) : Tool("text", "Text", "Ｔ") {
    override val isPersistent = false

    override fun onDown(x: Float, y: Float, pressure: Float, ctx: ToolContext) {
        getText { s ->
            if (s.isNotBlank()) {
                lastText = s
                ctx.ensureActiveLayerForCommit()
                val vc = ctx.activeVectorContent()
                if (vc is VectorContent) {
                    vc.shapes.add(TextShape(
                        com.keyframe.studio.engine.StrokeStyle(settings.color, 1f, settings.opacity, false, settings.fillColor),
                        x, y, s, settings.width * 6f))
                    ctx.commit("Add text")
                }
                ctx.setDirty()
            }
        }
    }
}
