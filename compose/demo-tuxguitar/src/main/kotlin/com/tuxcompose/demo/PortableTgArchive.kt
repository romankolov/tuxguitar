// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.io.base.TGSongWriterHandle
import app.tuxguitar.io.tg.TGSongReaderImpl
import app.tuxguitar.io.tg.TGSongWriterImpl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Only the TG ZIP envelope differs from upstream. Android 7 has no ZipEntry
 * FileTime methods; desugaring Commons Compress's overriding return types can
 * leave API-conversion bridges calling those absent platform methods. The JDK's
 * ordinary ZIP streams need neither FileTime nor a filesystem extraction.
 *
 * Native read(), including its version gates, and readContent() remain unchanged.
 * Bounds and exact entry membership are enforced before the native XML parser.
 * This optional demo adapter has no counterpart/dependency in the Compose core.
 */
internal class PortableTgReader : TGSongReaderImpl() {
    override fun getDecompressedVersionAndContent(inputStream: InputStream): Array<InputStream> {
        val expected=setOf(VERSION_FILE_NAME,CONTENT_FILE_NAME)
        val entries=linkedMapOf<String,ByteArray>()
        var expanded=0
        ZipInputStream(inputStream).use { zip ->
            while (true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in expected && entry.name !in entries) {
                    "TG archives must contain exactly one version.txt and content.xml"
                }
                val data=zip.readBounded(TuxGuitarImporter.MAX_FILE_BYTES-expanded)
                expanded+=data.size
                entries[entry.name]=data
                zip.closeEntry()
            }
        }
        require(entries.keys==expected) { "TG archive is missing its version or score" }
        val version=entries.getValue(VERSION_FILE_NAME)
        require(version.size<=256 && getFileFormatVersion(ByteArrayInputStream(version))!=null) {
            "Invalid TG file-format version"
        }
        return arrayOf(ByteArrayInputStream(version),ByteArrayInputStream(entries.getValue(CONTENT_FILE_NAME)))
    }
}

/** Native writeContent() owns every note, effect and XML field; only ZIP uses java.util.zip. */
internal class PortableTgWriter : TGSongWriterImpl() {
    override fun write(handle: TGSongWriterHandle) {
        val content=ByteArrayOutputStream()
        super.writeContent(TGSongWriterHandle().apply {
            factory=handle.factory; song=handle.song; outputStream=content
        })
        require(content.size()<=TuxGuitarImporter.MAX_FILE_BYTES) { "TG score exceeds the demo expansion limit" }
        ZipOutputStream(handle.outputStream).use { zip ->
            zip.putNextEntry(ZipEntry(VERSION_FILE_NAME))
            zip.write((VERSION_PREFIX+VERSION_SEPARATOR+FILE_FORMAT_TGVERSION.toString()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(CONTENT_FILE_NAME))
            content.writeTo(zip)
            zip.closeEntry()
        }
    }
}
