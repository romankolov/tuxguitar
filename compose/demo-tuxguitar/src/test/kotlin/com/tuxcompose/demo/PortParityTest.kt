// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.graphics.control.TGLayout
import app.tuxguitar.graphics.control.TGTrackSpacing
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.ui.resource.UIPainter
import app.tuxguitar.ui.resource.UIRectangle
import app.tuxguitar.util.TGMusicKeyUtils
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import java.io.ByteArrayInputStream
import kotlin.test.*

/** Differential tests run the ORIGINAL Java methods alongside the common Kotlin port. */
class PortParityTest {
    private val factory=TGFactory()
    @Test fun durationAndSpacingParityForEverySupportedDuration() {
        val original=object : TGLayout(null,TGLayout.DISPLAY_SCORE or TGLayout.DISPLAY_TABLATURE) {
            override fun getMode()=TGLayout.MODE_HORIZONTAL
            override fun paintSong(painter: UIPainter?, area: UIRectangle?, x: Float, y: Float) = Unit
        }
        val f=TGLayout::class.java.getDeclaredField("durationWidths").apply { isAccessible=true }
        f.set(original,floatArrayOf(30f,25f,21f,20f,19f,18f))
        TGDivisionType.supported.forEach { (enters,times) ->
            listOf(1,2,4,8,16,32,64).forEach { value -> repeat(3) { dots ->
                val java=factory.newDuration().apply { this.value=value; isDotted=dots==1; isDoubleDotted=dots==2; division.enters=enters; division.times=times }
                val kotlin=TGDuration(value,dots==1,dots==2,TGDivisionType(enters,times))
                assertEquals(java.time,kotlin.ticks)
                assertEquals(java.preciseTime,kotlin.preciseTicks)
                assertEquals(original.getDurationWidth(java),TGLayoutCalculator().durationWidth(kotlin))
                assertEquals(original.getSpacingForQuarter(java),TGLayoutCalculator().spacingForQuarter(kotlin),.0001f)
            } }
        }
    }
    @Test fun keySpellingParityAcrossAllPitchesKeysAndEnharmonics() {
        for (pitch in 12..127) for (key in 0..14) for (alt in listOf(false,true)) {
            assertEquals(TGMusicKeyUtils.noteIndex(pitch,key,alt),TGNotation.noteIndex(pitch,key,alt),"pitch=$pitch key=$key alt=$alt")
            assertEquals(TGMusicKeyUtils.noteOctave(pitch,key,alt),TGNotation.octave(pitch,key,alt))
            assertEquals(TGMusicKeyUtils.noteAlteration(pitch,key),when (TGNotation.alteration(pitch,key)) { -1 -> 3; 1 -> 2; else -> 1 })
        }
    }
    @Test fun lanePermutationParity() {
        TGLayoutStyles.entries.forEach { style ->
            val flags=when (style) { TGLayoutStyles.Score -> TGLayout.DISPLAY_SCORE; TGLayoutStyles.Tablature -> TGLayout.DISPLAY_TABLATURE; TGLayoutStyles.Mixed -> TGLayout.DISPLAY_SCORE or TGLayout.DISPLAY_TABLATURE }
            val layout=object : TGLayout(null,flags) {
                override fun getMode()=TGLayout.MODE_HORIZONTAL
                override fun paintSong(painter: UIPainter?, area: UIRectangle?, x: Float, y: Float)=Unit
            }
            val java=TGTrackSpacing(layout); val kotlin=TGSpacing(style)
            TGTrackLane.entries.forEachIndexed { i,lane -> java.setSize(i,i+1f); kotlin.register(lane,i+1f) }
            TGTrackLane.entries.forEachIndexed { i,lane -> assertEquals(java.getPosition(i),kotlin.position(lane)) }
            assertEquals(java.size,kotlin.height)
        }
    }
    @Test fun gp3RoundTrip() = roundTrip(3)
    @Test fun gp4RoundTrip() = roundTrip(4)
    @Test fun gp5RoundTrip() = roundTrip(5)
    private fun roundTrip(version: Int) {
        val bytes=DemoSongFactory.guitarPro(version)
        val imported=TuxGuitarImporter.read(ByteArrayInputStream(bytes),"study.gp$version")
        assertTrue(imported.score.tracks.flatMap { it.measures }.flatMap { it.beats }.all { it.preciseStart != null })
        assertEquals("Greenwood Study",imported.score.title)
        assertEquals(2,imported.score.tracks.size)
        assertEquals(12,imported.score.tracks[0].measures.size)
        assertEquals(listOf(64,59,55,50,45,40),imported.score.tracks[0].stringTuning)
        assertTrue(TGLayoutCalculator(TGLayoutConfig(multiTrack=true)).calculate(imported.score,400f).height>0)
    }
    @Test fun originalMidiParserProducesRepeatsBendsTempoAndNotes() {
        val native=DemoSongFactory.create()
        val sequence=DemoSequence(native)
        assertEquals(16,sequence.spans.size) // 12 measures + first four repeated once
        assertTrue(sequence.events.count { it.kind==DemoMidiEvent.Kind.NoteOn }>100)
        assertTrue(sequence.events.any { it.kind==DemoMidiEvent.Kind.Pitch && it.a!=64 })
        assertTrue(sequence.tempos.map { it.usq }.distinct().size>=2)
        assertTrue(sequence.endMicros>0)
        sequence.spans.forEach { span -> assertTrue(kotlin.math.abs(span.scoreStart-sequence.scoreTickAt(sequence.microsAt(span.performanceStart)))<=1) }
    }
    @Test fun invalidAndOversizedInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { TuxGuitarImporter.read(ByteArrayInputStream(byteArrayOf()),"empty.gp5") }
        assertFailsWith<IllegalArgumentException> { TuxGuitarImporter.read(ByteArrayInputStream("not a score".toByteArray()),"bad.txt") }
        assertFailsWith<IllegalArgumentException> { ByteArrayInputStream(ByteArray(128)).readBounded(127) }
    }
}
