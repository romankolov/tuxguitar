# Tux Compose 1.0.0

A decoded immutable score → shared layout → native Compose Canvas library, with a
separate Android demonstration. This is a substantive rendering release, not a rename
of the 0.x preview or a promise of universal superiority over complete upstream apps.

## Changes

- Preserve native TuxGuitar default rhythm/compact/greedy wrapping, fixed-size glyphs,
  parent-width pages and tail-fragment packing. Optional Adaptive remains available.
- Add optional **Spring** spacing: duration power-law compliance, 1.2–2 ratio, hard
  ink minimums, shared multi-track onsets, no squeezing. The sample persists the choice;
  Spring does not use the native compact switch.
- Displace diatonic chord seconds and conflicting secondary voices without moving TAB
  or musical onsets. Share compatible unison heads once and re-elect visible owners
  when an original owner ends before a held-voice fragment.
- Pack accidental height envelopes into columns; include them in spacing/row bounds
  and hit testing. Force conflicting simultaneous spellings. Suppress cross-bar tie
  accidentals only for a real matching origin, without registering the carry as a new
  accidental. Gaps/dead origins/dangling ties do not hide needed signs.
- Put grace ink before the complete accidental group and reserve actual grace-stem
  height below metadata. Deduplicate chord dot rows; retain anchors when a held
  undotted note acquires a dotted presentation-fragment duration.
- Replace tallest-caption shelves with an independent interval skyline **within the
  native bands**. Preserve measured prose, merged let-ring dashes/endcaps, indexed
  row-local connections, the hollow fixed-fret-width capsule and manual-scroll priority.
- Extend the default 32-bar/three-track Technique Atlas with close chord/accidental
  examples. Preserve separate TuxGuitar decoding/MIDI, FluidSynth/GeneralUser GS,
  Material3 Expressive controls, custom banks and external MIDI demonstration.

## Reproducible comparison and qualification

First complete implementation qualification:
[`04e18b44c68ecc2a0173d81042dd45253dcd9eda`](https://github.com/romankolov/tuxguitar/tree/04e18b44c68ecc2a0173d81042dd45253dcd9eda/compose),
[Actions run 42618620665](https://github.com/romankolov/tuxguitar/actions/runs/42618620665).
Final publication/documentation changes rerun these same gates on the final commit;
read the immutable release body and BUILD-VERIFICATION.json for the authoritative final
source/APK/signer identity. Temporary signing means candidate and final APK hashes are
not asserted to be equal.

| Gate | Result at the qualification checkpoint |
|---|---|
| Core + demo JVM suites | **201 tests** (160 core, 41 demo), zero failures/errors/skips |
| Actual alphaTab 1.9.0 helper results | **2,584 records** checked; three JVM comparison methods, not 2,584 test methods |
| TuxGuitar 2.1.1 source pin | **16 byte-identical** native UI/layout files; existing direct Java differentials retain **0.025 physical-unit** tolerance |
| Decoded Atlas layout matrix | **1,728 configurations**, 110,662 rows, 61,768 captions, 63,472 fragments; zero recorded failures |
| Android API 24 | **35** instrumented + **5** exact optimized-release cases, zero failures/errors/skips |
| Android API 35 | **35** instrumented + **5** exact optimized-release cases, zero failures/errors/skips |
| Host screenshot/install harness | **11** tests; local run and CI harness step pass |
| Release visual evidence | **20** settled release scene previews reconstructed, SHA-256 verified and visually reviewed |
| Build/artifact boundary | Lint, core assemble, optimized/non-debuggable R8 release, synth/bank/license/ELF and signing verification pass |

The first candidate's API-24 release install exceeded the generic 60-second command
budget; that failed run was not published. The install now gets **one** separate bounded
180-second deadline, without retrying or weakening the five smoke cases. A successful
install notice requires actual installer Success and installed-version confirmation.
No other UI, pixel or raw-Java tolerance was relaxed.

There are twelve actual-display chord profiles per API (Score/Mixed × three spacing
modes × 100%/150% zoom) within the instrumented pixel test; the existing opaque
vector-core test still runs at 100%/175%. Full screenshots/reports remain in the device
artifacts; bounded Checks previews also allow review when the artifact CDN is unavailable.
API-24 expanded-window letterboxing and old system-UI font rendering are emulator chrome,
not evidence that the score is supposed to use the entire framebuffer. No physical-device
or human-listening approval is claimed.

## Downloads, source and use

- [Immutable 1.0.0 release](https://github.com/romankolov/tuxguitar/releases/tag/tux-compose-v1.0.0)
- [Evaluation release APK](https://github.com/romankolov/tuxguitar/releases/download/tux-compose-v1.0.0/sample-android-release.apk)
- [Core AAR](https://github.com/romankolov/tuxguitar/releases/download/tux-compose-v1.0.0/tux-compose-core-release.aar)
- [Full source and complete model-only usage](https://github.com/romankolov/tuxguitar/tree/tux-compose-v1.0.0/compose)
- [Upstream comparison/licences/limits](UPSTREAM-COMPARISON.md), [new mathematics](ENGRAVING.md),
  [native mathematics](PORTING.md), [API policy](API-POLICY.md), [audio](SYNTH.md), [UI](UI.md).

The release includes SHA256SUMS.txt and BUILD-VERIFICATION.json. The library is LGPL;
alphaTab MPL sources run unmodified in test-only tooling and are not shipping runtime
code, fonts, parsers or audio. See NOTICE and the detailed comparison for the boundary.

**The sample is an evaluation release with a temporary CI key, not production signing.**
Uninstall a differently signed earlier sample before installing; this clears its private
settings/imported bank data. The core library's 1.0 API status does not create a persistent
APK update identity or a Play Store release.
