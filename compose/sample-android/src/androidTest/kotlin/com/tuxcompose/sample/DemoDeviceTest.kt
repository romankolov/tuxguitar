// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuxcompose.demo.*
import com.tuxcompose.component.TablatureState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class DemoDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun awaitDemo() { compose.waitUntil(60_000) { compose.onAllNodesWithTag("next").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() } } }
    @Test fun opensRealGuitarProAndNavigatesAndCustomizes() {
        awaitDemo()
        compose.onNodeWithTag("import-status").assertTextContains("Guitar Pro",substring=true)
        screenshot("paper-score")
        compose.onNodeWithTag("next").performClick()
        compose.onNodeWithTag("cursor-tick").assertTextEquals("Tick 1440")
        compose.onNodeWithTag("previous").performClick()
        compose.onNodeWithTag("cursor-tick").assertTextEquals("Tick 960")
        compose.onNodeWithTag("mode-Tablature").performClick()
        compose.onNodeWithTag("zoom-in").performClick()
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.onNodeWithTag("cursor-style").performScrollTo().assertTextContains("Capsule",substring=true)
        repeat(4) { compose.onNodeWithTag("cursor-style").performScrollTo().performClick() }
        compose.onNodeWithTag("cursor-style").assertTextContains("Capsule",substring=true)
        compose.onNodeWithTag("palette").performScrollTo().performClick()
        screenshot("dark-tablature")
        compose.onNodeWithTag("score-canvas").assertIsDisplayed()
        compose.onNodeWithTag("orientation").performScrollTo().performClick()
        compose.onNodeWithTag("score-canvas").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("stop").performClick()
        compose.onNodeWithTag("cursor-tick").assertTextEquals("Tick 960")
    }
    private fun screenshot(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        compose.onNodeWithTag("score-canvas").captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
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
