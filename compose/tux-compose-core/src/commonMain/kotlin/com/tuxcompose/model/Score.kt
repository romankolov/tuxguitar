// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** All collection inputs are snapshotted; publishing a score never retains mutable caller lists. */
@Immutable
data class TGSong(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val tracks: PersistentList<TGTrack> = persistentListOf(),
) {
    constructor(title: String = "", artist: String = "", album: String = "", tracks: List<TGTrack>) :
        this(title, artist, album, tracks.toPersistentList())
    init { require(tracks.map { it.id }.distinct().size == tracks.size) { "Track IDs must be unique" } }
}

/** Tuning is in STRING NUMBER order: index 0 = string 1 (high E), NOT low-to-high. */
@Immutable
data class TGTrack(
    val id: Int,
    val name: String,
    val stringTuning: PersistentList<Int>,
    val measures: PersistentList<TGMeasure>,
    val capo: Int = 0,
    val percussion: Boolean = false,
) {
    constructor(id: Int, name: String, stringTuning: List<Int>, measures: List<TGMeasure>, capo: Int = 0, percussion: Boolean = false) :
        this(id, name, stringTuning.toPersistentList(), measures.toPersistentList(), capo, percussion)
    init {
        require(stringTuning.size in 1..25) { "Expected 1–25 strings" }
        require(stringTuning.all { it in 0..127 } && capo in 0..36)
        require(measures.zipWithNext().all { (a, b) -> a.start < b.start && a.number < b.number }) { "Measures must be ordered" }
        require(measures.all { m -> m.beats.all { b -> b.voices.all { v -> v.notes.all { it.string <= stringTuning.size } } } }) { "Note string outside track" }
    }
    fun pitch(note: TGNote): Int = note.pitch ?: if (percussion) note.value.coerceIn(0,127) else (stringTuning[note.string - 1] + capo + note.value.coerceAtLeast(0)).coerceIn(0, 127)
}

@Immutable
data class TGMeasure(
    val number: Int,
    val header: TGMeasureHeader,
    val beats: PersistentList<TGBeat>,
    val start: Long = beats.firstOrNull()?.start ?: 0,
    val clef: TGClef = TGClef.Treble,
    val lineBreak: Boolean = false,
) {
    constructor(number: Int, header: TGMeasureHeader, beats: List<TGBeat>, start: Long = beats.firstOrNull()?.start ?: 0, clef: TGClef = TGClef.Treble, lineBreak: Boolean = false) :
        this(number, header, beats.toPersistentList(), start, clef, lineBreak)
    init {
        require(number > 0 && start >= 0)
        require(beats.zipWithNext().all { (a, b) -> a.start < b.start }) { "Beat starts must be unique and ascending" }
        require(beats.all { it.start >= start })
    }
    val end: Long get() = maxOf(start + header.timeSignature.ticks, beats.maxOfOrNull { it.start + it.maxTicks } ?: start)
}

@Immutable
data class TGMeasureHeader(
    val timeSignature: TGTimeSignature = TGTimeSignature(4, 4),
    val keySignature: TGKeySignature = TGKeySignature(0),
    val tempo: Int = 120,
    val repeatOpen: Boolean = false,
    /** Additional repetitions, exactly TuxGuitar: 1 means two total passes; 0 means no repeat close. */
    val repeatClose: Int = 0,
    /** Bit 0 = first ending, bit 1 = second ending, etc. */
    val repeatAlternative: Int = 0,
    val marker: String? = null,
    /** TuxGuitar header encoding: 0 straight, 1 swung eighths, 2 swung sixteenths. */
    val tripletFeel: Int = 0,
    /** Optional original tempo annotation; preserves raw/base/dotted changes at equal quarter BPM. */
    val tempoMark: TGTempoMark? = null,
) { init { require(tempo in 1..1000 && repeatClose in 0..99 && repeatAlternative >= 0 && tripletFeel in 0..2) } }

/** Display-only metadata, not a playback clock or scheduler. */
@Immutable
data class TGTempoMark(val value: Int, val base: Int = 4, val dotted: Boolean = false) {
    init { require(value in 1..1000 && base in listOf(1,2,4,8,16,32,64)) }
}

@Immutable
data class TGTimeSignature(val numerator: Int, val denominator: Int) {
    init { require(numerator in 1..64 && denominator in listOf(1, 2, 4, 8, 16, 32, 64)) }
    val ticks: Long get() = TGDuration.QUARTER_TIME * 4L * numerator / denominator
    /** Exact TGSongManager.getDivisionLength rule: dotted quarters only for N/8, N divisible by 3. */
    val divisionTicks: Long get() = if (denominator == 8 && numerator % 3 == 0) 1440L else 960L
}

/** TuxGuitar encoding: 0=C, 1..7=sharps, 8..14=flats. NOT signed fifths. */
@Immutable
data class TGKeySignature(val value: Int = 0) {
    init { require(value in 0..14) }
    val count: Int get() = if (value <= 7) value else value - 7
    val flats: Boolean get() = value > 7
}

/** Clef offsets from TGNotation.SCORE_CLEF_OFFSETS, in diatonic half-line steps. */
enum class TGClef(val positionOffset: Int) { Treble(30), Bass(18), Tenor(22), Alto(24), Percussion(30) }

@Immutable
data class TGBeat(
    val start: Long,
    val duration: TGDuration = TGDuration(),
    val voices: PersistentList<TGVoice> = persistentListOf(),
    val text: String? = null,
    val chord: TGChord? = null,
    /** Optional upstream precise start; preserves non-integral ticks for 7/11/13 tuplets. */
    val preciseStart: Long? = null,
    /** A strumming arrow across the strings (or score), distinct from the picking mark. */
    val stroke: TGStroke = TGStroke(),
    val pickStroke: TGStrokeDirection = TGStrokeDirection.None,
) {
    constructor(start: Long, duration: TGDuration = TGDuration(), voices: List<TGVoice>, text: String? = null, chord: TGChord? = null, preciseStart: Long? = null,
        stroke: TGStroke = TGStroke(), pickStroke: TGStrokeDirection = TGStrokeDirection.None) :
        this(start, duration, voices.toPersistentList(), text, chord, preciseStart, stroke, pickStroke)
    init {
        require(start >= 0 && (preciseStart == null || preciseStart >= 0))
        require(voices.map { it.index }.distinct().size == voices.size) { "Duplicate voice index" }
    }
    val maxTicks: Long get() = voices.filterNot { it.empty }.maxOfOrNull { (it.duration ?: duration).ticks } ?: duration.ticks
    val startQuarters: Double get() = preciseStart?.toDouble()?.div(TGDuration.PRECISE_QUARTER) ?: (start.toDouble() / TGDuration.QUARTER_TIME)
}

/**
 * Original TGStroke/TGPickStroke encoding. These are musical directions, not screen coordinates:
 * with high strings at the top, TuxGuitar draws Up toward increasing Y and Down toward decreasing Y.
 */
enum class TGStrokeDirection(val value: Int) { None(0), Up(1), Down(-1) }

/** Value preserves the original strum-speed denominator as metadata; core never schedules audio. */
@Immutable
data class TGStroke(val direction: TGStrokeDirection = TGStrokeDirection.None, val value: Int = 0) {
    init { require(value >= 0) }
}

/** Duration is per VOICE in TuxGuitar. Null inherits the beat convenience duration. */
@Immutable
data class TGVoice(
    val index: Int = 0,
    val notes: PersistentList<TGNote> = persistentListOf(),
    val duration: TGDuration? = null,
    val empty: Boolean = false,
    val stem: TGStemDirection = TGStemDirection.Auto,
) {
    constructor(index: Int = 0, notes: List<TGNote>, duration: TGDuration? = null, empty: Boolean = false, stem: TGStemDirection = TGStemDirection.Auto) :
        this(index, notes.toPersistentList(), duration, empty, stem)
    init { require(index in 0..3); require(!empty || notes.isEmpty()) }
}

enum class TGStemDirection { Auto, Up, Down }

@Immutable
data class TGDuration(
    val value: Int = 4,
    val dotted: Boolean = false,
    val doubleDotted: Boolean = false,
    val division: TGDivisionType = TGDivisionType(),
) {
    init { require(value in listOf(1, 2, 4, 8, 16, 32, 64)); require(!(dotted && doubleDotted)) }
    /** Same integer rounding order as TGDuration.getTime(). */
    val ticks: Long get() {
        val base = QUARTER_TIME * 4 / value
        val dot = if (dotted) base / 2 else if (doubleDotted) (base / 4) * 3 else 0
        return (base + dot) * division.times / division.enters
    }
    val preciseTicks: Long get() {
        val base = WHOLE_PRECISE_DURATION / value
        val withDots = if (dotted) base * 3 / 2 else if (doubleDotted) base * 7 / 4 else base
        return withDots * division.times / division.enters
    }
    val quarters: Double get() = preciseTicks.toDouble() / PRECISE_QUARTER
    val beamCount: Int get() = when (value) { 8 -> 1; 16 -> 2; 32 -> 3; 64 -> 4; else -> 0 }
    val index: Int get() { var n = value; var i = 0; while (n > 1) { n /= 2; i++ }; return i }
    companion object {
        const val QUARTER_TIME = 960L
        // lcm(64, 1,3,5,6,7,9,10,11,12,13) * 4, exactly TGDuration's precise unit.
        const val WHOLE_PRECISE_DURATION = 11_531_520L
        const val PRECISE_QUARTER = WHOLE_PRECISE_DURATION / 4
    }
}

@Immutable
data class TGDivisionType(val enters: Int = 1, val times: Int = 1) {
    init { require((enters to times) in supported) { "Unsupported TuxGuitar tuplet" } }
    companion object { val supported = listOf(1 to 1, 3 to 2, 5 to 4, 6 to 4, 7 to 4, 9 to 8, 10 to 8, 11 to 8, 12 to 8, 13 to 8) }
}

/** -1 is a dead/muted note, NOT a rest. An empty, non-empty-flag voice is a rest. */
@Immutable
data class TGNote(
    val value: Int,
    val string: Int,
    val velocity: Int = 95,
    val tied: Boolean = false,
    val effects: TGNoteEffects = TGNoteEffects(),
    val pitch: Int? = null,
    val altEnharmonic: Boolean = false,
) {
    init { require(value in -1..257 && string in 1..25 && velocity in 0..127 && (pitch == null || pitch in 0..127)) }
}

@Immutable
data class TGNoteEffects(
    val bend: TGBendEffect? = null,
    val harmonic: Int = 0,
    val hammer: Boolean = false,
    val slide: Boolean = false,
    val ghost: Boolean = false,
    val accented: Boolean = false,
    val heavyAccented: Boolean = false,
    val dead: Boolean = false,
    val vibrato: Boolean = false,
    val palmMute: Boolean = false,
    val letRing: Boolean = false,
    val staccato: Boolean = false,
    val tapping: Boolean = false,
    val slapping: Boolean = false,
    val popping: Boolean = false,
    val fadeIn: Boolean = false,
    val grace: TGGraceEffect? = null,
    val trill: TGTrillEffect? = null,
    val tremoloPicking: Int = 0,
    val tremoloBar: TGBendEffect? = null,
) {
    init { require(harmonic in 0..5 && tremoloPicking in listOf(0, 8, 16, 32, 64)) }
}

/** Bend points retain TuxGuitar units: position 0..12, raw amplitude (4 = one full-tone bend label). */
@Immutable
data class TGBendEffect(val points: PersistentList<Pair<Int, Int>>) {
    constructor(points: List<Pair<Int, Int>>) : this(points.toPersistentList())
    init {
        require(points.isNotEmpty())
        require(points.all { (t, v) -> t in 0..12 && v in -48..48 })
        require(points.zipWithNext().all { (a, b) -> a.first <= b.first })
    }
}

@Immutable
data class TGGraceEffect(val fret: Int, val duration: Int = 32, val onBeat: Boolean = false, val dead: Boolean = false) {
    init { require(fret in 0..127 && duration in listOf(16, 32, 64)) }
}
@Immutable
data class TGTrillEffect(val fret: Int, val duration: Int = 16) {
    init { require(fret in 0..127 && duration in listOf(8, 16, 32, 64)) }
}
@Immutable
data class TGChord(val name: String, val frets: PersistentList<Int>, val firstFret: Int = 1) {
    constructor(name: String, frets: List<Int>, firstFret: Int = 1) : this(name, frets.toPersistentList(), firstFret)
    init { require(frets.size in 1..25 && frets.all { it in -1..257 } && firstFret > 0) }
}
