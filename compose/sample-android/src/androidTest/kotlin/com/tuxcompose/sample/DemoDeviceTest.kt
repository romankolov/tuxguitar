// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuxcompose.demo.*
import com.tuxcompose.component.TablatureState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class DemoDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun awaitDemo() { compose.waitUntil(60_000) { compose.onAllNodesWithTag("next").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() } } }
    @Before fun resetWorkspace() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("studio-settings",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        compose.activityRule.scenario.recreate()
        awaitDemo()
    }
    private fun assertTick(tick: Long) {
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("score-details").performClick()
        compose.onNodeWithTag("cursor-tick").assertTextEquals("Tick $tick")
        compose.onNodeWithText("Done").performClick()
    }
    @Test fun opensLosslessShowcaseAndNavigatesAndCustomizes() {
        compose.onNodeWithTag("import-status").assertTextContains("TuxGuitar",substring=true)
        screenshot("studio-home")
        compose.onNodeWithTag("next").performClick(); assertTick(1440)
        compose.onNodeWithTag("previous").performClick(); assertTick(960)
        compose.onNodeWithTag("mode-Tablature").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("zoom-in").performScrollTo().performClick()
        compose.onNodeWithTag("settings-Cursor").performScrollTo().performClick()
        compose.onNodeWithTag("cursor-Capsule").assertIsOn()
        for (style in com.tuxcompose.component.CursorStyle.entries) {
            compose.onNodeWithTag("cursor-${style.name}").performScrollTo().performClick().assertIsOn()
        }
        compose.onNodeWithTag("settings-Appearance").performScrollTo().performClick()
        compose.onNodeWithTag("theme-Dark").performScrollTo().performClick().assertIsOn()
        screenshot("studio-settings-dark")
        compose.onNodeWithTag("settings-close").performClick()
        screenshot("studio-dark")
        compose.onNodeWithTag("score-canvas").assertIsDisplayed()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("orientation-Horizontal").performScrollTo().performClick()
        compose.onNodeWithTag("settings-close").performClick()
        compose.onNodeWithTag("score-canvas").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("stop").performClick(); assertTick(960)
    }
    @Test fun trackSelectionAndGroupedSoundSettingsRemainReachable() {
        compose.onNodeWithTag("track-picker").performClick()
        compose.onNodeWithTag("track-1").performClick()
        compose.onNodeWithTag("track-picker").assertTextContains("Fingered bass",substring=true)
        compose.onNodeWithTag("sound-settings").performClick()
        compose.onNodeWithTag("reverb").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("chorus").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("settings-Display").performScrollTo().performClick()
        compose.onNodeWithTag("all-tracks").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("compact-spacing").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("settings-close").performClick()
        compose.onNodeWithTag("score-canvas").assertIsDisplayed()
    }
    @Test fun themeAndCursorPreferencesSurviveRecreation() {
        compose.onNodeWithTag("next").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-Appearance").performScrollTo().performClick()
        compose.onNodeWithTag("theme-Dark").performScrollTo().performClick()
        compose.onNodeWithTag("high-contrast").performScrollTo().performClick()
        compose.onNodeWithTag("settings-Cursor").performScrollTo().performClick()
        compose.onNodeWithTag("smooth-cursor").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("settings-close").performClick()
        compose.activityRule.scenario.recreate(); awaitDemo()
        assertTick(1440)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-Appearance").performScrollTo().performClick()
        compose.onNodeWithTag("theme-Dark").performScrollTo().assertIsOn()
        compose.onNodeWithTag("high-contrast").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings-Cursor").performScrollTo().performClick()
        compose.onNodeWithTag("smooth-cursor").performScrollTo().assertIsOff()
    }
    @Test fun comprehensiveDefaultAndExplicitSectionNavigationWorkWithFollowAndCursorHidden() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("studio-settings",android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("follow",false).putBoolean("cursorVisible",false).commit()
        compose.activityRule.scenario.recreate();awaitDemo()
        val session=androidx.lifecycle.ViewModelProvider(compose.activity)[StudioSession::class.java]
        val score=session.state.song!!
        assertEquals(32,score.tracks[0].measures.size);assertEquals(3,score.tracks.size)
        val decoded=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"atlas.tg")
        assertTrue(decoded.score.tracks[0].measures.flatMap {it.beats}.flatMap {it.voices}.any {it.duration?.doubleDotted==true})
        compose.onNodeWithTag("section-picker").performClick()
        compose.onNodeWithTag("sections-list").performScrollToNode(hasTestTag("section-28"))
        compose.onNodeWithTag("section-28").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(score.tracks[0].measures[28].start,session.state.cursorTick)
            assertTrue("Explicit section navigation must reveal the section",session.state.scrollY>0f)
            assertEquals(0f,session.state.maxScrollX,0f)
        }
        screenshot("studio-finale")
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-Cursor").performClick()
        compose.onNodeWithTag("show-cursor").performScrollTo().assertIsOff()
        compose.onNodeWithTag("follow-setting").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings-close").performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .asImageBitmap().saveScoreCapture(name)
    }
    @Test fun decoderAndLayoutRunOnAndroid() {
        val decoded=TuxGuitarImporter.read(ByteArrayInputStream(DemoSongFactory.guitarPro()),"study.gp5")
        assertEquals(2,decoded.score.tracks.size)
        assertTrue(DemoSequence(decoded.native).events.isNotEmpty())
    }
    @Test fun nativeMidiTransportPlaysThroughAndroidAudioTrack() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val decoded=TuxGuitarImporter.read(ByteArrayInputStream(DemoSongFactory.guitarPro()),"study.gp5")
        val state=TablatureState(decoded.score,960)
        val runtimeScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val player=DemoPlayback(context,runtimeScope,state)
        try {
            compose.runOnIdle { player.load(decoded); player.play() }
            compose.waitUntil(60_000) { player.playing || player.error != null }
            assertNull("Android AudioTrack initialization failed",player.error)
            compose.waitUntil(10_000) { state.cursorTick >= 1100 || player.error != null }
            assertNull("Native MIDI transport failed",player.error)
            assertTrue("Transport must advance the rendering cursor",state.cursorTick >= 1100)
            compose.runOnIdle { player.stop() }
            assertEquals(960L,state.cursorTick)
        } finally {
            compose.runOnIdle { player.dispose() }
            runtimeScope.cancel()
        }
    }
}
