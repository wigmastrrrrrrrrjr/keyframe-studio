package com.keyframe.studio.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject

enum class LayerKind { VECTOR, RASTER }

/** One keyframe: content + optional tween target + easing. */
class Keyframe(
    val index: Int,
    var content: Content,
    var isBlank: Boolean = false,
    var tween: Boolean = false,
    var easing: Easing = Easing.LINEAR
) {
    companion object {
        fun fromJson(o: JSONArray, content: Content?) = Keyframe(
            o.getInt(0), content ?: VectorContent(), o.optBoolean(1, false),
            o.optBoolean(2, false),
            o.optJSONArray(3)?.let {
                Easing(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                    it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
            } ?: Easing.LINEAR
        )
    }
}


/** A layer with a timeline of keyframes. Frames between keys hold the previous key's content. */
class Layer(
    val id: String,
    var name: String,
    val kind: LayerKind,
    val frameW: Int,
    val frameH: Int
) {
    var visible = true
    var locked = false
    var opacity = 1f
    var layerMask: RasterContent? = null
    val keyframes = sortedMapOf<Int, Keyframe>()

    fun frameAt(index: Int): Keyframe? = keyframes.floorEntry(index).takeIf { it.key <= index }?.value

    fun ensureKey(index: Int): Keyframe {
        keyframes.get(index)?.let { return it }
        val content: Content = if (kind == LayerKind.RASTER) {
            RasterContent(frameW, frameH).apply {
                bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            }
        } else VectorContent()
        val k = Keyframe(index, content)
        keyframes[index] = k
        return k
    }

    fun deleteKey(index: Int) { keyframes.remove(index) }

    fun blankKey(index: Int): Keyframe {
        val k = Keyframe(index, if (kind == LayerKind.RASTER)
            RasterContent(frameW, frameH).apply {
                bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            } else VectorContent(), isBlank = true)
        keyframes[index] = k
        return k
    }

    fun ensureRasterFor(index: Int): RasterContent {
        val k = keyframes.get(index)
        if (k != null && k.content is RasterContent) return k.content as RasterContent
        val rc = RasterContent(frameW, frameH).apply {
            bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            if (k != null && k.content is VectorContent) {
                // draw existing vector content into the new raster layer once
                Canvas(bitmap).let { (k.content as VectorContent).render(it, 1f) }
            }
        }
        if (k != null) k.content = rc else ensureKey(index).content = rc
        return rc
    }

    /** Render this layer at frame [index] into [canvas]. Handles tween interpolation. */
    fun renderFrame(canvas: Canvas, index: Int) {
        if (!visible || opacity <= 0.001f) return
        val prev = frameAt(index) ?: return
        if (!prev.tween || prev.isBlank) {
            if (prev.isBlank) return
            canvas.saveLayer(null, null)
            prev.content.render(canvas, opacity)
            canvas.restore()
            return
        }
        val next = keyframes.ceilingEntry(index) ?: return
        if (next.key <= prev.index) return
        val t = Easing.evaluate(prev.easing, (index - prev.index) / (next.key - prev.index).toFloat())
        val a = prev.content
        val b = next.value.content
        canvas.saveLayer(null, null)
        when {
            a is VectorContent && b is VectorContent -> {
                val morphable = a.shapes.size == 1 && b.shapes.size == 1 &&
                    a.shapes[0] is PathShape && b.shapes[0] is PathShape &&
                    (a.shapes[0] as PathShape).segments.size == (b.shapes[0] as PathShape).segments.size
                if (morphable) {
                    val morph = VectorContent().apply {
                        shapes.add((a.shapes[0] as PathShape).morph(b.shapes[0] as PathShape, t))
                    }
                    morph.render(canvas, opacity)
                } else {
                    val out = a.clone()
                    out.transform = a.transform.interpolate(b.transform, t)
                    out.render(canvas, opacity)
                }
            }
            else -> a.render(canvas, opacity)
        }
        canvas.restore()
    }
}

class Document(val width: Int = 1280, val height: Int = 720) {
    val layers = mutableListOf<Layer>()
    var fps = 24
    var frameCount = 96
    var activeLayerId = ""
    var currentFrame = 0
    var loop = true
    var playing = false
    var projectName = "Untitled"

    fun activeLayer(): Layer = layers.firstOrNull { it.id == activeLayerId } ?: layers.lastOrNull()!!

    companion object {
        fun newProject(w: Int = 1280, h: Int = 720): Document {
            val d = Document(w, h)
            val v = Layer(java.util.UUID.randomUUID().toString(), "Vector 1", LayerKind.VECTOR, w, h)
            val r = Layer(java.util.UUID.randomUUID().toString(), "Raster 1", LayerKind.RASTER, w, h)
            r.opacity = 1f
            v.ensureKey(0)
            r.ensureKey(0)
            d.layers.add(v)
            d.layers.add(r)
            d.activeLayerId = v.id
            return d
        }
    }
}

