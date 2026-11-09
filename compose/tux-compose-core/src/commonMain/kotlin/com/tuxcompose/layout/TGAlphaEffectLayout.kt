// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Daniel Kuschny and alphaTab contributors.
// Kotlin adaptation of alphaTab 1.9.0, commit 9f1aa10460df1b326123d28a2222f7b20d081e61:
// rendering/glyphs/LineRangedGlyph.ts and rendering/EffectSystemPlacement.ts.
// This Source Code Form is subject to the terms of the Mozilla Public License,
// v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Painted geometry from LineRangedGlyph. The label is centred ON the first note. */
internal data class TGAlphaRangedGeometry(
    val label: Rect, val bounds: Rect, val from: Offset?, val to: Offset?,
    val dashSize: Float, val dashGap: Float, val thickness: Float, val cap: Rect?,
)

/**
 * LineRangedGlyph.paintNonGrouped/paintGrouped, expressed as immutable Compose geometry.
 * A singleton is just a centred label. A grouped line ends ON the last marked note,
 * not at its beat's right edge. A short group remains label-only: never move the line
 * below the caption. GroupedEffectGlyph restarts this geometry on EACH system.
 *
 * staffSpace is our fixed notation unit; the three ratios are EngravingSettings'
 * lineRangedGlyphDashSize/Gap and the Bravura pedalLineThickness default (0.16).
 */
internal fun alphaRangedGeometry(x: Float, y: Float, label: TGTextSize, endX: Float?, staffSpace: Float): TGAlphaRangedGeometry {
    val gap=.5f*staffSpace
    val dash=staffSpace
    val thickness=.16f*staffSpace
    val bounds=Rect(x-label.width/2f,y,x+label.width/2f,y+label.height)
    val startX=x+label.width/2f+gap/2f
    val lineY=y+label.height/2f
    val draws=endX!=null && endX>startX
    val cap=if(draws) Rect(endX!!,lineY-dash/2f,endX+thickness,lineY+dash/2f) else null
    return TGAlphaRangedGeometry(bounds,
        if(cap==null) bounds else Rect(bounds.left,minOf(bounds.top,cap.top),maxOf(bounds.right,cap.right),maxOf(bounds.bottom,cap.bottom)),
        if(draws) Offset(startX,lineY) else null,if(draws) Offset(endX!!,lineY) else null,dash,gap,thickness,cap)
}

/** A voice/effect/bar band; ranges are already in system coordinates. */
internal data class TGAlphaEffectBand(
    val effectId: String, val category: Int, val order: Int, val voice: Int, val renderer: Int,
    val height: Float, val ranges: List<Pair<Float,Float>>, val linkedToPrevious: Boolean=false,
)
internal data class TGAlphaEffectPlacement(val height: Float, val tops: List<Float>)

/**
 * EffectSystemPlacement's priority sort, two-phase linked-chain query/commit and
 * top-side coordinate conversion. No left-to-right caption shelves or ad-hoc offsets.
 * Categories: NoteAttached=0, Span=1, SystemMarker=2, HorizontalRow=3.
 * The containing TuxGuitar track lane supplies the already-reserved musical clearance.
 */
internal fun alphaPlaceEffectBands(bands: List<TGAlphaEffectBand>, left: Float, right: Float, pad: Float=5f): TGAlphaEffectPlacement {
    if(bands.isEmpty()) return TGAlphaEffectPlacement(0f,emptyList())
    val sky=TGSkyline(left,right)
    val sorted=bands.indices.sortedWith(compareBy<Int> {bands[it].category}
        .thenByDescending {bands[it].order.coerceIn(0,0xffff)}.thenBy {bands[it].voice}.thenBy {bands[it].renderer})
    val magnitudes=FloatArray(bands.size)
    var i=0
    while(i<sorted.size) {
        val band=bands[sorted[i]]
        var groupEnd=i+1
        while(groupEnd<sorted.size) {
            val next=bands[sorted[groupEnd]]
            if(next.effectId!=band.effectId || next.voice!=band.voice ||
                (if(band.category==3) next.category!=3 else !next.linkedToPrevious)) break
            groupEnd++
        }
        var magnitude=0f
        for(k in i until groupEnd) for((a,b) in bands[sorted[k]].ranges)
            magnitude=maxOf(magnitude,sky.maximum(a,b)+pad)
        // Query every chain member BEFORE inserting any of them; they share one y.
        for(k in i until groupEnd) {
            val index=sorted[k]; val member=bands[index]
            magnitudes[index]=magnitude
            for((a,b) in member.ranges) sky.reserve(a,b,magnitude+member.height)
        }
        i=groupEnd
    }
    val height=bands.indices.maxOf {magnitudes[it]+bands[it].height}
    return TGAlphaEffectPlacement(height,bands.indices.map {height-magnitudes[it]-bands[it].height})
}
