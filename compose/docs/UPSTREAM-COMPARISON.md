# Upstream comparison, adoption and limits (1.0.1 correction)

This is a **model-to-Compose rendering library**, not a replacement for either complete
application. “Better” below means a specific supported behavior, not a blanket quality,
performance, file-format, playback or engraving ranking. Neither upstream's full test
suite, editor, renderer nor arbitrary user scores have been reproduced here.

## Reproducible references

| Reference | Pin | What is compared |
|---|---|---|
| TuxGuitar **2.1.1**, released 2026-10-23 | [`b4feea4fe2f0155d648a37282ec57f1ecb329a35`](https://github.com/helge17/tuxguitar/tree/b4feea4fe2f0155d648a37282ec57f1ecb329a35) | 20 Android UI/default, font-baseline and shared Java notation/layout sources; byte-identical to the Java files actually executed by our native differential tests |
| alphaTab **1.9.0**, stable `main` | [`9f1aa10460df1b326123d28a2222f7b20d081e61`](https://github.com/CoderLine/alphaTab/tree/9f1aa10460df1b326123d28a2222f7b20d081e61) | chord-head, accidental/tie, spring/rod, skyline, collision and row-layout source review; actual-source numerical, paint, grouping and effect-placement oracles |

The TuxGuitar pin, paths and individual SHA-256 hashes are in
[`tools/upstream/tuxguitar-2.1.1.json`](../tools/upstream/tuxguitar-2.1.1.json).
`check_tuxguitar.py` verifies both this checkout and the remote pinned sources.
This does **not** assert that the entire fork is identical to TuxGuitar 2.1.1.
The direct Java geometry comparison still uses its original **0.025 physical-unit**
tolerance. Safety additions are disabled for that raw comparison; defaults are enhanced,
not silently described as raw pixel parity. The caption correction additionally
executes original annotation painters rather than treating lane equality as paint parity.

The alphaTab codeload archive is SHA-256
`14e575b8fb49095a043cc5e7fec6a65f836eeb3f48459768683add539a29c6dd`.
`check.py` verifies the checksum before extracting regular source files to an ignored
cache and bundles **unmodified upstream classes** with pinned esbuild 0.28.2. Node,
esbuild, reference TypeScript and bundles are **build/test tooling only**.
`Environment.defaultRenderers` is executed to check profile categories/orders. Its
upstream build-generated `VersionInfo` logger is absent from the archive; a fail-on-use
metadata seam supplies that unused import. None of the reference layout/paint code is modified.

```sh
# From compose/; Node 22, Python 3, authenticated gh, Java 17 for Gradle.
npm ci --no-audit --no-fund --prefix tools/upstream
python3 tools/upstream/check_tuxguitar.py
python3 tools/upstream/check.py             # byte-compare checked reference results
bash gradlew :tux-compose-core:desktopTest :demo-tuxguitar:test --stacktrace
```

The checked TSV contains **3,380 records**, not 3,380 separate test methods:
2,048 skyline operations, eight resets, 400 force/width cases, 128 accidental-column
cases, 360 ranged-glyph paint cases, 256 grouped-effect flag patterns and 128
effect-placement systems, plus 52 actual Score/Tab registry entries. Seven JVM test methods compare the Kotlin helpers and
model adaptation with those actual upstream results. Additional brute-force/randomized tests exercise
our own implementation and boundary rules. `--update` is an explicit fixture maintenance
operation, never an automatic CI repair. The CI build runs source/fixture verification
before Gradle; publication also requires both Android emulator jobs and an explicit `[publish]`
commit after visual review (or an intentional manual workflow dispatch).

## Original 1.0.0 source and decision map

The annotation decisions in this historical table are superseded by the 1.0.1
correction below; they are not presented as proof of correct 1.0.0 label placement.

All alphaTab paths below are under `packages/alphatab/src/` at the pin above. TuxGuitar
paths are under `common/TuxGuitar-lib/src/main/java/app/tuxguitar/graphics/control/`,
except its Android `view/tablature/TGSongViewStyles` / `TGSongViewController` defaults.

| Concern | TuxGuitar basis | alphaTab review | 1.0.0 decision / evidence |
|---|---|---|---|
| Native rhythm and compact mode | `TGMeasureImpl`, `TGMeasureHeaderImpl`, `TGLayout` | `rendering/staves/BarLayoutingInfo` | Keep native spacing/defaults. Springs are a **separate opt-in**, never a hidden replacement. Java differential suite remains. |
| Page/system breaks | `TGLayoutVertical`, `TGLayoutHorizontal` | score/stave row layout and bar force distribution | Keep native whole-bar greedy threshold. Exceptional overwide measures share time cuts across tracks; following bars may use the tail row. Never scale a system to fit. |
| Durations/tuplets/key/clef/spelling | `TGNotation`, `TGMusicKeyUtils`, `TGBeatGroup` | model and accidental helper conventions | Preserve TuxGuitar's decoded model and spelling. No SMuFL font substitution or new parser. |
| Diatonic seconds in chords | `TGNoteImpl`, `TGBeatGroup` | `rendering/glyphs/ScoreNoteChordGlyphBase` | Alternate compatible score heads around their stem; do not move TAB or rhythmic anchors. Shared unisons paint once. Geometry tests + Android display captures. |
| Multiple voices / rests | `TGVoiceImpl`, `TGMeasureImpl` silence logic | `utils/BarCollisionHelper`, chord voice offsets | Keep native rest decisions. Shift conflicting secondary score chords and stems together; retain compatible unisons and source voice/hit identities. |
| Accidental columns | `TGNoteImpl`, sequential `TGNotation` memory | `rendering/glyphs/AccidentalGroupGlyph` | Height-envelope first-fit columns, not just notehead rows. Real containment is overlap; shared planner supplies measurement, placement and hit boxes. 128 upstream column groups. |
| Cross-bar tied accidentals | native tie flag / accidental state | `utils/AccidentalHelper` real tie origin and non-registration | Require matching prior voice/string/pitch/spelling/clef ending exactly at the boundary. A carry does not establish the next bar's accidental. Dangling ties, gaps and changed pitches cannot hide a needed sign. |
| Simultaneous chromatic unisons | sequential accidental memory | per-score-step accidental state | Force both conflicting spellings and invalidate the ambiguous octave/step for the next attack. Preserve raw native behavior behind the opt-out. |
| Grace notes / dots | `TGNoteImpl` painters | chord-level origin and post-note glyph organization | Move grace ink before the whole accidental group. Unique dot rows lie right of the whole chord, including rebased fragments. Native vector models stay unchanged. |
| Captions, let-ring and collisions | native named vertical bands | `rendering/skyline/Skyline`, `SkylineScratch` | Independent half-open range-max skyline **inside existing bands**. Variable-height text no longer charges every reservation the tallest shelf height. Merged dashed let-ring spans remain. 2,048 actual-source operations plus 4,096 brute-force steps. |
| Spring justification | native proportional/equal-per-bar distribution | power-law compliance, minimum rods, force inversion | Optional `Spring` mode, ratio 1.2–2, shared onsets, hard rods. Below minimum width, fragment rather than squeeze. 400 actual-source force/width cases + randomized rod invariants. |
| Picking, strum and connectors | `TGBeatImpl` distinct pick/strum, native effect math | glyph/connection routing conventions | Preserve native direction and distinction. Existing indexed, row-local continuation connectors and effect bands remain. Not all upstream effect painters are implemented. |
| Glyphs / fonts | 32 native vector model trees | alphaTab's Bravura/SMuFL pipeline | Retain provenance-checked native vectors, actual-size path caching and measured Compose text. No alphaTab font or SMuFL path is copied. |
| Cursor / interaction | native song/track/beat identities | cursor/selection separation | Preserve external musical ticks, smooth configurable glide, fixed-fret-width **hollow** capsule, indexed hit geometry and explicit manual-scroll/follow arbitration. No core playback clock. |
| Import / synthesis / MIDI | native decoder and event engine | broad alphaTab import/player capabilities | Intentionally **not** in core. The separate Android demonstration retains TuxGuitar import, FluidSynth, GeneralUser GS, repeats and optional external MIDI. No claim of alphaTab format/player parity. |
| UI / platforms | native reading behavior | browser-oriented rendering and interaction | Native Compose Canvas and immutable model API; Material3 Expressive is sample-only. No WebView, SWT, heavyweight View or alphaTab runtime. Android/JVM gates; optional iOS targets are not device-verified here. |

## What the helper comparisons do not prove

The force solver comparison uses upstream **compliances and rods** as input. It proves
water-fill results for that input, not complete alphaTab page-layout equivalence. Our
spacing reference is the shortest displayed duration capped at **960 ticks**, and base
width is `durationWidths.min()` (18 by default). alphaTab's default reference cap is
30 ticks and its source base is 6.5; the oracle explicitly uses a 60-tick reference to
exercise its helper. Normalization, source fonts, glyph extents, overlays and optical
spacing are not equal. See [the exact equations](ENGRAVING.md).

The column oracle uses equal-width test glyphs to compare **vertical interval colouring**;
our production accidental boxes are conservative native control-point envelopes. It does
not compare alphaTab's SMuFL glyph metrics or optical kerning. The skyline oracle compares
positive outward heights over half-open X intervals, not a whole orchestral-page skyline.
No performance win is claimed without a controlled benchmark.

## Licence boundary after the annotation correction

TuxGuitar-derived shipping code and native vectors remain **LGPL-2.1-or-later**.
Unlike 1.0.0, **1.0.1 deliberately adapts upstream implementation code**:
`TGAlphaEffectLayout.kt` ports alphaTab's `LineRangedGlyph` paint geometry and
`EffectSystemPlacement` priority/chain-placement kernel. That file remains
**MPL-2.0**, with original attribution, pin, full licence and public corresponding
source. The POM lists both licences; see [NOTICE](../NOTICE.md).
No alphaTab parser, playback engine, font or JavaScript runtime is incorporated.
The other independently implemented helpers keep their existing LGPL notices.

## Annotation correction: source behaviour, not another placement scheme

The initial 1.0.0 helper comparisons did **not** exercise annotation paint origins.
The old native parity harness skipped raster painting, and the 2,584 alphaTab
records covered skyline, springs and accidental columns only. Those results did
not establish measure-number or let-ring parity.

1. **Numbers and repeat counts — TuxGuitar.** `TGMeasureImpl.paintDivisions`
   places the number at `barLeft + scale`, baseline `barlineTop + FMBaseLine -
   2*scale`. Android `TGPainterImpl.getFMBaseLine()` returns zero; `drawString`
   passes a baseline directly to `Canvas.drawText`. Compose therefore subtracts
   the actual `TextLayoutResult.firstBaseline`, not an estimated font height.
   The count is native `xN`, right-aligned four units before the closing barline,
   at that same baseline. A one-string TAB's barline extends one string-space
   above/below the string. Neither label belongs at the row's top/marker origin.
2. **Markers and prose — TuxGuitar anchors.** Markers start at
   `barLeft + headerLeftSpacing + firstNoteSpacing` (`beatAreaLeft`), not the clef's
   `headerX`. Beat prose remains attached to its beat's X. The existing fixed-parent
   emergency word wrapping in separate native metadata lanes is retained; it is
   an adaptation, not a claim of native full-page text parity. Captions are no
   longer pushed horizontally back from the last beat to make their box fit.
3. **Let-ring — alphaTab grouped glyphs.** `EffectBand` links consecutive flagged
   beats **per voice**. `LetRingEffectInfo.canExpand` is true across repeats and
   endings; voices are not unioned by duration. `GroupedEffectGlyph` only joins
   painting within one system. A new system restarts a plain centred label; no
   invented parenthesised continuation. The line ends **on the last marked note**,
   not at its beat end. Singletons and groups too short to fit a line are label-only;
   no line is moved below the words. Each drawable row segment has the upstream
   cap. The text remains the user's requested `let ring` spelling, passed to the
   same parameterised glyph instead of alphaTab's default `LetRing` string.
4. **Effect stacking — alphaTab.** The port uses category/order/voice/bar priority
   and two-phase query/commit for linked chains. A voice/effect/bar band shares a
   baseline; a span occupies its full painted interval. The existing TuxGuitar
   lane order supplies the musical clearance, with bottom alignment when that
   lane is taller. No new left-to-right effect-label shelf rules are introduced.
5. **Atomic labels at row edges.** Existing ink envelopes may reserve insufficient
   boundary whitespace for centred labels with larger custom fonts, instead of
   moving the label off its note. Normal native fonts retain their rhythmic grid. Hard minima are rounded upward,
   never down to the nearest physical pixel; effect-spacing constraints are scoped
   to the same voice/effect band instead of charging unrelated neighbouring effects.
   Truly indivisible content wider than the entire parent is still clipped; no
   font/staff scaling is used to conceal that physical limit.
6. **Other native annotations.** Disabling grouping retains `L.R` on every marked
   attack instead of silently dropping it. Palm mute uses native `P.M`; TAB harmonic
   labels keep the actual `N.H`, `A.H`, `T.H`, `P.H`, `S.H` type, while score/mixed
   do not duplicate the harmonic caption. Heavy accents use the native vector,
   not a font caret. Native fades use the two original cubics; vibrato and trill use
   the original filled paths at the un-justified native voice width, not a generic
   sine-like stroke or font substitute. This is targeted annotation correction, not complete editor
   or all-effects pixel parity.

### New executable evidence

- Original Java `paintDivisions`, `paintMarker` and `paintTexts` execute with a
  recording `UIPainter`; **288 number and 36 repeat-count calls** cover all three
  styles, page/horizontal layout, one/six strings and three physical scales.
  An additional differential compares **360 original native offline paths**, their
  voice widths and every recorded move/line/cubic/flush call.
- Original alphaTab classes produce **360 ranged-glyph paint records**, **256
  grouped-effect flag patterns** and **128 effect-placement systems**. These are
  checked alongside **52 original Score/Tab registry entries** and the previous
  2,584 records: **3,380** in total, not invented expectations.
- Native source pinning now includes the Android font-baseline backend, beat/text
  painters and harmonic labels: **20** byte-identical reference files.
- Android tests check real hardware number pixels near their staff baselines,
  centred ranged labels, dash gaps/caps, reflow, zoom and following prose.
- Debug captures are preserved before target-app cleanup; a fail-closed manifest
  requires all **32** annotation/engraving/cursor scenes per API. A green layout
  test without the actual images is no longer accepted as visual evidence.

Release qualification is recorded separately; these descriptions specify the
checks and do not assert that an unfinished candidate has passed them.
