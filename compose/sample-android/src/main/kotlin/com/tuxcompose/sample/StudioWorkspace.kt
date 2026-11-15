// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.tuxcompose.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import kotlin.math.roundToLong

@Composable
fun StudioTopBar(title: String, subtitle: String, busy: Boolean, onOpen: () -> Unit,
    onMore: () -> Unit, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large) {
            Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) { StudioIcon(R.drawable.ic_music_note) }
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.titleLargeEmphasized,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.testTag("import-status"),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        FilledTonalIconButton(onClick=onOpen,enabled=!busy,modifier=Modifier.size(48.dp).testTag("open-file")) {
            StudioIcon(R.drawable.ic_folder_open,"Open Guitar Pro file")
        }
        Box {
            StudioIconButton(R.drawable.ic_more_vert,"More score actions","more",!busy,onMore)
            menu()
        }
    }
}

@Composable
fun StudioScore(state: TablatureState, preferences: StudioPreferences, layout: TGLayoutConfig,
    trackName: String, busy: Boolean, onTracks: () -> Unit, onSettings: () -> Unit,
    onChange: (StudioPreferences) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
    onLayout: (TGSongLayout) -> Unit = {}, loop: LoopUiState = LoopUiState()) {
    // The overlay shares TablatureView's scroll/zoom transform, so it must use the same factor.
    val notationFactor=LocalDensity.current.density*state.zoomScale*layout.scale
    val loopTint=MaterialTheme.colorScheme.primary
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onTracks,enabled=state.song!=null,modifier=Modifier.weight(1f).testTag("track-picker"),
                contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
                StudioIcon(R.drawable.ic_graphic_eq)
                Spacer(Modifier.width(8.dp))
                Text(trackName,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                StudioIcon(R.drawable.ic_expand_more)
            }
            val following=preferences.follow&&!state.isAutoScrollSuspended
            if(preferences.follow && state.isAutoScrollSuspended) FilledTonalButton(onClick={
                onChange(preferences.copy(follow=true)); state.resumeAutoScroll()
            },modifier=Modifier.heightIn(min=48.dp).testTag("follow-cursor").semantics { contentDescription="Resume follow" },contentPadding=PaddingValues(horizontal=12.dp)) {
                Text("Resume",style=MaterialTheme.typography.labelLarge)
            } else FilledTonalIconToggleButton(checked=following,onCheckedChange={
                if(!following) { onChange(preferences.copy(follow=true)); state.resumeAutoScroll() }
                else onChange(preferences.copy(follow=false))
            },modifier=Modifier.size(48.dp).testTag("follow-cursor")) {
                StudioIcon(R.drawable.ic_center_focus_strong,if(following) "Pause following" else "Follow playhead")
            }
            StudioIconButton(R.drawable.ic_tune,"Customize workspace","settings",onClick=onSettings)
        }
        Surface(Modifier.weight(1f).fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge,
            color=MaterialTheme.colorScheme.surfaceContainerLowest,
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                StudioChoices(listOf(TGLayoutStyles.Tablature,TGLayoutStyles.Score,TGLayoutStyles.Mixed),preferences.mode,
                    {when(it) { TGLayoutStyles.Tablature -> "Tab"; TGLayoutStyles.Score -> "Staff"; TGLayoutStyles.Mixed -> "Both" }},
                    {"mode-${it.name}"},Modifier.padding(horizontal=12.dp,vertical=8.dp)) { onChange(preferences.copy(mode=it)) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TablatureView(state,Modifier.fillMaxSize().testTag("score-canvas"),layout,
                        TGCursorConfig(style=preferences.cursorStyle,visible=preferences.cursorVisible,
                            follow=preferences.follow,smoothMovement=preferences.smoothCursor,
                            smoothScroll=preferences.smoothFollow,capsuleInnerPadding=preferences.cursorPadding),
                        onBeatClicked={tick,_ -> onSeek(tick)},onLayout=onLayout,
                        drawOverlay={ songLayout ->
                            val region=loop.region
                            if (region!=null && notationFactor>0f) {
                                val viewport=Rect(state.scrollX,state.scrollY,
                                    state.scrollX+size.width/notationFactor,state.scrollY+size.height/notationFactor)
                                drawLoopRegion(songLayout,region,state.selectedTrackIndex,preferences.allTracks,
                                    viewport,loopTint,notationFactor,loop.enabled)
                            }
                        })
                    if (busy) Surface(Modifier.align(Alignment.Center),shape=MaterialTheme.shapes.extraLarge,
                        color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            LoadingIndicator(Modifier.size(48.dp))
                            Text("Opening your score…",style=MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StudioTransport(state: TablatureState, player: DemoPlayback, busy: Boolean,
    onSound: () -> Unit, onSpeed: () -> Unit, modifier: Modifier = Modifier,
    onSections: () -> Unit = {}, loop: LoopUiState = LoopUiState(),
    onLoopToggle: () -> Unit = {}, onLoopEdit: () -> Unit = {}, onLoopOff: () -> Unit = {},
    onLoopPickCancel: () -> Unit = {}) {
    // These derived values avoid making the whole workspace recompose at transport tick frequency.
    val measures=state.song?.tracks?.getOrNull(state.selectedTrackIndex)?.measures.orEmpty()
    val first=measures.firstOrNull()?.start ?: 0L
    val end=measures.lastOrNull()?.end ?: 1L
    val loopLabel=loop.region?.let { loopMeasureLabel(measures,it) }
    val stopDescription=if (loop.enabled) "Stop and return to loop start" else "Stop and return to beginning"
    val fraction by remember(state,first,end) { derivedStateOf { ((state.cursorTick-first).toDouble()/(end-first).coerceAtLeast(1)).toFloat().coerceIn(0f,1f) } }
    val measureLabel by remember(state,measures) { derivedStateOf {
        val index=measures.indexOfLast { it.start<=state.cursorTick }.coerceAtLeast(0)
        if (measures.isEmpty()) "No score" else "${index+1}/${measures.size}"
    } }
    var scrub by remember(first,end) { mutableStateOf<Float?>(null) }
    Surface(modifier.testTag("transport"),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
            val stacked=maxWidth<280.dp
            Column {
                // Context, scrubber and sound share one 48dp row. This leaves the
                // music more height instead of reserving an entire row for two icons.
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick=onSections,enabled=measures.isNotEmpty(),contentPadding=PaddingValues(horizontal=4.dp),
                        modifier=Modifier.widthIn(min=64.dp,max=112.dp).heightIn(min=48.dp).testTag("section-picker")
                            .semantics { contentDescription="Measure $measureLabel. Jump to a score section" }) {
                        Text(measureLabel,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("playhead"))
                        Spacer(Modifier.width(4.dp));StudioIcon(R.drawable.ic_expand_more,modifier=Modifier.size(16.dp))
                    }
                    if (loop.enabled) {
                        FilledTonalIconButton(onClick=onLoopToggle,enabled=!loop.picking,
                            modifier=Modifier.size(48.dp).testTag("loop")
                                .semantics { contentDescription="Loop on, ${loopLabel ?: "selected range"}. Turn loop off" }) {
                            StudioIcon(R.drawable.ic_repeat)
                        }
                    } else {
                        StudioIconButton(R.drawable.ic_repeat,when {
                            loop.picking -> "Choosing the loop range on the score"
                            loop.region==null -> "Set a practice loop"
                            else -> "Turn loop on"
                        },"loop",!loop.picking,onClick=onLoopToggle)
                    }
                    Slider(value=scrub ?: fraction,onValueChange={scrub=it},
                        onValueChangeFinished={
                            scrub?.let { value ->
                                val tick=(first+(end-first)*value.toDouble()).roundToLong().coerceIn(first,(end-1).coerceAtLeast(first))
                                state.cursor.seek(tick); state.resumeAutoScroll(); player.seek(tick)
                            }; scrub=null
                        },enabled=!busy&&measures.isNotEmpty(),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("timeline")
                            .semantics { contentDescription="Score position" })
                    StudioIconButton(R.drawable.ic_headphones,"Sound and MIDI settings","sound-settings",onClick=onSound)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterHorizontally)) {
                    if (!stacked) StudioIconButton(R.drawable.ic_stop,stopDescription,"stop",!busy) { player.stop() }
                    StudioIconButton(R.drawable.ic_skip_previous,"Previous beat","previous",!busy&&measures.isNotEmpty()) {
                        player.pause(); state.cursor.previousBeat(); state.resumeAutoScroll(); player.seek(state.cursorTick)
                    }
                    Button(onClick={if (player.playing||player.loading) player.pause() else player.play()},
                        modifier=Modifier.size(width=72.dp,height=64.dp).testTag("play"),
                        enabled=!busy&&measures.isNotEmpty(),shapes=ButtonDefaults.shapes(),contentPadding=PaddingValues(0.dp)) {
                        if (player.loading) LoadingIndicator(Modifier.size(32.dp),color=MaterialTheme.colorScheme.onPrimary)
                        else StudioIcon(if(player.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                            if(player.playing) "Pause" else "Play",Modifier.size(32.dp))
                    }
                    StudioIconButton(R.drawable.ic_skip_next,"Next beat","next",!busy&&measures.isNotEmpty()) {
                        player.pause(); state.cursor.nextBeat(); state.resumeAutoScroll(); player.seek(state.cursorTick)
                    }
                    if (!stacked) StudioIconButton(R.drawable.ic_speed,"Playback speed, ${player.speedPercent} percent","speed",onClick=onSpeed)
                }
                if (stacked) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    StudioIconButton(R.drawable.ic_stop,stopDescription,"stop",!busy) { player.stop() }
                    StudioIconButton(R.drawable.ic_speed,"Playback speed, ${player.speedPercent} percent","speed",onClick=onSpeed)
                }
                // Only with a loop configured (or while picking): state, progress and a one-tap exit.
                if (loop.picking) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(top=2.dp)
                        .testTag("loop-pick-hint"),verticalAlignment=Alignment.CenterVertically) {
                    StudioIcon(R.drawable.ic_repeat,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(loop.pendingStart?.let { start ->
                            "Start: ${loopMeasureLabel(measures,start) ?: "bar selected"}. Tap the last bar"
                        } ?: "Tap the first bar on the score",
                        style=MaterialTheme.typography.labelLarge,maxLines=2,modifier=Modifier.weight(1f))
                    TextButton(onClick=onLoopPickCancel,modifier=Modifier.heightIn(min=48.dp).testTag("loop-pick-cancel"),
                        contentPadding=PaddingValues(horizontal=12.dp)) { Text("Cancel",style=MaterialTheme.typography.labelLarge) }
                } else if (loop.region!=null) Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
                        .padding(top=2.dp).testTag("loop-status"),verticalAlignment=Alignment.CenterVertically) {
                    // The label is the editor: it opens the range sheet from either state.
                    TextButton(onClick=onLoopEdit,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("loop-open"),
                        contentPadding=PaddingValues(horizontal=4.dp)) {
                        StudioIcon(R.drawable.ic_repeat,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text((if (loop.enabled) "Looping " else "Loop off · ")+(loopLabel ?: "selected range"),
                            style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.weight(1f))
                    }
                    if (loop.enabled && player.playing) Text("pass ${player.loopPasses+1}",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.padding(horizontal=4.dp))
                    if (loop.enabled) TextButton(onClick=onLoopOff,modifier=Modifier.heightIn(min=48.dp).testTag("loop-off"),
                        contentPadding=PaddingValues(horizontal=12.dp)) { Text("End loop",style=MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
