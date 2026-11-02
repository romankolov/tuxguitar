// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.tuxcompose.layout.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

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
    onLayout: (TGSongLayout) -> Unit = {},
    drawOverlay: DrawScope.(TGSongLayout) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val factor = density.density * state.zoomScale * config.scale
    val fonts = remember(textMeasurer, config.typography, config.respectSystemFontScale, config.nativeSpacing, density.fontScale, factor) {
        com.tuxcompose.renderer.TGComposeTextMetrics(textMeasurer,config.typography,
            if (config.respectSystemFontScale) density.fontScale else 1f,
            if (config.nativeSpacing) factor else null)
    }
    val renderer = remember(config, fonts) { com.tuxcompose.renderer.TGCanvasRenderer(config.copy(renderScale=factor),fonts) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val width = (size.width / factor).coerceAtLeast(1f)
    val height = (size.height / factor).coerceAtLeast(1f)
    val song = state.song
    val selectedTrack = state.selectedTrackIndex.coerceIn(0,(song?.tracks?.lastIndex ?: 0).coerceAtLeast(0))
    SideEffect { if (state.selectedTrackIndex != selectedTrack) state.selectedTrackIndex = selectedTrack }
    val geometryConfig = remember(config,factor) { config.copy(colors=TGColorPalette.Paper,renderScale=factor) }
    val layoutTrackKey = if (config.multiTrack) 0 else selectedTrack
    val layout = remember(song,geometryConfig,fonts,width,layoutTrackKey) {
        song?.let { TGLayoutCalculator(geometryConfig,fonts).calculate(it,width,layoutTrackKey) } ?: TGSongLayout(width,0f,emptyList())
    }
    SideEffect { if (size.width > 0 && size.height > 0) state.updateViewport(layout.width,layout.height,width,height) }
    val layoutCallback by rememberUpdatedState(onLayout)
    val overlay by rememberUpdatedState(drawOverlay)
    LaunchedEffect(layout) { layoutCallback(layout) }
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
    val cursorAlpha: State<Float> = if (!cursorConfig.visible || cursorConfig.animation == CursorAnimation.Solid) remember { mutableStateOf(1f) } else {
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
    val cursorPosition = layout.cursorAt(selectedTrack,state.cursorTick,cursorConfig.smoothMovement)
    // Navigation state/effects precede the keyed cursor animation: changing systems
    // must reset visual interpolation only, never the user's suspended-follow epoch.
    var pointerHeld by remember(state) { mutableStateOf(false) }
    val userScrolling = pointerHeld || horizontal.isScrollInProgress || vertical.isScrollInProgress || transform.isTransformInProgress
    val userGeneration = state.userScrollGeneration
    var quietGeneration by remember(state) { mutableLongStateOf(userGeneration) }
    var followingGeneration by remember(state) { mutableLongStateOf(userGeneration) }
    val enabled = cursorConfig.follow && cursorConfig.visible
    val latestEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(state,userScrolling,userGeneration,cursorConfig.followResumeDelayMillis) {
        if (!userScrolling) {
            if (quietGeneration != userGeneration) delay(cursorConfig.followResumeDelayMillis.toLong())
            quietGeneration = userGeneration
        }
    }
    // An explicit Follow action, track/song change or re-enabling the cursor rejoins even
    // when the playhead is offscreen. Merely ending a drag does NOT snap the user back.
    LaunchedEffect(state,enabled,state.followRequestGeneration,song,selectedTrack) {
        if (enabled) {
            followingGeneration = state.userScrollGeneration
            quietGeneration = state.userScrollGeneration
        }
    }
    val viewport = Rect(state.scrollX,state.scrollY,state.scrollX+width,state.scrollY+height)
    val cursorInView = cursorIntersectsViewport(cursorPosition,viewport)
    LaunchedEffect(enabled,userScrolling,quietGeneration,userGeneration,cursorInView) {
        if (enabled && !userScrolling && quietGeneration == userGeneration && cursorInView)
            followingGeneration = userGeneration
    }
    val canFollow = enabled && !userScrolling && quietGeneration == userGeneration && followingGeneration == userGeneration
    SideEffect {
        state.isUserScrolling = userScrolling
        state.isAutoScrollSuspended = enabled && !canFollow
    }
    DisposableEffect(state) { onDispose { state.isUserScrolling = false; state.isAutoScrollSuspended = false } }
    LaunchedEffect(state,layout,selectedTrack,width,height,canFollow,cursorConfig.smoothMovement,
        cursorConfig.smoothScroll,cursorConfig.scrollAnimationMillis) {
        if (!canFollow || size.width == 0 || size.height == 0) return@LaunchedEffect
        val generation = state.userScrollGeneration
        coroutineScope {
            var planned = Offset(state.scrollX,state.scrollY)
            var motion: Job? = null
            snapshotFlow { state.cursorTick }.collect { tick ->
                val position = layout.cursorAt(selectedTrack,tick,cursorConfig.smoothMovement)
                if (position == null) {
                    motion?.cancelAndJoin()
                    planned = Offset(state.scrollX,state.scrollY)
                    return@collect
                }
                val destination = cursorFollowDestination(position,planned,width,height,state.maxScrollX,state.maxScrollY)
                if (destination != planned) {
                    planned = destination
                    motion?.cancelAndJoin()
                    motion = launch {
                        fun move(fraction: Float, from: Offset) {
                            // Check the epoch on every frame: even a pointer-down arriving
                            // before recomposition must interrupt automatic movement.
                            if (!latestEnabled || state.isUserScrolling || state.userScrollGeneration != generation)
                                throw CancellationException("User interrupted cursor follow")
                            state.scrollFromFollow(from.x+(destination.x-from.x)*fraction,from.y+(destination.y-from.y)*fraction)
                        }
                        val from = Offset(state.scrollX,state.scrollY)
                        if (!cursorConfig.smoothScroll || cursorConfig.scrollAnimationMillis == 0) move(1f,from)
                        else animate(0f,1f,animationSpec=tween(cursorConfig.scrollAnimationMillis,easing=FastOutSlowInEasing)) { value,_ -> move(value,from) }
                    }
                }
            }
        }
    }
    // A system change/reflow/track switch starts at the new position, never glides diagonally
    // back across the page. Backward seeks/repeats also snap instead of traversing old notes.
    val cursorX = key(layout,selectedTrack,cursorPosition?.systemIndex) {
        val previousTick = remember { longArrayOf(state.cursorTick) }
        val jump = state.cursorTick - previousTick[0]
        val snapMovement = !cursorConfig.visible || !cursorConfig.smoothMovement || jump < 0 ||
            jump > (cursorPosition?.beat?.beat?.maxTicks ?: 960L) * 2
        SideEffect { previousTick[0] = state.cursorTick }
        animateFloatAsState(cursorPosition?.x ?: 0f,
            animationSpec = if (snapMovement) snap() else tween(cursorConfig.movementAnimationMillis,easing=LinearEasing),
            label = "Cursor movement")
    }

    fun tapped(point: Offset, extend: Boolean) {
        val p = Offset(point.x/currentFactor+state.scrollX,point.y/currentFactor+state.scrollY)
        currentLayout.hitTest(p,config.stringSpacing)?.let { result ->
            state.select(result.trackIndex,result.beatTick,result.string,extend)
            clicked(result.beatTick,result.trackIndex); hit(result)
        }
    }
    val interactions = if (!gesturesEnabled) Modifier else Modifier
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                state.notifyUserInteraction()
                pointerHeld = true
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                    while (event.changes.any { it.pressed })
                } finally { pointerHeld = false }
            }
        }
        .scrollable(horizontal,Orientation.Horizontal,enabled=config.orientation != TGLayoutOrientation.Page || !config.fitPageToWidth)
        .scrollable(vertical,Orientation.Vertical)
        .transformable(transform,canPan = { false })
        .pointerInput(state, config.stringSpacing) {
            detectTapGestures(onTap = { tapped(it,false) },onLongPress = { tapped(it,true) },onDoubleTap = { zoomHandler(if (state.zoomScale < 2f) 1.5f else 1/1.5f) })
        }
    Box(modifier.clipToBounds().background(config.colors.background).onSizeChanged { size = it }
        .then(interactions)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionRight -> state.cursor.nextBeat().also { if (it) state.resumeAutoScroll() }
                Key.DirectionLeft -> state.cursor.previousBeat().also { if (it) state.resumeAutoScroll() }
                Key.DirectionDown -> state.cursor.nextMeasure().also { if (it) state.resumeAutoScroll() }
                Key.DirectionUp -> state.cursor.previousMeasure().also { if (it) state.resumeAutoScroll() }
                Key.Escape -> { state.clearSelection(); true }
                else -> false
            }
        }
        .focusable()
        .semantics {
            contentDescription = "${song?.title?.ifBlank { "Untitled" } ?: "Empty score"}, tablature and score"
            stateDescription = "Track ${state.selectedTrackIndex+1}, tick ${state.cursorTick}"
            customActions = listOf(
                CustomAccessibilityAction("Next beat") { state.cursor.nextBeat().also { if (it) state.resumeAutoScroll() } },
                CustomAccessibilityAction("Previous beat") { state.cursor.previousBeat().also { if (it) state.resumeAutoScroll() } },
                CustomAccessibilityAction("Follow cursor") { state.resumeAutoScroll(); true },
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
            canvasViewport(state,factor) { with(renderer) { renderCursor(cursorPosition,cursorConfig,cursorAlpha.value,cursorX.value) }; overlay(layout) }
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
