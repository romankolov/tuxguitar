# Studio workspace — Material 3 Expressive (0.1.7)

The Android sample is a **practice workspace**, not a component catalogue. The music
gets the largest continuous surface. File actions, reading choices and transport are
separate; secondary controls are progressively disclosed. This does not change the
core library's immutable-model → layout → Canvas architecture.

## Documented design basis

Reviewed on **7 November 2026**, against the official guidance:

- [Start building with Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive):
  hierarchy through color, emphasized type, containment, appropriate shape and motion.
- [Expressive motion theming](https://m3.material.io/blog/m3-expressive-motion-theming):
  theme-level physics rather than unrelated hand-tuned animations.
- [Color roles](https://m3.material.io/styles/color/roles) and
  [accessible design](https://m3.material.io/foundations/accessible-design/overview).
- [Button groups](https://m3.material.io/components/button-groups/overview),
  [icon buttons](https://m3.material.io/components/icon-buttons/overview),
  [sliders](https://m3.material.io/components/sliders/overview), and
  [bottom sheets](https://m3.material.io/components/bottom-sheets/overview).
- [AndroidX Material3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3).
  The sample pins **1.5.0-rc01** (4 November 2026). This is a release candidate, not a
  claim that Expressive is in stable Material3 1.4.0: that stable line removed the
  experimental Expressive API surface. We use real Expressive components, not a
  collection of look-alike custom chips on the older library.

Implementation API review used the official AndroidX beta02 source snapshot
[`e4d933c98df7baa7ea2b83eea6e086915961e0ff`](https://github.com/androidx/androidx/tree/e4d933c98df7baa7ea2b83eea6e086915961e0ff/compose/material3/material3),
then the API-frozen RC artifact in CI. RC-specific compile/lint and device checks are
the actual compatibility gate.

## Hierarchy and interaction map

| Area | Purpose | Controls |
| --- | --- | --- |
| Top app bar | Identify/open the score | Song title, format/measure count, open file, overflow |
| Reading bar | Focus on a part | Track sheet, follow/resume, workspace settings |
| Score surface | Read the music | Connected Tab / Score / Both toggle buttons, notation, pinch/scroll/seek |
| Transport surface | Practise | Score-position slider, previous, primary play/pause, next, stop, speed/sound shortcuts |
| Settings sheet | Deliberate customization | Display, Cursor, Sound, Theme tabs |
| Overflow | Infrequent actions | Score details, export **bundled demo** GP5, return to beginning, source/licensing information |

There are no more horizontally scrolling rows of unrelated settings above the score.
The settings tabs may scroll on a narrow/large-font screen; their *contents* scroll
vertically. File import, custom SF2/SF3, default-bank reset, external MIDI mirroring,
track focus, all-track display, spacing, chords, zoom, reading direction, all cursor
styles, effects and speed remain available. Track focus is for **reading**; the
arrangement still plays all native MIDI parts. The track sheet explicitly explains this.

Errors appear through a dismissible Material snackbar, not a new row that changes the
score height. Opening a bad file does not remove the last successfully imported score.
Loading is contained within the score and uses the Expressive `LoadingIndicator`.
Playback pauses on lifecycle stop; manual scrolling retains priority over follow.
An explicit resume-follow button is always next to the track selector.

The slider seeks score ticks, not an invented constant-tempo clock. Repeat-aware
playback remains the native TuxGuitar `DemoSequence`; a score-position slider can move
backward when the native sequence repeats a section. Changing speed pauses the player
and preserves its score position.

## Actual theme/components, not decorative approximations

- `MaterialExpressiveTheme` + `MotionScheme.expressive()` at the app boundary.
- `expressiveLightColorScheme()` / `darkColorScheme()` for the official baseline;
  optional Android 12+ dynamic wallpaper colors. System, Light and Dark choices.
- Primary/onPrimary for the one dominant play action; primaryContainer for the file
  action; neutral surface/container roles for score and controls. Paired `on*` roles
  rather than hardcoded low-contrast text. No green banner left from the old UI.
- Emphasized title styles selectively highlight the score, section headings and
  settings title. Body/label type roles replace 9–11sp ad-hoc controls. The platform
  font is used; there is no claim that a bundled variable font is installed.
- Connected `ToggleButton` shapes from `ButtonGroupDefaults`, shape-morphing primary
  `Button`, tonal icon buttons, `Slider`, `Switch`, modal sheets, tabs and snackbars.
- The score color adapter maps semantic roles to notation colors. Notes stay onSurface;
  the playhead alone uses primary. High-contrast paper is an independent preference.
- Material Symbols Rounded are individually vendored as small vector resources, not
  a giant icon-font/extended-icon dependency. Apache-2.0 license included. Source:
  [`google/material-design-icons@10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web).

## Responsive layout and accessibility

Phone portrait uses a compact header, full-width score card and a contained bottom
transport. At width ≥840dp, or width ≥640dp with height <480dp (landscape phone), the
transport and settings move into a 280dp side panel. The score is still the flexible
primary pane. The side panel can scroll if the window is short.

Insets are handled at the app boundary, with edge-to-edge status/navigation chrome.
There is no fixed-height wall of controls above the canvas. Buttons have at least
48dp interactive targets, standard semantics and named icon actions. Settings rows
are single switch targets rather than nested competing click handlers. Titles can
ellipsize visually while retaining their full accessible text and details action.
Labels may wrap; typography is not reduced to make a row fit. System 200% text is an
explicit device test. The core's native notation-font policy is unchanged.

An Android ViewModel owns the decoded source, native player/bank and notation state,
so window resize/rotation does not silently return to the demo or lose the playhead.
Persistable document access and small saved-state values allow re-import after process
death; sound banks themselves remain process-local. Playback pauses on lifecycle stop.

Preferences are saved locally (theme, display, cursor, effects); invalid old enum values
or invalid padding fall back safely. Selected custom banks are session-local, as is
playback; we do not display a remembered bank name without having loaded that bank.

This is not a claim of exhaustive TalkBack/device/RTL/foldable parity. Device tests
cover important targets, large text, dark theme, saved choices and actual expanded
windows. Physical-device user research remains valuable.

## Capsule inner margin

The old six-unit outline could cut through an eight-unit note. `capsuleInset` was only
**outside vertical row clearance**, not padding *inside* the capsule. These are now
separate:

```kotlin
TGCursorConfig(
    style = CursorStyle.Capsule,
    width = 1.5f,              // stroke
    capsuleWidth = 6f,         // MINIMUM outer width, not a tight box
    capsuleInset = 6f,         // row top/bottom clearance
    capsuleInnerPadding = 3f,  // note ink → inner stroke edge
)
```

The renderer takes the maximum left/right distance of visible fret/head bounds from
the beat anchor (at least 4 units), including multi-digit frets and displaced voices.
For ink half-width `a`, padding `p`, requested stroke `S`, minimum outer width `W`,
row height `h`:

```
requestedWidth = max(W, 2 * (a + p + S))
w = min(requestedWidth, h)
i = min(capsuleInset, (h-w)/2)
H = h - 2*i
s = min(S, w/3, H/3)
innerWidth = w - 2*s
```

For an ordinary eight-unit note, defaults yield outer width **17**, inner width **14**:
8 units of ink + **3 clear units on either side**. The capsule is still transparent,
never a filled highlight. Tiny rows degrade safely (padding may have to reduce), never
overflow or become a solid bar. Stroke, density, zoom, fractional glide, opacity and
follow keep the existing single-transform pipeline. This does **not** widen measures
or change any immutable note/tick identity. See [PORTING.md](PORTING.md).

## Release build and signing policy

The user chose an **evaluation release**. It is a real `release` variant:

- `debuggable=false`, R8 optimization/obfuscation and resource shrinking enabled;
- a separate 3072-bit RSA evaluation signing key, **not** the Android debug key;
- key/password generated privately in the build runner's temporary directory;
- no keystore/password in Git, caches, logs or uploaded artifacts;
- the key is discarded with that runner, so differently signed builds cannot upgrade
  one another. **Uninstall the old debug/evaluation app before installing this APK.**
  This also deletes its local app preferences/custom bank copy.

This is not a persistent production signing identity or a Play Store release. For
updateable distribution, replace the temporary-key step with a protected keystore
from your own CI secret store, adapt the signer policy check and retain that identity
securely. Never send signing material through chat or commit it to the repository.

For a private local build, create your own PKCS12 keystore outside the checkout and
set `TUX_RELEASE_STORE_FILE` and `TUX_RELEASE_STORE_PASSWORD` in your shell environment
(alias `tux-compose-evaluation`). Run `./gradlew :sample-android:assembleRelease`.
Do not put passwords into shell history, Gradle files or command-line arguments.
The CI policy verifier expects the evaluation certificate subject configured in
`tools/prepare_release_signing.sh`.

### Publication gates

1. JVM/core/native-parity tests, both variant lint, debug instrumentation assembly,
   release APK and core AAR assembly.
2. Verify the **release** APK's bank hash, four native ABIs and dependencies,
   64-bit 16KiB / 32-bit 4KiB alignment, license payload and audio-free core AAR.
3. `apksigner` verification, non-debug certificate, manifest version/non-debuggable
   check, nontrivial R8 mapping; record digest/certificate in `BUILD-VERIFICATION.json`.
4. Full detailed Android debug instrumentation (pixels, fonts, interactions, PCM).
5. Uninstall debug; install the **exact hashed release artifact** from the build job.
   Black-box accessibility-tree checks exercise GP import, native audio/playback,
   navigation, capsule/settings, dark theme, 200% text and an expanded window. A
   non-debuggable `run-as` rejection is checked too. This catches R8/JNI issues that
   a debug-only suite would miss.
6. Only after both build/device jobs succeed, publish `sample-android-release.apk`,
   core AAR, SHA-256 sums and release verification metadata. Old rolling debug asset
   is removed. Full screenshots/reports and R8 mapping stay in Actions diagnostics.

Toolchain: Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Android Compose 1.12.1),
Material3 1.5.0-rc01, AGP 9.2.1, Gradle 9.4.1, Java 17, compileSdk 37 / targetSdk 35 /
minSdk 24. Core uses the supported single-variant `com.android.kotlin.multiplatform.library`
plugin; the Android sample/synth use AGP built-in Kotlin. No legacy opt-out is needed.
Generated demo assets and native libraries are registered through AGP's variant sources
API rather than legacy `AndroidSourceSet` casts.
Material remains **sample-only**. The core still needs no Material theme or playback.
