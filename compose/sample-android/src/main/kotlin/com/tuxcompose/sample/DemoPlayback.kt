// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import androidx.compose.runtime.*
import com.tuxcompose.component.TablatureState
import com.tuxcompose.demo.DemoMidiEvent
import com.tuxcompose.demo.DemoSequence
import com.tuxcompose.demo.ImportedScore
import kotlinx.coroutines.*

/** Program/control/bend events only: enough to restore the synth state at any restart point. */
private fun DemoSequence.stateEventsBefore(index: Int): List<DemoMidiEvent> =
    events.take(index).filter {
        it.kind == DemoMidiEvent.Kind.Control || it.kind == DemoMidiEvent.Kind.Program || it.kind == DemoMidiEvent.Kind.Pitch
    }

/**
 * Absolute mixer/programme state for a loop jump. Bend is deliberately excluded: an
 * all-sounds-off already centres it, and replaying a mid-phrase bend would leave the
 * first bar of the next pass out of tune. Swing/tempo stay in TuxGuitar's own timeline.
 */
private fun DemoSequence.wrapEventsBefore(index: Int): List<DemoMidiEvent> =
    events.take(index).filter { it.kind == DemoMidiEvent.Kind.Control || it.kind == DemoMidiEvent.Kind.Program }

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
    /** Completed returns to the loop start during the current practice run. */
    var loopPasses by mutableIntStateOf(0)
        private set
    private var loopEnabled = false
    private var loopRegion: LoopRegion? = null
    private var loopVersion = 0
    private var job: Job? = null
    private var source: ImportedScore? = null
    private var sequence: DemoSequence? = null
    private var sequenceSpeed = 100
    private var position = 0L
    private var pendingSeek: Long? = null
    private var disposed = false
    private var generation = 0

    fun load(score: ImportedScore) {
        pause(); source=score; sequence=null; position=0; loopPasses=0
        pendingSeek=score.score.tracks.firstOrNull()?.measures?.firstOrNull()?.start
    }
    /** Playback follows the region; the score, layout and core library are never modified. */
    fun setLoop(enabled: Boolean, region: LoopRegion?) {
        val normalized=region?.takeIf { it.isValid }
        if (loopEnabled==enabled && loopRegion==normalized) return
        loopEnabled=enabled; loopRegion=normalized; loopVersion++
        if (!enabled) loopPasses=0
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
                var loopWindow=resolveWindow(prepared)
                var seenLoop=loopVersion
                // Starting outside the region (or turning it on there) begins at its first bar.
                loopWindow?.let { window -> if (position !in (window.startMicros until window.endMicros)) position=window.startMicros }
                var index=prepared.events.indexOfFirst { it.micros>=position }.let { if (it<0) prepared.events.size else it }
                withContext(Dispatchers.IO) { prepared.stateEventsBefore(index).forEach(audio::dispatch) }
                loading=false; playing=true
                var start=System.nanoTime()-position*1000
                // Silence ringing notes, restore programme/controller state, then re-anchor the clock.
                suspend fun jumpTo(target: Long) {
                    withContext(Dispatchers.IO) { audio.sendAllNotesOff() }
                    position=target
                    index=prepared.events.indexOfFirst { it.micros>=target }.let { if (it<0) prepared.events.size else it }
                    withContext(Dispatchers.IO) { prepared.wrapEventsBefore(index).forEach(audio::dispatch) }
                    start=System.nanoTime()-position*1000
                    state.cursor.seek(prepared.scoreTickAt(position))
                }
                while (isActive) {
                    audio.failure?.let { throw IllegalStateException(it) }
                    if (seenLoop!=loopVersion) {
                        seenLoop=loopVersion
                        loopWindow=resolveWindow(prepared)
                        val edited=loopWindow
                        if (edited!=null && position !in (edited.startMicros until edited.endMicros)) jumpTo(edited.startMicros)
                    }
                    position=(System.nanoTime()-start)/1000
                    val window=loopWindow
                    if (window!=null && position>=window.endMicros) {
                        jumpTo(window.startMicros)
                        loopPasses++
                        continue
                    }
                    withContext(Dispatchers.IO) {
                        while (index<prepared.events.size && prepared.events[index].micros<=position) audio.dispatch(prepared.events[index++])
                    }
                    state.cursor.seek(prepared.scoreTickAt(position))
                    if (window==null && position>=prepared.endMicros) break
                    delay(8)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error=e.message ?: "Unable to initialize audio" }
            finally {
                if (request == generation) { playing=false; loading=false }
                withContext(NonCancellable+Dispatchers.IO) { audio.sendAllNotesOff(); audio.pauseOutput() }
            }
        }
    }
    private fun resolveWindow(prepared: DemoSequence): LoopWindow? =
        if (loopEnabled) loopRegion?.let { prepared.loopWindow(it) } else null
    fun pause() { generation++; job?.cancel(); playing=false; loading=false }
    fun stop() {
        pause(); position=0; loopPasses=0
        // While practising, "stop" returns to the first bar of the loop rather than the score top.
        val loopStart=if (loopEnabled) loopRegion?.startTick else null
        pendingSeek=loopStart ?: state.song?.tracks?.firstOrNull()?.measures?.firstOrNull()?.start ?: 0
        state.cursor.seek(pendingSeek!!)
        if (loopStart==null) state.scrollTo(0f,0f) else state.resumeAutoScroll()
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
