# 0.1.8 engraving and workspace audit

This release extends the native-based renderer; it does **not** claim perfect desktop
engraving, complete editor parity, exhaustive accessibility or human listening approval.
Tests are publication gates, not proof that every possible score/device is defect-free.
The exact source revision, APK hash and signing identity accompany each published build.

## Fixed-width hollow cursor

The renderer measures all 100 two-digit strings `00`–`99` once per font/scale profile.
Let `F` be their maximum Note-font width, `p` the configured inner padding, `S` the
requested stroke, and `W` the explicit outer-width floor:

```
w = max(W, F + 2*(p+S))
i = min(capsuleInset, max(0, (rowHeight-w)/2))
H = rowHeight - 2*i
s = min(S, w/3, H/3)
path = Rect(x-w/2+s/2, rowTop+i+s/2, x+w/2-s/2, rowBottom-i-s/2)
```

The outer width is **independent of the current beat, chord, voice, rest, score head,
accidental and row height**. It changes only with the font/zoom rounding profile or
explicit cursor configuration. Ordinary rows have interior width `F+2*p`, leaving
`p` clear units on either side of the fixed fret slot. The default is now `p=2`.
A large custom `capsuleWidth` remains an intentional override. Very short rows reduce
the stroke/inset, not the width. Null/nonfinite/nonpositive vertical geometry is skipped.
The stroke is hollow; no fill is introduced. Fret ornaments and score heads do not
make it pulse wider. Density/zoom is applied once by the existing Canvas transform.

## Measured caption bands, even without let-ring

`avoidAnnotationCollisions=true` is the default. Markers, prose, effect captions and
chord names are measured in their own font role and interval-packed by row. Marker
captions reserve the measure-number height plus two units before their first line.
Chord names use their own packed sub-band, after the 42-unit fingerboard when diagrams
are enabled; turning diagrams off really hides them in both spacing modes.

Long text wraps by words, then Unicode code points for an indivisible long word. It is
not ellipsized, reduced in size or promoted into a global rhythmic-width multiplier.
Each interval lane needs `previousRight+2 <= nextLeft`; otherwise a new vertical lane
is allocated. Full let-ring caption/dash/cap intervals participate in this packing.
All row bands are re-placed after their maximum reservations are known. This preserves
source notes, ticks, bar identities, fragment attacks and fixed glyph sizes.

Tempo/triplet-feel marks get a dedicated measured Buffer reservation when present,
rather than painting through a newly packed chord/prose/effect band. Its minimum is
32 logical units, or Text-font height + 12 for larger fonts. Hammer/pull/slide/tie
connections reserve an additional 26-unit band after picking marks. Bend labels are
positioned inside a measured band (`max(14, Grace-font height+6)` in native mode;
Adaptive retains a minimum of 16), not above its allocated top edge. Picking and strum
arrows remain separate native-data symbols.

Caption wrapping and interval placement **never** change rhythmic X. The same safety
switch also enables a separate, minimal local visible-glyph clearance pass: a very wide
ghost fret, custom font, bend or diagram may require extra space that the native default
font grid did not budget. For shared onset `j`, it solves the forward constraints

```
x[j] - x[i] >= previousInk.right - nextInk.left + clearance
correction[j] >= correction[j-1] >= 0
```

using the original native positions as a lower bound. Each track's per-onset native
positions are translated to the common envelope before solving; the same correction
is then applied to every track. No onset is compressed, reordered or desynchronized,
and no entire row/bar is uniformly scaled. Native rhythm, header prefixes, ordinary
greedy packing and justification remain the base algorithms. Ordinary unconstrained
native grids are covered by unchanged-onset regression checks.

`avoidAnnotationCollisions=false` restores the unprotected upstream-comparison geometry;
`mergeLetRing=false` separately restores individual native `L.R` captions. Differential
Java-oracle tests deliberately disable these documented Compose enhancements, retain
the original 0.025-physical-pixel tolerance and run the actual upstream Java layout.
This is not a claim that the safety-enhanced layout is pixel-identical to upstream.

Visible caption runs use a cached interval index. A very long wrapped caption computes
its first/last visible line indices directly, rather than scanning thousands of hidden
lines on each cursor frame.

## Original comprehensive default: Technique Atlas

`ShowcaseSongFactory` builds original native TuxGuitar data, serializes it and then
feeds the real importer. There are no renderer-only notes/effects overlaid after import.
The default uses lossless `.tg` because the existing GP5 writer cannot preserve double
dots or per-measure clef changes. Genuine GP3/4/5/GPX/GP7 import remains in the separate
demo module, and the overflow action exports an actual GP5 companion. The older compact
`DemoSongFactory` remains a stable GP3/4/5 differential/decoder fixture.

32 bars, three audible parts (acoustic guitar, fingered bass, percussion), eight sections:

| Bars | Section | Examples |
| --- | --- | --- |
| 1–4 | Open strings & chords | Actual Em fingerings/names, up/down strums and picks, merged let-ring |
| 5–8 | Touch & articulation | Hammer/pull, slide, vibrato, ghost/dead notes, accents, palm mute, staccato, five harmonic types, tap/slap/pop/fade-in |
| 9–12 | Bends & ornaments | Bend/release/pre-bend data, tremolo-bar data, grace, trill, tremolo picking, overlapping prose/effects |
| 13–16 | Rhythm laboratory | Triplets, quintuplets, dotted/double-dotted rhythm, all 1–64 duration/rest values |
| 17–20 | Two voices & ties | Held bass under moving melody, ties, independent voices, voice-specific rests/stems |
| 21–24 | Keys & clefs | Flat/sharp signatures, enharmonic spelling, tenor/alto clefs, whole rest |
| 25–28 | Meter & repeats | 6/8, 3/4, 5/4, repeat/alternative endings and tempo change |
| 29–32 | Finale & reflow | Eighth/sixteenth swing, a full 64×64th bar, full-size fragmented let-ring |

Tremolo-bar display is currently a compact cue, **not** faithful point-curve engraving.
Other supported-but-not-exhaustively-engraved behavior is listed in [PORTING.md](PORTING.md).

The section sheet derives its entries from the imported markers, so it also works on
user files. An explicit jump reveals the destination even when the cursor/follow is
hidden, without enabling either preference. It does not alter source ticks or invent
a constant-tempo navigation clock.

## Responsive workspace

Connected choice cells measure their labels at the current system font scale, compute
how many complete cells fit, then wrap into connected rows. There is no ellipsis or
font shrinking in these choices. Single-cell rows have complete rounded shapes; target
height remains at least 48dp. Settings headings expose heading semantics. The transport
combines section/count, scrubber and sound in one row, retaining a separate dominant
play action and a stacked fallback on narrow windows.

## Reproducible checks

The workflow runs these on the same source before publication:

- Core unit/native differential suites, legacy Adaptive regressions, annotation/cursor
  geometry and adversarial custom-font cases; both Android variant lints and assemblies.
- `ShowcaseAuditTest`: actual `.tg`/GP5 decoder round trips, decoded feature coverage,
  contiguous/aligned meter checks and native MIDI percussion/pitch/repeat expansion.
- **1,152 real-default layout configurations** = 4 widths × 3 notation modes × 2 spacing
  modes × 2 compact choices × 2 justification choices × 4 track-focus choices × 3
  render scales. Checks parent width, finite geometry, unscaled glyphs, source identity,
  cursor lookup, vertical note bounds, caption bands and pairwise caption collisions.
  Geometry comparisons allow 0.01 logical units for Float accumulation at adjacent
  padded band boundaries; the independent raw-Java differential tolerance stays 0.025
  physical pixels. Machine-readable reports: `demo-tuxguitar/build/reports/showcase-*-*.json`.
- Android API **24 and 35**: actual-font/pixel tests, fixed capsule width across fret/chord/
  rest/style/zoom/padding changes, let-ring continuations, native spacing, strums, taps,
  smooth motion, manual-scroll priority, responsive labels/48dp targets, 200% text,
  section navigation, saved preferences, genuine GP decoding and FluidSynth PCM/AudioTrack.
- The sample enables pinned JDK time/NIO desugaring for the actual ZIP/XML decoder on
  API 24 (no JDK/runtime dependency added to core). API-24 pixel tests crop a real
  UiAutomation display screenshot because Compose's Window PixelCopy helper requires
  API 26; they are not skipped or replaced with a synthetic rendering.
- Both emulators then uninstall debug, verify the build-job APK digest and install that
  **exact R8/non-debuggable release**. Black-box tests cover startup, transport/audio,
  section navigation, settings, dark theme, 200% text and expanded windows. Screenshots
  include articulations, tuplets, voices and dense reflow in addition to the workspace.
- APK signing/non-debug/R8/version checks, bank hash/licenses, four ABIs/dependencies,
  page alignment, and core parser/audio/Material isolation. Publication needs every job.

Run source guards locally with `python3 compose/tools/verify.py`. Run the unit suites
with `cd compose && ./gradlew :tux-compose-core:desktopTest :demo-tuxguitar:test`.
Device automation uses `tools/device_test.sh` in the workflow's emulator environment.
Use CI artifacts/Checks for the actual counts, failures, screenshots and source hash.
