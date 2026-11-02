// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.test.*

class StrokeTest {
    private val tuning = listOf(64,59,55,50,45,40)
    private fun song(beats: List<TGBeat>, strings: Int = 6) = TGSong(tracks=listOf(
        TGTrack(1,"Guitar",List(strings) { tuning[it%tuning.size] },listOf(TGMeasure(1,TGMeasureHeader(),beats,960)))))
    private fun beat(direction: TGStrokeDirection = TGStrokeDirection.None, pick: TGStrokeDirection = TGStrokeDirection.None,
        start: Long = 960) = TGBeat(start,voices=listOf(TGVoice(notes=listOf(TGNote(5,1)))),
            stroke=TGStroke(direction,16),pickStroke=pick)

    @Test fun directionEncodingAndSpeedMetadataArePreservedWithoutChangingTiming() {
        assertEquals(1,TGStrokeDirection.Up.value)
        assertEquals(-1,TGStrokeDirection.Down.value)
        assertEquals(0,TGStrokeDirection.None.value)
        assertFailsWith<IllegalArgumentException> { TGStroke(TGStrokeDirection.Up,-1) }
        val original = beat()
        val changed = original.copy(stroke=TGStroke(TGStrokeDirection.Down,32),pickStroke=TGStrokeDirection.Up)
        assertEquals(original.start,changed.start)
        assertEquals(original.maxTicks,changed.maxTicks)
        assertSame(original.voices,changed.voices)
        assertEquals(32,changed.stroke.value)
        val noMarks = TGLayoutCalculator().calculate(song(listOf(original)),400f).staves.single().measures.single()
        assertNull(noMarks.beats.single().stroke)
        assertNull(noMarks.beats.single().pickStroke)
    }

    @Test fun strumDirectionAndStaffExtentsFollowTheOriginalPainter() {
        for (style in TGLayoutStyles.entries) for (strings in listOf(1,2,4,6,7,25)) {
            val data = song(listOf(beat(TGStrokeDirection.Up),beat(TGStrokeDirection.Down,start=1920)),strings)
            val config = TGLayoutConfig(style=style)
            val m = TGLayoutCalculator(config).calculate(data,1000f).staves.single().measures.single()
            val top: Float
            val bottom: Float
            if (style != TGLayoutStyles.Score) {
                val height = (strings-1)*config.stringSpacing
                val margin = minOf(config.stringSpacing*2,height-config.stringSpacing*2)/2
                top = m.tabTop!!+margin
                bottom = m.tabTop!!+height-margin
            } else {
                top = m.staffTop!!+config.staffSpacing
                bottom = m.staffTop!!+3*config.staffSpacing
            }
            m.beats.forEach { b ->
                val mark = b.stroke!!
                assertEquals(3,mark.lines.size) // Mixed mode must not draw a second score arrow.
                assertEquals(b.x+12,mark.lines[0].from.x,.001f)
                assertEquals(if (mark.direction == TGStrokeDirection.Up) top else bottom,mark.lines[0].from.y,.001f)
                assertEquals(if (mark.direction == TGStrokeDirection.Up) bottom else top,mark.lines[0].to.y,.001f)
                assertEquals(2f,mark.lines[0].to.x-mark.lines[1].from.x,.001f)
                assertEquals(5f,kotlin.math.abs(mark.lines[0].to.y-mark.lines[1].from.y),.001f)
                assertTrue(mark.bounds.top >= m.bounds.top && mark.bounds.bottom <= m.bounds.bottom)
            }
        }
    }

    @Test fun pickingMarksUseTheirOwnLaneAndOriginalVAndHeavyCapShapes() {
        for (style in TGLayoutStyles.entries) for (spacing in listOf(0f,8f,24f)) {
            val config = TGLayoutConfig(style=style,pickStrokeSpacing=spacing)
            val plain = TGLayoutCalculator(config).calculate(song(listOf(beat())),500f).staves.single().measures.single()
            val data = song(listOf(beat(pick=TGStrokeDirection.Up),beat(pick=TGStrokeDirection.Down,start=1920)))
            val m = TGLayoutCalculator(config).calculate(data,500f).staves.single().measures.single()
            // The original minimum buffer can absorb part of the new lane; total height need
            // not grow by eight. Check the lane-to-staff distance, not a guessed row-height delta.
            assertTrue(m.bounds.height >= plain.bounds.height)
            val afterPick = when (style) {
                TGLayoutStyles.Score -> maxOf(0f,28f*config.staffSpacing/8-
                    m.beats[0].voices[0].notes[0].position*config.staffSpacing/2)
                TGLayoutStyles.Tablature -> config.stringSpacing
                TGLayoutStyles.Mixed -> config.scoreTabSpacing
            }
            assertEquals(maxOf(8f,spacing)+afterPick,(m.tabTop ?: m.staffTop!!)-m.pickStrokeTop,.001f)
            val up = m.beats[0].pickStroke!!
            val down = m.beats[1].pickStroke!!
            assertEquals(2,up.lines.size)
            assertEquals(3,down.lines.size)
            assertEquals(m.pickStrokeTop+8,up.lines[0].to.y,.001f)
            assertEquals(m.pickStrokeTop,down.lines[0].to.y,.001f)
            assertEquals(2f,down.lines.last().width)
            assertEquals(6f,down.lines.last().to.x-down.lines.last().from.x,.001f)
            listOf(up,down).forEach { assertTrue(it.bounds.top >= m.bounds.top && it.bounds.bottom <= m.bounds.bottom) }
            assertTrue(m.beats.all { it.stroke == null })
        }
    }

    @Test fun continuedHeldBeatsDoNotRepeatStrumsOrPickingAttacks() {
        val fast = List(64) { i -> TGBeat(960+i*60L,TGDuration(64),listOf(TGVoice(notes=listOf(TGNote(5,1))))) }
        val held = TGBeat(960,TGDuration(1),listOf(TGVoice(notes=listOf(TGNote(0,1)))),
            stroke=TGStroke(TGStrokeDirection.Up,16),pickStroke=TGStrokeDirection.Down)
        val denseTrack = song(fast).tracks.single()
        val heldTrack = TGTrack(2,"Held",tuning,listOf(TGMeasure(1,TGMeasureHeader(),listOf(held),960)))
        val page = TGLayoutCalculator(TGLayoutConfig(multiTrack=true)).calculate(TGSong(tracks=listOf(denseTrack,heldTrack)),400f)
        val pieces = page.staves.filter { it.trackIndex == 1 }.flatMap { it.measures }
        assertTrue(pieces.size>1)
        assertEquals(1,pieces.flatMap { it.beats }.count { it.stroke != null })
        assertEquals(1,pieces.flatMap { it.beats }.count { it.pickStroke != null })
        pieces.drop(1).forEach { m -> m.beats.forEach { b ->
            assertTrue(b.isContinuation)
            assertSame(held,b.beat)
            assertNull(b.stroke); assertNull(b.pickStroke)
        } }
    }

    @Test fun strumAllowanceIsSharedAtTheSameOnsetAcrossTracks() {
        val plain = List(4) { i -> beat(start=960+i*960L) }
        val marked = plain.mapIndexed { i,b -> if (i == 1) b.copy(stroke=TGStroke(TGStrokeDirection.Down,16)) else b }
        val a = song(marked).tracks.single()
        val b = song(plain).tracks.single().copy(id=2)
        val page = TGLayoutCalculator(TGLayoutConfig(multiTrack=true,orientation=TGLayoutOrientation.Horizontal))
            .calculate(TGSong(tracks=listOf(a,b)),1000f)
        val first = page.staves[0].measures.single().beats
        val second = page.staves[1].measures.single().beats
        assertEquals(first.map { it.x },second.map { it.x })
        assertEquals(15f,(first[2].x-first[1].x)-(first[1].x-first[0].x),.001f)
        assertTrue(first[1].stroke!!.bounds.right < first[2].voices[0].notes[0].tabBounds!!.left)
    }

    @Test fun wideCustomFretsMoveTheArrowOutwardRatherThanShrinkEitherSymbol() {
        val fonts = TGFontMetrics { _,role -> TGTextSize(if (role == TGTextRole.Note) 80f else 18f,18f) }
        for (compact in listOf(false,true)) {
            val config = TGLayoutConfig(compact=compact,orientation=TGLayoutOrientation.Horizontal)
            val data = song(List(4) { i -> beat(TGStrokeDirection.Down,start=960+i*960L) })
            val m = TGLayoutCalculator(config,fonts).calculate(data,1000f).staves.single().measures.single()
            m.beats.forEach { b ->
                val fret = b.voices[0].notes[0].tabBounds!!
                val arrow = b.stroke!!
                assertEquals(83f,fret.width,.001f)
                assertTrue(arrow.bounds.left > fret.right)
                assertTrue(arrow.bounds.right < b.endX)
                assertEquals(2f,arrow.lines[0].to.x-arrow.lines[1].from.x,.001f)
            }
            assertEquals(1f,m.contentScale)
        }
    }
}
