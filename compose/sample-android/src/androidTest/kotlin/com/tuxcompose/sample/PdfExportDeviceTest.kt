// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tuxcompose.demo.ShowcaseSongFactory
import com.tuxcompose.demo.TuxGuitarImporter
import com.tuxcompose.layout.TGLayoutStyles
import com.tuxcompose.pdf.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Inspect actual exported PDFs and render their actual pages; never screenshot score views as PDFs. */
@RunWith(AndroidJUnit4::class)
class PdfExportDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun source()=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"atlas.tg")
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
    private fun preserve(file: File,name: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        // shell cannot read /data/user/0/.../cache. Stage the actual bytes in the
        // app's external-files directory, as the durable score-capture exporter does.
        val folder=File(context.getExternalFilesDir(null),"pdf-evidence").apply {check(mkdirs() || isDirectory)}
        val staged=File(folder,name)
        file.copyTo(staged,overwrite=true)
        assertArrayEquals(file.readBytes(),staged.readBytes())
        shell("mkdir -p /sdcard/Download/tux-compose-qa")
        shell("cp ${staged.absolutePath} /sdcard/Download/tux-compose-qa/$name")
        assertArrayEquals(file.readBytes(),shell("cat /sdcard/Download/tux-compose-qa/$name"))
    }
    private fun render(file: File,index: Int,name: String) {
        PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use {renderer->
            renderer.openPage(index).use {page->
                val width=900;val height=(width*page.height.toFloat()/page.width).toInt()
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                assertTrue("Generated page must contain real notation",(0 until height step 3).any {y->
                    (0 until width step 3).any {x->AndroidColor.red(bitmap.getPixel(x,y))<120}
                })
                bitmap.asImageBitmap().saveScoreCapture(name)
                bitmap.recycle()
            }
        }
    }
    @Test fun runtimeStandard14MetricsMatchOriginalItextWidthsAndDigitAscent() {
        PDFBoxResourceLoader.init(context)
        // Inspect the actual internal provider without adding font-test hooks to the API.
        val type=Class.forName("com.tuxcompose.pdf.PdfMetrics")
        val provider=type.getDeclaredConstructor().newInstance()
        val fontType=Class.forName("com.tuxcompose.pdf.PrintFont")
        val constructor=fontType.getDeclaredConstructor(String::class.java,java.lang.Float.TYPE,java.lang.Boolean.TYPE,java.lang.Boolean.TYPE)
        val width=type.getDeclaredMethod("width",String::class.java,fontType)
        val ascent=type.getDeclaredMethod("digitAscent",fontType)
        val texts=listOf("1234567890","Technique Atlas","Track Acoustic guitar","Tuning: E A D G B E",
            "let ring L.R P.M N.H A.H T.H P.H S.H","Cmaj7(add9)/G","Album Canvas Sessions (2026)","é € – ·", "中文",
            (32..255).map {it.toByte()}.toByteArray().toString(Charsets.ISO_8859_1),
            (32..255).map {it.toByte()}.toByteArray().toString(java.nio.charset.Charset.forName("windows-1252")))
        for(name in listOf("Times-Roman","Times-Bold","Helvetica")) for(size in listOf(6f,8f,9f,10f,12f,16f)) {
            val original=com.itextpdf.text.pdf.BaseFont.createFont(name,"Cp1252",false)
            val font=constructor.newInstance(name,size,true,false)
            assertEquals("$name/$size digit ascent",original.getAscent("1234567890")/1000f*size,ascent.invoke(provider,font) as Float,.0001f)
            texts.forEachIndexed {index,value->
                value.forEach {char->
                    val glyph=char.toString()
                    assertEquals("$name/$size U+%04X".format(char.code),original.getWidth(glyph)/1000f*size,width.invoke(provider,glyph,font) as Float,.0001f)
                }
                assertEquals("$name/$size string $index width",original.getWidth(value)/1000f*size,width.invoke(provider,value,font) as Float,.0001f)
            }
        }
    }
    @Test fun vectorPdfHasNativePageFontsTrackPartsAndNoRasterizedScoreAcrossAllStyles() {
        val imported=source()
        val exporter=TuxGuitarPdfExporter(context)
        PDFBoxResourceLoader.init(context)
        for(style in TGLayoutStyles.entries) {
            val file=File(context.cacheDir,"pdf-${style.name}.pdf")
            val request=imported.pdfSnapshot(TGPdfOptions(style=style))
            val result=file.outputStream().use {exporter.export(request.song,it,request.options,request.metadata)}
            assertTrue(result.pageCount>=3)
            assertEquals(file.length(),result.bytesWritten)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)},result.sha256)
            PDDocument.load(file).use {pdf->
                assertEquals(result.pageCount,pdf.numberOfPages)
                assertEquals(550f,pdf.getPage(0).mediaBox.width,.001f);assertEquals(800f,pdf.getPage(0).mediaBox.height,.001f)
                val text=PDFTextStripper().getText(pdf)
                assertTrue(text.contains(ShowcaseSongFactory.TITLE))
                assertTrue(text.contains("Track Acoustic guitar"));assertTrue(text.contains("Track Fingered bass"));assertTrue(text.contains("Track Studio drums"))
                if(style!=TGLayoutStyles.Score) assertTrue(text.contains("Tuning: E A D G B E"))
                val fonts=mutableSetOf<String>()
                for(page in pdf.pages) {
                    page.resources.fontNames.forEach {fonts+=page.resources.getFont(it).name}
                    assertFalse("Notation must remain vector paths/text, never image XObjects",page.resources.xObjectNames.iterator().hasNext())
                }
                assertTrue(fonts.contains("Times-Roman"))
                if(style!=TGLayoutStyles.Score) assertTrue(fonts.contains("Times-Bold"))
            }
            preserve(file,"pdf-${style.name}.pdf")
            render(file,0,"pdf-${style.name}-first")
            render(file,result.pageCount-1,"pdf-${style.name}-last")
        }
    }
    @Test fun selectedPartRangeAndPaperAreIndependentOfScreenAndCallerOwnsTheStream() {
        val imported=source();val exporter=TuxGuitarPdfExporter(context)
        val options=TGPdfOptions(style=TGLayoutStyles.Tablature,trackIndex=1,fromMeasure=5,toMeasure=8,page=TGPdfPage.A4,zoomPercent=125)
        var closed=false
        val bytes=object: ByteArrayOutputStream() {override fun close() {closed=true;super.close()}}
        val request=imported.pdfSnapshot(options)
        val result=exporter.export(request.song,bytes,request.options,request.metadata)
        assertFalse("Caller retains its output stream",closed)
        assertTrue(result.pageCount>0)
        val file=File(context.cacheDir,"pdf-range.pdf").apply {writeBytes(bytes.toByteArray())}
        PDDocument.load(file).use {pdf->
            assertEquals(TGPdfPage.A4.width,pdf.getPage(0).mediaBox.width,.01f)
            assertEquals(TGPdfPage.A4.height,pdf.getPage(0).mediaBox.height,.01f)
            val text=PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Track Fingered bass"));assertFalse(text.contains("Track Acoustic guitar"));assertFalse(text.contains("Track Studio drums"))
        }
        preserve(file,"pdf-range.pdf");render(file,0,"pdf-range-first")
        val invalid=ByteArrayOutputStream()
        try {exporter.export(imported.score,invalid,TGPdfOptions(fromMeasure=9999));fail("Invalid range must fail")}
        catch(_: IllegalArgumentException) {assertEquals(0,invalid.size())}
    }
    @Test fun samplePdfSettingsValidateInputAndSaveTheCurrentScoreThroughTheDocumentContract() {
        compose.waitUntil(60_000) {compose.onAllNodesWithTag("next").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }}
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("Export score / tab as PDF").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Export score / tab as PDF").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("pdf-export-sheet").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pdf-export-sheet").assertIsDisplayed()
        compose.onNodeWithTag("pdf-style-Mixed").assertIsOn()
        compose.onNodeWithTag("pdf-parts-all").assertIsOn()
        compose.onNodeWithTag("pdf-export-sheet").captureScoreImage().saveScoreCapture("pdf-sheet")
        compose.onNodeWithTag("pdf-first").performScrollTo().performTextReplacement("9999")
        compose.onNodeWithTag("pdf-save").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-first").performTextReplacement("1")
        compose.onNodeWithTag("pdf-last").performTextReplacement("4")
        compose.onNodeWithTag("pdf-parts-current").performScrollTo().performClick()
        compose.onNodeWithTag("pdf-save").assertIsEnabled()
        val file=File(context.cacheDir,"pdf-ui-export.pdf")
        file.delete()
        val filter=IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);addDataType("application/pdf")
        }
        // Intercept only the OS destination picker; the app's exporter and resulting PDF are real.
        val monitor=instrumentation.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))),true)
        try {
            compose.onNodeWithTag("pdf-save").performClick()
            compose.waitUntil(120_000) {file.isFile && file.length()>1000}
            compose.waitUntil(30_000) {compose.onAllNodesWithText("PDF exported",substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals(1,monitor.hits)
            PDDocument.load(file).use {pdf->assertTrue(PDFTextStripper().getText(pdf).contains(ShowcaseSongFactory.TITLE))}
            preserve(file,"pdf-ui-export.pdf")
            compose.onNodeWithTag("studio-root").captureScoreImage().saveScoreCapture("pdf-result")
        } finally {instrumentation.removeMonitor(monitor)}
    }
}
