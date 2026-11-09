# Attribution and license notices

Tux Compose is derived from **TuxGuitar**, copyright (C) 2005–2022 Julián Casadesús and 2023–2025 TuxGuitar contributors (including guiv42 and helge17). The reference checkout is `romankolov/tuxguitar`, in the `helge17/tuxguitar` lineage. See the repository's [AUTHORS](../AUTHORS) and [LGPL 2.1 text](../docs/LICENSE).

The Kotlin model/layout adaptation and generated musical vector paths are distributed under **LGPL-2.1-or-later**. Generated files preserve their source attribution, and the generator/provenance manifest make their origin explicit. Java upstream sources are referenced directly by the optional demo bridge instead of being duplicated or relicensed.

The Android demonstration now uses **FluidSynth 2.6.2**, dynamically linked through the LGPL bridge in `demo-synth`, not the legacy Gervill backend. FluidSynth and libsndfile are LGPL-2.1-or-later. The official SDK also carries Oboe (Apache-2.0), FLAC/Ogg/Vorbis/Opus (their BSD-style licenses) and the NDK C++ runtime (Apache-2.0 with LLVM exceptions/additional retained notices). Full texts are preserved in `demo-synth/src/main/assets/licenses/` and packaged under APK `assets/licenses/`. Exact versions, SHA-256 pins, corresponding-source links, build recipes and replacement/relinking instructions are in [SYNTH.md](docs/SYNTH.md). These components are **not dependencies or payload of `tux-compose-core`**.

The unmodified **GeneralUser GS 2.0.3** bank is by **S. Christian Collins**, distributed under **GeneralUser GS License v2.0**, not MIT/CC0. Its complete license is included as `GeneralUser-GS.txt`, including its qualification about historical sample provenance and commercial-software use. The source is pinned to commit `684543d5e5efaef08d02be50dcda8d552478fa60` of `mrbumpy409/GeneralUser-GS`; build-time downloads are checksum-verified and not stored in Git. Original TuxGuitar Gervill/Java Sound files and Magic Sound Font assets remain unchanged in the parent repository but are no longer compiled/packaged by the Compose sample.

The study titled *Greenwood Study* is an original generated demonstration, not a third-party tablature transcription. The sample also packages the original TuxGuitar LGPL text and this notice. There is no runtime bank download.

This notice is not a substitute for the applicable license terms. Distributors of derived binaries must preserve notices and satisfy source/relinking obligations as applicable.

The Android Studio workspace uses Material Symbols Rounded vector assets from
google/material-design-icons, commit 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7,
under Apache-2.0. The license is packaged in assets/licenses/Material-Symbols-Apache.txt.
Material3 1.5.0-rc01 is used only by the Android sample; it is not a core dependency.

The bundled **Technique Atlas** is original programmatically authored demonstration music
in `demo-tuxguitar/ShowcaseSongFactory.kt`, not a downloaded third-party transcription.
Its default lossless TG decoding and GP5 companion export remain demo-only integrations;
no parser, Material UI, native audio or music asset is added to the core library.

The **Android sample only** enables `com.android.tools:desugar_jdk_libs_nio:2.1.5`
for optional demo Java time/NIO APIs on Android 7 (API 24). The TG adapter uses
ordinary Java ZIP streams for its archive envelope to avoid a Commons Compress
`ZipEntry`/`FileTime` API-conversion incompatibility on API 24. Upstream TG XML
serialization/parsing and its version checks remain unchanged; the file format is
interoperability-tested in both directions against the unmodified upstream streams.
Corresponding source: [google/desugar_jdk_libs, 2.1.5 preparation commit
73170c345e6a762fc6a1f0301bb15218850023ef](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef).
OpenJDK GPLv2 text, Classpath-exception licensing information and assembly exception
are retained as `Desugar-JDK-*.txt` under APK `assets/licenses/`. Applicable individual
source files retain their original notices/exceptions. This does not change the core
AAR's dependencies or licence. No JDK implementation source is copied into the core.

## alphaTab comparison tooling (1.0.0)

The implementation review uses **alphaTab 1.9.0**, copyright Daniel Kuschny and
contributors, under **Mozilla Public License 2.0**, pinned to
[`9f1aa10460df1b326123d28a2222f7b20d081e61`](https://github.com/CoderLine/alphaTab/tree/9f1aa10460df1b326123d28a2222f7b20d081e61).
Its [license](https://github.com/CoderLine/alphaTab/blob/9f1aa10460df1b326123d28a2222f7b20d081e61/LICENSE)
and notices remain with the unmodified build/test reference source. The test harness
fetches and verifies that source into an ignored cache and executes three helpers to
produce mathematical reference fixtures. No alphaTab source implementation, bundle,
font, audio, parser or runtime dependency is incorporated into the shipped core/sample.
The new LGPL helpers independently implement standard interval/engraving/spring ideas;
see [the detailed source and licence boundary](docs/UPSTREAM-COMPARISON.md).
Pinned **esbuild 0.28.2 (MIT)** and Node are tooling only, not runtime payloads.

The 16 native Java UI/layout files compared with **TuxGuitar 2.1.1**
(`b4feea4fe2f0155d648a37282ec57f1ecb329a35`) are byte-identical to this checkout.
Their existing LGPL notices are unchanged; no newer external TuxGuitar implementation
file is silently substituted. The reviewed-path/hash manifest is retained with tooling.
