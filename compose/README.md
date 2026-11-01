# Tux Compose

**Immutable score → shared layout → Compose Canvas.** A standalone rendering library and an Android showcase built alongside (not inside) the legacy TuxGuitar applications.

The library has **no decoder, file access, clock, MIDI, synthesizer, audio dependency, Android View, SWT or AWT**. The showcase intentionally has these integrations, in separate modules.

## Modules

| Module | Responsibility | Platforms |
|---|---|---|
| `tux-compose-core` | `com.tuxcompose.model`, `.layout`, `.renderer`, `.component` | Android + JVM; optional iOS targets; all implementation in `commonMain` |
| `demo-tuxguitar` | Existing Java GP3/4/5, GPX, GP7/GP, TG and MIDI readers, immutable-model adapter, original MIDI event/repeat engine | JVM/Android, **demo only** |
| `demo-synth` | Existing Android-compatible Gervill and Java Sound shim | Android, **demo only** |
| `sample-android` | Compose screen, Storage Access Framework picker, transport, soundfonts and optional hardware MIDI output | Android 7.0+ |

**Status:** first implementation, not a claim of complete desktop engraving/editor parity. See [porting notes and explicit limitations](docs/PORTING.md). The original duration math, spelling tables, lane permutations, beam slope rules and 32 vector models are ported, with differential tests against the Java source. The UI is a native Compose component, not a screenshot or a legacy View embedded in Compose.

`TablatureView(onLayout = { ... }, drawOverlay = { layout -> ... })` exposes cached
layout geometry and a custom Canvas overlay. Overlay coordinates are logical song units;
the component applies the same zoom/scroll transform as the notation. Palette-only changes
do not rebuild geometry. Overlays should draw only the visible region for large scores.

## Build and install

Requirements: JDK 17, Android SDK 35, and network access to Gradle/Google/Maven repositories.

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

GitHub Actions: [Tux Compose · build, test & APK](https://github.com/romankolov/tuxguitar/actions/workflows/tux-compose.yml). Successful builds upload the **debug-signed installable APK**, release AAR, unit-test/lint diagnostics and emulator reports. Download the newest successful run's `tux-compose-apk-N` artifact. A debug APK is for evaluation, not a production-signed Play Store release.

## Consume the library

In the standalone build the demo simply uses:

```kotlin
dependencies { implementation(project(":tux-compose-core")) }
```

For an external project, publish to Maven Local first, add `mavenLocal()` to repositories, then use:

```kotlin
commonMain.dependencies {
    implementation("com.tuxcompose:tux-compose-core:0.1.0")
}
```

Use Kotlin 2.1.21 and Compose Multiplatform 1.8.2 (or compatible newer versions). Android minimum SDK is 24. No material theme is required by core; Material3 is used only by the sample.

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
                style = CursorStyle.Glow,
                width = 2f,
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
- Repeat-close values are **additional repetitions**: `1` means two passes, exactly as in TuxGuitar. Alternate-ending bits are preserved.
- Multi-track mode aligns columns by measure index and shared maxima; tracks must have matching measure counts, start ticks and meters. Invalid alignment is rejected instead of silently desynchronizing the score.
- Core never mutates or normalizes the caller's song. Supply rests for gaps if you want TuxGuitar-style completed measures. The demo adapter runs the original `autoCompleteSilences` before snapshotting.

## Interaction and customization

- Single-finger drag/fling or mouse wheel scrolls; both axes are pannable for oversized bars.
- Pinch zoom; double-tap zoom; programmatic `setZoom`, `scrollTo`, `scrollBy`.
- Tap selects a beat/string, moves the cursor, and reports `TGHitResult`; long-press extends a range on the same track. `selection`, `selectedString`, and cursor are controlled state.
- Arrow keys navigate beats/measures; Escape clears selection. Accessibility exposes track/tick state and next/previous/zoom actions without thousands of invisible per-note composables.
- Cursor line/block/glow, optional blink/pulse, independent color, width, opacity, note highlighting and automatic follow.
- Score/tab/mixed; wrapped systems or continuous horizontal flow; optional multi-track alignment, justification, repeated system signatures, compact spacing, tempo and measure numbers.
- Paper/dark/high-contrast palettes; custom text/note font families, weight and sizes; string/staff/note/padding/effect/system metrics.

Everything uses a **bounded viewport canvas**, not a giant song-sized surface. Text metrics are cached and shared with layout. Cursor/selection are independent layers; cursor updates do not rebuild notation geometry. Layout exposes bounds and hit testing directly for custom editor shells and export implementations.

## Tests, provenance and licensing

- `commonTest`: duration/tuplet validation, immutable snapshots, spacing, wrapping, multi-track alignment, hit testing, culling, beaming, empty/extreme inputs and state navigation.
- `demo-tuxguitar:test`: **differential tests** for every supported duration/dot/tuplet, all pitches/keys/enharmonic spellings and all 19 lane permutations; GP3/4/5 round trips; original MIDI event generation.
- Android instrumentation: real import, screen controls, theme/layout changes and **non-silent PCM from the bundled guitar soundfont**. Audio rendering is verified, not a claim that a human auditioned speaker output.
- `python3 tools/verify.py`: reproducible glyph source hashes and a fail-closed check against platform/parser/player imports or demo dependencies in core.

Derived TuxGuitar code and glyphs are **LGPL-2.1-or-later**; see [the existing license](../docs/LICENSE) and [NOTICE](NOTICE.md). The optional Gervill demo includes GPLv2 with Classpath Exception code and existing soundfont assets; their notices remain unchanged. Do not relicense the port as proprietary/MIT merely because the drawing API changed. When distributing derived binaries, include the relevant notices and comply with the applicable source/relinking requirements.
