// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import android.content.Context
import com.tom_roush.fontbox.afm.AFMParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tuxcompose.model.TGSong
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintDocument
import com.tuxcompose.pdf.internal.tuxguitar.ui.resource.*
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Optional Android PDF library. Runs the ORIGINAL TuxGuitar print layout/painters;
 * it never screenshots the Compose view, shrinks a screen layout, or prints a cursor.
 * Blocking: call on an IO dispatcher. The caller retains ownership of [OutputStream].
 */
class TuxGuitarPdfExporter(context: Context) {
    private val app=context.applicationContext
    fun export(song: TGSong,output: OutputStream,options: TGPdfOptions=TGPdfOptions(),
        metadata: TGPdfMetadata=TGPdfMetadata(),checkCancelled: ()->Unit={}): TGPdfResult = synchronized(nativePrintLock) {
        checkCancelled()
        PDFBoxResourceLoader.init(app)
        val metrics=PdfMetrics()
        val digest=MessageDigest.getInstance("SHA-256")
        var count=0L
        val counting=object: FilterOutputStream(output) {
            override fun write(value: Int) {out.write(value);count++}
            override fun write(bytes: ByteArray,offset: Int,length: Int) {out.write(bytes,offset,length);count+=length}
            override fun close() {flush()} // A PDF backend must not close a caller-owned SAF stream.
        }
        val guarded=object: FilterOutputStream(DigestOutputStream(counting,digest)) {
            override fun write(value: Int) {out.write(value)}
            override fun write(bytes: ByteArray,offset: Int,length: Int) {out.write(bytes,offset,length)}
            override fun close() {flush()}
        }
        val snapshot=metadata.copy(lyrics=metadata.lyrics.toMap(),percussionClefs=metadata.percussionClefs.mapValues {it.value.toList()})
        PDDocument().use {pdf->
            pdf.documentInformation.apply {
                title=song.title;artistMetadata(this,snapshot,song)
                producer="Tux Compose PDF · native TuxGuitar 2.1.1 print engine"
            }
            val document=NativePdfDocument(pdf,guarded,options.page,metrics,checkCancelled)
            try {
                renderNative(song,options,snapshot,PrintResources(),document)
                check(document.completed && document.pageCount>0) {"Native printer did not complete a document"}
            } finally {document.closeOpenPage()}
            return@synchronized TGPdfResult(document.pageCount,count,digest.digest().joinToString("") {"%02x".format(it)})
        }
    }
}
private fun artistMetadata(info: com.tom_roush.pdfbox.pdmodel.PDDocumentInformation,metadata: TGPdfMetadata,song: TGSong) {
    info.author=metadata.author.ifEmpty {song.artist}
    info.creator="Tux Compose PDF"
}

/** Standard-14 fonts/AFM advances match PDFPainter/PDFFont, not Android serif substitutes. */
internal class PdfMetrics: PrintMetrics {
    private val fonts=mapOf("Times-Roman" to PDType1Font.TIMES_ROMAN,"Times-Bold" to PDType1Font.TIMES_BOLD,
        "Helvetica" to PDType1Font.HELVETICA)
    private val ascents=mutableMapOf<String,Float>()
    private val winAnsi=Charset.forName("windows-1252")
    fun font(name: String)=requireNotNull(fonts[name]) {"Unknown native PDF font: $name"}
    fun text(value: String,name: String): String {
        val encoder=winAnsi.newEncoder()
        val f=font(name)
        // Like upstream CP1252/NOT_EMBEDDED output, unsupported glyphs have no ink/advance.
        return buildString {value.forEach {c->if(encoder.canEncode(c)) {
            try {f.encode(c.toString());append(c)} catch(_: IllegalArgumentException) { /* no Standard-14 glyph */ }
        }}}
    }
    override fun width(text: String,font: PrintFont)=this.font(font.name).getStringWidth(this.text(text,font.name))/1000f*font.size
    override fun digitAscent(font: PrintFont): Float = ascents.getOrPut(font.name) {
        PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/afm/${font.name}.afm").use {stream->
            AFMParser(stream).parse(true).charMetrics.filter {it.name in digitNames}.maxOf {it.boundingBox.upperRightY}
        }
    }/1000f*font.size
    private val digitNames=setOf("zero","one","two","three","four","five","six","seven","eight","nine")
}

/** TGPrintDocument lifecycle, preserving one global page number and per-track new pages. */
private class NativePdfDocument(private val pdf: PDDocument,private val output: OutputStream,
    private val page: TGPdfPage,private val metrics: PdfMetrics,private val cancelled: ()->Unit): TGPrintDocument {
    var pageCount=0;private set
    var completed=false;private set
    private var stream: PDPageContentStream?=null
    private val painter=PrintPainter(metrics,cancelled,::paint)
    override fun getPainter(): UIPainter=painter
    override fun getSize()=UISize(page.width,page.height)
    override fun getMargins()=UIInset(page.marginTop,page.marginLeft,page.marginRight,page.marginBottom)
    override fun isPaintable(page: Int)=true
    override fun isTransparentBackground()=true
    override fun start() {cancelled()}
    override fun pageStart() {
        cancelled();check(stream==null)
        val p=PDPage(PDRectangle(page.width,page.height));pdf.addPage(p)
        stream=PDPageContentStream(pdf,p)
        pageCount++;painter.pageStart()
    }
    override fun pageFinish() {closeOpenPage();painter.dispose()}
    fun closeOpenPage() {stream?.close();stream=null}
    override fun finish() {
        cancelled();check(stream==null && pageCount>0)
        pdf.save(output);output.flush();completed=true
    }
    private fun paint(op: PrintOp) {
        cancelled()
        val c=checkNotNull(stream)
        fun y(value: Float)=page.height-value // EXACT PDFPainter.getY top-down -> PDF conversion.
        when(op) {
            is PrintOp.Text->{
                val value=metrics.text(op.value,op.font.name)
                if(value.isNotEmpty()) {
                    c.beginText();c.setNonStrokingColor(op.color.red,op.color.green,op.color.blue)
                    c.setStrokingColor(op.color.red,op.color.green,op.color.blue)
                    c.setFont(metrics.font(op.font.name),op.font.size)
                    c.newLineAtOffset(op.x,y(op.baseline));c.showText(value);c.endText()
                }
            }
            is PrintOp.Path->{
                c.setLineWidth(op.width);c.setLineCapStyle(if(op.roundCap) 1 else 0)
                c.setLineDashPattern(op.dash.toFloatArray(),op.phase)
                c.setStrokingColor(op.foreground.red,op.foreground.green,op.foreground.blue)
                c.setNonStrokingColor(op.background.red,op.background.green,op.background.blue)
                op.segments.forEach {s->when(s) {
                    is PrintSegment.Move->c.moveTo(s.x,y(s.y))
                    is PrintSegment.Line->c.lineTo(s.x,y(s.y))
                    is PrintSegment.Cubic->c.curveTo(s.x1,y(s.y1),s.x2,y(s.y2),s.x,y(s.y))
                    is PrintSegment.Circle->{
                        // PDFPainter's arc(x-r,y-r,x+r,y+r,0,360): four standard cubics.
                        val r=s.diameter/2f;val k=r*.55228475f;val cy=y(s.y);val x=s.x
                        c.moveTo(x+r,cy)
                        c.curveTo(x+r,cy+k,x+k,cy+r,x,cy+r)
                        c.curveTo(x-k,cy+r,x-r,cy+k,x-r,cy)
                        c.curveTo(x-r,cy-k,x-k,cy-r,x,cy-r)
                        c.curveTo(x+k,cy-r,x+r,cy-k,x+r,cy)
                    }
                }}
                when(op.style and 3) {3->c.fillAndStroke();1->c.stroke();2->c.fill();else->error("Unknown native path style")}
            }
        }
    }
}
