// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.*

class CoreTest {
    private fun song(measures: Int = 4, duration: TGDuration = TGDuration(8), strings: List<Int> = listOf(64,59,55,50,45,40)): TGSong {
        val data=(0 until measures).map { mi ->
            val start=960L+mi*3840
            TGMeasure(mi+1,TGMeasureHeader(),List((3840/duration.ticks).toInt()) { bi ->
                TGBeat(start+bi*duration.ticks,duration,listOf(TGVoice(0,listOf(TGNote(5,1),TGNote(3,strings.size)))))
            },start)
        }
        return TGSong(tracks=listOf(TGTrack(1,"Guitar",strings,data)))
    }
    @Test fun durations() {
        assertEquals(960,TGDuration().ticks)
        assertEquals(720,TGDuration(8,dotted=true).ticks)
        assertEquals(840,TGDuration(8,doubleDotted=true).ticks)
        assertEquals(320,TGDuration(8,division=TGDivisionType(3,2)).ticks)
        assertEquals(TGDuration.WHOLE_PRECISE_DURATION,TGDuration(1).preciseTicks)
    }
    @Test fun allTupletsAreExactInPreciseUnit() {
        TGDivisionType.supported.forEach { (enters,times) ->
            val d=TGDuration(64,division=TGDivisionType(enters,times))
            assertEquals(TGDuration.WHOLE_PRECISE_DURATION/64*times,d.preciseTicks*enters)
        }
    }
    @Test fun rejectInvalidModels() {
        assertFailsWith<IllegalArgumentException> { TGDuration(3) }
        assertFailsWith<IllegalArgumentException> { TGDuration(4,true,true) }
        assertFailsWith<IllegalArgumentException> { TGTimeSignature(0,4) }
        assertFailsWith<IllegalArgumentException> { TGTimeSignature(4,3) }
        assertFailsWith<IllegalArgumentException> { TGNote(0,0) }
        assertFailsWith<IllegalArgumentException> { TGKeySignature(-1) }
        assertFailsWith<IllegalArgumentException> { TGDivisionType(2,3) }
    }
    @Test fun listInputsAreDefensivelySnapshotted() {
        val notes=mutableListOf(TGNote(5,1)); val voice=TGVoice(0,notes); notes.clear()
        assertEquals(1,voice.notes.size)
        val tracks=mutableListOf(song().tracks.first()); val s=TGSong(tracks=tracks); tracks.clear()
        assertEquals(1,s.tracks.size)
    }
    @Test fun restsAreNotMutedNotes() { assertTrue(TGVoice().notes.isEmpty()); assertEquals(-1,TGNote(-1,1).value) }
    @Test fun stringsAreHighToLow() { val t=song().tracks.first(); assertEquals(69,t.pitch(TGNote(5,1))); assertEquals(45,t.pitch(TGNote(5,6))) }
    @Test fun exactDurationTable() {
        val c=TGLayoutCalculator()
        assertEquals(21f,c.durationWidth(TGDuration(4)))
        assertEquals(40f,c.spacingForQuarter(TGDuration(8)))
        assertEquals(288f,c.spacingForQuarter(TGDuration(64)))
    }
    @Test fun meterBeamDivisionsFollowUpstream() {
        assertEquals(1440,TGTimeSignature(6,8).divisionTicks)
        assertEquals(1440,TGTimeSignature(3,8).divisionTicks)
        assertEquals(960,TGTimeSignature(5,8).divisionTicks)
        assertEquals(960,TGTimeSignature(6,16).divisionTicks)
    }
    @Test fun stringYAndHitTestingUseSameBounds() {
        val config=TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal)
        val layout=TGLayoutCalculator(config).calculate(song(),400f)
        val m=layout.staves.first().measures.first()
        val beat=m.beats.first(); val n=beat.voices.first().notes.first()
        assertEquals(m.tabTop,n.tabBounds!!.center.y)
        val low=beat.voices.first().notes.last()
        assertEquals(50f,low.tabBounds!!.center.y-n.tabBounds.center.y)
        val hit=layout.hitTest(n.tabBounds.center,config.stringSpacing)
        assertEquals(960,hit!!.beatTick); assertEquals(1,hit.string); assertEquals(0,hit.trackIndex)
        assertNull(layout.hitTest(Offset(m.bounds.left,m.bounds.top),config.stringSpacing))
    }
    @Test fun durationChangesSpacingNotNoteCount() {
        val layout=TGLayoutCalculator(TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal)).calculate(song(),1000f)
        val beats=layout.staves.first().measures.first().beats
        assertEquals(20f,beats[1].x-beats[0].x,.01f)
        assertEquals(20f,beats[4].x-beats[3].x,.01f)
    }
    @Test fun greedyPageWrappingAndJustification() {
        val l=TGLayoutCalculator().calculate(song(20),600f)
        assertTrue(l.staves.size>1)
        assertTrue(l.staves.all { it.measures.isNotEmpty() })
        l.staves.dropLast(1).forEach { assertEquals(588f,it.bounds.right,.01f) }
        l.staves.zipWithNext().forEach { (a,b) -> assertTrue(a.bounds.bottom < b.bounds.top) }
    }
    @Test fun singleOversizedBarDoesNotDisappearOrCompress() {
        val l=TGLayoutCalculator().calculate(song(2,TGDuration(64)),100f)
        assertEquals(2,l.staves.size); assertTrue(l.width>100f)
    }
    @Test fun emptySongsAndMeasuresAreSafe() {
        assertEquals(0f,TGLayoutCalculator().calculate(TGSong(),320f).height)
        val s=TGSong(tracks=listOf(TGTrack(1,"G",listOf(64),listOf(TGMeasure(1,TGMeasureHeader(),emptyList(),960)))))
        assertTrue(TGLayoutCalculator().calculate(s,320f).width.isFinite())
    }
    @Test fun cursorLookupIsMeasureAwareAtBoundaries() {
        val l=TGLayoutCalculator(TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal)).calculate(song(),600f)
        assertNull(l.beatAt(0,0)); assertEquals(960,l.beatAt(0,960)!!.beat.start)
        assertEquals(4800,l.beatAt(0,4800)!!.beat.start)
        assertNull(l.beatAt(0,99999)); assertNull(l.beatAt(9,960))
    }
    @Test fun viewportCullingReturnsOnlyVisibleGeometry() {
        val l=TGLayoutCalculator(TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal)).calculate(song(1000),500f)
        val viewport=Rect(10000f,0f,10500f,l.height)
        val visible=l.visible(viewport).flatMap { l.visibleMeasures(it,viewport) }.toList()
        assertTrue(visible.size in 1..5)
        assertTrue(visible.all { it.bounds.overlaps(viewport) })
    }
    @Test fun multitrackColumnsAlign() {
        val s=song(4)
        val second=s.tracks.first().copy(id=2,name="Bass",measures=song(4,TGDuration(16)).tracks.first().measures)
        val both=s.copy(tracks=listOf(s.tracks.first(),second).toPersistentList())
        val l=TGLayoutCalculator(TGLayoutConfig(multiTrack=true,orientation=TGLayoutOrientation.Horizontal)).calculate(both,600f)
        assertEquals(l.staves[0].measures.map { it.bounds.left to it.bounds.right },l.staves[1].measures.map { it.bounds.left to it.bounds.right })
        assertEquals(l.staves[0].measures[0].beats[2].x,l.staves[1].measures[0].beats[4].x,.01f)
    }
    @Test fun accidentalMemoryResetsPerMeasure() {
        val acc=TGAccidentals(0)
        assertEquals(TGAccidental.Sharp,acc.next(66,false))
        assertNull(acc.next(66,false))
        assertEquals(TGAccidental.Natural,acc.next(65,false))
        assertEquals(TGAccidental.Sharp,TGAccidentals(0).next(66,false))
    }
    @Test fun extremeKeysHaveCorrectOctaves() {
        assertEquals(6,TGNotation.noteIndex(60,7)); assertEquals(3,TGNotation.octave(60,7))
        assertEquals(0,TGNotation.noteIndex(59,13)); assertEquals(4,TGNotation.octave(59,13))
    }
    @Test fun beamingUsesMeterAndSeparatesRests() {
        val l=TGLayoutCalculator().calculate(song(),800f)
        val b=l.staves.first().measures.first().beats
        assertEquals(b[0].voices[0].beamGroup,b[1].voices[0].beamGroup)
        assertNotEquals(b[1].voices[0].beamGroup,b[2].voices[0].beamGroup)
    }
    @Test fun scoreAndTabCanBeIndependentlyDisabled() {
        TGLayoutStyles.entries.forEach { style ->
            val m=TGLayoutCalculator(TGLayoutConfig(style=style)).calculate(song(),600f).staves.first().measures.first()
            assertEquals(style!=TGLayoutStyles.Tablature,m.staffTop!=null)
            assertEquals(style!=TGLayoutStyles.Score,m.tabTop!=null)
        }
    }
    @Test fun bendsHaveSameMovementAndWidthAccounting() {
        val bend=TGBendEffect(listOf(0 to 0,3 to 4,6 to 4,12 to 0))
        assertEquals(listOf(4,-4),bend.movements())
        assertEquals(emptyList(),TGBendEffect(listOf(0 to 4,12 to 4)).movements())
    }
    @Test fun stateClampsNegativeTicksOverflowZoomAndScroll() {
        val s=TablatureState(song(),-1)
        assertEquals(0,s.cursorTick); s.moveCursorBackward(20); assertEquals(0,s.cursorTick)
        s.cursorTick=Long.MAX_VALUE-1; s.moveCursorForward(5); assertEquals(Long.MAX_VALUE,s.cursorTick)
        assertFailsWith<IllegalArgumentException> { s.moveCursorForward(-1) }
        assertFailsWith<IllegalArgumentException> { s.setZoom(Float.NaN) }
        s.setZoom(10f); assertEquals(4f,s.zoomScale)
        s.updateViewport(1000f,500f,300f,300f); s.scrollTo(2000f,-20f)
        assertEquals(700f,s.scrollX); assertEquals(0f,s.scrollY)
    }
    @Test fun cursorAndRangeSelection() {
        val s=TablatureState(song(),960); assertTrue(s.cursor.nextBeat()); assertEquals(1440,s.cursorTick)
        assertTrue(s.cursor.previousBeat()); assertEquals(960,s.cursorTick)
        s.select(0,1920); s.select(0,960,extend=true)
        assertEquals(960,s.selection!!.startTick); assertEquals(1920,s.selection!!.endTick)
        s.clearSelection(); assertNull(s.selection)
    }
    @Test fun configurationsRejectNonfiniteGeometry() {
        assertFailsWith<IllegalArgumentException> { TGLayoutConfig(scale=Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TGLayoutConfig(stringSpacing=0f) }
        assertFailsWith<IllegalArgumentException> { TGLayoutConfig(pagePadding=-1f) }
    }
}
