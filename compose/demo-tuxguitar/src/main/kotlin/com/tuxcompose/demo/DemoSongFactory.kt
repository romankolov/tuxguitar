// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.io.base.TGSongWriterHandle
import app.tuxguitar.io.gtp.*
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.models.TGDuration
import app.tuxguitar.song.models.TGSong
import app.tuxguitar.song.models.TGStroke
import app.tuxguitar.song.models.TGPickStroke
import java.io.ByteArrayOutputStream

/** Original composition, generated from native TuxGuitar models; no copyrighted tab fixtures. */
object DemoSongFactory {
    fun create(): TGSong {
        val f = TGFactory()
        val song = f.newSong().apply { name = "Greenwood Study"; artist = "Tux Compose · original demo"; album = "Canvas Sessions" }
        val tunings = listOf(listOf(64,59,55,50,45,40),listOf(43,38,33,28))
        val tracks = tunings.mapIndexed { i, tuning ->
            val channel = f.newChannel().apply { channelId = i; program = (if (i == 0) 25 else 33).toShort(); name = if (i == 0) "Acoustic guitar" else "Fingered bass" }
            song.addChannel(channel)
            f.newTrack().apply {
                number = i+1; name = channel.name; channelId = channel.channelId
                tuning.forEachIndexed { si, value -> strings.add(f.newString().apply { number = si+1; this.value = value }) }
                song.addTrack(this)
            }
        }
        var start = 960L
        val frets = listOf(listOf(0,2,3,2,0,2,3,5),listOf(3,2,0,2,3,5,3,2),listOf(0,2,4,5,4,2,0,2),listOf(5,3,2,0,2,3,2,0))
        repeat(12) { mi ->
            val compound = mi >= 8
            val header = f.newHeader().apply {
                number = mi+1; this.start = start
                timeSignature.numerator = if (compound) 6 else 4
                timeSignature.denominator.value = if (compound) 8 else 4
                tempo.quarterValue = if (mi < 8) 108 else 96
                isRepeatOpen = mi == 0
                repeatClose = if (mi == 3) 1 else 0
                if (mi == 0 || mi == 4 || mi == 8) marker = f.newMarker().apply { title = listOf("A · Arpeggio", "B · Articulations", "C · Six-eight")[mi/4] }
            }
            song.addMeasureHeader(header)
            tracks.forEachIndexed { ti, track ->
                val measure = f.newMeasure(header).apply { clef = if (ti == 0) 1 else 2; keySignature = if (mi < 4) 0 else 1 }
                track.addMeasure(measure)
                val beatCount = if (compound) 6 else 8
                repeat(beatCount) { bi ->
                    val beat = f.newBeat().apply { preciseStart = TGDuration.toPreciseTime(start + bi*480) }
                    val voice = beat.getVoice(0).apply { duration.value = 8; isEmpty = false }
                    if (!(mi == 11 && bi >= 4)) {
                        val note = f.newNote().apply {
                            string = if (ti == 0) listOf(3,2,1,2,3,2,1,2)[bi] else if (bi % 4 == 0) 4 else 3
                            value = if (ti == 0) frets[mi%4][bi] else listOf(0,0,2,2,3,3,2,0)[bi]
                            velocity = if (bi % 2 == 0) 95 else 80
                            if (ti == 0 && mi in 4..7) {
                                effect.isHammer = bi == 0
                                effect.isSlide = bi == 4
                                effect.isVibrato = bi == 6
                                effect.isPalmMute = mi == 5 && bi < 4
                                effect.isGhostNote = mi == 6 && bi == 3
                                effect.isAccentuatedNote = bi == 0
                                if (mi == 7 && bi == 2) effect.bend = f.newEffectBend().apply { addPoint(0,0); addPoint(6,4); addPoint(12,0) }
                                if (mi == 6 && bi == 6) effect.harmonic = f.newEffectHarmonic().apply { type = 1 }
                            }
                        }
                        voice.addNote(note)
                    }
                    // Real native GP stroke/pick data, not an overlay added after decoding.
                    if (ti == 0 && mi in listOf(0,4) && bi in listOf(0,4)) {
                        beat.stroke.direction = if (bi == 0) TGStroke.STROKE_DOWN else TGStroke.STROKE_UP
                        beat.stroke.value = 16
                        beat.pickStroke.direction = if (bi == 0) TGPickStroke.PICK_STROKE_DOWN else TGPickStroke.PICK_STROKE_UP
                        listOf(0,0,0,2,2,0).forEachIndexed { si,fret ->
                            if (voice.notes.none { it.string == si+1 }) voice.addNote(f.newNote().apply {
                                string = si+1; value = fret; velocity = 85
                            })
                        }
                    }
                    if (bi == 0 && ti == 0 && mi % 4 == 0) {
                        beat.chord = f.newChord(6).apply {
                            name = if (mi == 0) "Em" else "G"; firstFret = 1
                            listOf(0,0,0,2,2,0).forEachIndexed { si, fret -> addFretValue(si,fret) }
                        }
                    }
                    measure.addBeat(beat)
                }
            }
            start += header.length
        }
        return song
    }
    fun guitarPro(version: Int = 5): ByteArray {
        val writer = when (version) { 3 -> GP3OutputStream(GTPSettings()); 4 -> GP4OutputStream(GTPSettings()); else -> GP5OutputStream(GTPSettings()) }
        val out = ByteArrayOutputStream()
        writer.write(TGSongWriterHandle().apply { factory = TGFactory(); song = create(); outputStream = out })
        return out.toByteArray()
    }
}
