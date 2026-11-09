# 1.0.1 — upstream annotation correction

Candidate under verification; no new stable release is asserted here yet.

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

## Evidence required before publication

- Original Java annotation paint-call differentials, not only width/layout tests.
- 3,380 checksum-pinned alphaTab records including actual grouped glyph painting,
  flag linking, effect-band placement and Score/Tab priorities; 20 native source-file comparisons.
- Existing JVM, 1,728-layout matrix, Android API 24/35 and exact-release tests.
- Hardware pixel checks for number baselines and grouped effects; all 32 required
  detailed annotation/engraving/cursor images exported per API, hash-manifested.
- Actual image review before the explicit `[publish]` commit opts into publication.

Full source behaviour and scope limits: [UPSTREAM-COMPARISON](UPSTREAM-COMPARISON.md).
Licence change: [NOTICE](../NOTICE.md). Only `TGAlphaEffectLayout.kt` adds an MPL-2.0
source adaptation; no alphaTab parser/player/JavaScript runtime is shipped.

The sample still uses an ephemeral evaluation release signer, not the debug key
or a production update identity. Uninstalling a differently signed build clears
its private data; save imported work/settings before doing so.
