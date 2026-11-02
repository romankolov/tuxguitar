// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import com.tuxcompose.model.*
import kotlin.math.abs

/** Horizontal ink relative to an onset, in ONE track and ONE vertical lane. */
internal data class TGHorizontalInk(val tick: Long, val left: Float, val right: Float, val gap: Float = 1f)
internal data class TGMeasureInk(
    val lanes: List<List<TGHorizontalInk>> = emptyList(),
    val leading: Float = 0f,
    val continuationRight: Float = 0f,
)
internal data class TGMeasureSpacing(val offsets: Map<Long,Float>, val rhythm: Float, val inkRights: Map<Long,Float>)

/**
 * Chords, text and effects do not occupy the fret/head lane. In particular, a lone chord must
 * NOT set the spacing of every eighth note (TGMeasureImpl compares successive CHORD onsets).
 * Measure only visible ink; use the same font metrics and geometry as the Canvas painter.
 */
internal fun measureInk(track: TGTrack, measure: TGMeasure, config: TGLayoutConfig, fonts: TGFontMetrics, compact: Boolean): TGMeasureInk {
    // Keep tracks AND lanes separate. Only their resulting onset constraints are shared.
    val score = mutableListOf<TGHorizontalInk>()
    val tab = mutableListOf<TGHorizontalInk>()
    val chords = mutableListOf<TGHorizontalInk>()
    val text = mutableListOf<TGHorizontalInk>()
    val effects = mutableListOf<TGHorizontalInk>()
    val picking = mutableListOf<TGHorizontalInk>()
    val accidentals = TGAccidentals(measure.header.keySignature.value)
    var continuationRight = 0f
    measure.beats.forEach { beat ->
        val voices = beat.voices.filterNot { it.empty }
        var scoreLeft = 0f
        var scoreRight = 0f
        var tabLeft = 0f
        var tabRight = 0f
        var hasScore = false
        var hasTab = false
        voices.forEach { voice ->
            val duration = voice.duration ?: beat.duration
            if (voice.notes.isEmpty()) {
                // Largest original rest is < 11 units wide at its painter's scale.
                val scale = if (config.showStandardNotation) config.staffSpacing/9 else config.stringSpacing/10
                val right = maxOf(11f*scale+.5f,if (duration.doubleDotted) 15.2f else if (duration.dotted) 11.2f else 0f)
                continuationRight = maxOf(continuationRight,right+1)
                if (config.showStandardNotation) { hasScore = true; scoreRight = maxOf(scoreRight,right) }
                else { hasTab = true; tabRight = maxOf(tabRight,right) }
            }
            voice.notes.forEach { note ->
                if (config.showStandardNotation) {
                    hasScore = true
                    // The filled head has a small negative bearing; ledger lines add two units.
                    var left = -maxOf(.5f,.12f*config.noteHeadRadius)
                    var right = 2.66f*config.noteHeadRadius
                    val pitch = track.pitch(note)
                    val position = if (track.percussion) TGNotation.drumPosition(pitch) else
                        TGNotation.position(pitch,measure.clef,measure.header.keySignature.value,note.altEnharmonic)
                    if (position <= -3 || position >= 9) { left = minOf(left,-2.5f); right += 2.5f }
                    if (!track.percussion && !note.effects.dead && note.value != -1 && accidentals.next(pitch,note.altEnharmonic) != null)
                        left = minOf(left,-config.staffSpacing*.75f-.5f)
                    if (duration.dotted || duration.doubleDotted) right = maxOf(right,
                        12f*config.staffSpacing/8 + (if (duration.doubleDotted) 4f else 0f)*config.staffSpacing/10 + 1.2f*config.staffSpacing/10)
                    continuationRight = maxOf(continuationRight,right+1)
                    if (note.effects.grace != null) left = minOf(left,-9.5f)
                    scoreLeft = minOf(scoreLeft,left); scoreRight = maxOf(scoreRight,right)
                }
                if (config.showTablature) {
                    hasTab = true
                    val half = fonts.measure(tabText(note),TGTextRole.Note).width/2+1.5f
                    var left = -half
                    var right = half
                    continuationRight = maxOf(continuationRight,right+1)
                    note.effects.grace?.let {
                        val graceHalf = fonts.measure(it.fret.toString(),TGTextRole.Grace).width/2
                        left = minOf(left,-half-5-graceHalf)
                        right = maxOf(right,-half-5+graceHalf)
                    }
                    note.effects.bend?.let { bend ->
                        val movements = bend.movements()
                        if (movements.isEmpty()) right = maxOf(right,half+19f)
                        else movements.forEachIndexed { index,movement ->
                            val amount = abs(movement).let { if (it%4 == 0) (it/4).toString() else "$it/4" }
                            val labelHalf = fonts.measure(amount,TGTextRole.Grace).width/2
                            val tip = half+1+(index+1)*(if (compact) 3f else 10f)
                            left = minOf(left,tip-labelHalf)
                            right = maxOf(right,tip+maxOf(2.5f,labelHalf))
                        }
                    }
                    if (note.effects.tremoloBar != null) right = maxOf(right,half+14.5f)
                    tabLeft = minOf(tabLeft,left); tabRight = maxOf(tabRight,right)
                }
            }
        }
        if (beat.stroke.direction != TGStrokeDirection.None) {
            val right = strokeOffset(beat,config,fonts)+2.5f
            if (config.showTablature) { hasTab = true; tabRight = maxOf(tabRight,right) }
            else { hasScore = true; scoreRight = maxOf(scoreRight,right) }
        }
        if (hasScore) score += TGHorizontalInk(beat.start,scoreLeft,scoreRight)
        if (hasTab) tab += TGHorizontalInk(beat.start,tabLeft,tabRight)
        beat.chord?.takeIf { config.chordEnabled }?.let { chord ->
            if (config.nativeSpacing) {
                val width = 8f+5f*(chord.frets.size+1)
                val shift = kotlin.math.floor(4f*config.renderScale+.5f)/config.renderScale
                val left = shift-(width-8f)/2-8f
                val nameHalf = if (config.showChordNames) fonts.measure(chord.name,TGTextRole.Chord).width/2 else 0f
                chords += TGHorizontalInk(beat.start,minOf(if (config.showChords) left else shift,shift-nameHalf),
                    maxOf(if (config.showChords) left+width else shift,shift+nameHalf))
                return@let
            }
            var right = maxOf(5f*maxOf(track.stringTuning.size,chord.frets.size)+8f,fonts.measure(chord.name,TGTextRole.Chord).width-4)
            if (chord.firstFret > 1) right = maxOf(right,fonts.measure(chord.firstFret.toString(),TGTextRole.Grace).width-4)
            chords += TGHorizontalInk(beat.start,-4f,right)
        }
        beat.text?.let { text += TGHorizontalInk(beat.start,0f,fonts.measure(it,TGTextRole.Text).width,4f) }
        effectLabels(beat).takeIf { it.isNotEmpty() }?.let { labels ->
            val right = labels.maxOf {
                when (it) {
                    ">" -> 6.5f
                    "<" -> 7.5f
                    "~" -> 20.5f // the 18-unit wave rounds up to complete four-unit segments
                    else -> fonts.measure(it,TGTextRole.Text).width-1
                }
            }
            effects += TGHorizontalInk(beat.start,-1f,maxOf(0f,right),2f)
        }
        if (beat.pickStroke != TGStrokeDirection.None) picking += TGHorizontalInk(beat.start,-4f,4f)
    }
    val lanes = listOf(score,tab,chords,text,effects,picking).filter { it.isNotEmpty() }
    // Also protects an attack that becomes the first beat of a later page fragment.
    val leading = ((lanes.maxOfOrNull { lane -> -lane.minOf { it.left } } ?: 0f)-config.firstNoteSpacing).coerceAtLeast(0f)
    return TGMeasureInk(lanes,leading,continuationRight)
}

/**
 * Smallest nondecreasing local additions to the original rhythmic grid. Each forward edge says
 * x[next] - x[previous] >= previous.right - next.left + clearance. Empty positions in other
 * lanes cost nothing. A forward longest-path pass is O(onsets + occupied-lane events), after
 * sorting the shared onsets. It cannot compress rhythm, reorder attacks, or misalign tracks.
 */
internal fun balanceMeasureSpacing(base: Map<Long,Float>, rhythm: Float, end: Long, ink: List<TGMeasureInk>): TGMeasureSpacing {
    val ticks = (base.keys+end).distinct().sorted()
    val index = ticks.withIndex().associate { it.value to it.index }
    val original = FloatArray(ticks.size) { if (ticks[it] == end) rhythm else base.getValue(ticks[it]) }
    val incoming = Array(ticks.size) { mutableListOf<Pair<Int,Float>>() }
    ink.forEach { track -> track.lanes.forEach { lane ->
        lane.zipWithNext().forEach { (a,b) ->
            incoming[index.getValue(b.tick)].add(index.getValue(a.tick) to (a.right-b.left+a.gap))
        }
        lane.lastOrNull()?.let { last -> incoming.last().add(index.getValue(last.tick) to (last.right+last.gap)) }
    } }
    val shift = FloatArray(ticks.size)
    ticks.indices.forEach { i ->
        var needed = if (i == 0) 0f else shift[i-1]
        incoming[i].forEach { (from,distance) ->
            needed = maxOf(needed,shift[from]+distance-(original[i]-original[from]))
        }
        shift[i] = needed
    }
    val offsets = base.keys.associateWith { tick -> index.getValue(tick).let { original[it]+shift[it] } }
    val rights = mutableMapOf<Long,Float>()
    ink.forEach { track -> track.lanes.forEach { lane -> lane.forEach { event ->
        rights[event.tick] = maxOf(rights[event.tick] ?: 0f,offsets.getValue(event.tick)+event.right+event.gap)
    } } }
    return TGMeasureSpacing(offsets,original.last()+shift.last(),rights)
}
