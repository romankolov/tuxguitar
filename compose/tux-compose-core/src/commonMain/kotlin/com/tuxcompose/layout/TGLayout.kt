// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

@Immutable
data class TGNoteLayout(val note: TGNote, val voiceIndex: Int, val pitch: Int, val position: Int, val scoreBounds: Rect?, val tabBounds: Rect?, val accidental: TGAccidental?)
@Immutable
data class TGVoiceLayout(val voice: TGVoice, val duration: TGDuration, val notes: List<TGNoteLayout>, val up: Boolean, val stemX: Float, val stemStartY: Float, val stemEndY: Float, val beamGroup: Int)
@Immutable
data class TGBeatLayout(val beat: TGBeat, val bounds: Rect, val x: Float, val voices: List<TGVoiceLayout>, val chordBounds: Rect?)
@Immutable
data class TGMeasureLayout(
    val trackIndex: Int,
    val measureIndex: Int,
    val measure: TGMeasure,
    val bounds: Rect,
    val staffTop: Float?,
    val tabTop: Float?,
    val tabBottom: Float?,
    val paintClef: Boolean,
    val paintKey: Boolean,
    val paintTime: Boolean,
    val paintTempo: Boolean,
    val previousKey: Int,
    val clefWidth: Float,
    val keyWidth: Float,
    val beatAreaLeft: Float,
    val beats: List<TGBeatLayout>,
    val effectsTop: Float,
    val bendTop: Float,
    val textTop: Float,
    val chordTop: Float,
    val endingTop: Float,
    val tupletTop: Float,
)
@Immutable
data class TGStaffLayout(val systemIndex: Int, val trackIndex: Int, val bounds: Rect, val measures: List<TGMeasureLayout>)
@Immutable
data class TGHitResult(val trackIndex: Int, val measureIndex: Int, val beatTick: Long, val string: Int?, val voiceIndex: Int? = null)

/** Immutable geometry plus binary-search indices, independent of cursor/selection/scroll. */
class TGSongLayout(val width: Float, val height: Float, val staves: List<TGStaffLayout>) {
    private val byTrack = staves.flatMap { it.measures }.groupBy { it.trackIndex }
    fun beatAt(trackIndex: Int, tick: Long): TGBeatLayout? {
        val measures = byTrack[trackIndex] ?: return null
        val i = floorIndex(measures.size) { measures[it].measure.start <= tick }
        val measure = measures.getOrNull(i) ?: return null
        if (tick >= measure.measure.end) return null
        val j = floorIndex(measure.beats.size) { measure.beats[it].beat.start <= tick }
        return measure.beats.getOrNull(j)
    }
    fun visible(viewport: Rect): Sequence<TGStaffLayout> {
        // Systems are ordered by Y. Lower-bound the first intersecting staff, then stop below viewport.
        val first = floorIndex(staves.size) { staves[it].bounds.bottom < viewport.top } + 1
        return staves.asSequence().drop(first).takeWhile { it.bounds.top <= viewport.bottom }
            .filter { it.bounds.overlaps(viewport) }
    }
    fun visibleMeasures(staff: TGStaffLayout, viewport: Rect): Sequence<TGMeasureLayout> {
        val first = floorIndex(staff.measures.size) { staff.measures[it].bounds.right < viewport.left } + 1
        return staff.measures.asSequence().drop(first).takeWhile { it.bounds.left <= viewport.right }
    }
    fun hitTest(point: Offset, stringSpacing: Float): TGHitResult? {
        val staff = visible(Rect(point.x, point.y, point.x + .01f, point.y + .01f)).firstOrNull() ?: return null
        val i = floorIndex(staff.measures.size) { staff.measures[it].bounds.left <= point.x }
        val measure = staff.measures.getOrNull(i)?.takeIf { it.bounds.contains(point) } ?: return null
        if (point.x < measure.beatAreaLeft || measure.beats.isEmpty()) return null
        val beat = measure.beats.minByOrNull { kotlin.math.abs(it.x - point.x) } ?: return null
        val string = measure.tabTop?.let { top ->
            if (point.y in (top - stringSpacing / 2)..((measure.tabBottom ?: top) + stringSpacing / 2))
                ((point.y - top) / stringSpacing + 1.5f).toInt().coerceAtLeast(1) else null
        }
        val note = beat.voices.flatMap { it.notes }.firstOrNull { it.tabBounds?.contains(point) == true || it.scoreBounds?.contains(point) == true }
        return TGHitResult(staff.trackIndex, measure.measureIndex, beat.beat.start, note?.note?.string ?: string, note?.voiceIndex)
    }
}

internal inline fun floorIndex(size: Int, predicate: (Int) -> Boolean): Int {
    var lo = 0; var hi = size - 1; var result = -1
    while (lo <= hi) { val mid = (lo + hi) ushr 1; if (predicate(mid)) { result = mid; lo = mid + 1 } else hi = mid - 1 }
    return result
}
