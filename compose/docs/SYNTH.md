# Showcase audio: FluidSynth 2.6.2 + GeneralUser GS 2.0.3

This is **Android showcase functionality only**. `tux-compose-core` has no MIDI,
SoundFont, JNI, file access, audio output, synthesizer or playback dependency.
The library AAR is not enlarged by any of the audio SDK/bank assets below.

## What changed

The sample now uses the official **FluidSynth 2.6.2** Android API-24 SDK instead of the
legacy Java Gervill backend, and **GeneralUser GS 2.0.3** instead of the lightweight
Magic Sound Font fragments. The complete bank includes melodic presets, GS variations
and drum kits. This improves the available sampled instruments as well as enabling
FluidSynth's native SoundFont envelopes, filters, modulators, sustain, pitch bends,
stereo reverb and chorus. Audio quality remains subjective and dependent on the chosen
bank, device and speaker; automated PCM tests are not a human listening evaluation.
This is not a guitar amp/distortion simulator or a replacement for recorded instruments.

The sample starts with restrained reverb/chorus enabled. Customize → **Reverb** /
**Chorus** toggles the native processors live. **SoundFont** opens an SF2/SF3 through
Android's document picker (256 MiB limit); **Reset bank** restores GeneralUser GS.
A custom bank is app-private and lasts for the current session. There is no runtime
network permission or bank download. Sample playback still uses the original TuxGuitar
MIDI/event/repeat engine; the synthesizer does not replace that musical logic.

## Architecture and numerical conventions

- `demo-tuxguitar`: original decoders, event generation, tempo/repeat map.
- `sample-android/DemoAudio`: original `GMChannelRouter` / `GMChannel` bend/percussion
  routing → standard MIDI messages → `demo-synth/FluidSynth` JNI wrapper.
- `demo-synth`: the official dynamic native engine; one monitor serializes PCM, MIDI,
  bank loading and close. The render thread never owns a lock during device writes.
- Stereo signed 16-bit PCM at **44,100 Hz**, in blocks of **512 frames** (~11.61 ms).
  One pull-render thread, 256-voice limit, upstream fourth-order interpolation, gain 0.35.
  `AudioTrack` uses media/music attributes and requests low-latency mode on API 26+.
  Actual latency depends on the Android audio backend; no universal latency is promised.
- The old seventh-point interpolation is intentionally not requested: upstream 2.6 changed
  its meaning and documents its earlier ringing limitations. The supported fourth-order
  default is used rather than an expensive “highest quality” flag.
- Native TuxGuitar bends are 7-bit, centre **64**. Sending MIDI LSB=0, MSB=value yields
  `bend14 = 128 × value`, centre **8192**. RPN bend-range messages remain intact:
  `frequencyRatio = 2 ^ ((rangeSemitones × (bend14-8192)/8192) / 12)`.
  For range 12 and value 96 this is a six-semitone bend, ratio `sqrt(2)`.
- Native bank 128 is a percussion-routing sentinel, not an actual 7-bit CC value. The
  original router chooses the percussion channel; the wire bank controller becomes 0.
- Score CC91/93 sends remain intact. The user's ambience preference adds small SoundFont
  generator offsets (180/80 of the 0–1000 reverb/chorus send scale), rather than replacing
  those native controller messages. Toggles disable the corresponding native processor.
- Pause/background/stop releases the `AudioTrack` and stops the PCM thread, retaining the
  decoded bank for resume. Transport cancellation is serialized before another play opens
  output. Output-generation tokens prevent an old write loop reviving during rapid resume.
  PCM failures are surfaced in the UI instead of silently advancing a broken transport.
- SoundFont replacement validates the new bank before unloading the previous native bank.
  Cancellation, invalid banks and repeated close do not free a handle underneath a render.
  Files selected by the user are native-parser input, **not a hostile-file sandbox**.
- Android hardware MIDI output continues to mirror standard messages independently of
  internal audio processing. Internal reverb/chorus preferences are not forced onto an
  external device.

## Pinned dependencies and offline runtime

`demo-synth:prepareSynth` downloads and verifies exact artifacts at **build time**, then
places libraries/headers and assets under the ignored `build/generated/` directory.
No `.so`, SDK ZIP or large SoundFont dataset is committed to this repository.
A different byte sequence fails the build; there is no fallback to “latest”.

| Input | Version/source | SHA-256 |
|---|---|---|
| Android SDK ZIP | [FluidSynth v2.6.2, android24](https://github.com/FluidSynth/fluidsynth/releases/tag/v2.6.2) | `f29d1fe9c46c8528c65d22b2bfcd15997960f908370531a5a9427a73ab9e5313` |
| SoundFont source ZIP | [GeneralUser GS commit 684543d5e5efaef08d02be50dcda8d552478fa60](https://github.com/mrbumpy409/GeneralUser-GS/tree/684543d5e5efaef08d02be50dcda8d552478fa60) | `197207fffaf7bad496a1d5d9a44b0edd16efe97d53db42afb6c73cdbdefb4484` |
| Packaged `GeneralUser-GS.sf2` (32,319,396 bytes) | Unmodified 2.0.3 bank | `9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe` |

The official SDK's `lib/<ABI>/` and public include tree are extracted, not arbitrary ZIP
paths. Supported ABIs: **arm64-v8a, armeabi-v7a, x86_64, x86**. JNI is built with
NDK **27.3.13750724**, CMake **3.22.1**, C++17 and 16 KiB linker page alignment.
AGP installs these SDK tools if needed. An initial build needs network access and more
space than the rendering-only AAR; later builds can reuse the verified download cache.

## Rebuilding, licenses and relinking

All bridge/player sources are in this checkout. The unmodified engine's exact source and
its Android/dependency recipe are in [FluidSynth v2.6.2](https://github.com/FluidSynth/fluidsynth/tree/v2.6.2),
especially `.azure/azure-pipelines-android.yml` and `doc/android/README.md`. The official
recipe pins Oboe 1.10.0, libsndfile 1.2.2, libogg 1.3.6, libvorbis 1.3.7, Opus 1.5.2
and FLAC 1.4.3. These upstream source tags, the recipe and the NDK version identify the
corresponding source used by that SDK. Rebuild it with upstream's instructions to modify
the engine. For a local relink, run `:demo-synth:prepareSynth` once, replace the dynamic
libraries in `demo-synth/build/generated/fluidsynth/lib/`, then run
`./gradlew :sample-android:assembleDebug -x :demo-synth:prepareSynth` and re-sign/install.
Skipping preparation in that deliberately modified build prevents it restoring the pinned
SDK over your replacement. The JNI CMake target links dynamically; it does not embed a
hidden static copy. To distribute a changed SDK reproducibly, update the pinned artifact
URL/hash and notices in `demo-synth/build.gradle.kts` instead.

FluidSynth / libsndfile are **LGPL-2.1-or-later**. Oboe is Apache-2.0, the Xiph libraries
have BSD-style terms, and the NDK C++ runtime has Apache-2.0 with LLVM exceptions and
additional retained notices. Full notices are packaged in `assets/licenses/` and their
source copies live in `demo-synth/src/main/assets/licenses/`. Preserve them and comply
with corresponding-source/relinking obligations when redistributing binaries.

GeneralUser GS is by **S. Christian Collins**, under its own License v2.0. The full text
is included, including its discussion of historical sample provenance and considerations
for commercial software. Do not describe the bank as MIT/CC0 or remove that qualification.
Review the supplied license for production distribution. The existing repository's
Gervill sources/assets remain unchanged, but are no longer compiled/packaged by this
Compose sample. See [NOTICE](../NOTICE.md).

## Verification scope

Actions builds the JNI bridge for all four ABIs, runs JVM/native-layout tests and Android
lint, and tests the sample on an API-35 x86_64 emulator. Instrumentation checks the engine
version, non-silent piano/guitar/bass/flute/drum PCM, reverb tails, chorus changes, RPN bend
pitch response, invalid-bank recovery, double-close safety and native-MIDI → AudioTrack
transport/cursor progression. `tools/verify_synth.py` independently checks the actual APK
bank checksum, ABI payload/dependencies/notices, 16-KiB ELF/ZIP alignment for 64-bit
ABIs (4-KiB for the NDK's 32-bit ABI runtimes), absence of the old Gervill
backend and absence of synth/native payload in the core AAR. These checks do not claim
human audition, exhaustively verified synthesis effects, ARM hardware performance,
API-24 device testing or speaker/USB-MIDI hardware validation.
