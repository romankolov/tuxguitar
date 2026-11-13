# Tux Compose PDF — optional Android library

**1.1.0** · [release APK/AAR/example PDF](https://github.com/romankolov/tuxguitar/releases/tag/tux-compose-v1.1.0) · [qualification and evidence](../docs/RELEASE-1.1.0.md)

Exports the renderer's decoded immutable `TGSong` as a vector PDF. The optional
module depends on `tux-compose-core`; core never depends on PDF, Android storage,
a parser, playback, Material or the demo. Android API 24+.

This does **not** convert a screenshot or slice the phone's continuous layout.
It executes TuxGuitar's **original `TGPrintLayout` and notation painters**, pinned
to TuxGuitar 2.1.1 `b4feea4fe2f0155d648a37282ec57f1ecb329a35`.
One hundred original runtime classes are generated with namespace-only relocation
into `com.tuxcompose.pdf.internal.tuxguitar`; no pagination/engraving algorithms
are rewritten. This avoids duplicate classes and global-resource interference
when an app also uses the native importer/player. The public API exposes only
core models and PDF options, never these implementation classes.

## Plug it in

```kotlin
// In an Android application's dependencies (project inclusion shown):
implementation(project(":tux-compose-core"))
implementation(project(":tux-compose-pdf"))
```

Include the module in the standalone `compose` build, or publish its `release`
Maven publication locally. A manually installed AAR also needs the core AAR and
`com.tom-roush:pdfbox-android:2.0.27.0`; the Maven POM declares these dependencies.
For manually downloaded AARs in an existing core-based app:

```kotlin
implementation(files("libs/tux-compose-pdf-release.aar"))
implementation("com.tom-roush:pdfbox-android:2.0.27.0")
// Keep your existing core AAR/project dependency; no extra native print JAR is needed.
```

```kotlin
val exporter = TuxGuitarPdfExporter(context.applicationContext)
val options = TGPdfOptions(
    style = TGLayoutStyles.Mixed,  // Score / Tablature / Mixed
    trackIndex = null,             // all parts; or zero-based source index
    fromMeasure = 1,
    toMeasure = null,              // final measure
    zoomPercent = 100,             // print scale, NOT screen zoom/density
    page = TGPdfPage.TuxGuitar,
    chordDiagrams = true,
    chordNames = true,
)

// The caller owns its output stream and dispatcher. No file picker is built into the library.
withContext(Dispatchers.IO) {
    contentResolver.openOutputStream(destinationUri, "wt")!!.use { output ->
        val result = exporter.export(decodedSong, output, options)
        // result.pageCount, result.bytesWritten, result.sha256
    }
}
```

`checkCancelled` can check a coroutine context. Exceptions propagate; success is
reported only after the complete native document has been serialized. An invalid
model/range is rejected before PDF bytes are written. Native resources and the
PDF document are disposed on failure. The caller's stream is not closed by the
exporter.

`TGPdfMetadata` provides author/year/copyright/transcriber/writer and per-track
lyrics and stored percussion-clef sequences that are deliberately absent from the smaller core model. The sample
passes these from its already decoded native song, including latent percussion
clef changes which influence original header widths; no music reader is added to
this library.

## Actual upstream print behaviour

| Concern | Original source / behaviour |
|---|---|
| Default paper | `PDFSongWriter`: **550 × 800 PDF points**, margins 20 each; not A4. A4/Letter are explicit caller options. |
| Units | 1/72 inch. No Android dp, font-scale multiplier or current `TablatureState.zoomScale`. |
| Width/height | `TGPrintLayout.getMaxWidth/Height`: page minus both margins **minus print scale**. |
| Notation profile | `PDFSettings` / `PDFLayoutStyles`: 7-point staff spacing, 8-point string spacing, duration widths `[18,18,16,15,14]`, Times-Roman 8 / Times-Bold 9. These differ from the screen and desktop-printer profiles. |
| Grouping | Whole native measures; greedy row threshold is `>=`. Full rows divide their remaining width equally among measures, using the original beat-spacing logic. |
| Page breaks | A complete native staff row is moved when `posY + rowHeight + trackSpacing > topMargin + maxHeight`. No invented row splitting or fit-to-page scale. |
| Parts | Each selected track starts a new page. The original print engine does not interleave all parts in a screen-style system. |
| Headers | Title/artist/album-year/track and author/copyright/transcriber/writer, native font sizes, native centring/right alignment and original conditional increments. |
| Tuning | Non-percussion TAB parts print the original low-to-high sharp note names. |
| Continuation | Track header on its first page only; native measure signature-change rules. Global page numbers continue across parts. |
| Effects | Original TuxGuitar printed notation, including native L.R/P.M and effect paths, not alphaTab's screen-only grouped annotations. |
| Metrics | As in `PDFPainter`, ascent is the maximum digit bounding-box top, and width is the sum of Standard-14 AFM advances. Bold flags do not substitute a different BaseFont name. |

PDFBox-Android supplies **serialization and Standard-14 fonts**, not musical
layout. It replaces upstream iText's AGPL runtime dependency. The original PDF
painter's baseline, path fill/stroke, dash/cap and top-down → PDF Y conversion are
preserved. iText 5.5.13.3 is **test-only**, never shipped in the AAR or app.

## Native model and font limits

- Native TuxGuitar has two voices. Non-empty extra voices fail explicitly rather
  than disappearing. Tracks must share aligned global headers and consecutive
  measure numbers beginning at 1, as native print-range indexing requires.
- A contradictory per-note pitch override or a combination of effects which
  native setters cannot represent is rejected. Source identities/models are never
  rewritten in place. The exporter prints written tuning/fret notation with the
  native capo/transpose conventions, not an invented concert-pitch arrangement.
- Upstream's non-embedded **CP1252 / Standard-14 font repertoire** is retained.
  Characters outside that repertoire have no printed glyph/advance; do not assume
  general CJK/Unicode font embedding. PDF metadata can retain Unicode separately.
- Original overwide-measure/row behaviour is retained. There is no new clipping,
  glyph shrinking, font substitution or emergency music-page layout algorithm.
- Serialized object numbers, compression and producer metadata differ between
  PDF libraries. Byte-for-byte PDF identity or identical rasterisation in every
  PDF viewer is not asserted.

## Source and verification

`upstream.json` pins all 100 runtime classes plus eight original PDF-backend
reference sources. `tools/prepare_pdf_sources.py --remote` checks GitHub's complete
pinned tree, local SHA-256 values and namespace-only generation. Original source
is still in the repository; generated Java and build products are not committed.
The two included `MidiInstrument`/`MidiPercussionKey` classes are **name/value
records** referenced by native translated messages, not sequencers or audio.

The host JVM suite executes the original unrelocated `TGPrintLayout`, original
PDF profile and original iText PDF painter and compares the complete native
painter call stream against the isolated adapter, across styles, parts, ranges
and zoom. Android tests write and inspect real PDFs, verify their fonts/paper/
text/vector resources, render actual pages with Android `PdfRenderer`, and exercise
the app's document-export flow. CI preserves original PDFs and page previews,
checks their manifests and refuses publication if evidence is missing.

## Licence

TuxGuitar-derived engine/adapter: **LGPL-2.1-or-later**. PDFBox-Android and its
bundled font data have their own Apache/third-party notices; Bouncy Castle has its
permissive licence. Full notices are in `src/main/assets/licenses` and the AAR/APK.
The existing core's separate LGPL/MPL boundary remains unchanged.
PDFBox's compile-only JPEG-2000 codec is intentionally not packaged: there is no
input-PDF/image-decoder API and exported notation is paths/text. Only the two
optional JP2 codec class warnings are excluded; image-XObject rejection, native
paint comparisons, PDF parsing/rendering and all core/release gates remain active. Consumers must
comply with applicable source/relinking requirements; changing a Java package
name does not remove upstream copyright or licence obligations.
