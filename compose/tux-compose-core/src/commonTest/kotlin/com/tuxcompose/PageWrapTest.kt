// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Offset
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.*

class PageWrapTest {
    private val tuning = listOf(64,59,55,50,45,40)
    private fun measure(index: Int = 0, duration: TGDuration = TGDuration(64), fret: Int = 5): TGMeasure {
        val start = 960+index*3840L
        return TGMeasure(index+1,TGMeasureHeader(),List((3840/duration.ticks).toInt()) { bi ->
            TGBeat(start+bi*duration.ticks,duration,listOf(TGVoice(notes=listOf(TGNote(fret,1),TGNote(3,6)))))
        },start)
    }
    private fun song(vararg measures: TGMeasure) = TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,measures.toList())))
    private fun TGSongLayout.measures() = staves.flatMap { it.measures }
    private fun TGSongLayout.attacks() = measures().flatMap { it.beats }.flatMap { it.voices }.filterNot { it.isContinuation }

    @Test fun ordinaryAndDenseRowsHaveIdenticalGlyphAndStringSizes() {
        val data = song(measure(0,TGDuration(4)),measure(1),measure(2,TGDuration(4)))
        val page = TGLayoutCalculator().calculate(data,300f)
        val wide = TGLayoutCalculator(TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal)).calculate(data,300f)
        val reference = wide.measures()[0].beats[0].voices[0].notes[0]
        assertTrue(page.measures().count { it.measureIndex == 1 } > 1)
        page.measures().forEach { m ->
            assertEquals(1f,m.contentScale)
            assertEquals(50f,m.tabBottom!!-m.tabTop!!,.001f)
            m.beats.flatMap { it.voices }.flatMap { it.notes }.forEach { n ->
                assertEquals(reference.tabBounds!!.width,n.tabBounds!!.width,.001f)
                assertEquals(reference.tabBounds.height,n.tabBounds.height,.001f)
                assertEquals(reference.scoreBounds!!.width,n.scoreBounds!!.width,.001f)
                assertEquals(reference.scoreBounds.height,n.scoreBounds.height,.001f)
            }
        }
    }
    @Test fun oversizedMeasureHasNoLostOrRepeatedAttacksAndKeepsOriginalModelIdentity() {
        val original = measure()
        val page = TGLayoutCalculator().calculate(song(original),300f)
        assertTrue(page.staves.size > 1)
        assertEquals(original.beats.size,page.attacks().size)
        page.measures().forEach { assertSame(original,it.measure); assertEquals(0,it.measureIndex) }
        assertEquals(original.beats.map { it.start },page.measures().flatMap { it.beats }.map { it.startTick })
        page.measures().zipWithNext().forEach { (a,b) -> assertEquals(a.endTick,b.startTick) }
        assertEquals(original.start,page.measures().first().startTick)
        assertEquals(original.end,page.measures().last().endTick)
    }
    @Test fun cursorAndHitTestingResolveEveryFragmentIncludingItsFirstAndLastTick() {
        val original = measure()
        val page = TGLayoutCalculator().calculate(song(original),300f)
        page.staves.forEach { staff -> staff.measures.forEach { m ->
            m.beats.forEach { b ->
                val n = b.voices[0].notes[0]
                val hit = page.hitTest(n.tabBounds!!.center,10f)!!
                assertEquals(b.startTick,hit.beatTick)
                assertEquals(0,hit.measureIndex); assertEquals(1,hit.string)
                assertEquals(staff.systemIndex,page.cursorAt(0,b.startTick)!!.systemIndex)
                val tail = page.cursorAt(0,b.endTick-1)!!
                assertEquals(staff.systemIndex,tail.systemIndex)
                assertTrue(tail.x <= b.endX)
                assertTrue(tail.x <= 300)
            }
        } }
        assertNull(page.cursorAt(0,original.end))
    }
    @Test fun headersRepeatMarksAndEndingsBelongToTheCorrectFragment() {
        val m = measure().copy(header=TGMeasureHeader(repeatOpen=true,repeatClose=2,repeatAlternative=1,marker="Verse"))
        val pieces = TGLayoutCalculator().calculate(song(m),300f).measures()
        assertEquals(1,pieces.count { it.isFirstFragment })
        assertEquals(1,pieces.count { it.isLastFragment })
        assertTrue(pieces.first().paintClef && pieces.first().paintTime && pieces.first().paintTempo)
        assertTrue(pieces.drop(1).all { !it.paintClef && !it.paintTime && !it.paintTempo })
        val repeated = TGLayoutCalculator(TGLayoutConfig(repeatSystemSignatures=true)).calculate(song(m),300f).measures()
        assertTrue(repeated.all { it.paintClef && it.paintTime })
        assertEquals(1,repeated.count { it.paintTempo })
        assertEquals(1,repeated.count { it.isLastFragment })
    }
    @Test fun accidentalMemoryContinuesThroughTheSameBarAndResetsForTheNext() {
        val page = TGLayoutCalculator().calculate(song(measure(0,fret=2),measure(1,fret=2)),300f)
        for (index in 0..1) {
            val notes = page.measures().filter { it.measureIndex == index }.flatMap { it.beats }.flatMap { it.voices }.flatMap { it.notes }.filter { it.note.string == 1 }
            assertEquals(TGAccidental.Sharp,notes.first().accidental)
            assertTrue(notes.drop(1).all { it.accidental == null })
        }
    }
    @Test fun sparseTrackUsesTheSameCutsAndContinuesSustainedNotesWithoutNewAttacks() {
        val dense = measure()
        val sustain = measure(duration=TGDuration(1))
        val data = TGSong(tracks=listOf(TGTrack(1,"Fast",tuning,listOf(dense)),TGTrack(2,"Held",tuning,listOf(sustain))))
        val page = TGLayoutCalculator(TGLayoutConfig(multiTrack=true)).calculate(data,423f)
        val fast = page.staves.filter { it.trackIndex == 0 }
        val held = page.staves.filter { it.trackIndex == 1 }
        assertTrue(fast.size > 1)
        assertEquals(fast.map { it.measures[0].startTick to it.measures[0].endTick },held.map { it.measures[0].startTick to it.measures[0].endTick })
        assertEquals(1,held.flatMap { it.measures }.flatMap { it.beats }.flatMap { it.voices }.count { !it.isContinuation })
        held.forEachIndexed { index,staff ->
            val m = staff.measures[0]
            val b = m.beats.first()
            assertEquals(m.startTick,b.startTick)
            assertEquals(index>0,b.voices[0].isContinuation)
            assertEquals(sustain.start,b.voices[0].sourceTick)
            assertEquals(TGDuration(4),b.voices[0].duration)
            assertEquals(staff.systemIndex,page.cursorAt(1,m.startTick)!!.systemIndex)
            if (index>0) assertTrue(staff.connections.any { it.kind == TGConnectionKind.Tie && it.continuesFromPrevious })
        }
        assertSame(sustain,held.last().measures[0].measure)
    }
    @Test fun longerSecondVoiceIsNotDroppedWhenThePrimaryVoiceWraps() {
        val base = measure()
        val beats = base.beats.mapIndexed { i,b -> if (i == 0) b.copy(voices=(b.voices+TGVoice(1,listOf(TGNote(7,2)),TGDuration(1))).toPersistentList()) else b }
        val page = TGLayoutCalculator().calculate(song(base.copy(beats=beats.toPersistentList())),423f)
        page.measures().drop(1).forEach { m ->
            val voice = m.beats[0].voices.single { it.voice.index == 1 }
            assertTrue(voice.isContinuation)
            assertEquals(base.start,voice.sourceTick)
            assertEquals(2,voice.notes[0].note.string)
        }
    }
    @Test fun crossRowTieHammerAndSlideHaveOutgoingAndIncomingPathsAtNormalScale() {
        val base = measure()
        val probe = TGLayoutCalculator().calculate(song(base),300f)
        val cut = probe.measures()[0].beats.size
        for (kind in TGConnectionKind.entries) {
            val beats = base.beats.mapIndexed { i,b ->
                val n = b.voices[0].notes[0]
                val changed = when {
                    i == cut-1 && kind == TGConnectionKind.Hammer -> n.copy(effects=TGNoteEffects(hammer=true))
                    i == cut-1 && kind == TGConnectionKind.Slide -> n.copy(effects=TGNoteEffects(slide=true))
                    i == cut && kind == TGConnectionKind.Tie -> n.copy(tied=true)
                    else -> n
                }
                b.copy(voices=listOf(TGVoice(notes=listOf(changed))).toPersistentList())
            }
            val page = TGLayoutCalculator().calculate(song(base.copy(beats=beats.toPersistentList())),300f)
            val paths = page.staves.flatMap { it.connections }.filter { it.kind == kind && !it.onScore }
            assertTrue(paths.any { it.continuesToNext },"Missing outgoing $kind")
            assertTrue(paths.any { it.continuesFromPrevious },"Missing incoming $kind")
            page.staves.forEach { staff -> staff.connections.forEach { path ->
                assertTrue(path.from.x >= staff.bounds.left && path.to.x <= staff.bounds.right)
                assertTrue(path.to.x > path.from.x)
                assertTrue(path.from.y >= staff.bounds.top && path.from.y <= staff.bounds.bottom)
                assertTrue(path.to.y >= staff.bounds.top && path.to.y <= staff.bounds.bottom)
            } }
            assertEquals(if (kind == TGConnectionKind.Hammer) 1 else 0,paths.count { it.label != null })
        }
    }
    @Test fun connectionsNeverChooseANoteOnAnotherVoiceOrString() {
        val b1 = TGBeat(960,voices=listOf(TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(hammer=true)))),TGVoice(1,listOf(TGNote(9,1)))))
        val b2 = TGBeat(1920,voices=listOf(TGVoice(1,listOf(TGNote(10,1)))))
        val b3 = TGBeat(2880,voices=listOf(TGVoice(0,listOf(TGNote(7,1)))))
        val page = TGLayoutCalculator().calculate(song(TGMeasure(1,TGMeasureHeader(),listOf(b1,b2,b3))),400f)
        val hammer = page.staves.flatMap { it.connections }.single { it.kind == TGConnectionKind.Hammer }
        assertEquals(0,hammer.voiceIndex)
        assertEquals(2880L,hammer.targetTick)
    }
    @Test fun bendsChordsTextAndTupletsMoveWithTheirBeatsWithoutShrinking() {
        val duration = TGDuration(16,division=TGDivisionType(3,2))
        val bend = TGBendEffect(listOf(0 to 0,6 to 4,12 to 0))
        val beats = List(24) { i -> TGBeat(960+i*duration.ticks,duration,
            listOf(TGVoice(notes=listOf(TGNote(5,1,effects=if (i == 2) TGNoteEffects(bend=bend) else TGNoteEffects())))),
            text=if (i == 2) "bend here" else null,chord=if (i == 2) TGChord("Am",listOf(0,1,2,2,0,-1)) else null) }
        val data = song(TGMeasure(1,TGMeasureHeader(),beats))
        val page = TGLayoutCalculator().calculate(data,250f)
        assertTrue(page.staves.size > 1)
        val moved = page.measures().flatMap { it.beats }.single { it.beat.start == beats[2].start }
        assertSame(beats[2],moved.beat)
        assertNotNull(moved.chordBounds)
        assertSame(bend,moved.voices[0].notes[0].note.effects.bend)
        assertTrue(page.attacks().all { it.duration.division.enters == 3 })
        page.measures().forEach { m ->
            assertEquals(1f,m.contentScale)
            assertTrue(m.beats.all { it.endX <= m.bounds.right })
        }
    }
    @Test fun veryNarrowViewsTerminateAndNeverApplyAutomaticGlyphScaling() {
        for (width in listOf(1f,20f,100f)) {
            val page = TGLayoutCalculator().calculate(song(measure()),width)
            assertEquals(width,page.width)
            assertEquals(64,page.attacks().size)
            assertTrue(page.measures().all { it.contentScale == 1f })
        }
    }
    @Test fun overflowOptOutAndHorizontalModeKeepTheUnsplitMeasure() {
        val data = song(measure())
        for (config in listOf(TGLayoutConfig(fitPageToWidth=false),TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal))) {
            val page = TGLayoutCalculator(config).calculate(data,300f)
            assertEquals(1,page.measures().size)
            assertTrue(page.width > 300)
            assertEquals(1f,page.measures()[0].contentScale)
        }
    }
}
