# Tux Compose

**Immutable score → shared layout → Compose Canvas.** A standalone rendering library and an Android showcase built alongside (not inside) the legacy TuxGuitar applications.

The library has **no decoder, file access, playback clock, MIDI, synthesizer, audio dependency, Android View, SWT or AWT**. The showcase intentionally has these integrations, in separate modules.

## Modules

| Module | Responsibility | Platforms |
|---|---|---|
| `tux-compose-core` | `com.tuxcompose.model`, `.layout`, `.renderer`, `.component` | Android + JVM; optional iOS targets; all implementation in `commonMain` |
| `demo-tuxguitar` | Existing Java GP3/4/5, GPX, GP7/GP, TG and MIDI readers, immutable-model adapter, original MIDI event/repeat engine | JVM/Android, **demo only** |
| `demo-synth` | FluidSynth 2.6.2 JNI + pinned GeneralUser GS 2.0.3 bank | Android, **demo only** |
| `sample-android` | Compose screen, Storage Access Framework picker, transport, soundfonts and optional hardware MIDI output | Android 7.0+ |

**Status:** first implementation, not a claim of complete desktop engraving/editor parity. See [porting notes and explicit limitations](docs/PORTING.md). Native compact/noncompact spacing, headers, row packing/justification, vertical lanes, duration/spelling math, beam rules and 32 vector models are ported, with differential tests against Java. Oversized-bar wrapping, merged let-ring spans and optional adaptive clearance are documented extensions. The UI is a native Compose component, not a screenshot or a legacy View embedded in Compose.

`TablatureView(onLayout = { ... }, drawOverlay = { layout -> ... })` exposes cached
layout geometry and a custom Canvas overlay. Overlay coordinates are logical song units;
the component applies the same zoom/scroll transform as the notation. Palette-only changes
do not rebuild geometry. Overlays should draw only the visible region for large scores.

## Build and install

Requirements: JDK 17, Android SDK 35, Python 3 for the verification tools, and build-time network access to Gradle/Google/Maven/GitHub. The showcase installs NDK 27.3.13750724 / CMake 3.22.1 as needed and downloads checksum-pinned synth/bank inputs; the core-only AAR needs neither.

```sh
cd compose
./gradlew :tux-compose-core:desktopTest :demo-tuxguitar:test
./gradlew :sample-android:lintDebug :sample-android:assembleDebug
adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk
# With an Android device or emulator:
./gradlew :sample-android:connectedDebugAndroidTest
# Build local Maven artifacts, including KMP metadata:
./gradlew :tux-compose-core:publishToMavenLocal
# On macOS, opt in to native compilation:
./gradlew -PenableIos=true :tux-compose-core:compileKotlinIosSimulatorArm64
```

The separate build does not change the original Maven/Android application build. There are no dynamic dependency versions. A Gradle wrapper is included.

GitHub Actions: [Tux Compose · build, test & APK](https://github.com/romankolov/tuxguitar/actions/workflows/tux-compose.yml). Successful builds upload the **signed, non-debuggable release APK**, release AAR, R8/variant-lint diagnostics and emulator reports. The user chose an ephemeral **evaluation release** key, separate from Android's debug key. Uninstall previous differently signed builds before installing; this is not a persistent production/Play Store signing identity. Download the newest successful run's `tux-compose-apk-N` artifact. After both JVM/build and device-test jobs pass (including black-box checks on the exact release APK), the workflow publishes the [latest verified APK and AAR](https://github.com/romankolov/tuxguitar/releases/tag/tux-compose-preview) with SHA-256 checksums.

## Consume the library

In the standalone build the demo simply uses:

```kotlin
dependencies { implementation(project(":tux-compose-core")) }
```

For an external project, publish to Maven Local first, add `mavenLocal()` to repositories, then use:

```kotlin
commonMain.dependencies {
    implementation("com.tuxcompose:tux-compose-core:0.1.7")
}
```

Use Kotlin 2.4.21 and Compose Multiplatform 1.12.2 (or compatible newer versions). Android minimum SDK is 24. No material theme is required by core; Material3 is used only by the sample.

## Complete model-only screen

This example contains **no parser or playback**. Buttons manipulate the rendering cursor. An application's own transport can call `state.cursor.seek(tick)` on the UI thread.

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*

@Composable
fun ScoreScreen() {
    val song = remember {
        TGSong(
            title = "First study",
            artist = "Your application",
            tracks = listOf(
                TGTrack(
                    id = 1,
                    name = "Acoustic guitar",
                    // STRING NUMBER order: high E first. String 1 uses tuning[0].
                    stringTuning = listOf(64, 59, 55, 50, 45, 40),
                    measures = listOf(
                        TGMeasure(
                            number = 1,
                            header = TGMeasureHeader(
                                TGTimeSignature(4, 4), TGKeySignature(0), tempo = 108
                            ),
                            start = 960,
                            beats = List(4) { i ->
                                TGBeat(
                                    start = 960L + i * 960L,
                                    duration = TGDuration(4),
                                    voices = listOf(
                                        TGVoice(0, notes = listOf(TGNote(value = i * 2, string = 1)))
                                    )
                                )
                            }
                        )
                    )
                )
            )
        )
    }
    val state = rememberTablatureState(song, initialCursorTick = 960)
    val config = remember {
        TGLayoutConfig(
            style = TGLayoutStyles.Mixed,
            orientation = TGLayoutOrientation.Page,
            stringSpacing = 14f,
            staffSpacing = 10f,
            noteHeadRadius = 5f,
            staffPadding = 16f,
            colors = TGColorPalette.Dark.copy(cursor = Color(0xFF80EFB2)),
            typography = TGTypography(noteSize = 11f, textSize = 9f),
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row {
            Button(onClick = { state.cursor.previousBeat() }) { Text("Previous") }
            Button(onClick = { state.cursor.nextBeat() }) { Text("Next") }
            Button(onClick = { state.setZoom(state.zoomScale * 1.2f) }) { Text("Zoom") }
        }
        TablatureView(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = config,
            cursorConfig = TGCursorConfig(
                style = CursorStyle.Capsule, // hollow, not a filled pill
                width = 1.5f,               // stroke width
                capsuleWidth = 6f,          // minimum outer width, expanded to clear note ink
                capsuleInset = 6f,          // top/bottom margin inside the row
                capsuleInnerPadding = 3f,   // clear space inside the outline around each note
                highlightNotes = true,
                animation = CursorAnimation.Pulse,
            ),
            onBeatClicked = { tick, track -> println("Track $track, tick $tick") },
        )
    }
}
```

The complete Android host with real decoding and playback is [MainActivity.kt](sample-android/src/main/kotlin/com/tuxcompose/sample/MainActivity.kt). Startup creates an original study with TuxGuitar models, writes GP5 bytes using TuxGuitar's writer, then **reopens those bytes with the actual GP5 reader**. “Open file” reads a local document; “Export demo GP5” lets you keep the original composition. There are no network permissions, downloaded tabs or bundled copyrighted songs.

## Model contracts

- Inputs use immutable `PersistentList`s. Convenience constructors accept `List` and defensively snapshot it. To replace an immutable list with `copy`, call `.toPersistentList()`.
- TuxGuitar's time base is **960 ticks per quarter**, conventionally starting at tick 960. Tick 0 is also supported; provide explicit measure starts, especially for empty measures.
- `TGBeat.preciseStart` preserves the upstream precise unit for tuplets. `TGDuration` retains both precise and legacy rounded times. Each voice can have its **own duration**; the beat duration is a convenience fallback.
- Tuning is **string-number order, high-to-low for a standard guitar**: `[64,59,55,50,45,40]`. This corrects the ambiguous low-to-high example in the initial blueprint. Strings are one-based, tuning indices zero-based.
- Key signatures use **TuxGuitar's encoding**: `0` natural, `1..7` sharps, `8..14` flats, not signed fifths.
- An empty, active voice means **rest**. `empty=true` means the voice does not participate. A fret of `-1` means a muted/dead note, **not** a rest.
- Repeat-close values are **additional repetitions**: `1` means two passes, exactly as in TuxGuitar. Alternate-ending bits are preserved. `tripletFeel` retains 0/1/2 (straight/eighth/sixteenth); optional `TGTempoMark(value,base,dotted)` preserves native tempo annotations even when quarter BPM is unchanged.
- Multi-track mode shares measure columns and header maxima; tracks must have matching counts, starts and meters. Native uses the original per-track effect-slack distribution; Adaptive additionally enforces a shared interior-onset map. Invalid measure alignment is rejected.
- Core never mutates or normalizes the caller's song. Supply rests for gaps if you want TuxGuitar-style completed measures. The demo adapter runs the original `autoCompleteSilences` before snapshotting.

## Interaction and customization

- Single-finger drag/fling or mouse wheel scrolls. Page mode wraps at the parent width; horizontal mode (or `fitPageToWidth=false`) allows horizontal panning.
- Pinch zoom; double-tap zoom; programmatic `setZoom`, `scrollTo`, `scrollBy`.
- Tap selects a beat/string, moves the cursor, and reports `TGHitResult`; long-press extends a range on the same track. `selection`, `selectedString`, and cursor are controlled state.
- Arrow keys navigate beats/measures; Escape clears selection. Accessibility exposes track/tick state and next/previous/zoom actions without thousands of invisible per-note composables.
- Cursor line/block/glow/hollow capsule, optional blink/pulse, independent color, width, opacity, note highlighting and automatic follow.
- Score/tab/mixed; wrapped systems or continuous horizontal flow; optional multi-track alignment, justification, repeated system signatures, compact spacing, tempo and measure numbers.
- Paper/dark/high-contrast palettes; custom text/note font families, weight and sizes; string/staff/note/padding/effect/system metrics.

Everything uses a **bounded viewport canvas**, not a giant song-sized surface. Text metrics are cached and shared with layout. Cursor/selection are independent layers; cursor updates do not rebuild notation geometry. Layout exposes bounds and hit testing directly for custom editor shells and export implementations.

## Native spacing by default (0.1.5)

The default now uses **TuxGuitar's actual layout algorithms**, rather than the custom
"balanced" solver introduced in 0.1.4. Both native apps default to compact spacing; the
library previously did not. Vertical layout now reserves the stem direction actually used,
not both possible directions, and uses native track/lane spacing.

```kotlin
TGLayoutConfig(
    spacingMode = TGSpacingMode.TuxGuitar, // default: original measure/beat/layout equations
    compact = true,                      // native default; ineffective for multiple tracks
    justifySystems = true,               // equal extra width per measure on full rows
    showChords = false,                   // Android native default; metadata is retained
    showChordNames = false,
)
```

The sample's **Customize → Compact / Fill rows / Chord diagrams / Chord names** controls
expose the corresponding display choices. Native desktop also defaults compact on but
shows diagrams and has different page margins; match flags and viewport when comparing.

The port includes minimum voice widths, adjacent compact chords, the noncompact global
chord/quarter grid, actual precise endings, header/signature maxima, per-track bend slack,
native greedy row packing, **proportional** beat justification and native vertical lanes.
It does not measure prose/custom-font collisions to silently change ordinary native widths.
A half + quarter + eighth + eighth spans **86** natural rhythmic units in native compact
mode, versus **160** noncompact. This is a concrete fixture, not a universal density ratio.

Actual Java layout differentials compare every onset, measure bounds, row membership,
headers and vertical lanes at fractional scale, including real GP3/4/5 showcase imports.
See [the source map, mathematics and precise scope](docs/PORTING.md).

For large/custom fonts or accessibility where additional collision clearance is preferred
rather than the original fixed grid, explicitly opt in:

```kotlin
TGLayoutConfig(
    spacingMode = TGSpacingMode.Adaptive, // Compose extension, not exact native equations
    compact = false,
    optimizeSpacing = true,              // this switch applies ONLY to Adaptive
    respectSystemFontScale = true,
    showChords = true,
)
```

**Migration:** `optimizeSpacing=false` alone no longer selects the old layout. Select
Adaptive first if you need that comparison. New native defaults include a 5-unit left
margin, independent 10-physical-pixel right margin, 15-unit first-track spacing, 5-unit
track spacing, 8-unit chord font and 6-unit grace font. Native fonts ignore Android's sp
multiplier unless explicitly enabled. For a previous-style visual setup, also set
`pagePadding=12f`, `systemSpacing=20f`, `showChords=true` and your previous typography.
No musical timing, note identity or playback/parser boundary is changed.

## Hollow capsule and let-ring spans (0.1.6)

The sample starts with **Capsule**; the Cursor settings offer all four styles.
The library keeps Line as its compatibility default. Select `CursorStyle.Capsule` in
`TGCursorConfig`: `width` is its stroke (default 1.5), `capsuleWidth` its minimum outer
width (6), `capsuleInset` its top/bottom row margin (6), and `capsuleInnerPadding` the
clear space between the note ink and inner stroke edge (3). In 0.1.7 the outline
expands around actual visible fret/head bounds: ordinary eight-unit ink gets a
17-unit outer / 14-unit inner width, leaving three units clear on either side. These are **logical notation units**, not
already density-scaled pixels. The stroke centreline is inset by half its thickness so
all of the stroke remains inside its bounds. Tiny rows clamp the dimensions/stroke while
retaining a transparent interior. Smooth movement, opacity/blink/pulse and follow settings
work unchanged; fractional playhead positions are not snapped to pixels.

`TGLayoutConfig(mergeLetRing = true)` is enabled by default. Consecutive marked attacks
in a voice now display **`let ring - - - - |`**, covering the last marked duration, not a
label at every note. Reattacks without the flag, rests, time gaps and repeat/ending
boundaries stop a run. Tied continuations sustain it without another onset label.
Overlapping voice runs share a display span, but tracks remain independent. A row break
or oversized-bar fragment shows `(let ring)` with continuation dashes; only the actual
end receives a cap. A very short segment puts its line below its caption instead of
losing the cap or drawing through the words.

Effects in the span's middle or after it are measured and packed into separate vertical
lanes; beat prose has its own measured, word-wrapped lanes. Whole captions are retained,
not ellipsized or shrunk. The layout grows those rows vertically, **never widens native
rhythmic spacing or changes source ticks, notes or bar identities**. Rows without a
let-ring run retain their existing annotation layout. An indivisible custom-font glyph
wider than the entire viewport is clipped, as with the other fixed-size notation.

This is a deliberate Compose enhancement: upstream `TGNoteImpl` prints separate `L.R`
labels and does not implement this merged span. `mergeLetRing=false` restores per-beat
captions and the existing lane reservations. Native Java differential fixtures containing
let-ring explicitly disable the enhancement when comparing native geometry.

`TGStaffLayout.annotations` stores row-local caption/line/cap geometry;
`layout.visibleAnnotations(staff, viewport)` uses a cached interval index, including
continuations whose label is offscreen. The normal `TGCanvasRenderer.render` includes
this pass. Custom fragment renderers using `renderMeasure` must additionally render the
staff's cached `renderAnnotation` and `renderConnection` overlays; a measure with
`plannedAnnotations=true` deliberately omits its old repeated beat captions.

## Page fitting, cursor glide and cooperative auto-scroll

Page/vertical mode now keeps **exactly the parent width**, including fractional density and
zoom. **Notation never shrinks to fit a row.** Ordinary measures wrap as whole units using
TuxGuitar's greedy system/justification rules. Only a measure wider than a complete row is
split at rhythmic boundaries, preferring metrical groups/subdivisions. Its fragments keep
the same fonts, note-head radii and staff/string spacing as every other row. The final
continuation shares its row with following measures whenever they fit, using the same
greedy packing rule instead of forcing another row. The musical bars are not merged,
and explicit line breaks remain authoritative.

Ties, hammers/pull-offs and slides have outgoing/incoming row-local segments; beams stop at
the break and tuplet/ending brackets continue without inventing another measure/repeat.
Bends, chords, text and other beat effects move with their owning beats, with space reserved
for their printed size. Tracks share the same cuts; a sustained voice is displayed with tied
continuations rather than a new model attack. The original immutable song is untouched.
A shared row uses common vertical lanes, so a following chord/text/picking mark also
repositions the preceding continuation correctly.
Cursor, selection and hit testing use each fragment's visible tick interval.

`TGLayoutConfig(fitPageToWidth = false)` keeps oversized measures intact. Native page
justification then follows the editor's potentially crowded negative spacing. For natural-
width panning, use Horizontal mode or disable both fitting and justification. Blank margins
may reduce at extremely narrow widths,
not glyph sizes. If even an indivisible glyph/effect/header cannot fit the viewport, it is
clipped rather than silently shrunk; use a wider viewport, lower explicit zoom or overflow
opt-out in that physically impossible case.

`onLayout` exposes `TGMeasureLayout.startTick/endTick`, `isFirstFragment/isLastFragment`,
`TGBeatLayout.startTick/endTick`, voice `isContinuation/sourceTick`, and each staff's cached
`connections` / `annotations` and each beat's cached `stroke` / `pickStroke` line geometry for custom
overlays. `measure` still references the complete source model;
`measureIndex` is not renumbered. `contentScale` is retained for compatibility and is always 1.

Strumming arrows and picking marks are separate, **beat-level** properties, matching
`TGBeatImpl.paintStroke/paintPickStroke` rather than generic text arrows:

```kotlin
val strummedBeat = TGBeat(
    start = 960,
    voices = listOf(TGVoice(notes = listOf(TGNote(0, 1), TGNote(2, 5), TGNote(0, 6)))),
    stroke = TGStroke(TGStrokeDirection.Down, value = 16),
    pickStroke = TGStrokeDirection.Down, // optional, independent picking symbol
)
```

`Up/Down` retain TuxGuitar's musical direction encoding (`1/-1`), **not screen Y**. With
high strings drawn at the top, its `Up` strum arrow points down the page and `Down` points
up; the picking marks are the original V and heavy-capped staple. Mixed mode draws the
strum once on the tablature; score-only mode uses the score staff. `None` hides a mark.
`pickStrokeSpacing` defaults to the original eight-unit symbol height. A synthetic sustained
continuation does not reprint either attack mark. The demo's GP5 study now contains both
directions, written/read by the actual TuxGuitar codec, with stroke speed metadata preserved.

```kotlin
TablatureView(
    state = state,
    cursorConfig = TGCursorConfig(
        visible = true,
        follow = true,
        smoothMovement = true,          // sweep through each beat using the supplied tick
        movementAnimationMillis = 80,   // short tween for coarse position updates; 0 = exact tick
        smoothScroll = true,
        scrollAnimationMillis = 280,
        followResumeDelayMillis = 1200,
    ),
)
// Explicit Follow button after browsing away:
// Button(onClick = { state.resumeAutoScroll() }) { Text("Follow cursor") }
```

Continue feeding `state.cursor.seek(tick)` from your own transport on the UI thread. Core
never starts a musical clock or guesses playback timing. Continuous ticks give a continuous
sweep; `smoothMovement = false` restores beat-snapped movement. Backward seeks/repeats,
track changes and wrapped-system transitions do not animate backwards across the page.
Note highlighting remains attached to the actual active beat.

Auto-follow requires **both `visible` and `follow`**. Pointer holds, drag, wheel, pinch and
fling immediately interrupt an in-flight follow animation. Programmatic `scrollTo`/`scrollBy`
also yield to the caller. After the idle grace, following resumes only if the playhead is
still/again in the visible viewport: browsing to other bars does not snap you back. Call
`state.resumeAutoScroll()` (or toggle Follow on) to rejoin explicitly. Read
`state.isUserScrolling` / `state.isAutoScrollSuspended` for your own UI. When following is
already active it continues smoothly across systems, including bringing the next row into
view. The sample exposes Cursor glide, Smooth scroll, Show cursor and Follow controls.

## Showcase synthesizer and sound (0.1.6)

The Android sample uses **FluidSynth 2.6.2 + GeneralUser GS 2.0.3**, replacing its old
Gervill/lightweight-bank backend. TuxGuitar still generates the actual notes, repeats,
controllers and bend events; its GM router preserves percussion and independent bend
channels. FluidSynth renders stereo PCM, SoundFont envelopes/filters/modulators, sustain,
reverb and chorus. Customize has **Reverb**, **Chorus**, **SoundFont** and **Reset bank**
controls; SF2/SF3 loading uses the document picker, not a network service. Pause/background
stops the audio device/thread. Hardware MIDI output remains available.

This does **not** add playback, parsers, JNI or a SoundFont to the rendering library AAR.
All SDK/bank downloads are build-only, version/SHA-256 pinned, under ignored generated
folders. The installed app remains offline. See [SYNTH.md](docs/SYNTH.md) for full source
provenance, native build versions, mathematical MIDI/PCM conventions, licensing/relinking,
custom-bank behavior, footprint and test/hardware limitations.

## Tests, provenance and licensing

- `commonTest`: duration/tuplet validation, immutable snapshots, spacing, wrapping, multi-track alignment, hit testing, culling, beaming, empty/extreme inputs and state navigation.
- `demo-tuxguitar:test`: full native Java layout differentials (`NativeLayoutParityTest`) compare widths, every onset, row packing/justification, header flags and vertical lanes across styles, compact modes, fractional scale, effects, polyphony and real GP3/4/5 imports. The actual Android style class is compiled into tests, not copied. `native-layout-parity.json` is included in CI diagnostics. Also tests every supported duration/dot/tuplet, all pitches/keys/enharmonic spellings and all 19 lane permutations; whole-measure and projected-fragment row packing against `TGLayoutVertical.getTempLines`; recorded strum/picking vectors against the actual Java painters; GP3/4/5 strum round trips (plus GP4/5 picking marks); original MIDI event generation.
- Android instrumentation: native-default mixed-duration reflow, actual-font zoom/pointer transforms, native/system-font-scale policy, real import, screen controls, theme/layout changes, native MIDI-to-AudioTrack playback and **non-silent PCM from the full GeneralUser GS bank**. FluidSynth instrumentation covers version, melodic/drum presets, reverb tails, chorus changes, RPN pitch bends and invalid-bank/close safety. Actual-font annotation tests also cover caption clearances, terminal-cap pixels, all three notation styles, font scale/reflow/zoom and hollow capsule pixels. Additional frame-clock/pointer tests cover unscaled dense-bar wrapping, packed continuation/next-bar hit testing, rendered incoming/outgoing effect pixels and both strum/picking directions, parent-width fitting, cursor tween frames, smooth follow, hidden cursors and manual interruption/resume. Audio rendering is verified, not a claim that a human auditioned speaker output.
- `python3 tools/verify.py`: reproducible glyph source hashes and a fail-closed check against platform/parser/player imports or demo dependencies in core.

Derived TuxGuitar code and glyphs are **LGPL-2.1-or-later**; see [the existing license](../docs/LICENSE) and [NOTICE](NOTICE.md). The showcase now dynamically links LGPL FluidSynth and packages GeneralUser GS under its own license; full dependency notices are in `demo-synth/src/main/assets/licenses/` and the APK. See [synth provenance and relinking](docs/SYNTH.md). Original Gervill files remain untouched in the repository but are no longer compiled into this sample. Do not relicense the port as proprietary/MIT merely because the drawing API changed. When distributing derived binaries, include the relevant notices and comply with the applicable source/relinking requirements.

## Focused Material 3 Expressive workspace (0.1.7)

The sample now uses the real Material3 1.5.0-rc01 Expressive theme, connected/morphing
toggle buttons, tonal controls, emphasized typography, loading shapes and semantic
light/dark/dynamic color roles. The score owns the primary surface; Display, Cursor,
Sound and Theme settings live in a tabbed sheet. Landscape/expanded windows move
playback into a side pane. Preferences survive recreation, and 200% UI text retains
48dp primary targets without shrinking notation.

See **[UI.md](docs/UI.md)** for the official guidance links, component/interaction map,
adaptive behavior, capsule padding math, toolchain and release signing/verification
policy. The rolling download is now
**[sample-android-release.apk](https://github.com/romankolov/tuxguitar/releases/download/tux-compose-preview/sample-android-release.apk)**,
not a renamed debug build.
