// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

@Immutable
data class TGNoteLayout(val note: TGNote, val voiceIndex: Int, val pitch: Int, val position: Int, val scoreBounds: Rect?, val tabBounds: Rect?, val accidental: TGAccidental?)
@Immutable
data class TGVoiceLayout(val voice: TGVoice, val duration: TGDuration, val notes: List<TGNoteLayout>, val up: Boolean, val stemX: Float, val stemStartY: Float, val stemEndY: Float, val beamGroup: Int,
    /** Presentation-only continuation of a sustained voice; no new model note or attack. */
    val isContinuation: Boolean = false, val sourceTick: Long = 0L)
@Immutable
data class TGBeatLayout(val beat: TGBeat, val bounds: Rect, val x: Float, val voices: List<TGVoiceLayout>, val chordBounds: Rect?, val endX: Float = bounds.right,
    /** Visible interval, which may be only part of the unchanged source beat. */
    val startTick: Long = beat.start, val endTick: Long = beat.start + beat.maxTicks,
    val stroke: TGStrokeLayout? = null, val pickStroke: TGStrokeLayout? = null) {
    val isContinuation: Boolean get() = startTick > beat.start
}
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
    val percussion: Boolean = false,
    /** Retained for source compatibility; page reflow never scales a system (always 1). */
    val contentScale: Float = 1f,
    val startTick: Long = measure.start,
    val endTick: Long = measure.end,
    val headerX: Float = bounds.left+15f,
    val pickStrokeTop: Float = effectsTop,
    /** Effective native compact mode (disabled when more than one track is displayed). */
    val compact: Boolean = false,
) {
    val isFirstFragment: Boolean get() = startTick == measure.start
    val isLastFragment: Boolean get() = endTick == measure.end
}
@Immutable
data class TGStaffLayout(val systemIndex: Int, val trackIndex: Int, val bounds: Rect, val measures: List<TGMeasureLayout>,
    /** Cached, row-local effect paths; no path crosses the whitespace between systems. */
    val connections: List<TGConnectionLayout> = emptyList())
@Immutable
data class TGHitResult(val trackIndex: Int, val measureIndex: Int, val beatTick: Long, val string: Int?, val voiceIndex: Int? = null)

/** The active beat stays discrete for highlighting; the playhead can sweep continuously through it. */
@Immutable
data class TGCursorLayout(val beat: TGBeatLayout, val x: Float, val systemIndex: Int, val measureIndex: Int, val trackIndex: Int) {
    val bounds: Rect get() = beat.bounds.translate(Offset(x - beat.x,0f))
}

/** Immutable geometry plus binary-search indices, independent of cursor/selection/scroll. */
class TGSongLayout(val width: Float, val height: Float, val staves: List<TGStaffLayout>) {
    private data class LocatedMeasure(val systemIndex: Int, val layout: TGMeasureLayout)
    private val byTrack = staves.flatMap { s -> s.measures.map { LocatedMeasure(s.systemIndex,it) } }.groupBy { it.layout.trackIndex }
    private val connectionIndices = staves.filter { it.connections.isNotEmpty() }.associate {
        (it.systemIndex to it.trackIndex) to TGConnectionIndex(it.connections)
    }
    fun visibleConnections(staff: TGStaffLayout, viewport: Rect): Sequence<TGConnectionLayout> =
        connectionIndices[staff.systemIndex to staff.trackIndex]?.visible(viewport) ?: emptySequence()
    private fun measureAt(trackIndex: Int, tick: Long): LocatedMeasure? {
        val measures = byTrack[trackIndex] ?: return null
        val i = floorIndex(measures.size) { measures[it].layout.startTick <= tick }
        return measures.getOrNull(i)?.takeIf { tick < it.layout.endTick }
    }
    fun beatAt(trackIndex: Int, tick: Long): TGBeatLayout? = cursorAt(trackIndex,tick,false)?.beat

    /** No playback clock: interpolate the supplied musical tick, never extrapolate future time. */
    fun cursorAt(trackIndex: Int, tick: Long, smooth: Boolean = true): TGCursorLayout? {
        val located = measureAt(trackIndex,tick) ?: return null
        val m = located.layout
        val i = floorIndex(m.beats.size) { m.beats[it].startTick <= tick }
        val beat = m.beats.getOrNull(i) ?: return null
        val end = beat.endTick
        val fraction = if (smooth && end > beat.startTick)
            ((tick - beat.startTick).toDouble() / (end - beat.startTick)).toFloat().coerceIn(0f,1f) else 0f
        return TGCursorLayout(beat,beat.x + (beat.endX - beat.x) * fraction,located.systemIndex,m.measureIndex,trackIndex)
    }
    fun visible(viewport: Rect): Sequence<TGStaffLayout> {
        // Systems are ordered by Y. Lower-bound the first intersecting staff, then stop below viewport.
        val first = floorIndex(staves.size) { staves[it].bounds.bottom < viewport.top } + 1
        return (first until staves.size).asSequence().map { staves[it] }.takeWhile { it.bounds.top <= viewport.bottom }
            .filter { it.bounds.overlaps(viewport) }
    }
    fun visibleMeasures(staff: TGStaffLayout, viewport: Rect): Sequence<TGMeasureLayout> {
        val first = floorIndex(staff.measures.size) { staff.measures[it].bounds.right < viewport.left } + 1
        return (first until staff.measures.size).asSequence().map { staff.measures[it] }
            .takeWhile { it.bounds.left <= viewport.right }.filter { it.bounds.overlaps(viewport) }
    }
    fun hitTest(point: Offset, stringSpacing: Float): TGHitResult? {
        val staff = visible(Rect(point.x, point.y, point.x + .01f, point.y + .01f)).firstOrNull() ?: return null
        val i = floorIndex(staff.measures.size) { staff.measures[it].bounds.left <= point.x }
        val measure = staff.measures.getOrNull(i)?.takeIf { it.bounds.contains(point) } ?: return null
        if (measure.beats.isEmpty()) return null
        // A wide fret/head can extend past the nearest-onset Voronoi cell (and the first
        // fret extends left of beatAreaLeft). Prefer actual ink hits before empty-space
        // seeking, otherwise a valid tap on a large custom glyph selects its neighbor.
        measure.beats.forEach { beat -> beat.voices.forEach { voice -> voice.notes.forEach { note ->
            if (note.tabBounds?.contains(point) == true || note.scoreBounds?.contains(point) == true)
                return TGHitResult(staff.trackIndex,measure.measureIndex,beat.startTick,note.note.string,note.voiceIndex)
        } } }
        if (point.x < measure.beatAreaLeft) return null
        val beat = measure.beats.minByOrNull { kotlin.math.abs(it.x - point.x) } ?: return null
        val spacing = stringSpacing
        val string = measure.tabTop?.let { top ->
            if (point.y in (top - spacing / 2)..((measure.tabBottom ?: top) + spacing / 2))
                ((point.y - top) / spacing + 1.5f).toInt().coerceIn(1, (((measure.tabBottom ?: top) - top) / spacing + 1.5f).toInt()) else null
        }
        val note = beat.voices.flatMap { it.notes }.firstOrNull { it.tabBounds?.contains(point) == true || it.scoreBounds?.contains(point) == true }
        return TGHitResult(staff.trackIndex, measure.measureIndex, beat.startTick, note?.note?.string ?: string, note?.voiceIndex)
    }
}

internal inline fun floorIndex(size: Int, predicate: (Int) -> Boolean): Int {
    var lo = 0; var hi = size - 1; var result = -1
    while (lo <= hi) { val mid = (lo + hi) ushr 1; if (predicate(mid)) { result = mid; lo = mid + 1 } else hi = mid - 1 }
    return result
}
