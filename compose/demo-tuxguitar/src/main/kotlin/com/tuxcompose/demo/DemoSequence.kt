// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.player.base.MidiRepeatController
import app.tuxguitar.player.base.MidiSequenceHandler
import app.tuxguitar.player.base.MidiSequenceParser
import app.tuxguitar.song.managers.TGSongManager
import app.tuxguitar.song.models.TGSong
import app.tuxguitar.song.models.TGTimeSignature

/** Events originate in the unmodified TuxGuitar parser: repeats, bends, ties, articulations, tempo. */
data class DemoMidiEvent(val tick: Long, val kind: Kind, val channel: Int, val a: Int, val b: Int = 0, val voice: Int = 0, val bend: Boolean = false, val micros: Long = 0) {
    enum class Kind { Tempo, NoteOff, Control, Program, Pitch, NoteOn }
}
data class DemoMeasureSpan(val performanceStart: Long, val scoreStart: Long, val length: Long)
data class DemoTempo(val tick: Long, val micros: Long, val usq: Int)

class DemoSequence(song: TGSong, speedPercent: Int = 100, metronome: Boolean = false) {
    val events: List<DemoMidiEvent>
    val spans: List<DemoMeasureSpan>
    val tempos: List<DemoTempo>
    val endMicros: Long
    init {
        require(speedPercent in 25..200)
        val collected = mutableListOf<DemoMidiEvent>()
        val parser = MidiSequenceParser(song,TGSongManager(),MidiSequenceParser.ADD_DEFAULT_CONTROLS or MidiSequenceParser.ADD_MIXER_MESSAGES or MidiSequenceParser.ADD_BANK_SELECT or if (metronome) MidiSequenceParser.ADD_METRONOME else 0)
        parser.setTempoPercent(speedPercent)
        val handler = object : MidiSequenceHandler(song.countTracks()+2) {
            override fun addNoteOn(tick: Long, track: Int, channel: Int, note: Int, velocity: Int, voice: Int, bendMode: Boolean) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.NoteOn,channel,note,velocity,voice,bendMode) }
            override fun addNoteOff(tick: Long, track: Int, channel: Int, note: Int, velocity: Int, voice: Int, bendMode: Boolean) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.NoteOff,channel,note,velocity,voice,bendMode) }
            override fun addPitchBend(tick: Long, track: Int, channel: Int, value: Int, voice: Int, bendMode: Boolean) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.Pitch,channel,value,voice=voice,bend=bendMode) }
            override fun addControlChange(tick: Long, track: Int, channel: Int, controller: Int, value: Int) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.Control,channel,controller,value) }
            override fun addProgramChange(tick: Long, track: Int, channel: Int, instrument: Int) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.Program,channel,instrument) }
            override fun addTempoInUSQ(tick: Long, track: Int, usq: Int) { collected += DemoMidiEvent(tick,DemoMidiEvent.Kind.Tempo,-1,usq) }
            override fun addTrackName(tick: Long, track: Int, name: String) = Unit
            override fun addTimeSignature(tick: Long, track: Int, ts: TGTimeSignature) = Unit
            override fun notifyFinish() = Unit
        }
        parser.parse(handler)
        val controller = MidiRepeatController(song,-1,-1)
        spans = buildList {
            var safety = 0
            while (!controller.finished()) {
                require(++safety < 100_000) { "Repeat expansion exceeds demo limit" }
                val i = controller.index; val move = controller.repeatMove
                controller.process()
                if (controller.shouldPlay()) song.getMeasureHeader(i).let { add(DemoMeasureSpan(it.start+move,it.start,it.length)) }
            }
        }
        val tempoEvents = collected.filter { it.kind == DemoMidiEvent.Kind.Tempo }.sortedBy { it.tick }
        var previousTick = 960L; var usq = 500_000; var micros = 0L
        tempos = buildList {
            add(DemoTempo(previousTick,0,usq))
            tempoEvents.forEach { e ->
                micros += (e.tick-previousTick).coerceAtLeast(0) * usq / 960
                add(DemoTempo(e.tick,micros,e.a)); previousTick=e.tick; usq=e.a
            }
        }
        events = collected.sortedWith(compareBy<DemoMidiEvent> { it.tick }.thenBy { it.kind.ordinal }).map { it.copy(micros=microsAt(it.tick)) }
        endMicros = maxOf(events.lastOrNull()?.micros ?: 0,spans.lastOrNull()?.let { microsAt(it.performanceStart+it.length) } ?: 0)
    }
    fun microsAt(tick: Long): Long {
        val t = tempos.lastOrNull { it.tick <= tick } ?: tempos.first()
        return t.micros + (tick-t.tick).coerceAtLeast(0) * t.usq / 960
    }
    fun performanceTickAt(micros: Long): Long {
        val t = tempos.lastOrNull { it.micros <= micros } ?: tempos.first()
        return t.tick + (micros-t.micros).coerceAtLeast(0) * 960 / t.usq
    }
    fun scoreTickAt(micros: Long): Long {
        val tick = performanceTickAt(micros)
        val span = spans.lastOrNull { it.performanceStart <= tick } ?: return 960L
        return span.scoreStart + (tick-span.performanceStart).coerceIn(0,span.length-1)
    }
    fun seekMicros(scoreTick: Long): Long {
        val span = spans.firstOrNull { scoreTick in it.scoreStart until (it.scoreStart+it.length) } ?: return 0
        return microsAt(span.performanceStart+scoreTick-span.scoreStart)
    }
}
