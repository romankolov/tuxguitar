// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.io.base.TGSongReader
import app.tuxguitar.io.base.TGSongReaderHandle
import app.tuxguitar.io.base.TGSongStreamContext
import app.tuxguitar.io.gtp.*
import app.tuxguitar.io.midi.MidiSongReader
import app.tuxguitar.io.tg.TGSongReaderImpl
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.managers.TGSongManager
import app.tuxguitar.util.TGContext
import com.tuxcompose.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import app.tuxguitar.song.models.TGSong as NativeSong
import app.tuxguitar.song.models.TGDuration as NativeDuration
import app.tuxguitar.song.models.TGNote as NativeNote

/** This optional DEMO module is the ONLY place where decoded native objects cross into core. */
data class ImportedScore(val native: NativeSong, val score: TGSong, val format: String)

object TuxGuitarImporter {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    fun read(input: InputStream, fileName: String): ImportedScore {
        val bytes = input.readBounded(MAX_FILE_BYTES)
        require(bytes.size >= 4) { "The selected file is empty or truncated" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val prefix = bytes.take(40).toByteArray().toString(Charsets.ISO_8859_1)
        val reader: TGSongReader = when {
            prefix.contains("GUITAR PRO v3") -> GP3InputStream(GTPSettings())
            prefix.contains("GUITAR PRO v4") -> GP4InputStream(GTPSettings())
            prefix.contains("GUITAR PRO v5") -> GP5InputStream(GTPSettings())
            prefix.startsWith("BCFS") || prefix.startsWith("BCFZ") -> {
                if (prefix.startsWith("BCFZ")) {
                    require(bytes.size > 8)
                    val expanded = (0..3).fold(0L) { n, i -> n or ((bytes[i + 4].toLong() and 255) shl (8 * i)) }
                    require(expanded in 1..MAX_FILE_BYTES.toLong()) { "GPX expanded size exceeds demo limit" }
                }
                app.tuxguitar.io.gpx.v6.GPXInputStream()
            }
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() -> {
                preflightZip(bytes)
                if (extension == "tg") TGSongReaderImpl() else app.tuxguitar.io.gpx.v7.GPXInputStream()
            }
            prefix.startsWith("MThd") -> MidiSongReader(TGContext())
            extension == "tg" -> TGSongReaderImpl()
            else -> throw IllegalArgumentException("Supported formats: GP3, GP4, GP5, GPX, GP7/GP and TG, MIDI")
        }
        val handle = TGSongReaderHandle().apply {
            factory = TGFactory(); inputStream = ByteArrayInputStream(bytes); context = TGSongStreamContext()
        }
        reader.read(handle)
        val native = requireNotNull(handle.song) { "Decoder returned no song" }
        require(native.countTracks() in 1..64 && native.countMeasureHeaders() in 1..10000) { "Score is empty or too large for this demo" }
        val manager = TGSongManager(handle.factory)
        // Legacy GP readers may leave getPreciseStart() null. Match the upstream import
        // order: establish precise starts BEFORE the rest completer reads/unboxes them.
        manager.updatePreciseStart(native)
        manager.autoCompleteSilences(native)
        return ImportedScore(native, adapt(native), reader.fileFormat.name)
    }

    fun adapt(native: NativeSong): TGSong = TGSong(native.name ?: "Untitled", native.artist ?: "", native.album ?: "",
        (0 until native.countTracks()).map { ti ->
            val track = native.getTrack(ti)
            TGTrack(track.number, track.name ?: "Track ${ti + 1}",
                (1..track.stringCount()).map { track.getString(it).value },
                (0 until track.countMeasures()).map { mi ->
                    val m = track.getMeasure(mi)
                    TGMeasure(m.number, TGMeasureHeader(
                        TGTimeSignature(m.timeSignature.numerator,m.timeSignature.denominator.value), TGKeySignature(m.keySignature),
                        m.tempo.quarterValue, m.isRepeatOpen, m.repeatClose.coerceAtLeast(0), m.header.repeatAlternative,
                        m.header.marker?.title),
                        m.beats.sortedBy { it.start }.map { b ->
                            val voices = (0 until b.countVoices()).map { vi ->
                                val v = b.getVoice(vi)
                                TGVoice(vi, v.notes.map(::note), duration(v.duration), v.isEmpty,
                                    when (v.direction) { 1 -> TGStemDirection.Up; 2 -> TGStemDirection.Down; else -> TGStemDirection.Auto })
                            }
                            val duration = voices.filterNot { it.empty }.minByOrNull { it.duration?.preciseTicks ?: Long.MAX_VALUE }?.duration ?: TGDuration()
                            TGBeat(b.start,duration,voices,b.text?.value,b.chord?.let { TGChord(it.name ?: "",it.strings.toList(),it.firstFret.coerceAtLeast(1)) },b.preciseStart,
                                stroke=TGStroke(strokeDirection(b.stroke.direction),b.stroke.value),
                                pickStroke=strokeDirection(b.pickStroke.direction))
                        }, m.start, if (track.isPercussion) TGClef.Percussion else when (m.clef) { 2 -> TGClef.Bass; 3 -> TGClef.Tenor; 4 -> TGClef.Alto; else -> TGClef.Treble }, m.header.isLineBreak)
                }, percussion = track.isPercussion)
        })

    private fun strokeDirection(value: Int) = TGStrokeDirection.entries.firstOrNull { it.value == value } ?: TGStrokeDirection.None
    private fun duration(d: NativeDuration) = TGDuration(d.value,d.isDotted,d.isDoubleDotted,TGDivisionType(d.division.enters,d.division.times))
    private fun note(n: NativeNote): TGNote {
        val e = n.effect
        return TGNote(n.value,n.string,n.velocity,n.isTiedNote,TGNoteEffects(
            bend = e.bend?.points?.takeIf { it.isNotEmpty() }?.let { points -> TGBendEffect(points.map { it.position to it.value }) },
            harmonic = e.harmonic?.type ?: 0, hammer = e.isHammer, slide = e.isSlide, ghost = e.isGhostNote,
            accented = e.isAccentuatedNote, heavyAccented = e.isHeavyAccentuatedNote, dead = e.isDeadNote,
            vibrato = e.isVibrato, palmMute = e.isPalmMute, letRing = e.isLetRing, staccato = e.isStaccato,
            tapping = e.isTapping, slapping = e.isSlapping, popping = e.isPopping, fadeIn = e.isFadeIn,
            grace = e.grace?.let { TGGraceEffect(it.fret,when (it.duration) { 1 -> 64; 3 -> 16; else -> 32 },it.isOnBeat,it.isDead) },
            trill = e.trill?.let { TGTrillEffect(it.fret,it.duration.value) },
            tremoloPicking = e.tremoloPicking?.duration?.value ?: 0,
            tremoloBar = e.tremoloBar?.points?.takeIf { it.isNotEmpty() }?.let { points -> TGBendEffect(points.map { it.position to it.value }) },
        ), altEnharmonic = n.isAltEnharmonic)
    }
    private fun preflightZip(bytes: ByteArray) {
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (zip.nextEntry != null) {
                require(++count < 4096) { "Too many archive entries" }
                val data = zip.readBounded(MAX_FILE_BYTES - total)
                total += data.size
                require(total <= MAX_FILE_BYTES) { "Archive expands beyond demo limit" }
                if (data.take(2048).toByteArray().toString(Charsets.UTF_8).contains("<!DOCTYPE",ignoreCase=true))
                    throw IllegalArgumentException("Document type declarations are not permitted")
            }
        }
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size().toLong() + n <= limit) { "File exceeds the demo's 32 MiB input/expansion limit" }
        output.write(buffer,0,n)
    }
    return output.toByteArray()
}
