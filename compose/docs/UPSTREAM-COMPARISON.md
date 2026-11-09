# 1.0.0: upstream comparison, adoption and limits

This is a **model-to-Compose rendering library**, not a replacement for either complete
application. “Better” below means a specific supported behavior, not a blanket quality,
performance, file-format, playback or engraving ranking. Neither upstream's full test
suite, editor, renderer nor arbitrary user scores have been reproduced here.

## Reproducible references

| Reference | Pin | What is compared |
|---|---|---|
| TuxGuitar **2.1.1**, released 2026-10-23 | [`b4feea4fe2f0155d648a37282ec57f1ecb329a35`](https://github.com/helge17/tuxguitar/tree/b4feea4fe2f0155d648a37282ec57f1ecb329a35) | 16 Android UI/default and shared Java notation/layout sources; byte-identical to the Java files actually executed by our native differential tests |
| alphaTab **1.9.0**, stable `main` | [`9f1aa10460df1b326123d28a2222f7b20d081e61`](https://github.com/CoderLine/alphaTab/tree/9f1aa10460df1b326123d28a2222f7b20d081e61) | chord-head, accidental/tie, spring/rod, skyline, collision and row-layout source review; three actual-source helper oracles |

The TuxGuitar pin, paths and individual SHA-256 hashes are in
[`tools/upstream/tuxguitar-2.1.1.json`](../tools/upstream/tuxguitar-2.1.1.json).
`check_tuxguitar.py` verifies both this checkout and the remote pinned sources.
This does **not** assert that the entire fork is identical to TuxGuitar 2.1.1.
The direct Java geometry comparison still uses its original **0.025 physical-unit**
tolerance. Safety additions are disabled for that raw comparison; defaults are enhanced,
not silently described as raw pixel parity.

The alphaTab codeload archive is SHA-256
`14e575b8fb49095a043cc5e7fec6a65f836eeb3f48459768683add539a29c6dd`.
`check.py` verifies the checksum before extracting regular source files to an ignored
cache and bundles **unmodified upstream classes** with pinned esbuild 0.28.2. Node,
esbuild, reference sources and bundles are **build/test tooling only**.

```sh
# From compose/; Node 22, Python 3, authenticated gh, Java 17 for Gradle.
npm ci --no-audit --no-fund --prefix tools/upstream
python3 tools/upstream/check_tuxguitar.py
python3 tools/upstream/check.py             # byte-compare checked reference results
bash gradlew :tux-compose-core:desktopTest :demo-tuxguitar:test --stacktrace
```

The checked TSV contains **2,584 records**, not 2,584 separate test methods:
2,048 skyline insert/query operations, eight resets, 400 force/width cases at five
ratios, and 128 accidental-column cases. Three JVM test methods compare the actual
Kotlin helpers with those results. Additional brute-force/randomized tests exercise
our own implementation and boundary rules. `--update` is an explicit fixture maintenance
operation, never an automatic CI repair. The CI build runs source/fixture verification
before Gradle; publication also requires both Android emulator jobs.

## Source and decision map

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
| Glyphs / fonts | 32 native vector model trees | alphaTab's Bravura/SMuFL pipeline | Retain provenance-checked native vectors, actual-size path caching and measured Compose text. No alphaTab font or glyph is copied. |
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

## License boundary

TuxGuitar-derived shipping code and native vectors remain **LGPL-2.1-or-later**.
alphaTab is **MPL-2.0**; its reviewed source is executed unmodified only in the excluded
test cache. The new shipping helpers independently implement standard engraving,
interval and spring mathematics; no alphaTab implementation file, font, parser,
audio code, Wasm or bundle is incorporated into the AAR/APK. See [NOTICE](../NOTICE.md).
If future work copies an implementation file, retain its MPL notices and meet MPL
corresponding-source requirements rather than relabel it LGPL.

## Explicit limits of this 1.0 library

- Not a full music editor or an all-score engraving engine. No global optimal-page
  breaking, cross-staff beaming, arbitrary microtonal/double-accidental notation,
  SMuFL text/font replacement or exhaustive effect-painter parity.
- Chord seconds, compatible unisons and secondary-voice offsets are deterministic;
  dense three-plus-head-column clusters are not an optical-engraving parity promise.
  The conservative envelopes favor clearance over minimum possible white space.
- Tie-origin suppression is deliberately conservative at the first onset of a new
  bar. A changed clef/spelling, unresolved tie or temporal gap prints a sign rather
  than guessing. This does not invent tie origins missing from the decoded model.
- A single indivisible custom glyph/header larger than the entire parent cannot be
  made to fit without violating fixed-size notation. Wrapping does not promise the
  impossible; use a viable width or smaller explicit typography.
- The existing import-format/effect, GP5 export, synth, accessibility and hardware
  limits in [PORTING](PORTING.md), [SYNTH](SYNTH.md) and [UI](UI.md) still apply.
- Emulator/automated checks and reviewed captures do not prove physical-device,
  auditory, accessibility or universal defect-free behavior.

The demonstrated advantages are **Compose-native isolation**, fixed-size parent-width
reflow with retained model identity, and the targeted tested engraving improvements
above. Neither upstream is declared inferior as a whole.
