// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import com.tuxcompose.layout.TGLayoutStyles

/** PDF points (1/72 inch), never Android dp, device density or current screen zoom. */
data class TGPdfPage(val width: Float=550f,val height: Float=800f,
    val marginLeft: Float=20f,val marginTop: Float=20f,val marginRight: Float=20f,val marginBottom: Float=20f) {
    init {
        require(listOf(width,height,marginLeft,marginTop,marginRight,marginBottom).all {it.isFinite()})
        require(width in 72f..14400f && height in 72f..14400f)
        require(minOf(marginLeft,marginTop,marginRight,marginBottom)>=0f)
        require(width-marginLeft-marginRight>36f && height-marginTop-marginBottom>36f)
    }
    companion object {
        /** PDFSongWriter's actual default. It is NOT A4. */
        val TuxGuitar = TGPdfPage()
        val A4 = TGPdfPage(595.2756f,841.8898f)
        val Letter = TGPdfPage(612f,792f)
    }
}

/** TuxGuitar prints each selected track as a separate part, not a screen multi-track view. */
data class TGPdfOptions(
    val style: TGLayoutStyles=TGLayoutStyles.Mixed,
    /** Zero-based source index. Null means all tracks, each starting a new page. */
    val trackIndex: Int?=null,
    /** Inclusive, one-based native measure numbers. Null end means the final measure. */
    val fromMeasure: Int=1,
    val toMeasure: Int?=null,
    val zoomPercent: Int=100,
    val page: TGPdfPage=TGPdfPage.TuxGuitar,
    val chordDiagrams: Boolean=true,
    val chordNames: Boolean=true,
    val compact: Boolean=true,
) {
    init {
        require(trackIndex==null || trackIndex>=0)
        require(fromMeasure>=1 && (toMeasure==null || toMeasure>=fromMeasure))
        require(zoomPercent in 25..400)
    }
}

/** Optional print-only data absent from the intentionally small decoded core model. */
data class TGPdfMetadata(
    val author: String="",val year: String="",val copyright: String="",
    val transcriber: String="",val writer: String="",
    /** One entry per zero-based source track index. Inputs are snapshotted on export. */
    val lyrics: Map<Int,TGPdfLyrics> = emptyMap(),
) 
data class TGPdfLyrics(val text: String,val fromMeasure: Int=1) {
    init {require(fromMeasure>=1)}
}

data class TGPdfResult(val pageCount: Int,val bytesWritten: Long,val sha256: String)
