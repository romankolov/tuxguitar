// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.graphics.control.TGMeasureImpl
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.models.TGDuration as NativeDuration
import com.tuxcompose.layout.*
import kotlin.test.*

/** Original Java PAINT calls, not a second implementation of the expected anchor equations. */
class NativeAnnotationPaintTest {
    private fun fixture(strings: Int=6): app.tuxguitar.song.models.TGSong {
        val f=TGFactory();val song=f.newSong()
        val track=f.newTrack().apply {number=1;name="Annotation reference"}
        repeat(strings) {i->track.strings.add(f.newString().apply {number=i+1;value=64-i*5})}
        song.addTrack(track)
        repeat(8) {mi->
            val start=960L+3840L*mi
            val header=f.newHeader().apply {
                number=mi+1;this.start=start
                if(mi==0) marker=f.newMarker().apply {title="Reference section"}
                if(mi==2) repeatClose=3
            }
            song.addMeasureHeader(header)
            val measure=f.newMeasure(header);track.addMeasure(measure)
            repeat(4) {bi->
                val beat=f.newBeat().apply {preciseStart=NativeDuration.toPreciseTime(start+bi*960L)}
                beat.getVoice(0).apply {isEmpty=false;duration.value=4
                    addNote(f.newNote().apply {value=5;string=1})}
                if(bi==1) beat.text=f.newText().apply {value="at this note"}
                measure.addBeat(beat)
            }
        }
        return song
    }

    @Test fun measureNumberAndRepeatCountBaselinesMatchActualNativePaintCalls() {
        var numbers=0;var repeats=0
        for(style in TGLayoutStyles.entries) for(scale in listOf(1f,1.25f,2.625f))
            for(orientation in TGLayoutOrientation.entries) for(strings in listOf(1,6)) {
                val config=TGLayoutConfig(style=style,orientation=orientation,mergeLetRing=false,
                    fitPageToWidth=false,avoidAnnotationCollisions=false)
                val ref=NativeLayoutReference(fixture(strings),config,900f*scale,scale)
                ref.actual.staves.flatMap {it.measures}.forEach {m->
                    val native=ref.song.getTrack(0).getMeasure(m.measureIndex) as TGMeasureImpl
                    val calls=ref.paintStrings(native,"paintDivisions")
                    val number=calls.single {it.value==m.measure.number.toString()}
                    val position=assertNotNull(m.measureNumberBaseline(config))
                    assertEquals(number.x,position.x*scale,.025f,"$style/$orientation/$strings number x")
                    assertEquals(number.baseline,position.y*scale,.025f,"$style/$orientation/$strings number BASELINE")
                    assertTrue(position.y<(m.staffTop ?: m.tabTop!!))
                    numbers++
                    if(m.measure.header.repeatClose>0) {
                        val label="x${m.measure.header.repeatClose}"
                        val count=calls.single {it.value==label}
                        val anchor=assertNotNull(m.repeatCountBaseline(config,TGFontMetrics.Approximate.measure(label,TGTextRole.Text).width))
                        assertEquals(count.x,anchor.x*scale,.025f,"native repeat-count x")
                        assertEquals(count.baseline,anchor.y*scale,.025f,"native repeat-count BASELINE")
                        repeats++
                    }
                }
            }
        assertEquals(288,numbers);assertEquals(36,repeats)
    }

    @Test fun markerAndProseRemainAttachedToTheNativeMusicalAnchors() {
        for(style in TGLayoutStyles.entries) for(scale in listOf(1f,1.25f,2.625f)) {
            val config=TGLayoutConfig(style=style,mergeLetRing=false,avoidAnnotationCollisions=false)
            val ref=NativeLayoutReference(fixture(),config,900f*scale,scale)
            val safe=TGLayoutCalculator(config.copy(avoidAnnotationCollisions=true,renderScale=scale)).calculate(ref.model,900f)
            ref.actual.staves.flatMap {it.measures}.forEach {m->
                val native=ref.song.getTrack(0).getMeasure(m.measureIndex) as TGMeasureImpl
                val marker=ref.paintStrings(native,"paintMarker").singleOrNull()
                if(marker!=null) {
                    assertEquals(marker.x,m.beatAreaLeft*scale,.025f)
                    val annotation=safe.staves.flatMap {it.annotations}.single {it.band==TGAnnotationBand.Marker}
                    assertEquals(marker.x,annotation.labelBounds.left*scale,.025f,"marker must not be moved to headerX")
                    val baseline=annotation.labelBounds.top+TGFontMetrics.Approximate.firstBaseline(annotation.lines.first(),TGTextRole.Text)
                    val safeMeasure=safe.staves.flatMap {it.measures}.single {it.measureIndex==m.measureIndex}
                    assertEquals(marker.baseline-native.posY,(baseline-safeMeasure.bounds.top)*scale,.025f,
                        "marker BASELINE relative to native content; alphaTab reserves staff overflow outside it")
                }
                val prose=ref.paintStrings(native,"paintTexts").single()
                val beat=m.beats.single {it.beat.text==prose.value}
                assertEquals(prose.x,beat.x*scale,.025f)
                val annotation=safe.staves.flatMap {it.annotations}.single {it.band==TGAnnotationBand.Text && it.sourceTick==beat.startTick}
                assertEquals(prose.x,annotation.labelBounds.left*scale,.025f,"prose must not be clamped back from its note")
                val baseline=annotation.labelBounds.top+TGFontMetrics.Approximate.firstBaseline(annotation.lines.first(),TGTextRole.Text)
                val safeMeasure=safe.staves.flatMap {it.measures}.single {it.measureIndex==m.measureIndex}
                assertEquals(prose.baseline-native.posY,(baseline-safeMeasure.bounds.top)*scale,.025f,
                    "prose baseline relative to its native measure origin; protected rows may be taller")
            }
        }
    }
    @Test fun nativeOfflineShapesAndVoiceWidthsMatchOriginalJavaPaintCalls() {
        val methods=mapOf(TGOfflineEffectKind.Accent to "paintAccentuated",TGOfflineEffectKind.HeavyAccent to "paintHeavyAccentuated",
            TGOfflineEffectKind.FadeIn to "paintFadeIn",TGOfflineEffectKind.Vibrato to "paintVibrato",TGOfflineEffectKind.Trill to "paintTrill")
        val port=Class.forName("com.tuxcompose.layout.TGNativeOfflineEffectsKt").getDeclaredMethod("nativeOfflineEffectPaintCalls",
            TGOfflineEffectKind::class.java,java.lang.Float.TYPE,java.lang.Float.TYPE)
        var checked=0
        for(style in TGLayoutStyles.entries) for(compact in listOf(false,true)) for(scale in listOf(1f,1.25f,2.625f)) {
            val song=fixture()
            val durations=listOf(8,8,4,2)
            song.getTrack(0).measures.forEach {m->
                var tick=m.header.start
                m.beats.forEachIndexed {i,b->
                    b.preciseStart=NativeDuration.toPreciseTime(tick)
                    b.getVoice(0).duration.value=durations[i]
                    tick+=b.getVoice(0).duration.time
                }
            }
            val cfg=TGLayoutConfig(style=style,compact=compact,fitPageToWidth=false,avoidAnnotationCollisions=false,mergeLetRing=false)
            val ref=NativeLayoutReference(song,cfg,900f*scale,scale)
            val m=ref.actual.staves.flatMap {it.measures}.first()
            val native=ref.song.getTrack(0).getMeasure(0) as TGMeasureImpl
            m.beats.forEachIndexed {bi,b->
                val voice=(native.getBeat(bi) as app.tuxguitar.graphics.control.TGBeatImpl).getVoiceImpl(0)
                assertEquals(voice.width,b.voices.single().effectWidth*scale,.025f,"source voice width: $style/$compact/$scale/$bi")
                val note=voice.notes.first() as app.tuxguitar.graphics.control.TGNoteImpl
                methods.forEach {(kind,method)->
                    val expected=ref.paintOfflinePath(note,method)
                    @Suppress("UNCHECKED_CAST")
                    val actual=port.invoke(null,kind,b.voices.single().effectWidth,TGFontMetrics.Approximate.measure("Tr",TGTextRole.Text).width) as List<Pair<String,List<Float>>>
                    assertEquals(expected.map {it.first},actual.map {it.first},"$method/$style/$compact/$scale/$bi path operations")
                    expected.zip(actual).forEach {(a,c)->a.second.zip(c.second).forEach {(x,y)->
                        assertEquals(x,y*scale,.025f,"$method/$style/$compact/$scale/$bi vertex")
                    }}
                    checked++
                }
            }
        }
        assertEquals(360,checked)
    }

}
