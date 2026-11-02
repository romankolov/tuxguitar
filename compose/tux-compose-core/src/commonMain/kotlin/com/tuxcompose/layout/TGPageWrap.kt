// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

/** A time slice of ONE unchanged model measure. X values are natural rhythmic-area offsets. */
internal data class TGPageFragment(val startTick: Long, val endTick: Long, val fromX: Float, val toX: Float)

internal data class TGFragmentHeader(
    val first: Float, val clef: Float, val key: Float, val previousKey: Int,
    val paintClef: Boolean, val paintKey: Boolean, val paintTime: Boolean, val paintTempo: Boolean,
)

/**
 * Only exceptional bars wider than a complete system are fragmented. Whole measures remain the
 * normal wrapping unit, exactly as TGLayoutVertical.getTempLines. No glyph/font/staff is scaled.
 * All tracks use these same time cuts, including sustained voices on a less dense track.
 */
internal fun wrapPageMeasure(
    start: Long, end: Long, offsets: Map<Long, Float>, rhythmWidth: Float,
    effects: Map<Long, Float>, available: Float, firstPrefix: Float, continuationPrefix: Float,
    padding: Float, repeatWidth: Float, compact: Boolean, divisionTicks: Long,
): List<TGPageFragment> {
    val actual = (offsets.keys + start + end).distinct().sorted()
    val cuts = mutableListOf(start to 0f)
    // Quarter subdivisions are also available inside an unusually long rhythmic gap. They are
    // presentation cuts, not new model beats. The original note is continued with a tie.
    val capacity = (available - 2 * padding - maxOf(firstPrefix,continuationPrefix)).coerceAtLeast(18f)
    actual.zipWithNext().forEach { (a,b) ->
        val x1 = offsets[a] ?: 0f
        val x2 = if (b == end) rhythmWidth else offsets.getValue(b)
        val effect = effects[a] ?: 0f
        val space = (x2 - x1 - effect).coerceAtLeast(0f)
        if (!compact && space + effect > capacity && b > a) {
            var tick = a
            while (tick < b) {
                val budget = (capacity - if (tick == a) effect else 0f).coerceAtLeast(18f)
                var step = TGDuration.QUARTER_TIME
                while (step > 1 && space * step.toDouble() / (b - a) > budget) step /= 2
                if (b - tick <= step) break
                tick += step
                val x = x1 + effect + space * ((tick - a).toDouble() / (b - a)).toFloat()
                cuts += tick to x
            }
        }
        cuts += b to x2
    }
    val result = mutableListOf<TGPageFragment>()
    var from = 0
    while (from < cuts.lastIndex) {
        val prefix = if (from == 0) firstPrefix else continuationPrefix
        var until = from + 1 // Always make progress, even below an indivisible glyph/header width.
        while (until < cuts.size) {
            val width = 2 * padding + prefix + cuts[until].second - cuts[from].second +
                if (until == cuts.lastIndex) repeatWidth else 0f
            if (width > available && until > from + 1) { until--; break }
            if (width > available || until == cuts.lastIndex) break
            until++
        }
        // Prefer complete metrical/beam groups, then their binary subdivisions, if doing so
        // uses at least half of the available rhythmic span. A group wider than a row can
        // still break at a beat, with open tuplet brackets and explicit connection segments.
        if (until < cuts.lastIndex && until > from+1) {
            val half = (cuts[until].second-cuts[from].second)/2
            var step = divisionTicks
            while (step > 0) {
                val preferred = (until downTo from+1).firstOrNull { i ->
                    (cuts[i].first-start)%step == 0L && cuts[i].second-cuts[from].second >= half
                }
                if (preferred != null) { until = preferred; break }
                if (step%2 != 0L) break
                step /= 2
            }
        }
        result += TGPageFragment(cuts[from].first,cuts[until].first,cuts[from].second,cuts[until].second)
        from = until
    }
    return result
}

/** Cache the complete, unscaled engraving once; fragment extraction is indexed, not quadratic. */
internal class TGMeasureFragments(private val full: TGMeasureLayout) {
    private val voices = full.beats.flatMap { beat -> beat.voices.map { beat to it } }
        .groupBy { it.second.voice.index }

    fun part(slice: TGPageFragment, bounds: Rect, header: TGFragmentHeader, padding: Float, extra: Float): TGMeasureLayout {
        val area = bounds.left + padding + header.first
        val dy = bounds.top - full.bounds.top
        val span = slice.toX - slice.fromX
        fun x(offset: Float): Float {
            val local = offset - slice.fromX
            return area + local + if (span > 0) extra * local / span else 0f
        }
        fun voiceAt(source: TGBeatLayout, voice: TGVoiceLayout, anchor: Float, tick: Long, continued: Boolean): TGVoiceLayout {
            val delta = Offset(anchor-source.x,dy)
            val remaining = (source.startTick + voice.duration.ticks - tick).coerceAtLeast(1L)
            val visible = minOf(remaining,(slice.endTick-tick).coerceAtLeast(1L))
            return voice.copy(
                duration = fragmentDuration(voice.duration,visible),
                notes = voice.notes.map { note -> note.copy(
                    scoreBounds=note.scoreBounds?.translate(delta),tabBounds=note.tabBounds?.translate(delta),
                    accidental=if (continued) null else note.accidental) },
                stemX=voice.stemX+delta.x,stemStartY=voice.stemStartY+dy,stemEndY=voice.stemEndY+dy,
                isContinuation=continued,sourceTick=source.startTick,
            )
        }
        fun beatAt(source: TGBeatLayout, continued: Boolean): TGBeatLayout {
            val tick = maxOf(slice.startTick,source.startTick)
            val anchor = if (continued) area else x(source.x-full.beatAreaLeft)
            val endX = x(minOf(slice.toX,source.endX-full.beatAreaLeft))
            val delta = Offset(anchor-source.x,dy)
            return source.copy(
                x=anchor,endX=endX,startTick=tick,endTick=minOf(source.endTick,slice.endTick),
                bounds=Rect(maxOf(area,anchor-5),bounds.top,maxOf(anchor+5,endX-5),bounds.bottom),
                voices=if (continued) emptyList() else source.voices.map { voiceAt(source,it,anchor,tick,false) },
                chordBounds=if (continued) null else source.chordBounds?.translate(delta),
            )
        }
        val from = floorIndex(full.beats.size) { full.beats[it].startTick < slice.startTick } + 1
        val until = floorIndex(full.beats.size) { full.beats[it].startTick < slice.endTick } + 1
        val beats = full.beats.subList(from,until).map { beatAt(it,false) }.toMutableList()
        val preceding = full.beats.getOrNull(from-1)
        if (preceding != null && beats.firstOrNull()?.startTick != slice.startTick)
            beats.add(0,beatAt(preceding,true))
        // A sparse track, or a longer second voice, must continue over the same row cuts as the
        // dense voice. Add tied display notes without mutating/copying source score events.
        if (slice.startTick > full.startTick && beats.firstOrNull()?.startTick == slice.startTick) {
            val first = beats.first()
            val present = first.voices.map { it.voice.index }.toSet()
            val continuing = voices.filterKeys { it !in present }.values.mapNotNull { lane ->
                val i = floorIndex(lane.size) { lane[it].first.startTick < slice.startTick }
                lane.getOrNull(i)?.takeIf { (beat,voice) -> beat.startTick+voice.duration.ticks > slice.startTick }
                    ?.let { (beat,voice) -> voiceAt(beat,voice,first.x,slice.startTick,true) }
            }
            beats[0] = first.copy(voices=(first.voices+continuing).sortedBy { it.voice.index })
        }
        return full.copy(
            bounds=bounds,staffTop=full.staffTop?.plus(dy),tabTop=full.tabTop?.plus(dy),tabBottom=full.tabBottom?.plus(dy),
            paintClef=header.paintClef,paintKey=header.paintKey,paintTime=header.paintTime,paintTempo=header.paintTempo,
            previousKey=header.previousKey,clefWidth=header.clef,keyWidth=header.key,beatAreaLeft=area,beats=beats,
            effectsTop=full.effectsTop+dy,bendTop=full.bendTop+dy,textTop=full.textTop+dy,
            chordTop=full.chordTop+dy,endingTop=full.endingTop+dy,tupletTop=full.tupletTop+dy,
            contentScale=1f,startTick=slice.startTick,endTick=slice.endTick,headerX=bounds.left+padding,
        )
    }
}

/** Prefer an exact notated duration for a clipped sustained voice; timing remains source-owned. */
private val fragmentDurations: Map<Long,TGDuration> by lazy {
    buildMap<Long,TGDuration> {
        val divisions = listOf(1 to 1) + TGDivisionType.supported.filter { it != (1 to 1) }
        divisions.forEach { (enters,times) ->
            for (value in listOf(1,2,4,8,16,32,64)) for (dots in 0..2) {
                val duration = TGDuration(value,dots==1,dots==2,TGDivisionType(enters,times))
                if (duration.ticks > 0 && duration.ticks !in this) put(duration.ticks,duration)
            }
        }
    }
}
private fun fragmentDuration(original: TGDuration, ticks: Long): TGDuration =
    if (ticks >= original.ticks) original else fragmentDurations[ticks] ?: original
