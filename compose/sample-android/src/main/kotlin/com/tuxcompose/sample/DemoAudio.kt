// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import app.tuxguitar.android.sound.TGSourceDataLine
import app.tuxguitar.gm.GMChannelRouter
import app.tuxguitar.gm.port.GMChannel
import app.tuxguitar.gm.port.GMReceiver
import app.tuxguitar.song.models.TGSong
import com.tuxcompose.demo.DemoMidiEvent
import media.sound.SF2Soundbank
import media.sound.SoftSynthesizer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** Android-only demo sink. Neither this class nor any synthesizer is a core dependency. */
class DemoAudio(private val context: Context) : GMReceiver, AutoCloseable {
    private var synth: SoftSynthesizer? = null
    private val channels = mutableMapOf<Int,GMChannel>()
    var external: ((ByteArray) -> Unit)? = null
    fun open(song: TGSong) {
        if (synth == null) {
            val next = SoftSynthesizer()
            val format = AudioFormat(44100f,16,2,true,false)
            val line = TGSourceDataLine(DataLine.Info(SourceDataLine::class.java,format))
            try {
                line.open(format)
                next.open(line,mapOf<String,Any>("load default soundbank" to false))
                synth = next
            } catch (e: Exception) { runCatching { next.close() }; runCatching { line.close() }; throw e }
        }
        sendAllNotesOff()
        channels.clear()
        val router = GMChannelRouter()
        val banks = song.channels.asSequence().toList()
        banks.forEach { native ->
            val file = "soundbank/instrument-${native.bank}-${native.program}.sf2"
            val fallback = if (native.isPercussionChannel) "soundbank/instrument-128-0.sf2" else "soundbank/instrument-0-${native.program}.sf2"
            val stream = runCatching { context.assets.open(file) }.getOrElse { context.assets.open(fallback) }
            stream.use { synth!!.loadAllInstruments(SF2Soundbank(it)) }
            val channel = GMChannel(native.channelId,router,this)
            channels[native.channelId] = channel
            channel.sendControlChange(0,native.bank.toInt())
            channel.sendProgramChange(native.program.toInt())
            channel.sendControlChange(7,native.volume.toInt())
            channel.sendControlChange(10,native.balance.toInt())
        }
        // Re-emit configuration if routes were displaced by channel parameters.
        channels.values.forEach { it.sendProgramUpdated() }
    }
    fun dispatch(e: DemoMidiEvent) {
        val channel = channels[e.channel] ?: return
        when (e.kind) {
            DemoMidiEvent.Kind.NoteOn -> channel.sendNoteOn(e.a,e.b,e.voice,e.bend)
            DemoMidiEvent.Kind.NoteOff -> channel.sendNoteOff(e.a,e.b,e.voice,e.bend)
            DemoMidiEvent.Kind.Control -> channel.sendControlChange(e.a,e.b)
            DemoMidiEvent.Kind.Program -> channel.sendProgramChange(e.a)
            DemoMidiEvent.Kind.Pitch -> channel.sendPitchBend(e.a,e.voice,e.bend)
            DemoMidiEvent.Kind.Tempo -> Unit
        }
    }
    override fun sendAllNotesOff() {
        synth?.channels?.forEach { it.allSoundOff(); it.allNotesOff(); it.pitchBend = 8192 }
        repeat(16) { channel -> sendExternal(0xB0,channel,123,0) }
    }
    override fun sendNoteOn(channel: Int, key: Int, velocity: Int) { synth?.channels?.getOrNull(channel)?.noteOn(key,velocity); sendExternal(0x90,channel,key,velocity) }
    override fun sendNoteOff(channel: Int, key: Int, velocity: Int) { synth?.channels?.getOrNull(channel)?.noteOff(key,velocity); sendExternal(0x80,channel,key,velocity) }
    override fun sendProgramChange(channel: Int, value: Int) { synth?.channels?.getOrNull(channel)?.programChange(value); sendExternal(0xC0,channel,value) }
    override fun sendControlChange(channel: Int, controller: Int, value: Int) { synth?.channels?.getOrNull(channel)?.controlChange(controller,value.coerceIn(0,127)); sendExternal(0xB0,channel,controller,value.coerceIn(0,127)) }
    // TuxGuitar bend messages are 7-bit centered at 64; Gervill expects 14-bit centered at 8192.
    override fun sendPitchBend(channel: Int, value: Int) { synth?.channels?.getOrNull(channel)?.pitchBend = value * 128; sendExternal(0xE0,channel,0,value) }
    private fun sendExternal(status: Int, channel: Int, a: Int, b: Int? = null) {
        if (channel !in 0..15) return
        external?.invoke(if (b == null) byteArrayOf((status or channel).toByte(),a.toByte()) else byteArrayOf((status or channel).toByte(),a.toByte(),b.toByte()))
    }
    override fun close() { sendAllNotesOff(); synth?.close(); synth=null; channels.clear() }
}
