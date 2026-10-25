# WA2 engine spec for `:core` — font cell map + LSCR bytecode (lifted from `WA2.exe`, corpus-verified)

Author: @designer. All numbers below were lifted from `resource/WA2/WA2.exe` (CC v1.01; `.text` identical in
`WA2_chs.exe`) and validated against every file in `script.pak` / `ck-gal.pak` / `fnt.pak` / `fon.pak`.
Scratch tools (uv-runnable): `scratch/fontmap_verify.py`, `scratch/lscr_grammar.py`, `scratch/fnc.py`,
`scratch/lscr_census.py`, `scratch/kcap.py`, `scratch/lac.py`. Full op census: `scratch/opcode_census.txt`,
full signature table: `scratch/fnc_spec.txt`.

---

## 1. Glyph atlas cell map (exact)

Engine routine `0x401050(code16, flag)` (called from the glyph-quad builder at `0x4463e4` with `flag=1`):
walk a `{u16 lo, u16 hi}` range table, `0xFFFF`-terminated; if `lo ≤ code ≤ hi` return `acc + code − lo`,
else `acc += hi − lo + 1`. Not found ⇒ returns `acc` (= 7655 = "no glyph", draw nothing).
Cell → atlas position: `col = cell % 80`, `row = cell / 80`, cell size 40 px (`本体80.tga`/`袋影80.tga`,
3200×3840) or 24 px (`14pt本体.tga`/`14pt袋.tga`, 1920×2304). Same map for JP `fnt.pak` and CN `fon.pak`.

**Code16 construction:** double-byte SJIS pair → `(lead << 8) | trail` (big-endian). Single byte `b`:
`b < 0x80 ? b : 0xFF00 | b` (sign-extended; that is why half-width kana appear as `0xFFB1–0xFFDD`).
Lead bytes recognised by the text walker (`0x44db80`): `0x81–0x9F`, `0xE0–0xEA`, `0xF0`.
**Trail byte 0x7F is a valid trail** (CN text uses 18 such codes, 6 564 times).

Table B (`.rdata 0x4a14b8`, 59 ranges, total 7655 cells):

| # | lo–hi | cells | | # | lo–hi | cells |
|---|---|---|---|---|---|---|
| 0 | 0x0020–0x007D | 0–93 | | 20–34 | 0x8940–0x89FC … 0x9740–0x97FC (189 each) | 842–3676 |
| 1 | 0xFFB1–0xFFDD | 94–138 | | 35 | 0x9840–0x9872 | 3677–3727 |
| 2 | 0x8140–0x81AC | 139–247 | | 36 | 0x989F–0x98FC | 3728–3821 |
| 3 | 0x81B8–0x81BF | 248–255 | | 37–43 | 0x9940–0x99FC … 0x9F40–0x9FFC (189 each) | 3822–5144 |
| 4 | 0x81C8–0x81CE | 256–262 | | 44–53 | 0xE040–0xE0FC … 0xE940–0xE9FC (189 each) | 5145–7034 |
| 5 | 0x81DA–0x81FC | 263–297 | | 54 | 0xEA40–0xEAA4 | 7035–7135 |
| 6 | 0x824F–0x8258 | 298–307 | | 55 | 0xF040–0xF0C0 | 7136–7264 |
| 7 | 0x8260–0x8279 | 308–333 | | 56 | 0xFA40–0xFAFC | 7265–7453 |
| 8 | 0x8281–0x829A | 334–359 | | 57 | 0xFB40–0xFBFC | 7454–7642 |
| 9 | 0x829F–0x82F1 | 360–442 | | 58 | 0xFC40–0xFC4B | 7643–7654 |
| 10 | 0x8340–0x8396 | 443–529 | | | | |
| 11 | 0x839F–0x83B6 | 530–553 | | | | |
| 12 | 0x83BF–0x83D6 | 554–577 | | | | |
| 13 | 0x8440–0x8460 | 578–610 | | | | |
| 14 | 0x8470–0x8491 | 611–644 | | | | |
| 15 | 0x849F–0x84BE | 645–676 | | | | |
| 16 | 0x8740–0x875D | 677–706 | | | | |
| 17 | 0x875F–0x8775 | 707–729 | | | | |
| 18 | 0x877E–0x878F | 730–747 | | | | |
| 19 | 0x889F–0x88FC | 748–841 | | | | |

(The routine's 2nd argument selects the table: `flag=0` → table A at `0x4a1420`, the same list truncated after
range 35 — 36 ranges, 3728 cells, JIS level-1 only; the only caller `0x4463e4` pushes `1`, so table B is the one
to implement. The lone glyph at cell 7199 is code 0xF07F.)

**Validation:** anchors 0x8141→140, 亜 0x889F→748, 仰 0x8BC2→1350, 暁 0x8BC5→1353 (= CN 啊), 腕 0x9872→3727,
弌 0x989F→3728, 熙 0xEAA4→7135, 黑 0xFC4B→7654 all match the atlases. All **3 715 distinct CN codes** in
`ck-gal.pak` (1 741 399 occurrences) and all 2 627 JP codes map to a non-empty cell; **0 unmapped**. CN text
uses cells 139–4350 only. **Exception (single bytes):** CN text contains 7 stray single bytes with no cell —
0x7E ×3 (2407/3001/3010), 0x09 ×2 (2009/2504), 0x1E ×1 (2322), 0x7F ×1 (2411) — translator typos; the engine
maps them to 7655 and draws nothing, so the decoder must skip them, never throw.

Kotlin sketch:
```kotlin
object Wa2FontMap {
    private val ranges: IntArray = intArrayOf(/* lo,hi pairs from the table above */)
    const val NO_GLYPH = 7655
    fun cell(code16: Int): Int { var acc = 0; var i = 0
        while (i < ranges.size) { val lo = ranges[i]; val hi = ranges[i+1]
            if (code16 in lo..hi) return acc + code16 - lo; acc += hi - lo + 1; i += 2 }
        return acc }
    fun isLead(b: Int) = b in 0x81..0x9F || b in 0xE0..0xEA || b == 0xF0
    fun single(b: Int) = if (b < 0x80) b else 0xFF00 or b
}
```

---

## 2. LSCR container (`NNNN.bnr`)

```
u32 'LSCR' | u32 hdrSize | u32 n | u32 x | u32 codeOffset | n×{u32,u32} table | u32,u32 | code...
```
Observed `(hdrSize,n,x,codeOffset)`: (20,1,0,28) ×28 files, (68,7,9999,76) ×155, (124,14,9999,132) ×3,
(28,2,10,36) ×1 — i.e. `hdrSize = 12 + 8n`, `codeOffset = hdrSize + 8`. Code = little-endian **u32 word stream**
to EOF; **jump targets in control ops are ABSOLUTE file byte offsets** (critic-verified: 2281/2282 targets land on an
item boundary as `wordIndex = (target − codeOffset) / 4`; the one exception is a `0` placeholder in 2019.bnr).
Stream ends with words `0,1`
(= `(0,1)` end marker) after the last statement.

### 2.1 Items (word-level grammar) — parses all 187 files, 303 405 statements, zero errors
| encoding | meaning |
|---|---|
| `5, 3, int` | push int constant |
| `5, 4, floatbits` | push float constant |
| `1, X` | push **global variable** ref X = index into `Global.vrb` (exactly 29 distinct X = 0..28, 1 345 uses; e.g. `(1,8)` = `FLG_小春好意度`) |
| `2, X` | push **local array variable** ref X (X in 0..50, 3 053 uses) — always followed by an index push and `(6,27)` `[]` |
| `3, X` | push **text-token reference** X (token X of the paired `NNNN.txt`, 1-based comma split; 118 534 uses) |
| `4, op` | call function `op` (see §3); pops its arguments, pushes an int result |
| `6, k` | operator (see §2.2) |
| `0, sub, …` | control (see §2.3) |

Argument passing: a call pops `nargs` values (see `.fnc` arity). **Missing trailing args take the `.fnc`
defaults** (e.g. `M` with 4 pushes vs default list). Tag-3 pushes feed params of type 5/6 (token/string). (Corrections in #29 by @critic applied: tag 1/2 roles, (0,0)/(0,1), (0,2) and (0,5) operand order.)

### 2.2 Operators `(6,k)` — from `0x451a60`, jump tables `0x4523a0/0x452408/0x452444/0x452470/0x452480`
| k | op | k | op | k | op |
|---|---|---|---|---|---|
| 0 | `=` assign (pops value, stores into lvalue ref below it, pushes value) | 13 | `&&` | 21 | `&` |
| 1–4 | `+=` `-=` `*=` `/=` (5–7 `%=` `&=` `|=`, unused) | 14 | `\|\|` | 22 | `\|` |
| 8 | `==` | 15 | `!=` | 23 | unary `-` |
| 9 | `<` | 16 | `+` | 24 | `!` |
| 10 | `>` | 17 | `-` | 25 | `++` (lvalue) |
| 11 | `<=` | 18 | `*` | 26 | `--` (lvalue) |
| 12 | `>=` | 19 | `/` (int: idiv, float: fdiv) | 27 | `[]` subscript (pops index, applies to var ref) |
| | | 20 | `%` | 30 | end of statement (discard result) |

Int/float promotion: if either operand is float the float path is used (`<=`,`>=`,`!=` on floats raise an
engine error — never emitted). Logical ops yield int 0/1. Corpus usage: `+` 14 715× (sprite `face+base`),
unary `-` 11 940×, `[]` 3 053×, `=` 1 476×, `*` 613×, `/` 548×, `||` 320×, `>=` 327×, `>` 165×, `==` 129×,
`|` 129× (SetWeather flags), `-` 283×, `+=` 62×, `&` 41×, `<` 15×, `++` 12×, `<=` 12×, `-=` 1×.

### 2.3 Control `(0,sub,…)` — arities corpus-proven (zero parse errors)
| sub | args | count | meaning (inferred from shape; verify in spike) |
|---|---|---|---|
| 0 | 0 | 970 | **label-block entry** (no-op); every label block opens with `(0,1)(0,0)` |
| 1 | 0 | 1157 | **end of block / return** (`(6,30)(0,1)` closes every label block; 970 mid-file + 187 at EOF = 1157 = total label count 28×1+155×7+3×14+1×2) |
| 2 | 2: `elseAddr, endAddr` | 1152 | `if` header: evaluate the following expression up to `(0,13)`; if false jump to `elseAddr` when non-zero (points at a `(0,4)` else ×71 or a `(0,3)` elif ×2) else to `endAddr`; `elseAddr`=0 in 1079 cases |
| 3 | 1: `target` | 2 | `else if`/`elif` header (expression follows, `(0,13)` ends it) |
| 4 | 0 | 72 | `else` (begin alternative block) |
| 5 | 4: `endAddr, condAddr, incrAddr, bodyAddr` | 12 | `for` header (first word is the largest address; cond<incr<body in all 12); the following statement is the init (`var[0]=0`); a later statement `cond (0,14) step` holds condition/step |
| 6 | 1: `target` | 4 | `switch` form A (LF3 only): expression then `(0,15)` |
| 7 | 2+2n: `n, endAddr, (value,target)×n` | 18 | `switch` form B: expression follows, then `(0,16)`; `(value→target)` cases, fallthrough to `endAddr` |
| 8 | 0 | 48 | `case` body begin |
| 10 | 0 | 48 | `case` body end / `break` |
| 12 | 1: `target` | 1010 | unconditional `goto target` (also `break`/`continue`) |
| 13 | 0 | 1154 | end of condition expression |
| 14 | 0 | 12 | separator between `for` condition and step |
| 15 / 16 | 0 | 4 / 18 | end of switch expression (forms A / B) |

### 2.4 Statement shapes you will see 99 % of the time (scene 1001/1002 traces)
```
(6,30)                                   ; empty
(4,3) run                                ; file prologue
(5,3,0)(5,3,1001)(4,137) VI(0,1001)      ; set scene id (voice namespace)
(3,15)(4,144) WN(tok15)                  ; speaker name := token "？？？"   | (5,3,0)(4,144) = clear name | (5,3,1)(6,23)(4,144) = WN(-1) = previous/none
(5,3,10)(5,3,256)(5,3,0)(5,3,0)(5,3,0)(4,138) VV(10,256,0,0,0)   ; voice: file {VI}_{0000}_{10}.OGG
(3,2)(5,3,0)(5,3,1)(4,131) SetMessageE(tok2, line=0, 1)         ; show text token 2 (line index 0 pairs with VV arg5)
(5,3,1)(4,132) EndMessage(1)                                     ; wait click / page end
(5,3,10)(5,3,111)(5,3,1000)(6,16)(5,3,1)(5,3,0)(5,3,256)(5,3,128)(4,155) CW(10, 111+1000, 1, 0, 256, 128)
      ; sprite: prefix[10]="tak" + "%06d"%1111 → char\tak001111.tga ; arg4 = position index → x = {-288,0,288,-384,384,-480,480,-480,-160,160,480}[i]
(5,3,1)(6,23)(5,3,0)(5,3,0)(5,3,30)(5,3,0)(5,3,0)(5,3,0)(5,4,1280f)(5,4,720f)(4,146) B(-1,0,0,30,0,0,0,1280,720)  ; backdrop fade
(5,3,1)(5,3,1004)(5,3,0)(5,3,60)...(4,147) BC(1,1004,0,60,...)   ; background file B%04d%1d%1d → b100400.tga family
(5,3,8)(5,3,-2)(5,3,1)(5,3,255)(4,158) M(8,-2,1,255)             ; BGM_008 (_A intro + _B loop)
(5,3,1)(5,3,8160)(5,3,120)(5,3,1)(5,3,40)(5,3,0)(4,165) SEP(ch1, SE_8160, fade120, loop1, vol40, 0)
(3,4)(5,3,0)(4,172) SetEffctMode(tok4="sepia.AMP",0)             ; LUT by token name; empty token = clear
(5,3,120)(5,3,0)(5,3,0)(4,194) Wait(120 frames)
(3,83)(5,3,0)(4,0) SLoad(tok83="1002",0)                        ; jump to next scene ; (4,1) SCall(tok "CATCH") = call sub-script and return
```

---

## 3. Function table (`LFScriptFunc.fnc` ops 0–18, `LFScriptFuncEx.fnc` ops 128–238; dispatch `handler = [0x4c2610 + 4*(op-128)]`)

`.fnc` record = `char name[32]; u32 flags (low byte = nargs, bit 0x100 = blocking/yields); u32 ret type;
u8 argType[16]; i32 default[16]` (type 3 int, 4 float, 5 text-token, 6 string, 7 line-index; default
`0x80000000` = required). The 84 ops that occur in the corpus (counts over all 187 scenes; `IC` = count in
1001–1003+2001):

| op | name | sig (defaults) | uses | semantics |
|---|---|---|---|---|
| 0 | SLoad | (tok, int=0) | 207 | load scene named by token, replace current script |
| 1 | SCall | (tok, int=0) | 224 | call sub-script by token (`CATCH`, `catch2`…) then return |
| 2 | call | (int) | 366 | call internal label/function id (e.g. 9995) |
| 3 | run | () | 208 | script prologue marker |
| 4 | print / 5 ret / 9 sin | | rare | debug print / return / math |
| 130 | SetMessage | (tok, line, int=?) | 379 | show text (variant) |
| 131 | SetMessageE | (tok, line, int) | 70 793 | **show dialogue/narration token** |
| 132 | EndMessage | (int) | 70 848 | end of message: wait for click/auto |
| 133/134 | SetMessage2 / WaitMessage2 | | 1/1 | |
| 135 | K | () | 14 | key wait |
| 136 | SetDemoMode | (int, int=60) | 42 | |
| 137 | VI | (nextLine, scene=-1) | 240 | voice resync: `scene` sets the voice namespace (−1 = keep current), `nextLine` = line counter for the next VV (e.g. 1008: VI(0,1008) … VI(171,−1) … ; 1008_030: VI(215,1008)); with this rule 45 526+/45 642 VV calls resolve to real VOICE.PAK files |
| 138 | VV | (spk, 256, 0, 0, line) | 45 642 | **play voice** `{scene}_{line:04d}_{spk:02d}.OGG` (spk 99 = generic) |
| 139 | VX | (int,int,-1,256,0,0) | 373 | voice extra (replay/other scene?) |
| 140/141 | VW / VS | | 40/136 | voice wait / stop |
| 143 | WR | (-1, 0) | 3 070 | window reset/clear (page clear) |
| 144 | WN | (str) | 57 041 | **speaker name** (token / 0 clear / -1) |
| 146 | B | (mode=1, X=-2, Y=0, fade=30, 0,0,0, w=1280f, h=720f) | 4 913 | **background**: stem = X*10+Y → `B{X:04d}{Y:1d}{T:1d}.tga` with T = current SetTimeMode (+ optional `B….amp` LUT); X=-2 → keep picture, transition only; X=0 → B0000{Y}{T} (black/white). `mode`: −1 instant, 1 crossfade, 11–14 crossfade+zoom, 16–19 push from top/bottom/right/left, ≥128 mask `f0{mode&0x7F:03d}.bmp` (0x100 reverse, 0x800 flipX, 0x1000 flipY), other → engine error ≈ instant; duration = `fade` frames @60 fps |
| 147 | BC | same | 1 466 | background change **keeping the standing sprites** (primitive kind=0 → `B%04d%1d%1d`, flag p3=1; verified: handler 0x454fc0 pushes (…,1,0), all 451 BC stems exist only in bak.pak) |
| 148 | V | (X, Y, 0,30,0,0,0,1280f,720f) | 346 | event CG `v%06d.tga` of stem X*10+Y (primitive kind=1); H (149) = `h%06d.tga` (kind=2) |
| 150 | SetShake | (5 int) | 85 | screen shake |
| 152 | F | (5 int) | 178 | flash/fade colour |
| 153 | FB | (time=-1, r,g,b=128) | 376 | fade to colour (0,0,0,0 = instant black) |
| 154 | C | (char, num, **pos**, 0, 0, fade=15, scale=256, bright=128) | 12 224 | **sprite show** `char\{prefix}{num:06d}.tga`; arg2 = position index 0..10 (corpus: 1×6252, 0×2549, 2×2528…), arg3/arg4 always 0, arg5 = crossfade frames, arg6 scale/256, arg7 brightness (128 neutral) |
| 155 | CW | (char, num, **pos**, 0, 256, 128) | 2 095 | sprite show & wait (fixed default fade) |
| 156 | CR | (char, mode=0, fade=15) | 559 | sprite remove |
| 157 | CRW | (int) | 187 | sprite remove & wait |
| 158 | M | (track, -2, 1, 255) | 1 153 | **BGM play** `BGM_%03d` (`_A`+`_B` loop pair preferred) |
| 159 | MS | (fade=0) | 1 315 | BGM stop |
| 161 | MV | (vol, time) | 39 | BGM volume |
| 164 | SE | (id, vol=255) | 11 | one-shot SE `SE_%04d` |
| 165 | SEP | (ch, id, fade=0, loop=1, vol=255, 0) | 4 604 | **channel SE play** (ambient) |
| 166 | SES | (ch, fade=0) | 1 397 | channel stop |
| 167 | SEV | (ch, vol, time=0) | 166 | channel volume |
| 168 | SEW | (ch, 0) | 1 770 | channel wait |
| 170 | SetTimeMode | (int=0) | 2 964 | time-of-day grading mode |
| 171 | SetChromaMode | (255, 0) | 418 | |
| 172 | SetEffctMode | (tok, 0) | 425 | **LUT by name** (`.AMP` token; empty = off) |
| 173/174/175 | SetWeather / ChangeWeather / ResetWeather | | 118/142/6 | `.ani` particle weather (SetWeather arg0 is a flag word: `4\|256`) |
| 176/177/180 | LoadBmp / LoadBmpAnime / ReleaseBmp | (slot, tok name, …) | 201/8/199 | free bitmap layers (txt-named images) |
| 185/188/190/191 | SetBmpParam / Move / Zoom / Zoom2 | | 903/30/24/10 | bitmap layer transforms |
| 193 | SetMovie | (tok, int=0) | 15 | play movie by token (`mv01`…) |
| 194 | Wait | (frames, 0, 0) | 7 014 | wait N frames (60 fps base) |
| 195/196/204 | StartTimer / WaitTimer / GetTimer | | 437/231/480 | |
| 197 | GoTitle | () | 37 | return to title |
| 198/199 | GetGameFlag / SetGameFlag | (idx[, val]) | 34/67 | GFLAG.dat global flags |
| 200 | LogOut | (int) | 2 | |
| 201 | V_Flag | (int,int,int) | 27 | CG-seen flag |
| 203 | Calender | (y, m, d, -1, 1) | 238 | date card |
| 205/206/237/238 | GetSkip / GetClick / IfSkip / GetSkip2 | () | | skip/click state (used inside `if` conditions) |
| 207 | runEX | () | 484 | |
| 208 | SetSelectMess | (tok, 99, 0, 0) | 70 | **choice option** text |
| 209 | SetSelect | (?=3) | 34 | **present choice**, result used by following `switch`/`if` on a variable |
| 210/211/213/214 | S / Z / WSZ / StopSZR | | 484/87/8/12 | scroll / zoom camera |
| 220 | NevelMode | (int) | 14 | novel-mode window |
| 221/222 | EroMode / GetReplayMode | | 50/43 | |
| 225/227 | B2 / V2 | (…, sx f, sy f, f, 1) | 519/43 | zoomable background / CG |
| 229/231 | SetWeather2 / SetShake2 | | 36/6 | |
| 233 | NB | (0,1) | 249 | novel-mode page (5xxx novels) |
| 235 | VXV | (255,30,0) | 8 | |
| 236 | Wait2 | (frames,0,0,0) | 98 | |

Never used in the corpus (safe to stub): 6–8, 10–18, 128, 129, 142, 145, 149, 151, 160, 162, 163, 169,
178, 179, 181–184, 186, 187, 189, 192, 202, 212, 215–219, 223, 224, 226, 228, 230, 232, 234.

**Sprite prefix table** (`.rdata 0x4a2904`, id → prefix; ids 6–9 unused): 0 har, 1 kaz, 2 set, 3 koh, 4 izu,
5 mar, 10 tak, 11 ioo, 12 chi, 13 pap, 14 mam, 15 oto, 16 you, 17 tan, 18 shi, 19 tom, 20 sat, 21 hon, 22 nak,
23 say, 24 aco, 25 mih, 26 mhh, 27 ueh, 28 yos, 29 tan, 30 ham, 31 mat, 32 kiz, 33 suz, 34 saw, 35 miy, 36 yan,
37 mas. **Verified: 14 316 / 14 317 `C`/`CW` calls resolve to an existing file in `char.pak`+`IC/char.pak`**
(the one miss is `tak101101`); `num == 4000` = hide sentinel. Position index → x offset table
(`.data 0x4be0bc`): `[-288, 0, 288, -384, 384, -480, 480, -480, -160, 160, 480]` px from stage centre.

---

## 4. Still open (next RE step; does not block parsing or the linear player)
1. Elif `(0,3)` re-arming: when `elseAddr` points at a `(0,3)` (3 words) the VM must jump to it exactly, not skip 8 bytes (critic #29 A).
2. Local array (`tag 2`) storage/persistence across SLoad; indices > 0 never occur in the corpus.
3. Argument type coercion for type-5 params (token → string) and type-7 (line index).
4. ~~B/BC/V stems~~ — resolved (see table rows 146–149 and board #47/#48). Remaining: exact zoom curves for modes 11–14 and the push-transition easing (cosmetic).
