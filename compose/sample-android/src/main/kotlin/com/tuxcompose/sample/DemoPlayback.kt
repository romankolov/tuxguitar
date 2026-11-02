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
    private var source: ImportedScore? = null
    private var sequence: DemoSequence? = null
    private var sequenceSpeed = 100
    private var position = 0L
    private var pendingSeek: Long? = null
    private var disposed = false
    private var generation = 0

    fun load(score: ImportedScore) {
        pause(); source=score; sequence=null; position=0
        pendingSeek=score.score.tracks.firstOrNull()?.measures?.firstOrNull()?.start
    }
    fun seek(tick: Long) {
        val resume=playing || loading
        pause(); pendingSeek=tick
        if (resume) play()
    }
    fun play() {
        if (disposed || playing || loading) return
        val source=source ?: return
        val previous=job
        previous?.cancel()
        val request = ++generation
        error=null; loading=true
        val speed=speedPercent
        job=scope.launch {
            // Serializing teardown prevents an old job's finally block silencing a new transport.
            try {
                previous?.join()
                val prepared=withContext(Dispatchers.IO) {
                    (if (sequence != null && sequenceSpeed==speed) sequence!! else DemoSequence(source.native,speed))
                        .also { audio.open(source.native) }
                }
                val seek=pendingSeek ?: if (sequenceSpeed!=speed) state.cursorTick else null
                if (seek != null) { position=prepared.seekMicros(seek); pendingSeek=null }
                sequence=prepared; sequenceSpeed=speed
                if (position>=prepared.endMicros) position=0
                var index=prepared.events.indexOfFirst { it.micros>=position }.let { if (it<0) prepared.events.size else it }
                withContext(Dispatchers.IO) {
                    prepared.events.take(index).filter { it.kind in listOf(DemoMidiEvent.Kind.Control,DemoMidiEvent.Kind.Program,DemoMidiEvent.Kind.Pitch) }.forEach(audio::dispatch)
                }
                loading=false; playing=true
                val start=System.nanoTime()-position*1000
                while (isActive && position<prepared.endMicros) {
                    position=(System.nanoTime()-start)/1000
                    withContext(Dispatchers.IO) {
                        while (index<prepared.events.size && prepared.events[index].micros<=position) audio.dispatch(prepared.events[index++])
                    }
                    state.cursor.seek(prepared.scoreTickAt(position))
                    delay(8)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error=e.message ?: "Unable to initialize audio" }
            finally {
                if (request == generation) { playing=false; loading=false }
                withContext(NonCancellable+Dispatchers.IO) { audio.sendAllNotesOff() }
            }
        }
    }
    fun pause() { generation++; job?.cancel(); playing=false; loading=false }
    fun stop() {
        pause(); position=0
        pendingSeek=state.song?.tracks?.firstOrNull()?.measures?.firstOrNull()?.start ?: 0
        state.cursor.seek(pendingSeek!!)
        state.scrollTo(0f,0f)
        state.resumeAutoScroll()
    }
    fun dispose() {
        if (disposed) return
        disposed=true; generation++
        val previous=job
        previous?.cancel()
        // A short, non-cancellable resource teardown survives disposal of the composition's scope.
        scope.launch(NonCancellable+Dispatchers.IO) { previous?.join(); audio.close() }
    }
}
