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
import java.util.UUID

/**
 * Top-level workspace composition: toolbar + canvas + layer/color panel +
 * transport + timeline. Rebuilds between portrait and landscape layouts.
 */
class StudioRootView(context: Context) : LinearLayout(context) {

    val doc: Document = restoreOrNewProject()
    private val handler = Handler(Looper.getMainLooper())

    val history = History { refreshHistoryChips() }
    private val autosaver = Autosaver(File(context.filesDir, "project_keyframe"), { doc })

    private val canvasView = CanvasView(context)

    // non-core modules: lazily constructed so a crash there doesn't take
    // down the whole app — see safeFeature()
    private val timelineView: TimelineView by lazy { TimelineView(context) }
    private val layerPanel: LayerPanel by lazy { LayerPanel(context) }
    private val colorView: ColorPickerView by lazy { ColorPickerView(context) }
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

    // welcome tour (first launch only: no saved project yet)
    private val showWelcomeOnBuild = !autosaver.projectFile.exists()
    private var welcomeStep = 0
    private var welcomeHost: FrameLayout? = null
    private var expandedTimeline = false
    private var autosavedBefore = true

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

    // NOTE: init runs last (block placed after all property declarations on
    // purpose — buildUi() depends on toolDefs and other late fields)

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
    // Welcome tour (first-run onboarding on top of the live workspace)
    // ------------------------------------------------------------------

    private class TourStep(val title: String, val body: String, val chips: String)

    private val tourSteps: List<TourStep> = listOf(
        TourStep(
            "Welcome to Keyframe Studio",
            "A 2D animation studio with vector drawing, raster painting and professional timeline animation.",
            "keep drawing · frame every idea"
        ),
        TourStep(
            "Canvas",
            "Draw anywhere on the stage. Two fingers pan, pinch to zoom, and stylus pressure is supported. Two-finger tap on the canvas undoes the last action.",
            "Pan · Zoom · Pressure"
        ),
        TourStep(
            "Tools",
            "The dock on the left (top bar in portrait) holds selection, transform, pencil, pen, eraser, line, shapes, fill, text and the eyedropper. Rect and ellipse respect the fill toggle.",
            "Tap a tool to use it"
        ),
        TourStep(
            "Layers",
            "Add vector and raster layers, toggle visibility, lock a layer, or reorder with ▲ / ▼. Selection follows the last tapped layer row.",
            "＋V adds a vector layer"
        ),
        TourStep(
            "Timeline",
            "The bottom grid is a layer × frame timeline. Tap the ruler to scrub; tap a cell to pick frame + layer. Add keys (＋K), blank keys (◇K), delete (✕K), and tween between keys with ◠T.",
            "＋K keys the current frame"
        ),
        TourStep(
            "Playback & Export",
            "The transport bar has play, loop, onion skin, FPS and duration. Undo lives here too. Import images and export PNG / JPG / PNG sequence / SVG.",
            "Autosave + undo history"
        ),
        TourStep(
            "You're set",
            "Everything autosaves while you work — come back later and this exact project reopens. Have fun animating!",
            "got it"
        )
    )

    private fun showWelcome() {
        try {
            if (welcomeHost != null) return
            welcomeStep = 0
            val host = FrameLayout(context)
            host.isClickable = true
            host.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x5E101012)
            }
            addView(host, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            welcomeHost = host
            renderWelcome()
        } catch (t: Throwable) {
            welcomeHost?.let { removeView(it) }
            welcomeHost = null
            markCrashedFeature("Welcome tour", t)
        }
    }

    private fun dismissWelcome() {
        welcomeHost?.let { removeView(it) }
        welcomeHost = null
        welcomeStep = 0
    }

    private fun renderWelcome() {
        val host = welcomeHost ?: return
        host.removeAllViews()
        val step = tourSteps[welcomeStep]
        val last = welcomeStep == tourSteps.size - 1
        val card = LinearLayout(context)
        card.orientation = VERTICAL
        card.setPadding(dip(20), dip(16), dip(20), dip(10))
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Theme.SURFACE)
            cornerRadius = dip(14).toFloat()
        }
        card.elevation = dip(12).toFloat()

        card.addView(TextView(context).apply {
            text = step.title
            setTextColor(Theme.TEXT)
            textSize = 19f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        card.addView(TextView(context).apply {
            text = step.body
            setTextColor(Theme.TEXT_DIM)
            textSize = 14f
            lineHeightMultiplier = 1.25f
            setPadding(0, dip(8), 0, dip(10))
        })
        card.addView(TextView(context).apply {
            text = step.chips
            setTextColor(Theme.ACCENT)
            textSize = 12f
            letterSpacing = 0.05f
        })
        val next = TextView(context).apply {
            text = if (last) "Start animating" else "Next"
            setTextColor(0xFF1B1B1D.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.ACCENT)
                cornerRadius = dip(10).toFloat()
            }
        }
        next.setOnClickListener {
            if (last) dismissWelcome()
            else { welcomeStep++; renderWelcome() }
        }
        card.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(42)).apply {
            topMargin = dip(12)
        })
        card.addView(TextView(context).apply {
            text = if (welcomeStep == 0) "Skip tour" else "Back"
            setTextColor(Theme.TEXT_DIM)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dip(8), 0, 0)
            setOnClickListener {
                if (welcomeStep == 0) dismissWelcome()
                else { welcomeStep--; renderWelcome() }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cardLp = FrameLayout.LayoutParams(dip(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        host.addView(card, cardLp)
    }

    private var TextView.lineHeightMultiplier: Float
        get() = 1f
        set(v) = setLineSpacing(0f, v)

    // ------------------------------------------------------------------
    // Project lifecycle
    // ------------------------------------------------------------------

    private fun wireModel() {
        canvasView.doc = doc
        canvasView.renderer = WorkspaceRenderer(doc)
        canvasView.history = history
        runCatching {
            canvasView.crashReporter = { mod, t ->
                CrashReporter.log(context, mod, t)
                post { showRuntimeCrashNotice(mod, t) }
            }
        }.onFailure { markCrashedFeature("Drawing", it) }
        runCatching {
            canvasView.onColorChanged = { c -> colorView.setColor(c) }
        }.onFailure { markCrashedFeature("Color", it) }
        canvasView.textTool = TextTool(
            { onOk ->
                textDialog("Add Text", canvasView.textTool?.lastText ?: "Text", onOk)
            },
            canvasView.brushSettings
        )

        runCatching {
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
        }.onFailure { markCrashedFeature("Timeline", it) }

        runCatching {
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
        }.onFailure { markCrashedFeature("Layers", it) }

        runCatching {
            colorView.onColorChanged = { c -> canvasView.brushSettings.color = c }
        }.onFailure { markCrashedFeature("Color", it) }
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

    private val prefs = context.getSharedPreferences("keyframe_prefs", 0)
    private val uiScale: Float get() = prefs.getFloat("ui_scale", 1f)
    fun dip(v: Int) = (context.dip(v) * uiScale).toInt()

    private var crashNotice: View? = null

    private fun showRuntimeCrashNotice(module: String, t: Throwable) {
        crashNotice?.let { removeView(it) }
        crashNotice = null
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF481D1D.toInt())
            cornerRadius = dip(8).toFloat()
        }
        bar.setPadding(dip(10), dip(6), dip(10), dip(6))
        bar.addView(TextView(context).apply {
            text = "Crash in“$module” detected — report saved in app data"
            setTextColor(Theme.TEXT)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(chipButton(Chip(context, "Report")) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CrashReporter.issueUrl(context, module, t))))
            }
            crashNotice?.let { removeView(it) }
            crashNotice = null
        })
        bar.addView(chipButton(Chip(context, "✕")) {
            crashNotice?.let { removeView(it) }
            crashNotice = null
        })
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(bar, lp)
        crashNotice = bar
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    // ------------------------------------------------------------------
    // Module guards: each UI module is built behind safeFeature() so a
    // crash inside one feature disables only that feature and shows a
    // recovery banner instead of killing the app. Core startup failures
    // are caught in MainActivity and show the fatal-error screen.
    // ------------------------------------------------------------------

    private val crashedFeatures = mutableListOf<String>()

    private fun <T : View> safeFeature(name: String, build: () -> T): View =
        try {
            build()
        } catch (t: Throwable) {
            markCrashedFeature(name, t)
            TextView(context).apply {
                text = "⚠  $name unavailable"
                setTextColor(Theme.TEXT_DIM)
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundColor(Theme.SURFACE2)
                minValueWidth()
            }
        }

    private fun TextView.minValueWidth() {
        minHeight = dip(40)
        minWidth = dip(64)
    }

    private fun markCrashedFeature(name: String, t: Throwable) {
        val entry = "“$name” has crashed on startup: ${t.message ?: t.javaClass.simpleName}"
        if (crashedFeatures.contains(entry)) return
        crashedFeatures += entry
        android.util.Log.e("KeyframeStudio", "module crashed: $name", t)
        toast("Module disabled: $name")
        post { showFeatureCrashBanner() }
    }

    private var crashBanner: View? = null

    private fun showFeatureCrashBanner() {
        crashBanner?.let { removeView(it) }
        crashBanner = null
        if (crashedFeatures.isEmpty()) return
        val names = crashedFeatures.joinToString(" · ") { it.substringBefore(" ") }
        val banner = TextView(context).apply {
            text = "An error has happened but the app can still launch with all the other features — $names. This will be fixed in the following updates. (Tap to dismiss)"
            setTextColor(Theme.TEXT)
            textSize = 12f
            setPadding(dip(12), dip(10), dip(12), dip(10))
            setBackgroundColor(0xFF7A4212.toInt())
            setOnClickListener {
                removeView(this)
                crashBanner = null
            }
        }
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dip(216)
        addView(banner, lp)
        crashBanner = banner
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
            addView(safeFeature("Expanded timeline") { buildExpandedTimeline() })
            postRefresh()
            return
        }

        if (isLandscape()) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.addView(safeFeature("Tool dock") { buildToolbarColumn() }, LinearLayout.LayoutParams(dip(64), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(canvasView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(safeFeature("Tool options") { buildSidePanel() }, LinearLayout.LayoutParams(dip(240), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(safeFeature("Playback") { buildTransportBar() })
            addView(safeFeature("Timeline") { timelineView }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(200)))
        } else {
            addView(safeFeature("Toolbar") { buildTopBar() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(62)))
            addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(safeFeature("Playback") { buildTransportBar() })
            addView(safeFeature("Timeline") { timelineView }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(216)))
        }
        postRefresh()
    }

    private fun isLandscape(): Boolean = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun postRefresh() {
        runCatching {
            updateToolSelection()
            updateFrameIndicator()
            updateProps()
            refreshHistoryChips()
            loopChip.setAccent(doc.loop)
            onionChip.setAccent(canvasView.onion.enabled)
            layerPanel.post { layerPanel.invalidate() }
            timelineView.post { timelineView.invalidate() }
            canvasView.post { canvasView.invalidateDoc() }
        }.onFailure { markCrashedFeature("Refresh", it) }
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

    // placed here on purpose: init runs after all properties above it
    // because Kotlin executes initializers and init blocks in
    // declaration order — buildUi() (below) iterates toolDefs
    init {
        wireModel()
        buildUi()
        post { canvasView.fitToScreen() }
        runCatching { autosaver.start() }
            .onFailure { markCrashedFeature("Autosave", it) }
        if (!autosaver.projectFile.exists()) {
            autosavedBefore = false
        }
        if (showWelcomeOnBuild) post { showWelcome() }
    }

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
        val pressureToggle = toggleText("Pressure") { on ->
            canvasView.brushSettings.pressureSensitive = on
        }
        col.addView(pressureToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(30)))
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

    private fun toggleText(labelName: String, apply: (Boolean) -> Unit): TextView {
        var state = false
        val tv = TextView(context)
        tv.text = "$labelName: OFF"
        tv.setTextColor(Theme.TEXT_DIM)
        tv.textSize = 13f
        tv.setPadding(dip(10), 0, 0, dip(2))
        tv.setOnClickListener {
            state = !state
            apply(state)
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

    private fun sectionTitle(labelText: String): TextView = TextView(context).apply {
        text = labelText
        setTextColor(Theme.TEXT_DIM)
        textSize = 10f
        letterSpacing = 0.12f
        setPadding(dip(2), dip(8), 0, dip(4))
    }

    private fun inlineChips(items: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        items.forEach { (label, act) ->
            row.addView(chipButton(Chip(context, label, label.length < 4), 40, act))
        }
        return row
    }

    private fun gap(h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dip(h))
    }

    /** Wraps a chip with padding + click handler.
     * @param size chip square size in dp */
    private fun chipButton(chip: Chip, size: Int = 40, action: (() -> Unit)? = null): View {
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
        bar.addView(chipButton(playChip, 38, null))
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
        bar.addView(chipButton(Chip(context, "💾")) {
            runCatching { autosaver.saveNow() }
            toast("Project saved")
        })
        bar.addView(chipButton(Chip(context, "⇩")) { exportDialog() })
        val scaleChip = Chip(context, "S ${uiScalePercent()}%")
        scaleChip.setOnClickListener {
            val options = listOf(1f, 0.85f, 0.7f, 0.6f)
            var next = options[0]
            for (o in options) if (Math.abs(o - uiScale) < Math.abs(next - uiScale)) next = o
            val idx = (((options.indexOf(next)) + 1) % options.size)
            prefs.edit().putFloat("ui_scale", options[idx]).apply()
            scaleChip.text = "S ${(options[idx] * 100).toInt()}%"
            toast("UI scale ${(options[idx] * 100).toInt()}%")
            buildUi()
            post { canvasView.fitToScreen() }
        }
        bar.addView(chipButton(scaleChip))
        bar.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return bar
    }

    private fun uiScalePercent(): Int = (uiScale * 100f + 0.5f).toInt()

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
