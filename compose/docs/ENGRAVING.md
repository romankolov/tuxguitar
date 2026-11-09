# Engraving and annotation mathematics (1.0.1)

The native spacing, row/track lanes, beam and duration derivations remain in
[PORTING](PORTING.md); caption/cursor/connection and physical-path mathematics remain
in [VISUAL-AUDIT](VISUAL-AUDIT.md). This document describes the additional 1.0 layer.
[UPSTREAM-COMPARISON](UPSTREAM-COMPARISON.md) pins references and distinguishes actual
helper comparisons from broader source inspiration.

## One plan, three consumers

`scoreEngraving(track, measure, previous, config, directions)` produces **relative**
head X, accidental origin/envelope, grace origin, shared-head visibility and per-voice
stem shift. The immutable decoded notes are never changed. The same plan is used by:

1. `measureInk`: lower-bound horizontal spacing and leading-prefix clearance;
2. `TGLayoutCalculator`: absolute Canvas geometry and hit rectangles;
3. `TGMeasureFragments`: rebasing into physical rows and suppressing carried attacks.

Thus the measurer cannot reserve one accidental arrangement while the renderer paints
another. Mutable planners/accidental memory/skyline instances stay local to calculation;
no published layout retains them. `avoidAnnotationCollisions=false` bypasses enhanced
head/accidental/grace placement and preserves the raw native reference route.

## Chords, voices and dots

Let `r` be the notehead radius, `p` its diatonic position, and `x0` the rhythmic onset.
Native modeled head width is `w=2.66r` and stem attachment displacement is `a=2.17r`.
Sort a voice's notes bottom-to-top for up-stems, top-to-bottom for down-stems.
First-fit columns allow another note only when its position differs by more than one
half-line, or both heads are compatible unisons. Column `c` receives

```
headX = x0 + c*a*(up ? +1 : -1) + voiceShift
```

Compatibility requires equal pitch, diatonic position, fill state, harmonic state and
dead-head state. Compatible equal-location heads paint once; their model and hit
identities, TAB frets and stems remain independent. Fragment extraction re-elects the
visible owner: a longer held unison cannot disappear when its original shared owner
has already ended. Secondary-voice conflict tests
use adjacent diatonic positions and overlapping horizontal head boxes. A conflict
moves the whole secondary chord far enough right to clear previous heads by two
units. Its stem and beam endpoint interpolation receive the same shift. TAB and
musical time anchors do not receive it.

Dots use the right edge of the **whole voice chord** plus 2.5 units. A dot whose head
centre is on a staff line moves into the preceding half-space. Equal resulting dot
rows are deduplicated. These anchors are cached even for an undotted source because
a presentation fragment can acquire a dotted duration; the painter checks the actual
fragment duration before painting one/two dots. A fragment translates the cached
anchors by exactly the same score-lane delta as its heads.

## Accidental columns and memory

Native spelling/key/clef/octave tables are unchanged. At each onset, signs use
conservative native control-point envelopes. For staff spacing `s`, centre `y`, and
`g=max(1,s/8)`, the relative vertical envelope is

```
[y - 1.25*s - .25, y + 1.12*s + .25]
columnWidth = .64*s + .5
right = min(headX relative to x0) - g
originX(c) = right - (c+1)*(columnWidth+g)
```

Include a further .25-unit left stroke allowance in the hit/ink envelope. Stable
ascending-top first-fit columns accept an interval only if `previousBottom+g<=top`.
An interval wholly inside another is a collision, not a free column. Widely separated
signs can reuse a column. All signs are left of the whole displaced chord. Grace ink
is placed before the leftmost head/sign envelope and contributes to the same leading
ink and spacing constraints.

Accidental memory is keyed by **octave and spelled scale step**, local to one bar.
Simultaneous different pitches at the same step force explicit signs and invalidate
that step for the following attack. A first-onset cross-bar tie suppresses a sign only
if a real matching voice/string note ends at that exact boundary, has equal pitch and
spelling, and the clef is unchanged. Suppression **does not write accidental memory**;
a later untied attack must still establish its sign. Gaps, missing origins and changed
pitches cannot borrow an unrelated earlier accidental.

Horizontal envelopes enter the existing forward longest-path clearance pass. Native
rhythmic offsets are lower bounds, not replaced with uniform wide padding. Native
vertical row reservations use the same plan, including accidental envelopes and
actual grace-stem/glyph height, so high graces cannot enter preceding metadata. Absolute
`accidentalBounds` participate in hit testing before nearest-onset empty-space seeking.
Continued fragments clear accidental/grace origins so they do not reattack visually.

## Half-open skyline (prose) and upstream effect bands

A skyline stores a piecewise-constant nonnegative outward height `H(x)` on `[L,R)`.
For reservation `[a,b)` with height `h`, the next outward origin is

```
y = max(H(x), x in [a,b))
H(x) = max(H(x), y+h+gap), x in [a,b)
```

For emergency prose wrapping, band packing includes its gap in the reservation height. Disjoint or merely touching
intervals do not lift one another. A narrow tall caption lifts only overlapping later
captions, rather than raising every caption in an entire shelf. The renderer still
uses TuxGuitar's explicit named metadata/effect bands, not a new all-purpose staff
coordinate system. Let-ring dash/cap intervals reserve their full width.

Boundaries/heights are flat arrays. Queries binary-search the first segment, then scan
only intersecting segments; insertion splits at most two boundaries and coalesces equal
neighbors. Query cost is `O(log n+k)`; insertion is `O(n)` because arrays may shift and
coalesce. No unsupported logarithmic-update or whole-page speed claim is made.

## Correct native text baselines and grouped effects

A row's **ink envelope** is not the native measure origin. Marker/prose baselines
can be at that origin, with ascenders above it. Include that actual ink in culling
bounds instead of shifting the baseline down to a box's top. `TGFontMetrics` now
exposes `firstBaseline`; the Compose implementation returns the same cached
`TextLayoutResult.firstBaseline` used for painting. Custom metric providers may
override it; its default remains explicitly approximate.

Native prose/marker text top is `laneBaseline-firstBaseline`. Native chord names
use `chordTop+diagramHeight+FMTopLine`, i.e. text top
`chordTop+diagramHeight-.2*firstBaseline` on Android. Vertically separated atomic
captions first consume the entire existing signature/prefix and margins; only a
missing minimum adds whitespace, not space on top of a sufficient signature.


### Numbers and repeat counts

The Android native painter uses baseline coordinates, with `FMBaseLine = 0`.
For first visible staff/barline ordinate `yS`, bar edges `xL,xR` and measured count
width `wR`, TuxGuitar's logical-unit anchors are:

```
numberBaseline = (xL + 1, yS - 2)
repeatBaseline = (xR - wR - 4, yS - 2)   // native text: xN
composeTextTop = baselineY - TextLayoutResult.firstBaseline
```

For one-string TAB only, `yS = tabTop - stringSpacing`; its barline bottom is one
string-space below the string. The row/marker top is NOT `yS`. Changing metadata
height must move the number with its staff, not leave it floating at the row origin.
The Java differential executes `paintDivisions`, so these expectations are recorded
from upstream drawing calls rather than duplicated formula assertions alone.

### Grouped let-ring geometry

Use alphaTab's parameterised `LineRangedGlyph` with the caption `let ring`. For label
width `W`, height `H`, first note X `x0`, last linked note X `x1`, top `y` and fixed
staff-space `s`:

```
label = [x0-W/2, y, x0+W/2, y+H]
dashSize = s
dashGap = s/2
thickness = .16*s
lineStart = x0 + W/2 + dashGap/2
lineCentreY = y + H/2
cap = [x1, lineCentreY-s/2, x1+thickness, lineCentreY+s/2]
```

Only draw dashes/cap if this system has a linked group and `x1 > lineStart`.
A singleton or short group remains label-only; never wrap/clamp the atomic label,
move a line below it or interpolate its endpoint to a beat end. Group membership
is per voice and consecutive decoded flags. The next system starts another plain
centred label and independently applies these same rules, including its cap.
An unmarked tie does not acquire a new decoded effect flag from our layout layer.

Hardware dash rendering uses explicit filled rectangles. The first visible dash
period is found by `floor((visibleLeft-lineStart)/(dashSize+dashGap))`; clipping does
not restart the phase or scan an offscreen prefix. The fixed-size cap rectangle is
not a stroke-centred vertical line. Source `LineRangedGlyph.paint` calls generate
the checked label/rectangle coordinate fixtures.

### Source-priority effect bands

`TGAlphaEffectLayout.kt` retains MPL-2.0 and adapts `EffectSystemPlacement`.
Sort bands by category ascending, visual-stack order descending, voice ascending,
bar ascending. For a linked-chain group, query every occupied range against the
same current skyline before inserting any member:

```
magnitude = max(max(H(x), x in memberRange) + 5, all members)
H(x) = max(H(x), magnitude + memberHeight)
rowEffectHeight = max(magnitude + memberHeight)
memberTop = rowEffectHeight - magnitude - memberHeight
```

This two-phase query/commit keeps linked members at one ordinate instead of
letting each continuation lift itself. Existing TuxGuitar track lanes supply
musical clearance. When a native effects lane is taller than the effect plan,
translate by `laneHeight-planHeight` to preserve its native bottom alignment.
Atomic caption ink can consume the existing bar margins; missing boundary space
is reserved by the existing ink-envelope machinery, with hard minima rounded
upward. Different voice/effect bands do not constrain each other's rhythmic X.
Prose's fixed-parent emergency wrapping remains a documented adaptation, not a
claim that all of alphaTab's staff skyline, fonts and overlay rods were reproduced.

## Optional power-law springs with hard rods

**Native remains the default.** Select `TGSpacingMode.Spring` explicitly, with
`springSpacingRatio` in `[1.2,2]` (default 1.5). Let `D` be the shortest displayed nominal
duration capped at 960 ticks, `B=durationWidths.min()`, and `q=log2(ratio)`. Shared
onsets from every displayed track/voice are unioned. For outgoing interval `delta[i]`
and shortest nominal duration at that onset `d[i]`, compliance is

```
c[i] = (delta[i]/d[i]) * (delta[i]/D)^q * B
```

For an isolated nominal duration, doubling it multiplies compliance by `ratio`.
Simultaneous tracks share X without forcing one track's caption lane into another.
The existing measured-ink forward solver establishes a feasible natural grid; every
resulting adjacent interval becomes a hard minimum rod `m[i]`. Extra row slack solves

```
w[i] = max(m[i], F*c[i])
sum(w[i]) = max(target, sum(m[i]))
```

Sort thresholds `m[i]/c[i]`. Sweep the thresholds, maintaining the sum of still-fixed
rods and active compliance. In each active region,
`F=(target-fixedRods)/activeCompliance`; stop when this force lies before the next
threshold. Complexity is `O(n log n)` for sorting, `O(n)` afterward. Computation uses
Double for force/weights, Float only for final Canvas widths.

A target below rod total returns the minima: it does not introduce negative spacing,
compress a glyph, shrink typography, change tick order or loop forever. The fixed-size
page wrapper fragments the exceptional overwide measure and retains continuations.
Native per-bar equal slack allocation is retained between bars. Already-cut overwide
fragments retain their cached rod-safe grid and spread additional local slack
monotonically; they are not independently reoptimized spring networks. Spring mode
does not use native compact spacing. This is not alphaTab's complete global/overlay
spacing model. The **normalization difference** from alphaTab
and exact oracle scope are documented in UPSTREAM-COMPARISON.

## Public configuration and identities

```kotlin
val native = TGLayoutConfig() // TuxGuitar mode + collision-safe engraving
val spring = native.copy(
    spacingMode = TGSpacingMode.Spring,
    springSpacingRatio = 1.5f,
)
val rawJavaComparison = native.copy(
    avoidAnnotationCollisions = false,
    mergeLetRing = false,
)
```

Layout fields are logical Canvas coordinates, never physical device pixels. The view
applies density/zoom exactly once. `contentScale` is always one. Fragments preserve the
source measure index, voice index, original note object and source tick; visible
intervals are presentation metadata. Core still owns no parser, MIDI or audio clock.
