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
import media.sound.SF2Soundbank
import media.sound.SoftSynthesizer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat

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
        compose.onNodeWithTag("palette").performClick()
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
    @Test fun gervillRendersNonSilentPcmFromBundledSoundfont() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val synth=SoftSynthesizer()
        try {
            val stream=synth.openStream(AudioFormat(44100f,16,2,true,false),mapOf<String,Any>("load default soundbank" to false))
            context.assets.open("soundbank/instrument-0-25.sf2").use { assertTrue(synth.loadAllInstruments(SF2Soundbank(it))) }
            synth.channels[0].programChange(25)
            synth.channels[0].noteOn(64,110)
            val bytes=ByteArray(44100)
            var offset=0
            while (offset<bytes.size) { val n=stream.read(bytes,offset,bytes.size-offset); if (n<0) break; offset+=n }
            assertTrue("Synthesized PCM must not be silent",bytes.any { it.toInt()!=0 })
            synth.channels[0].allSoundOff()
            stream.close()
        } finally { synth.close() }
    }
}
