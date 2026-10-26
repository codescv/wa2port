# wa2port — White Album 2 (PC + CK-GAL CHS) for Android

Clean-room re-implementation of Leaf's AVG engine (`WA2.exe` / `WA2_chs.exe`) in Kotlin and C (JNI).
The APK ships **engine only** (Bring Your Own Assets); players supply their own PC install (`IC/`,
`script.pak`, `ck-gal.pak`, `fnt.pak`, `fon.pak`, `bak.pak`, `char.pak`, `grp.pak`, `BGM.PAK`,
`SE.PAK`, `VOICE.PAK`, `mv*0.pak`).

## Highlights

- **Side-by-Side Bilingual Display (`中/日 双` / `中文` / `日本語`)**:
  - Instant runtime toggle between **Left-CN / Right-JP two-column bilingual layout** (with 18-char balanced line wrapping, sentence-boundary reflow, and 6-step adaptive font sizing so long sentences never clip), **Simplified Chinese**, and **Japanese** (with `<R漢字|かな>` furigana ruby) in both **ADV Window** and **Novel Full-Screen** modes.
  - **Font Size Adjustment (`字号` 70%–150%)**: one-tap presets (`小 85%` · `标准 100%` · `大 115%` · `特大 130%` · `超大 145%`), `−`/`+` 5% steps, a fine slider and a live `实时预览 Preview` card in Config, plus an in-game `字号100%` quick-bar button cycling `100 → 115 → 130 → 145 → 85`. Line caps shrink to what each column holds (`min(18, ⌊colW/size⌋)`, no orphan double-wraps) and the ADV window grows upwards so 3 lines render at the chosen size; NOVEL pages, choices and the Backlog scale too.
  - Pixel-accurate glyph rendering straight from `fon.pak` (`本体80.tga` + `袋影80.tga`, covering all 3,602 CK-GAL extended kanji cells `748..4349` + off-by-one remap `4320..4350`) and `fnt.pak` via `WA2.exe`'s exact 59-range Shift-JIS / GBK cell map (`7,655` cells).
- **1280×720 Hardware-Accelerated Stage & Faithful Visuals**:
  - Premultiplied-alpha character sprites (`char.pak` `bpp=0` header repair + α=2 edge cleanup), backgrounds with time-of-day fallback, event CGs, and `f0%03d.bmp` soft-threshold (`add_x4`) pattern dissolves.
  - Exe-accurate dual-table `.AMP` color grading (`T0[Tc[c]]` with separate background/CG and sprite LUT slots) and `FB`/`F` vertex brightness modulation (`(v - 128) * 2`, neutral at `128`).
  - Frame-accurate blocking vs. asynchronous transitions (`.fnc` `flag & 0x100`: blocking `C`/`CR`/`B`/`BC`/`V`/`H`/`F`/`FB`/`SetShake` vs. non-blocking `CW`/`CRW`), screen shake, and procedural snow particle field.
- **Native ASF (`WMV3` + `WMAv2`) Movie & Gapless `LAC\0` Audio**:
  - Plays original `mv*0.pak` (`1280×720` WMV3 + WMAv2 in ASF containers) directly via a minimal LGPLv2.1 FFmpeg 7.0.2 JNI decoder (`libwa2movie.so` for `arm64-v8a` and `x86_64`) with tap-to-skip.
  - Streams `BGM.PAK`, `SE.PAK`, and `VOICE.PAK` directly from `LAC\0` byte slices with zero temp-file extraction and gapless `BGM_%03d_A.OGG` (intro) → `BGM_%03d_B.OGG` (loop) chaining.
- **Complete Extras Suite (`CG 鉴赏` · `音乐鉴赏` · `场景回想`)**:
  - **CG Gallery (`CG 鉴赏模式`)**: Backed by `WA2.exe` `.data 0x4c01b0` (`149` cards / `272` variations across `IC` and `CC/Coda`), chapter filters, `Show All` toggle, and full-screen pinch-to-zoom (`1×..4×`) variation viewer.
  - **BGM Gallery (`音乐鉴赏模式`)**: All `63` tracks in `.data 0x4c1dd0` display order with titles & singer/composer credits, `A→B` gapless intro+loop playback, `#25` Campus Fes `BGM_021`/`035` switching, `#31` Full Version auto-unlock, and transport bar.
  - **Scene Replay (`场景回想模式`)**: All `24` replay slots from `.data 0x4c1bf0` (`1` IC, `13` CC, `10` Coda) with pre-baked `tv%06d.tga` thumbnails, bilingual titles, `9999.bnr` + `GetReplayMode()` (`op 222`) script dispatch, and automatic return to the replay gallery on completion.
- **Mobile Touch Controls & Save System**:
  - Tap to reveal/advance, long-press (`≥350 ms`) to fast-skip, swipe-up for bilingual Backlog with `▶` voice replay, two-finger tap to hide window, 24 save slots + Quick Save/Load with stage thumbnails, and persistent `既読` bitsets + 1024-entry `GFLAG` system data.

## Project Structure

```text
:core  Pure Kotlin/JVM 17 — KCAP/LZSS & LAC archives, TGA/BMP/.AMP decoders, 59-range font map,
       bilingual scenario parser, LSCR bytecode VM, engine state machine, CG/BGM/Replay catalogs,
       and 31 JUnit 5 tests verified against the full 187-scene PC corpus in seconds.
:app   Android (minSdk 26, target/compileSdk 35) — Jetpack Compose UI, 1280×720 StageView/StageComposer,
       dual-pass glyph-atlas text renderer, AudioEngine, JNI FFmpeg movie player, and SAF asset importer.
```

## Build & Test

Requires **JDK 21** and **Android SDK 35** (with NDK `27.0.12077973` and CMake `3.22.1`):

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/Android/Sdk

# Run desktop JVM unit & full-corpus regression tests (auto-skips asset tests if WA2 install is absent)
./gradlew :core:test -Dwa2.root=/path/to/WA2

# Build Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Build Signed Release APK -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease
```

### Installing Game Assets on Device

Copy your PC installation directory (including `IC/`, `script.pak`, `ck-gal.pak`, `fon.pak`, `fnt.pak`,
`bak.pak`, `char.pak`, `grp.pak`, `BGM.PAK`, `SE.PAK`, `VOICE.PAK`, and `mv*0.pak`) to:

```text
/sdcard/Android/data/com.wa2port.android/files/wa2/
```

Or open **资源文件夹 Asset Folder** on the Title Screen and select your game folder via the Android
Storage Access Framework (SAF) picker.

## Architecture Overview

### `:app` (`com.wa2port.android`)

| Package | Contents |
|---|---|
| (root), `ui/`, `data/` | `MainActivity`, `AppViewModel`, `TitleScreen`, `CgGalleryScreen`, `BgmGalleryScreen`, `SceneReplayScreen`, `SaveLoadScreen`, `ConfigScreen`, `AssetSetupScreen` (path scan + SAF import), `SaveStore`, `SettingsStore` |
| `game/` | `GameSession` (frame-driven engine loop, typewriter, auto/skip, quick/slot saves, replay mode guards), `GameScreen`, two-column bilingual `AdvWindow` & `NovelPage`, choices, `BacklogOverlay` with `▶` voice replay, `QuickBar`, touch gestures |
| `render/` | `StageView` / `StageComposer` (1280×720 letterboxed compositor: `bg → sprites → layers → CG`, `.amp` LUTs, crossfade + `f0%03d.bmp` mask dissolve, `FB`/`F` brightness, snow), `AssetImageCache`, `GlyphAtlasText`, `MoviePlayer` |
| `audio/` | `AudioEngine` (BGM `_A`→`_B` gapless loop via `setNextMediaPlayer`, 4 ambient SE channels + one-shots, voice) streaming directly from `LAC\0` archive slices |
| `movie/` + `src/main/cpp` | `NativeMovieDecoder` / `AsfMoviePlayer`: plays original `mvNN0.pak` ASF (`WMV3` + `WMAv2`) via minimal LGPL FFmpeg 7.0.2 (`build_ffmpeg.sh`, prebuilt `.so` for `arm64-v8a` / `x86_64`) |

### `:core` (`com.wa2port.core`)

| Package | Contents |
|---|---|
| `archive` | `KcapArchive` (`OkumuraLzss`; decompresses `raw[8:size]` to `usize`, handling the CK-GAL `csize` quirk), `LacArchive` (`XOR 0xFF` names), `Wa2Assets` (loose → `patch.pak` → `<dir>.pak` → `IC/<dir>.pak`, case-insensitive) |
| `image` | `TgaDecoder` (`bpp=0` repair, premultiplied alpha, 8-bpp indexed atlases), `BmpDecoder` (dissolve masks), `AmpLut` (`T0[Tc[c]]`) |
| `text` | `Wa2FontMap` (59-range Table B, `7,655` cells), `ScenarioTextParser` (SJIS-aware, trail `0x7F`, `<R…\|…>`, `<F..>`, `<W..>`, `\n`, `\k`), `BilingualTextDecoder`, `CnCellTable` (`cn_cells.tsv` + `cn_render_remap.tsv`) |
| `script` | `LscrProgram` (`.bnr`), `FunctionTable` (`.fnc` signatures + defaults + `0x100` yielding flag), `LscrVm` (RPN expressions, `if/elif/else`, `for`, `switch`, `call`/`SCall`/`SLoad`, statement-boundary resumable blocking calls), `ScenarioStore` (1:1 aligned JP `script.pak` & CN `ck-gal.pak`) |
| `engine` | `Wa2Engine` (engine functions → `StageState`, dialogue, choices, `EngineEvent`s, `replayMode`), `SaveData` / `SystemData` (JSON; `既読` bitsets, 1024-int `gflags`, `cgFlags`, `bgmFlags`), `HeadlessRunner` |
| `gallery` | `CgGallery` (`cg_cards.tsv`, 149 cards / 272 variants), `BgmGallery` (63 tracks), `SceneReplayGallery` (24 replay slots) |

## Documentation

Comprehensive reverse-engineering and architecture documentation (in Simplified Chinese) is available under [`docs/`](docs/README.md):

- **[`docs/README.md`](docs/README.md)** — Documentation index & system overview diagram
- **[`docs/reverse-engineering.md`](docs/reverse-engineering.md)** — Full reverse-engineering specification (`KCAP`/LZSS, `LAC\0`, TGA/BMP, `.AMP` dual-table LUTs, `FB`/`F` brightness, 59-range font map & CK-GAL tables, `.bnr` LSCR bytecode VM, engine opcodes, CG/BGM/Replay binary tables)
- **[`docs/architecture.md`](docs/architecture.md)** — Architecture & runtime design (`:core` / `:app` modules, unidirectional data flow, resumable VM statements, save/load & `既読` tracking, threading model, two-column bilingual layout)
- **[`docs/build-and-test.md`](docs/build-and-test.md)** — Build, test, FFmpeg rebuild, and Android deployment guide
- **[`docs/WA2_LSCR_Spec.md`](docs/WA2_LSCR_Spec.md)** — English quick-reference sheet for the LSCR bytecode VM and font range tables

## License

- Engine source code (`:core` and `:app`): **[MIT License](LICENSE)**.
- Bundled minimal FFmpeg 7.0.2 shared libraries (`app/src/main/cpp/ffmpeg/`): **[GNU LGPL v2.1](app/src/main/cpp/ffmpeg/COPYING.LGPLv2.1)** (dynamically linked; built with `app/src/main/cpp/build_ffmpeg.sh`).
- **No copyrighted game assets** are distributed in this repository or its releases.
