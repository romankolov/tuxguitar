// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import androidx.compose.runtime.*
import com.tuxcompose.component.TablatureState
import com.tuxcompose.demo.DemoMidiEvent
import com.tuxcompose.demo.DemoSequence
import com.tuxcompose.demo.ImportedScore
import kotlinx.coroutines.*

/** Monotonic, cancellable demo transport. TuxGuitar produces all musical events and repeat spans. */
@Stable
class DemoPlayback(context: Context, private val scope: CoroutineScope, private val state: TablatureState) {
    val audio = DemoAudio(context.applicationContext)
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var speedPercent by mutableIntStateOf(100)
    private var job: Job? = null
    private var sequence: DemoSequence? = null
    private var source: ImportedScore? = null
    private var position = 0L
    fun load(score: ImportedScore) { pause(); source=score; sequence=null; position=0 }
    fun seek(tick: Long) { val resume = playing; pause(); position=sequence?.seekMicros(tick) ?: 0; if (resume) play() }
    fun play() {
        if (playing || loading) return
        val source = source ?: return
        error=null; loading=true
        job=scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { DemoSequence(source.native,speedPercent).also { audio.open(source.native) } }
                sequence=prepared
                if (position >= prepared.endMicros) position=0
                var index=prepared.events.indexOfFirst { it.micros >= position }.let { if (it < 0) prepared.events.size else it }
                // Restore program/controllers when seeking, but do not replay past note-ons.
                withContext(Dispatchers.IO) {
                    prepared.events.take(index).filter { it.kind in listOf(DemoMidiEvent.Kind.Control,DemoMidiEvent.Kind.Program,DemoMidiEvent.Kind.Pitch) }.forEach(audio::dispatch)
                }
                loading=false; playing=true
                val start=System.nanoTime()-position*1000
                while (isActive && position < prepared.endMicros) {
                    position=(System.nanoTime()-start)/1000
                    withContext(Dispatchers.IO) {
                        while (index < prepared.events.size && prepared.events[index].micros <= position) audio.dispatch(prepared.events[index++])
                    }
                    state.cursor.seek(prepared.scoreTickAt(position))
                    delay(8)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error=e.message ?: "Unable to initialize audio" }
            finally { playing=false; loading=false; withContext(NonCancellable+Dispatchers.IO) { audio.sendAllNotesOff() } }
        }
    }
    fun pause() { job?.cancel(); job=null; playing=false; audio.sendAllNotesOff() }
    fun stop() { pause(); position=0; state.cursor.seek(state.song?.tracks?.firstOrNull()?.measures?.firstOrNull()?.start ?: 0) }
    suspend fun close() { job?.cancelAndJoin(); audio.close() }
}
