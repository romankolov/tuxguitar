// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.graphics.control.TGBeatImpl
import app.tuxguitar.graphics.control.TGLayout
import app.tuxguitar.graphics.control.TGLayoutVertical
import app.tuxguitar.graphics.control.TGMeasureImpl
import app.tuxguitar.graphics.control.TGTrackImpl
import app.tuxguitar.graphics.control.TGTrackSpacing
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.ui.resource.UIPainter
import app.tuxguitar.ui.resource.UIRectangle
import androidx.compose.ui.geometry.Offset
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import java.lang.reflect.Proxy
import kotlin.test.*

// v0.1.4 extension regressions. Native defaults have separate full-Java differential tests.
private val adaptiveConfig = TGLayoutConfig(spacingMode=TGSpacingMode.Adaptive,compact=false,
    pagePadding=12f,pageRightPadding=12f,systemSpacing=20f,showChords=true,
    typography=TGTypography(chordSize=9f,graceSize=6.3f),respectSystemFontScale=true)


/** Calls the original Java painters and records their actual vector commands, not a hand-written oracle. */
class StrokeParityTest {
    private val factory = TGFactory()
    private val tuning = listOf(64,59,55,50,45,40)

    @Test fun strumAndPickingVectorsMatchTheOriginalJavaPainter() {
        for (style in TGLayoutStyles.entries) for (strings in listOf(1,2,4,6,7,25))
            for (direction in TGStrokeDirection.entries) for (wide in listOf(false,true)) {
                val config = adaptiveConfig.copy(style=style,stringSpacing=if (wide) 14f else 10f,
                    staffSpacing=if (wide) 12f else 8f)
                val beat = TGBeat(960,voices=listOf(TGVoice(notes=listOf(TGNote(5,1)))),
                    stroke=TGStroke(direction,16),pickStroke=direction)
                val data = TGSong(tracks=listOf(TGTrack(1,"Guitar",List(strings) { tuning[it%6] },
                    listOf(TGMeasure(1,TGMeasureHeader(),listOf(beat))))))
                val m = TGLayoutCalculator(config).calculate(data,1000f).staves.single().measures.single()
                val flags = when (style) {
                    TGLayoutStyles.Score -> TGLayout.DISPLAY_SCORE
                    TGLayoutStyles.Tablature -> TGLayout.DISPLAY_TABLATURE
                    TGLayoutStyles.Mixed -> TGLayout.DISPLAY_SCORE or TGLayout.DISPLAY_TABLATURE
                }
                val original = object : TGLayout(null,flags) {
                    override fun getMode() = TGLayout.MODE_HORIZONTAL
                    override fun getScale() = 1f
                    override fun getStringSpacing() = config.stringSpacing
                    override fun getScoreLineSpacing() = config.staffSpacing
                    override fun getLineWidth(level: Int) = if (level == 2) 2f else 0f
                    override fun paintSong(painter: UIPainter?, area: UIRectangle?, x: Float, y: Float) = Unit
                }
                val track = TGTrackImpl(factory)
                repeat(strings) { i -> track.strings.add(factory.newString().apply { number=i+1; value=tuning[i%6] }) }
                track.update(original)
                val measure = TGMeasureImpl(factory.newHeader())
                track.addMeasure(measure)
                val native = object : TGBeatImpl(factory) {
                    override fun getSpacing(layout: TGLayout?) = 0f
                    override fun getPaintPosition(index: Int): Float = when (index) {
                        TGTrackSpacing.POSITION_TABLATURE -> m.tabTop ?: 0f
                        TGTrackSpacing.POSITION_SCORE_MIDDLE_LINES -> m.staffTop ?: 0f
                        TGTrackSpacing.POSITION_PICK_STROKES -> m.pickStrokeTop
                        else -> 0f
                    }
                }
                native.posX = m.beats.single().x
                native.stroke.direction = direction.value
                native.pickStroke.direction = direction.value
                measure.addBeat(native)
                val context = "$style strings=$strings direction=$direction wide=$wide"
                assertEquals(record { native.paintStroke(original,it,0f,0f) },m.beats.single().stroke?.lines.orEmpty(),context)
                assertEquals(record { native.paintPickStroke(original,it,0f,0f) },m.beats.single().pickStroke?.lines.orEmpty(),context)
            }
    }

    @Test fun projectedFragmentAndMeasurePackingMatchesTheOriginalGreedyRule() {
        val measures = List(4) { mi ->
            val start = 960+mi*3840L
            val duration = TGDuration(if (mi == 0) 64 else 4)
            TGMeasure(mi+1,TGMeasureHeader(),List((3840/duration.ticks).toInt()) { i ->
                TGBeat(start+i*duration.ticks,duration,listOf(TGVoice(notes=listOf(TGNote(5,1)))))
            },start,lineBreak=mi==3)
        }
        val data = TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,measures)))
        for (repeat in listOf(false,true)) {
            val config = adaptiveConfig.copy(justifySystems=false,repeatSystemSignatures=repeat)
            val page = TGLayoutCalculator(config).calculate(data,404f)
            val flat = page.staves.flatMap { it.measures }
            assertTrue(page.staves.any { row -> row.measures.map { it.measureIndex }.containsAll(listOf(0,1)) })
            val track = factory.newTrack()
            flat.forEach { m -> track.addMeasure(object : TGMeasureImpl(factory.newHeader()) {
                override fun getWidth(layout: TGLayout?) = m.bounds.width
                override fun getMaxY() = 0f
                override fun getMinY() = 0f
                override fun isLineBreak() = m.isFirstFragment && m.measure.lineBreak
                override fun registerSpacing(layout: TGLayout?, spacing: TGTrackSpacing?) = Unit
            }) }
            val original = TGLayoutVertical(null,TGLayout.DISPLAY_SCORE or TGLayout.DISPLAY_TABLATURE)
            TGLayoutVertical::class.java.getDeclaredField("maximumWidth").apply { isAccessible=true }
                .setFloat(original,404-2*config.pagePadding)
            val method = TGLayoutVertical::class.java.methods.single { it.name == "getTempLines" }
            val expected = mutableListOf<List<Int>>()
            var from = 0
            while (from < flat.size) {
                val line = method.invoke(original,track,from,TGTrackSpacing(original))
                val field = line.javaClass.getDeclaredField("measures").apply { isAccessible=true }
                @Suppress("UNCHECKED_CAST") val indices = field.get(line) as List<Int>
                expected += indices
                from = indices.last()+1
            }
            var offset = 0
            val actual = page.staves.map { row -> List(row.measures.size) { offset++ } }
            assertEquals(expected,actual,"repeatSystemSignatures=$repeat")
        }
    }

    private fun record(draw: (UIPainter) -> Unit): List<TGStrokeLine> {
        val lines = mutableListOf<TGStrokeLine>()
        var position = Offset.Zero
        var width = 0f
        val painter = Proxy.newProxyInstance(UIPainter::class.java.classLoader,arrayOf(UIPainter::class.java)) { _,method,args ->
            when (method.name) {
                "setLineWidth" -> width = (args!![0] as Number).toFloat()
                "moveTo" -> position = Offset((args!![0] as Number).toFloat(),(args[1] as Number).toFloat())
                "lineTo" -> {
                    val end = Offset((args!![0] as Number).toFloat(),(args[1] as Number).toFloat())
                    lines += TGStrokeLine(position,end,width)
                    position = end
                }
            }
            null
        } as UIPainter
        draw(painter)
        return lines
    }
}
