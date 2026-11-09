// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*
import kotlin.math.abs

/** Relative geometry: rhythmic onsets and TAB frets never move with displaced score heads. */
internal data class TGScoreNotePlacement(val headX: Float, val accidental: TGAccidental?,
    val accidentalOrigin: Offset?=null, val accidentalBounds: Rect?=null, val graceOrigin: Offset?=null, val paintHead: Boolean=true)
internal data class TGScoreBeatPlan(val notes: Map<Pair<Int,Int>,TGScoreNotePlacement>, val voiceShifts: Map<Int,Float>)

/**
 * Engraving conventions independently implemented after comparison with TuxGuitar
 * TGNoteImpl and alphaTab ScoreNoteChordGlyphBase / AccidentalGroupGlyph. No
 * alphaTab source or glyphs are copied. Compatibility mode keeps raw TG geometry.
 */
internal fun scoreEngraving(track: TGTrack, measure: TGMeasure, previous: TGMeasure?, config: TGLayoutConfig,
    directions: Map<Pair<Int,Int>,Boolean>): List<TGScoreBeatPlan> {
    val memory=TGAccidentals(measure.header.keySignature.value)
    val enhanced=config.avoidAnnotationCollisions && config.showStandardNotation
    val headWidth=2.66f*config.noteHeadRadius
    val stemWidth=2.17f*config.noteHeadRadius
    data class Entry(val key: Pair<Int,Int>,val note: TGNote,val position: Int,val pitch: Int,val filled: Boolean,
        var x: Float=0f,var accidental: TGAccidental?=null,var origin: Offset?=null,var bounds: Rect?=null,var grace: Offset?=null,var paintHead: Boolean=true)
    return measure.beats.mapIndexed { bi,beat ->
        val voices=beat.voices.filterNot {it.empty}.let {if(enhanced) it.sortedBy {v->v.index} else it}
        val entries=voices.flatMap {v->v.notes.mapIndexed {ni,n->
            val pitch=track.pitch(n)
            Entry(v.index to ni,n,if(track.percussion) TGNotation.drumPosition(pitch) else
                TGNotation.position(pitch,measure.clef,measure.header.keySignature.value,n.altEnharmonic),pitch,(v.duration ?: beat.duration).value>=4)
        }}
        fun compatible(a: Entry,b: Entry)=a.position==b.position && a.pitch==b.pitch && a.filled==b.filled &&
            a.note.effects.harmonic==b.note.effects.harmonic &&
            (a.note.effects.dead || a.note.value==-1)==(b.note.effects.dead || b.note.value==-1)
        val shifts=mutableMapOf<Int,Float>()
        val placed=mutableListOf<Entry>()
        voices.forEach {v->
            val own=entries.filter {it.key.first==v.index}
            val up=directions[bi to v.index] ?: (v.stem!=TGStemDirection.Down)
            if(enhanced) {
                // Alternate sides of the same stem for successive diatonic seconds.
                // Exact compatible unisons retain a shared head. More complex clusters
                // receive additional columns, never overprinted opaque head centres.
                val columns=mutableListOf<MutableList<Entry>>()
                val ordered=if(up) own.sortedByDescending {it.position} else own.sortedBy {it.position}
                ordered.forEach {e->
                    val col=columns.indexOfFirst {column->column.none {other->
                        abs(other.position-e.position)<=1 && !compatible(other,e)
                    }}.takeIf {it>=0} ?: columns.size.also {columns.add(mutableListOf())}
                    columns[col]+=e;e.x=col*stemWidth*(if(up) 1 else -1)
                }
                // Secondary voices keep their own stems. Share only genuinely compatible
                // unisons; otherwise shift the entire chord, not individual beat anchors.
                fun conflict(shift: Float)=own.any {a->placed.any {b->
                    abs(a.position-b.position)<=1 && a.x+shift<headWidth+b.x+1f && b.x<headWidth+a.x+shift+1f &&
                        !(compatible(a,b) && a.x+shift==b.x)
                }}
                val shift=if(!conflict(0f)) 0f else own.maxOfOrNull {a->
                    placed.filter {abs(a.position-it.position)<=1}.maxOfOrNull {it.x+headWidth+2f-a.x} ?: 0f
                }?.coerceAtLeast(0f) ?: 0f
                own.forEach {it.x+=shift};shifts[v.index]=shift
            }
            placed+=own
        }
        if(enhanced) entries.forEachIndexed {i,e->
            e.paintHead=entries.take(i).none {compatible(it,e) && it.x==e.x}
        }
        // Conflicting simultaneous spellings must both be explicit; neither can
        // silently establish the accidental for the following onset.
        val ambiguous=if(enhanced) entries.filterNot {it.note.effects.dead || it.note.value==-1}
            .groupBy {it.position}.filterValues {group->group.map {it.pitch}.distinct().size>1} else emptyMap()
        // Suppress a bar-crossing tie only with a matching real origin and unchanged
        // spelling/clef. Crucially it must NOT register accidental state in the new bar.
        entries.forEach {e->
            if(!track.percussion && !e.note.effects.dead && e.note.value!=-1) {
                val prior=if(enhanced && beat.start==measure.start && e.note.tied && previous?.clef==measure.clef)
                    previous!!.beats.asReversed().asSequence().flatMap {b->b.voices.asSequence()
                        .filter {it.index==e.key.first && !it.empty}.flatMap {v->v.notes.asSequence().map {n->Triple(b,v,n)}}}
                        .firstOrNull {it.third.string==e.note.string} else null
                val carry=prior!=null && !prior.third.effects.dead && prior.third.value!=-1 && prior.first.start+(prior.second.duration ?: prior.first.duration).ticks==measure.start &&
                    track.pitch(prior.third)==e.pitch && TGNotation.position(track.pitch(prior.third),previous!!.clef,
                        previous.header.keySignature.value,prior.third.altEnharmonic)==e.position
                e.accidental=if(carry) null else memory.next(e.pitch,e.note.altEnharmonic,e.position in ambiguous)
            }
        }
        ambiguous.values.forEach {group->group.forEach {memory.invalidate(it.pitch,it.note.altEnharmonic)}}
        if(enhanced) {
            // Conservative control-point envelope of all three unchanged native glyphs.
            // Reserve actual HEIGHTS, not merely distinct staff positions (nearby sharps
            // and naturals are much taller than noteheads). Whole containment is overlap.
            val space=config.staffSpacing
            val gap=maxOf(1f,space/8f)
            val width=.64f*space+.5f
            val right=(entries.minOfOrNull {it.x} ?: 0f)-gap
            val marked=entries.filter {it.accidental!=null}
            val boxes=marked.map {e->val y=(e.position+1)*space/2f
                Rect(0f,y-1.25f*space-.25f,width,y+1.12f*space+.25f)}
            val columns=packScoreColumns(boxes,gap)
            marked.forEachIndexed {i,e->
                val y=(e.position+1)*space/2f
                val x=right-(columns[i]+1)*(width+gap)
                e.origin=Offset(x,y);e.bounds=boxes[i].translate(Offset(x,0f)).let {it.copy(left=it.left-.25f)}
            }
            val inkLeft=minOf(entries.minOfOrNull {it.x} ?: 0f,entries.mapNotNull {it.bounds?.left}.minOrNull() ?: 0f)
            val maxShift=entries.maxOfOrNull {it.x} ?: 0f
            entries.filter {it.note.effects.grace!=null}.forEach {e->
                e.grace=Offset(inkLeft-10f-maxShift+e.x,(e.position+1)*space/2f-config.noteHeadRadius+1f)
            }

        }
        TGScoreBeatPlan(entries.associate {it.key to TGScoreNotePlacement(it.x,it.accidental,it.origin,it.bounds,it.grace,it.paintHead)},shifts)
    }
}

/** First-fit colouring of actual vertical ink intervals, stable for equal tops. */
internal fun packScoreColumns(bounds: List<Rect>, gap: Float=0f): List<Int> {
    require(gap.isFinite() && gap>=0f)
    val result=IntArray(bounds.size)
    val bottoms=mutableListOf<Float>()
    bounds.indices.sortedBy {bounds[it].top}.forEach {i->
        val b=bounds[i]
        require(b.top.isFinite() && b.bottom.isFinite() && b.top<=b.bottom)
        val col=bottoms.indexOfFirst {it+gap<=b.top}.takeIf {it>=0} ?: bottoms.size.also {bottoms+=Float.NEGATIVE_INFINITY}
        bottoms[col]=b.bottom;result[i]=col
    }
    return result.toList()
}
