// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Default native layout with actual Android fonts, density, zoom, clipping and pointer hits. */
@RunWith(AndroidJUnit4::class)
class NativeSpacingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun song() = TGSong(tracks=listOf(TGTrack(1,"Native spacing",listOf(64,59,55,50,45,40),List(8) { mi ->
        val start = 960+mi*3840L
        var tick = start
        TGMeasure(mi+1,TGMeasureHeader(),listOf(2,4,8,8).map { value ->
            val duration = TGDuration(value)
            TGBeat(tick,duration,listOf(TGVoice(notes=listOf(TGNote(5,1))))).also { tick += duration.ticks }
        },start)
    })))

    @Test fun nativeDefaultsFitTwoMixedDurationBarsWithFixedGlyphsAndWorkAfterZoom() {
        val state = TablatureState(song(),960)
        var page: TGSongLayout? = null
        var factor = 1f
        var clicked: Long? = null
        compose.setContent {
            factor = LocalDensity.current.density*state.zoomScale
            Box(Modifier.size(360.dp,320.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("native-viewport"),TGLayoutConfig(justifySystems=false),
                    TGCursorConfig(visible=false,follow=false),onBeatClicked={ tick,_ -> clicked=tick },onLayout={ page=it })
            }
        }
        compose.waitForIdle()
        val original = page!!
        assertEquals(listOf(0,1),original.staves[0].measures.map { it.measureIndex })
        val m = original.staves[0].measures[0]
        assertTrue(m.compact)
        assertEquals(25f,m.beats[1].x-m.beats[0].x,.01f)
        assertEquals(21f,m.beats[2].x-m.beats[1].x,.01f)
        assertEquals(20f,m.beats[3].x-m.beats[2].x,.01f)
        val originalHead = m.beats[0].voices[0].notes[0].scoreBounds!!
        val note = m.beats[1].voices[0].notes[0].tabBounds!!
        compose.onNodeWithTag("native-viewport").performTouchInput { click(note.center*factor) }
        // detectTapGestures waits out the double-tap zoom window before dispatching a tap.
        compose.waitUntil(5000) { clicked != null }
        assertEquals(m.beats[1].startTick,clicked)
        compose.runOnIdle { state.setZoom(1.5f); clicked=null }
        compose.waitForIdle()
        val zoomed = page!!
        assertEquals(0f,state.maxScrollX,.01f)
        assertEquals(360f/1.5f,zoomed.width,.1f)
        val current = zoomed.staves[0].measures[0]
        assertEquals(1f,current.contentScale,0f)
        val currentHead = current.beats[0].voices[0].notes[0].scoreBounds!!
        assertEquals(originalHead.width,currentHead.width,.001f)
        assertEquals(originalHead.height,currentHead.height,.001f)
        assertEquals(50f,current.tabBottom!!-current.tabTop!!,.01f)
        val target = current.beats[2].voices[0].notes[0].tabBounds!!
        compose.onNodeWithTag("native-viewport").performTouchInput { click(Offset(target.center.x-state.scrollX,target.center.y-state.scrollY)*factor) }
        compose.waitUntil(5000) { clicked != null }
        assertEquals(current.beats[2].startTick,clicked)
        zoomed.staves.flatMap { it.measures }.forEach { assertTrue(it.bounds.right <= zoomed.width+.01f) }
        assertNotNull(compose.onNodeWithTag("native-viewport").captureScoreImage())
    }

    @Test fun nativeFontsIgnoreSystemSpMultiplierUnlessExplicitlyEnabled() {
        val state = TablatureState(song(),960)
        val respect = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        var page: TGSongLayout? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale.value)) {
                Box(Modifier.size(360.dp,320.dp)) {
                    TablatureView(state,Modifier.fillMaxSize(),TGLayoutConfig(respectSystemFontScale=respect.value),
                        TGCursorConfig(visible=false,follow=false),onLayout={ page=it })
                }
            }
        }
        compose.waitForIdle()
        fun bounds() = page!!.staves[0].measures[0].beats[0].voices[0].notes[0].tabBounds!!
        val normal = bounds().size
        compose.runOnIdle { fontScale.value=1.8f }
        compose.waitForIdle()
        assertEquals(normal,bounds().size)
        compose.runOnIdle { respect.value=true }
        compose.waitForIdle()
        assertTrue(bounds().height > normal.height)
        assertEquals(0f,state.maxScrollX,.01f)
    }
}
