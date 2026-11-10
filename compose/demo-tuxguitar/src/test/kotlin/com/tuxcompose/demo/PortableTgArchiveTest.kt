// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.io.base.TGFileFormatException
import app.tuxguitar.io.base.TGSongReaderHandle
import app.tuxguitar.io.base.TGSongWriterHandle
import app.tuxguitar.io.tg.TGSongReaderImpl
import app.tuxguitar.io.tg.TGSongWriterImpl
import app.tuxguitar.song.factory.TGFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PortableTgArchiveTest {
    private fun handle(bytes: ByteArray)=TGSongReaderHandle().apply {
        factory=TGFactory(); inputStream=ByteArrayInputStream(bytes)
    }
    private fun entries(): List<Pair<String,ByteArray>> {
        val result=mutableListOf<Pair<String,ByteArray>>()
        ZipInputStream(ByteArrayInputStream(ShowcaseSongFactory.lossless())).use { zip ->
            while (true) {
                val entry=zip.nextEntry ?: break
                result+=entry.name to zip.readBytes()
            }
        }
        return result
    }
    private fun archive(entries: List<Pair<String,ByteArray>>): ByteArray {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name,data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
    private fun versioned(version: String)=archive(entries().map { (name,data) ->
        name to if(name=="version.txt") version.toByteArray() else data
    })
    @Test fun portableWriterIsReadableByTheUnmodifiedUpstreamReader() {
        val h=handle(ShowcaseSongFactory.lossless())
        TGSongReaderImpl().read(h)
        assertEquals(ShowcaseSongFactory.TITLE,h.song.name)
        assertEquals(3,h.song.countTracks())
        assertEquals(32,h.song.countMeasureHeaders())
        assertTrue(TuxGuitarImporter.adapt(h.song).tracks[0].measures.flatMap {it.beats}
            .flatMap {it.voices}.any {it.duration?.doubleDotted==true})
    }
    @Test fun upstreamWriterIsReadableByThePortableEnvelopeAndNativeDecoder() {
        val output=ByteArrayOutputStream()
        TGSongWriterImpl().write(TGSongWriterHandle().apply {
            factory=TGFactory(); song=ShowcaseSongFactory.create(); outputStream=output
        })
        val score=TuxGuitarImporter.read(ByteArrayInputStream(output.toByteArray()),"native.tg").score
        assertEquals(ShowcaseSongFactory.TITLE,score.title)
        assertTrue(score.tracks.all {it.measures.size==32})
        assertEquals(3,score.tracks.size)
    }
    @Test fun versionAndContentMayArriveInEitherOrder() {
        val h=handle(archive(entries().reversed()))
        PortableTgReader().read(h)
        assertEquals(32,h.song.countMeasureHeaders())
        assertFalse(h.isNewerFileFormatDetected)
    }
    @Test fun nativeMajorVersionCompatibilityChecksAreNotBypassed() {
        for (major in listOf(1,3)) assertFailsWith<TGFileFormatException> {
            PortableTgReader().read(handle(versioned("TuxGuitar_file_format $major.0.0")))
        }
    }
    @Test fun nativeNewerMinorVersionFlagSurvivesTheEnvelopeAdapter() {
        val h=handle(versioned("TuxGuitar_file_format 2.1.0"))
        PortableTgReader().read(h)
        assertTrue(h.isNewerFileFormatDetected)
        assertEquals(32,h.song.countMeasureHeaders())
    }
    @Test fun malformedAndOverlongVersionHeadersAreRejectedBeforeXmlDecoding() {
        for(version in listOf("", "not-a-tg-version", "x".repeat(257))) {
            assertFailsWith<IllegalArgumentException> {PortableTgReader().read(handle(versioned(version)))}
        }
        // TGVersion intentionally maps invalid numeric text to 0.0 rather than
        // throwing. The unchanged native major-version gate rejects that value.
        assertFailsWith<TGFileFormatException> {
            PortableTgReader().read(handle(versioned("TuxGuitar_file_format nope")))
        }
    }
    @Test fun missingUnknownAndDuplicateArchiveMembersAreRejected() {
        val valid=entries()
        for(invalid in listOf(valid.drop(1),valid+("unexpected.txt" to byteArrayOf(1)))) {
            assertFailsWith<IllegalArgumentException> {PortableTgReader().read(handle(archive(invalid)))}
        }
        // ZipOutputStream itself rejects duplicate names. Rename equal-length ZIP
        // headers in an otherwise valid archive to exercise the reader's own gate.
        val duplicate=archive(valid)
        val old="content.xml".toByteArray(); val replacement="version.txt".toByteArray()
        assertEquals(old.size,replacement.size)
        var replacements=0
        for(i in 0..duplicate.size-old.size) if(old.indices.all {duplicate[i+it]==old[it]}) {
            replacement.copyInto(duplicate,i); replacements++
        }
        assertEquals(2,replacements)
        assertFailsWith<IllegalArgumentException> {PortableTgReader().read(handle(duplicate))}
    }
}
