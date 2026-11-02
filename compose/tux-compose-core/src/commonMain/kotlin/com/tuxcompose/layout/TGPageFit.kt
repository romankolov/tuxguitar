// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Rect

/**
 * Fit the exceptional, single-bar system as a whole, not just its rhythmic gaps.
 * Retain its original geometry for a uniform Canvas transform (including fonts/glyphs),
 * while exposing fitted bounds for culling, hit testing, cursor and custom overlays.
 */
internal fun TGMeasureLayout.fitted(scale: Float): TGMeasureLayout {
    if (scale == 1f) return this
    val left = bounds.left
    val top = bounds.top
    fun x(value: Float) = left + (value - left) * scale
    fun y(value: Float) = top + (value - top) * scale
    fun rect(value: Rect) = Rect(x(value.left),y(value.top),x(value.right),y(value.bottom))
    return copy(
        bounds = rect(bounds),
        staffTop = staffTop?.let(::y), tabTop = tabTop?.let(::y), tabBottom = tabBottom?.let(::y),
        clefWidth = clefWidth * scale, keyWidth = keyWidth * scale,
        beatAreaLeft = x(beatAreaLeft),
        beats = beats.map { beat ->
            beat.copy(bounds = rect(beat.bounds),x = x(beat.x),endX = x(beat.endX),
                chordBounds = beat.chordBounds?.let(::rect),
                voices = beat.voices.map { voice ->
                    voice.copy(stemX = x(voice.stemX),stemStartY = y(voice.stemStartY),stemEndY = y(voice.stemEndY),
                        notes = voice.notes.map { note -> note.copy(
                            scoreBounds = note.scoreBounds?.let(::rect),tabBounds = note.tabBounds?.let(::rect)) })
                })
        },
        effectsTop = y(effectsTop), bendTop = y(bendTop), textTop = y(textTop),
        chordTop = y(chordTop), endingTop = y(endingTop), tupletTop = y(tupletTop),
        contentScale = scale, unscaled = this,
    )
}
