# Native TuxGuitar PDF export (1.1.0 candidate)

This feature is implemented in the separate optional Android library
[`tux-compose-pdf`](../tux-compose-pdf/README.md). Qualification is still in
progress; see Actions for actual results, not assumed success.

## Why it does not export the screen layout

TuxGuitar's `common/TuxGuitar-pdf/PDFSongWriter` uses `TGPrintLayout`, not
`TGLayoutVertical`, the mobile canvas, or a screenshot. The print profile has
its own fonts, spacings, track-part handling, headers and pagination. The new
module compiles that original print engine with namespace-only relocation;
no alternative page-fitting/engraving routine is substituted. The screen's
alphaTab enhancements, playback cursor, palette, selection, scroll and zoom
are not silently turned into print instructions.

The pin is TuxGuitar 2.1.1, commit
`b4feea4fe2f0155d648a37282ec57f1ecb329a35`. `upstream.json` records 108 original
source hashes: 100 runtime classes and eight original PDF-backend reference
files. Namespace relocation is byte-checkable and preserves all algorithms.
The two `MidiInstrument`/`MidiPercussionKey` classes in the closure are name/value
descriptors used by the translated-message utility, not audio/player engines.
No musical input decoder is included; the caller supplies decoded core models.
The sample also supplies stored percussion-clef sequences through print metadata:
core intentionally uses a single Percussion clef, but the original native raw
clef changes still influence printed header widths. Those values are preserved,
not compensated with a custom spacing formula.

## Source equations and decisions

All dimensions below are **PDF points**, 1/72 inch. Default page `(W,H)` is
`(550,800)` and margins `(L,T,R,B)` are `(20,20,20,20)`, exactly `PDFSongWriter`.
The default is intentionally not A4. Let `s=zoomPercent/100`.

```
maxWidth  = W - L - R - s
maxHeight = H - T - B - s
```

These are `TGPrintLayout.getMaxWidth/Height`, including its final one-scale-unit
allowance. Native `PDFLayoutStyles` uses staff spacing `7s`, string spacing `8s`,
track spacing `5s`, first-track spacing `5s`, first-note spacing `10s`, left/right
measure spacing `15s` and duration-width table `s*[18,18,16,15,14]` (native clamping
for shorter durations). Chord fret/string/index spacings are `5s`, `4s`, `8s`;
chord dot size is `3s`, chord line width is the source hairline `0`.

### Row selection and horizontal space

For the next native measure of natural width `w`, the original greedy check is

```
fullLine = sumAcceptedWidths + w >= maxWidth
```

If it crosses that threshold or an explicit line break is pending, end the row.
If the row is empty, include that first measure anyway—the original overwide-bar
behaviour is not replaced by a new splitting or automatic glyph shrink. A full
line assigns equal additional width to each of its `n` accepted measures:

```
perMeasureSpace = (maxWidth - sumNaturalWidths) / n
```

The original `TGMeasureImpl`/`TGBeatImpl` then distribute that measure space.
The buffer-disabled PDF path does not introduce the screen's bitmap rounding.
All note/beam/effect/chord geometry comes from the original native painters.

### Vertical placement and pages

Each selected track opens its own part page. Its first content Y is
`round(T + 5s) + headerOffset`. The header is printed once for that track.
Before placing a complete row of native height `h`, the exact page check is

```
if posY + h + 5s > T + maxHeight:
    print current page footer
    close current page; open next page
    posY = round(T + 5s)
```

Continuation pages do not repeat the song/part header. After painting the row:

```
posY += round(h + 5s + .5)
```

This is the source order, including its extra half-unit before `Math.round`.
Page numbering remains global across parts, while every part starts a new page.
Native measure ranges are inclusive and still retain preceding bars when
computing changes/lyric offsets; they are not implemented by slicing the model.

### Headers, tuning and footer

Header rows are conditional: title, artist, album/year and part add `20s` each;
author/copyright/transcriber add `10s` each; writer adds `20s`; tuning adds `20s`.
Title/artist/album/part are centred, author information is right-aligned, tuning
is left-aligned. Native label strings and punctuation are retained. Non-percussion
TAB parts list strings from low to high using the native sharp note-name helper.

For measured width `fw` and native current digit-ascent metric `fa`:

```
centredX = L + (maxWidth - fw)/2
rightX   = L + maxWidth - fw
footerBaseline = T + fa + maxHeight - fa = T + maxHeight
```

`paintHeader` captures its original top-line metric before changing header fonts;
the adapter does not replace that with a newly chosen text-top alignment.
Default header sizes are 16/12/10/8 points times native font scale. As upstream,
BaseFont identity controls the font: a bold Boolean alone does not substitute a
different font name. Note text explicitly uses Times-Bold 9; ordinary print text
uses Times-Roman 8.

### Font metrics and PDF drawing

`PDFFont` is CP1252 / non-embedded Standard-14. Preserve its metrics:

```
width(text,font) = sum(AFM advances) / 1000 * fontHeight
ascent(font)     = max(digit glyph bounding-box top) / 1000 * fontHeight
baseline metric = 0
middle metric   = ascent/2
PDF_y           = pageHeight - native_y
```

Do not substitute Android `serif`, use the global AFM ascender instead of digit
bounds, or measure in device pixels. PDFBox-Android supplies the same Standard-14
AFM data and PDF serialization, avoiding an iText AGPL dependency in the runtime.
The original iText backend is used only in host comparison tests.

`UIPainter.closePath` flushes paint with draw/fill/both flags; it is not a request
to close every contour. Native rectangle, separate accent subpaths, cubic controls,
circle diameters, solid/dotted/dashed patterns and line-cap state are retained.
There is no raster score XObject in the produced PDF.

## Android sample

Open the overflow menu → **Export score / tab as PDF**. Choose Score/Tab/Both,
all parts or the current part, inclusive measure range, print scale, paper and
native chord/compact options. Save uses `ACTION_CREATE_DOCUMENT` with
`application/pdf`; no broad storage permission is requested. The selected decoded
song/options/metadata are snapshotted before opening the system destination picker,
so a pending result cannot accidentally export a different song. Work runs on an
IO dispatcher; cancellation/failures are reported instead of success.

## Verification boundaries

Host tests run the original, unrelocated `TGPrintLayout`, original PDF profile and
original iText painter, then compare its complete painter call stream with the
isolated adapter over the decoded Technique Atlas, styles, parts, ranges and scale.
The reference really writes an iText PDF; it is not a second pagination formula.

Device tests write actual PDF files, inspect page sizes/fonts/text/vector resources,
check output digest/count/stream ownership, and rasterize their actual pages using
Android `PdfRenderer`. They also exercise the sample's document contract (only the
OS destination chooser is intercepted for an automated test). The host validates
all pulled PDFs and original page-preview images before release smoke checks.
The exact R8 release then creates another PDF through its real UI and the actual
system DocumentsUI picker. That file is parsed/validated and tied to the installed
APK hash, rather than assuming a debug-instrumented export proves release linkage.
Missing files/images fail closed. PDF page previews are labelled PDF-rendered
evidence, not hardware screenshots of the score canvas.

Limits are explicit in the module README: the native two-voice/aligned-header/
measure-number model, incompatible effect and pitch-override rejection, native
written-pitch conventions, native overwide rows, and Standard-14/CP1252 character
coverage. Object ordering/compression/producer metadata differ between backends;
byte identity or every PDF viewer's optical rasterisation is not promised.
