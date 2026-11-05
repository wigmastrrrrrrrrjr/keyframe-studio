# Keyframe Studio

A professional 2D animation studio for Android — vector illustration, raster
painting, frame-by-frame animation and tweening in one app.

![build](https://img.shields.io/badge/build-GitHub_Actions-4A9EDA)

## Build

CI (recommended): every push to `main` builds debug + release APKs via
`.github/workflows/build.yml`. Grab the APKs from the run's **Artifacts**
section, or from GitHub Releases when a release is published.

Local: open the project in Android Studio (Koala or newer, JDK 17) and run
`Run ▸ app`, or from a terminal with Gradle 8.7:

```sh
gradle :app:assembleDebug
```

## Features

- **Canvas** — large drawing surface with pinch-to-zoom, two-finger pan,
  stylus pressure support, and a two-finger-tap undo shortcut.
- **Tools** — selection (with editable Bézier anchors), move/transform,
  pencil, pen, eraser, line, rectangle, ellipse, fill bucket, text,
  eyedropper. Vector paths are stored as editable cubic segments.
- **Layers** — separate vector and raster layers with visibility, lock,
  opacity and reordering.
- **Timeline** — keyframes and blank keyframes, frame hold, tween toggle
  (shape morph between equal-segment paths, transform interpolation),
  onion skinning with per-side opacity and range, FPS + duration controls,
  scrubbing, playback and looping.
- **Project I/O** — editable `.kfproj` folder format (`project.json` +
  raster PNGs), 12s atomic autosave with rolling backup, recovery on
  restart. Export PNG/JPG per frame, full PNG sequences (transparent),
  and SVG of vector layers.
- **Undo** — snapshot-based history (60 steps), covering strokes and
  structural edits alike.

## Architecture

```
app/src/main/java/com/keyframe/studio/
├── engine/      Document, Layer, Keyframe, Content model, tween/easing math
├── render/      Stage renderer, onion-skin compositing, selection overlays
├── tools/       Tool interface + drawing/selection/transform/text tools
├── project/     .kfproj JSON save/load, autosaver with backup swap, exports
├── history/     Undo stack
└── ui/          CanvasView, TimelineView, LayerPanel, color/brush panels,
                 workspace composition (portrait + landscape)
```

Each tool implements `Tool` (down/move/up/preview) and routes through
`ToolContext`; new tools are added without touching the engine or renderer.

## Roadmap

Audio import + waveform tracks, symbol system, IK rigging, camera layers,
masking, GIF/MP4 export.

## License

MIT (see `LICENSE`).
