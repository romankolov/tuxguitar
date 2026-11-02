// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Offset
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random
import kotlin.test.*

class MeasureSpacingTest {
    private val tuning = listOf(64,59,55,50,45,40)
    private val horizontal = TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal,showTempo=false,justifySystems=false)
    private val chord = TGChord("Am",listOf(0,1,2,2,0,-1))
    private fun bar(index: Int = 0, duration: TGDuration = TGDuration(8)): TGMeasure {
        val start = 960+3840L*index
        return TGMeasure(index+1,TGMeasureHeader(),List((3840/duration.ticks).toInt()) { i ->
            TGBeat(start+i*duration.ticks,duration,listOf(TGVoice(notes=listOf(TGNote(5,1)))))
        },start)
    }
    private fun TGMeasure.edit(block: (Int,TGBeat) -> TGBeat) = copy(beats=beats.mapIndexed(block).toPersistentList())
    private fun data(vararg measures: TGMeasure) = TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,measures.toList())))
    private fun layout(measure: TGMeasure, config: TGLayoutConfig = horizontal, fonts: TGFontMetrics = TGFontMetrics.Approximate) =
        TGLayoutCalculator(config,fonts).calculate(data(measure),1000f).staves.single().measures.single()
    private fun offsets(measure: TGMeasureLayout) = measure.beats.map { it.x-measure.beatAreaLeft }
    private fun TGBeat.noteEffects(effects: TGNoteEffects) = copy(voices=voices.map { v ->
        v.copy(notes=v.notes.map { it.copy(effects=effects) }.toPersistentList())
    }.toPersistentList())

    @Test fun oneChordDoesNotWidenEveryEighthNoteInTheMeasure() {
        val plain = bar()
        val marked = plain.edit { i,b -> if (i == 0) b.copy(chord=chord) else b }
        val normal = layout(plain)
        val balanced = layout(marked)
        val legacy = layout(marked,horizontal.copy(optimizeSpacing=false))
        assertEquals(offsets(normal),offsets(balanced))
        assertEquals(normal.bounds.width,balanced.bounds.width,.001f)
        assertTrue(legacy.bounds.width-balanced.bounds.width > 180f)
        assertTrue(balanced.beats[0].chordBounds!!.right < balanced.bounds.right)
    }

    @Test fun sparseLabelsAndIndependentLanesReuseExistingRhythmicSpace() {
        val plain = bar()
        val marked = plain.edit { i,b -> when (i) {
            0 -> b.copy(text="play softly").noteEffects(TGNoteEffects(letRing=true))
            1 -> b.copy(chord=chord)
            4 -> b.copy(chord=chord)
            else -> b
        } }
        val before = layout(plain)
        val after = layout(marked)
        assertEquals(offsets(before),offsets(after))
        assertEquals(before.bounds.width,after.bounds.width,.001f)
        assertEquals(plain.beats.map { it.start },after.beats.map { it.startTick })
    }

    @Test fun consecutiveChordsOnlyAddTheClearanceTheyActuallyNeed() {
        val marked = bar().edit { i,b -> if (i < 2) b.copy(chord=chord) else b }
        val m = layout(marked)
        assertEquals(43f,m.beats[1].x-m.beats[0].x,.001f)
        m.beats.drop(1).zipWithNext().forEach { (a,b) -> assertEquals(20f,b.x-a.x,.001f) }
        assertEquals(183f,m.beats.last().endX-m.beatAreaLeft,.001f)
        assertTrue(m.beats[0].chordBounds!!.right < m.beats[1].chordBounds!!.left)
    }

    @Test fun aLongFinalLabelAddsATailRatherThanScalingAllThePreviousBeats() {
        val plain = bar()
        val marked = plain.edit { i,b -> if (i == 7) b.copy(text="perform softly") else b }
        val before = layout(plain)
        val after = layout(marked)
        assertEquals(offsets(before),offsets(after))
        val label = TGFontMetrics.Approximate.measure("perform softly",TGTextRole.Text).width
        assertEquals(label+4f-20f,after.bounds.width-before.bounds.width,.001f)
        assertTrue(after.beats.last().x+label <= after.bounds.right-horizontal.measurePadding)
        assertTrue(after.bounds.width < layout(marked,horizontal.copy(optimizeSpacing=false)).bounds.width/2)
    }

    @Test fun strumsUseTheExistingGapAndDenseStrumsGetOnlyLocalClearance() {
        for (value in listOf(4,8,16,64)) {
            val plain = bar(duration=TGDuration(value))
            val marked = plain.edit { i,b -> b.copy(stroke=TGStroke(if (i%2 == 0) TGStrokeDirection.Up else TGStrokeDirection.Down,16)) }
            val after = layout(marked)
            val legacy = layout(marked,horizontal.copy(optimizeSpacing=false))
            assertTrue(after.bounds.width < legacy.bounds.width)
            after.beats.zipWithNext().forEach { (a,b) ->
                assertTrue(a.stroke!!.bounds.right+0.99f <= b.voices[0].notes[0].tabBounds!!.left)
            }
            if (value <= 8) assertEquals(offsets(layout(plain)),offsets(after))
            after.beats.forEach { assertEquals(1f,after.contentScale); assertEquals(9f,it.voices[0].notes[0].tabBounds!!.width,.001f) }
        }
    }

    @Test fun aWideCustomFretDoesNotSetTheScaleOfEveryOtherBeat() {
        val fonts = TGFontMetrics { text,role -> TGTextSize(if (role == TGTextRole.Note && text == "257") 80f else 6f,10f) }
        val marked = bar().edit { i,b -> if (i == 2) b.copy(voices=listOf(TGVoice(notes=listOf(TGNote(257,1)))).toPersistentList()) else b }
        val after = layout(marked,horizontal,fonts)
        val legacy = layout(marked,horizontal.copy(optimizeSpacing=false),fonts)
        assertEquals(20f,after.beats[1].x-after.beats[0].x,.001f)
        assertEquals(47f,after.beats[2].x-after.beats[1].x,.001f)
        assertEquals(47f,after.beats[3].x-after.beats[2].x,.001f)
        assertEquals(20f,after.beats[4].x-after.beats[3].x,.001f)
        assertEquals(83f,after.beats[2].voices[0].notes[0].tabBounds!!.width,.001f)
        assertTrue(after.bounds.width < legacy.bounds.width/2)
        val first = after.beats.first().voices[0].notes[0].tabBounds!!
        assertTrue(first.left >= after.bounds.left)
    }

    @Test fun wideBendLabelsReserveTheirNegativeBearingAndRightEdgeLocally() {
        val fonts = TGFontMetrics { _,role -> TGTextSize(if (role == TGTextRole.Grace) 80f else 6f,10f) }
        val original = bar().edit { i,b -> if (i == 0) b.noteEffects(TGNoteEffects(bend=TGBendEffect(listOf(0 to 0,12 to 4)))) else b }
        val config = horizontal.copy(style=TGLayoutStyles.Tablature)
        val m = layout(original,config,fonts)
        val labelCenter = m.beats[0].voices[0].notes[0].tabBounds!!.right+1+10
        assertTrue(labelCenter-40 >= m.bounds.left+config.measurePadding)
        assertTrue(labelCenter+40 < m.beats[1].voices[0].notes[0].tabBounds!!.left)
        assertEquals(20f,m.beats[2].x-m.beats[1].x,.001f)
    }

    @Test fun hiddenFretFontsDoNotWidenScoreOnlyNotation() {
        val fonts = TGFontMetrics { _,role -> TGTextSize(if (role == TGTextRole.Note) 90f else 6f,10f) }
        val config = horizontal.copy(style=TGLayoutStyles.Score)
        assertEquals(layout(bar(),config).bounds.width,layout(bar(),config,fonts).bounds.width,.001f)
        assertEquals(offsets(layout(bar(),config)),offsets(layout(bar(),config,fonts)))
    }

    @Test fun sharedLocalConstraintsAlignSparseAndDenseTracksWithDifferentOnsets() {
        val dense = bar(duration=TGDuration(16)).edit { i,b -> when (i) {
            1,2 -> b.copy(chord=chord)
            6 -> b.copy(text="a longer label")
            8 -> b.noteEffects(TGNoteEffects(bend=TGBendEffect(listOf(0 to 0,6 to 8,12 to 0))))
            else -> b
        } }
        val sparse = bar().edit { i,b -> if (i == 5) b.copy(stroke=TGStroke(TGStrokeDirection.Down),pickStroke=TGStrokeDirection.Up) else b }
        val song = TGSong(tracks=listOf(TGTrack(1,"Dense",tuning,listOf(dense)),TGTrack(2,"Sparse",tuning,listOf(sparse))))
        val page = TGLayoutCalculator(horizontal.copy(multiTrack=true)).calculate(song,1000f)
        val a = page.staves[0].measures.single()
        val b = page.staves[1].measures.single()
        assertEquals(a.bounds.left,b.bounds.left,.001f); assertEquals(a.bounds.right,b.bounds.right,.001f)
        val positions = a.beats.associate { it.startTick to it.x }
        b.beats.forEach { assertEquals(positions.getValue(it.startTick),it.x,.001f) }
        assertSame(dense,a.measure); assertSame(sparse,b.measure)
    }

    @Test fun nativeCompactModeIsOptionalAndDisabledForMultipleDisplayedTracks() {
        val durations = listOf(TGDuration(2),TGDuration(4),TGDuration(8),TGDuration(8))
        var tick = 960L
        val beats = durations.map { d -> TGBeat(tick,d,listOf(TGVoice(notes=listOf(TGNote(5,1))))).also { tick += d.ticks } }
        val m = TGMeasure(1,TGMeasureHeader(),beats,960)
        val compact = layout(m,horizontal.copy(compact=true))
        assertTrue(compact.compact)
        assertEquals(86f,compact.beats.last().endX-compact.beatAreaLeft,.001f)
        assertEquals(160f,layout(m).beats.last().endX-layout(m).beatAreaLeft,.001f)
        assertTrue(compact.bounds.width < layout(m).bounds.width)
        assertEquals(beats.map { it.start },compact.beats.map { it.startTick })
        assertEquals(durations,compact.beats.map { it.voices.single().duration })
        val track = data(m).tracks.single()
        val song = TGSong(tracks=listOf(track,track.copy(id=2)))
        val calc = TGLayoutCalculator(horizontal.copy(compact=true,multiTrack=true))
        val normal = TGLayoutCalculator(horizontal.copy(multiTrack=true)).calculate(song,1000f)
        val all = calc.calculate(song,1000f)
        all.staves.forEachIndexed { i,s ->
            assertFalse(s.measures.single().compact)
            assertEquals(offsets(normal.staves[i].measures.single()),offsets(s.measures.single()))
        }
    }

    @Test fun balancingFitsMoreWholeMeasuresWithoutChangingNotationOrTiming() {
        val song = data(*Array(6) { i -> bar(i).edit { bi,b -> if (bi == 0) b.copy(chord=chord) else b } })
        val config = TGLayoutConfig(justifySystems=false)
        val page = TGLayoutCalculator(config).calculate(song,700f)
        val legacy = TGLayoutCalculator(config.copy(optimizeSpacing=false)).calculate(song,700f)
        assertEquals(listOf(0,1,2),page.staves[0].measures.map { it.measureIndex })
        assertTrue(page.staves.size < legacy.staves.size)
        assertEquals(6,page.staves.sumOf { it.measures.size })
        page.staves.flatMap { it.measures }.forEach { m ->
            assertSame(song.tracks[0].measures[m.measureIndex],m.measure)
            assertEquals(1f,m.contentScale)
            assertEquals(50f,m.tabBottom!!-m.tabTop!!,.001f)
            m.beats.forEach { b ->
                assertEquals(9f,b.voices[0].notes[0].tabBounds!!.width,.001f)
                assertEquals(b.startTick,page.cursorAt(0,b.startTick)!!.beat.startTick)
                val hit = page.hitTest(b.voices[0].notes[0].tabBounds!!.center,10f)!!
                assertEquals(m.measureIndex,hit.measureIndex); assertEquals(b.startTick,hit.beatTick)
            }
        }
    }

    @Test fun exactFitStillWrapsAndManualBreaksAreNotRemovedByBalancing() {
        val song = data(bar().edit { i,b -> if (i == 0) b.copy(chord=chord) else b },bar(1))
        val config = horizontal.copy(orientation=TGLayoutOrientation.Page)
        val natural = TGLayoutCalculator(horizontal).calculate(song,1000f).staves.single().measures
        val exact = natural.sumOf { it.bounds.width.toDouble() }.toFloat()+2*config.pagePadding
        assertEquals(2,TGLayoutCalculator(config).calculate(song,exact).staves.size)
        assertEquals(1,TGLayoutCalculator(config).calculate(song,exact+.1f).staves.size)
        val broken = data(song.tracks[0].measures[0],bar(1).copy(lineBreak=true))
        assertEquals(2,TGLayoutCalculator(config).calculate(broken,1000f).staves.size)
    }

    @Test fun fragmentCutsReserveWholeAnnotationsInsteadOfCuttingTheirInk() {
        val longChord = TGChord("long chord spelling",chord.frets)
        val m = bar(duration=TGDuration(64)).edit { i,b -> when (i) {
            4,21,40 -> b.copy(chord=longChord)
            10,30,55 -> b.copy(text="careful annotation")
            else -> b
        } }
        val config = TGLayoutConfig(justifySystems=false)
        val page = TGLayoutCalculator(config).calculate(data(m),220f)
        val pieces = page.staves.flatMap { it.measures }
        assertTrue(pieces.size>1)
        assertEquals(m.beats.map { it.start },pieces.flatMap { it.beats }.map { it.startTick })
        assertEquals(3,pieces.flatMap { it.beats }.count { it.chordBounds != null })
        assertEquals(3,pieces.flatMap { it.beats }.count { it.beat.text != null })
        pieces.zipWithNext().forEach { (a,b) -> assertEquals(a.endTick,b.startTick) }
        pieces.forEach { piece ->
            assertTrue(piece.bounds.right <= 220f+.001f)
            piece.beats.forEach { b ->
                b.chordBounds?.let { bounds -> assertTrue(bounds.left+TGFontMetrics.Approximate.measure(longChord.name,TGTextRole.Chord).width <= piece.bounds.right-config.measurePadding+.001f) }
                b.beat.text?.let { assertTrue(b.x+TGFontMetrics.Approximate.measure(it,TGTextRole.Text).width <= piece.bounds.right-config.measurePadding+.001f) }
                assertEquals(b.startTick,page.cursorAt(0,b.startTick)!!.beat.startTick)
            }
        }
    }

    @Test fun optionalBlankMarginsAreShedBeforeBreakingAnOtherwiseFittingLabel() {
        val original = bar(duration=TGDuration(1)).edit { _,b -> b.copy(text="keep this long instruction whole") }
        val page = TGLayoutCalculator(TGLayoutConfig(showTempo=false)).calculate(data(original),300f)
        val m = page.staves.single().measures.single()
        assertTrue(m.isFirstFragment && m.isLastFragment)
        assertTrue(m.bounds.right <= 300f)
        assertEquals(300f,page.width)
        val label = TGFontMetrics.Approximate.measure(original.beats[0].text!!,TGTextRole.Text).width
        assertTrue(m.beats[0].x+label <= m.bounds.right-15f+.001f)
    }

    @Test fun annotationOverhangIsReservedSeparatelyFromTheFragmentTimeSpan() {
        val offsets = (0 until 8).associate { it*60L to it*18f }
        val pieces = wrapPageMeasure(0,480,offsets,144f,emptyMap(),70f,10f,10f,0f,0f,false,960,
            mapOf(120L to 76f))
        assertEquals(120,pieces.first().endTick)
        assertTrue(pieces.any { it.contentToX > it.toX })
        pieces.forEach { assertTrue(10+it.contentToX-it.fromX <= 70f) }
        assertEquals(480,pieces.last().endTick)
        pieces.zipWithNext().forEach { (a,b) -> assertEquals(a.endTick,b.startTick); assertEquals(a.toX,b.fromX) }
    }

    @Test fun wideFretEdgesAndTheFirstGlyphRemainHittableAtTheirTrueGeometry() {
        val fonts = TGFontMetrics { text,role -> TGTextSize(if (role == TGTextRole.Note && text == "257") 80f else 6f,10f) }
        val m = bar().edit { i,b -> if (i == 0 || i == 2) b.copy(voices=listOf(TGVoice(notes=listOf(TGNote(257,1)))).toPersistentList()) else b }
        val page = TGLayoutCalculator(horizontal,fonts).calculate(data(m),1000f)
        val placed = page.staves.single().measures.single()
        for (index in listOf(0,2)) {
            val beat = placed.beats[index]
            val bounds = beat.voices.single().notes.single().tabBounds!!
            for (x in listOf(bounds.left+.5f,bounds.center.x,bounds.right-.5f))
                assertEquals(beat.startTick,page.hitTest(Offset(x,bounds.center.y),10f)!!.beatTick)
        }
        assertNull(page.hitTest(Offset(placed.bounds.left+1,placed.tabTop!!),10f))
    }

    @Test fun oddMetersOverfullBarsEmptyBarsAndExplicitDurationsKeepTheirSourceIntervals() {
        val durations = listOf(TGDuration(8,true),TGDuration(8,division=TGDivisionType(7,4)),TGDuration(64))
        for (meter in listOf(TGTimeSignature(3,8),TGTimeSignature(7,8),TGTimeSignature(5,4))) for (duration in durations) {
            val header = TGMeasureHeader(timeSignature=meter,repeatClose=2,keySignature=TGKeySignature(9))
            val beats = List(12) { i -> TGBeat(960+i*duration.ticks,duration,listOf(TGVoice(notes=listOf(TGNote(i%8,1)))) ,chord=if (i%4 == 0) chord else null) }
            val source = TGMeasure(1,header,beats,960)
            for (style in TGLayoutStyles.entries) for (compact in listOf(false,true)) {
                val config = TGLayoutConfig(style=style,compact=compact,repeatSystemSignatures=true)
                val page = TGLayoutCalculator(config).calculate(data(source),280f)
                val pieces = page.staves.flatMap { it.measures }
                assertEquals(960,pieces.first().startTick); assertEquals(source.end,pieces.last().endTick)
                assertEquals(1,pieces.count { it.isLastFragment })
                assertEquals(source.beats.map { it.start },pieces.flatMap { it.beats }.filterNot { it.isContinuation }.map { it.startTick })
                pieces.forEach { assertEquals(1f,it.contentScale) }
                assertEquals(pieces.last().measureIndex,page.cursorAt(0,source.end-1)!!.measureIndex)
            }
            val empty = TGMeasure(1,header,emptyList(),960)
            assertEquals(empty.end,layout(empty).endTick)
        }
    }

    @Test fun randomizedForwardConstraintsNeverCompressRhythmAndClearEveryOccupiedLane() {
        val random = Random(7201)
        repeat(150) {
            val base = (0 until 40).associate { i -> i*60L to i*18f }
            val groups = List(4) { TGMeasureInk(List(6) {
                base.keys.filter { random.nextBoolean() }.map { tick ->
                    TGHorizontalInk(tick,-random.nextInt(1,35).toFloat(),random.nextInt(5,100).toFloat(),random.nextInt(1,5).toFloat())
                }
            }) }
            val result = balanceMeasureSpacing(base,720f,2400,groups)
            base.keys.zipWithNext().forEach { (a,b) -> assertTrue(result.offsets.getValue(b)-result.offsets.getValue(a) >= 17.999f) }
            groups.forEach { group -> group.lanes.forEach { lane ->
                lane.zipWithNext().forEach { (a,b) ->
                    assertTrue(result.offsets.getValue(b.tick)+b.left >= result.offsets.getValue(a.tick)+a.right+a.gap-.001f)
                }
                lane.lastOrNull()?.let { assertTrue(result.rhythm >= result.offsets.getValue(it.tick)+it.right+it.gap-.001f) }
            } }
        }
    }
}
