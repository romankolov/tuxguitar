# Tux Compose 1.1.0 — optional native PDF library

Adds the separate **`tux-compose-pdf` Android library** and integrates it into the
sample's **Export score / tab as PDF** menu. The immutable model-to-Canvas core
still has no PDF, Android storage, parser, playback or Material dependency.

[APK, core AAR, PDF AAR, example PDF, source and checksums](https://github.com/romankolov/tuxguitar/releases/tag/tux-compose-v1.1.0)

## Source-driven implementation

- Execute TuxGuitar 2.1.1's original `TGPrintLayout`, model/managers and notation
  painters: **100 runtime sources**, namespace-only relocated to avoid collisions
  and global-resource interference with host importers/players. No musical layout
  or page-fitting algorithm is rewritten.
- Pin those sources and **eight original PDF-backend reference files** to upstream
  `b4feea4fe2f0155d648a37282ec57f1ecb329a35`. Local SHA-256 and upstream Git-tree
  comparisons verify the source identity and namespace-only generation.
- Retain native defaults: 550 × 800 PDF points, 20-point margins, Times-Roman /
  Times-Bold AFM metrics, native print spacing, headers, low-to-high tuning,
  measure-range rules, whole-measure greedy wrapping, per-track part pages and
  global page-number footers. No screen bitmap, current zoom, palette or cursor
  is exported. A4/Letter are explicit paper options, not a new default.
- Use PDFBox-Android for vector PDF serialization rather than shipping iText's
  AGPL runtime. Width measurement uses raw native-compatible AFM advances,
  including the source's non-breaking-space alias and zero soft-hyphen metric,
  not backend convenience substitutions that would alter centring/wrapping.
- Preserve print-only metadata, lyrics and latent percussion clef changes which
  the smaller core model intentionally does not encode. Reject unsupported extra
  voices, contradictory effects/pitch overrides or invalid ranges explicitly.

## Sample integration

Choose Score/Tab/Both, all parts/current part, inclusive measure range, native
print scale, paper, chord names/diagrams and compact spacing. Export snapshots the
selected decoded model/options/metadata before the system destination picker.
Writing runs on an IO dispatcher, propagates cancellation/errors and does not
close the caller-owned stream. Android `ACTION_CREATE_DOCUMENT` uses
`application/pdf`; no broad storage permission or renderer-only export hook is
added.

[API and usage](../tux-compose-pdf/README.md) ·
[Exact print equations and source decisions](PDF-EXPORT.md) ·
[Licences](../NOTICE.md)

## Qualification and visual review

Reviewed revision: **`a518442d831173e2681909563b7649e40a3f5d48`**.
[Successful Actions run 43448119603](https://github.com/romankolov/tuxguitar/actions/runs/43448119603).
Publication is a separate opt-in commit, with all gates required again. Final
artifact digests/signing/source are in the release's verification JSON/checksums,
not a digest copied from an earlier candidate.

| Gate | Reviewed result |
|---|---|
| JVM suites | **219**, zero failures/errors/skips |
| Native PDF reference | Original unrelocated print layout/profile/iText painter really writes PDFs; complete painter calls and pagination match across **12** notation/part/range/scale configurations |
| Host harness | **40** passing tests; parser, image/PDF integrity, source isolation, installation/protocol and diagnostics guards |
| Existing layout audit | **1,728** configurations; zero recorded failures |
| PDF source pin | **108** original files checked; **100** runtime files generated with namespace-only changes |
| Android API 24 | **40** instrumented + **6** exact-release cases; zero failures/errors/skips |
| Android API 35 | **40** instrumented + **6** exact-release cases; zero failures/errors/skips |
| Runtime font parity | Standard-14 widths and digit ascents compared to original iText over three native fonts, six sizes, full CP1252 characters/strings and unrepresentable text |
| Generated documents/API | Five instrumented PDFs: Mixed **15** pages, Score **10**, TAB **10**, selected range **1**, sample document-contract export **1** |
| Exact release export/API | Real R8 app + real system DocumentsUI picker generates an independently validated **15-page vector PDF** tied to the installed APK's hash |
| Capture evidence | **53 required / 53 exported per API**, zero missing: 32 retained score regressions, 7 actual PDF-page renders, 2 PDF UI captures and 12 settled release scenes |
| Visual approval | **106 actual previews** reassembled with SHA-256 verification and inspected before promotion; PDF pages are labelled PDF-rendered, not hardware score screenshots |
| Artifact inspection | Optimized/nondebuggable evaluation APK; original print engine and licence notices in PDF AAR; no music parser/player or iText runtime in it; no iText descriptors in release DEX; core isolation unchanged |

Reviewed capture Checks:
[API 24](https://github.com/romankolov/tuxguitar/runs/131968375916),
[API 35](https://github.com/romankolov/tuxguitar/runs/131969198509).
The Checks are **neutral evidence exports**, not automatic visual approval.
Partial/failure captures remain explicitly failed and cannot qualify publication.

Capture-manifest fingerprints for that approval run:
- API 24: `6ee23e087e02b78039c5fa115d7d019f8e2d2dec9b8465a78b71ffb5ebc945d1`
- API 35: `38603e29d0686371033a08e0ddbc41a348e2021a33c03933f764c79b7c7c51f0`

Android 7 intermittently returned `install-write` EIO for the 118 MB debug APK.
Its harness now performs one bounded **non-streaming** installation and invokes
the same complete AndroidJUnitRunner suite. All declared cases must start/finish;
crashes, duplicates, missing cases, failures and skips fail closed. API 35 retains
AGP's connected runner. No blanket retry or relaxed assertion is used.

The real Android-7 file picker also keeps its roots drawer over the enabled Save
bar; its Back action cancels document creation rather than closing that drawer.
The driver uses the observed drawer scrim, not a debug hook or file injection.
The actual user flow, produced PDF and R8 linkage are tested on both APIs.

## Deliberate limits and distribution

This is original TuxGuitar print behaviour within the exported decoded model,
not a redesigned music typesetter or byte-identical iText document. Native model
limits (two voices, aligned headers, sequential measures), written tuning/fret
notation, CP1252/Standard-14 character coverage and original overwide-row behaviour
are documented in the API guide. No general Unicode font embedding, desktop/iOS
PDF backend, arbitrary PDF input reader or exhaustive editor/physical-device
parity is claimed. Core Android/JVM rendering remains independent.

The PDF engine/adapter retains LGPL-2.1-or-later; PDFBox/AFM and Bouncy Castle notices
are bundled. iText is a test-only reference. The two `MidiInstrument` and
`MidiPercussionKey` runtime name/value records are not a playback engine.

The sample is still an **evaluation release**, optimized/nondebuggable and signed
with a temporary separate CI key, not Android's debug key or a production update
identity. A differently signed older build must be uninstalled first; uninstalling
clears app-private data. Preserve imported work/settings before doing so.
