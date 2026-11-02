// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min
import kotlin.math.max

/** A connector is split at SYSTEM edges, never drawn diagonally through the inter-system gap. */
enum class TGConnectionKind { Tie, Hammer, Slide }
@Immutable
data class TGConnectionLayout(
    val kind: TGConnectionKind,
    val from: Offset,
    val to: Offset,
    val archHeight: Float = 0f,
    val label: String? = null,
    val continuesFromPrevious: Boolean = false,
    val continuesToNext: Boolean = false,
    val voiceIndex: Int,
    val string: Int,
    val sourceTick: Long,
    val targetTick: Long? = null,
    val onScore: Boolean = false,
) {
    val bounds: Rect get() = Rect(min(from.x,to.x)-1,min(from.y,to.y)+min(0f,archHeight)-12,
        max(from.x,to.x)+1,max(from.y,to.y)+max(0f,archHeight)+2)
}

private data class StaffPosition(val index: Int, val local: Int, val staff: TGStaffLayout)
private data class NotePosition(
    val staff: StaffPosition, val measure: TGMeasureLayout, val beat: TGBeatLayout,
    val voice: TGVoiceLayout, val note: TGNoteLayout,
)

/** Indexed once with the engraving, not searched from every note on every Canvas frame. */
internal fun connectStaves(staves: List<TGStaffLayout>, config: TGLayoutConfig): List<TGStaffLayout> {
    val paths = Array(staves.size) { mutableListOf<TGConnectionLayout>() }
    val tracks = staves.withIndex().groupBy { it.value.trackIndex }.mapValues { (_,rows) ->
        rows.mapIndexed { local,row -> StaffPosition(row.index,local,row.value) }
    }
    tracks.values.forEach { rows ->
        val notes = mutableMapOf<Pair<Int,Int>,MutableList<NotePosition>>()
        val rests = mutableMapOf<Int,MutableList<Long>>()
        rows.forEach { row -> row.staff.measures.forEach { m -> m.beats.forEach { b ->
            b.voices.forEach { v ->
                if (v.notes.isEmpty()) rests.getOrPut(v.voice.index) { mutableListOf() }.add(b.startTick)
                v.notes.forEach { n -> notes.getOrPut(v.voice.index to n.note.string) { mutableListOf() }
                    .add(NotePosition(row,m,b,v,n)) }
            }
            if (b.voices.isEmpty()) rests.getOrPut(0) { mutableListOf() }.add(b.startTick)
        } } }
        fun left(row: StaffPosition): Float = max(row.staff.bounds.left+2,(row.staff.measures.firstOrNull()?.beatAreaLeft ?: row.staff.bounds.left)-16)
        fun right(row: StaffPosition): Float = row.staff.bounds.right-2
        fun box(note: NotePosition, score: Boolean): Rect? = if (score) note.note.scoreBounds else note.note.tabBounds
        fun centerY(row: StaffPosition, note: NotePosition, score: Boolean): Float {
            val m = row.staff.measures.first()
            return if (!score) (m.tabTop ?: m.bounds.top)+(note.note.note.string-1)*config.stringSpacing
            else (m.staffTop ?: m.bounds.top)+((if (m.percussion) TGNotation.drumPosition(note.note.pitch) else TGNotation.position(note.note.pitch,m.measure.clef,m.measure.header.keySignature.value,note.note.note.altEnharmonic))+1)*config.staffSpacing/2
        }
        fun connect(source: NotePosition, target: NotePosition, kind: TGConnectionKind, score: Boolean = false) {
            val a = box(source,score) ?: return
            val b = box(target,score) ?: return
            val height = if (kind == TGConnectionKind.Slide) 0f else if (score)
                config.staffSpacing/2*(if (source.voice.up) 1 else -1) else -config.stringSpacing/2
            val rising = target.note.note.value.compareTo(source.note.note.value)
            fun y(base: Float, outgoing: Boolean): Float = when {
                kind == TGConnectionKind.Slide && rising > 0 -> base+(if (outgoing) 3 else -3)*config.stringSpacing/10
                kind == TGConnectionKind.Slide && rising < 0 -> base+(if (outgoing) -3 else 3)*config.stringSpacing/10
                kind == TGConnectionKind.Slide -> base-3*config.stringSpacing/10
                score -> base+(if (source.voice.up) config.noteHeadRadius+1 else -config.noteHeadRadius-1)
                else -> base-a.height/2-1
            }
            for (local in source.staff.local..target.staff.local) {
                val row = rows[local]
                val first = local == source.staff.local
                val last = local == target.staff.local
                val fromX = if (first) a.right+1 else left(row)
                val toX = if (last) b.left-1 else right(row)
                if (toX <= fromX) continue
                val fromY = y(if (first) a.center.y else centerY(row,source,score),true)
                val toY = y(if (last) b.center.y else centerY(row,source,score),false)
                paths[row.index] += TGConnectionLayout(kind,Offset(fromX,fromY),Offset(toX,toY),height,
                    label=if (first && kind == TGConnectionKind.Hammer) (if (rising < 0) "P" else "H") else null,
                    continuesFromPrevious=!first,continuesToNext=!last,voiceIndex=source.voice.voice.index,
                    string=source.note.note.string,sourceTick=source.voice.sourceTick,targetTick=target.beat.startTick,onScore=score)
            }
        }
        fun stub(note: NotePosition, kind: TGConnectionKind, incoming: Boolean, score: Boolean = false) {
            val b = box(note,score) ?: return
            val x1 = if (incoming) max(left(note.staff),b.left-18) else b.right+1
            val x2 = if (incoming) b.left-1 else min(right(note.staff),b.right+15)
            if (x2 <= x1) return
            val height = if (kind == TGConnectionKind.Slide) 0f else if (score)
                config.staffSpacing/2*(if (note.voice.up) 1 else -1) else -config.stringSpacing/2
            val y = if (score) (if (note.voice.up) b.bottom+1 else b.top-1) else b.top-1
            paths[note.staff.index] += TGConnectionLayout(kind,Offset(x1,y),Offset(x2,y),height,
                label=if (kind == TGConnectionKind.Hammer) "H" else null,
                voiceIndex=note.voice.voice.index,string=note.note.note.string,sourceTick=note.voice.sourceTick,onScore=score)
        }
        notes.forEach { (key,lane) ->
            val restTicks = rests[key.first].orEmpty()
            var previous: NotePosition? = null
            lane.forEach { current ->
                if (current.note.note.tied || current.voice.isContinuation) {
                    val before = previous
                    val lastRest = floorIndex(restTicks.size) { restTicks[it] <= current.beat.startTick }
                    val restBetween = before != null && lastRest >= 0 && restTicks[lastRest] > before.beat.startTick
                    if (before != null && !restBetween && before.note.pitch == current.note.pitch) {
                        connect(before,current,TGConnectionKind.Tie)
                        connect(before,current,TGConnectionKind.Tie,true)
                    } else {
                        stub(current,TGConnectionKind.Tie,true)
                        stub(current,TGConnectionKind.Tie,true,true)
                    }
                }
                previous = current
            }
            var next: NotePosition? = null
            for (i in lane.indices.reversed()) {
                val current = lane[i]
                if (!current.voice.isContinuation) {
                    val effect = current.note.note.effects
                    // Preserve TGNoteImpl.paintEffects' precedence and voice/string matching.
                    val kind = when {
                        effect.bend != null || effect.tremoloBar != null -> null
                        effect.slide -> TGConnectionKind.Slide
                        effect.hammer -> TGConnectionKind.Hammer
                        else -> null
                    }
                    if (kind != null) {
                        val target = next
                        val scoreOnly = current.note.tabBounds == null
                        if (target == null) stub(current,kind,false,scoreOnly) else connect(current,target,kind,scoreOnly)
                    }
                    next = current
                }
            }
        }
    }
    return staves.mapIndexed { i,staff -> if (paths[i].isEmpty()) staff else staff.copy(connections=paths[i]) }
}
