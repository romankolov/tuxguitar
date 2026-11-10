// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

/** A time slice of ONE unchanged model measure. X values are natural rhythmic-area offsets. */
internal data class TGPageFragment(val startTick: Long, val endTick: Long, val fromX: Float, val toX: Float,
    /** Ink may extend beyond this time cut; reserve it without distorting cursor time. */
    val contentToX: Float = toX)

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
    inkRights: Map<Long,Float> = emptyMap(), continuationRight: Float = 0f,
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
        // A sparse chord/text can span several onsets without forcing their spacing apart.
        // At a row break its WHOLE ink must still travel with the owning attack. Cache the
        // window maxima while scanning, including the values needed by metrical backtracking.
        val rights = mutableListOf(cuts[from].second+continuationRight)
        fun contentTo(index: Int) = maxOf(cuts[index].second,rights[index-from])
        while (until < cuts.size) {
            rights += maxOf(rights.last(),inkRights[cuts[until-1].first] ?: cuts[from].second)
            val width = 2 * padding + prefix + contentTo(until) - cuts[from].second +
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
        result += TGPageFragment(cuts[from].first,cuts[until].first,cuts[from].second,cuts[until].second,contentTo(until))
        from = until
    }
    return result
}

/**
 * Cache the complete, unscaled engraving once; fragment extraction is indexed, not quadratic.
 * Rebase each vertical lane independently: a following bar sharing the row may add a chord,
 * text or picking lane, moving the staff without changing any glyph's size.
 */
internal class TGMeasureFragments(private val full: TGMeasureLayout) {
    private val hasSharedHeads=full.beats.any {b->b.voices.any {v->v.notes.any {!it.paintScoreHead}}}
    private val voices = full.beats.flatMap { beat -> beat.voices.map { beat to it } }
        .groupBy { it.second.voice.index }

    fun part(slice: TGPageFragment, bounds: Rect, header: TGFragmentHeader, padding: Float, extra: Float, lanes: TGSpacing): TGMeasureLayout {
        val area = bounds.left + padding + header.first
        fun top(lane: TGTrackLane) = bounds.top+lanes.position(lane)
        val score = full.staffTop?.let { top(TGTrackLane.Score) }
        val tab = full.tabTop?.let { top(TGTrackLane.Tab) }
        val scoreDelta = (score ?: 0f)-(full.staffTop ?: 0f)
        val tabDelta = (tab ?: 0f)-(full.tabTop ?: 0f)
        val stemDelta = if (score != null) scoreDelta else tabDelta
        val chordDelta = top(TGTrackLane.Chord)-full.chordTop
        val pickDelta = top(TGTrackLane.PickStroke)-full.pickStrokeTop
        val span = slice.toX - slice.fromX
        fun x(offset: Float): Float {
            val local = offset - slice.fromX
            return area + local + if (span > 0) extra * local / span else 0f
        }
        fun voiceAt(source: TGBeatLayout, voice: TGVoiceLayout, anchor: Float, tick: Long, continued: Boolean): TGVoiceLayout {
            val dx = anchor-source.x
            val remaining = (source.startTick + voice.duration.ticks - tick).coerceAtLeast(1L)
            val visible = minOf(remaining,(slice.endTick-tick).coerceAtLeast(1L))
            return voice.copy(
                duration = fragmentDuration(voice.duration,visible),
                notes = voice.notes.map { note -> note.copy(
                    scoreBounds=note.scoreBounds?.translate(Offset(dx,scoreDelta)),
                    tabBounds=note.tabBounds?.translate(Offset(dx,tabDelta)),
                    accidental=if (continued) null else note.accidental,
                    accidentalOrigin=if(continued) null else note.accidentalOrigin?.plus(Offset(dx,scoreDelta)),
                    accidentalBounds=if(continued) null else note.accidentalBounds?.translate(Offset(dx,scoreDelta)),
                    graceScoreOrigin=if(continued) null else note.graceScoreOrigin?.plus(Offset(dx,scoreDelta))) },
                stemX=voice.stemX+dx,stemStartY=voice.stemStartY+stemDelta,stemEndY=voice.stemEndY+stemDelta,
                isContinuation=continued,sourceTick=source.startTick,silenceY=voice.silenceY?.plus(stemDelta),
                augmentationDots=voice.augmentationDots.map {it+Offset(dx,scoreDelta)},
            )
        }
        fun beatAt(source: TGBeatLayout, continued: Boolean): TGBeatLayout {
            val tick = maxOf(slice.startTick,source.startTick)
            val anchor = if (continued) area else x(source.x-full.beatAreaLeft)
            val endX = x(minOf(slice.toX,source.endX-full.beatAreaLeft))
            val dx = anchor-source.x
            return source.copy(
                x=anchor,endX=endX,startTick=tick,endTick=minOf(source.endTick,slice.endTick),
                bounds=Rect(maxOf(area,anchor-5),bounds.top,maxOf(anchor+5,endX-5),bounds.bottom),
                voices=if (continued) emptyList() else source.voices.map { voiceAt(source,it,anchor,tick,false) },
                chordBounds=if (continued) null else source.chordBounds?.translate(Offset(dx,chordDelta)),
                stroke=if (continued) null else source.stroke?.translated(Offset(dx,if (tab != null) tabDelta else scoreDelta)),
                pickStroke=if (continued) null else source.pickStroke?.translated(Offset(dx,pickDelta)),
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
        // The original shared-head owner may have ended before this fragment.
        // Re-elect an owner among VISIBLE voices, using the fragment fill state.
        // Raw native geometry (which never suppresses heads) is left untouched.
        val visibleBeats=if(!hasSharedHeads) beats else beats.map {b->
            data class Head(val bounds: Rect,val pitch: Int,val filled: Boolean,val harmonic: Int,val dead: Boolean)
            val seen=mutableSetOf<Head>()
            b.copy(voices=b.voices.sortedBy {it.voice.index}.map {v->v.copy(notes=v.notes.map {n->
                val r=n.scoreBounds
                if(r==null) n else n.copy(paintScoreHead=seen.add(Head(r,n.pitch,v.duration.value>=4,
                    n.note.effects.harmonic,n.note.effects.dead || n.note.value==-1)))
            })})
        }
        return full.copy(
            bounds=bounds,staffTop=score,tabTop=tab,tabBottom=full.tabBottom?.plus(tabDelta),
            paintClef=header.paintClef,paintKey=header.paintKey,paintTime=header.paintTime,paintTempo=header.paintTempo,
            paintTripletFeel=full.paintTripletFeel && slice.startTick == full.measure.start,
            previousKey=header.previousKey,clefWidth=header.clef,keyWidth=header.key,beatAreaLeft=area,beats=visibleBeats,
            effectsTop=top(TGTrackLane.Effects),bendTop=top(TGTrackLane.Bend),textTop=top(TGTrackLane.Text),
            chordTop=top(TGTrackLane.Chord),endingTop=top(TGTrackLane.RepeatEnding),tupletTop=top(TGTrackLane.Tuplet1),
            pickStrokeTop=top(TGTrackLane.PickStroke),
            notationTop=top(TGTrackLane.Chord)+lanes.size(TGTrackLane.Chord),markerBaseline=top(TGTrackLane.Marker),effectLaneHeight=lanes.size(TGTrackLane.Effects),
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
