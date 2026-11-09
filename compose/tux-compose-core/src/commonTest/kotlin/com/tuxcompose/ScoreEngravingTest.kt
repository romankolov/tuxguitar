// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.test.*

class ScoreEngravingTest {
    private val tuning=listOf(64,59,55,50,45,40)
    private val config=TGLayoutConfig(justifySystems=false,showTempo=false)
    private fun n(pitch: Int,string: Int=1,tied: Boolean=false,effects: TGNoteEffects=TGNoteEffects())=TGNote(3,string,pitch=pitch,tied=tied,effects=effects)
    private fun bar(notes: List<TGNote>,stem: TGStemDirection=TGStemDirection.Up)=TGMeasure(1,TGMeasureHeader(),
        listOf(TGBeat(960,TGDuration(4),listOf(TGVoice(notes=notes,stem=stem)))),960)
    private fun page(vararg bars: TGMeasure,width: Float=1000f,cfg: TGLayoutConfig=config)=
        TGLayoutCalculator(cfg).calculate(TGSong(tracks=listOf(TGTrack(1,"Engraving",tuning,bars.toList()))),width)
    private fun notes(m: TGMeasureLayout)=m.beats.flatMap {it.voices}.flatMap {it.notes}
    @Test fun consecutiveDiatonicSecondsAlternateOnTheSameStemWithoutMovingTabOnsets() {
        for(stem in listOf(TGStemDirection.Up,TGStemDirection.Down)) {
            val m=page(bar(listOf(n(60,1),n(62,2),n(64,3),n(65,4)),stem)).staves.single().measures.single()
            val heads=notes(m).map {it.scoreBounds!!.left}
            assertEquals(2,heads.distinct().size)
            assertEquals(1,notes(m).map {it.tabBounds!!.center.x}.distinct().size)
            assertTrue(heads.zipWithNext().all {it.first!=it.second})
        }
    }
    @Test fun nearbyAccidentalsUseActualHeightColumnsAndNeverOverlap() {
        val m=page(bar(listOf(n(61,1),n(63,2),n(66,3)))).staves.single().measures.single()
        val boxes=notes(m).mapNotNull {it.accidentalBounds}
        assertEquals(3,boxes.size);assertTrue(boxes.map {it.left}.distinct().size>=2)
        boxes.forEachIndexed {i,a->boxes.drop(i+1).forEach {b->assertFalse(a.overlaps(b))}}
        assertTrue(boxes.all {it.right<notes(m).minOf {it.scoreBounds!!.left}})
    }
    @Test fun accidentalsAreClickableUsingPublishedInkGeometry() {
        val p=page(bar(listOf(n(61,1),n(63,2),n(66,3))))
        val m=p.staves.single().measures.single()
        notes(m).forEach {note->
            val hit=p.hitTest(note.accidentalBounds!!.center,config.stringSpacing)
            assertNotNull(hit);assertEquals(note.note.string,hit.string);assertEquals(960L,hit.beatTick)
        }
    }
    @Test fun realCrossBarTieDoesNotPrintOrRegisterNewMeasureAccidental() {
        val a=TGMeasure(1,TGMeasureHeader(),listOf(TGBeat(960,TGDuration(1),listOf(TGVoice(notes=listOf(n(61)))))),960)
        val b=TGMeasure(2,TGMeasureHeader(),listOf(
            TGBeat(4800,TGDuration(),listOf(TGVoice(notes=listOf(n(61,tied=true))))),
            TGBeat(5760,TGDuration(),listOf(TGVoice(notes=listOf(n(61)))))),4800)
        val measures=page(a,b).staves.flatMap {it.measures}
        val second=measures.first {it.measureIndex==1}
        assertNull(second.beats[0].voices[0].notes[0].accidental)
        assertEquals(TGAccidental.Sharp,second.beats[1].voices[0].notes[0].accidental)
    }
    @Test fun danglingTieOrChangedPitchCannotHideARequiredAccidental() {
        val p=page(bar(listOf(n(61,tied=true))))
        assertEquals(TGAccidental.Sharp,notes(p.staves.single().measures.single()).single().accidental)
    }
    @Test fun aGapOrRestBeforeTheBoundaryDoesNotBecomeATieOrigin() {
        val a=bar(listOf(n(61))) // quarter ends at 1920, not the next bar at 4800
        val b=TGMeasure(2,TGMeasureHeader(),listOf(TGBeat(4800,TGDuration(),listOf(TGVoice(notes=listOf(n(61,tied=true)))))),4800)
        assertEquals(TGAccidental.Sharp,page(a,b).staves.flatMap {it.measures}.last().beats[0].voices[0].notes[0].accidental)
    }
    @Test fun simultaneousChromaticUnisonForcesBothSpellingsAndNextOnsetIsExplicit() {
        val b=TGMeasure(1,TGMeasureHeader(),listOf(
            TGBeat(960,TGDuration(),listOf(TGVoice(notes=listOf(n(60,1),n(61,2))))),
            TGBeat(1920,TGDuration(),listOf(TGVoice(notes=listOf(n(60,1)))))),960)
        val m=page(b).staves.single().measures.single()
        assertEquals(listOf(TGAccidental.Natural,TGAccidental.Sharp),m.beats[0].voices[0].notes.map {it.accidental})
        assertEquals(TGAccidental.Natural,m.beats[1].voices[0].notes.single().accidental)
    }
    @Test fun incompatibleDeadHeadsDoNotMergeWithOrdinaryUnisons() {
        val m=page(bar(listOf(n(60,1),n(60,2,effects=TGNoteEffects(dead=true))))).staves.single().measures.single()
        assertEquals(2,notes(m).map {it.scoreBounds!!.left}.distinct().size)
        assertTrue(notes(m).all {it.paintScoreHead})
    }
    @Test fun compatibleUnisonsShareHeadsWhileConflictingVoicesKeepSeparateStems() {
        fun layout(pitch: Int): List<TGVoiceLayout> {
            val b=TGBeat(960,TGDuration(),listOf(TGVoice(0,listOf(n(60,1)),stem=TGStemDirection.Up),
                TGVoice(1,listOf(n(pitch,2)),stem=TGStemDirection.Down)))
            return page(TGMeasure(1,TGMeasureHeader(),listOf(b),960)).staves.single().measures.single().beats.single().voices
        }
        val shared=layout(60);assertEquals(shared[0].notes[0].scoreBounds,shared[1].notes[0].scoreBounds)
        assertTrue(shared[0].notes[0].paintScoreHead);assertFalse(shared[1].notes[0].paintScoreHead)
        val separate=layout(62)
        assertFalse(separate[0].notes[0].scoreBounds!!.overlaps(separate[1].notes[0].scoreBounds!!))
        assertTrue(separate[1].stemX>separate[0].stemX)
    }
    @Test fun augmentationDotsUseUniqueSpacesToTheRightOfTheWholeChord() {
        val b=TGBeat(960,TGDuration(4,doubleDotted=true),listOf(TGVoice(notes=listOf(n(60,1),n(62,2),n(64,3)))))
        val v=page(TGMeasure(1,TGMeasureHeader(),listOf(b),960)).staves.single().measures.single().beats.single().voices.single()
        assertTrue(v.augmentationDots.isNotEmpty())
        assertEquals(v.augmentationDots.size,v.augmentationDots.distinct().size)
        assertTrue(v.augmentationDots.all {it.x>v.notes.maxOf {n->n.scoreBounds!!.right}+2f})
    }
    @Test fun fragmentRebasingPreservesAccidentalOriginsBoundsAndHitIdentity() {
        val b=List(64) {i->TGBeat(960+i*60L,TGDuration(64),listOf(TGVoice(notes=listOf(n(if(i%2==0) 61 else 60,1),n(63,2),n(66,3)))))}
        val data=TGMeasure(1,TGMeasureHeader(),b,960)
        val p=page(data,width=320f)
        assertTrue(p.staves.size>2)
        p.staves.forEach {row->row.measures.forEach {m->
            assertEquals(1f,m.contentScale)
            notes(m).filter {it.accidental!=null}.forEach {note->
                assertNotNull(note.accidentalOrigin);val r=note.accidentalBounds!!
                assertTrue(r.top>=row.bounds.top-.01f && r.bottom<=row.bounds.bottom+.01f)
                assertEquals(1,p.hitTest(r.center,10f)?.trackIndex?.plus(1))
            }
        }}
    }
    @Test fun anUndottedHeldChordKeepsUniqueDotAnchorsWhenFragmentDurationBecomesDotted() {
        val b=TGBeat(960,TGDuration(1),listOf(TGVoice(notes=listOf(n(60,1),n(62,2),n(64,3)))))
        val m=page(TGMeasure(1,TGMeasureHeader(),listOf(b),960),cfg=config.copy(style=TGLayoutStyles.Score)).staves.single().measures.single()
        val lanes=TGSpacing(TGLayoutStyles.Score).apply {register(TGTrackLane.Top,m.staffTop!!-m.bounds.top)}
        val header=TGFragmentHeader(m.beatAreaLeft-m.bounds.left-15f,m.clefWidth,m.keyWidth,m.previousKey,m.paintClef,m.paintKey,m.paintTime,m.paintTempo)
        val part=TGMeasureFragments(m).part(TGPageFragment(960,2400,0f,40f),m.bounds,header,15f,0f,lanes)
        val voice=part.beats.single().voices.single()
        assertTrue(voice.duration.dotted)
        assertTrue(voice.augmentationDots.isNotEmpty())
        assertEquals(voice.augmentationDots.size,voice.augmentationDots.distinct().size)
        assertTrue(voice.augmentationDots.all {it.x>voice.notes.maxOf {n->n.scoreBounds!!.right}})
    }
    @Test fun graceNotesStayBeforeAccidentalColumnsRatherThanInsideThem() {
        val m=page(bar(listOf(n(61,1,effects=TGNoteEffects(grace=TGGraceEffect(2))),n(63,2),n(66,3)))).staves.single().measures.single()
        val all=notes(m);val origin=all.first().graceScoreOrigin!!
        assertTrue(origin.x+6f<all.mapNotNull {it.accidentalBounds}.minOf {it.left})
    }
    @Test fun rawNativeModeRetainsOriginalHeadAndAccidentalCoordinates() {
        val m=page(bar(listOf(n(61,1),n(63,2),n(66,3))),cfg=config.copy(avoidAnnotationCollisions=false)).staves.single().measures.single()
        assertEquals(1,notes(m).map {it.scoreBounds!!.left}.distinct().size)
        assertTrue(notes(m).all {it.accidentalOrigin==null && it.accidentalBounds==null})
    }
}
