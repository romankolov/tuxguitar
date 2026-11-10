// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuxcompose.demo.synth.FluidSynth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class FluidSynthDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun engine()=FluidSynth().apply { loadSoundFont(FluidSynth.defaultSoundFont(context)) }
    private fun energy(pcm: ShortArray)=pcm.sumOf { it.toDouble()*it }
    private fun render(synth: FluidSynth, frames: Int=4096)=ShortArray(frames*2).also(synth::render)

    @Test fun officialEngineAndFullBankRenderGuitarBassPianoFluteAndDrums() {
        engine().use { synth ->
            assertEquals("2.6.2",synth.version)
            synth.effects(false,false)
            for (program in listOf(0,25,33,73)) {
                synth.panic(); synth.midi(0xC0,program); synth.midi(0x90,64,110)
                val pcm=render(synth)
                assertTrue("GM program $program must produce PCM",energy(pcm)>100_000)
                assertTrue("Single-note output should retain headroom",pcm.count { abs(it.toInt())>=32760 }<pcm.size/100)
            }
            synth.panic(); synth.midi(0xC9,0); synth.midi(0x99,38,110)
            assertTrue("Channel 10 must have a drum kit",energy(render(synth))>100_000)
        }
    }
    @Test fun reverbProducesADecayingTailRatherThanAnInertControl() {
        fun tail(wet: Boolean): Double = engine().use { synth ->
            synth.effects(wet,false)
            synth.midi(0xC0,25); synth.midi(0xB0,91,0); synth.midi(0x90,64,110)
            repeat(6) { render(synth) }
            synth.midi(0x80,64,0)
            repeat(10) { render(synth) }
            energy(render(synth))
        }
        val dry=tail(false); val wet=tail(true)
        assertTrue("Reverb tail should exceed dry release: wet=$wet dry=$dry",wet>dry*1.2+1000)
    }
    @Test fun nativeRpnBendRangeAnd14BitPitchBendChangeTheRenderedPitch() {
        engine().use { synth ->
            synth.effects(false,false)
            synth.midi(0xC0,73)
            synth.midi(0xB0,101,0); synth.midi(0xB0,100,0); synth.midi(0xB0,6,12)
            synth.midi(0xB0,101,127); synth.midi(0xB0,100,127)
            synth.midi(0x90,60,100)
            repeat(3) { render(synth) }
            val normal=frequency(render(synth))
            synth.midi(0xE0,0,96) // centre + half the positive range = six semitones
            repeat(3) { render(synth) }
            val bent=frequency(render(synth))
            assertTrue("Expected a six-semitone bend, not a lost/miscentred bend ($normal -> $bent)",bent/normal in 1.35..1.48)
        }
    }
    @Test fun chorusToggleChangesStereoPcmAndCanBeTurnedOffAgain() {
        fun sample(chorus: Boolean)=engine().use { synth ->
            synth.effects(false,chorus); synth.midi(0xC0,73); synth.midi(0xB0,93,0); synth.midi(0x90,64,110)
            repeat(6) { render(synth) }; render(synth)
        }
        val dry=sample(false); val wet=sample(true)
        val difference=dry.indices.sumOf { val d=wet[it].toDouble()-dry[it]; d*d }
        assertTrue("Chorus must change rendered PCM",difference>energy(dry)*.001)
        engine().use { synth -> synth.effects(true,true); synth.effects(false,false); synth.close(); synth.close() }
    }
    @Test fun invalidSoundfontDoesNotDestroyThePreviousBankAndClosedHandlesAreSafe() {
        val invalid=File.createTempFile("invalid-",".sf2",context.cacheDir).apply { writeText("not a SoundFont header and not a MIDI file") }
        try {
            engine().use { synth ->
                try { synth.loadSoundFont(invalid); fail("Invalid bank was accepted") } catch (_: IllegalStateException) { }
                synth.effects(false,false); synth.midi(0x90,64,110)
                assertTrue(energy(render(synth))>100_000)
                synth.close(); synth.close()
                try { render(synth); fail("Closed synthesizer accepted a render") } catch (_: IllegalStateException) { }
            }
        } finally { invalid.delete() }
    }
    private fun frequency(pcm: ShortArray): Double {
        // Narrow fundamental band for C4 / F#4 on the monophonic flute, not a broad pitch detector.
        val samples=DoubleArray(pcm.size/2) { pcm[it*2].toDouble() }
        return (220..420 step 2).maxBy { hz ->
            val angle=2*PI*hz/44100
            var real=0.0; var imaginary=0.0
            samples.forEachIndexed { i,v ->
                val window=.5-.5*cos(2*PI*i/(samples.size-1))
                real+=v*window*cos(angle*i); imaginary+=v*window*sin(angle*i)
            }
            real*real+imaginary*imaginary
        }.toDouble()
    }
}
