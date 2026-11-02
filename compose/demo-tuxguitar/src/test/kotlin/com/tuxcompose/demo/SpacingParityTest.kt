// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.graphics.control.TGFactoryImpl
import app.tuxguitar.graphics.control.TGLayout
import app.tuxguitar.graphics.control.TGMeasureImpl
import app.tuxguitar.ui.resource.UIPainter
import app.tuxguitar.ui.resource.UIRectangle
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.test.*

// v0.1.4 extension regressions. Native defaults have separate full-Java differential tests.
private val adaptiveConfig = TGLayoutConfig(spacingMode=TGSpacingMode.Adaptive,compact=false,
    pagePadding=12f,pageRightPadding=12f,systemSpacing=20f,showChords=true,
    typography=TGTypography(chordSize=9f,graceSize=6.3f),respectSystemFontScale=true)


/** Explicit Adaptive-mode differences. Not evidence of native-default layout parity. */
class SpacingParityTest {
    private val factory = TGFactoryImpl()
    private val tuning = listOf(64,59,55,50,45,40)
    private val config = adaptiveConfig.copy(style=TGLayoutStyles.Tablature,orientation=TGLayoutOrientation.Horizontal,showTempo=false)
    private val nativeLayout = object : TGLayout(null,TGLayout.DISPLAY_TABLATURE or TGLayout.DISPLAY_CHORD_DIAGRAM) {
        override fun getMode() = TGLayout.MODE_HORIZONTAL
        override fun getScale() = 1f
        override fun getChordStringSpacing() = 5f
        override fun getChordFretIndexSpacing() = 8f
        override fun paintSong(painter: UIPainter?, area: UIRectangle?, x: Float, y: Float) = Unit
    }.also { layout ->
        TGLayout::class.java.getDeclaredField("durationWidths").apply { isAccessible=true }
            .set(layout,floatArrayOf(30f,25f,21f,20f,19f,18f))
    }

    private fun native(chords: Set<Int>, values: List<Int> = List(8) { 8 }): TGMeasureImpl {
        val track = factory.newTrack()
        tuning.forEachIndexed { i,value -> track.strings.add(factory.newString().apply { number=i+1; this.value=value }) }
        val header = factory.newHeader().apply { number=1; start=960 }
        val measure = factory.newMeasure(header) as TGMeasureImpl
        track.addMeasure(measure)
        var tick = 960L
        values.forEachIndexed { i,value ->
            val beat = factory.newBeat().apply {
                start=tick
                getVoice(0).apply { isEmpty=false; duration.value=value }
                if (i in chords) chord=factory.newChord(6).apply { name="Am" }
            }
            tick += beat.getVoice(0).duration.time
            measure.addBeat(beat)
        }
        TGMeasureImpl::class.java.getDeclaredMethod("calculateBeats",TGLayout::class.java).apply { isAccessible=true }
            .invoke(measure,nativeLayout)
        return measure
    }
    private fun quarter(measure: TGMeasureImpl): Float = TGMeasureImpl::class.java.getDeclaredField("quarterSpacing")
        .apply { isAccessible=true }.getFloat(measure)
    private fun port(chords: Set<Int>, values: List<Int> = List(8) { 8 }, compact: Boolean = false): TGMeasureLayout {
        var tick = 960L
        val beats = values.mapIndexed { i,value ->
            val duration = TGDuration(value)
            TGBeat(tick,duration,listOf(TGVoice()),chord=if (i in chords) TGChord("Am",listOf(0,1,2,2,0,-1)) else null)
                .also { tick += duration.ticks }
        }
        val m = TGMeasure(1,TGMeasureHeader(),beats,960)
        val data = TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,listOf(m))))
        return TGLayoutCalculator(config.copy(compact=compact)).calculate(data,1000f).staves.single().measures.single()
    }

    @Test fun isolatedAndWidelySeparatedChordsMatchNativeRhythmicSpacing() {
        for (chords in listOf(emptySet(),setOf(0),setOf(0,4))) {
            val original = native(chords)
            val m = port(chords)
            assertEquals(40f,quarter(original),.001f)
            m.beats.zipWithNext().forEach { (a,b) -> assertEquals(quarter(original)/2,b.x-a.x,.001f) }
            assertEquals(quarter(original)*4,m.beats.last().endX-m.beatAreaLeft,.001f)
        }
    }

    @Test fun closeChordsKeepNativeClearanceButDoNotInflateTheRestOfTheBar() {
        val original = native(setOf(0,1))
        val m = port(setOf(0,1))
        assertEquals(86f,quarter(original),.001f)
        // Deliberate extension: the source's 43-unit clearance is local, not a global scale.
        assertEquals(quarter(original)/2,m.beats[1].x-m.beats[0].x,.001f)
        assertEquals(20f,m.beats[2].x-m.beats[1].x,.001f)
        assertEquals(183f,m.beats.last().endX-m.beatAreaLeft,.001f)
        assertTrue(m.beats.last().endX-m.beatAreaLeft < quarter(original)*4)
    }

    @Test fun unannotatedCompactWidthMatchesTheNativeSumOfBeatMinima() {
        val values = listOf(2,4,8,8)
        val original = native(emptySet(),values)
        val nativeWidth = TGMeasureImpl::class.java.getDeclaredField("widthBeats").apply { isAccessible=true }.getFloat(original)
        val m = port(emptySet(),values,compact=true)
        assertTrue(m.compact)
        assertEquals(86f,nativeWidth,.001f)
        assertEquals(nativeWidth,m.beats.last().endX-m.beatAreaLeft,.001f)
    }
}
