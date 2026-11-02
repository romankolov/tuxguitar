// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame-clock and real-pointer regressions for fitting, cursor motion and cooperative follow. */
@RunWith(AndroidJUnit4::class)
class ViewportMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun song(measures: Int = 40, denominator: Int = 8): TGSong {
        val duration = TGDuration(denominator)
        return TGSong(tracks=listOf(TGTrack(1,"Guitar",listOf(64,59,55,50,45,40),
            List(measures) { mi ->
                val start = 960L+mi*3840L
                TGMeasure(mi+1,TGMeasureHeader(),List((3840/duration.ticks).toInt()) { bi ->
                    TGBeat(start+bi*duration.ticks,duration,listOf(TGVoice(notes=listOf(TGNote(0,1)))))
                },start)
            })))
    }

    private fun host(state: TablatureState, cursor: State<TGCursorConfig>,
        config: TGLayoutConfig = TGLayoutConfig(), onLayout: (TGSongLayout) -> Unit = {}) {
        compose.setContent {
            Box(Modifier.size(320.dp,180.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("viewport"),config,cursor.value,onLayout=onLayout)
            }
        }
        compose.waitForIdle()
    }

    @Test fun fittedPageHasNoHorizontalScrollAtNormalAndZoomedDensity() {
        val state = TablatureState(song(4,64),960)
        var layout: TGSongLayout? = null
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(320.dp,180.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("viewport"),cursorConfig=TGCursorConfig(follow=false),onLayout={ layout=it })
            }
        }
        for (zoom in listOf(1f,1.75f,2.5f)) {
            compose.runOnIdle { state.setZoom(zoom) }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("viewport").fetchSemanticsNode().size.width
            compose.runOnIdle {
                assertEquals(pixels/(density*zoom),layout!!.width,.01f)
                assertEquals(0f,state.maxScrollX,0f)
                state.scrollBy(x=100f)
                assertEquals(0f,state.scrollX,0f)
            }
        }
    }

    @Test fun densePageKeepsNormalGlyphSizesAndCanSelectTheWrappedContinuation() {
        val normal = song(1,4).tracks[0].measures[0]
        val fast = song(1,64).tracks[0].measures[0]
        val dense = TGMeasure(2,TGMeasureHeader(),fast.beats.map { it.copy(start=it.start+3840) },4800)
        val data = TGSong(tracks=listOf(TGTrack(1,"Guitar",listOf(64,59,55,50,45,40),listOf(normal,dense))))
        val state = TablatureState(data,960)
        var layout: TGSongLayout? = null
        host(state,mutableStateOf(TGCursorConfig(follow=false)),onLayout={ layout=it })
        val fragments = layout!!.staves.flatMap { it.measures }
        val reference = fragments.first().beats.first().voices.first().notes.first()
        val continued = fragments.filter { it.measureIndex == 1 }
        assertTrue("Dense bars must wrap, not shrink",continued.size>1)
        compose.runOnIdle {
            fragments.forEach { m ->
                assertEquals(1f,m.contentScale,0f)
                assertEquals(50f,m.tabBottom!!-m.tabTop!!,.01f)
                m.beats.flatMap { it.voices }.flatMap { it.notes }.forEach { note ->
                    assertEquals(reference.tabBounds!!.width,note.tabBounds!!.width,.01f)
                    assertEquals(reference.tabBounds.height,note.tabBounds.height,.01f)
                    assertEquals(reference.scoreBounds!!.width,note.scoreBounds!!.width,.01f)
                    assertEquals(reference.scoreBounds.height,note.scoreBounds.height,.01f)
                }
            }
        }
        val target = continued.last()
        val beat = target.beats.first()
        val point = beat.voices.first().notes.first().tabBounds!!.center
        compose.runOnIdle { state.scrollTo(y=target.bounds.top) }
        compose.waitForIdle()
        val factor = compose.onNodeWithTag("viewport").fetchSemanticsNode().size.width/layout!!.width
        compose.onNodeWithTag("viewport").performTouchInput {
            click(Offset((point.x-state.scrollX)*factor,(point.y-state.scrollY)*factor))
        }
        compose.waitUntil(5000) { state.cursorTick == beat.startTick }
        compose.runOnIdle { assertEquals(1,state.selectedString) }
    }

    @Test fun effectPathsRenderOnBothSidesOfAWrappedRowEvenWhenTheSourceIsOffscreen() {
        val raw = song(1,64)
        val track = raw.tracks[0]
        val m = track.measures[0]
        val beats = m.beats.map { b -> b.copy(voices=b.voices.map { v ->
            v.copy(notes=v.notes.map { it.copy(effects=TGNoteEffects(slide=true)) }.toPersistentList())
        }.toPersistentList()) }
        val data = raw.copy(tracks=listOf(track.copy(measures=listOf(m.copy(beats=beats.toPersistentList())).toPersistentList())).toPersistentList())
        val state = TablatureState(data,960)
        var layout: TGSongLayout? = null
        val config = TGLayoutConfig(style=TGLayoutStyles.Tablature,colors=TGColorPalette.Paper.copy(noteHead=Color.Magenta))
        host(state,mutableStateOf(TGCursorConfig(visible=false,follow=false)),config,onLayout={ layout=it })
        assertTrue(layout!!.staves.size>1)
        val outgoing = layout!!.staves.first().connections.single { it.kind == TGConnectionKind.Slide && it.continuesToNext }
        assertMagentaNear((outgoing.from+outgoing.to)/2f,state,layout!!)
        val row = layout!!.staves[1]
        val incoming = row.connections.single { it.kind == TGConnectionKind.Slide && it.continuesFromPrevious }
        compose.runOnIdle { state.scrollTo(y=row.bounds.top) }
        compose.waitForIdle()
        assertMagentaNear((incoming.from+incoming.to)/2f,state,layout!!)
    }
    private fun assertMagentaNear(point: Offset, state: TablatureState, layout: TGSongLayout) {
        val image = compose.onNodeWithTag("viewport").captureToImage()
        val pixels = image.toPixelMap()
        val factor = image.width/layout.width
        val px = ((point.x-state.scrollX)*factor).toInt()
        val py = ((point.y-state.scrollY)*factor).toInt()
        var count = 0
        for (y in (py-4).coerceAtLeast(0)..(py+4).coerceAtMost(image.height-1))
            for (x in (px-4).coerceAtLeast(0)..(px+4).coerceAtMost(image.width-1)) {
                val c = pixels[x,y]
                if (c.red>.7f && c.blue>.7f && c.green<.4f) count++
            }
        assertTrue("A visible magenta continuation segment must be painted near $point",count>0)
    }

    private fun magentaCursorX(): Float {
        val image = compose.onNodeWithTag("viewport").captureToImage()
        val pixels = image.toPixelMap()
        var sum = 0L
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = pixels[x,y]
            if (c.red > .8f && c.blue > .8f && c.green < .2f) { sum += x; count++ }
        }
        assertTrue("The magenta playhead must be rendered",count>0)
        return sum.toFloat()/count
    }

    @Test fun cursorTweenRendersIntermediatePositionsInsteadOfBeatJumps() {
        val state = TablatureState(song(),960)
        val cursor = mutableStateOf(TGCursorConfig(color=Color.Magenta,follow=false,movementAnimationMillis=240))
        host(state,cursor,TGLayoutConfig(orientation=TGLayoutOrientation.Horizontal))
        val start = magentaCursorX()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.cursorTick=1440 }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val middle = magentaCursorX()
        compose.mainClock.advanceTimeBy(400)
        val end = magentaCursorX()
        assertTrue("Cursor must move forward",end>start+10)
        assertTrue("Cursor must have an intermediate animation frame",middle>start+1 && middle<end-1)
    }

    @Test fun hiddenCursorDoesNotFollowAndVisibleCursorScrollsWithIntermediateFrames() {
        val state = TablatureState(song(),960)
        val cursor = mutableStateOf(TGCursorConfig(visible=false,scrollAnimationMillis=400,movementAnimationMillis=0))
        var layout: TGSongLayout? = null
        host(state,cursor,onLayout={ layout=it })
        compose.runOnIdle { state.cursorTick=960+20*3840L }
        compose.runOnIdle { assertEquals(0f,state.scrollY,0f) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { cursor.value=cursor.value.copy(visible=true) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(112)
        val middle = state.scrollY
        val destination = (layout!!.cursorAt(0,state.cursorTick)!!.beat.bounds.top-8f).coerceIn(0f,state.maxScrollY)
        assertTrue("Follow must animate rather than jump",middle>0f && middle<destination)
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(destination,state.scrollY,.1f) }
    }

    @Test fun systemChangesDoNotResetSuspendedFollowAfterProgrammaticBrowsing() {
        val state = TablatureState(song(),960)
        val cursor = mutableStateOf(TGCursorConfig(smoothScroll=false,followResumeDelayMillis=0,movementAnimationMillis=0))
        var layout: TGSongLayout? = null
        host(state,cursor,onLayout={ layout=it })
        compose.mainClock.autoAdvance = false
        val browsingY = layout!!.staves[5].bounds.top
        compose.runOnIdle { state.scrollTo(y=browsingY) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertTrue(state.isAutoScrollSuspended) }
        for (measure in listOf(30,31,10,0)) {
            compose.runOnIdle { state.cursorTick=960+measure*3840L }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle {
                assertTrue("System changes must preserve manual browsing",state.isAutoScrollSuspended)
                assertEquals(browsingY,state.scrollY,.1f)
            }
        }
        compose.runOnIdle { state.resumeAutoScroll() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertFalse(state.isAutoScrollSuspended)
            assertTrue("An explicit Follow must still work",state.scrollY<browsingY)
        }
    }

    @Test fun draggingInterruptsFollowAndBrowsingAwayDoesNotSnapBack() {
        val state = TablatureState(song(),960)
        val cursor = mutableStateOf(TGCursorConfig(scrollAnimationMillis=400,followResumeDelayMillis=0,movementAnimationMillis=0))
        var layout: TGSongLayout? = null
        host(state,cursor,onLayout={ layout=it })
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.cursorTick=960+15*3840L }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("viewport").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val beforeDrag = state.scrollY
        compose.onNodeWithTag("viewport").performTouchInput {
            moveBy(Offset(0f,-40f),16)
            moveBy(Offset(0f,-40f),16)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("Pointer drag must suspend follow",state.isUserScrolling)
            assertTrue("The gesture must really scroll, not merely hold the pointer: before=$beforeDrag after=${state.scrollY}",state.scrollY>beforeDrag+1)
        }
        val heldY = state.scrollY
        val playingTick = 960+30*3840L
        compose.runOnIdle { state.cursorTick=playingTick }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            assertEquals("Playback must not fight a held drag",heldY,state.scrollY,.1f)
            assertEquals("A drag must not become a long-press selection",playingTick,state.cursorTick)
        }
        compose.onNodeWithTag("viewport").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(4000)
        compose.runOnIdle {
            assertFalse(state.isUserScrolling)
            assertEquals("Releasing a drag must not select a beat",playingTick,state.cursorTick)
            assertTrue("Browsing away must remain suspended: before=$beforeDrag held=$heldY tick=${state.cursorTick}, scroll=${state.scrollY}, cursor=${layout!!.cursorAt(0,state.cursorTick)?.beat?.bounds}",state.isAutoScrollSuspended)
        }
        val browsingY = state.scrollY
        compose.runOnIdle { state.cursorTick+=100 }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(browsingY,state.scrollY,.1f); state.resumeAutoScroll() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(800)
        val destination = (layout!!.cursorAt(0,state.cursorTick)!!.beat.bounds.top-8f).coerceIn(0f,state.maxScrollY)
        compose.runOnIdle {
            assertEquals(destination,state.scrollY,.1f)
            assertFalse(state.isAutoScrollSuspended)
        }
    }
}
