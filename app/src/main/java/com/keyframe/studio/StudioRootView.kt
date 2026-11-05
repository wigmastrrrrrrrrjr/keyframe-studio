package com.keyframe.studio

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.keyframe.studio.engine.Document
import com.keyframe.studio.engine.Easing
import com.keyframe.studio.engine.Keyframe
import com.keyframe.studio.engine.Layer
import com.keyframe.studio.engine.LayerKind
import com.keyframe.studio.engine.RasterContent
import com.keyframe.studio.engine.VectorContent
import com.keyframe.studio.history.History
import com.keyframe.studio.project.Autosaver
import com.keyframe.studio.project.ProjectIO
import com.keyframe.studio.render.WorkspaceRenderer
import com.keyframe.studio.tools.BrushSettings
import com.keyframe.studio.tools.TextTool
import com.keyframe.studio.tools.Tool
import com.keyframe.studio.ui.CanvasView
import com.keyframe.studio.ui.ColorPickerView
import com.keyframe.studio.ui.LayerPanel
import com.keyframe.studio.ui.SwatchRow
import com.keyframe.studio.ui.TimelineView
import com.keyframe.studio.ui.makeSlider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top-level workspace composition: toolbar + canvas + layer/color panel +
 * transport + timeline. Rebuilds between portrait and landscape layouts.
 */
class StudioRootView(context: Context) : LinearLayout(context) {

    val doc: Document = restoreOrNewProject()
    private val handler = Handler(Looper.getMainLooper())
    private fun dip(v: Int) = context.dip(v)

    val history = History { refreshHistoryChips() }
    private val autosaver = Autosaver(File(context.filesDir, "project_keyframe"), { doc })

    private val canvasView = CanvasView(context)
    private val timelineView = TimelineView(context)
    private val layerPanel = LayerPanel(context)
    private val colorView = ColorPickerView(context)
    private val toolButtons = mutableMapOf<String, ToolButton>()
    private val propsLabel = TextView(context)
    private val playChip = Chip(context, "▶")
    private val loopChip = Chip(context, "↻")
    private val onionChip = Chip(context, "≈")
    private val fpsChip = Chip(context, "24 fps")
    private val frameIndicator = TextView(context)
    private val undoChip = Chip(context, "↶")
    private val redoChip = Chip(context, "↷")
    private val expandChip = Chip(context, "⤢")
    private var expandedTimeline = false

    // clip-board for keyframe content
    private var clipboardContent: com.keyframe.studio.engine.Content? = null

    // playback loop
    private val playTick = object : Runnable {
        override fun run() {
            if (!doc.playing) return
            val f = doc.currentFrame + 1
            if (f >= doc.frameCount) {
                if (doc.loop) doc.currentFrame = 0 else { stopPlayback(); return }
            } else {
                doc.currentFrame = f
            }
            canvasView.invalidateDoc()
            timelineView.invalidate()
            updateFrameIndicator()
            handler.postDelayed(this, 1000L / doc.fps.coerceAtLeast(1))
        }
    }

    init {
        wireModel()
        buildUi()
        post { canvasView.fitToScreen() }
        autosaver.start()
    }

    // ------------------------------------------------------------------
    // Public lifecycle hooks (called by MainActivity)
    // ------------------------------------------------------------------

    fun onConfigChanged() {
        buildUi()
        post { canvasView.fitToScreen() }
    }

    fun onAppPause() {
        autosaver.saveNow()
        stopPlayback()
    }

    fun onAppResume() = Unit

    fun onFilePicked(uri: Uri) {
        runCatching {
            val stream = context.contentResolver.openInputStream(uri)
            val bmp = android.graphics.BitmapFactory.decodeStream(stream)
            stream?.close()
            bmp ?: return
            addRasterLayerBitmap(bmp, "IMG " + SimpleDateFormat("HH:mm", Locale.US).format(Date()))
        }.onFailure {
            toast("Could not import file")
        }
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private fun wireModel() {
        canvasView.doc = doc
        canvasView.renderer = WorkspaceRenderer(doc)
        canvasView.history = history
        canvasView.onColorChanged = { c -> colorView.setColor(c) }
        canvasView.textTool = TextTool(
            { onOk ->
                textDialog("Add Text", canvasView.textTool?.lastText ?: "Text", onOk)
            },
            canvasView.brushSettings
        )

        timelineView.doc = doc
        timelineView.callbacks = object : TimelineView.Callbacks {
            override fun onFrameSelected(frame: Int) {
                canvasView.invalidateDoc()
                updateFrameIndicator()
                updateProps()
                layerPanel.invalidate()
            }

            override fun onLayerSelected(l: Layer) {
                canvasView.invalidateDoc()
                layerPanel.invalidate()
                updateProps()
            }
        }

        layerPanel.doc = doc
        layerPanel.callbacks = object : LayerPanel.Callbacks {
            override fun onLayerSelected(l: Layer) {
                updateProps()
                canvasView.invalidateDoc()
                timelineView.invalidate()
            }

            override fun onVisibleToggle(l: Layer) {
                l.visible = !l.visible
                refreshAll()
            }

            override fun onLockToggle(l: Layer) {
                l.locked = !l.locked
                refreshAll()
            }

            override fun onLayerReordered(from: Int, to: Int) {
                moveLayer(from, to)
            }
        }

        colorView.onColorChanged = { c -> canvasView.brushSettings.color = c }
    }

    private fun restoreOrNewProject(): Document {
        val probe = Autosaver(File(context.filesDir, "project_keyframe"), { null })
        val dir = probe.bestProjectDir()
        val json = File(dir, "project.json")
        return if (json.exists()) {
            try {
                ProjectIO.load(dir)
            } catch (e: Exception) {
                Document.newProject()
            }
        } else Document.newProject()
    }

    // ------------------------------------------------------------------
    // Layout building
    // ------------------------------------------------------------------

    private fun buildUi() {
        removeAllViews()
        toolButtons.clear()
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)

        if (expandedTimeline) {
            addView(buildExpandedTimeline())
            postRefresh()
            return
        }

        if (isLandscape()) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.addView(buildToolbarColumn(), LinearLayout.LayoutParams(dip(64), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(canvasView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(buildSidePanel(), LinearLayout.LayoutParams(dip(240), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildTransportBar())
            addView(timelineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(200)))
        } else {
            addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(62)))
            addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildTransportBar())
            addView(timelineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(216)))
        }
        postRefresh()
    }

    private fun isLandscape(): Boolean = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun postRefresh() {
        updateToolSelection()
        updateFrameIndicator()
        updateProps()
        refreshHistoryChips()
        loopChip.setAccent(doc.loop)
        onionChip.setAccent(canvasView.onion.enabled)
        layerPanel.post { layerPanel.invalidate() }
        timelineView.post { timelineView.invalidate() }
        canvasView.post { canvasView.invalidateDoc() }
    }

    private class ToolDef(val label: String, val icon: String, val tool: (StudioRootView) -> Tool)

    private val toolDefs = listOf(
        ToolDef("Select", "⬚", { it.canvasView.selectionTool }),
        ToolDef("Move", "✥", { it.canvasView.transformTool }),
        ToolDef("Pencil", "✎", { it.canvasView.brushTool }),
        ToolDef("Pen", "✒", { it.canvasView.penTool }),
        ToolDef("Eraser", "⌫", { it.canvasView.eraserTool }),
        ToolDef("Line", "／", { it.canvasView.lineTool }),
        ToolDef("Rect", "▭", { it.canvasView.rectTool }),
        ToolDef("Ellipse", "◯", { it.canvasView.ellipseTool }),
        ToolDef("Fill", "◧", { it.canvasView.fillTool }),
        ToolDef("Text", "Ｔ", { it.canvasView.textTool as Tool }),
        ToolDef("Eyedrop", "◉", { it.canvasView.pickerTool })
    )

    private fun addToolButton(row: LinearLayout, def: ToolDef, landscape: Boolean) {
        val btn = ToolButton(context, def.icon, def.label)
        btn.setOnClickListener { canvasView.setTool(def.tool(this)) }
        toolButtons[def.label] = btn
        val lp: LinearLayout.LayoutParams = if (landscape)
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(56))
        else LinearLayout.LayoutParams(dip(54), ViewGroup.LayoutParams.MATCH_PARENT)
        row.addView(btn, lp)
    }

    private fun buildToolbarColumn(): View {
        val scroll = ScrollView(context)
        scroll.setBackgroundColor(Theme.SURFACE)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        toolDefs.forEach { addToolButton(col, it, true) }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun buildTopBar(): View {
        val scroll = HorizontalScrollView(context)
        scroll.setBackgroundColor(Theme.SURFACE)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.setPadding(dip(2), 0, dip(2), 0)
        toolDefs.forEach { addToolButton(row, it, false) }
        scroll.addView(row)
        return scroll
    }

    private fun buildSidePanel(): View {
        val scroll = ScrollView(context)
        scroll.setBackgroundColor(Theme.SURFACE)
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        col.setPadding(dip(10), dip(8), dip(10), dip(10))

        col.addView(sectionTitle("COLOR"))
        col.addView(colorView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(190)))
        col.addView(swatches(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(30)))
        col.addView(gap(8))

        col.addView(sectionTitle("BRUSH"))
        col.addView(makeSlider(context, "Size", 1, 96, 8) { canvasView.brushSettings.width = it.toFloat() })
        col.addView(makeSlider(context, "Opacity", 5, 100, 100) { canvasView.brushSettings.opacity = it / 100f })
        col.addView(toggleText("Pressure") { on ->
            canvasView.brushSettings.pressureSensitive = on
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(30)))
        col.addView(gap(10))

        col.addView(sectionTitle("LAYER"))
        col.addView(layerPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(160)))
        col.addView(gap(6))
        col.addView(inlineChips(listOf(
            "＋V" to { addLayer(LayerKind.VECTOR) },
            "＋R" to { addLayer(LayerKind.RASTER) },
            "✕" to { deleteLayer() },
            "▲" to { nudgeLayer(-1) },
            "▼" to { nudgeLayer(1) }
        )))
        col.addView(gap(10))

        col.addView(sectionTitle("KEYFRAME"))
        col.addView(inlineChips(listOf(
            "＋K" to { insertKey(false) },
            "◇K" to { insertKey(true) },
            "✕K" to { deleteKey() },
            "◠T" to { toggleTween() },
            "⧉" to { copyFrame() },
            "⎘" to { pasteFrame() }
        )))
        col.addView(makeSlider(context, "Frames", 12, 288, doc.frameCount.coerceIn(12, 288)) {
            doc.frameCount = it
            timelineView.invalidate()
            updateFrameIndicator()
        })
        col.addView(gap(10))

        col.addView(sectionTitle("PROPS"))
        col.addView(propsLabel.apply {
            setTextColor(Theme.TEXT_DIM)
            textSize = 12f
            maxLines = 3
        })
        col.addView(gap(8))
        col.addView(inlineChips(listOf(
            "Import" to { pickImport() },
            "Export" to { exportDialog() }
        )))

        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun toggleText(labelName: String, flip: (Boolean) -> Boolean): TextView {
        var state = false
        val tv = TextView(context)
        tv.text = "$labelName: OFF"
        tv.setTextColor(Theme.TEXT_DIM)
        tv.textSize = 13f
        tv.setPadding(dip(10), 0, 0, dip(2))
        tv.setOnClickListener {
            state = !state
            flip(state)
            tv.text = "$labelName: ${if (state) "ON" else "OFF"}"
            tv.setTextColor(if (state) Theme.ACCENT else Theme.TEXT_DIM)
        }
        return tv
    }

    private fun swatches(): View {
        val row = SwatchRow(context)
        row.onSwatch = { c ->
            canvasView.brushSettings.color = c
            colorView.setColor(c)
        }
        return row
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        text = text
        setTextColor(Theme.TEXT_DIM)
        textSize = 10f
        letterSpacing = 0.12f
        setPadding(dip(2), dip(8), 0, dip(4))
    }

    private fun inlineChips(items: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        items.forEach { (label, act) ->
            row.addView(chipButton(Chip(context, label, label.length < 4), act))
        }
        return row
    }

    private fun gap(h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dip(h))
    }

    /** Wraps a chip with padding + click handler.
     * @param size chip square size in dp */
    private fun chipButton(chip: Chip, action: (() -> Unit)?, size: Int = 40): View {
        val wrap = FrameLayout(context)
        val lp = FrameLayout.LayoutParams(dip(size), dip(size))
        lp.setMargins(dip(2), 0, dip(2), 0)
        wrap.addView(chip, lp)
        wrap.setPadding(0, dip(1), 0, dip(1))
        if (action != null) {
            wrap.setOnClickListener { action() }
        }
        return wrap
    }

    private fun buildTransportBar(): View {
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        bar.setBackgroundColor(Theme.SURFACE)
        bar.setPadding(dip(6), dip(3), dip(6), dip(3))
        bar.gravity = Gravity.CENTER_VERTICAL

        playChip.setOnClickListener { togglePlay() }
        bar.addView(chipButton(playChip, null, 38))
        bar.addView(chipButton(loopChip) { doc.loop = !doc.loop; loopChip.setAccent(doc.loop) })
        bar.addView(chipButton(onionChip) {
            canvasView.onion.enabled = !canvasView.onion.enabled
            onionChip.setAccent(canvasView.onion.enabled)
        })
        bar.addView(chipButton(fpsChip) { fpsDialog() })
        bar.addView(chipButton(Chip(context, "－")) { canvasView.zoom(0.85f) })
        bar.addView(chipButton(Chip(context, "＋")) { canvasView.zoom(1.2f) })

        frameIndicator.setTextColor(Theme.TEXT)
        frameIndicator.textSize = 13f
        frameIndicator.typeface = android.graphics.Typeface.MONOSPACE
        frameIndicator.setPadding(dip(10), 0, dip(10), 0)
        bar.addView(frameIndicator)

        bar.addView(chipButton(undoChip) { history.undo() })
        bar.addView(chipButton(redoChip) { history.redo() })
        bar.addView(chipButton(expandChip) { toggleExpand() })
        bar.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return bar
    }

    private fun buildTimelineRow(): View = timelineView

    private fun buildExpandedTimeline(): View {
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        bar.setPadding(dip(8), dip(6), dip(8), dip(6))
        bar.setBackgroundColor(Theme.SURFACE)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(chipButton(Chip(context, "✕")) { toggleExpand() })
        val t = TextView(context)
        t.text = "TIMELINE"
        t.setTextColor(Theme.TEXT_DIM)
        t.textSize = 11f
        t.letterSpacing = 0.14f
        t.setPadding(dip(10), 0, 0, 0)
        bar.addView(t)
        bar.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(inlineChips(listOf(
            "＋K" to { insertKey(false) },
            "◇K" to { insertKey(true) },
            "✕K" to { deleteKey() },
            "◠T" to { toggleTween() },
            "⧉" to { copyFrame() },
            "⎘" to { pasteFrame() }
        )))
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(46)))
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.addView(layerPanel, LinearLayout.LayoutParams(dip(130), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(timelineView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun toggleExpand() {
        expandedTimeline = !expandedTimeline
        buildUi()
        timelineView.scrollToFrame(doc.currentFrame)
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private fun togglePlay() {
        if (doc.playing) stopPlayback() else startPlayback()
    }

    fun startPlayback() {
        doc.playing = true
        playChip.text = "⏸"
        playChip.setAccent(true)
        handler.post(playTick)
    }

    fun stopPlayback() {
        doc.playing = false
        playChip.text = "▶"
        playChip.setAccent(false)
        handler.removeCallbacks(playTick)
        updateFrameIndicator()
    }

    private fun updateFrameIndicator() {
        frameIndicator.text = String.format("%d / %d", doc.currentFrame, doc.frameCount)
        fpsChip.text = "${doc.fps} fps"
    }

    private fun updateProps() {
        val l = doc.activeLayer()
        val k = l.keyframes[doc.currentFrame]
        val keyDesc = when {
            k == null -> "no key (hold)"
            k.isBlank -> "blank key"
            else -> "key"
        } + if (k?.tween == true) " · tween" else ""
        propsLabel.text = "Layer ${l.name} · Frame ${doc.currentFrame} · $keyDesc"
    }

    private fun refreshAll() {
        canvasView.invalidateDoc()
        timelineView.invalidate()
        layerPanel.invalidate()
        updateProps()
        updateFrameIndicator()
    }

    private fun refreshHistoryChips() {
        undoChip.setAccent(history.canUndo)
        redoChip.setAccent(history.canRedo)
    }

    private fun updateToolSelection() {
        toolButtons.forEach { (label, btn) ->
            val def = toolDefs.firstOrNull { it.label == label } ?: return@forEach
            val t = def.tool(this)
            if (t === canvasView.tool) btn.setSelected() else btn.setDeselected()
        }
    }

    // ------------------------------------------------------------------
    // Structural history (document snapshot)
    // ------------------------------------------------------------------

    private fun structuralSnapshot(): DocSnapshot {
        val rows = doc.layers.map { l ->
            val keys = l.keyframes.map { (idx, k) ->
                KeySnap(idx, k.isBlank, k.tween, k.easing, k.content.clone())
            }
            LayerSnap(l.id, l.name, l.kind, l.visible, l.locked, l.opacity, keys)
        }
        return DocSnapshot(rows, doc.activeLayerId, doc.frameCount)
    }

    private fun restoreStructural(snap: DocSnapshot) {
        doc.layers.clear()
        snap.layers.forEach { ls ->
            val l = Layer(ls.id, ls.name, ls.kind, doc.width, doc.height)
            l.visible = ls.visible
            l.locked = ls.locked
            l.opacity = ls.opacity
            ls.keys.forEach { ks ->
                val content = ks.content ?: if (ls.kind == LayerKind.RASTER)
                    RasterContent(doc.width, doc.height).also { rh ->
                        rh.bitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                    } else VectorContent()
                l.keyframes[ks.index] = Keyframe(ks.index, content, ks.blank, ks.tween, ks.easing)
            }
            doc.layers.add(l)
        }
        doc.activeLayerId = if (doc.layers.any { it.id == snap.activeLayerId })
            snap.activeLayerId else doc.layers.lastOrNull()?.id ?: ""
        doc.frameCount = snap.frameCount
        refreshAll()
    }

    private class KeySnap(val index: Int, val blank: Boolean, val tween: Boolean, val easing: Easing, val content: com.keyframe.studio.engine.Content?)
    private class LayerSnap(val id: String, val name: String, val kind: LayerKind, val visible: Boolean, val locked: Boolean, val opacity: Float, val keys: List<KeySnap>)
    private class DocSnapshot(val layers: List<LayerSnap>, val activeLayerId: String, val frameCount: Int)

    private fun withSnapshot(label: String, body: () -> Unit) {
        val before = structuralSnapshot()
        body()
        val after = structuralSnapshot()
        val beforeRef = before
        val afterRef = after
        history.push(label,
            { restoreStructural(beforeRef) },
            { restoreStructural(afterRef) })
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // Frame / keyframe operations
    // ------------------------------------------------------------------

    private fun lockedCheck(): Boolean {
        val l = doc.activeLayer()
        if (l.locked) {
            toast("Layer is locked")
            return true
        }
        return false
    }

    private fun insertKey(blank: Boolean) {
        if (lockedCheck()) return
        val l = doc.activeLayer()
        val f = doc.currentFrame
        withSnapshot(if (blank) "Blank key" else "Keyframe") {
            if (blank) {
                l.blankKey(f)
            } else {
                val hold = l.frameAt(f)?.content
                val k = l.ensureKey(f)
                if (hold != null) {
                    k.isBlank = false
                    k.content = hold.clone()
                } else {
                    k.isBlank = blank && true
                }
                k.isBlank = false
            }
        }
        refreshAll()
    }

    private fun deleteKey() {
        if (lockedCheck()) return
        val l = doc.activeLayer()
        if (!l.keyframes.containsKey(doc.currentFrame)) {
            toast("No key here")
            return
        }
        withSnapshot("Delete key") {
            l.deleteKey(doc.currentFrame)
            val f = doc.currentFrame - 1
            if (doc.currentFrame > 0) doc.currentFrame = f.coerceAtLeast(0)
        }
        refreshAll()
    }

    private fun toggleTween() {
        if (lockedCheck()) return
        val l = doc.activeLayer()
        val k = l.keyframes[doc.currentFrame]
        if (k == null) {
            toast("Key a frame first")
            return
        }
        withSnapshot(if (k.tween) "Remove tween" else "Add tween") {
            k.tween = !k.tween
        }
        refreshAll()
    }

    private fun copyFrame() {
        val l = doc.activeLayer()
        val hold = l.frameAt(doc.currentFrame)?.content
        clipboardContent = hold?.clone()
        if (clipboardContent == null) toast("Key a frame first") else toast("Copied")
    }

    private fun pasteFrame() {
        if (lockedCheck()) return
        clipboardContent ?: run {
            toast("Clipboard empty")
            return
        }
        val l = doc.activeLayer()
        val f = doc.currentFrame
        withSnapshot("Paste keyframe") {
            val k = l.ensureKey(f)
            k.content = clipboardContent!!.clone()
            k.isBlank = false
        }
        refreshAll()
    }

    // ------------------------------------------------------------------
    // Layer operations
    // ------------------------------------------------------------------

    private fun newLayer(kind: LayerKind, name: String): Layer {
        val l = Layer(UUID.randomUUID().toString(), name, kind, doc.width, doc.height)
        if (doc.layers.isEmpty()) doc.layers.add(l)
        else doc.layers.add(0, l) // list index 0 renders on top
        doc.activeLayerId = l.id
        return l
    }

    fun addLayer(kind: LayerKind) {
        withSnapshot("Add layer") {
            val next = (doc.layers.count { it.kind == kind } + 1)
            newLayer(kind, (if (kind == LayerKind.RASTER) "Raster " else "Vector ") + next)
        }
        refreshAll()
    }

    private fun deleteLayer() {
        if (doc.layers.size <= 1) {
            toast("Keep at least one layer")
            return
        }
        withSnapshot("Delete layer") {
            doc.layers.remove(doc.activeLayer())
            doc.activeLayerId = doc.layers.last().id
        }
        refreshAll()
    }

    private fun nudgeLayer(delta: Int) {
        withSnapshot("Reorder layer") {
            val i = doc.layers.indexOfFirst { it.id == doc.activeLayerId }
            val j = (i + delta).coerceIn(0, doc.layers.size - 1)
            doc.layers.removeAt(i)
            doc.layers.add(j, doc.activeLayer())
        }
        refreshAll()
    }

    private fun moveLayer(from: Int, to: Int) {
        withSnapshot("Reorder layer") {
            val item = doc.layers.removeAt(from)
            doc.layers.add(to, item)
        }
        refreshAll()
    }

    private fun addRasterLayerBitmap(bmp: Bitmap, name: String) {
        withSnapshot("Import image") {
            val l = newLayer(LayerKind.RASTER, name)
            l.ensureKey(0)
            val k = l.keyframes[0]!!
            val rc = RasterContent(doc.width, doc.height)
            val base = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(base)
            val s = minOf(
                doc.width.toFloat() / bmp.width,
                doc.height.toFloat() / bmp.height
            )
            val w = bmp.width * s
            val h = bmp.height * s
            val ox = (doc.width - w) / 2f
            val oy = (doc.height - h) / 2f
            val m = android.graphics.Matrix()
            m.postTranslate(ox, oy)
            m.preScale(s, s)
            c.drawBitmap(bmp, m, null)
            rc.bitmap = base
            k.content = rc
            k.isBlank = false
        }
        refreshAll()
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private var fpsDialogOpen = false

    private fun fpsDialog() {
        if (fpsDialogOpen) return
        fpsDialogOpen = true
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dip(24), dip(16), dip(24), 0)
        layout.addView(makeSlider(context, "FPS", 1, 60, doc.fps.coerceIn(1, 60)) {
            doc.fps = it
            fpsChip.text = "$it fps"
            updateFrameIndicator()
        })
        layout.addView(makeSlider(context, "Total frames", 12, 720, doc.frameCount.coerceIn(12, 720)) {
            doc.frameCount = it
            updateFrameIndicator()
            timelineView.invalidate()
        })
        AlertDialog.Builder(context)
            .setTitle("Playback")
            .setView(layout)
            .setPositiveButton("Done") { d, _ ->
                fpsDialogOpen = false
                d.dismiss()
                autosaver.saveNow()
            }
            .setOnCancelListener { fpsDialogOpen = false }
            .show()
    }

    private fun textDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(context)
        input.setText(initial)
        input.setSelection(initial.length)
        input.hint = "Your text"
        input.setPadding(dip(24), dip(12), dip(24), 0)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Add") { d, _ ->
                d.dismiss()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                onOk(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.post { input.requestFocus() }
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        (context as? android.app.Activity)?.startActivityForResult(
            Intent.createChooser(intent, "Import image"), com.keyframe.studio.MainActivity.REQ_FILE)
            ?: toast("Import needs an activity")
    }

    private fun exportDialog() {
        val options = listOf("PNG (current frame)", "JPG (current frame)", "PNG sequence (all frames)", "SVG (current frame)")
        AlertDialog.Builder(context)
            .setTitle("Export")
            .setItems(options.toTypedArray()) { d, which ->
                d.dismiss()
                doExport(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doExport(which: Int) {
        val outDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "exports/" + doc.projectName.replace(" ", "_") + "_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        ).apply { mkdirs() }
        val doc1 = doc
        val transparentCurrent = which == 0 // PNG single = transparent background
        toast("Exporting…")
        Thread {
            try {
                when (which) {
                    0 -> {
                        val b = ProjectIO.renderFrame(doc1, doc1.currentFrame, transparent = true)
                        ProjectIO.exportPngJpg(b, File(outDir, "frame_${doc1.currentFrame}.png"), jpg = false)
                    }
                    1 -> {
                        val b = ProjectIO.renderFrame(doc1, doc1.currentFrame, transparent = false)
                        ProjectIO.exportPngJpg(b, File(outDir, "frame_${doc1.currentFrame}.jpg"), jpg = true)
                    }
                    2 -> {
                        for (f in 0 until doc1.frameCount) {
                            val b = ProjectIO.renderFrame(doc1, f, transparent = true)
                            ProjectIO.exportPngJpg(b, File(outDir, "frame_%04d.png".format(f)), jpg = false)
                            b.recycle()
                        }
                    }
                    3 -> ProjectIO.exportSvg(doc1, doc1.currentFrame, File(outDir, "frame_${doc1.currentFrame}.svg"))
                }
                handler.post {
                    toast("Saved to ${outDir.path}")
                }
            } catch (e: Exception) {
                handler.post { toast("Export failed: ${e.message}") }
            }
        }.start()
    }
}
