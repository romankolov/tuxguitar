// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper

/** Optional Android MIDI hardware output; no permissions, USB dialogs or recording are requested. */
class ExternalMidi(context: Context, private val audio: DemoAudio) : AutoCloseable {
    private val manager = context.getSystemService(MidiManager::class.java)
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var generation = 0
    fun devices(): List<MidiDeviceInfo> = manager?.devices?.filter { it.inputPortCount > 0 } ?: emptyList()
    fun connect(info: MidiDeviceInfo, result: (String?) -> Unit) {
        close()
        val request = generation
        manager?.openDevice(info, { next ->
            if (request != generation) { next?.close(); return@openDevice }
            device=next
            port=next?.openInputPort(0)
            if (port == null) { result("Unable to open MIDI input port"); close() }
            else {
                audio.external = { bytes -> runCatching { port?.send(bytes,0,bytes.size) } }
                result(null)
            }
        },Handler(Looper.getMainLooper()))
    }
    override fun close() { generation++; if (audio.external != null) audio.sendAllNotesOff(); audio.external=null; port?.close(); port=null; device?.close(); device=null }
}
