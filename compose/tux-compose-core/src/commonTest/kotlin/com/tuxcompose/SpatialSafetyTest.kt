// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.test.*

/** Adversarial, deterministic engraving checks, not a golden screenshot approval shortcut. */
class SpatialSafetyTest {
    private val tuning=listOf(64,59,55,50,45,40)
    private fun source(chords: Boolean=false, captions: Boolean=true, rings: Boolean=false): TGSong = TGSong(tracks=listOf(
        TGTrack(1,"Safety",tuning,List(3) { mi ->
            val start=960+3840L*mi
            TGMeasure(mi+1,TGMeasureHeader(marker=if(mi==0) "A section marker with enough words to wrap safely on a narrow parent" else null),List(8) { bi ->
                TGBeat(start+480*bi,TGDuration(8),listOf(TGVoice(notes=listOf(TGNote(if(bi%2==0) 24 else 3,1,
                    effects=TGNoteEffects(letRing=rings,palmMute=bi%2==0,tapping=bi%3==0,vibrato=bi==5))))),
                    text=if(captions && bi in listOf(0,1,7)) "A complete caption at beat $bi, never an ellipsis" else null,
                    chord=if(chords && bi%2==0) TGChord("Cmaj7(add9)/G",listOf(0,0,0,2,3,3)) else null)
            },start)
        })
    ))
    private fun clear(page: TGSongLayout, width: Float) {
        assertEquals(width,page.width,.01f)
        assertTrue(page.height.isFinite() && page.height>0f)
        page.staves.forEach { row ->
            assertTrue(row.bounds.left>=-.01f && row.bounds.right<=width+.01f)
            row.measures.forEach { assertEquals(1f,it.contentScale,0f) }
            row.annotations.forEachIndexed { i,a ->
                assertTrue(a.bounds.top>=row.bounds.top-.01f && a.bounds.bottom<=row.bounds.bottom+.01f,"${a.band} outside its row")
                assertTrue(a.labelBounds.left>=a.clipBounds.left-.01f && a.labelBounds.right<=a.clipBounds.right+.01f)
                assertTrue(a.lines.none { it.contains('…') })
                row.annotations.drop(i+1).forEach { b -> assertFalse(a.bounds.overlaps(b.bounds),"${a.band}: ${a.lines} collides with ${b.band}: ${b.lines}") }
            }
        }
    }
    @Test fun standaloneProseAndEffectsAreProtectedEvenWithoutAnyLetRing() {
        val data=source()
        for(style in TGLayoutStyles.entries) for(width in listOf(240f,320f,411f,800f)) {
            val page=TGLayoutCalculator(TGLayoutConfig(style=style)).calculate(data,width)
            clear(page,width)
            assertTrue(page.staves.flatMap { it.annotations }.any { it.band==TGAnnotationBand.Text })
            assertTrue(page.staves.flatMap { it.annotations }.any { it.band==TGAnnotationBand.Effects })
            assertTrue(page.staves.flatMap { it.measures }.all { it.plannedAnnotations })
        }
    }
    @Test fun longChordNamesArePackedAndWrappedRatherThanCoveringTheirNeighbours() {
        for(spacing in TGSpacingMode.entries) for(diagrams in listOf(false,true)) {
            val cfg=TGLayoutConfig(spacingMode=spacing,showChordNames=true,showChords=diagrams)
            val page=TGLayoutCalculator(cfg).calculate(source(chords=true),320f)
            clear(page,320f)
            val names=page.staves.flatMap { it.annotations }.filter { it.band==TGAnnotationBand.ChordNames }
            assertEquals(12,names.size)
            assertTrue(names.all { it.lines.joinToString(" ")=="Cmaj7(add9)/G" })
        }
    }
    @Test fun customFontAndWidthMatrixKeepsEveryCaptionWithinItsOwnBand() {
        for(style in TGLayoutStyles.entries) for(width in listOf(220f,320f,600f)) for(factor in listOf(.8f,1f,1.8f)) {
            val fonts=TGFontMetrics { value,role -> TGFontMetrics.Approximate.measure(value,role).let {TGTextSize(it.width*factor,it.height*factor)} }
            val page=TGLayoutCalculator(TGLayoutConfig(style=style,showChordNames=true),fonts).calculate(source(chords=true,rings=true),width)
            clear(page,width)
        }
    }
    @Test fun markersHaveTheirOwnMeasuredBandBelowMeasureNumbers() {
        val page=TGLayoutCalculator().calculate(source(),240f)
        val marker=page.staves.flatMap { it.annotations }.single { it.band==TGAnnotationBand.Marker }
        val row=page.staves.first()
        assertTrue(marker.labelBounds.top>=row.bounds.top+TGFontMetrics.Approximate.measure("0",TGTextRole.Measure).height+2f)
        assertTrue(marker.lines.size>1)
        assertEquals("A section marker with enough words to wrap safely on a narrow parent",marker.lines.joinToString(" "))
    }
    @Test fun unmarkedNativeRhythmicGridIsNotReplacedByUniformScalingOrExtraPadding() {
        val notes=List(8) {i->TGBeat(960+480L*i,TGDuration(8),listOf(TGVoice(notes=listOf(TGNote(3,1)))))}
        val data=TGSong(tracks=listOf(TGTrack(1,"Native",tuning,listOf(TGMeasure(1,TGMeasureHeader(),notes,960)))))
        for(style in TGLayoutStyles.entries) {
            val cfg=TGLayoutConfig(style=style)
            val raw=TGLayoutCalculator(cfg.copy(avoidAnnotationCollisions=false)).calculate(data,700f)
            val safe=TGLayoutCalculator(cfg).calculate(data,700f)
            assertEquals(raw.staves.flatMap { it.measures }.flatMap { it.beats }.map { it.x },safe.staves.flatMap { it.measures }.flatMap { it.beats }.map { it.x })
            assertEquals(raw.width,safe.width,.001f)
            assertTrue(safe.height>=raw.height) // Dedicated tempo clearance may increase row height.
            assertTrue(safe.staves.flatMap {it.measures}.any {it.headerBaseline!=null})
        }
    }
    @Test fun nativeDenseWideFretsGetOnlyLocalNecessaryClearance() {
        val beats=List(64) { i -> TGBeat(960+60L*i,TGDuration(64),listOf(TGVoice(notes=listOf(TGNote(24,1,effects=TGNoteEffects(ghost=i==3)))))) }
        val data=TGSong(tracks=listOf(TGTrack(1,"Wide frets",tuning,listOf(TGMeasure(1,TGMeasureHeader(),beats,960)))))
        val cfg=TGLayoutConfig(style=TGLayoutStyles.Tablature,orientation=TGLayoutOrientation.Horizontal,justifySystems=false)
        val page=TGLayoutCalculator(cfg).calculate(data,700f)
        val notes=page.staves.flatMap {it.measures}.flatMap {it.beats}.map {it.voices.single().notes.single().tabBounds!!}
        notes.zipWithNext().forEach { (a,b) -> assertTrue(a.right+0.99f<=b.left,"Wide fret masks must not erase an adjacent fret") }
        assertTrue(page.staves.flatMap {it.measures}.all {it.contentScale==1f})
    }
    @Test fun connectorLabelsHaveClearanceFromEffectAndPickingBands() {
        val beats=List(8) {i->TGBeat(960+480L*i,TGDuration(8),listOf(TGVoice(notes=listOf(TGNote(3+i,1,effects=TGNoteEffects(hammer=i%2==0,palmMute=true))))),pickStroke=TGStrokeDirection.Down)}
        val data=TGSong(tracks=listOf(TGTrack(1,"Connections",tuning,listOf(TGMeasure(1,TGMeasureHeader(),beats,960)))))
        for(style in listOf(TGLayoutStyles.Tablature,TGLayoutStyles.Mixed)) {
            val page=TGLayoutCalculator(TGLayoutConfig(style=style)).calculate(data,600f)
            val row=page.staves.single()
            row.connections.forEach { connection -> row.annotations.filter {it.band==TGAnnotationBand.Effects}.forEach {a->assertFalse(connection.bounds.overlaps(a.bounds))} }
        }
    }
    @Test fun longWrappedCaptionOnlyVisitsVisibleLines() {
        val lines=List(10000) {"Line $it"}
        val a=TGAnnotationLayout(TGAnnotationKind.Text,TGAnnotationBand.Text,lines,Rect(0f,0f,100f,100000f),10f,
            Rect(0f,0f,100f,100000f),Rect(0f,0f,100f,100000f),sourceTick=960,endTick=1440)
        assertEquals((9000..9004).toList(),a.visibleLines(Rect(0f,90000f,100f,90050f)).toList())
        assertTrue(a.visibleLines(Rect(120f,0f,140f,50f)).isEmpty())
        assertTrue(a.visibleLines(Rect(0f,110000f,100f,110050f)).isEmpty())
    }
}
