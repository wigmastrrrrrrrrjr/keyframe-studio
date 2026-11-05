package com.keyframe.studio.project

import android.graphics.Bitmap
import android.graphics.Canvas
import com.keyframe.studio.engine.Document
import com.keyframe.studio.engine.Easing
import com.keyframe.studio.engine.Keyframe
import com.keyframe.studio.engine.Layer
import com.keyframe.studio.engine.LayerKind
import com.keyframe.studio.engine.RasterContent
import com.keyframe.studio.engine.VectorContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Native editable `.kfproj` folder format:
 *   project.json            document + timeline data
 *   raster/<uuid>.png       rasterized frame bitmaps (referenced by uuid)
 */
object ProjectIO {

    /** layerId:frame -> raster filename, populated on save/load. */
    private val rasterRefs = mutableMapOf<String, String>()

    private fun resetRefs() = rasterRefs.clear()

    fun rasterKey(layerId: String, frame: Int) = "$layerId:$frame"

    // ---------------------------------------------------------------------
    // Save / load
    // ---------------------------------------------------------------------

    fun save(doc: Document, dir: File) {
        val rasterDir = File(dir, "raster").apply { mkdirs() }
        val root = JSONObject()
            .put("name", doc.projectName)
            .put("w", doc.width)
            .put("h", doc.height)
            .put("fps", doc.fps)
            .put("frames", doc.frameCount)
            .put("activeLayer", doc.activeLayerId)
        val layers = JSONArray()
        for (l in doc.layers) {
            val lo = JSONObject()
                .put("id", l.id)
                .put("name", l.name)
                .put("kind", if (l.kind == LayerKind.RASTER) "raster" else "vector")
                .put("visible", l.visible)
                .put("locked", l.locked)
                .put("opacity", l.opacity.toDouble())
                .put("keys", JSONArray())
            for ((idx, k) in l.keyframes) {
                val ka = JSONArray().put(idx).put(k.isBlank).put(k.tween)
                ka.put(JSONArray().also { e ->
                    e.put(k.easing.x1.toDouble()); e.put(k.easing.y1.toDouble())
                    e.put(k.easing.x2.toDouble()); e.put(k.easing.y2.toDouble())
                })
                when (val c = k.content) {
                    is VectorContent -> ka.put(c.toJson())
                    is RasterContent -> {
                        val key = rasterKey(l.id, idx)
                        val uuid = rasterRefs[key] ?: UUID.randomUUID().toString()
                        rasterRefs[key] = uuid
                        val f = File(rasterDir, "$uuid.png")
                        f.outputStream().use { c.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        ka.put("$uuid.png")
                    }
                }
                lo.getJSONArray("keys").put(ka)
            }
            layers.put(lo)
        }
        root.put("layers", layers)
        File(dir, "project.json").writeText(root.toString(2))
        // prune unreferenced raster images
        val referenced = rasterRefs.values.toSet()
        rasterDir.listFiles()?.forEach {
            if (it.isFile && it.extension == "png" && it.nameWithoutExtension !in referenced) it.delete()
        }
    }

    fun load(dir: File): Document {
        resetRefs()
        val root = JSONObject(File(dir, "project.json").readText())
        val doc = Document(root.getInt("w"), root.getInt("h"))
        doc.projectName = root.optString("name", "Untitled")
        doc.fps = root.optInt("fps", 24)
        doc.frameCount = root.optInt("frames", 96)
        doc.activeLayerId = root.optString("activeLayer")
        val layers = root.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val lo = layers.getJSONObject(i)
            val l = Layer(
                lo.getString("id"), lo.getString("name"),
                if (lo.optString("kind") == "raster") LayerKind.RASTER else LayerKind.VECTOR,
                doc.width, doc.height
            )
            l.visible = lo.optBoolean("visible", true)
            l.locked = lo.optBoolean("locked", false)
            l.opacity = lo.optDouble("opacity", 1.0).toFloat()
            val keys = lo.getJSONArray("keys")
            for (j in 0 until keys.length()) {
                val ka = keys.getJSONArray(j)
                val idx = ka.getInt(0)
                val blank = ka.optBoolean(1, false)
                val tween = ka.optBoolean(2, false)
                val easing = ka.optJSONArray(3)?.let {
                    Easing(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                        it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
                } ?: Easing.LINEAR
                val content: com.keyframe.studio.engine.Content = if (l.kind == LayerKind.RASTER) {
                    val fn = ka.optString(4)
                    val bmp = if (fn.isNotEmpty()) {
                        val f = File(dir, "raster/$fn")
                        val d = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        if (d != null && (d.width != doc.width || d.height != doc.height)) {
                            val normalized = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                            Canvas(normalized).drawBitmap(d, 0f, 0f, null)
                            d.recycle()
                            normalized
                        } else d
                    } else null
                    if (fn.isNotEmpty()) rasterRefs[rasterKey(l.id, idx)] = fn.substringBefore(".png")
                    RasterContent(doc.width, doc.height).also { rc ->
                        rc.bitmap = bmp ?: Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                    }
                } else {
                    ka.optJSONObject(4)?.let { VectorContent.fromJson(it) } ?: VectorContent()
                }
                l.keyframes[idx] = Keyframe(idx, content, blank, tween, easing)
            }
            doc.layers.add(l)
        }
        if (doc.layers.isNotEmpty() && doc.layers.none { it.id == doc.activeLayerId }) {
            doc.activeLayerId = doc.layers.last().id
        }
        return doc
    }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------

    fun exportPngJpg(bitmap: Bitmap, target: File, jpg: Boolean): File {
        target.outputStream().use {
            bitmap.compress(
                if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                if (jpg) 92 else 100, it
            )
        }
        return target
    }

    /** Render one frame (all layers, tweens applied) at doc resolution. */
    fun renderFrame(doc: Document, frame: Int, transparent: Boolean): Bitmap {
        val stage = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(stage)
        if (!transparent) c.drawColor(android.graphics.Color.WHITE)
        for (i in doc.layers.indices.reversed()) doc.layers[i].renderFrame(c, frame)
        return stage
    }

    fun renderFrameScaled(doc: Document, frame: Int, outW: Int, outH: Int, transparent: Boolean): Bitmap {
        val base = renderFrame(doc, frame, transparent)
        if (base.width == outW && base.height == outH) return base
        val scaled = Bitmap.createScaledBitmap(base, outW, outH, true)
        base.recycle()
        return scaled
    }

    /** Export vector content of one frame as SVG (strokes, fills, text). */
    fun exportSvg(doc: Document, frame: Int, file: File) {
        fun colorHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
        val sb = StringBuilder()
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${doc.width}\" " +
                "height=\"${doc.height}\" viewBox=\"0 0 ${doc.width} ${doc.height}\">\n"
        )
        for (l in doc.layers) {
            val content = l.frameAt(frame)?.content
            if (content !is VectorContent) continue
            if (!l.visible) continue
            sb.append("<g opacity=\"%.2f\">\n".format(l.opacity))
            for (sh in content.shapes) {
                val st = sh.style
                val stroke = colorHex(st.color)
                val fill = colorHex(st.fillColor)
                when (sh) {
                    is com.keyframe.studio.engine.PathShape -> {
                        if (sh.segments.isEmpty()) continue
                        val d = StringBuilder()
                        sh.segments.forEach {
                            d.append("M ${it.sx} ${it.sy} C ${it.c1x} ${it.c1y} ${it.c2x} ${it.c2y} ${it.ex} ${it.ey} ")
                        }
                        if (st.filled) sb.append("<path d=\"$d\" fill=\"$fill\"/>\n")
                        else sb.append(
                            "<path d=\"$d\" fill=\"none\" stroke=\"$stroke\" " +
                                "stroke-opacity=\"%.2f\" stroke-width=\"${st.width}\" stroke-linecap=\"round\"/>\n"
                                .format(st.opacity)
                        )
                    }
                    is com.keyframe.studio.engine.RectShape ->
                        sb.append(
                            "<rect x=\"${sh.x}\" y=\"${sh.y}\" width=\"${sh.w}\" height=\"${sh.h}\" " +
                                "fill=\"${if (st.filled) fill else "none"}\" " +
                                (if (!st.filled) "stroke=\"$stroke\" stroke-width=\"${st.width}\"" else "") + "/>\n"
                        )
                    is com.keyframe.studio.engine.EllipseShape ->
                        sb.append(
                            "<ellipse cx=\"${sh.x + sh.w / 2}\" cy=\"${sh.y + sh.h / 2}\" " +
                                "rx=\"${sh.w / 2}\" ry=\"${sh.h / 2}\" " +
                                "fill=\"${if (st.filled) fill else "none"}\" " +
                                (if (!st.filled) "stroke=\"$stroke\" stroke-width=\"${st.width}\"" else "") + "/>\n"
                        )
                    is com.keyframe.studio.engine.LineShape ->
                        sb.append(
                            "<line x1=\"${sh.x1}\" y1=\"${sh.y1}\" x2=\"${sh.x2}\" y2=\"${sh.y2}\" " +
                                "stroke=\"$stroke\" stroke-width=\"${st.width}\" stroke-linecap=\"round\"/>\n"
                        )
                    is com.keyframe.studio.engine.TextShape ->
                        sb.append(
                            "<text x=\"${sh.x}\" y=\"${sh.y}\" font-size=\"${sh.size}\" fill=\"$stroke\">" +
                                escaped(sh.text) + "</text>\n"
                        )
                }
            }
            sb.append("</g>\n")
        }
        sb.append("</svg>")
        file.writeText(sb.toString())
    }

    private fun escaped(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Import raster image (PNG/JPG) at native resolution. */
    fun importBitmap(f: File): Bitmap =
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
