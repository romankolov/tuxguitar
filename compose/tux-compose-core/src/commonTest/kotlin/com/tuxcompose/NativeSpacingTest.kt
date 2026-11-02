// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Offset
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.*

/** Native defaults and the explicitly non-native, fixed-size page-wrapping extension. */
class NativeSpacingTest {
    private val base = TGLayoutConfig()
    private val tuning = listOf(64,59,55,50,45,40)
    private fun bar(index: Int = 0, values: List<Int> = listOf(2,4,8,8), chordIndices: Set<Int> = emptySet()): TGMeasure {
        var tick = 960+index*3840L
        val start = tick
        return TGMeasure(index+1,TGMeasureHeader(),values.mapIndexed { i,value ->
            val duration = TGDuration(value)
            TGBeat(tick,duration,listOf(TGVoice(notes=listOf(TGNote(5,1)))),
                chord=if (i in chordIndices) TGChord("Am",listOf(0,1,2,2,0,-1)) else null).also { tick += duration.ticks }
        },start)
    }
    private fun data(vararg bars: TGMeasure) = TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,bars.toList())))
    private fun measure(m: TGMeasure, config: TGLayoutConfig = base, fonts: TGFontMetrics = TGFontMetrics.Approximate) =
        TGLayoutCalculator(config.copy(orientation=TGLayoutOrientation.Horizontal),fonts).calculate(data(m),1000f).staves.single().measures.single()
    private fun rhythm(m: TGMeasureLayout) = m.beats.last().endX-m.beatAreaLeft

    @Test fun compactIsDefaultAndMixedDurationsUseTheMinimumVoiceSum() {
        assertTrue(base.nativeSpacing)
        assertTrue(base.compact)
        val m = measure(bar())
        assertEquals(86f,rhythm(m),.001f)
        assertEquals(listOf(25f,21f,20f),m.beats.zipWithNext().map { (a,b) -> b.x-a.x })
        assertEquals(160f,rhythm(measure(bar(),base.copy(compact=false))),.001f)
    }

    @Test fun chordDisplayFlagsControlNativeSpacingAndDoNotRemoveMetadata() {
        val m = bar(values=List(8) { 8 },chordIndices=setOf(0,1))
        val hidden = measure(m)
        assertEquals(160f,rhythm(hidden),.001f)
        assertNotNull(hidden.beats[0].beat.chord)
        assertNull(hidden.beats[0].chordBounds)
        assertEquals(183f,rhythm(measure(m,base.copy(showChords=true))),.001f)
        assertEquals(344f,rhythm(measure(m,base.copy(showChords=true,compact=false))),.001f)
        assertEquals(344f,rhythm(measure(m,base.copy(showChordNames=true,compact=false))),.001f)
    }

    @Test fun loneChordAndNonAdjacentCompactChordsDoNotWidenTheWholeBar() {
        for (indices in listOf(setOf(0),setOf(0,4))) {
            val m = bar(values=List(8) { 8 },chordIndices=indices)
            assertEquals(160f,rhythm(measure(m,base.copy(showChords=true))),.001f)
            assertEquals(160f,rhythm(measure(m,base.copy(showChords=true,compact=false))),.001f)
        }
    }

    @Test fun nativeSpacingDoesNotRunTheAdaptiveFontTextOrStrumSolver() {
        val m = bar().copy(beats=bar().beats.map { b -> b.copy(text="A long annotation",stroke=TGStroke(TGStrokeDirection.Up,16)) }.toPersistentList())
        val huge = TGFontMetrics { _,_ -> TGTextSize(120f,40f) }
        val original = measure(m)
        val changed = measure(m,fonts=huge)
        assertEquals(original.bounds.width,changed.bounds.width,.001f)
        assertEquals(original.beats.map { it.x },changed.beats.map { it.x })
        assertEquals(12f,changed.beats[0].stroke!!.lines[0].from.x-changed.beats[0].x,.001f)
        val adaptive = measure(m,base.copy(spacingMode=TGSpacingMode.Adaptive,compact=false),huge)
        assertTrue(adaptive.bounds.width > changed.bounds.width)
    }

    @Test fun nativeBendsUseMaximumPerVoiceAndPerBeatNotEveryNoteOrCustomInk() {
        val bend = TGBendEffect(listOf(0 to 0,6 to 4,12 to 0))
        val source = bar()
        val m = source.copy(beats=source.beats.mapIndexed { i,b -> if (i!=0) b else b.copy(voices=listOf(
            TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(bend=bend)),TGNote(5,2,effects=TGNoteEffects(bend=bend))))).toPersistentList()) }.toPersistentList())
        assertEquals(86f,rhythm(measure(m)),.001f)
        assertEquals(180f,rhythm(measure(m,base.copy(compact=false))),.001f)
        assertEquals(180f,rhythm(measure(m,base.copy(compact=false,style=TGLayoutStyles.Score))),.001f)
    }

    @Test fun shortCallerBarIsNotPromotedToItsNominalMeterLength() {
        val m = bar(values=listOf(2))
        assertEquals(25f,rhythm(measure(m,base.copy(compact=false))),.001f)
        assertEquals(25f,rhythm(measure(m)),.001f)
    }

    @Test fun greedyEqualityAndUnequalDurationsUseNativeJustification() {
        val song = data(bar(),bar(1),bar(2))
        val page = TGLayoutCalculator().calculate(song,338f)
        assertEquals(listOf(listOf(0),listOf(1,2)),page.staves.map { it.measures.map { m -> m.measureIndex } })
        val b = page.staves[0].measures[0].beats
        val ratio = (86f+126f)/86f
        assertEquals(25f*ratio,b[1].x-b[0].x,.001f)
        assertEquals(20f*ratio,b[3].x-b[2].x,.001f)
        assertEquals(328f,page.staves[0].bounds.right,.001f)
    }

    @Test fun rightMarginStaysTenPhysicalPixelsAcrossDensityAndZoom() {
        val song = data(*Array(16) { bar(it) })
        for (scale in listOf(1f,1.25f,2.625f)) {
            val page = TGLayoutCalculator(base.copy(renderScale=scale)).calculate(song,900f/scale)
            assertEquals(900f/scale,page.width,0f)
            assertEquals(890f,page.staves[0].bounds.right*scale,.51f)
        }
    }

    @Test fun oversizedNativeMeasureWrapsAtFixedSizeAndItsTailSharesTheNextBar() {
        val first = bar(values=List(64) { 64 })
        val song = data(first,bar(1,values=List(4) { 4 }))
        val page = TGLayoutCalculator().calculate(song,350f)
        assertEquals(350f,page.width,0f)
        assertTrue(page.staves.size > 2)
        val tailRow = page.staves.last { row -> row.measures.any { it.measureIndex==0 } }
        assertEquals(listOf(0,1),tailRow.measures.map { it.measureIndex })
        val parts = page.staves.flatMap { it.measures }.filter { it.measureIndex==0 }
        assertSame(first,parts[0].measure)
        assertEquals(first.start,parts.first().startTick)
        assertEquals(first.end,parts.last().endTick)
        parts.zipWithNext().forEach { (a,b) -> assertEquals(a.endTick,b.startTick) }
        assertEquals(64,parts.sumOf { p -> p.beats.count { !it.isContinuation } })
        parts.forEach { m ->
            assertEquals(1f,m.contentScale,0f)
            assertTrue(m.bounds.right <= 350.01f)
            assertEquals(50f,m.tabBottom!!-m.tabTop!!,.001f)
            m.beats.forEach { beat -> beat.voices.flatMap { it.notes }.forEach { n ->
                assertEquals(8f,n.scoreBounds!!.height,.001f)
                val bounds = n.tabBounds!!
                assertNotNull(page.hitTest(bounds.center,base.stringSpacing))
            } }
        }
    }

    @Test fun multitrackWrappedBendsAndHeldVoicesKeepCommonCutsAndSourceOwnership() {
        val dense = bar(values=List(64) { 64 })
        val sparse = bar(values=listOf(1)).copy(beats=bar(values=listOf(1)).beats.map { b ->
            b.copy(voices=listOf(TGVoice(notes=listOf(TGNote(7,2,effects=TGNoteEffects(bend=TGBendEffect(listOf(0 to 0,6 to 4,12 to 0))))))).toPersistentList())
        }.toPersistentList())
        val song = TGSong(tracks=listOf(TGTrack(1,"Dense",tuning,listOf(dense)),TGTrack(2,"Held",tuning,listOf(sparse))))
        val page = TGLayoutCalculator(base.copy(multiTrack=true)).calculate(song,350f)
        val a = page.staves.filter { it.trackIndex==0 }.flatMap { it.measures }
        val b = page.staves.filter { it.trackIndex==1 }.flatMap { it.measures }
        assertEquals(a.map { it.startTick to it.endTick },b.map { it.startTick to it.endTick })
        assertTrue(b.drop(1).any { p -> p.beats.any { beat -> beat.voices.any { it.isContinuation } } })
        page.staves.flatMap { it.measures }.forEach { m ->
            assertTrue(m.bounds.right <= 350.01f)
            assertEquals(1f,m.contentScale,0f)
            m.beats.forEach { beat -> beat.voices.flatMap { it.notes }.forEach { n ->
                assertTrue(n.tabBounds!!.right <= 350.01f)
            } }
        }
    }

    @Test fun unnormalisedEmptyModelsAndTinyParentsStillProduceFiniteClippedGeometry() {
        val song = data(TGMeasure(1,TGMeasureHeader(),emptyList(),960))
        for (width in listOf(1f,40f,100f,320f)) {
            val page = TGLayoutCalculator().calculate(song,width)
            assertEquals(width,page.width,0f)
            assertTrue(page.height.isFinite())
            assertTrue(page.staves.all { it.bounds.width.isFinite() })
        }
    }
}
