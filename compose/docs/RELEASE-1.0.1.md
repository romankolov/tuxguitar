# 1.0.1 — upstream annotation correction

Annotation-correction release, version **1.0.1 / code 11**. The library remains a
model-to-Canvas component; the Android APK is explicitly an evaluation release,
not a production update identity. [APK, AAR, checksums and source](https://github.com/romankolov/tuxguitar/releases/tag/tux-compose-v1.0.1).

This corrects user-reported defects in 1.0.0. The previous green layout tests did
not establish label-placement parity. The correction uses native TuxGuitar paint
baselines/anchors and an attributed MPL-2.0 adaptation of alphaTab's ranged glyph
and effect-band placement, rather than adding another caption-placement scheme.

- Measure numbers sit above the actual staff/barline, not at the metadata row top.
- Native repeat-count text and its baseline/right alignment are restored.
- Let-ring labels are centred on the first flagged note, end on the last marked
  note, restart plainly per row, and use upstream singleton/short-span/cap rules.
- Grouping is per voice and follows decoded consecutive flags, including across
  repeat/ending boundaries. No invented voice union or automatic tie inheritance.
- Markers/prose retain their native musical X anchors and measured baseline origins. Native L.R labels remain
  present when grouping is disabled; harmonic type labels and heavy accents are
  preserved rather than replaced by generic text. Native fade-in cubics and
  filled vibrato/Tr wave paths are restored at the original voice width.
- Fixed parent width, fixed-size notation, playback/parser isolation, native greedy
  wrapping and the hollow fixed-width cursor remain in place.

## Qualification and visual approval

Reviewed renderer revision **`437970d323eca87faaa8872e3e217530f50353f2`**:
[Actions run 42710087426](https://github.com/romankolov/tuxguitar/actions/runs/42710087426).
Publication is a separate explicit `[publish]` commit, with the same executable
rendering code, documentation/attribution updates and all gates required again.
The final APK/source SHA/signer are in the release's `BUILD-VERIFICATION.json`;
`SHA256SUMS.txt` identifies the actual APK/AAR, not a previous candidate.

| Gate | Reviewed result |
|---|---|
| JVM differential/unit | **216**, zero failures/errors/skips |
| Host harness | **17** passing tests |
| Pinned alphaTab source | **3,444 records**: original numerical, paint, grouping, registry, placement and staff-overflow code |
| Pinned TuxGuitar source | **20** byte-identical files; 288 number, 36 repeat-count and 360 offline-path comparisons |
| Fixed-parent layout matrix | **1,728** configurations; 110,662 rows, 59,752 annotations, 63,472 fragments; zero recorded failures |
| Android API 24 | **36 instrumented + 5 exact-release** cases; zero failures/errors/skips |
| Android API 35 | **36 instrumented + 5 exact-release** cases; zero failures/errors/skips |
| Detailed hardware export | **32 required / 37 preserved PNGs per API**, checked before target cleanup |
| Human-readable image review | **64 detailed + 20 release previews**, reassembled with SHA-256 verification and inspected |
| APK/AAR inspection | Optimized/nondebuggable evaluation APK, full soundbank/four ABIs, parser/audio-free core, MPL licence/notice bytes verified in both artifacts |

Detailed-capture evidence Checks:
[API 24](https://github.com/romankolov/tuxguitar/runs/129484666567),
[API 35](https://github.com/romankolov/tuxguitar/runs/129485668709).
Their export checks are deliberately **neutral**: exporting an image is not visual
approval. Actual image review happened separately before opting into publication.
PNG integrity/hashes are verified on the runner; the reviewed local images were
Checks previews of those real captures, not a second synthetic rendering.

Visual review rejected an earlier green candidate: at 150% TAB zoom, preceding
rhythm beams touched following prose. The correction uses actual alphaTab staff
maximum-overflow/height/content-origin accounting. A new hardware guard rejects
dark notation inside the following text box; the old tolerance was not relaxed.
The final reviewed captures show separation on both APIs.

The preview manifest fingerprints for the reviewed run are
`0768ad251b93013ff507ac0b49df7727af3691c9de7073de5bc192b29665e016` (API 24) and
`a63cb4e3eba73d00e91efdba598ae8c6e59ec15be9a957d44ca581513ee08be3` (API 35).
These identify that visual qualification, not the separately rebuilt final APK.
This is targeted source/annotation evidence, not exhaustive editor, whole-page,
all-effect optical or physical-device parity. No human speaker-listening claim is made.

Full source behaviour and scope limits: [UPSTREAM-COMPARISON](UPSTREAM-COMPARISON.md).
Licence change: [NOTICE](../NOTICE.md). Only `TGAlphaEffectLayout.kt` adds an MPL-2.0
source adaptation; no alphaTab parser/player/JavaScript runtime is shipped.

The sample still uses an ephemeral evaluation release signer, not the debug key
or a production update identity. Uninstalling a differently signed build clears
its private data; save imported work/settings before doing so.
