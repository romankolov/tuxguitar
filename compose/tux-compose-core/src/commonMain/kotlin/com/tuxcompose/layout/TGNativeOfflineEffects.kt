// SPDX-License-Identifier: LGPL-2.1-or-later
// Direct geometry port of TuxGuitar TGNoteImpl.paint{Accentuated,HeavyAccentuated,FadeIn,Vibrato,Trill}.
// Reference: helge17/tuxguitar b4feea4fe2f0155d648a37282ec57f1ecb329a35.
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Rect
import kotlin.math.floor

/** UIPainter.closePath flushes a path; it does NOT add a geometric closing segment. */
internal fun nativeOfflineEffectPaintCalls(kind: TGAnnotationKind, voiceWidth: Float,
    trillTextWidth: Float = 0f): List<Pair<String,List<Float>>> = buildList {
    fun move(x: Float,y: Float) {add("moveTo" to listOf(x,y))}
    fun line(x: Float,y: Float) {add("lineTo" to listOf(x,y))}
    fun cubic(x1: Float,y1: Float,x2: Float,y2: Float,x: Float,y: Float) {
        add("cubicTo" to listOf(x1,y1,x2,y2,x,y))
    }
    when(kind) {
        TGAnnotationKind.Accent->{move(0f,2f);line(6f,4.5f);move(6f,4.5f);line(0f,7f)}
        TGAnnotationKind.HeavyAccent->{move(0f,5f);line(3f,1f);move(3f,1f);line(6f,5f)}
        TGAnnotationKind.FadeIn->{
            move(0f,4f);cubic(0f,4f,voiceWidth,4f,voiceWidth,0f)
            move(0f,4f);cubic(0f,4f,voiceWidth,4f,voiceWidth,8f);move(voiceWidth,8f)
        }
        TGAnnotationKind.Vibrato,TGAnnotationKind.Trill->{
            val trill=kind==TGAnnotationKind.Trill
            val start=if(trill) trillTextWidth else 0f
            val y=if(trill) 4f else 2f
            val loops=floor((voiceWidth-start-2f)/6f+.5f).toInt()
            if(loops>0) {
                move(start+2f,y+1f)
                var x=start
                repeat(loops) {i->
                    x=start+6f*i
                    line(x+2f,y+1f)
                    cubic(x+2f,y+1f,x+3f,y,x+4f,y+1f)
                    line(x+6f,y+3f)
                }
                line(x+7f,y+2f)
                cubic(x+7f,y+2f,x+8f,y+2f,x+7f,y+3f)
                for(i in loops-1 downTo 0) {
                    x=start+6f*i
                    line(x+6f,y+4f)
                    cubic(x+6f,y+4f,x+5f,y+5f,x+4f,y+4f)
                    line(x+2f,y+2f);line(x+1f,y+3f)
                }
                cubic(x+1f,y+3f,x,y+3f,x+1f,y+2f)
                line(x+2f,y+1f)
            }
        }
        else->error("Not a native offline path: $kind")
    }
    if(isNotEmpty()) add("closePath" to emptyList())
}

internal fun nativeOfflineKind(label: String): TGAnnotationKind? = when(label) {
    ">"->TGAnnotationKind.Accent
    "^"->TGAnnotationKind.HeavyAccent
    "<"->TGAnnotationKind.FadeIn
    "~"->TGAnnotationKind.Vibrato
    "Tr"->TGAnnotationKind.Trill
    else->null
}

/** Control-point envelope (conservative for cubics), including native outline width. */
internal fun nativeOfflineEffectBounds(kind: TGAnnotationKind,width: Float,trillTextWidth: Float=0f): Rect {
    val points=nativeOfflineEffectPaintCalls(kind,width,trillTextWidth).flatMap {it.second.chunked(2)}
    if(points.isEmpty()) return Rect.Zero
    val body=Rect(points.minOf {it[0]},points.minOf {it[1]},points.maxOf {it[0]},points.maxOf {it[1]})
    return if(kind==TGAnnotationKind.Vibrato || kind==TGAnnotationKind.Trill) body else body.inflate(.5f)
}
