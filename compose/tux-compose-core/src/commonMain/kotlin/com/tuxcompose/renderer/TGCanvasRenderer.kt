// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import com.tuxcompose.component.CursorStyle
import com.tuxcompose.component.TGCursorConfig
import com.tuxcompose.component.TGSelection
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import com.tuxcompose.renderer.glyphs.*
import kotlin.math.abs
import kotlin.math.max

/** Pure Compose vectors. Coordinates and unit glyph paths never pass through Android Canvas/View. */
class TGCanvasRenderer(private val config: TGLayoutConfig, private val text: TGComposeTextMetrics) {
    private val colors get() = config.colors

    fun DrawScope.render(layout: TGSongLayout, viewport: Rect) {
        layout.visible(viewport).forEach { staff ->
            layout.visibleMeasures(staff, viewport).forEach { measure -> renderMeasure(measure) }
            layout.visibleConnections(staff,viewport).forEach { renderConnection(it) }
        }
    }

    /** One fragment's notation; render() additionally draws each staff's cached connections. */
    fun DrawScope.renderMeasure(layout: TGMeasureLayout) {
        val b = layout.bounds
        layout.staffTop?.let { top -> repeat(5) { i -> line(b.left, top + i * config.staffSpacing, b.right, top + i * config.staffSpacing, colors.staffLines, .6f) } }
        layout.tabTop?.let { top ->
            val count = (((layout.tabBottom ?: top) - top) / config.stringSpacing + 1.5f).toInt()
            repeat(count) { i -> line(b.left, top + i * config.stringSpacing, b.right, top + i * config.stringSpacing, colors.tablatureLines, .6f) }
        }
        barlines(layout)
        header(layout)
        layout.beats.forEach { beat ->
            if (!beat.isContinuation) beat.beat.text?.let { label(it, beat.x, layout.textTop, TGTextRole.Text, colors.beatText) }
            if (!beat.isContinuation) beat.beat.chord?.let { chord(layout, beat, it) }
            (if (beat.isContinuation) emptyList() else effectLabels(beat.beat)).forEachIndexed { i, effect ->
                val y = layout.effectsTop + i * config.effectSpacing
                when (effect) {
                    ">" -> { line(beat.x, y + 1, beat.x + 6, y + 3, colors.noteHead); line(beat.x + 6, y + 3, beat.x, y + 5, colors.noteHead) }
                    "~" -> vibrato(beat.x, y + 3, minOf(beat.bounds.width, 18f))
                    "<" -> { line(beat.x + 7, y + 1, beat.x, y + 3, colors.noteHead); line(beat.x, y + 3, beat.x + 7, y + 5, colors.noteHead) }
                    else -> label(effect, beat.x - 1, y - 1, TGTextRole.Text, colors.beatText)
                }
            }
            beat.voices.forEach { voice ->
                if (voice.notes.isEmpty()) rest(layout, beat, voice)
                else voice.notes.forEach { note -> note(layout, beat, voice, note) }
            }
            // A beat with no voice is the public model's convenience rest.
            if (beat.voices.isEmpty()) rest(layout, beat, TGVoiceLayout(TGVoice(), beat.beat.duration, emptyList(), true, beat.x, 0f, 0f, -1))
        }
        stemsAndBeams(layout)
        layout.beats.forEach { beat ->
            beat.stroke?.lines?.forEach { drawLine(colors.noteHead,it.from,it.to,it.width) }
            beat.pickStroke?.lines?.forEach { drawLine(colors.noteHead,it.from,it.to,it.width) }
        }
        tuplets(layout)
        endings(layout)
    }

    fun DrawScope.renderSelection(layout: TGSongLayout, viewport: Rect, selection: TGSelection?) {
        if (selection == null) return
        layout.visible(viewport).filter { it.trackIndex == selection.trackIndex }.forEach { staff ->
            layout.visibleMeasures(staff, viewport).forEach { measure ->
                measure.beats.filter { it.startTick <= selection.endTick && it.endTick > selection.startTick }.forEach { beat ->
                    drawRect(colors.selection, beat.bounds.topLeft, beat.bounds.size)
                }
            }
        }
    }

    fun DrawScope.renderCursor(layout: TGSongLayout, trackIndex: Int, tick: Long, cursor: TGCursorConfig, opacity: Float = 1f) {
        renderCursor(layout.cursorAt(trackIndex,tick,cursor.smoothMovement),cursor,opacity)
    }

    fun DrawScope.renderCursor(position: TGCursorLayout?, cursor: TGCursorConfig, opacity: Float = 1f, displayX: Float? = null) {
        if (!cursor.visible || position == null) return
        val beat = position.beat
        val x = displayX ?: position.x
        val b = beat.bounds.translate(Offset(x - beat.x,0f))
        val base = cursor.color ?: colors.cursor
        val color = base.copy(alpha = base.alpha * opacity)
        when (cursor.style) {
            CursorStyle.Line -> line(x - 3, b.top + 4, x - 3, b.bottom - 4, color, cursor.width)
            CursorStyle.Block -> drawRect(color.copy(alpha = color.alpha * cursor.blockAlpha), b.topLeft, b.size)
            CursorStyle.Glow -> {
                listOf(5f to .06f, 3f to .1f, 1.7f to .2f, 1f to 1f).forEach { (size, alpha) ->
                    line(x - 3, b.top + 4, x - 3, b.bottom - 4, color.copy(alpha = color.alpha * alpha), cursor.width * size)
                }
            }
        }
        if (cursor.highlightNotes) beat.voices.flatMap { it.notes }.forEach { n ->
            (n.tabBounds ?: n.scoreBounds)?.let { drawRect(color, it.topLeft, it.size, style = Stroke(cursor.width)) }
        }
    }

    private fun DrawScope.barlines(m: TGMeasureLayout) {
        val top = m.staffTop ?: m.tabTop ?: return
        val bottom = m.tabBottom ?: (top + 4 * config.staffSpacing)
        if (m.isFirstFragment) line(m.bounds.left,top,m.bounds.left,bottom,colors.staffLines)
        if (m.isLastFragment) line(m.bounds.right,top,m.bounds.right,bottom,colors.staffLines)
        if (m.isFirstFragment && m.measure.header.repeatOpen) {
            line(m.bounds.left + 1.5f, top, m.bounds.left + 1.5f, bottom, colors.noteHead, 3f)
            line(m.bounds.left + 6, top, m.bounds.left + 6, bottom, colors.noteHead)
            repeatDots(m, m.bounds.left + 10)
        }
        if (m.isLastFragment && m.measure.header.repeatClose > 0) {
            line(m.bounds.right - 2, top, m.bounds.right - 2, bottom, colors.noteHead, 3f)
            line(m.bounds.right - 7, top, m.bounds.right - 7, bottom, colors.noteHead)
            repeatDots(m, m.bounds.right - 11)
            if (m.measure.header.repeatClose > 1) label("×${m.measure.header.repeatClose + 1}", m.bounds.right - 20, m.bounds.top, TGTextRole.Text, colors.beatText)
        }
    }
    private fun DrawScope.repeatDots(m: TGMeasureLayout, x: Float) {
        listOfNotNull(m.staffTop?.let { it + 2 * config.staffSpacing }, m.tabTop?.let { (it + (m.tabBottom ?: it)) / 2 }).forEach { y ->
            drawCircle(colors.noteHead, 1.5f, Offset(x, y - 4)); drawCircle(colors.noteHead, 1.5f, Offset(x, y + 4))
        }
    }

    private fun DrawScope.header(m: TGMeasureLayout) {
        val x = m.headerX
        val top = m.staffTop
        if (config.showMeasureNumbers && m.isFirstFragment) label(m.measure.number.toString(), m.bounds.left + 2, m.bounds.top, TGTextRole.Measure, colors.measureNumber)
        m.measure.header.marker?.takeIf { m.isFirstFragment }?.let { label(it, x, m.bounds.top + 10, TGTextRole.Chord, colors.beatText) }
        if (m.paintClef && top != null) {
            val glyph = if (m.percussion) TGClefPainter.NEUTRAL else when (m.measure.clef) {
                TGClef.Treble -> TGClefPainter.TREBLE
                TGClef.Bass -> TGClefPainter.BASS
                TGClef.Tenor -> TGClefPainter.TENOR
                TGClef.Alto -> TGClefPainter.ALTO
                TGClef.Percussion -> TGClefPainter.NEUTRAL
            }
            glyph(glyph, x, top, config.staffSpacing)
        }
        if (m.paintKey && top != null) key(m, x + m.clefWidth, top)
        if (m.paintTime) {
            val h = if (top != null) config.staffSpacing * 2 - 1 else config.stringSpacing * 1.5f
            val middle = if (top != null) top + 2 * config.staffSpacing else ((m.tabTop ?: m.bounds.top) + (m.tabBottom ?: m.bounds.bottom)) / 2
            val tx = x + m.clefWidth + m.keyWidth
            val a = m.measure.header.timeSignature.numerator
            val b = m.measure.header.timeSignature.denominator
            val width = maxOf(digitsWidth(a, h), digitsWidth(b, h))
            digits(a, tx + (width - digitsWidth(a, h)) / 2, middle - h, h)
            digits(b, tx + (width - digitsWidth(b, h)) / 2, middle, h)
        }
        if (m.paintTempo) {
            val y = (top ?: m.tabTop ?: m.bounds.top + 24) - maxOf(config.staffSpacing, config.stringSpacing) - 19
            glyph(TGNotePainter.NOTE, x, y + 12, 4f)
            line(x + 4.5f, y, x + 4.5f, y + 14, colors.noteHead)
            label("= ${m.measure.header.tempo}", x + 9, y + 6, TGTextRole.Text, colors.beatText)
        }
    }

    private fun DrawScope.key(m: TGMeasureLayout, startX: Float, y: Float) {
        val sharps = arrayOf(intArrayOf(1,4,0,3,6,2,5), intArrayOf(3,6,2,5,8,4,7), intArrayOf(7,3,6,2,5,1,4), intArrayOf(2,5,1,4,7,3,6))
        val flats = arrayOf(intArrayOf(5,2,6,3,7,4,8), intArrayOf(7,4,8,5,9,6,10), intArrayOf(4,1,5,2,6,3,7), intArrayOf(6,3,7,4,8,5,9))
        val clef = when (m.measure.clef) { TGClef.Bass -> 1; TGClef.Tenor -> 2; TGClef.Alto -> 3; else -> 0 }
        val previous = TGKeySignature(m.previousKey)
        val current = m.measure.header.keySignature
        var x = startX
        val from = if (previous.flats == current.flats) current.count else 0
        for (i in from until previous.count) {
            val p = if (previous.flats) flats[clef][i] else sharps[clef][i]
            glyph(TGKeySignaturePainter.NATURAL, x, y + (p - 1) * config.staffSpacing / 2, config.staffSpacing)
            x += config.staffSpacing * .75f
        }
        repeat(current.count) { i ->
            val p = if (current.flats) flats[clef][i] else sharps[clef][i]
            glyph(if (current.flats) TGKeySignaturePainter.FLAT else TGKeySignaturePainter.SHARP, x, y + (p - 1) * config.staffSpacing / 2, config.staffSpacing)
            x += config.staffSpacing * .75f
        }
    }

    private fun DrawScope.note(m: TGMeasureLayout, beat: TGBeatLayout, voice: TGVoiceLayout, n: TGNoteLayout) {
        n.scoreBounds?.let { b ->
            val fill = voice.duration.value >= 4 && !n.note.effects.dead && n.note.value != -1
            val percussion = m.percussion || m.measure.clef == TGClef.Percussion
            val glyph = when {
                n.note.effects.dead || n.note.value == -1 -> TGNotePainter.PERCUSSION_X
                n.note.effects.harmonic != 0 -> TGNotePainter.HARMONIC
                percussion && n.pitch in listOf(42,44,46,51,59) -> TGNotePainter.PERCUSSION_X
                percussion && n.pitch in listOf(49,52,55,57) -> TGNotePainter.PERCUSSION_CYMBAL
                percussion && n.pitch in listOf(54,56,76,77,80,81) -> TGNotePainter.PERCUSSION_TRIANGLE
                percussion && n.pitch == 53 -> TGNotePainter.HARMONIC
                else -> TGNotePainter.NOTE
            }
            glyph(glyph, b.left + if (fill) -.06f * b.height else 0f, b.top + if (fill) .06f * b.height else .1f * b.height, b.height * if (fill) .9f else .8f,
                filled = fill && glyph !== TGNotePainter.PERCUSSION_X)
            n.accidental?.let { accidental ->
                val a = when (accidental) { TGAccidental.Sharp -> TGKeySignaturePainter.SHARP; TGAccidental.Flat -> TGKeySignaturePainter.FLAT; TGAccidental.Natural -> TGKeySignaturePainter.NATURAL }
                glyph(a, b.left - config.staffSpacing * .75f, b.center.y, config.staffSpacing)
            }
            // Ledger lines follow the glyph center, in diatonic half-line steps.
            val centerY = b.center.y
            val staff = m.staffTop ?: 0f
            var ledger = staff - config.staffSpacing
            while (ledger >= centerY - .1f) { line(b.left - 2, ledger, b.right + 2, ledger, colors.staffLines); ledger -= config.staffSpacing }
            ledger = staff + 5 * config.staffSpacing
            while (ledger <= centerY + .1f) { line(b.left - 2, ledger, b.right + 2, ledger, colors.staffLines); ledger += config.staffSpacing }
            dots(voice.duration, b.left + 12f * config.staffSpacing / 8, b.center.y, config.staffSpacing / 10)
            n.note.effects.grace?.takeUnless { voice.isContinuation }?.let { grace ->
                glyph(if (grace.dead) TGNotePainter.PERCUSSION_X else TGNotePainter.NOTE, b.left - 9, b.top + 1, b.height * .55f)
                line(b.left - 4, b.top - 10, b.left - 4, b.top + 4, colors.noteHead, .7f)
            }
            if (n.note.effects.staccato && !voice.isContinuation) drawCircle(colors.noteHead, 1.3f, Offset(b.center.x, if (voice.up) voice.stemEndY - 4 else voice.stemEndY + 4))
        }
        n.tabBounds?.let { b ->
            // Clear JUST the text box. Font result is identical to the calculator's measurement.
            drawRect(colors.background, b.topLeft, b.size)
            label(tabText(n.note), b.center.x, b.center.y, TGTextRole.Note, colors.noteText, centered = true)
            n.note.effects.grace?.takeUnless { voice.isContinuation }?.let { label(it.fret.toString(), b.left - 5, b.center.y, TGTextRole.Grace, colors.noteText, centered = true) }
            n.note.effects.bend?.takeUnless { voice.isContinuation }?.let { bend -> bend(m, b, bend) }
            n.note.effects.tremoloBar?.takeUnless { voice.isContinuation }?.let { vibrato(b.right + 2, m.bendTop + 7, 12f) }
        }
    }

    private fun DrawScope.stemsAndBeams(m: TGMeasureLayout) {
        val entries = m.beats.flatMap { b -> b.voices.filter { it.notes.isNotEmpty() && it.duration.value >= 2 }.map { b to it } }
        entries.forEach { (_, v) -> line(v.stemX, v.stemStartY, v.stemX, v.stemEndY, colors.noteHead, .8f) }
        entries.groupBy { it.second.beamGroup }.values.forEach { group ->
            group.forEachIndexed { i, (_, voice) ->
                repeat(voice.duration.beamCount) { level ->
                    val offset = level * 4f * if (voice.up) 1 else -1
                    val next = group.getOrNull(i + 1)?.second
                    val previous = group.getOrNull(i - 1)?.second
                    when {
                        next != null && next.duration.beamCount > level -> beam(voice.stemX, voice.stemEndY + offset, next.stemX, next.stemEndY + offset)
                        previous != null && previous.duration.beamCount > level -> Unit // drawn by the previous note
                        group.size == 1 -> glyph(if (voice.up) TGNotePainter.FOOTER_UP else TGNotePainter.FOOTER_DOWN, voice.stemX, voice.stemEndY + offset, config.staffSpacing / 8f)
                        else -> {
                            val neighbor = next ?: previous ?: voice
                            val sign = if (next != null) 1 else -1
                            val length = minOf(8f, abs(neighbor.stemX - voice.stemX) / 3) * sign
                            val slope = if (neighbor.stemX != voice.stemX) (neighbor.stemEndY - voice.stemEndY) / (neighbor.stemX - voice.stemX) else 0f
                            beam(voice.stemX, voice.stemEndY + offset, voice.stemX + length, voice.stemEndY + offset + slope * length)
                        }
                    }
                }
                if (m.staffTop == null) dots(voice.duration, voice.stemX + 3, voice.stemEndY - 4, 1f)
                val tremolo = voice.notes.maxOfOrNull { it.note.effects.tremoloPicking } ?: 0
                if (tremolo > 0 && !voice.isContinuation) repeat(TGDuration(tremolo).beamCount) { n -> line(voice.stemX - 3, voice.stemStartY - 10 + n * 4, voice.stemX + 4, voice.stemStartY - 12 + n * 4, colors.noteHead, 2f) }
            }
        }
    }
    private fun DrawScope.beam(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawPath(Path().apply { moveTo(x1,y1); lineTo(x2,y2); lineTo(x2,y2+2.4f); lineTo(x1,y1+2.4f); close() }, colors.noteHead)
    }
    private fun DrawScope.rest(m: TGMeasureLayout, b: TGBeatLayout, v: TGVoiceLayout) {
        val glyph = when (v.duration.value) { 1 -> TGSilencePainter.WHOLE; 2 -> TGSilencePainter.HALF; 4 -> TGSilencePainter.QUARTER; 8 -> TGSilencePainter.EIGHTH; 16 -> TGSilencePainter.SIXTEENTH; 32 -> TGSilencePainter.THIRTY_SECOND; else -> TGSilencePainter.SIXTY_FOURTH }
        val spacing = if (m.staffTop != null) config.staffSpacing else config.stringSpacing
        val scale = spacing / if (m.staffTop != null) 9 else 10
        val top = m.staffTop ?: m.tabTop ?: m.bounds.top
        val middle = if (m.staffTop != null) top + 2 * spacing else (top + (m.tabBottom ?: top)) / 2
        val y = when (v.duration.value) { 1 -> top + spacing; 2 -> top + 2 * spacing - glyph.height * scale; else -> middle - glyph.height * scale / 2 }
        glyph(glyph, b.x, y + if (v.voice.index == 0) 0f else 8f, scale)
        dots(v.duration, b.x + 10, middle, 1f)
    }
    private fun DrawScope.tuplets(m: TGMeasureLayout) {
        m.beats.flatMap { b -> b.voices.filter { it.duration.division.enters != 1 }.map { b to it } }
            .groupBy { (b,v) -> Triple(v.voice.index, (b.startTick - m.measure.start) / m.measure.header.timeSignature.divisionTicks, v.duration.division.enters) }
            .values.forEach { group ->
                val x1 = group.first().first.x; val x2 = group.last().first.x + config.staffSpacing
                val y = if (group.first().second.voice.index == 0) m.tupletTop + 5 else (m.tabBottom ?: m.staffTop?.plus(40) ?: m.bounds.bottom - 10) + 10
                val division = m.measure.header.timeSignature.divisionTicks
                val groupStart = m.measure.start+(group.first().first.startTick-m.measure.start)/division*division
                val incoming = m.startTick > groupStart
                val outgoing = m.endTick < minOf(m.measure.end,groupStart+division)
                if (!incoming) line(x1,y+2,x1,y,colors.noteHead,.7f)
                line(x1,y,x2,y,colors.noteHead,.7f)
                if (!outgoing) line(x2,y,x2,y+2,colors.noteHead,.7f)
                if (incoming) return@forEach
                val label = group.first().second.duration.division.enters.toString()
                val size = text.measure(label,TGTextRole.Text)
                drawRect(colors.background, Offset((x1+x2-size.width)/2-2,y-size.height/2), Size(size.width+4,size.height))
                label(label,(x1+x2)/2,y,TGTextRole.Text,colors.noteHead,centered=true)
            }
    }
    private fun DrawScope.endings(m: TGMeasureLayout) {
        val mask = m.measure.header.repeatAlternative
        if (mask == 0) return
        val y = m.endingTop + 1
        if (m.isFirstFragment) line(m.beatAreaLeft,y+8,m.beatAreaLeft,y,colors.noteHead)
        line(m.beatAreaLeft,y,m.bounds.right-1,y,colors.noteHead)
        if (m.isFirstFragment) label((0..7).filter { mask and (1 shl it) != 0 }.joinToString(",") { "${it + 1}." }, m.beatAreaLeft + 3, y + 1, TGTextRole.Text, colors.beatText)
    }
    private fun DrawScope.chord(m: TGMeasureLayout, b: TGBeatLayout, chord: TGChord) {
        val bounds = b.chordBounds ?: return
        label(chord.name, bounds.left, bounds.top, TGTextRole.Chord, colors.noteHead)
        val top = bounds.top + 13; val left = bounds.left + 8; val width = (chord.frets.size - 1) * 5f
        repeat(chord.frets.size) { i -> line(left + i * 5, top, left + i * 5, top + 24, colors.staffLines, .5f) }
        repeat(5) { i -> line(left, top + i * 6, left + width, top + i * 6, colors.staffLines, if (i == 0 && chord.firstFret == 1) 1.8f else .5f) }
        chord.frets.forEachIndexed { i, fret ->
            val x = left + (chord.frets.lastIndex - i) * 5
            when {
                fret < 0 -> label("×", x, top - 4, TGTextRole.Grace, colors.noteHead, centered = true)
                fret == 0 -> drawCircle(colors.noteHead, 1.6f, Offset(x,top-3), style=Stroke(.6f))
                fret - chord.firstFret in 0..3 -> drawCircle(colors.noteHead, 1.7f, Offset(x,top+(fret-chord.firstFret+.5f)*6))
            }
        }
        if (chord.firstFret > 1) label(chord.firstFret.toString(), bounds.left, top + 1, TGTextRole.Grace, colors.beatText)
    }
    private fun DrawScope.bend(m: TGMeasureLayout, bounds: Rect, effect: TGBendEffect) {
        val movements = effect.movements()
        var x = bounds.right + 1
        val low = bounds.center.y
        val high = m.bendTop + 8
        if (movements.isEmpty()) {
            repeat(4) { i -> line(x + i * 5, high, x + i * 5 + 2.5f, high, colors.noteHead, .8f) }
            return
        }
        movements.forEach { movement ->
            val y0 = if (movement > 0) low else high
            val y1 = if (movement > 0) high else low
            val width = if (m.compact) 3f else 10f
            drawPath(Path().apply { moveTo(x,y0); cubicTo(x+1,y0,x+width,y0,x+width,y1) }, colors.noteHead, style=Stroke(.8f))
            val sign = if (movement > 0) 1 else -1
            line(x+width,y1,x+width-2,y1+2*sign,colors.noteHead,.8f)
            line(x+width,y1,x+width+2,y1+2*sign,colors.noteHead,.8f)
            val amplitude = abs(movement).let { if (it % 4 == 0) (it / 4).toString() else "$it/4" }
            label(amplitude,x+width,m.bendTop-1,TGTextRole.Grace,colors.beatText,centered=true)
            x += width
        }
    }
    private fun DrawScope.dots(duration: TGDuration, x: Float, y: Float, scale: Float) {
        if (!duration.dotted && !duration.doubleDotted) return
        drawCircle(colors.noteHead, 1.2f * scale, Offset(x,y))
        if (duration.doubleDotted) drawCircle(colors.noteHead, 1.2f*scale, Offset(x+4*scale,y))
    }
    private fun DrawScope.vibrato(x: Float, y: Float, width: Float) {
        val p = Path().apply {
            moveTo(x,y)
            var dx = 0f
            while (dx < width) { cubicTo(x+dx+1,y-3,x+dx+3,y+3,x+dx+4,y); dx+=4 }
        }
        drawPath(p,colors.noteHead,style=Stroke(.8f))
    }
    /** Draw cached row-local paths after notation. Even an incoming-only visible row has its path. */
    fun DrawScope.renderConnection(connection: TGConnectionLayout) {
        val from = connection.from
        val to = connection.to
        if (connection.kind == TGConnectionKind.Slide) drawLine(colors.noteHead,from,to,.8f)
        else {
            val h = connection.archHeight
            drawPath(Path().apply {
                moveTo(from.x,from.y)
                cubicTo(from.x,from.y+h,to.x,to.y+h,to.x,to.y)
                cubicTo(to.x,to.y+h*.65f,from.x,from.y+h*.65f,from.x,from.y)
                close()
            },colors.noteHead)
        }
        connection.label?.let { label(it,(from.x+to.x)/2,minOf(from.y,to.y)-9,TGTextRole.Grace,colors.beatText,centered=true) }
    }
    private fun DrawScope.glyph(g: TGVectorGlyph, x: Float, y: Float, size: Float, filled: Boolean = true) {
        withTransform({ translate(x,y); scale(size,size,Offset.Zero) }) {
            drawPath(g.path,colors.noteHead,style=if (filled) Fill else Stroke(.8f / size))
        }
    }
    private fun digitsWidth(value: Int, size: Float): Float = value.toString().sumOf { TGNumberPainter.digit(it.digitToInt()).width.toDouble() }.toFloat() * size + (value.toString().length-1) * .1f*size
    private fun DrawScope.digits(value: Int, x: Float, y: Float, size: Float) {
        var dx = x
        value.toString().forEach { c -> val glyph = TGNumberPainter.digit(c.digitToInt()); glyph(glyph,dx,y,size); dx+=(glyph.width+.1f)*size }
    }
    private fun DrawScope.label(value: String, x: Float, y: Float, role: TGTextRole, color: Color, centered: Boolean = false) {
        val result = text.layout(value,role)
        drawText(result,color=color,topLeft=if (centered) Offset(x-result.size.width/2f,y-result.size.height/2f) else Offset(x,y))
    }
    private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float = 1f) = drawLine(color,Offset(x1,y1),Offset(x2,y2),width)
}
