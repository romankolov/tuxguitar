// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import com.tuxcompose.layout.TGLayoutStyles
import com.tuxcompose.model.TGSong
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.TGLayout
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.TGLayoutStyles as NativeStyles
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.TGFactoryImpl
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintController
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintDocument
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintLayout
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintSettings
import com.tuxcompose.pdf.internal.tuxguitar.song.managers.TGSongManager
import com.tuxcompose.pdf.internal.tuxguitar.ui.resource.*
import com.tuxcompose.pdf.internal.tuxguitar.util.TGMessagesManager
import com.tuxcompose.pdf.internal.tuxguitar.resource.TGResourceBundle
import java.util.Locale
import java.util.Properties

/** Direct transcription of common/TuxGuitar-pdf/PDFLayoutStyles, not the screen profile. */
internal fun nativePdfStyles()=NativeStyles().apply {
    isBufferEnabled=false
    firstMeasureSpacing=15f;minBufferSeparator=15f;minTopSpacing=20f;minScoreTabSpacing=15f
    scoreLineSpacing=7f;firstTrackSpacing=5f;trackSpacing=5f;stringSpacing=8f
    chordFretIndexSpacing=8f;chordStringSpacing=4f;chordFretSpacing=5f;chordNoteSize=3f;chordLineWidth=0f
    repeatEndingSpacing=20f;textSpacing=15f;markerSpacing=15f;divisionTypeSpacing=10f;effectSpacing=8f
    firstNoteSpacing=10f;measureLeftSpacing=15f;measureRightSpacing=15f;clefSpacing=30f
    keySignatureSpacing=15f;timeSignatureSpacing=15f
    lineWidths=floatArrayOf(0f,1f,2f,3f,4f,5f)
    durationWidths=floatArrayOf(18f,18f,16f,15f,14f)
    defaultFont=UIFontModel("Times-Roman",8f,false,false)
    noteFont=UIFontModel("Times-Bold",9f,true,false)
    lyricFont=UIFontModel("Times-Roman",8f,false,false)
    textFont=UIFontModel("Times-Roman",8f,false,false)
    graceFont=UIFontModel("Times-Roman",8f,false,false)
    chordFont=UIFontModel("Times-Roman",8f,false,false)
    chordFretFont=UIFontModel("Times-Roman",8f,false,false)
    markerFont=UIFontModel("Times-Roman",8f,false,false)
    foregroundColor=UIColorModel(0,0,0);backgroundColor=UIColorModel(255,255,255)
    lineColor=UIColorModel(0,0,0);lineColorInvalid=UIColorModel(0,0,0)
    scoreNoteColor=UIColorModel(0,0,0);tabNoteColor=UIColorModel(0,0,0);playNoteColor=UIColorModel(0,0,0)
    loopSMarkerColor=UIColorModel(0,0,0);loopEMarkerColor=UIColorModel(0,0,0)
    measureNumberColor=UIColorModel(255,0,0)
}

internal fun nativePrintSettings(song: TGSong,options: TGPdfOptions)=TGPrintSettings().apply {
    trackNumber=options.trackIndex?.plus(1) ?: TGPrintSettings.ALL_TRACKS
    fromMeasure=options.fromMeasure;toMeasure=options.toMeasure ?: song.tracks.first().measures.size
    style=TGLayout.DISPLAY_MODE_BLACK_WHITE or
        (if(options.style!=TGLayoutStyles.Tablature) TGLayout.DISPLAY_SCORE else 0) or
        (if(options.style!=TGLayoutStyles.Score) TGLayout.DISPLAY_TABLATURE else 0) or
        (if(options.chordDiagrams) TGLayout.DISPLAY_CHORD_DIAGRAM else 0) or
        (if(options.chordNames) TGLayout.DISPLAY_CHORD_NAME else 0) or
        (if(options.compact) TGLayout.DISPLAY_COMPACT else 0)
}

/** Separate relocated native context: printing cannot mutate the demo/importer's globals. */
internal val nativePrintLock=Any()
internal fun renderNative(song: TGSong,options: TGPdfOptions,metadata: TGPdfMetadata,
    resources: UIResourceFactory,document: TGPrintDocument) = synchronized(nativePrintLock) {
    val labels=Properties().apply {
        put("track","Track");put("tuning","Tuning");put("composition.album","Album")
        put("composition.author","Author");put("composition.copyright","Copyright")
        put("composition.transcriber","Transcribed by");put("composition.writer","Tab Creator")
    }
    TGMessagesManager.getInstance().setResources(TGResourceBundle(Locale.ENGLISH,labels))
    val native=nativeScore(song,options,metadata)
    val manager=TGSongManager(TGFactoryImpl())
    // Exact PDFSongWriter pipeline: decoded clone/model, original print layout, native update and paint.
    val controller=TGPrintController(native,manager,resources,nativePdfStyles())
    val layout=TGPrintLayout(controller,nativePrintSettings(song,options))
    try {
        layout.loadStyles(options.zoomPercent/100f)
        layout.updateSong()
        layout.makeDocument(document)
    } finally {
        layout.disposeLayout()
        controller.resourceBuffer.disposeAllResources()
    }
}
