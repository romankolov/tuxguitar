# Porting TuxGuitar's layout to Compose

## Reference and scope

The spacing audit for **0.1.5** uses the original Java sources in this repository at
`0d9bfd1ac03248d262b7789367a962e3475c0158`, including the Android `TGSongViewStyles`
and controller defaults. The source lineage and glyph provenance are unchanged. Package
names are `app.tuxguitar`, not the older `org.herac.tuxguitar` names.

**Correction to 0.1.4:** both Android and desktop TuxGuitar enable `DISPLAY_COMPACT` by
default. Compose previously defaulted to a noncompact, custom collision solver. That was
not native layout parity. It also reserved both stem directions vertically, whether needed
or not. Version 0.1.5 makes `TGSpacingMode.TuxGuitar` the default and keeps the previous
solver explicitly opt-in as `TGSpacingMode.Adaptive`.

This is a source-level layout port, not a claim that the entire editor, platform fonts,
antialiasing or every effect painter is pixel-identical. Oversized-measure fragmentation
remains an explicit Compose extension to meet the fixed-parent-width/fixed-glyph contract.

### Source map

| Original source | Compose implementation |
|---|---|
| Android `view/tablature/TGSongViewStyles.java` and `TGSongViewController.java` | `TGLayoutConfig` defaults and sample defaults |
| `TGLayout.loadStyles`, `getDurationWidth`, `getSpacingForQuarter` | logical metrics, physical-pixel rounding, `TGLayoutCalculator` |
| `TGMeasureImpl.calculateBeats`, `makeVoice`, `makeBeat`, `calculateWidth` | `TGNativeSpacing.kt` |
| `TGMeasureHeaderImpl.notify*`, `getFirstNoteSpacing` | native column/header maxima and prefixes |
| `TGMeasureImpl.updateComponents`, `getDisplayPosition`, `getBeatSpacing` | per-track onset maps and native justification |
| `TGMeasureImpl.checkValue`, `registerSpacing`; `TGLayoutVertical.paintSong` | `nativeStaffSpacing` |
| `TGVoiceImpl.updateSilenceSpacing`; hidden-rest decisions in `calculateBeats` | `nativeSilences`, `TGVoiceLayout.silenceY/hiddenSilence` |
| `TGSpacing`, `TGTrackSpacing` | `TGSpacing.kt`: same 19 lanes and permutations |
| `TGLayoutVertical.getTempLines/paintLine`, `TGLayoutHorizontal` | whole-bar greedy packing and equal-per-measure justification |
| `TGNotation`, `TGMusicKeyUtils`, `TGBeatGroup` | spelling, voice groups, stem direction and beam endpoints |
| `TGDuration`, `TGDivisionType`, `TGEffectBend.updateMovements` | immutable duration math and shared bend movements |
| `TGBeatImpl.paintStroke/paintPickStroke` | `TGStrokes.kt` |
| `TGChordImpl` | native diagram footprint, six fret lines, optional names |
| `TGTempoPainter`, `TGTripletFeelPainter`, header change flags | tempo-mark/triplet-feel metadata, spacing and Canvas annotations |
| Original `TGPaintModel` trees | 32 generated Compose glyph paths and provenance hashes |

## Defaults and coordinate spaces

Default logical style units reproduce Android TuxGuitar: strings **10**, score lines **8**,
note font **9**, grace font **6**, chord font **8**, duration widths
`[30,25,21,20,19,18]`, measure left/right spacing **15**, first-note spacing **10**,
left page margin **5**, first-track spacing **15**, track spacing **5**.

Compact is on; score and tablature are on; chord diagrams and names are off. Chord data is
retained even when hidden. Desktop TuxGuitar also defaults compact on, but enables chord
diagrams and uses a different first-measure margin (20). To compare applications, match
notation mode, selected/all tracks, chord flags, style sizes, viewport and zoom—not just
the percentage shown by two different toolkits.

```
factor = density × state.zoomScale × config.scale
logicalViewportWidth = parentWidthPixels / factor
logicalPoint = pixelPoint / factor + scrollOffset
```

Density and zoom are applied once to drawing and pointer transforms. Native Android also
initialises its scale from display density. There is no extra density multiplier in the
new spacing equations.

`TablatureView` supplies `renderScale=factor` to the calculator. It is a rounding context,
not another geometric zoom. Java `Math.round` is reproduced as `floor(v+0.5)` in physical
pixels, then divided by `factor` for logical geometry. This matters for clefs, key changes,
time signatures, tempo/feel prefixes and track origins. A few original quantities really
are unscaled pixels: the vertical layout's right margin is **10 physical pixels**, the
lyric spacer is 10, and some stem/separator allowances are 1 or 2. Those stay physical
pixels instead of being accidentally enlarged at high density/zoom.

Native fonts use `max(1, round(fontSize × factor)) / factor` logical units, matching the
original font loader. They ignore Android's `sp` multiplier by default. Set
`respectSystemFontScale=true` to opt in to system font scaling; for much larger/custom
fonts consider Adaptive spacing too. The text cache and fret cutouts still use exactly
the text layout drawn on Canvas.

For direct non-component use, supply logical width and the same rendering context:

```kotlin
val factor = deviceDensity * zoom * config.scale
val layout = TGLayoutCalculator(config.copy(renderScale = factor))
    .calculate(song, parentWidthPixels / factor, selectedTrackIndex)
// Draw/hit-test with the same factor and scroll transform.
```

## Native horizontal spacing mathematics

### Duration table and selector

```
baseTicks = 960 × 4 / denominator
legacyTicks = (baseTicks + dotAdjustment) × tupletTimes / tupletEnters
D(duration) = durationWidths[min(log2(denominator), lastIndex)]
q(duration) = 960f / legacyTicks × D(duration)
```

Legacy integer operation order is retained. Dotted adds `base/2`; double-dotted adds
`(base/4)*3`. A whole precise duration is **11,531,520** units; a precise quarter is
**2,882,880**. The six-entry native table means both 32nd and 64th notes use its last
entry, 18. The shortest participating voice is selected by **legacy integer ticks**,
accepting the last duration on an equal-tick tie, exactly as `calculateBeats` does.

### Compact mode (default)

Let `e(v)` be the maximum bend allowance among a voice's notes. Native compact bends have
zero extra horizontal allowance; noncompact bends reserve ten style units per movement.
Native voice and beat minima are:

```
voiceMinimum(v) = D(duration(v)) + e(v)
beatMinimum(i) = min over nonempty voices v of voiceMinimum(v)
compactRhythmWidth = sum(beatMinimum(i))
compactOffset(i) = sum(beatMinimum(j), j < i)
```

A minimum is over voices, not a generic maximum of text/font/effect envelopes. Empty active
voices are rests; `empty=true` voices do not participate. Text, grace, strum arrows, picking
marks and arbitrary font widths do not add a new horizontal solver to these equations.

When chord diagrams **or names** are enabled and two adjacent beats both carry chords,
`makeBeat` widens the **previous** beat to at least the native chord width:

```
chordWidth = fretIndexSpacing + stringSpacing + strings × stringSpacing
           = 8 + 5 + 6 × 5 = 43  // six-string Android defaults
```

Compact checks adjacent beats, not just successive chord events. A lone chord or two chords
separated by unchorded beats does not inflate the entire bar. Compact is effective unless
all-track display is enabled with more than one song track. The renderer uses that same
effective flag, including for bends.

Example: half + quarter + eighth + eighth has natural rhythmic width
`25+21+20+20 = 86`, versus 160 on the noncompact quarter grid. Justification may subsequently
expand either width, but it never changes glyph/font/staff sizes.

### Noncompact quarter grid

The selected duration supplies `q`. If chords are enabled, the shortest distance between
successive chord onsets sets a global lower bound:

```
q = max(q, 960f × chordWidth / shortestChordDistanceTicks)
Q = ceil((lastBeatPreciseStart + maxDurationOfLastBeat - measurePreciseStart)
         / preciseQuarter)
Etrack = sum(maximumVoiceBendAllowanceAtEachBeat)
rhythmWidth = q × Q + Etrack
```

This is the actual last precise ending, rounded up to whole quarters—not an unconditional
`max(meter, actualEnding)`. The editor/importer completes missing rests before calculating
it. Core does not mutate data; supply completed rests for editor-equivalent input. It has
finite defensive fallbacks for empty/convenience models, not a parser or rest normalizer.

Native noncompact chords really can widen the whole bar: adjacent eighth-note chords
produce `q=86` and `86×4=344`. Compact instead widens their one gap to 43 and produces 183
for eight eighths. Version 0.1.4 deliberately substituted a local solution even in
noncompact mode; the native default no longer does that.

### Headers and final natural width

Tempo and triplet-feel changes occupy the top prefix (45 and 55 style units). Clef, key,
time signature and first-note spacing occupy the middle prefix. Physical-pixel rounding
follows the original individual operations:

```
firstNotePrefix = round(max(tempoPrefix + tripletFeelPrefix,
    clefPrefix + keyPrefix + meterPrefix + firstNoteSpacing))
naturalMeasureWidth = rhythmWidth + firstNotePrefix
                    + (repeatClose ? 20 : 0) + leftMeasureSpace + rightMeasureSpace
```

A clef reserves `round(16×factor)+round(clefSpacing×factor)` physical pixels. Key spacing
rounds **current and previous key counts separately**, then adds key-signature spacing.
It retains the original reservation, even when the actually painted cancellation needs
fewer symbols. Meter spacing rounds `(10+timeSignatureSpacing)×factor`. Prefix rounding
uses Java half-up semantics, not Kotlin's ties-to-even `round`.

Headers notify clef/key maxima from **all song tracks**, including hidden tracks in
selected-track mode. All-track display uses shared width, quarter and effect maxima;
those are not replaced with a per-lane collision solver. Immutable headers preserve
`tripletFeel` (0/1/2, explicitly adapted from Java's 1/2/3 constants) and optional `TGTempoMark(value,base,dotted)` metadata. Raw tempo marks
can change at the same quarter BPM and must still trigger a new native header prefix.

### Track effects and beat positioning

For a noncompact track in all-track view:

```
qTrack = sharedQuarterWidth + (sharedMaxEffects - Etrack) / quartersInTimeSignature
beatOffset(t) = (legacyTick(t) - measureStart) / 960f × qTrack
              + precedingEffectsInThisTrack
```

Single-track view uses its local quarter/effect widths. Actual legacy tick positions are
used for painting; precise starts determine the ending. This distinction matters for
7/11/13 tuplets.

The source spreads missing effect slack over the meter while retaining local effect
steps. Equal-tick notes on different tracks are therefore not forced through a new shared
onset map. Common measure columns, time identities and fragment cuts remain aligned;
Native follows the original interior positions. Adaptive's shared-onset constraints are
an opt-in alternative, not falsely described as Java behavior.

## Native row packing and justification

`TGLayoutVertical` greedily accepts whole natural-width bars. Adding a next bar with
`used + next >= available` rejects it unless the row is empty. Explicit source line
breaks are retained; the original full-line decision is evaluated before that break.

For a full row with `n` measures:

```
extraPerMeasure = (available - sum(naturalWidths)) / n
M = naturalMeasureWidth - firstNotePrefix - leftMeasureSpace - rightMeasureSpace
newOffset = max(minimumDurationWidth, M + extraPerMeasure) × naturalOffset / M
```

`M` includes the repeat-close allowance. It excludes the fixed prefix and measure margins.
Justification moves beat anchors in proportion to their **natural offset**, including in
compact mode. It is not `extra × beatIndex / beatCount`. Glyphs remain unchanged. The last
ordinary incomplete row is not stretched. `justifySystems=false` is an explicit ragged-edge
extension; it does not change natural widths or greedy thresholds.

Android uses the unbuffered path, so measure widths are not independently rounded as they
would be by the desktop bitmap-buffer path. Source-level comparisons here target that
Android path, not every desktop raster-buffer quantization choice.

## Vertical layout

All 19 lane permutations and max-registration rules are retained. Score extent is based
on the **actual group's direction**, not both possible stems:

- Up: reserve the note Y and `Y - upOffset - 2 physical pixels` where needed.
- Down: reserve `Y + downOffset + 2 physical pixels` and the note Y where needed.
- Up/down offsets are 28/35 at score spacing 8, scaled with score-line spacing.
- Score-only mode retains native editor extra-line minima: top `-3×staffSpacing`, bottom
  `8×staffSpacing`. Mixed mode does not invent those editor-only reserves.
- Visible rests use native height/position, other-voice displacement and hidden-duplicate
  rest decisions. An active rest voice also counts when selecting opposing stem directions.
- Score lane is five line spacings, although its four intervals are the painted staff.
- Mixed tab separation is 5; tab-only separation considers the actual upward extent.
  Tab lane size uses actual downward extent or `tabHeight+stringSpacing+1 physical pixel`.
- Chord diagrams reserve 42 units (six fret lines), names a separately rounded 15. Beat
  text reserves 15, each actual native offline-effect category 8, and bends 8 when tab is
  visible. Tremolo-bar data does not silently become a bend-spacing reservation.
- Buffer/top minima are applied after the actual lanes; there is no unconditional extra
  top block. Track increments use the original `round(height+trackSpacing+0.5)`.
  Lane accumulation remains in physical float units until the final conversion, preserving
  Java's rounding thresholds even at fractional density/zoom (not a tolerance adjustment).

Tab strings remain `tabTop + (stringNumber-1)×stringSpacing`. Pitched score positions use
`clefOffset - diatonicIndex - 7×octave`, with offsets `[30,18,22,24]`. The spelling tables
retain E#/B#/Cb/Fb and enharmonic alternatives. Beam groups use the native division rule
(dotted quarters only for N/8 with N divisible by 3), interior-extreme flattening, and the
ten-unit maximum beam slope.

## Explicit Compose extensions

### Oversized-measure fragmentation

The Java editor keeps an oversized singleton whole. Negative justification can crowd its
onsets while leaving glyphs fixed. This is not the fixed-size wrapping the user requested.
Default Compose page mode instead fragments **only bars wider than an entire row**, at
beat/metrical boundaries or within exceptionally long gaps. Ordinary bars stay whole.

Every fragment references the same model measure/index, keeps half-open source tick bounds,
and never changes numbering, timing, actual barlines, font size, staff/string spacing or
`contentScale` (always 1). The last tail shares its row with following bars whenever their
projected widths fit. A following normal bar is not split merely to fill leftover space.

Ink overhang is a separate physical endpoint from the logical time cut. An annotation
travels with its source attack; accidental memory is computed over the complete measure.
Held notes/rests have source-owned continuations, not reattacks. Score/tab/text/chord/effect
lanes are rebased separately into each row's lane maxima. Tempo/feel, marker, number and
repeat-open belong to the first fragment; repeat-close/final barline to the last.

Native multi-track interior offsets can differ. For exceptional fragments only, the planner
budgets the maximum per-track advance between successive common time cuts, then projects
each track using its own native coordinates. It does not change ordinary native spacing.
An indivisible glyph/header wider than the entire parent can only be clipped, never shrunk.
Blank margins may reduce in such narrow viewports; this is explicitly not normal native
packing. Reported default page width remains exactly the parent's logical width.

`fitPageToWidth=false` disables this extension. With Native + justification enabled,
oversized page singletons reproduce the original negative-spacing/crowding behavior.
For natural-width horizontal panning use Horizontal mode, or disable both page fitting and
justification. Adaptive retains its older natural-width overflow behavior.

`TGConnections.kt` caches row-local ties, hammers and slides, including incoming paths whose
source is offscreen. Indexed intervals avoid scanning an offscreen prefix. Rest barriers
prevent reconnection across rests. Beams and tuplet/ending brackets continue row-locally;
no connection is drawn diagonally through another staff or row gap.

### Let-ring semantics and collision-free caption bands (0.1.6)

**Source audit:** `TGNoteImpl.paintOfflineEffects` draws `L.R` separately at each flagged note
(and other offline effects in category lanes). It has no merged dashed span to port.
`mergeLetRing=true` therefore opts the default Compose view into the explicitly requested
annotation enhancement; setting it false retains the existing/native lane arithmetic.
The immutable source effects remain unchanged in either mode.

`TGAnnotations.kt` first walks source events, **before fragmentation**, per track/voice.
Consecutive flagged attacks extend `[start,end)` through the last marked duration.
An unmarked reattack/rest closes it, a time gap starts another, tied continuations extend
it without duplicate captions, and repeat-open/close or changed alternate endings cut it.
Unrelated voices do not terminate a held voice. Overlapping/touching runs in the same
repeat section are unioned for display; different tracks/sections never merge. Sorted,
disjoint run intervals provide binary-search row lookup.

For a source time inside a placed beat, project with the same rhythmic interval used by
the cursor:

```
u = clamp((tick - beat.startTick) / (beat.endTick - beat.startTick), 0, 1)
x(tick) = beat.x + u * (beat.endX - beat.x)
```

Split the run against each system/fragment's half-open source interval. At an incoming
edge label it `(let ring)`; at an outgoing edge omit the cap. The final endpoint is two
logical units before the projected duration end, keeping it off the barline. Only the
true end has a six-unit vertical cap. Dashes use a 3-on/2-off pattern, stroke 0.8.
The caption precedes the line by 3 units. If a short final fragment cannot accommodate
both, the line moves below the caption with a 4-unit gap; the cap is never discarded.

On **rows intersecting a run**, preflight all effect captions and beat prose using the
shared measured font metrics. Wrap prose/captions by words, with a Unicode-code-point
fallback for very long words. Clamp their X within the row; do not truncate strings or
shrink fonts. An impossible single glyph is clipped at the viewport boundary. For each
of the Text/Effects bands, sort intervals by left edge and reuse the first lane with
`previousRight + 2 <= nextLeft`; otherwise allocate a new lane. A let-ring interval
includes its caption **and the whole dashed span**, so an interior or immediately
following label cannot occupy the same ink space. Each lane's height is its largest
caption/line/cap box plus clearance (and at least `effectSpacing` in Effects).

Increase, never decrease, the corresponding `TGSpacing` reservations, then re-place the
row with the same horizontal columns. Cached fragments rebase their lanes independently.
Score-only's native lane order puts Effects before ScoreDown, so the enhancement also
pads the caption origin past downward score ink. No native natural width, greedy row
threshold, justification equation, note/font size, source identity or hit-test tick is
changed. Adaptive does not reserve a fresh horizontal `let ring` word on every attack;
its other optional ink constraints remain independent.

`TGStaffLayout.annotations` caches the resulting immutable geometry; a balanced interval
index finds visible ink even with an offscreen label. Renderer dash work is limited to
the visible interval, preserving its original phase. Captions are line-culled vertically.
The normal `render` pass handles these overlays after notation/connections. Standalone
`renderMeasure` callers must draw cached row `renderAnnotation` overlays, just as with
`renderConnection`; `plannedAnnotations` suppresses legacy duplicate beat captions.
Rows without let-ring keep their previous/native layout.

### Hollow capsule cursor geometry (0.1.6)

`CursorStyle.Capsule` uses `Stroke`, never Fill. For row height `h > 0`, configured outer
width `W`, inset `I`, stroke request `S`, and interpolated, unsnapped logical playhead `x`:

```
w = min(W, h)
i = min(I, (h-w)/2)
H = h-2*i
s = min(S, w/3, H/3)
path = Rect(x-w/2+s/2, top+i+s/2, x+w/2-s/2, bottom-i-s/2)
radius = (min(w,H)-s)/2
```

This is the **stroke centreline** rectangle. Its actual outside edge is inset by `i`;
its interior width is `w-2s >= w/3 > 0`, even with a very large stroke request. Zero or
nonfinite row geometry is skipped. Defaults: `capsuleWidth=6`, `capsuleInset=6`,
`width=1.5`; parameters are finite and validated. Canvas applies density × zoom × scale
once afterwards. The style uses the existing tweened displayX, color/opacity/animation
and cooperative follow logic. Core's default remains Line; the showcase starts Capsule
and cycles using the enum's actual size rather than a hardcoded three styles.

### Adaptive/custom-font safety (opt-in)

`spacingMode=TGSpacingMode.Adaptive` retains 0.1.4's font/ink-aware forward solver.
Score/tab/chord/text/effect/picking lanes compete only with their own consecutive ink:

```
x[j] - x[i] >= rightInk[i] - leftInk[j] + clearance
shift[j] = max(previousShift, shift[i] + requiredDistance - baseDistance)
```

A longest-path pass applies minimum nondecreasing shifts. Leading bearings and final
annotation tails are protected, shared onsets align tracks, and strums may move outward
from +12 for very wide frets. This may widen or redistribute space differently from Java.
It is useful for accessibility/custom typography but **not native parity**. The old
`optimizeSpacing` switch only affects this mode; false selects its older 0.1.3 equations.
Neither adaptive settings nor font widths silently replace Native's ordinary-bar math.

## Strokes and picking directions

Strums are beat-level `TGStroke(direction,value)` metadata; picking marks are separate.
Directions preserve None=0, Up=1, Down=-1. With high strings at the top, native Up points
down the page. The cached shaft is at `beatX+12`, wings ±2 and length 5. Mixed mode draws
one strum on tab; score-only uses the staff. Native retains the fixed +12 even for custom
fonts; only Adaptive applies an outward clearance adjustment.

Picking is the native V or heavy-capped staple in its own lane. Both marks move with their
attack and are suppressed on held continuations. Speed remains metadata; core schedules
no audio. Differential tests also record the actual Java painters' line commands. Demo
GP3/4/5 round trips retain strums, and GP4/5 retain separate picks.

## Rendering, interaction and performance

The library remains immutable model → remembered geometry → Compose Canvas. There are no
parsers, MIDI/audio, clocks, platform Views, AWT/SWT or heavy Java dependencies in core.
The actual Java sources used by the oracle are **test/demo dependencies only**.

- Geometry is remembered by score/style/metrics/viewport/selected track. Cursor or selection
  frames do not recompute layout. Palette-only changes reuse geometry.
- Three viewport-sized layers render notation, selection and cursor; no song-sized bitmap.
- Binary searches/indexed iteration cull systems/measures; connections have balanced
  interval indices; row annotations use the same offscreen-source-safe indexing.
  Initial layout is not claimed constant-time or hardware-benchmarked.
- All 32 paths are generated losslessly from original `TGPaintModel` commands. Source
  hashes and regeneration are checked by `tools/verify.py`.
- A bounded 512-entry text cache drives both rendered cutouts and pointer rectangles.
- Actual note rectangles are tested before nearest-onset empty-space seeking.

The cursor interpolates within the active beat's rhythmic interval, never toward the next
row's first X. Optional short tweening smooths coarse caller updates. Reflows, row changes
and backward seeks reset interpolation; highlighting stays discrete. Core owns no clock.

Follow requires a visible, enabled cursor. Pointer-down, drag, wheel/fling and pinch
invalidate running follow animations immediately. Manual browsing has priority; idle grace
and playhead visibility control resumption. Seeking does not implicitly resume follow.
`state.resumeAutoScroll()` is the explicit way to rejoin. Existing frame-clock/pointer
regressions remain in the suite.

## Verification and limitations

`NativeLayoutReference` runs the **actual Java layout**, including `loadStyles`,
`updateSong`, `getTempLines`, `paintSong`, `paintLine`, `updateComponents` and
`getBeatSpacing`. Only final raster painting is suppressed. Tests compile the real
Android `TGSongViewStyles.java`, rather than a copied expected-width table.

`NativeLayoutParityTest` compares natural/justified measure bounds, every beat onset,
row membership, header flags/maxima, stem direction, hidden/displaced rests and vertical
lanes. Its matrix includes score/tab/mixed, compact/noncompact, chord display modes,
fractional scale, polyphony, multi/selected tracks, signatures, effects and real GP3/4/5
showcase imports. The showcase now includes a real let-ring phrase; its native-only
comparisons set `mergeLetRing=false`, because merged annotations intentionally differ.
`native-layout-parity.json` in CI diagnostics records matrix cases;
the differential tolerance is 0.025 **physical pixels**, not a bitmap-similarity claim.
Common and Android tests separately exercise Native's default reflow, wrapping extension,
fixed-size glyphs, actual fonts, transformed pointer hits and system-font-scale policy.
Old Adaptive tests now request that mode explicitly instead of masquerading as defaults.

Remaining limits are explicit:

- Not the entire editor: no editing commands/undo, serialization, print pagination,
  tuning/chord/percussion editors or lyric syllable/text layout in core.
- Core expects ordered decoded data; it does not complete missing rests. The demo uses
  the original completer. Empty/uncompleted defensive fallbacks are not editor parity.
- Core supports up to four voice slots as an extension; this Java reference has two.
- Complex notehead/accidental displacement, arbitrary slurs and every historical
  bend/conflict/effect subvariant are not exhaustively pixel-verified. Some effects still
  share a generic label/path. Outside the enhanced let-ring rows, Native intentionally
  inherits its fixed-grid font/label crowding; choose Adaptive for additional ink-aware
  horizontal clearance rather than those exact equations.
- Platform font shaping and antialiasing differ. Geometry differentials are not a full
  native-versus-Compose screenshot comparison or a claim of identical desktop buffers.
- Android/JVM are CI targets. Optional iOS targets have no runtime validation here.
- Demo decoders are upstream readers, not a newly audited hostile-file sandbox. Input and
  archive limits reduce risk; use trusted evaluation files. Hardware MIDI requires a
  device; CI verifies original MIDI events and software PCM, not human-auditioned hardware.

Derived code/glyphs remain LGPL-2.1-or-later with existing notices. The demo now uses dynamically linked FluidSynth and GeneralUser GS under their respective
licenses; [SYNTH.md](SYNTH.md) documents versions, source, math and relinking. Original
Gervill files remain unchanged but are not compiled into the Compose sample. Keeping the
decoder/player outside core is intentional.
