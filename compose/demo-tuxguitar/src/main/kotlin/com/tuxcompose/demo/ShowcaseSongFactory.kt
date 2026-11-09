// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.io.base.TGSongWriterHandle
import app.tuxguitar.io.gtp.GP5OutputStream
import app.tuxguitar.io.gtp.GTPSettings
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.models.*
import java.io.ByteArrayOutputStream

/** Original, deterministic music; never a downloaded/copyrighted tab or a core dependency.
 *
 * TG is the default LOSSLESS showcase file: GP5 cannot retain double dots, arbitrary
 * clef changes and all native metadata. GP5 export/import remains a real companion.
 * No renderer-only notes/effects are injected after decoding.
 */
object ShowcaseSongFactory {
    const val TITLE = "Technique Atlas"
    const val MEASURES = 32
    val sections = listOf("Open strings & chords", "Touch & articulation", "Bends & ornaments",
        "Rhythm laboratory", "Two voices & ties", "Keys & clefs", "Meter & repeats", "Finale & reflow")

    private data class Rhythm(val value: Int, val dotted: Boolean=false, val doubleDotted: Boolean=false,
                              val enters: Int=1, val times: Int=1)
    private fun rhythm(bar: Int): List<Rhythm> = when (bar) {
        12 -> List(12) { Rhythm(8,enters=3,times=2) }
        13 -> List(20) { Rhythm(16,enters=5,times=4) }
        14 -> listOf(Rhythm(4,dotted=true),Rhythm(8),Rhythm(4,doubleDotted=true),Rhythm(16))
        15 -> listOf(2,4,8,16,32,64,64).map { Rhythm(it) }
        19 -> List(4) { Rhythm(4) }
        23 -> listOf(Rhythm(1))
        24,25 -> List(6) { Rhythm(8) }
        26 -> List(10) { Rhythm(8) }
        29 -> List(16) { Rhythm(16) }
        30 -> List(64) { Rhythm(64) }
        31 -> List(2) { Rhythm(2) }
        else -> List(8) { Rhythm(8) }
    }

    fun create(): TGSong {
        val f=TGFactory()
        val song=f.newSong().apply { name=TITLE; artist="Tux Compose · original showcase"; album="Canvas Sessions" }
        val tunings=listOf(listOf(64,59,55,50,45,40),listOf(43,38,33,28),List(6) { 0 })
        val tracks=tunings.mapIndexed { ti,tuning ->
            val channel=f.newChannel().apply {
                channelId=ti
                name=listOf("Acoustic guitar","Fingered bass","Studio drums")[ti]
                program=listOf(25,33,0)[ti].toShort()
                if(ti==2) bank=TGChannel.DEFAULT_PERCUSSION_BANK
                volume=listOf(100,85,72)[ti].toShort()
                balance=listOf(58,70,64)[ti].toShort()
            }
            song.addChannel(channel)
            f.newTrack().apply {
                number=ti+1; name=channel.name; channelId=channel.channelId
                tuning.forEachIndexed { i,pitch -> strings.add(f.newString().apply { number=i+1; value=pitch }) }
                song.addTrack(this)
            }
        }
        val motif=listOf(0,2,3,5,7,10,12,14)
        var start=960L
        repeat(MEASURES) { mi ->
            val header=f.newHeader().apply {
                number=mi+1; this.start=start
                timeSignature.numerator=when(mi) {24->6;25->3;26->5;else->4}
                timeSignature.denominator.value=if(mi==24) 8 else 4
                tempo.quarterValue=when {mi<8->108;mi<16->96;mi<24->112;else->100}
                isLineBreak=mi%4==0
                isRepeatOpen=mi==0 || mi==24
                repeatClose=if(mi==3 || mi==26) 1 else 0
                repeatAlternative=when(mi) {26->1;27->2;else->0}
                tripletFeel=when(mi) {28->TGMeasureHeader.TRIPLET_FEEL_EIGHTH;29->TGMeasureHeader.TRIPLET_FEEL_SIXTEENTH;else->TGMeasureHeader.TRIPLET_FEEL_NONE}
                if(mi%4==0) marker=f.newMarker().apply { title="${mi/4+1}. ${sections[mi/4]}" }
            }
            song.addMeasureHeader(header)
            tracks.forEachIndexed { ti,track ->
                val measure=f.newMeasure(header).apply {
                    clef=when {ti==1->2;mi==22->3;mi==23->4;else->1}
                    keySignature=when(mi) {20->9;21,22,23->2;else->0}
                }
                track.addMeasure(measure)
                val pattern=if(ti==0) rhythm(mi) else List((header.length/480).toInt()) { Rhythm(8) }
                var tick=start
                pattern.forEachIndexed { bi,r ->
                    val beat=f.newBeat().apply { preciseStart=TGDuration.toPreciseTime(tick) }
                    val voice=beat.getVoice(0).apply {
                        isEmpty=false
                        duration.value=r.value; duration.isDotted=r.dotted; duration.isDoubleDotted=r.doubleDotted
                        duration.division.enters=r.enters; duration.division.times=r.times
                        if(ti==0 && mi in 16..19) direction=1
                    }
                    val silent=ti==0 && (mi==15 || mi==23 || mi==19 && bi%2==0 || mi==31 && bi==1)
                    if(!silent) {
                        val note=f.newNote().apply {
                            string=when(ti) {0->if(mi in 4..11 || mi in 16..18) 1 else listOf(3,2,1,2,3,2,1,2)[bi%8];1->if(bi%4==0) 4 else 3;else->if(bi%2==0) 1 else 3}
                            value=when(ti) {0->if(mi==17 && bi<2) 7 else if(mi==21) listOf(0,1,2,3,12,13,23,24)[bi%8] else motif[(bi+mi)%8];1->listOf(0,0,2,2,3,3,2,0)[bi%8];else->if(bi%2==0) 42 else 38}
                            velocity=if(bi%2==0) 94 else 76
                            if(ti==0) {
                                if(mi in 0..1 || mi==30) effect.isLetRing=true
                                when(mi) {
                                    4 -> { effect.isHammer=bi in listOf(0,2); effect.isSlide=bi==4; effect.isVibrato=bi==6 }
                                    5 -> { effect.isPalmMute=bi<4; effect.isStaccato=bi==4; effect.isAccentuatedNote=bi==0; effect.isHeavyAccentuatedNote=bi==6; effect.isGhostNote=bi==3; effect.isDeadNote=bi==7 }
                                    6 -> if(bi%2==0 || bi==7) effect.harmonic=f.newEffectHarmonic().apply { type=if(bi==7) 3 else listOf(1,2,4,5)[bi/2] }
                                    7 -> { effect.isTapping=bi==0;effect.isSlapping=bi==2;effect.isPopping=bi==4;effect.isFadeIn=bi==6 }
                                    8 -> if(bi in listOf(0,4)) effect.bend=f.newEffectBend().apply {
                                        addPoint(0,if(bi==4) 4 else 0);addPoint(6,4);addPoint(12,0)
                                    }
                                    9 -> if(bi in listOf(2,5)) effect.tremoloBar=f.newEffectTremoloBar().apply {addPoint(0,0);addPoint(6,-4);addPoint(12,0)}
                                    10 -> when(bi) {
                                        0,4 -> effect.grace=f.newEffectGrace().apply {fret=2;duration=if(bi==0) 2 else 3;isOnBeat=bi==0;transition=3}
                                        2 -> effect.trill=f.newEffectTrill().apply {fret=9;duration.value=16}
                                        6 -> effect.tremoloPicking=f.newEffectTremoloPicking().apply {duration.value=32}
                                    }
                                    11 -> {effect.isAccentuatedNote=bi%2==0;effect.isPalmMute=bi in 2..4;effect.isVibrato=bi==6}
                                    17 -> isTiedNote=bi==1
                                    21 -> if(bi==2) toggleAltEnharmonic()
                                }
                            }
                        }
                        voice.addNote(note)
                        if(ti==0 && mi in 0..3 && bi in listOf(0,4)) {
                            // Real native strum and pick data, both directions.
                            beat.stroke.direction=if(bi==0) TGStroke.STROKE_DOWN else TGStroke.STROKE_UP
                            beat.stroke.value=16
                            beat.pickStroke.direction=if(bi==0) TGPickStroke.PICK_STROKE_DOWN else TGPickStroke.PICK_STROKE_UP
                            voice.notes.forEach { it.value=listOf(0,0,0,2,2,0)[it.string-1] }
                            listOf(0,0,0,2,2,0).forEachIndexed { si,fret ->
                                if(voice.notes.none {it.string==si+1}) voice.addNote(f.newNote().apply {
                                    string=si+1;value=fret;velocity=85;if(mi<2) effect.isLetRing=true
                                })
                            }
                            beat.chord=f.newChord(6).apply {name="Em";firstFret=1;listOf(0,0,0,2,2,0).forEachIndexed {si,fret->addFretValue(si,fret)}}
                        }
                        // Standard diatonic seconds with three close accidentals:
                        // independent score-head displacement, shared TAB onset, and
                        // height-aware accidental columns are visible in bar 19.
                        if(ti==0 && mi==18 && bi%2==0) {
                            voice.notes.toList().forEach {voice.removeNote(it)}
                            listOf(2,4,6).forEachIndexed {si,fret->voice.addNote(f.newNote().apply {
                                string=si+1;value=fret;velocity=88
                            })}
                        }
                        if(ti==2 && bi%2==0) voice.addNote(f.newNote().apply {string=6;value=36;velocity=90})
                    }
                    if(ti==0 && (mi==16 && bi==0 || mi==18 && bi%2==0 || mi==19 && bi%2==0)) {
                        beat.getVoice(1).apply {
                            isEmpty=false;direction=2;duration.value=if(mi==16) 1 else if(mi==19) 2 else 4
                            addNote(f.newNote().apply {string=5;value=3;velocity=76})
                        }
                    }
                    if(ti==0) {
                        val prose=when {
                            mi==1 && bi==2 -> "Let every string keep ringing"
                            mi==11 && bi==0 -> "Soft attack, clear accents"
                            mi==11 && bi==1 -> "Then let the phrase breathe"
                            mi==14 && bi==2 -> "Double-dotted quarter"
                            mi==16 && bi==0 -> "Held bass, moving melody"
                            mi==18 && bi==0 -> "Seconds & accidental columns"
                            mi==30 && bi==24 -> "A dense bar reflows at full size"
                            else -> null
                        }
                        if(prose!=null) beat.text=f.newText().apply {value=prose}
                    }
                    measure.addBeat(beat)
                    tick+=voice.duration.time
                }
                check(tick==start+header.length) {"Showcase bar ${mi+1}, track $ti does not fill its meter: $tick"}
            }
            start+=header.length
        }
        return song
    }

    fun lossless(): ByteArray = write(false)
    fun guitarPro(): ByteArray = write(true)
    private fun write(gp: Boolean): ByteArray {
        val output=ByteArrayOutputStream()
        val handle=TGSongWriterHandle().apply {factory=TGFactory();song=create();outputStream=output}
        if(gp) GP5OutputStream(GTPSettings()).write(handle) else PortableTgWriter().write(handle)
        return output.toByteArray()
    }
}
