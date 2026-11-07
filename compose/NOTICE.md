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
