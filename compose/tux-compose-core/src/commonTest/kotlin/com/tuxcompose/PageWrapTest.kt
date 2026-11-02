// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    @Test fun scoreOnlyLegatoAlsoContinuesAcrossRowsWithoutTabBounds() {
        val config = TGLayoutConfig(style=TGLayoutStyles.Score)
        val base = measure()
        val cut = TGLayoutCalculator(config).calculate(song(base),300f).measures()[0].beats.size
        for (kind in listOf(TGConnectionKind.Hammer,TGConnectionKind.Slide)) {
            val beats = base.beats.mapIndexed { i,b -> if (i != cut-1) b else b.copy(voices=b.voices.map { v ->
                v.copy(notes=v.notes.map { n -> n.copy(effects=TGNoteEffects(hammer=kind==TGConnectionKind.Hammer,slide=kind==TGConnectionKind.Slide)) }.toPersistentList())
            }.toPersistentList()) }
            val page = TGLayoutCalculator(config).calculate(song(base.copy(beats=beats.toPersistentList())),300f)
            val paths = page.staves.flatMap { it.connections }.filter { it.kind == kind }
            assertTrue(paths.all { it.onScore })
            assertTrue(paths.any { it.continuesToNext })
            assertTrue(paths.any { it.continuesFromPrevious })
        }
    }
    @Test fun bendsOnDifferentTrackOnsetsDoNotMisalignSharedBeats() {
        val bend = TGBendEffect(listOf(0 to 0,6 to 4,12 to 0))
        fun withBend(at: Int): TGMeasure {
            val base = measure()
            return base.copy(beats=base.beats.mapIndexed { i,b -> if (i != at) b else b.copy(voices=b.voices.map { v ->
                v.copy(notes=v.notes.map { it.copy(effects=TGNoteEffects(bend=bend)) }.toPersistentList())
            }.toPersistentList()) }.toPersistentList())
        }
        val data = TGSong(tracks=listOf(TGTrack(1,"A",tuning,listOf(withBend(1))),TGTrack(2,"B",tuning,listOf(withBend(4)))))
        for (orientation in TGLayoutOrientation.entries) {
            val page = TGLayoutCalculator(TGLayoutConfig(multiTrack=true,orientation=orientation)).calculate(data,300f)
            val a = page.staves.filter { it.trackIndex == 0 }.flatMap { it.measures }
            val b = page.staves.filter { it.trackIndex == 1 }.flatMap { it.measures }
            assertEquals(a.map { it.startTick to it.endTick },b.map { it.startTick to it.endTick })
            assertEquals(a.flatMap { it.beats }.map { it.startTick to it.x },b.flatMap { it.beats }.map { it.startTick to it.x })
        }
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
    @Test fun finalContinuationPacksWithTheFollowingBarOnEveryDisplayedTrack() {
        val data = song(measure(),measure(1,TGDuration(4)))
        for (multi in listOf(false,true)) {
            val source = if (!multi) data else data.copy(tracks=(data.tracks+TGTrack(2,"Held",tuning,
                listOf(measure(duration=TGDuration(1)),measure(1,TGDuration(4))))).toPersistentList())
            val page = TGLayoutCalculator(TGLayoutConfig(multiTrack=multi,justifySystems=false)).calculate(source,400f)
            val lastSystem = page.staves.maxOf { it.systemIndex }
            page.staves.filter { it.systemIndex == lastSystem }.forEach { row ->
                assertEquals(listOf(0,1),row.measures.map { it.measureIndex })
                val tail = row.measures[0]
                val next = row.measures[1]
                assertFalse(tail.isFirstFragment); assertTrue(tail.isLastFragment)
                assertTrue(next.isFirstFragment && next.isLastFragment)
                assertEquals(tail.bounds.right,next.bounds.left,.001f)
                assertEquals(tail.endTick,next.startTick)
                assertTrue(next.bounds.right <= 400f)
                row.measures.forEach { m -> m.beats.forEach { b ->
                    val hit = page.hitTest(b.voices[0].notes[0].tabBounds!!.center,10f)!!
                    assertEquals(m.measureIndex,hit.measureIndex)
                    assertEquals(b.startTick,hit.beatTick)
                    assertEquals(row.systemIndex,page.cursorAt(row.trackIndex,b.startTick)!!.systemIndex)
                } }
            }
            if (multi) {
                val rows = page.staves.filter { it.systemIndex == lastSystem }
                assertEquals(rows[0].measures.map { it.bounds.left to it.bounds.right },
                    rows[1].measures.map { it.bounds.left to it.bounds.right })
            }
        }
    }
    @Test fun explicitBreakAfterAContinuationStillStartsANewRow() {
        val next = measure(1,TGDuration(4)).copy(lineBreak=true)
        val page = TGLayoutCalculator().calculate(song(measure(),next),400f)
        assertEquals(listOf(1),page.staves.last().measures.map { it.measureIndex })
        assertTrue(page.staves.dropLast(1).all { row -> row.measures.all { it.measureIndex == 0 } })
        assertTrue(page.staves[page.staves.lastIndex-1].bounds.right < 400-12)
    }
    @Test fun repeatedSignaturesBelongOnlyToTheRowStartNotTheBarFollowingATail() {
        val config = TGLayoutConfig(repeatSystemSignatures=true,multiTrack=true)
        val a = TGTrack(1,"Fast",tuning,listOf(measure(),measure(1,TGDuration(4))))
        val b = a.copy(id=2)
        // 255-unit tail + 124-unit next bar needs 379 usable units (404 minus margins).
        val page = TGLayoutCalculator(config).calculate(TGSong(tracks=listOf(a,b)),404f)
        page.staves.filter { it.systemIndex == page.staves.last().systemIndex }.forEach { row ->
            assertEquals(2,row.measures.size)
            assertTrue(row.measures[0].paintClef && row.measures[0].paintTime)
            assertFalse(row.measures[1].paintClef || row.measures[1].paintTime)
            assertEquals(46f,row.measures[0].clefWidth,.001f)
        }
    }
    @Test fun oversizedNextBarCanStartMidRowAndRepeatItsOwnSignaturesOnLaterRows() {
        val key = TGKeySignature(2)
        val short = TGMeasure(1,TGMeasureHeader(timeSignature=TGTimeSignature(1,4),keySignature=key),
            listOf(TGBeat(960,voices=listOf(TGVoice(notes=listOf(TGNote(5,1)))))),960)
        val base = measure()
        val dense = TGMeasure(2,base.header.copy(keySignature=key),
            base.beats.map { it.copy(start=it.start+960) },1920)
        val a = TGTrack(1,"First",tuning,listOf(short,dense))
        val b = a.copy(id=2)
        val page = TGLayoutCalculator(TGLayoutConfig(repeatSystemSignatures=true,multiTrack=true))
            .calculate(TGSong(tracks=listOf(a,b)),600f)
        page.staves.filter { it.systemIndex == 0 }.forEach { row ->
            assertEquals(listOf(0,1),row.measures.map { it.measureIndex })
            val firstPiece = row.measures[1]
            assertTrue(firstPiece.isFirstFragment)
            assertFalse(firstPiece.paintClef || firstPiece.paintKey)
            assertTrue(firstPiece.paintTime) // A real 1/4 -> 4/4 change, not a repeated system header.
        }
        page.staves.filter { it.systemIndex > 0 }.forEach { row ->
            val continuation = row.measures.single()
            assertEquals(1,continuation.measureIndex)
            assertFalse(continuation.isFirstFragment)
            assertTrue(continuation.paintClef && continuation.paintKey && continuation.paintTime)
            assertEquals(46f,continuation.clefWidth,.001f)
            assertEquals(27f,continuation.keyWidth,.001f)
            assertTrue(continuation.bounds.right <= 600f)
        }
    }
    @Test fun sharedRowRebasesCachedFragmentsIntoTheFollowingBarsLanes() {
        val source = measure()
        val marked = source.copy(beats=source.beats.mapIndexed { i,b -> if (i == source.beats.lastIndex)
            b.copy(stroke=TGStroke(TGStrokeDirection.Up,16),pickStroke=TGStrokeDirection.Down) else b
        }.toPersistentList())
        val beat = TGBeat(4800,voices=listOf(TGVoice(notes=listOf(TGNote(5,1,effects=TGNoteEffects(
            bend=TGBendEffect(listOf(0 to 0,6 to 4,12 to 0)),vibrato=true))))),
            text="Strum",chord=TGChord("Em",listOf(0,0,0,2,2,0)),
            stroke=TGStroke(TGStrokeDirection.Down,16),pickStroke=TGStrokeDirection.Up)
        val next = TGMeasure(2,TGMeasureHeader(timeSignature=TGTimeSignature(1,4),marker="Chorus"),listOf(beat),4800)
        val page = TGLayoutCalculator().calculate(song(marked,next),400f)
        val row = page.staves.last()
        assertEquals(2,row.measures.size)
        val tail = row.measures[0]
        val following = row.measures[1]
        assertEquals(following.staffTop,tail.staffTop)
        assertEquals(following.tabTop,tail.tabTop)
        assertEquals(following.effectsTop,tail.effectsTop)
        assertEquals(following.pickStrokeTop,tail.pickStrokeTop)
        val first = page.staves.first().measures.first()
        val a = first.beats.first().voices.first().notes.first()
        val b = tail.beats.last().voices.first().notes.first()
        assertEquals(a.scoreBounds!!.center.y-first.staffTop!!,b.scoreBounds!!.center.y-tail.staffTop!!,.001f)
        assertEquals(a.tabBounds!!.center.y-first.tabTop!!,b.tabBounds!!.center.y-tail.tabTop!!,.001f)
        assertEquals(a.scoreBounds.width,b.scoreBounds.width,.001f)
        assertEquals(a.scoreBounds.height,b.scoreBounds.height,.001f)
        assertEquals(a.tabBounds.width,b.tabBounds.width,.001f)
        assertEquals(a.tabBounds.height,b.tabBounds.height,.001f)
        assertTrue(tail.staffTop!!-tail.bounds.top > first.staffTop!!-first.bounds.top)
        assertEquals(tail.tabTop!!+10,tail.beats.last().stroke!!.lines[0].from.y,.001f)
        assertEquals(tail.pickStrokeTop,tail.beats.last().pickStroke!!.lines.last().from.y,.001f)
        assertTrue(row.measures.all { it.contentScale == 1f })
    }
    @Test fun connectionCullingFindsNestedLongSpansWithoutReturningOffscreenPaths() {
        val paths = List(4000) { i -> TGConnectionLayout(TGConnectionKind.Slide,Offset(i*20f,10f),Offset(i*20f+10,10f),
            voiceIndex=0,string=1,sourceTick=i.toLong()) } +
            TGConnectionLayout(TGConnectionKind.Hammer,Offset(0f,20f),Offset(80000f,20f),
                voiceIndex=1,string=2,sourceTick=0)
        val index = TGConnectionIndex(paths)
        for (viewport in listOf(Rect(75000f,0f,75050f,50f),Rect(-50f,0f,25f,50f),
            Rect(79980f,0f,81000f,50f),Rect(75000f,100f,75050f,150f),Rect(-50f,0f,81000f,50f))) {
            assertEquals(paths.filter { it.bounds.overlaps(viewport) }.toSet(),index.visible(viewport).toSet())
        }
        assertEquals(0,index.visible(Rect(90000f,0f,91000f,50f)).count())
        assertEquals(0,TGConnectionIndex(emptyList()).visible(Rect(0f,0f,100f,100f)).count())
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
