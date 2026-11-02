// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

/** Original UIPainter line widths: 0 is a device hairline, 2 is the down-pick's heavy cap. */
@Immutable
data class TGStrokeLine(val from: Offset, val to: Offset, val width: Float = 0f)

/** Cached line geometry for one strum arrow or picking mark; coordinates are song-local. */
@Immutable
data class TGStrokeLayout(val direction: TGStrokeDirection, val lines: List<TGStrokeLine>) {
    val bounds: Rect = if (lines.isEmpty()) Rect.Zero else Rect(
        lines.minOf { minOf(it.from.x,it.to.x) }, lines.minOf { minOf(it.from.y,it.to.y) },
        lines.maxOf { maxOf(it.from.x,it.to.x) }, lines.maxOf { maxOf(it.from.y,it.to.y) },
    ).inflate(maxOf(.5f,lines.maxOf { it.width }/2))

    internal fun translated(delta: Offset): TGStrokeLayout = copy(lines=lines.map {
        it.copy(from=it.from+delta,to=it.to+delta)
    })
}

/** Native uses exactly +12. Adaptive may move an arrow away from a custom/wide fret glyph. */
internal fun strokeOffset(beat: TGBeat, config: TGLayoutConfig, fonts: TGFontMetrics): Float =
    if (config.nativeSpacing) 12f else maxOf(12f,if (config.showTablature) beat.voices.flatMap { it.notes }.maxOfOrNull {
        fonts.measure(tabText(it),TGTextRole.Note).width/2+4.5f
    } ?: 0f else config.noteHeadRadius*2.66f+1)

/** Direct port of TGBeatImpl.paintStroke. Mixed mode draws ONE arrow, on the tablature. */
internal fun strumLayout(direction: TGStrokeDirection, x: Float, score: Float?, tab: Float?,
    tabHeight: Float, config: TGLayoutConfig, offset: Float = 12f): TGStrokeLayout? {
    if (direction == TGStrokeDirection.None) return null
    val top: Float
    val bottom: Float
    if (tab != null) {
        val margin = minOf(config.stringSpacing*2,tabHeight-config.stringSpacing*2)/2
        top = tab+margin
        bottom = tab+tabHeight-margin
    } else {
        val y = score ?: return null
        top = y+config.staffSpacing
        bottom = y+(config.staffSpacing*4-config.staffSpacing)
    }
    val shaftX = x+offset
    val tip = if (direction == TGStrokeDirection.Up) bottom else top
    val base = if (direction == TGStrokeDirection.Up) top else bottom
    val wing = tip+if (direction == TGStrokeDirection.Up) -5f else 5f
    return TGStrokeLayout(direction,listOf(
        TGStrokeLine(Offset(shaftX,base),Offset(shaftX,tip)),
        TGStrokeLine(Offset(shaftX-2,wing),Offset(shaftX,tip)),
        TGStrokeLine(Offset(shaftX+2,wing),Offset(shaftX,tip)),
    ))
}

/** Direct port of TGBeatImpl.paintPickStroke: an up-pick V or down-pick staple with a heavy cap. */
internal fun pickStrokeLayout(direction: TGStrokeDirection, x: Float, y: Float): TGStrokeLayout? = when (direction) {
    TGStrokeDirection.None -> null
    TGStrokeDirection.Up -> TGStrokeLayout(direction,listOf(
        TGStrokeLine(Offset(x-3,y),Offset(x,y+8)),
        TGStrokeLine(Offset(x+3,y),Offset(x,y+8)),
    ))
    TGStrokeDirection.Down -> TGStrokeLayout(direction,listOf(
        TGStrokeLine(Offset(x-3,y+6),Offset(x-3,y)),
        TGStrokeLine(Offset(x+3,y),Offset(x+3,y+6)),
        TGStrokeLine(Offset(x-3,y),Offset(x+3,y),2f),
    ))
}
