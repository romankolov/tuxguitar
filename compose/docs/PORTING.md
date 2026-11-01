# Porting TuxGuitar's layout to Compose

## Reference and scope

The reference is this repository's `common/TuxGuitar-lib` at base commit **74d67f3681322fddf7462a36450f56c80295450b**, from the helge17/TuxGuitar lineage. Package names in this version are `app.tuxguitar`, not the older `org.herac.tuxguitar` names. No SWT or Graphics2D adapter is retained in the component.

### Source map

| Upstream source | Compose implementation |
|---|---|
| `song/models/TGDuration.java`, `TGDivisionType.java` | `model/Score.kt` |
| `graphics/control/TGLayout.getDurationWidth/getSpacingForQuarter` | `layout/TGLayoutCalculator` |
| `TGMeasureImpl.calculateWidth/updateComponents/getDisplayPosition` | measure metrics + `placeMeasure` |
| `TGMeasureHeaderImpl.notify*`, `getFirstNoteSpacing` | per-column maxima and signature prefix |
| `TGSpacing`, `TGTrackSpacing` | `layout/TGSpacing.kt` (same 19 positions and permutations) |
| `TGLayoutHorizontal.paintTrack`, `TGLayoutVertical.getTempLines/paintLine` | horizontal/wrapped systems and equal-per-measure justification |
| `TGNotation`, `TGMusicKeyUtils` | `layout/TGNotation.kt` |
| `TGBeatGroup.finish/getY1/getY2` | voice groups, stem direction and constrained beam endpoints |
| `TGNoteImpl.update` | one-based string Y mapping and note bounds |
| `graphics/control/painters/*` | generated Compose paths in `renderer/glyphs` |
| `TGEffectBend.updateMovements` | shared `movements()` for spacing and drawing |
| `TGMeasureImpl.paint*`, `TGVoiceImpl.paint*`, `TGNoteImpl.paint*` | `renderer/TGCanvasRenderer` |

## Coordinate spaces

1. **Models:** ticks, precise musical durations, fret/string values. They contain no pixels or toolkit objects.
2. **Layout:** logical floating-point canvas units. Default metrics reproduce `android/.../TGSongViewStyles.java`: strings 10, staff 8, note font 9, duration widths `[30,25,21,20,19,18]`.
3. **Viewport:** physical Compose pixels. The single conversion is `factor = density × zoomScale × config.scale`.

A viewport pixel tap maps back via:

```
logicalX = pixelX / factor + scrollX
logicalY = pixelY / factor + scrollY
```

Drawing applies the inverse transform. The same note rectangles determine fret cutouts, cursor outlines and hit tests. The layout never reads `DrawScope.size` as the full song size; it receives the available logical width explicitly.

## Duration and beat math

For duration denominator `d`:

```
baseTicks = 960 × 4 / d
legacyTicks = (baseTicks + dotAdjustment) × tupletTimes / tupletEnters
durationWidth = durationWidths[min(log2(d), lastIndex)]
quarterSpacing = 960 / legacyTicks × durationWidth
```

All legacy integer operations retain the Java order. Dotted means `+base/2`; double-dotted means `+(base/4)*3`. Precise duration uses `lcm(64, all supported tuplet enters) × 4 = 11,531,520` units per whole note. Supplying `preciseStart` avoids accumulating integer-tick rounding in 7/11/13 tuplets.

The shortest participating voice determines a measure's base quarter spacing. Chord diagrams and text metrics may increase collision-free spacing. Shared headers take maxima across displayed tracks.

For a normal (not compact) measure:

```
measureWidth = maxQuarterSpacing × ceil(actualDurationInQuarters)
             + maxEffectWidth
             + firstNoteSpacing
             + repeatCloseAllowance
             + leftPadding + rightPadding

firstNoteSpacing = round(max(
    tempoPrefixWidth,
    clefWidth + keyWidth + timeWidth + configuredFirstNoteSpacing
))
```

The implementation reserves at least the meter length because the immutable core does not synthesize missing rests. Overfull measures use their actual ending. This agrees with completed upstream measures, but does not pretend a missing-data measure was normalized in place.

The beat's base display position is:

```
relativeQuarters = beat.startQuarters - measure.start / 960
beatX = left + leftPadding + firstNoteSpacing
      + relativeQuarters × adjustedQuarterWidth
      + precedingBendMovementWidths
```

The adjusted width spreads cross-track effect slack over the meter. Compact mode instead accumulates minimum beat widths and is disabled for more than one displayed track, as upstream does. The font-driven collision check is an intentional Compose adaptation: changing a font must not cause neighboring two-digit or ghost/harmonic fret labels to overlap.

## Wrapping and track alignment

Horizontal layout has one system per track with sequential measure X positions. Page layout greedily appends natural-width columns until adding another would meet/exceed available width, or an explicit line break occurs. A too-wide single measure gets its own system and remains pannable rather than being crushed below readable size.

For a full line, the unused width is divided equally among its measures (the upstream `defaultMeasureSpacing` rule). Within each bar, additional beat spacing is proportional to its musical position. The final incomplete line is not stretched. `repeatSystemSignatures=false` preserves upstream's signature-change behavior; opt in to repeating them for print-style systems.

Multi-track mode shares column widths, quarter widths, effect width and prefixes. Staff height is independent per track, but all bars align horizontally.

## Vertical lanes, note positions and beams

`TGSpacing.register` stores the **maximum** requested height for a category over the whole system; positions are prefix sums. The exact score/tab/mixed lane permutations are preserved. Text, chords, markers, endings, tuplets, effects, bends and ledger/stem extents reserve lanes before the staff is placed.

Tab string mapping is exactly `tabTop + (stringNumber - 1) × stringSpacing`.

Pitched score positions follow:

```
position = clefOffset - diatonicIndex - 7 × octave
noteTopY = staffTop + position × staffSpacing/2
clefOffsets = [30,18,22,24]  // treble,bass,tenor,alto
```

The spelling tables include E#/B# and Cb/Fb special octaves and the alternative enharmonic representation. Accidental memory is local to a measure and octave. For normal single-voice groups, the group's middle position chooses the stem direction (`>3` points up); multiple active voices use opposing directions. The original up/down offsets (28/35 at staff spacing 8), ten-unit maximum slope and interior-extreme flattening are retained. Beam groups honor TuxGuitar's division rule: dotted-quarter grouping only for N/8 with N divisible by 3; otherwise quarter grouping.

## Vector conversion

`tools/port_glyphs.py` parses the original `TGPaintModel` trees and maps:

```
TGMoveTo → Path.moveTo
TGLineTo → Path.lineTo
TGCubicTo → Path.cubicTo
TGRectangle → Path.addRect
```

All 32 models retain their original floating-point coordinates. The generator also retains command-control-point bounds for numeral advance widths and the 0.1-unit digit margin. `docs/glyph-provenance.json` pins input SHA-256 hashes; CI fails if source or generated output drifts. No platform musical Unicode glyph or downloaded font is substituted for these symbols.

Fret numbers and prose use a bounded 512-entry text-layout cache, measured in unit density and drawn under the common transform. A custom font therefore changes both its bounding box and its rendered cutout together.

## Rendering and performance

- Immutable geometry is remembered by score, style, font metrics, selected track and viewport width. Cursor movement, playback tick updates and selection do not recompute it.
- There is no full-song bitmap and no giant Compose measurement constraint. Three viewport-sized canvases draw selection, notation and cursor independently.
- System/measure visibility and cursor lookup use lower-bound binary searches. Only visible measures enter the painter.
- Reusable musical paths are lazy, shared objects. Fonts are measured once per cached text/style, not per cursor frame.
- The caller can use `TGLayoutCalculator` and `TGSongLayout` directly without the default component.

This is not a hardware benchmark or a claim of constant-time initial layout: initial layout is proportional to the score size. Hit testing uses the visible staff/measure, then the beats in that bar. Mutate Compose state only on the UI thread; decoding and the demo's MIDI preparation run off it.

## Explicit limitations / next parity work

This implementation is **not all of TuxGuitar's editor or a pixel-identical complete replacement**:

- No editing commands, undo stack, score serialization, lyric syllable alignment, print pagination, page headers/footers, tunings editor, chord editor or percussion-map editor in core.
- Core expects ordered decoded data and does not auto-complete missing rest beats; the demo uses the original completer before adaptation.
- Basic ties/hammer/slides and a broad effect set are drawn, but long cross-system tie/slur continuation, overlapping accidentals/chord notehead displacement, all voice-collision engraving and exact historical bend/conflict special cases need further visual parity work.
- Natural and artificial harmonic identification, grace, trill and tremolo data are preserved. Some effect variants share a generic label/path rather than every legacy subvariant having a distinct engraved symbol. Pick-stroke/stroke, triplet-feel labels and lyric text are not yet modeled fully.
- Rhythm stems/beams and rests are implemented; complex mixed-duration beam joining and multi-voice rest-placement differences are not asserted pixel-identical to every Java branch.
- Platform text shaping/antialiasing differ from the legacy Java toolkits. Custom fonts and configurable note sizes intentionally affect collision spacing.
- Common code is Android/JVM validated by CI. iOS targets can be enabled, but no iOS app or runtime validation is supplied here.
- The demo's readers are the existing upstream readers, not a newly audited hostile-file sandbox. Input/ZIP expansion limits and upstream XML hardening reduce risk, but import only trusted scores for evaluation.
- Hardware MIDI enumeration/output is optional and requires a connected device. CI checks software PCM, not a physical USB/Bluetooth device or human-auditioned timing.

These limitations are documented rather than hidden behind “production-grade” or “1:1” labels. The source-mapped, testable boundary allows the remaining parity work without importing the parser/player stack into the library.
