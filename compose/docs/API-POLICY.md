# 1.x API and release policy

The 1.0 surface is `com.tuxcompose.model`, `layout`, `renderer` and `component` in
`tux-compose-core`. It takes decoded immutable models and returns layouts/draws Compose
Canvas. Import, sequencing, synth and Material UI remain outside core.

- Version numbers follow major/minor/patch intent: incompatible supported source-API
  changes require a major version; additive optional configuration can be minor;
  rendering/bug corrections can be patch. This is not a pixel-output stability promise.
- The 0.x previews were not a binary-compatibility baseline. Recompile callers for 1.0;
  new defaulted data-class parameters preserve normal Kotlin source use, not old
  constructor bytecode or exhaustive enum `when` expressions.
- Published layout fields are rendering/hit geometry, not a serialized file format.
  Do not persist them across versions or infer source identity from an X coordinate.
  Use source measure/track/voice/string/tick identities. Actual line wrapping can
  change after an engraving fix, typography change or width change.
- `internal` planners/builders and the demonstration modules have no public compatibility
  promise. The sample's temporary evaluation signing identity is not an update channel.
- Native TuxGuitar mode stays the default. Improved collision/engraving protection is
  explicitly switchable; raw-reference flags are for comparison/compatibility and may
  intentionally reintroduce upstream overlaps. Spring spacing is opt-in.
- Android minSdk is 24; Android/JVM are exercised in CI. iOS target configuration is
  optional and not a claim of a verified iOS application or published XCFramework.
- No Maven Central publication is implied. The README describes project inclusion and
  local publication; GitHub release AARs have explicit source, checksums and test proof.

The release pipeline fails on architectural boundary checks, mismatched version codes,
reference-source/fixture changes, JVM failures, lint/build failures, either emulator
failure, or the exact optimized-release black-box checks. Artifact inspection verifies
non-debuggable/R8 output, synth payload/licences, ELF alignment and signing. Device
captures are evidence, not an assertion of universal correctness.

Verified 1.x publications also create `tux-compose-v<version>` GitHub releases with
immutable tags/assets; existing versioned releases are never force-moved or clobbered.
The `tux-compose-preview` alias remains a separately moving latest-verified build.
A stable core-library version does not turn the sample's evaluation key into production
signing or a Play Store update identity.

For reproducibility, retain the release source SHA, CI run, BUILD-VERIFICATION.json,
SHA256SUMS.txt and signer digest. Uninstall a differently signed evaluation sample
before installing a new one; this clears that app's private data. A real distributable
update channel needs a securely retained production signing identity instead.

### 1.0.1 annotation correction

The original five `TGAnnotationKind` values are unchanged; exhaustive source consumers
remain valid. Native heavy-accent/trill path identity is additional optional metadata
(`nativeEffect`), not new values injected into that existing enum. Layout constructors
retain defaulted additions; recompile consuming projects, as no Kotlin data-class
constructor/copy bytecode compatibility is claimed by this correction. Custom font
metrics can optionally override `firstBaseline`; Compose uses its actual cached result.
The attributed `TGAlphaEffectLayout.kt` adaptation adds an MPL-2.0 file obligation;
other core source remains LGPL-2.1-or-later. Full licences and notices are verified
inside both the shipped AAR and sample APK.
