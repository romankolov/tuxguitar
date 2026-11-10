// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// v0.1.4 extension regressions. Native defaults have separate full-Java differential tests.
private val adaptiveConfig = TGLayoutConfig(spacingMode=TGSpacingMode.Adaptive,compact=false,
    pagePadding=12f,pageRightPadding=12f,systemSpacing=20f,showChords=true,
    typography=TGTypography(chordSize=9f,graceSize=6.3f),respectSystemFontScale=true)


/** Real Android text metrics, Canvas pixels and pointer transforms after denser reflow. */
@RunWith(AndroidJUnit4::class)
class MeasureSpacingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun song(count: Int = 6, largeFret: Boolean = false): TGSong = TGSong(tracks=listOf(
        TGTrack(1,"Guitar",listOf(64,59,55,50,45,40),List(count) { mi ->
            val start = 960+mi*3840L
            TGMeasure(mi+1,TGMeasureHeader(),List(4) { bi ->
                TGBeat(start+bi*960,voices=listOf(TGVoice(notes=listOf(TGNote(if (largeFret && bi == 2) 257 else 5,1)))),
                    chord=if (bi == 0) TGChord("Am",listOf(0,1,2,2,0,-1)) else null,
                    stroke=if (bi == 1) TGStroke(TGStrokeDirection.Up,16) else TGStroke())
            },start)
        })))

    @Test fun balancedRealFontReflowFitsAnotherWholeBarAndPreservesStrumPixelsAndTaps() {
        val state = TablatureState(song(),960)
        val config = mutableStateOf(adaptiveConfig.copy(style=TGLayoutStyles.Tablature,justifySystems=false,optimizeSpacing=false,
            colors=TGColorPalette.Paper.copy(noteHead=Color.Magenta)))
        var layout: TGSongLayout? = null
        compose.setContent {
            Box(Modifier.size(320.dp,240.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("spacing-viewport"),config.value,
                    TGCursorConfig(visible=false,follow=false),onLayout={ layout=it })
            }
        }
        compose.waitForIdle()
        val legacy = layout!!
        val originalSize = legacy.staves[0].measures[0].beats[0].voices[0].notes[0].tabBounds!!.size
        assertEquals(1,legacy.staves[0].measures.size)
        compose.runOnIdle { config.value=config.value.copy(optimizeSpacing=true) }
        compose.waitForIdle()
        val page = layout!!
        assertEquals(listOf(0,1),page.staves[0].measures.map { it.measureIndex })
        assertTrue(page.staves.size < legacy.staves.size)
        val row = page.staves[0]
        row.measures.forEach { m ->
            assertEquals(1f,m.contentScale,0f)
            assertEquals(50f,m.tabBottom!!-m.tabTop!!,.01f)
            m.beats.forEach { b ->
                val bounds = b.voices[0].notes[0].tabBounds!!
                assertEquals(originalSize.width,bounds.width,.01f)
                assertEquals(originalSize.height,bounds.height,.01f)
            }
        }
        val factor = compose.onNodeWithTag("spacing-viewport").fetchSemanticsNode().size.width/page.width
        val mark = row.measures[1].beats[1].stroke!!
        val sample = (mark.lines[0].from+mark.lines[0].to)/2f
        val pixels = compose.onNodeWithTag("spacing-viewport").captureScoreImage().toPixelMap()
        val px = (sample.x*factor).toInt(); val py = (sample.y*factor).toInt()
        assertTrue("The unscaled strum must still be painted after packing",(-3..3).any { dx -> (-3..3).any { dy ->
            val x = (px+dx).coerceIn(0,pixels.width-1); val y = (py+dy).coerceIn(0,pixels.height-1)
            pixels[x,y].let { it.red>.7f && it.blue>.7f && it.green<.4f }
        } })
        val target = row.measures[1].beats[2]
        val point = target.voices[0].notes[0].tabBounds!!.center
        compose.onNodeWithTag("spacing-viewport").performTouchInput { click(point*factor) }
        compose.waitUntil(5000) { state.cursorTick == target.startTick }
        compose.runOnIdle {
            assertEquals(0,state.selection!!.trackIndex)
            assertEquals(target.startTick,state.selection!!.startTick)
            assertEquals(1,state.selectedString)
            assertEquals(1,page.cursorAt(0,state.cursorTick)!!.measureIndex)
            assertEquals(0f,state.maxScrollX,0f)
        }
    }

    @Test fun tappingTheEdgeOfALargeFontFretDoesNotSelectItsCloserNeighbor() {
        val state = TablatureState(song(1,largeFret=true),960)
        var layout: TGSongLayout? = null
        val config = adaptiveConfig.copy(style=TGLayoutStyles.Tablature,justifySystems=false,
            typography=TGTypography(noteSize=28f))
        compose.setContent {
            Box(Modifier.size(320.dp,240.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("spacing-viewport"),config,
                    TGCursorConfig(visible=false,follow=false),onLayout={ layout=it })
            }
        }
        compose.waitForIdle()
        val page = layout!!
        val m = page.staves[0].measures[0]
        val target = m.beats[2]
        val box = target.voices[0].notes[0].tabBounds!!
        val point = Offset(box.left+3,box.center.y)
        assertTrue("Fixture must exercise an ink hit outside the nearest-onset cell",abs(point.x-m.beats[1].x) < abs(point.x-target.x))
        val factor = compose.onNodeWithTag("spacing-viewport").fetchSemanticsNode().size.width/page.width
        compose.onNodeWithTag("spacing-viewport").performTouchInput { click(point*factor) }
        compose.waitUntil(5000) { state.cursorTick == target.startTick }
        compose.runOnIdle { assertEquals(1,state.selectedString); assertEquals(0f,state.maxScrollX,0f) }
    }
}
