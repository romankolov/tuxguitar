// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.tuxcompose.model.TGSong

@Immutable
data class TGSelection(val trackIndex: Int, val anchorTick: Long, val focusTick: Long = anchorTick) {
    val startTick: Long get() = minOf(anchorTick, focusTick)
    val endTick: Long get() = maxOf(anchorTick, focusTick)
}

enum class CursorStyle { Line, Block, Glow }
@Immutable
data class TGCursorConfig(
    val style: CursorStyle = CursorStyle.Line,
    val color: Color? = null,
    val width: Float = 1.5f,
    val blockAlpha: Float = .18f,
    val visible: Boolean = true,
    val highlightNotes: Boolean = false,
    val follow: Boolean = true,
) { init { require(width.isFinite() && width > 0 && blockAlpha in 0f..1f) } }

/** Convenience wrapper; library has no clock, player, decoder or MIDI dependencies. */
@Immutable
data class TablatureConfig(val layout: com.tuxcompose.layout.TGLayoutConfig = com.tuxcompose.layout.TGLayoutConfig(), val cursor: TGCursorConfig = TGCursorConfig(), val gesturesEnabled: Boolean = true)

@Stable
class TablatureState(initialSong: TGSong? = null, initialCursorTick: Long = 0L) {
    var song: TGSong? by mutableStateOf(initialSong)
    var cursorTick: Long by mutableLongStateOf(initialCursorTick.coerceAtLeast(0L))
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

    fun moveCursorForward(ticks: Long) {
        require(ticks >= 0)
        cursorTick = if (Long.MAX_VALUE - cursorTick < ticks) Long.MAX_VALUE else cursorTick + ticks
    }
    fun moveCursorBackward(ticks: Long) { require(ticks >= 0); cursorTick = (cursorTick - ticks).coerceAtLeast(0) }
    fun setZoom(scale: Float) { require(scale.isFinite()); zoomScale = scale.coerceIn(.25f, 4f) }
    fun scrollTo(x: Float = scrollX, y: Float = scrollY) {
        require(x.isFinite() && y.isFinite())
        scrollX = x.coerceIn(0f, maxScrollX); scrollY = y.coerceIn(0f, maxScrollY)
    }
    fun scrollBy(x: Float = 0f, y: Float = 0f) = scrollTo(scrollX + x, scrollY + y)
    fun select(trackIndex: Int, tick: Long, string: Int? = null, extend: Boolean = false) {
        selectedTrackIndex = trackIndex; cursorTick = tick.coerceAtLeast(0); selectedString = string
        selection = if (extend && selection?.trackIndex == trackIndex) selection?.copy(focusTick = tick) else TGSelection(trackIndex,tick)
    }
    fun clearSelection() { selection = null }
    internal fun updateViewport(contentWidth: Float, contentHeight: Float, viewportWidth: Float, viewportHeight: Float) {
        maxScrollX = (contentWidth - viewportWidth).coerceAtLeast(0f)
        maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
        scrollTo()
    }
    internal fun moveBeat(direction: Int): Boolean {
        val track = song?.tracks?.getOrNull(selectedTrackIndex) ?: return false
        val beats = track.measures.flatMap { it.beats }
        val next = if (direction > 0) beats.firstOrNull { it.start > cursorTick } else beats.lastOrNull { it.start < cursorTick }
        if (next == null) return false
        cursorTick = next.start
        return true
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
