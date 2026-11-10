// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import app.tuxguitar.gm.GMChannelRouter
import app.tuxguitar.gm.port.GMChannel
import app.tuxguitar.gm.port.GMReceiver
import app.tuxguitar.song.models.TGSong
import com.tuxcompose.demo.DemoMidiEvent
import com.tuxcompose.demo.synth.FluidSynth
import java.io.File
import java.io.InputStream

/** Android-only demo sink. The core library has no reference to MIDI, JNI or AudioTrack. */
class DemoAudio(private val context: Context) : GMReceiver, AutoCloseable {
    private var synth: FluidSynth? = null
    private var output: AudioTrack? = null
    private var pump: Thread? = null
    @Volatile private var running=false
    @Volatile private var outputGeneration=0
    @Volatile var failure: String?=null
        private set
    private var selectedFont: File?=null
    private var reverb=true
    private var chorus=true
    private val channels=mutableMapOf<Int,GMChannel>()
    @Volatile var external: ((ByteArray) -> Unit)?=null

    @Synchronized fun open(song: TGSong) {
        if (failure!=null) { pauseOutput(); synth?.close(); synth=null; failure=null }
        if (synth==null) {
            val next=FluidSynth()
            try {
                next.loadSoundFont(selectedFont ?: FluidSynth.defaultSoundFont(context))
                next.effects(reverb,chorus)
                synth=next
            } catch (e: Exception) { next.close(); throw e }
        }
        sendAllNotesOff()
        channels.clear()
        val router=GMChannelRouter()
        song.channels.forEach { native ->
            val channel=GMChannel(native.channelId,router,this)
            channels[native.channelId]=channel
            // GMChannelRouter handles percussion banks and independent bend/voice channels.
            channel.sendControlChange(0,native.bank.toInt())
            channel.sendProgramChange(native.program.toInt())
            channel.sendControlChange(7,native.volume.toInt())
            channel.sendControlChange(10,native.balance.toInt())
            channel.sendControlChange(91,native.reverb.toInt())
            channel.sendControlChange(93,native.chorus.toInt())
        }
        channels.values.forEach { it.sendProgramUpdated() }
        startOutput()
    }
    private fun startOutput() {
        if (running) return
        val engine=requireNotNull(synth)
        val minimum=AudioTrack.getMinBufferSize(engine.sampleRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)
        check(minimum>0) { "AudioTrack does not support stereo PCM at ${engine.sampleRate} Hz" }
        val builder=AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(engine.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum*2,4096))
        if (Build.VERSION.SDK_INT>=26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track=builder.build()
        try { check(track.state==AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }; track.play() }
        catch (e: Exception) { track.release(); throw e }
        output=track; running=true; failure=null
        val generation=++outputGeneration
        pump=Thread({
            val pcm=ShortArray(1024) // 512 stereo frames: 11.6 ms; never lock across blocking device I/O.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running && generation==outputGeneration) {
                    engine.render(pcm)
                    var offset=0
                    while (running && generation==outputGeneration && offset<pcm.size) {
                        val n=track.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_BLOCKING)
                        check(n>0) { "AudioTrack write failed ($n)" }
                        offset+=n
                    }
                }
            } catch (e: Exception) { if (running && generation==outputGeneration) failure=e.message ?: "PCM output failed" }
        },"FluidSynth PCM").apply { start() }
    }
    /** Stop the pull thread/device during pause/backgrounding; keep the decoded bank for resume. */
    @Synchronized fun pauseOutput() {
        running=false; outputGeneration++
        output?.let { track -> runCatching { track.pause(); track.flush(); track.stop() }; track.release() }
        output=null
        pump?.join(1500); pump=null
    }
    @Synchronized fun setEffects(reverb: Boolean, chorus: Boolean) {
        this.reverb=reverb; this.chorus=chorus
        synth?.effects(reverb,chorus)
    }
    /** Transactional user-selected SF2/SF3 loading. No runtime downloads or shared-storage paths. */
    @Synchronized fun loadSoundFont(input: InputStream) {
        val temporary=File.createTempFile("soundfont-",".sf2",context.cacheDir)
        try {
            temporary.outputStream().use { out ->
                val buffer=ByteArray(65536); var size=0L
                while (true) {
                    val n=input.read(buffer); if (n<0) break
                    size+=n; require(size<=268_435_456L) { "SoundFont exceeds 256 MiB" }; out.write(buffer,0,n)
                }
            }
            // Validate even when audio has not been opened yet; preserve the old bank on failure.
            val engine=synth ?: FluidSynth()
            try { engine.loadSoundFont(temporary); engine.effects(reverb,chorus) }
            catch (e: Exception) { if (synth==null) engine.close(); throw e }
            val old=selectedFont
            selectedFont=temporary; synth=engine
            old?.delete()
            channels.values.forEach { it.sendProgramUpdated() }
        } catch (e: Exception) { temporary.delete(); throw e }
    }
    @Synchronized fun resetSoundFont() {
        synth?.loadSoundFont(FluidSynth.defaultSoundFont(context))
        selectedFont?.delete(); selectedFont=null
        channels.values.forEach { it.sendProgramUpdated() }
    }
    @Synchronized fun dispatch(e: DemoMidiEvent) {
        val channel=channels[e.channel] ?: return
        when (e.kind) {
            DemoMidiEvent.Kind.NoteOn -> channel.sendNoteOn(e.a,e.b,e.voice,e.bend)
            DemoMidiEvent.Kind.NoteOff -> channel.sendNoteOff(e.a,e.b,e.voice,e.bend)
            DemoMidiEvent.Kind.Control -> channel.sendControlChange(e.a,e.b)
            DemoMidiEvent.Kind.Program -> channel.sendProgramChange(e.a)
            DemoMidiEvent.Kind.Pitch -> channel.sendPitchBend(e.a,e.voice,e.bend)
            DemoMidiEvent.Kind.Tempo -> Unit
        }
    }
    @Synchronized override fun sendAllNotesOff() {
        synth?.panic()
        repeat(16) { channel -> sendExternal(0xB0,channel,120,0); sendExternal(0xB0,channel,123,0); sendExternal(0xE0,channel,0,64) }
    }
    @Synchronized override fun sendNoteOn(channel: Int, key: Int, velocity: Int) { synth?.midi(0x90 or channel,key,velocity); sendExternal(0x90,channel,key,velocity) }
    @Synchronized override fun sendNoteOff(channel: Int, key: Int, velocity: Int) { synth?.midi(0x80 or channel,key,velocity); sendExternal(0x80,channel,key,velocity) }
    @Synchronized override fun sendProgramChange(channel: Int, value: Int) { synth?.midi(0xC0 or channel,value); sendExternal(0xC0,channel,value) }
    @Synchronized override fun sendControlChange(channel: Int, controller: Int, value: Int) {
        // Upstream's bank 128 is a percussion-routing sentinel, not an on-wire CC value.
        val wire=if (controller==0 && value==128) 0 else value.coerceIn(0,127)
        synth?.midi(0xB0 or channel,controller,wire); sendExternal(0xB0,channel,controller,wire)
    }
    // TuxGuitar bends are 7-bit centred at 64; standard MIDI/FluidSynth uses 14-bit 8192.
    @Synchronized override fun sendPitchBend(channel: Int, value: Int) { synth?.midi(0xE0 or channel,0,value.coerceIn(0,127)); sendExternal(0xE0,channel,0,value.coerceIn(0,127)) }
    private fun sendExternal(status: Int, channel: Int, a: Int, b: Int?=null) {
        if (channel !in 0..15) return
        external?.invoke(if (b==null) byteArrayOf((status or channel).toByte(),a.toByte()) else byteArrayOf((status or channel).toByte(),a.toByte(),b.toByte()))
    }
    @Synchronized override fun close() {
        sendAllNotesOff(); pauseOutput(); synth?.close(); synth=null; channels.clear()
        selectedFont?.delete(); selectedFont=null
    }
}
