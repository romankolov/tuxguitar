// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo.synth

import android.content.Context
import java.io.File

/** Demo-only pull synthesizer. Rendering, MIDI, bank loading and teardown share one monitor. */
class FluidSynth(val sampleRate: Int = 44100) : AutoCloseable {
    init { require(sampleRate in 22050..96000) }
    private val lock=Any()
    private var handle=nativeCreate(sampleRate).also { check(it!=0L) { "Unable to initialize FluidSynth" } }
    val version: String get()=nativeVersion()
    fun loadSoundFont(file: File) = synchronized(lock) {
        checkOpen()
        require(file.isFile && file.length() in 12..268_435_456L) { "Choose a SoundFont smaller than 256 MiB" }
        check(nativeLoad(handle,file.absolutePath)>=0) { "FluidSynth could not load this SoundFont" }
    }
    fun midi(status: Int, a: Int, b: Int=0) = synchronized(lock) {
        checkOpen()
        require(status in 0x80..0xef && a in 0..127 && b in 0..127)
        nativeMidi(handle,status,a,b)
    }
    fun effects(reverb: Boolean, chorus: Boolean) = synchronized(lock) { checkOpen(); nativeEffects(handle,reverb,chorus) }
    fun render(pcm: ShortArray) = synchronized(lock) {
        checkOpen()
        require(pcm.isNotEmpty() && pcm.size%2==0 && pcm.size<=131072)
        check(nativeRender(handle,pcm)==0) { "FluidSynth PCM rendering failed" }
    }
    fun panic() = synchronized(lock) { if (handle!=0L) nativePanic(handle) }
    override fun close() = synchronized(lock) { if (handle!=0L) { nativeClose(handle); handle=0L } }
    private fun checkOpen()=check(handle!=0L) { "Synthesizer is closed" }
    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeLoad(handle: Long, path: String): Int
    private external fun nativeMidi(handle: Long, status: Int, a: Int, b: Int)
    private external fun nativeEffects(handle: Long, reverb: Boolean, chorus: Boolean)
    private external fun nativeRender(handle: Long, pcm: ShortArray): Int
    private external fun nativePanic(handle: Long)
    private external fun nativeVersion(): String
    private external fun nativeClose(handle: Long)
    companion object {
        init { System.loadLibrary("tuxfluidsynth") }
        /** Copy once from APK assets to app-private storage, entirely offline. Call off the UI thread. */
        @Synchronized fun defaultSoundFont(context: Context): File {
            val file=File(context.filesDir,"synth/GeneralUser-GS-2.0.3.sf2")
            if (file.isFile && file.length()==32319396L) return file
            file.parentFile!!.mkdirs()
            val temporary=File(file.parentFile,"GeneralUser-GS.part")
            try {
                context.assets.open("soundbank/GeneralUser-GS.sf2").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length()==32319396L) { "Bundled SoundFont is incomplete" }
                check(temporary.renameTo(file)) { "Could not install bundled SoundFont" }
            } finally { temporary.delete() }
            return file
        }
    }
}
