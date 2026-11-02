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
| `TGStroke`, `TGPickStroke`, `TGBeatImpl.paintStroke/paintPickStroke` | beat-level model metadata + cached `layout/TGStrokes.kt` vectors |
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
             + sum(sharedEffectWidthAtEachOnset)
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
      + precedingSharedEffectWidths
```

Effect slack is shared at the same onset on every track, not spread differently across each track's meter. Compact mode instead accumulates minimum beat widths and is disabled for more than one displayed track, as upstream does. The font-driven collision check is an intentional Compose adaptation: changing a font must not cause neighboring two-digit or ghost/harmonic fret labels to overlap.

## Wrapping and track alignment

Horizontal layout has one system per track with sequential measure X positions. Like
`TGLayoutVertical.getTempLines`, page layout greedily appends whole natural-width columns
until adding another would meet/exceed available width, or an explicit line break occurs.
`paintLine` justifies full lines by adding spacing, **not by scaling notation**. Differential
tests invoke the original Java method, including the exact-fit threshold and manual breaks.

**Important difference for an oversized single measure:** the legacy Java view keeps the
measure whole and never scales its glyphs. `paintLine` may apply negative spacing, and
`TGMeasureImpl.getBeatSpacing` compresses rhythmic gaps with a minimum-width floor. That
can crowd/overflow notation rather than splitting a measure. The earlier Compose 0.1.1 uniform-fit adaptation was
incorrect for consistent row sizes and has been removed. To meet both fixed parent width
and fixed glyph size, the default Compose page mode fragments only those exceptional bars
at beat boundaries, preferring complete metrical/beam groups and binary subdivisions when
at least half the usable rhythmic span remains occupied. Long rhythmic gaps may also have
presentation cuts. Fragments are not isolated systems: the last continuation stays in the
current row and the next measure is appended whenever `used + nextWidth < available`.
The `>=` wrap threshold and explicit line-break behavior are the original greedy rule;
the same rule is tested against Java using the projected fragment widths. The first piece
of a following oversized bar may also share the row if it fits, but an ordinary bar is never
split just to fill a previous row. Musical measures, barlines, timing and hit-test indices
remain separate. `fitPageToWidth=false` retains the previous Compose unsplit, natural-width panning option;
it does not reproduce the Java painter's potentially overlapping negative spacing.

Each fragment references the SAME complete model measure and original measure index. Its
`startTick/endTick` is a half-open visible interval. Shared beat/effect offsets determine a
common cut across all tracks. Accidental memory is calculated over the complete measure
before fragmentation; it does not reset at an artificial row break. A sparse track or longer
second voice gets tied display continuations with its original `sourceTick`, not new model
beats or playback events. Exact clipped durations are used when representable by the
supported duration table; an irregular cut otherwise retains the source duration glyph.
That last fallback is a presentation limitation, not a change in score timing.

For a fragment whose natural rhythmic coordinates are `[a,b]`:

```
xOnRow(t) = rowLeft + padding + prefix + (xNatural(t) - a)
           + extraSpacing * (xNatural(t) - a) / (b - a)
```

Only beat anchors and rhythmic gaps are repositioned. Fret rectangles, glyphs, fonts,
string/staff spacing and note-head dimensions are translated, **never scaled**. Clef/key/time
prefixes can repeat at a new system if explicitly configured; tempo, marker, measure number,
repeat-open and ending start belong to the first fragment, and repeat-close/real final
barline to the last. Beams are rendered from each row's local voices; a split tuplet bracket
has an open continuation edge. No artificial full barline or new repeat is inserted at a
fragment boundary. The final incomplete line is not stretched.

Ties and hammer/slide targets are indexed by track, voice and string. `TGConnections.kt`
caches outgoing, intervening and incoming segments on each affected staff. A segment ends
at that row's edge and resumes after the next row's prefix, never diagonally through another
staff or the gap between rows. Incoming segments remain available when the source row is
culled. Rest barriers prevent ties from reconnecting to an earlier note across a rest. Bends,
chords, beat text and effect labels reserve horizontal/vertical room and move with the beat.
This extends the upstream local-note painters rather than claiming the legacy view itself
split measures in this way.

The reported page width equals the viewport width exactly. At very narrow widths optional
blank margins reduce first. An indivisible glyph/header/effect wider than the parent is
clipped without rescaling (no finite layout can preserve its size and fit it in fewer pixels).
Horizontal mode and the overflow opt-out retain their natural widths.

A fragment's full engraving is still cached once. Projection rebases score, tablature,
stems, chord/text/effect/picking lanes independently into the shared row's lane maxima.
Simply translating the first row's Y coordinates would misalign the continuation with a
following bar that introduces a chord diagram or additional effects. Repeated system
signatures belong at a row start, not at the following real measure in the middle of a row.

Multi-track mode shares column widths, quarter widths and prefixes. Effect slack is shared
at its actual onset across tracks so notes at equal ticks stay aligned, even when different
tracks bend on different beats. Staff heights reserve the maximum needed lanes per row.

## Strumming strokes and picking directions

The two effects are distinct in `TGBeat`, not `TGNoteEffects`: `stroke` contains the native
`TGStroke` direction and speed denominator (`value`), while `pickStroke` contains the
separate picking direction. Directions preserve `None=0`, `Up=1`, `Down=-1`. Import does
not reinterpret these by screen orientation; the original GP readers already account for
the format-specific direction encodings. GP3/4/5 round trips test both strum directions;
GP4/5 additionally preserve the separate picking marks (GP3 has no such field).

`TGStrokes.kt` ports the actual `TGBeatImpl.paintStroke` geometry. For tab height `h`,
string spacing `s`, staff spacing `l`, and the beat anchor `x`:

```
margin = min(2*s, h-2*s) / 2
Tab:   y1 = tabTop + margin; y2 = tabTop + h - margin
Score: y1 = staffTop + l;    y2 = staffTop + (4*l - l)
arrowX = x + 12
```

Tab placement takes precedence in mixed mode, so there is one strum arrow, not two.
`Up` has its tip at `y2`, `Down` at `y1`, with wings two units wide and five units long.
This apparently reversed screen direction is intentional: the tablature's string numbering
runs from high strings at the top toward bass strings below, and the source painter uses
these musical direction names. One-/two-string tracks retain the source margin formula.

Picking uses `POSITION_PICK_STROKES`: `Up` is the V from `(x±3,y)` to `(x,y+8)`;
`Down` has six-unit legs and a six-unit-wide heavy cap. Legs/strum lines use the original
hairline width; the down-pick cap uses width two. The default lane is eight units, matching
Android `TGSongViewStyles`; `pickStrokeSpacing` can enlarge it but never shrinks the mark.
The common layout caches each mark's line geometry and bounds; the Canvas paints those
lines without Android Views, platform glyphs or per-frame geometry construction.

For wide/custom fret fonts, the Compose collision extension moves the arrow outward from
`x+12` when necessary and reserves its footprint in shared onset slack on every track,
including compact mode. Glyphs and arrowheads are never rescaled. This additional clearance
is not a claim that the original Java painter reserves exactly the same space. Both marks
stay with their owning attack when wrapping; synthetic held-note continuations suppress
them. Stroke speed is preserved as model metadata, with no scheduling or audio logic in
core; the separate demo's native MIDI engine consumes the original native stroke data.

Differential tests record `moveTo/lineTo/setLineWidth` from the **actual Java painters** via
a `UIPainter` test double and compare the returned common geometry for score/tab/mixed,
both directions/none, varied spacing and 1/2/4/6/7/25 strings. Android pixel tests check the
shaft, both arrowhead wings and picking marks on a continued/offscreen-source row.

## Vertical lanes, note positions and beams

`TGSpacing.register` stores the **maximum** requested height for a category over the whole system; positions are prefix sums. The exact score/tab/mixed lane permutations are preserved. Text, chords, markers, endings, tuplets, effects, bends and ledger/stem extents reserve lanes before the staff is placed.

Tab string mapping is exactly `tabTop + (stringNumber - 1) × stringSpacing`.

Pitched score positions follow:

```
position = clefOffset - diatonicIndex - 7 × octave
noteCenterY = staffTop + (position + 1) × staffSpacing/2
noteTopY = noteCenterY - noteHeadRadius
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
- Effect segments have per-staff balanced interval indices, including subtree maximum endpoints, so long/nested connectors remain visible without scanning an offscreen prefix. Geometry and indices are built once with layout, not during cursor frames.
- System/measure visibility and cursor lookup use lower-bound binary searches. Indexed iteration starts at the resulting offset (not a linear `Sequence.drop`); only visible measures enter the painter. A counting-list regression test bounds reads when scrolling past 950 offscreen measures/systems.
- Reusable musical paths are lazy, shared objects. Fonts are measured once per cached text/style, not per cursor frame.
- The caller can use `TGLayoutCalculator` and `TGSongLayout` directly without the default component.

This is not a hardware benchmark or a claim of constant-time initial layout: initial layout processes the score and builds sorted/indexed geometry; it is not constant-time or a per-frame operation. Hit testing uses the visible staff/measure, then the beats in that bar. Mutate Compose state only on the UI thread; decoding and the demo's MIDI preparation run off it.

## Continuous cursor and follow policy

`cursorAt(track,tick,smooth=true)` interpolates from the active beat's X to its next rhythmic
boundary: `x = beatX + (endX - beatX) × (tick - beatStart)/(beatEnd - beatStart)`. The last beat
uses the end of its measure's rhythmic area, never the next row's first X. This changes only
presentation; active-beat lookup, selection and note highlighting stay discrete. An optional
short Compose tween smooths coarse updates; new systems/reflows and backward seeks reset
rather than sweeping across unrelated notation. The component owns no playback clock.

Follow is eligible only for a visible, enabled cursor. User-input epochs invalidate running
scroll animations immediately, including pointer-down before recomposition and subsequent
wheel/fling/pinch updates. Idle grace and viewport visibility govern resumption after manual
browsing; explicit resume is available. Follow tests each new playhead against its *planned*
viewport, not an in-flight animated offset, so incoming playback ticks do not repeatedly
cancel an animation as it brings a system into view.

## Explicit limitations / next parity work

This implementation is **not all of TuxGuitar's editor or a pixel-identical complete replacement**:

- No editing commands, undo stack, score serialization, lyric syllable alignment, print pagination, page headers/footers, tunings editor, chord editor or percussion-map editor in core.
- Core expects ordered decoded data and does not auto-complete missing rest beats; the demo uses the original completer before adaptation.
- Ties, hammers and slides include cross-row continuations, but arbitrary slur grouping, overlapping accidentals/chord notehead displacement, all voice-collision engraving and exact historical bend/conflict special cases need further visual parity work.
- Natural and artificial harmonic identification, grace, trill and tremolo data are preserved. Some effect variants share a generic label/path rather than every legacy subvariant having a distinct engraved symbol. Triplet-feel labels and lyric text are not yet modeled fully.
- Rhythm stems/beams and rests are implemented; complex mixed-duration beam joining and multi-voice rest-placement differences are not asserted pixel-identical to every Java branch.
- Platform text shaping/antialiasing differ from the legacy Java toolkits. Custom fonts and configurable note sizes intentionally affect collision spacing.
- Common code is Android/JVM validated by CI. iOS targets can be enabled, but no iOS app or runtime validation is supplied here.
- The demo's readers are the existing upstream readers, not a newly audited hostile-file sandbox. Input/ZIP expansion limits and upstream XML hardening reduce risk, but import only trusted scores for evaluation.
- Hardware MIDI enumeration/output is optional and requires a connected device. CI checks software PCM and the original MIDI events through Android AudioTrack, not a physical USB/Bluetooth device or human-auditioned timing.

These limitations are documented rather than hidden behind “production-grade” or “1:1” labels. The source-mapped, testable boundary allows the remaining parity work without importing the parser/player stack into the library.
