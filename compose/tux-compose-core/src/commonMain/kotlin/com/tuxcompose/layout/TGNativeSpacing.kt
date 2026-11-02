// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import com.tuxcompose.model.*
import kotlin.math.ceil

/**
 * TGMeasureImpl.calculateBeats/makeVoice/makeBeat/calculateWidth, at scale 1.
 * No font, text, strum or per-lane collision constraints belong in these equations.
 * The caller applies density/zoom once, after measuring the logical viewport.
 */
internal data class TGNativeSpacing(
    val quarter: Float,
    val quarters: Float,
    val beatWidths: List<Float>,
    val beatEffects: List<Float>,
) {
    val effects: Float get() = beatEffects.sum()
}

internal fun nativeMeasureSpacing(track: TGTrack, measure: TGMeasure, config: TGLayoutConfig, compact: Boolean): TGNativeSpacing {
    fun width(d: TGDuration) = config.durationWidths[minOf(d.index,config.durationWidths.lastIndex)]
    fun effect(v: TGVoice): Float = if (compact) 0f else v.notes.maxOfOrNull {
        (it.effects.bend?.movements()?.size ?: 0)*10f
    } ?: 0f
    var shortest: TGDuration? = null
    val beatWidths = mutableListOf<Float>()
    val beatEffects = mutableListOf<Float>()
    measure.beats.forEach { beat ->
        val voices = beat.voices.filterNot { it.empty }.sortedBy { it.index }
        voices.forEach { voice ->
            val d = voice.duration ?: beat.duration
            // Upstream compares rounded integer ticks, accepting the LAST duration on a tie.
            if (shortest == null || d.ticks <= shortest!!.ticks) shortest = d
        }
        if (voices.isEmpty()) {
            // Defensive presentation fallback for an unnormalised caller model. The actual
            // editor/importer completes rests before layout; core must not mutate the score.
            if (shortest == null || beat.duration.ticks <= shortest!!.ticks) shortest = beat.duration
        }
        beatWidths += voices.minOfOrNull { width(it.duration ?: beat.duration)+effect(it) } ?: width(beat.duration)
        beatEffects += voices.maxOfOrNull(::effect) ?: 0f
    }
    var quarter = shortest?.let { TGDuration.QUARTER_TIME.toFloat()/it.ticks*width(it) } ?: config.durationWidths.min()
    var previousChord: TGBeat? = null
    measure.beats.forEachIndexed { i,beat ->
        if (config.chordEnabled && beat.chord != null) {
            val chordWidth = 8f+5f*(track.stringTuning.size+1)
            if (compact) {
                // Unlike the quarter grid, compact checks ADJACENT beats, not adjacent chords.
                if (i > 0 && measure.beats[i-1].chord != null)
                    beatWidths[i-1] = maxOf(beatWidths[i-1],chordWidth)
            } else {
                previousChord?.let { previous ->
                    val distance = (beat.start-previous.start).toFloat()
                    if (distance > 0) quarter = maxOf(quarter,TGDuration.QUARTER_TIME*chordWidth/distance)
                }
            }
            previousChord = beat
        }
    }
    val last = measure.beats.lastOrNull()
    val quarters = if (last == null) measure.header.timeSignature.ticks.toFloat()/TGDuration.QUARTER_TIME else {
        val duration = last.voices.filterNot { it.empty }.maxOfOrNull { (it.duration ?: last.duration).preciseTicks }
            ?: last.duration.preciseTicks
        val end = (last.preciseStart ?: (last.start*TGDuration.PRECISE_QUARTER/TGDuration.QUARTER_TIME))+duration
        val start = measure.start*TGDuration.PRECISE_QUARTER/TGDuration.QUARTER_TIME
        // This float division/ceiling is intentional: it is what the original Java uses.
        ceil((end-start).toFloat()/TGDuration.PRECISE_QUARTER)
    }
    return TGNativeSpacing(quarter,quarters,beatWidths,beatEffects)
}

/** TGMeasureImpl.updateComponents: local effect displacement + shared-header effect slack. */
internal fun nativeBeatOffsets(measure: TGMeasure, spacing: TGNativeSpacing, compact: Boolean,
    quarter: Float, maxEffects: Float, multiTrack: Boolean): Map<Long,Float> {
    val quartersInSignature = measure.header.timeSignature.numerator*4f/measure.header.timeSignature.denominator
    val q = quarter+if (multiTrack) (maxEffects-spacing.effects)/quartersInSignature else 0f
    var x = 0f
    var effect = 0f
    return buildMap {
        put(measure.start,0f)
        measure.beats.forEachIndexed { i,beat ->
            // Precise starts determine the ending, but upstream PAINTS using legacy ticks.
            put(beat.start,if (compact) x else ((beat.start-measure.start).coerceAtLeast(0L).toFloat()/TGDuration.QUARTER_TIME)*q+effect)
            x += spacing.beatWidths[i]
            effect += spacing.beatEffects[i]
        }
    }
}

/** TGMeasureImpl.getBeatSpacing: move onsets proportionally; never scale glyphs or headers. */
internal fun nativeJustifiedOffset(offset: Float, rhythmicWidth: Float, extra: Float, minimumWidth: Float): Float {
    if (rhythmicWidth <= 0f) return offset
    return maxOf(minimumWidth,rhythmicWidth+extra)*offset/rhythmicWidth
}
