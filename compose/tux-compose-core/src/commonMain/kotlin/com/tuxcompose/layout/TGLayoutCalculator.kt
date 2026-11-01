// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Port of TGLayout -> TGMeasureHeaderImpl -> TGMeasureImpl -> TGLayoutVertical/Horizontal.
 * Geometry is computed once per score/style/viewport, never during a cursor frame.
 */
class TGLayoutCalculator(private val config: TGLayoutConfig = TGLayoutConfig(), private val fonts: TGFontMetrics = TGFontMetrics.Approximate) {
    fun durationWidth(duration: TGDuration): Float = config.durationWidths[minOf(duration.index, config.durationWidths.lastIndex)]
    /** Exact TGLayout.getSpacingForQuarter (including legacy integer tick rounding). */
    fun spacingForQuarter(duration: TGDuration): Float = TGDuration.QUARTER_TIME.toFloat() / duration.ticks * durationWidth(duration)

    fun calculate(song: TGSong, viewportWidth: Float, selectedTrackIndex: Int = 0): TGSongLayout {
        require(viewportWidth.isFinite() && viewportWidth > 0)
        if (song.tracks.isEmpty()) return TGSongLayout(viewportWidth, 0f, emptyList())
        val indices = if (config.multiTrack) song.tracks.indices.toList() else listOf(selectedTrackIndex.coerceIn(song.tracks.indices))
        val tracks = indices.map { song.tracks[it] }
        if (config.multiTrack) validateAlignment(tracks)
        val count = tracks.maxOf { it.measures.size }
        if (count == 0) return TGSongLayout(viewportWidth, 0f, emptyList())
        val compact = config.compact && tracks.size == 1
        val metrics = tracks.map { track -> track.measures.mapIndexed { i, m -> measureMetrics(track, m, track.measures.getOrNull(i - 1), compact, false) } }
        fun column(index: Int, first: Boolean): Column {
            val measures = tracks.mapIndexedNotNull { ti, t -> t.measures.getOrNull(index)?.let { m ->
                if (first && config.repeatSystemSignatures) measureMetrics(t, m, t.measures.getOrNull(index - 1), compact, true) else metrics[ti][index]
            } }
            val q = measures.maxOf { it.quarter }
            val effect = measures.maxOf { it.effect }
            val firstSpace = measures.maxOf { it.first }
            val clefSpace = measures.maxOf { it.clef }
            val keySpace = measures.maxOf { it.key }
            val width = measures.maxOf { metric ->
                (if (compact) metric.beatWidths.sum() else q * metric.quarters + effect) + firstSpace + 2 * config.measurePadding + metric.repeatWidth
            }
            return Column(index, width, q, effect, firstSpace, clefSpace, keySpace, measures)
        }
        val available = (viewportWidth - 2 * config.pagePadding).coerceAtLeast(1f)
        val systems = mutableListOf<List<Column>>()
        val filled = mutableListOf<Boolean>()
        var next = 0
        while (next < count) {
            val row = mutableListOf<Column>()
            var used = 0f
            var full = false
            while (next < count) {
                val col = column(next, row.isEmpty())
                val lineBreak = row.isNotEmpty() && tracks.any { it.measures.getOrNull(next)?.lineBreak == true }
                if (config.orientation == TGLayoutOrientation.Page && row.isNotEmpty() && (used + col.width >= available || lineBreak)) {
                    full = !lineBreak
                    break
                }
                row += col; used += col.width; next++
                // A single oversized measure is never compressed; allow cross-axis panning.
                if (config.orientation == TGLayoutOrientation.Page && used >= available) { full = false; break }
            }
            systems += row; filled += full
        }
        val staves = mutableListOf<TGStaffLayout>()
        var top = config.pagePadding
        var maxRight = viewportWidth
        systems.forEachIndexed { si, columns ->
            val extra = if (config.orientation == TGLayoutOrientation.Page && filled[si] && config.justifySystems)
                ((available - columns.sumOf { it.width.toDouble() }.toFloat()) / columns.size).coerceAtLeast(0f) else 0f
            tracks.forEachIndexed { ti, track ->
                val lanes = staffSpacing(track, columns.mapNotNull { track.measures.getOrNull(it.index) })
                val measures = mutableListOf<TGMeasureLayout>()
                var left = config.pagePadding
                columns.forEach { col ->
                    val m = track.measures.getOrNull(col.index)
                    if (m != null) {
                        val localMetric = col.metrics[ti]
                        measures += placeMeasure(track, indices[ti], col.index, m, col, localMetric, Rect(left, top, left + col.width + extra, top + lanes.height), lanes, compact, extra)
                    }
                    left += col.width + extra
                }
                staves += TGStaffLayout(si, indices[ti], Rect(config.pagePadding, top, left, top + lanes.height), measures)
                maxRight = maxOf(maxRight, left + config.pagePadding)
                top += lanes.height + config.systemSpacing
            }
        }
        return TGSongLayout(maxRight, (top - config.systemSpacing + config.pagePadding).coerceAtLeast(0f), staves)
    }

    private fun validateAlignment(tracks: List<TGTrack>) {
        val reference = tracks.first()
        require(tracks.all { it.measures.size == reference.measures.size }) { "Multi-track measures must have equal counts" }
        tracks.drop(1).forEach { track ->
            require(track.measures.zip(reference.measures).all { (a, b) -> a.start == b.start && a.header.timeSignature == b.header.timeSignature }) { "Multi-track headers must share starts and time signatures" }
        }
    }

    private data class Metric(val quarter: Float, val effect: Float, val first: Float, val clef: Float, val key: Float,
        val quarters: Float, val repeatWidth: Float, val beatWidths: List<Float>, val beatEffects: List<Float>,
        val paintClef: Boolean, val paintKey: Boolean, val paintTime: Boolean, val paintTempo: Boolean, val previousKey: Int)
    private data class Column(val index: Int, val width: Float, val quarter: Float, val effect: Float, val first: Float, val clef: Float, val key: Float, val metrics: List<Metric>)

    private fun measureMetrics(track: TGTrack, m: TGMeasure, prev: TGMeasure?, compact: Boolean, systemFirst: Boolean): Metric {
        val score = config.showStandardNotation
        val paintClef = score && (prev == null || prev.clef != m.clef || systemFirst)
        val paintKey = score && (prev == null || prev.header.keySignature != m.header.keySignature || systemFirst)
        val paintTime = prev == null || prev.header.timeSignature != m.header.timeSignature || systemFirst
        val paintTempo = config.showTempo && (prev == null || prev.header.tempo != m.header.tempo)
        val clef = if (paintClef) round(16f + config.clefSpacing) else 0f
        val previousKey = if (systemFirst) 0 else prev?.header?.keySignature?.value ?: 0
        val keyCount = m.header.keySignature.count + if (prev != null && !systemFirst) prev.header.keySignature.count else 0
        val key = if (paintKey && keyCount > 0) round(6f * keyCount) + config.keySignatureSpacing else 0f
        val time = if (paintTime) 10f + config.timeSignatureSpacing else 0f
        val first = round(maxOf(if (paintTempo) 45f else 0f, clef + key + time + config.firstNoteSpacing))
        val durations = m.beats.flatMap { b -> b.voices.filterNot { it.empty }.map { it.duration ?: b.duration }.ifEmpty { listOf(b.duration) } }
        val minDuration = durations.minByOrNull { it.preciseTicks }
        var quarter = minDuration?.let(::spacingForQuarter) ?: config.durationWidths.min()
        val beatEffects = m.beats.map { beat -> beat.voices.flatMap { it.notes }.maxOfOrNull { note ->
            if (compact || !config.showTablature) 0f else (note.effects.bend?.movements()?.size ?: 0) * 10f
        } ?: 0f }
        val beatWidths = m.beats.mapIndexed { bi, beat ->
            val duration = beat.voices.filterNot { it.empty }.minByOrNull { (it.duration ?: beat.duration).preciseTicks }?.duration ?: beat.duration
            val textWidth = beat.voices.flatMap { it.notes }.maxOfOrNull { fonts.measure(tabText(it), TGTextRole.Note).width + 4f } ?: 0f
            val chord = beat.chord?.let { 8f + 5f * (track.stringTuning.size + 1) } ?: 0f
            val width = maxOf(durationWidth(duration), textWidth, chord)
            if (beat.chord != null) quarter = maxOf(quarter, chord / duration.quarters.toFloat())
            width + beatEffects[bi]
        }
        // Use actual precise ending for invalid/overfull measures; empty bars reserve their meter.
        val endQuarters = m.beats.maxOfOrNull { b -> b.startQuarters - m.start.toDouble() / 960 +
            (b.voices.filterNot { it.empty }.maxOfOrNull { (it.duration ?: b.duration).quarters } ?: b.duration.quarters) }
        val quarters = ceil(maxOf(m.header.timeSignature.ticks / 960.0, endQuarters ?: 0.0)).toFloat()
        // Font-driven collision protection is a deliberate extension of the upstream duration table.
        if (!compact) m.beats.forEachIndexed { i, b ->
            val next = m.beats.getOrNull(i + 1)
            val distance = next?.let { it.startQuarters - b.startQuarters } ?: b.duration.quarters
            if (distance > 0) quarter = maxOf(quarter, (beatWidths[i] - beatEffects[i]) / distance.toFloat())
        }
        return Metric(quarter, beatEffects.sum(), first, clef, key, quarters, if (m.header.repeatClose > 0) 20f else 0f, beatWidths, beatEffects, paintClef, paintKey, paintTime, paintTempo, previousKey)
    }

    private fun staffSpacing(track: TGTrack, measures: List<TGMeasure>): TGSpacing {
        val lanes = TGSpacing(config.style)
        lanes.register(TGTrackLane.Top, config.staffPadding)
        val allBeats = measures.flatMap { it.beats }
        val notes = allBeats.flatMap { it.voices }.flatMap { it.notes }
        if (measures.any { it.header.marker != null }) lanes.register(TGTrackLane.Marker, 15f)
        if (allBeats.any { it.text != null }) lanes.register(TGTrackLane.Text, maxOf(15f, fonts.measure("Mg", TGTextRole.Text).height))
        if (allBeats.any { it.chord != null }) lanes.register(TGTrackLane.Chord, 49f)
        if (measures.any { it.header.repeatAlternative > 0 }) lanes.register(TGTrackLane.RepeatEnding, 20f)
        if (allBeats.any { b -> b.voices.any { (it.duration ?: b.duration).division.enters != 1 && it.index == 0 } }) lanes.register(TGTrackLane.Tuplet1, 10f)
        if (allBeats.any { b -> b.voices.any { (it.duration ?: b.duration).division.enters != 1 && it.index != 0 } }) lanes.register(TGTrackLane.Tuplet2, 10f)
        val effectsHeight = allBeats.maxOfOrNull { b -> effectLabels(b).size * config.effectSpacing } ?: 0f
        lanes.register(TGTrackLane.Effects, effectsHeight)
        if (config.showTablature && notes.any { it.effects.bend != null || it.effects.tremoloBar != null }) lanes.register(TGTrackLane.Bend, 16f)
        if (config.showStandardNotation) {
            val positions = measures.flatMap { m -> m.beats.flatMap { it.voices }.flatMap { it.notes }.map { note -> notePosition(track, m, note) * config.staffSpacing / 2 } }
            // Same max/min registration strategy as TGLayoutVertical; reserve stem and ledger space.
            val minY = (positions.minOrNull() ?: 0f) - 28f * config.staffSpacing / 8f
            val maxY = (positions.maxOrNull() ?: (4 * config.staffSpacing)) + 35f * config.staffSpacing / 8f
            lanes.register(TGTrackLane.ScoreUp, maxOf(0f, -minY))
            lanes.register(TGTrackLane.Score, config.staffSpacing * 5)
            lanes.register(TGTrackLane.ScoreDown, (maxY - 4 * config.staffSpacing).coerceAtLeast(0f))
        }
        if (config.showTablature) {
            lanes.register(TGTrackLane.TabSeparator, if (config.showStandardNotation) config.scoreTabSpacing else config.stringSpacing)
            val tabHeight = (track.stringTuning.size - 1) * config.stringSpacing
            lanes.register(TGTrackLane.Tab, tabHeight + config.stringSpacing + 1 + if (config.showStandardNotation) 0f else config.stringSpacing * 2.5f)
        }
        lanes.register(TGTrackLane.Lyric, 10f)
        val bufferTarget = if (config.showStandardNotation) TGTrackLane.ScoreUp else TGTrackLane.Tab
        val buffer = lanes.position(bufferTarget) - lanes.position(TGTrackLane.Buffer)
        if (buffer < 20f) lanes.register(TGTrackLane.Buffer, 20f - buffer)
        val staffTarget = if (config.showStandardNotation) TGTrackLane.Score else TGTrackLane.Tab
        if (lanes.position(staffTarget) < config.staffPadding) lanes.register(TGTrackLane.Top, config.staffPadding - lanes.position(staffTarget))
        return lanes
    }

    private fun placeMeasure(track: TGTrack, trackIndex: Int, index: Int, m: TGMeasure, col: Column, metric: Metric, bounds: Rect, lanes: TGSpacing, compact: Boolean, extra: Float): TGMeasureLayout {
        val score = if (config.showStandardNotation) bounds.top + lanes.position(TGTrackLane.Score) else null
        val tab = if (config.showTablature) bounds.top + lanes.position(TGTrackLane.Tab) else null
        val area = bounds.left + config.measurePadding + col.first
        val groups = voiceGroups(track, m)
        var effectOffset = 0f
        var compactOffset = 0f
        val xs = m.beats.mapIndexed { bi, b ->
            // TGBeatImpl.getSpacing: spread justification proportionally through musical time.
            val relative = (b.startQuarters - m.start.toDouble() / 960).toFloat().coerceAtLeast(0f)
            val x = if (compact) area + compactOffset else area + relative * (col.quarter + (col.effect - metric.effect) / metric.quarters) + effectOffset
            val justified = x + if (compact) extra * bi / maxOf(m.beats.size, 1) else extra * relative / metric.quarters
            compactOffset += metric.beatWidths[bi]; effectOffset += metric.beatEffects[bi]
            justified
        }
        val accidentals = TGAccidentals(m.header.keySignature.value)
        val beats = m.beats.mapIndexed { bi, b ->
            val x = xs[bi]
            val nextX = xs.getOrNull(bi + 1) ?: (bounds.right - config.measurePadding - metric.repeatWidth)
            val voices = b.voices.filterNot { it.empty }.map { v ->
                val duration = v.duration ?: b.duration
                val g = groups.getValue(bi to v.index)
                val up = g.up
                val notes = v.notes.map { note ->
                    val pitch = track.pitch(note)
                    val position = notePosition(track, m, note)
                    val size = fonts.measure(tabText(note), TGTextRole.Note)
                    val nh = 2 * config.noteHeadRadius
                    val y = score?.plus(position * config.staffSpacing / 2)
                    TGNoteLayout(note, v.index, pitch, position,
                        y?.let { Rect(x, it, x + nh * 1.33f, it + nh) },
                        tab?.let { top -> val ty = top + (note.string - 1) * config.stringSpacing; Rect(x - size.width / 2 - 1.5f, ty - size.height / 2, x + size.width / 2 + 1.5f, ty + size.height / 2) },
                        if (track.percussion || note.effects.dead || note.value == -1) null else accidentals.next(pitch, note.altEnharmonic))
                }
                val stemX = x + if (score != null && up) config.staffSpacing * 1.085f else 0f
                val localStart = if (score != null) {
                    if (up) notes.maxOfOrNull { it.position * config.staffSpacing / 2 + config.noteHeadRadius } ?: 2 * config.staffSpacing
                    else notes.minOfOrNull { it.position * config.staffSpacing / 2 + config.noteHeadRadius } ?: 2 * config.staffSpacing
                } else (track.stringTuning.size - 1) * config.stringSpacing + config.stringSpacing / 2
                val origin = score ?: tab ?: bounds.top
                val endY = if (score != null) groupStemY(g, xs, bi) else localStart + 2 * config.stringSpacing
                TGVoiceLayout(v, duration, notes, up, stemX, origin + localStart, origin + endY, g.id)
            }
            val chordBounds = b.chord?.let { Rect(x - 4f, bounds.top + lanes.position(TGTrackLane.Chord), x + 5f * track.stringTuning.size + 8f, bounds.top + lanes.position(TGTrackLane.Chord) + 47f) }
            TGBeatLayout(b, Rect(maxOf(area, x - 5f), bounds.top, maxOf(x + 5f, nextX - 5f), bounds.bottom), x, voices, chordBounds)
        }
        return TGMeasureLayout(trackIndex, index, m, bounds, score, tab, tab?.plus((track.stringTuning.size - 1) * config.stringSpacing),
            metric.paintClef, metric.paintKey, metric.paintTime, metric.paintTempo, metric.previousKey,
            if (config.multiTrack) col.clef else metric.clef, if (config.multiTrack) col.key else metric.key,
            area, beats, bounds.top + lanes.position(TGTrackLane.Effects), bounds.top + lanes.position(TGTrackLane.Bend),
            bounds.top + lanes.position(TGTrackLane.Text), bounds.top + lanes.position(TGTrackLane.Chord), bounds.top + lanes.position(TGTrackLane.RepeatEnding), bounds.top + lanes.position(TGTrackLane.Tuplet1))
    }

    private data class Group(val id: Int, val beats: List<Int>, val positions: List<List<Int>>, val up: Boolean)
    private fun voiceGroups(track: TGTrack, m: TGMeasure): Map<Pair<Int, Int>, Group> {
        val result = mutableMapOf<Pair<Int, Int>, Group>()
        var id = 0
        val activeVoices = m.beats.flatMap { it.voices }.filter { !it.empty && it.notes.isNotEmpty() }.map { it.index }.distinct().size
        for (vi in 0..3) {
            val batches = mutableListOf<MutableList<Int>>()
            var previous: Int? = null
            m.beats.forEachIndexed { bi, b ->
                val voice = b.voices.find { it.index == vi && !it.empty } ?: return@forEachIndexed
                val before = previous?.let { m.beats[it] }
                val prevVoice = before?.voices?.find { it.index == vi }
                val division = m.header.timeSignature.divisionTicks
                val canJoin = before != null && prevVoice != null && prevVoice.notes.isNotEmpty() && voice.notes.isNotEmpty() &&
                    (prevVoice.duration ?: before.duration).value >= 8 && (voice.duration ?: b.duration).value >= 8 &&
                    (before.start - m.start) / division == (b.start - m.start) / division &&
                    before.start + (prevVoice.duration ?: before.duration).ticks <= b.start + 1
                if (!canJoin || batches.isEmpty()) batches += mutableListOf(bi) else batches.last() += bi
                previous = bi
            }
            batches.forEach { indices ->
                val positions = indices.map { bi -> m.beats[bi].voices.first { it.index == vi }.notes.map { notePosition(track, m, it) } }
                val all = positions.flatten()
                val middle = if (all.isEmpty()) 4 else all.min() + (all.max() - all.min()) / 2
                val explicit = indices.map { bi -> m.beats[bi].voices.first { it.index == vi }.stem }.lastOrNull { it != TGStemDirection.Auto }
                val up = when (explicit) { TGStemDirection.Up -> true; TGStemDirection.Down -> false; else -> if (activeVoices > 1) vi == 0 else config.showStandardNotation && middle > 3 }
                val group = Group(id++, indices, positions, up)
                indices.forEach { result[it to vi] = group }
            }
        }
        return result
    }

    /** TGBeatGroup.getY2: constrained ten-unit beam slope; interior extremes flatten the group. */
    private fun groupStemY(g: Group, xs: List<Float>, beatIndex: Int): Float {
        val positions = g.positions.map { p -> if (g.up) p.minOrNull() ?: 4 else p.maxOrNull() ?: 4 }
        val extreme = if (g.up) positions.min() else positions.max()
        val offset = (if (g.up) -28f else 35f) * config.staffSpacing / 8
        if (extreme != positions.first() && extreme != positions.last()) return extreme * config.staffSpacing / 2 + offset
        var y1 = positions.first() * config.staffSpacing / 2 + offset
        var y2 = positions.last() * config.staffSpacing / 2 + offset
        if (abs(y1 - y2) > 10f) {
            if (g.up) { if (y1 < y2) y2 = y1 + 10 else y1 = y2 + 10 }
            else { if (y1 > y2) y2 = y1 - 10 else y1 = y2 - 10 }
        }
        val x1 = xs[g.beats.first()]; val x2 = xs[g.beats.last()]
        return if (x2 == x1) y1 else y1 + (y2 - y1) * (xs[beatIndex] - x1) / (x2 - x1)
    }

    private fun notePosition(track: TGTrack, m: TGMeasure, note: TGNote): Int = if (track.percussion) TGNotation.drumPosition(track.pitch(note)) else TGNotation.position(track.pitch(note), m.clef, m.header.keySignature.value, note.altEnharmonic)
}

internal fun tabText(note: TGNote): String {
    val value = if (note.value == -1 || note.effects.dead) "X" else note.value.toString()
    return if (note.effects.ghost) "($value)" else if (note.effects.harmonic == 1) "<$value>" else value
}

/** TGBeatSpacing order, collapsed by effect category across all notes of a beat. */
internal fun effectLabels(beat: TGBeat): List<String> {
    val effects = beat.voices.flatMap { it.notes }.map { it.effects }
    return buildList {
        if (effects.any { it.accented }) add(">")
        if (effects.any { it.heavyAccented }) add("^")
        if (effects.any { it.harmonic > 1 }) add("A.H.")
        if (effects.any { it.tapping }) add("T")
        if (effects.any { it.slapping }) add("S")
        if (effects.any { it.popping }) add("P")
        if (effects.any { it.palmMute }) add("P.M.")
        if (effects.any { it.letRing }) add("let ring")
        if (effects.any { it.vibrato }) add("~")
        if (effects.any { it.trill != null }) add("tr")
        if (effects.any { it.fadeIn }) add("<")
    }
}

/** TGEffectBend.updateMovements, used by both width calculation and painting. */
internal fun TGBendEffect.movements(): List<Int> {
    if (points.size < 2) return emptyList()
    var start = points[0].second; var current = points[1].second
    return buildList {
        points.drop(2).forEach { (_, next) ->
            if ((next - current) * (current - start) < 0) { add(if (current > start) current else -start); start = current }
            current = next
        }
        if (current > start) add(current) else if (current < start) add(-start)
    }
}
