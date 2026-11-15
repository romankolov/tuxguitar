// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuxcompose.component.TablatureState
import com.tuxcompose.demo.ShowcaseSongFactory
import com.tuxcompose.demo.TuxGuitarImporter
import com.tuxcompose.model.TGMeasure
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** The loop is sample UI + sample transport only; the core renderer stays clock-free. */
@RunWith(AndroidJUnit4::class)
class LoopPracticeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun awaitDemo() { compose.waitUntil(60_000) { compose.onAllNodesWithTag("next").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() } } }
    @Before fun resetWorkspace() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("studio-settings",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        compose.activityRule.scenario.recreate()
        awaitDemo()
    }
    private fun session()=androidx.lifecycle.ViewModelProvider(compose.activity)[StudioSession::class.java]
    /** Real hardware capture, preserved for adb pull exactly like the other QA scenes. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .asImageBitmap().saveScoreCapture(name)
    }

    @Test fun loopSheetSelectsWholeBarsAndEnablesTheTransport() {
        compose.onNodeWithTag("loop").performClick()
        compose.onNodeWithTag("loop-sheet").assertIsDisplayed()
        compose.onNodeWithTag("loop-enabled").assertIsOff()
        screenshot("loop-sheet-empty")
        // Choosing a range is the whole gesture: it also turns looping on.
        compose.onNodeWithTag("loop-whole-score").performScrollTo().performClick()
        val studio=session()
        compose.runOnIdle {
            assertTrue("Selecting a range must enable looping",studio.loopEnabled)
            val measures=studio.state.song!!.tracks[0].measures
            val region=requireNotNull(studio.loop)
            assertEquals("Whole-bar range must start at the first bar",measures.first().start,region.startTick)
            assertEquals("Whole-bar range must end with the last bar",measures.last().end,region.endTick)
        }
        screenshot("loop-sheet-range")
        compose.onNodeWithTag("loop-close").performClick()
        compose.onNodeWithTag("loop-status").assertIsDisplayed()
        screenshot("studio-loop")
        compose.onNodeWithTag("loop-off").performClick()
        compose.runOnIdle { assertFalse("The transport exit must disable looping",studio.loopEnabled) }
    }

    @Test fun transportKeepsReturningToTheSelectedRange() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val decoded=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"atlas.tg")
        val state=TablatureState(decoded.score)
        val measure=decoded.score.tracks[0].measures.first()
        val end=minOf(measure.start+960,measure.end)
        assumeTrue("The first bar has no beat-length span to loop",end>measure.start)
        val region=LoopRegion(measure.start,end)
        val runtimeScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val player=DemoPlayback(context,runtimeScope,state)
        try {
            compose.runOnIdle { player.load(decoded); player.setLoop(true,region); player.play() }
            compose.waitUntil(60_000) { player.playing || player.error != null }
            assertNull("Android AudioTrack initialization failed",player.error)
            compose.waitUntil(30_000) { player.loopPasses>=2 || player.error != null }
            assertNull("Looping transport failed",player.error)
            assertTrue("Playback must return to the loop start repeatedly",player.loopPasses>=2)
            val tick=state.cursorTick
            assertTrue("Cursor must stay inside the loop, was $tick",tick>=region.startTick && tick<region.endTick)
        } finally {
            compose.runOnIdle { player.dispose() }
            runtimeScope.cancel()
        }
    }

    @Test fun transportButtonTogglesAnExistingLoopWithoutOpeningTheSheet() {
        compose.onNodeWithTag("loop").performClick()
        compose.onNodeWithTag("loop-whole-score").performScrollTo().performClick()
        compose.onNodeWithTag("loop-close").performClick()
        val studio=session()
        compose.runOnIdle { assertTrue(studio.loopEnabled) }
        compose.onNodeWithTag("loop").performClick()
        compose.runOnIdle { assertFalse("The transport button must pause the loop",studio.loopEnabled) }
        assertTrue("Toggling must not reopen the sheet",compose.onAllNodesWithTag("loop-sheet").fetchSemanticsNodes().isEmpty())
        // The configured range stays on the score and is reopened from the status row.
        compose.onNodeWithTag("loop-status").assertIsDisplayed()
        compose.onNodeWithTag("loop-open").performClick()
        compose.onNodeWithTag("loop-sheet").assertIsDisplayed()
        compose.onNodeWithTag("loop-close").performClick()
        compose.onNodeWithTag("loop").performClick()
        compose.runOnIdle { assertTrue("The transport button must resume the loop",studio.loopEnabled) }
    }

    @Test fun pickingArmsOnTheScoreAndCancelsFromTheTransport() {
        compose.onNodeWithTag("loop").performClick()
        compose.onNodeWithTag("loop-pick").performScrollTo().performClick()
        assertTrue("Picking must dismiss the sheet",compose.onAllNodesWithTag("loop-sheet").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("loop-pick-hint").assertIsDisplayed()
        screenshot("studio-loop-pick")
        compose.onNodeWithTag("loop-pick-cancel").performClick()
        assertEquals(0,compose.onAllNodesWithTag("loop-pick-hint").fetchSemanticsNodes().size)
    }

    @Test fun pickedBarsProduceAnOrderedWholeBarRange() {
        val decoded=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"atlas.tg")
        val measures=decoded.score.tracks[0].measures
        val firstBar=measures[2]
        val lastBar=measures[5]
        val first=requireNotNull(pickLoopRange(measures,null,firstBar.start+1))
        assertFalse("One tap is only a start",first.complete)
        assertEquals(firstBar.start,first.region.startTick)
        assertEquals(firstBar.end,first.region.endTick)
        val second=requireNotNull(pickLoopRange(measures,first.region,lastBar.start))
        assertTrue("The second tap completes the range",second.complete)
        assertEquals(firstBar.start,second.region.startTick)
        assertEquals(lastBar.end,second.region.endTick)
        // Tapping backwards still yields an ordered range.
        val backward=requireNotNull(pickLoopRange(measures,first.region,measures[0].start))
        assertEquals(measures[0].start,backward.region.startTick)
        assertEquals(firstBar.end,backward.region.endTick)
        assertNull(pickLoopRange(listOf<TGMeasure>(),null,0))
    }
}
