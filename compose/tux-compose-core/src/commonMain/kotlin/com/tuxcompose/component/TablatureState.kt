// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.tuxcompose.model.TGSong

@Immutable
data class TGSelection(val trackIndex: Int, val anchorTick: Long, val focusTick: Long = anchorTick) {
    init { require(trackIndex >= 0 && anchorTick >= 0 && focusTick >= 0) }
    val startTick: Long get() = minOf(anchorTick, focusTick)
    val endTick: Long get() = maxOf(anchorTick, focusTick)
}

enum class CursorStyle { Line, Block, Glow, Capsule }
enum class CursorAnimation { Solid, Blink, Pulse }
@Immutable
data class TGCursorConfig(
    val style: CursorStyle = CursorStyle.Line,
    val color: Color? = null,
    val width: Float = 1.5f,
    val blockAlpha: Float = .18f,
    val visible: Boolean = true,
    val highlightNotes: Boolean = false,
    val follow: Boolean = true,
    val animation: CursorAnimation = CursorAnimation.Solid,
    val animationMillis: Int = 900,
    /** Sweep through the beat using cursorTick instead of snapping to its first note. */
    val smoothMovement: Boolean = true,
    /** Short visual tween for coarse tick updates; 0 uses exact, continuous tick positions. */
    val movementAnimationMillis: Int = 80,
    val smoothScroll: Boolean = true,
    val scrollAnimationMillis: Int = 280,
    /** Idle grace after drag, wheel, fling or zoom; browsing away also waits for cursor visibility. */
    val followResumeDelayMillis: Int = 1200,
    /** Hollow capsule outer width, in logical notation units; width controls its stroke. */
    val capsuleWidth: Float = 6f,
    /** Vertical padding inside the row; the complete stroke stays inside this inset. */
    val capsuleInset: Float = 6f,
) {
    init {
        require(width.isFinite() && width > 0 && blockAlpha in 0f..1f && animationMillis > 0)
        require(capsuleWidth.isFinite() && capsuleWidth > 0 && capsuleInset.isFinite() && capsuleInset >= 0)
        require(movementAnimationMillis >= 0 && scrollAnimationMillis >= 0 && followResumeDelayMillis >= 0)
    }
}

/** Convenience wrapper; library has no clock, player, decoder or MIDI dependencies. */
@Immutable
data class TablatureConfig(val layout: com.tuxcompose.layout.TGLayoutConfig = com.tuxcompose.layout.TGLayoutConfig(), val cursor: TGCursorConfig = TGCursorConfig(), val gesturesEnabled: Boolean = true)

@Stable
class TablatureState(initialSong: TGSong? = null, initialCursorTick: Long = 0L) {
    var song: TGSong? by mutableStateOf(initialSong)
    private var tickValue: Long by mutableLongStateOf(initialCursorTick.coerceAtLeast(0L))
    var cursorTick: Long
        get() = tickValue
        set(value) { tickValue = value.coerceAtLeast(0L) }
    var selectedTrackIndex: Int by mutableIntStateOf(0)
    var selectedString: Int? by mutableStateOf(null)
    var selection: TGSelection? by mutableStateOf(null)
    var zoomScale: Float by mutableFloatStateOf(1f)
        private set
    var scrollX: Float by mutableFloatStateOf(0f)
        private set
    var scrollY: Float by mutableFloatStateOf(0f)
        private set
    var scrollPosition: Float
        get() = scrollX
        set(value) { scrollTo(value, scrollY) }
    var maxScrollX: Float by mutableFloatStateOf(0f)
        private set
    var maxScrollY: Float by mutableFloatStateOf(0f)
        private set
    val cursor: CursorController = CursorController(this)
    /** Includes pointer hold, drag, fling, wheel and pinch interaction, not automatic following. */
    var isUserScrolling: Boolean by mutableStateOf(false)
        internal set
    var isAutoScrollSuspended: Boolean by mutableStateOf(false)
        internal set
    internal var userScrollGeneration: Long by mutableLongStateOf(0)
        private set
    internal var followRequestGeneration: Long by mutableLongStateOf(0)
        private set

    internal fun notifyUserInteraction() { userScrollGeneration++ }
    /** Explicitly rejoin the playhead after browsing away. Does not override follow/visible config. */
    fun resumeAutoScroll() { followRequestGeneration++ }

    fun moveCursorForward(ticks: Long) {
        require(ticks >= 0)
        cursorTick = if (Long.MAX_VALUE - cursorTick < ticks) Long.MAX_VALUE else cursorTick + ticks
    }
    fun moveCursorBackward(ticks: Long) { require(ticks >= 0); cursorTick = (cursorTick - ticks).coerceAtLeast(0) }
    fun setZoom(scale: Float) { require(scale.isFinite()); zoomScale = scale.coerceIn(.25f, 4f) }
    fun scrollTo(x: Float = scrollX, y: Float = scrollY) {
        require(x.isFinite() && y.isFinite())
        notifyUserInteraction()
        scrollFromFollow(x,y)
    }
    internal fun scrollFromFollow(x: Float, y: Float) {
        scrollX = x.coerceIn(0f, maxScrollX); scrollY = y.coerceIn(0f, maxScrollY)
    }
    fun scrollBy(x: Float = 0f, y: Float = 0f) = scrollTo(scrollX + x, scrollY + y)
    fun select(trackIndex: Int, tick: Long, string: Int? = null, extend: Boolean = false) {
        require(trackIndex >= 0)
        val normalized = tick.coerceAtLeast(0)
        selectedTrackIndex = trackIndex; cursorTick = normalized; selectedString = string
        selection = if (extend && selection?.trackIndex == trackIndex) selection?.copy(focusTick = normalized) else TGSelection(trackIndex,normalized)
        resumeAutoScroll()
    }
    fun clearSelection() { selection = null }
    internal fun updateViewport(contentWidth: Float, contentHeight: Float, viewportWidth: Float, viewportHeight: Float) {
        maxScrollX = (contentWidth - viewportWidth).coerceAtLeast(0f)
        maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
        scrollFromFollow(scrollX,scrollY)
    }
    internal fun moveBeat(direction: Int): Boolean {
        val track = song?.tracks?.getOrNull(selectedTrackIndex) ?: return false
        var lo = 0; var hi = track.measures.lastIndex; var measureIndex = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (track.measures[mid].start <= cursorTick) { measureIndex = mid; lo = mid + 1 } else hi = mid - 1
        }
        var mi = if (measureIndex < 0) 0 else measureIndex
        while (mi in track.measures.indices) {
            val beats = track.measures[mi].beats
            var left = 0; var right = beats.lastIndex; var candidate = if (direction > 0) beats.size else -1
            while (left <= right) {
                val mid = (left + right) ushr 1
                if (direction > 0) {
                    if (beats[mid].start > cursorTick) { candidate = mid; right = mid - 1 } else left = mid + 1
                } else {
                    if (beats[mid].start < cursorTick) { candidate = mid; left = mid + 1 } else right = mid - 1
                }
            }
            beats.getOrNull(candidate)?.let { cursorTick = it.start; return true }
            mi += if (direction > 0) 1 else -1
        }
        return false
    }
    companion object {
        fun saver(song: TGSong?) = listSaver<TablatureState, Any>(
            save = { listOf(it.cursorTick, it.selectedTrackIndex, it.zoomScale, it.scrollX, it.scrollY) },
            restore = { values -> TablatureState(song,values[0] as Long).apply {
                selectedTrackIndex = values[1] as Int; setZoom(values[2] as Float)
                scrollX = values[3] as Float; scrollY = values[4] as Float
            } },
        )
    }
}

@Stable
class CursorController internal constructor(private val state: TablatureState) {
    val tick: Long get() = state.cursorTick
    fun seek(tick: Long) { state.cursorTick = tick.coerceAtLeast(0) }
    fun nextBeat(): Boolean = state.moveBeat(1)
    fun previousBeat(): Boolean = state.moveBeat(-1)
    fun nextMeasure(): Boolean {
        val m = state.song?.tracks?.getOrNull(state.selectedTrackIndex)?.measures?.firstOrNull { it.start > tick } ?: return false
        seek(m.start); return true
    }
    fun previousMeasure(): Boolean {
        val m = state.song?.tracks?.getOrNull(state.selectedTrackIndex)?.measures?.lastOrNull { it.start < tick } ?: return false
        seek(m.start); return true
    }
}

/** Song changes are propagated without destroying cursor/zoom state; null scores are safe. */
@Composable
fun rememberTablatureState(song: TGSong? = null, initialCursorTick: Long = 0L): TablatureState {
    val state = rememberSaveable(saver = TablatureState.saver(song)) { TablatureState(song,initialCursorTick) }
    LaunchedEffect(song) { state.song = song }
    return state
}
