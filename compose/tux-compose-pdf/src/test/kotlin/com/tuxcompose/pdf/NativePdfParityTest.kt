// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import app.tuxguitar.graphics.control.TGFactoryImpl as OriginalFactory
import app.tuxguitar.graphics.control.print.TGPrintController as OriginalController
import app.tuxguitar.graphics.control.print.TGPrintLayout as OriginalLayout
import app.tuxguitar.graphics.control.print.TGPrintSettings as OriginalSettings
import app.tuxguitar.io.pdf.PDFDocument
import app.tuxguitar.io.pdf.PDFLayoutStyles
import app.tuxguitar.io.pdf.PDFResourceFactory
import app.tuxguitar.song.managers.TGSongManager as OriginalManager
import app.tuxguitar.ui.resource.UIPainter as OriginalPainter
import app.tuxguitar.ui.resource.UIInset as OriginalInset
import app.tuxguitar.ui.resource.UISize as OriginalSize
import app.tuxguitar.util.TGContext
import app.tuxguitar.util.TGMessagesManager
import app.tuxguitar.resource.TGResourceBundle
import com.itextpdf.text.pdf.BaseFont
import com.tuxcompose.demo.ShowcaseSongFactory
import com.tuxcompose.demo.TuxGuitarImporter
import com.tuxcompose.layout.TGLayoutStyles
import com.tuxcompose.model.*
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.print.TGPrintDocument
import com.tuxcompose.pdf.internal.tuxguitar.ui.resource.*
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.Properties
import kotlin.test.*

/** Executes the ORIGINAL print engine and iText PDF backend, not a copied pagination formula. */
class NativePdfParityTest {
    private val metrics=object: PrintMetrics {
        fun font(f: PrintFont)=BaseFont.createFont(f.name,BaseFont.CP1252,BaseFont.NOT_EMBEDDED)
        override fun width(text: String,font: PrintFont)=this.font(font).getWidth(text)/1000f*font.size
        override fun digitAscent(font: PrintFont)=this.font(font).getAscent("1234567890")/1000f*font.size
    }
    private class Calls {
        val values=mutableListOf<String>()
        fun add(name: String,args: Array<out Any?>,result: Any?=null) {
            fun value(a: Any?): String = when(a) {
                null->"null"
                is Float->a.toRawBits().toString(16)
                is Number,is Boolean,is String->a.toString()
                else->{
                    val c=a.javaClass
                    if(c.methods.any {it.name=="getHeight"}) listOf("getName","getHeight","isBold","isItalic").joinToString(",") {
                        value(c.getMethod(it).invoke(a))
                    } else if(c.methods.any {it.name=="getRed"}) listOf("getRed","getGreen","getBlue").joinToString(",") {
                        value(c.getMethod(it).invoke(a))
                    } else c.simpleName
                }
            }
            values+=name+":"+args.joinToString(",") {value(it)}+(if(name.startsWith("getFM") || name=="getFontSize") "="+value(result) else "")
        }
    }
    private fun <T> proxy(type: Class<T>,delegate: T,calls: Calls): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader,arrayOf(type)) {_,m,args->
            val result=try {m.invoke(delegate,*(args.orEmpty()))} catch(e: InvocationTargetException) {throw e.targetException}
            if(m.name !in setOf("isDisposed","dispose","toString","hashCode","equals")) calls.add(m.name,args.orEmpty(),result)
            result
        } as T
    }
    private fun metadata(native: app.tuxguitar.song.models.TGSong)=TGPdfMetadata(
        author=native.author,year=native.date,copyright=native.copyright,transcriber=native.transcriber,writer=native.writer,
        lyrics=(0 until native.countTracks()).associateWith {i->native.getTrack(i).lyrics.let {TGPdfLyrics(it.lyrics,it.from)}},
        percussionClefs=(0 until native.countTracks()).filter {native.getTrack(it).isPercussion}.associateWith {ti->
            val track=native.getTrack(ti)
            (0 until track.countMeasures()).map {mi->when(track.getMeasure(mi).clef) {
                2->TGClef.Bass;3->TGClef.Tenor;4->TGClef.Alto;else->TGClef.Treble
            }}
        })
    private fun original(input: app.tuxguitar.song.models.TGSong,options: TGPdfOptions): List<String> {
        val calls=Calls();val out=ByteArrayOutputStream();val context=TGContext()
        val labels=Properties().apply {
            put("track","Track");put("tuning","Tuning");put("composition.album","Album");put("composition.author","Author")
            put("composition.copyright","Copyright");put("composition.transcriber","Transcribed by");put("composition.writer","Tab Creator")
        }
        TGMessagesManager.getInstance().setResources(TGResourceBundle(Locale.ENGLISH,labels))
        val manager=OriginalManager(OriginalFactory());val song=input.clone(manager.factory)
        val controller=OriginalController(song,manager,PDFResourceFactory(),PDFLayoutStyles(context))
        val source=TuxGuitarImporter.adapt(input)
        val settings=OriginalSettings().apply {
            val config=nativePrintSettings(source,options)
            trackNumber=config.trackNumber;fromMeasure=config.fromMeasure;toMeasure=config.toMeasure;style=config.style
        }
        val layout=OriginalLayout(controller,settings)
        val page=options.page
        val doc=object: PDFDocument(context,OriginalSize(page.width,page.height),OriginalInset(page.marginTop,page.marginLeft,page.marginRight,page.marginBottom),out) {
            private val tracing=proxy(OriginalPainter::class.java,super.getPainter(),calls)
            override fun getPainter()=tracing
            override fun pageStart() {calls.values+="PAGE";super.pageStart()}
            override fun pageFinish() {super.pageFinish();calls.values+="ENDPAGE"}
        }
        try {layout.loadStyles(options.zoomPercent/100f);layout.updateSong();layout.makeDocument(doc)}
        finally {layout.disposeLayout();controller.resourceBuffer.disposeAllResources()}
        assertTrue(out.size()>1000,"The reference must actually write an iText PDF")
        return calls.values
    }
    private fun adapted(source: TGSong,options: TGPdfOptions,metadata: TGPdfMetadata): List<String> {
        val calls=Calls();val painter=PrintPainter(metrics,{}, {})
        val tracing=proxy(UIPainter::class.java,painter,calls)
        val page=options.page
        val doc=object: TGPrintDocument {
            override fun getPainter()=tracing
            override fun getSize()=UISize(page.width,page.height)
            override fun getMargins()=UIInset(page.marginTop,page.marginLeft,page.marginRight,page.marginBottom)
            override fun start() {}
            override fun finish() {}
            override fun pageStart() {calls.values+="PAGE";painter.pageStart()}
            override fun pageFinish() {painter.dispose();calls.values+="ENDPAGE"}
            override fun isPaintable(page: Int)=true
            override fun isTransparentBackground()=true
        }
        renderNative(source,options,metadata,PrintResources(),doc)
        return calls.values
    }
    /** Compare model fields before layout so a bridge omission is diagnosed at its source. */
    private fun nativeModel(o: Any): List<String> {
        fun get(obj: Any,name: String,vararg args: Any): Any? {
            val method=obj.javaClass.methods.first {it.name==name && it.parameterCount==args.size}
            method.isAccessible=true
            return method.invoke(obj,*args)
        }
        fun value(obj: Any,names: List<String>)=names.joinToString(",") {"$it=${get(obj,it)}"}
        fun d(obj: Any): String {
            val div=get(obj,"getDivision")!!
            return value(obj,listOf("getValue","isDotted","isDoubleDotted"))+","+value(div,listOf("getEnters","getTimes"))
        }
        val out=mutableListOf<String>()
        repeat(get(o,"countTracks") as Int) {ti->
            val track=get(o,"getTrack",ti)!!
            out+="t$ti "+value(track,listOf("getNumber","getName","getOffset","getChannelId","isPercussion"))
            repeat(get(track,"stringCount") as Int) {si->out+="s$ti/$si "+value(get(track,"getString",si+1)!!,listOf("getNumber","getValue"))}
            repeat(get(track,"countMeasures") as Int) {mi->
                val m=get(track,"getMeasure",mi)!!;val h=get(m,"getHeader")!!
                out+="m$ti/$mi "+value(m,listOf("getNumber","getStart","getClef","getKeySignature"))+","+value(h,listOf("isLineBreak","getTripletFeel","isRepeatOpen","getRepeatClose","getRepeatAlternative"))
                val beats=get(m,"getBeats") as List<*>
                beats.forEachIndexed {bi,nullable->val b=nullable!!
                    out+="b$ti/$mi/$bi "+value(b,listOf("getStart","getPreciseStart"))
                    repeat(get(b,"countVoices") as Int) {vi->
                        val v=get(b,"getVoice",vi)!!
                        out+="v$ti/$mi/$bi/$vi "+value(v,listOf("isEmpty","getDirection"))+","+d(get(v,"getDuration")!!)
                        (get(v,"getNotes") as List<*>).forEachIndexed {ni,nn->val n=nn!!
                            out+="n$ti/$mi/$bi/$vi/$ni "+value(n,listOf("getValue","getString","getVelocity","isTiedNote","isAltEnharmonic"))
                        }
                    }
                }
            }
        }
        return out
    }
    @Test fun printProfileMatchesActualPdfPluginRatherThanAndroidScreenDefaults() {
        val a=nativePdfStyles();val b=PDFLayoutStyles(TGContext())
        val song=ShowcaseSongFactory.create()
        val defaults=app.tuxguitar.io.pdf.PDFSongWriter(TGContext()).getDefaultStyles(song)
        val options=nativePrintSettings(TuxGuitarImporter.adapt(song),TGPdfOptions())
        assertEquals(defaults.style,options.style);assertEquals(defaults.fromMeasure,options.fromMeasure)
        assertEquals(defaults.toMeasure,options.toMeasure);assertEquals(defaults.trackNumber,options.trackNumber)
        val getters=listOf("getFirstMeasureSpacing","getMinBufferSeparator","getMinTopSpacing","getMinScoreTabSpacing",
            "getScoreLineSpacing","getFirstTrackSpacing","getTrackSpacing","getStringSpacing","getChordFretIndexSpacing",
            "getChordStringSpacing","getChordFretSpacing","getChordNoteSize","getChordLineWidth","getRepeatEndingSpacing",
            "getTextSpacing","getMarkerSpacing","getDivisionTypeSpacing","getEffectSpacing","getFirstNoteSpacing",
            "getMeasureLeftSpacing","getMeasureRightSpacing","getClefSpacing","getKeySignatureSpacing","getTimeSignatureSpacing")
        getters.forEach {name->assertEquals(b.javaClass.getMethod(name).invoke(b),a.javaClass.getMethod(name).invoke(a),name)}
        assertContentEquals(b.durationWidths,a.durationWidths)
        assertContentEquals(b.lineWidths,a.lineWidths)
        assertEquals(b.noteFont.name,a.noteFont.name);assertEquals(b.noteFont.height,a.noteFont.height)
        assertEquals(b.defaultFont.name,a.defaultFont.name);assertEquals(b.defaultFont.height,a.defaultFont.height)
    }
    @Test fun completeAtlasPaintCallsAndPaginationMatchOriginalAcrossStylesPartsRangesAndZoom() {
        val original=ShowcaseSongFactory.create()
        val source=TuxGuitarImporter.adapt(original)
        val configurations=TGLayoutStyles.entries.flatMap {style->listOf(
            TGPdfOptions(style=style),TGPdfOptions(style=style,trackIndex=0),
            TGPdfOptions(style=style,trackIndex=1,fromMeasure=5,toMeasure=12,zoomPercent=150),
            TGPdfOptions(style=style,trackIndex=2,fromMeasure=13,toMeasure=24,page=TGPdfPage.A4))}
        val before=nativeModel(original)
        val after=nativeModel(nativeScore(source,TGPdfOptions(),metadata(original)))
        val modelMismatch=before.indices.firstOrNull {it>=after.size || before[it]!=after[it]}
        if(modelMismatch!=null) fail("Native model at $modelMismatch: ${before[modelMismatch]} != ${after.getOrNull(modelMismatch)}")
        assertEquals(before.size,after.size,"native bridge model fields")
        configurations.forEach {options->
            val expected=original(original,options);val actual=adapted(source,options,metadata(original))
            val mismatch=expected.indices.firstOrNull {it>=actual.size || expected[it]!=actual[it]}
            if(mismatch!=null) {
                val recent=expected.take(mismatch).filter {it.startsWith("drawString:")}.takeLast(5)
                fail("$options at call $mismatch\nrecent $recent\nexpected ${expected[mismatch]}\nactual ${actual.getOrNull(mismatch)}")
            }
            assertEquals(expected.size,actual.size,"$options call count")
            assertEquals(expected.count {it=="PAGE"},actual.count {it=="PAGE"},"$options original pages")
        }
    }
    @Test fun originalInputIsNotMutatedAndInvalidRangeOrExtraVoiceFailsClosed() {
        val native=ShowcaseSongFactory.create();val source=TuxGuitarImporter.adapt(native)
        val snapshot=source.toString()
        adapted(source,TGPdfOptions(trackIndex=0,fromMeasure=2,toMeasure=4),metadata(native))
        assertEquals(snapshot,source.toString())
        assertFailsWith<IllegalArgumentException> {nativeScore(source,TGPdfOptions(fromMeasure=1000),TGPdfMetadata())}
        val m=TGMeasure(1,TGMeasureHeader(),listOf(TGBeat(960,TGDuration(),listOf(TGVoice(2,listOf(TGNote(5,1)))))),960)
        assertFailsWith<IllegalArgumentException> {
            nativeScore(TGSong(tracks=listOf(TGTrack(1,"Extra voice",listOf(64),listOf(m)))),TGPdfOptions(),TGPdfMetadata())
        }
    }
}
