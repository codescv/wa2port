# wa2port — White Album 2 (PC + CK-GAL CHS) for Android

Clean-room re-implementation of Leaf's AVG engine (`WA2.exe`) in Kotlin. The APK ships **engine
only**; players supply their own PC install (BYOA). Bilingual 中/日 display, full touch controls.

```
:core  pure Kotlin/JVM — archives, image decoders, font cell map, scenario text, LSCR VM, engine,
       saves; unit-tested on desktop against the real install in seconds
:app   Android (minSdk 26, target/compile 35) — Compose UI, stage renderer, glyph-atlas text,
       audio, movie playback, touch controls
```

## Build & test

```bash
export JAVA_HOME=/usr/local/google/home/chii/.jdks/jdk21
./gradlew :core:test          # uses -Dwa2.root / $WA2_ROOT, default ../resource/WA2 (skips if absent)
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Device assets: copy the PC install (incl. `IC/`, `ck-gal.pak`, `fon.pak`, `grp/`) to
`/sdcard/Android/data/com.wa2port.android/files/wa2/` (or `/data/local/tmp/wa2`), or import a folder
via the in-app SAF picker.

## `:app` map (`com.wa2port.android`)

| Package | Contents |
|---|---|
| (root), `ui/`, `data/` | `MainActivity`, `AppViewModel`, title / config / asset setup (path scan + SAF import) / save-load screens, settings + save store |
| `game/` | `GameSession` (frame-driven engine loop, typewriter, auto/skip, quick/slot saves), `GameScreen`, ADV/NOVEL message windows, choices, backlog with ▶ voice replay, quick-bar, touch gestures (tap, ≥350 ms hold = skip, swipe-up = log, two-finger tap = hide) |
| `render/` | `StageView`/`StageCanvas` (1280×720 letterboxed compositor: bg → sprites → layers → CG, `.amp` LUTs, crossfade + mask dissolve, overlay fades, snow), `AssetImageCache`, `GlyphAtlasText` (CN from `fon.pak`, JP from `fnt.pak` atlases, outline + body, ruby), `MoviePlayer` |
| `audio/` | `AudioEngine` (BGM `_A`→`_B` gapless loop, 4 ambient SE channels + one-shots, voice) streaming straight from the LAC archives |
| `movie/` + `src/main/cpp` | `NativeMovieDecoder`/`AsfMoviePlayer`: original `mvNN0.pak` ASF (WMV3 + WMAv2) via a minimal LGPL FFmpeg 7.0.2 build (`build_ffmpeg.sh`, prebuilt `.so` for arm64-v8a/x86_64); a user `mvNN.mp4` overrides |

## `:core` map

| Package | Contents |
|---|---|
| `archive` | `KcapArchive` (+`OkumuraLzss`; decompress `raw[8:size]` to `usize`, never trust `csize`), `LacArchive` (XOR-0xFF names), `Wa2Assets` (loose → `patch.pak` → `<dir>.pak` → `IC/<dir>.pak`, case-insensitive) |
| `image` | `TgaDecoder` (bpp=0 quirk, premultiplied alpha, indexed atlases), `BmpDecoder` (dissolve masks), `AmpLut` |
| `text` | `Wa2FontMap` (exe range table B, 7655 cells), `ScenarioTextParser` (SJIS-aware, trail 0x7F, `<R…\|…>`, `<F..>`, `<W..>`, `\n`, `\k`), `BilingualTextDecoder`, `CnCellTable` (CN cell→Unicode + CK-GAL render remap) |
| `script` | `LscrProgram` (`.bnr`), `FunctionTable` (`.fnc` signatures + defaults), `LscrVm` (RPN expressions, if/else/elif, for, switch, call/SCall/SLoad, resumable blocking calls), `ScenarioStore` (paired JP `script.pak` / CN `ck-gal.pak`, 1:1 token ordinals) |
| `engine` | `Wa2Engine` (engine functions → `StageState`, dialogue, choices, `EngineEvent`s), `SaveData`/`SystemData` (JSON; 既読 bitsets, GFLAG), `HeadlessRunner` |

Engine facts used (lifted from `WA2.exe`, corpus-verified; see `docs/WA2_LSCR_Spec.md`): statements end
with `(6,30)`; tag 1 = `Global.vrb` var, tag 2 = local array, tag 3 = text token, tag 4 = call,
tag 5 = push, tag 6 = operator, tag 0 = control; jump targets are absolute byte offsets; voice
`{scene}_{line:04d}_{speaker:02d}.OGG`; sprites `char\{prefix}{num:06d}.tga` at x-offset table
`[-288,0,288,-384,384,-480,480,-480,-160,160,480]`; `B`/`BC` → `B%04d%1d%1d.tga` (time-of-day
mode), `V`/`H` → `v%05d%1d.tga`; timings in 60 Hz frames.

## Documentation

详细开发与逆向工程文档（简体中文）位于 [`docs/`](docs/README.md)：

- **[`docs/README.md`](docs/README.md)** — 文档索引与导航
- **[`docs/reverse-engineering.md`](docs/reverse-engineering.md)** — 逆向工程规格手册（KCAP/LZSS、LAC、TGA/BMP、`.AMP` 调色、FB/F 亮度、59 段字库与 CK-GAL 汉化表、双语剧本语法、LSCR 字节码与引擎函数语义表）
- **[`docs/architecture.md`](docs/architecture.md)** — 架构设计（`:core` / `:app` 模块映射、单向数据流、LSCR 可重入挂起模型、存读档与既读追踪、线程模型与双栏排版决策）
- **[`docs/build-and-test.md`](docs/build-and-test.md)** — 构建、测试与部署指南（JDK 21 / Gradle、`:core:test`、`:app:assembleDebug`、FFmpeg 重编译与设备资产推送）
- **[`docs/WA2_LSCR_Spec.md`](docs/WA2_LSCR_Spec.md)** — 原始英文逆向规格参考（LSCR VM 与字库映射表）

