// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.tuxcompose.layout.*

/**
 * Viewport-sized canvas, not a song-sized bitmap. Scroll/zoom use logical coordinates; visible
 * systems/measures are culled via binary searches. Cursor and selection have independent layers.
 */
@Composable
fun TablatureView(
    state: TablatureState,
    modifier: Modifier = Modifier,
    config: TGLayoutConfig = TGLayoutConfig(),
    cursorConfig: TGCursorConfig = TGCursorConfig(),
    gesturesEnabled: Boolean = true,
    onBeatClicked: (beatTick: Long, trackIndex: Int) -> Unit = { _, _ -> },
    onHit: (TGHitResult) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val fonts = remember(textMeasurer, config.typography, density.fontScale) {
        com.tuxcompose.renderer.TGComposeTextMetrics(textMeasurer,config.typography,density.fontScale)
    }
    val renderer = remember(config, fonts) { com.tuxcompose.renderer.TGCanvasRenderer(config,fonts) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val factor = density.density * state.zoomScale * config.scale
    val width = (size.width / factor).coerceAtLeast(1f)
    val height = (size.height / factor).coerceAtLeast(1f)
    val song = state.song
    val layout = remember(song,config,fonts,width,state.selectedTrackIndex) {
        song?.let { TGLayoutCalculator(config,fonts).calculate(it,width,state.selectedTrackIndex) } ?: TGSongLayout(width,0f,emptyList())
    }
    SideEffect { if (size.width > 0 && size.height > 0) state.updateViewport(layout.width,layout.height,width,height) }
    val clicked by rememberUpdatedState(onBeatClicked)
    val hit by rememberUpdatedState(onHit)
    val currentFactor by rememberUpdatedState(factor)
    val currentLayout by rememberUpdatedState(layout)

    fun zoom(change: Float) {
        val old = state.zoomScale
        val next = (old * change).coerceIn(.25f,4f)
        val centerX = size.width / (2 * density.density * config.scale)
        val centerY = size.height / (2 * density.density * config.scale)
        val anchorX = state.scrollX + centerX / old
        val anchorY = state.scrollY + centerY / old
        state.setZoom(next)
        state.scrollTo(anchorX - centerX / next,anchorY - centerY / next)
    }
    val zoomHandler by rememberUpdatedState<(Float) -> Unit> { zoom(it) }
    val cursorAlpha: State<Float> = if (cursorConfig.animation == CursorAnimation.Solid) remember { mutableStateOf(1f) } else {
        val transition = rememberInfiniteTransition(label = "Notation cursor")
        transition.animateFloat(1f, if (cursorConfig.animation == CursorAnimation.Blink) 0f else .3f,
            animationSpec = infiniteRepeatable(
                animation = if (cursorConfig.animation == CursorAnimation.Blink) snap(cursorConfig.animationMillis) else tween(cursorConfig.animationMillis),
                repeatMode = RepeatMode.Reverse), label = "Cursor opacity")
    }
    val transform = rememberTransformableState { zoomChange, pan, _ ->
        zoom(zoomChange)
        state.scrollBy(-pan.x/currentFactor,-pan.y/currentFactor)
    }
    val horizontal = rememberScrollableState { delta ->
        val old = state.scrollX; state.scrollBy(x = -delta/currentFactor); (old - state.scrollX) * currentFactor
    }
    val vertical = rememberScrollableState { delta ->
        val old = state.scrollY; state.scrollBy(y = -delta/currentFactor); (old - state.scrollY) * currentFactor
    }
    LaunchedEffect(state.cursorTick,state.selectedTrackIndex,layout,cursorConfig.follow) {
        if (cursorConfig.follow) layout.beatAt(state.selectedTrackIndex,state.cursorTick)?.let { b ->
            var x = state.scrollX; var y = state.scrollY
            if (b.x < x + 12 || b.x > x + width - 12) x = (b.x - width * .25f).coerceAtLeast(0f)
            if (b.bounds.top < y || b.bounds.bottom > y + height) y = (b.bounds.top - 8).coerceAtLeast(0f)
            state.scrollTo(x,y)
        }
    }
    fun tapped(point: Offset, extend: Boolean) {
        val p = Offset(point.x/currentFactor+state.scrollX,point.y/currentFactor+state.scrollY)
        currentLayout.hitTest(p,config.stringSpacing)?.let { result ->
            state.select(result.trackIndex,result.beatTick,result.string,extend)
            clicked(result.beatTick,result.trackIndex); hit(result)
        }
    }
    val interactions = if (!gesturesEnabled) Modifier else Modifier
        .scrollable(horizontal,Orientation.Horizontal)
        .scrollable(vertical,Orientation.Vertical)
        .transformable(transform,canPan = { false })
        .pointerInput(state, config.stringSpacing) {
            detectTapGestures(onTap = { tapped(it,false) },onLongPress = { tapped(it,true) },onDoubleTap = { zoomHandler(if (state.zoomScale < 2f) 1.5f else 1/1.5f) })
        }
    Box(modifier.background(config.colors.background).onSizeChanged { size = it }
        .then(interactions)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionRight -> state.cursor.nextBeat()
                Key.DirectionLeft -> state.cursor.previousBeat()
                Key.DirectionDown -> state.cursor.nextMeasure()
                Key.DirectionUp -> state.cursor.previousMeasure()
                Key.Escape -> { state.clearSelection(); true }
                else -> false
            }
        }
        .focusable()
        .semantics {
            contentDescription = "${song?.title?.ifBlank { "Untitled" } ?: "Empty score"}, tablature and score"
            stateDescription = "Track ${state.selectedTrackIndex+1}, tick ${state.cursorTick}"
            customActions = listOf(
                CustomAccessibilityAction("Next beat") { state.cursor.nextBeat() },
                CustomAccessibilityAction("Previous beat") { state.cursor.previousBeat() },
                CustomAccessibilityAction("Zoom in") { zoom(1.25f); true },
                CustomAccessibilityAction("Zoom out") { zoom(.8f); true },
            )
        }) {
        // Selection beneath notation; moving the cursor does not invalidate the notation layer.
        Canvas(Modifier.fillMaxSize()) {
            canvasViewport(state,factor) { viewport -> with(renderer) { renderSelection(layout,viewport,state.selection) } }
        }
        Canvas(Modifier.fillMaxSize()) {
            canvasViewport(state,factor) { viewport -> with(renderer) { render(layout,viewport) } }
        }
        Canvas(Modifier.fillMaxSize()) {
            canvasViewport(state,factor) { with(renderer) { renderCursor(layout,state.selectedTrackIndex,state.cursorTick,cursorConfig,cursorAlpha.value) } }
        }
    }
}

private inline fun DrawScope.canvasViewport(state: TablatureState, factor: Float, draw: DrawScope.(Rect) -> Unit) {
    val viewport = Rect(state.scrollX,state.scrollY,state.scrollX+size.width/factor,state.scrollY+size.height/factor)
    withTransform({ scale(factor,factor,Offset.Zero); translate(-state.scrollX,-state.scrollY) }) { draw(viewport) }
}

@Composable
fun TablatureView(state: TablatureState, config: TablatureConfig, modifier: Modifier = Modifier, onBeatClicked: (Long,Int) -> Unit = { _,_ -> }) =
    TablatureView(state,modifier,config.layout,config.cursor,config.gesturesEnabled,onBeatClicked)
