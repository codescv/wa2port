# `render/` + `audio/` — public API

Android presentation layer for the WA2 port. Everything consumes `:core` types
(`Wa2Assets`, `StageState`, `RichText`, `EngineEvent`). Main-thread APIs unless noted.

## Images — `AssetImageCache`

```kotlin
val cache = AssetImageCache(assets /* Wa2Assets */, budgetBytes = 150 shl 20)
val bmp: Bitmap? = cache.get("char", "kaz001101.tga")   // suspend, decodes on Dispatchers.IO
cache.peek(dir, name); cache.prefetch(dir, name); cache.lut("sepia.AMP")
cache.trim() / trimTo(0.5f)  // onTrimMemory
cache.close()                // game screen torn down
```
TGA/BMP → premultiplied `ARGB_8888`, shared in-flight decodes, LRU by `allocationByteCount`,
missing files remembered. Names without extension get `.tga`.

## Stage — `StageView` / `StageCanvas`

```kotlin
val controller = rememberStageController()
StageCanvas(stage = engine.stage, cache = cache, modifier = Modifier.fillMaxSize(), controller = controller)
controller.captureThumbnail(320, 180)        // Bitmap? for save slots
controller.screenToStage(x, y)               // PointF? in 1280×720 space (null in letterbox bars)
controller.stageToScreen(x, y); controller.stageBounds; controller.finishTransition()
```
`StageView` (plain `View`, same methods + `setStage(StageState)`, `release()`) can be used directly.

* 1280×720 virtual stage, letterboxed 16:9 with black bars.
* `StageComposer` flattens bg → sprites (≤8, by `order`, bottom-aligned at `centerX`) → visible
  `BmpLayer`s (by `layer`, `slot`; alpha/x/y/zoom) → event CG → exact per-pixel `.AMP` LUT, off the UI
  thread, once per state change (latest state wins when the engine changes faster than we compose).
* `serial` change → transition: CUT, FADE (crossfade `durationMs`), MASK (grey `grp/f0NNN.bmp`
  threshold sweep, `pf0x.psh` ramp with softness 0.25, GPU via ColorMatrix + `DST_IN`).
* Sprites: graded by `spriteLut` (per-background `B….amp`, cached via `AssetImageCache.getGraded`),
  scaled by `scale/256` (anchored at the feet), tinted by `bright/128`. MASK honours `reverse`, `flipX`, `flipY`
  (non-reverse = dark mask pixels first).
* Live layers: `tint` (`FB` screen tint, 0x808080 = neutral; MULTIPLY below 128, SCREEN above, faded over `tintFadeMs`), `shake` (jitter, ≤24 px), `weather != null` → snow.

## Text — `GlyphAtlasText`

```kotlin
val text = GlyphAtlasText(assets)            // atlases load lazily; call text.preload(TextLang.CN, TextLang.JP) on IO
val h = text.drawRich(canvas, rich, TextLang.CN, x, y, maxWidth, sizePx, color = 0xFFFFFFFF.toInt(),
                      visibleGlyphs = n /* typewriter */, outlineColor = text.outlineColor)
text.glyphCount(rich)                         // unit of visibleGlyphs (ruby readings excluded)
text.measureHeight(rich, lang, maxWidth, sizePx)
val l = text.layout(rich, lang, maxWidth, sizePx)   // cacheable; l.height, l.lineCount, l.endX/endY, l.cursorAfter(n)
text.drawLayout(canvas, l, lang, x, y, color, visibleGlyphs)
```
* Glyphs come from `fon.pak` (CN) / `fnt.pak` (JP) `袋影80.tga` (outline, drawn first) +
  `本体80.tga` (body) as tinted `ALPHA_8` atlases → pixel-faithful CK-GAL glyphs incl. `0x??7F`.
  System-font fallback (via `BilingualTextDecoder`) when an atlas or cell is missing.
* `sizePx` = em: full-width advance = `sizePx·(1+letterSpacing)`, single-byte = half; glyph ink ≈ 0.87 em
  (40-px cell drawn at 4/3 em). Line box = `sizePx·lineSpacing`
  (default 1.5) with glyphs at the bottom and the ruby band on top (`rubyScale` 0.45).
* Wraps at `maxWidth` (ruby groups / ASCII words unbroken, closing punctuation hangs), `\n` breaks,
  `\k` does not break (positions in `TextLayout.pageBreaks` = glyph counts, for typewriter pauses),
  `<W>` waits ignored, `<F14>/<F16>` whisper spans drawn at `smallScale` (0.8×).
* CN cells go through `CnCellTable.atlasCell` (CK-GAL off-by-one fix 4320..4350); codes with no
  cell (stray ck-gal bytes) advance but draw nothing.
* Use from a Compose `Canvas { drawIntoCanvas { text.drawRich(it.nativeCanvas, …) } }`.

## Audio — `audio.AudioEngine`

```kotlin
val audio = AudioEngine(context, assets)
engine.drainEvents().forEach(audio::handle)  // PlayBgm/StopBgm/PlaySe/StopSe/SeVolume/PlayVoice/StopVoice
audio.replayVoice(voiceRef); audio.isVoicePlaying
audio.setVolumes(bgm = 1f, se = 1f, voice = 1f)
audio.pause(); audio.resume(); audio.stopAll(); audio.release()
```
`MediaPlayer` on archive byte ranges (no extraction), private audio thread. BGM `_A` intro →
`_B` loop chained with `setNextMediaPlayer` (gapless), fades/cross-fades. Voice one-shot (new
voice stops the old one). SE: overlapping one-shots on `SE_ONESHOT_CHANNEL`, one sound per other
channel (ambient loops 0..3) with fade-in/out and `SeVolume` ramps.

## Movies — `MoviePlayer`

```kotlin
MoviePlayer(stem = "mv100", assets = assets, onFinished = { engine.movieFinished() }, modifier = Modifier.fillMaxSize())
findMovieFile(assets, stem): File?
```
Source priority: (1) user-supplied `mvNN.mp4` / `mvNN0.mp4` (root, `IC/` or `movie/`,
case-insensitive) in a `VideoView`; (2) the original `mvNN0.pak` ASF via `movie.AsfMoviePlayer`
(native WMV3/WMAv2 decoder, when `NativeMovieDecoder.isAvailable`); (3) a "movie unavailable" card
for 1.5 s. Tap skips. `onFinished` fires exactly once.
