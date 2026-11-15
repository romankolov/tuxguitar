// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.tuxcompose.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tuxcompose.model.TGSong
import kotlin.math.roundToInt

/**
 * Practice-loop editor: one range, snapped to whole bars, applied to the native transport.
 * Selecting a range turns loop playback on; the playhead only moves when asked.
 */
@Composable
fun LoopPracticeSheet(song: TGSong?, trackIndex: Int, enabled: Boolean, region: LoopRegion?,
    cursorTick: () -> Long, playing: Boolean, passes: Int,
    onEnabled: (Boolean) -> Unit, onRegion: (LoopRegion) -> Unit, onPick: () -> Unit,
    onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val measures=song?.tracks?.getOrNull(trackIndex)?.measures.orEmpty()
    val lastIndex=measures.lastIndex
    val maxIndex=lastIndex.coerceAtLeast(0)
    val startIndex=(region?.let { measures.indexOfMeasureAt(it.startTick) } ?: 0).coerceIn(0,maxIndex)
    val endIndex=(region?.let { measures.indexOfMeasureAt((it.endTick-1).coerceAtLeast(it.startTick)) } ?: maxIndex).coerceIn(0,maxIndex)
    var draftStart by remember(measures) { mutableStateOf<Float?>(null) }
    var draftEnd by remember(measures) { mutableStateOf<Float?>(null) }
    val shownStart=(draftStart ?: startIndex.toFloat()).coerceIn(0f,maxIndex.toFloat())
    val shownEnd=(draftEnd ?: endIndex.toFloat()).coerceIn(0f,maxIndex.toFloat())
    fun commit(editedStart: Float?, editedEnd: Float?) {
        if (measures.isEmpty()) return
        var first=(editedStart ?: startIndex.toFloat()).roundToInt().coerceIn(0,lastIndex)
        var last=(editedEnd ?: endIndex.toFloat()).roundToInt().coerceIn(0,lastIndex)
        if (first>last) { if (editedStart!=null) last=first else first=last }
        loopRegionFor(measures,first,last)?.let { next ->
            onRegion(next)
            if (!enabled) onEnabled(true)
        }
    }
    val firstNumber=measures.getOrNull(startIndex)?.number
    val lastNumber=measures.getOrNull(endIndex)?.number
    val bars=endIndex-startIndex+1
    val rangeLabel=when {
        firstNumber==null || lastNumber==null -> "Choose a score first"
        region==null -> "No range yet · pick one below"
        firstNumber==lastNumber -> "Measure $firstNumber · 1 bar"
        else -> "Measures $firstNumber–$lastNumber · $bars bars"
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId=true }
            .testTag("loop-sheet").padding(horizontal=24.dp).padding(bottom=32.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Loop practice",style=MaterialTheme.typography.headlineSmallEmphasized)
                    Text("Repeat a passage until it feels right.",style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudioIconButton(R.drawable.ic_close,"Close loop practice","loop-close",onClick=onDismiss)
            }
            SettingsSwitch("Loop playback",
                if (region==null) "Pick a range below to start looping." else "Return to ${loopMeasureLabel(measures,region) ?: "the range"} after its last bar.",
                enabled,"loop-enabled",measures.isNotEmpty()) { onEnabled(it) }
            SettingsHeading("Loop range","Whole bars, from the score itself. Selecting a range turns looping on.")
            Text(rangeLabel,style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("loop-range-label"))
            if (measures.isEmpty()) {
                Text("Open a score to choose a loop range.",style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=16.dp))
            } else if (lastIndex==0) {
                Text("This score has a single measure, so the whole score is the loop.",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.padding(vertical=16.dp))
            } else {
                val startTickLabel=(draftStart ?: startIndex.toFloat()).roundToInt().coerceIn(0,lastIndex)
                Text("First measure · ${measures.getOrNull(startTickLabel)?.number ?: "-"}",
                    style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=16.dp))
                Slider(value=shownStart,onValueChange={ draftStart=it },valueRange=0f..lastIndex.toFloat(),
                    steps=(lastIndex-1).coerceAtLeast(0),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("loop-start"),
                    onValueChangeFinished={ commit(draftStart,draftEnd); draftStart=null; draftEnd=null })
                val endTickLabel=(draftEnd ?: endIndex.toFloat()).roundToInt().coerceIn(0,lastIndex)
                Text("Last measure · ${measures.getOrNull(endTickLabel)?.number ?: "-"}",
                    style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=8.dp))
                Slider(value=shownEnd,onValueChange={ draftEnd=it },valueRange=0f..lastIndex.toFloat(),
                    steps=(lastIndex-1).coerceAtLeast(0),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("loop-end"),
                    onValueChangeFinished={ commit(draftStart,draftEnd); draftStart=null; draftEnd=null })
            }
            if (measures.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    // The playhead is read inside the handlers: the sheet must not recompose every tick.
                    OutlinedButton(onClick={ val here=measures.indexOfMeasureAt(cursorTick()); commit(here.toFloat(),here.toFloat()) },
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("loop-this-measure"),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
                        Text("This measure",style=MaterialTheme.typography.labelLarge,maxLines=2,textAlign=TextAlign.Center)
                    }
                    OutlinedButton(onClick={ val here=measures.indexOfMeasureAt(cursorTick()); commit(here.toFloat(),lastIndex.toFloat()) },
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("loop-from-playhead"),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
                        Text("From here",style=MaterialTheme.typography.labelLarge,maxLines=2,textAlign=TextAlign.Center)
                    }
                    OutlinedButton(onClick={ commit(0f,lastIndex.toFloat()) },
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("loop-whole-score"),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
                        Text("Whole score",style=MaterialTheme.typography.labelLarge,maxLines=2,textAlign=TextAlign.Center)
                    }
                }
                // Two taps on the music, no slider precision needed.
                OutlinedButton(onClick=onPick,modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                        .heightIn(min=48.dp).testTag("loop-pick"),
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
                    StudioIcon(R.drawable.ic_repeat,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick the range on the score",style=MaterialTheme.typography.labelLarge)
                }
            }
            region?.let { current ->
                TextButton(onClick={ onSeek(current.startTick) },enabled=current.isValid,
                    modifier=Modifier.padding(top=8.dp).testTag("loop-goto")) { Text("Go to loop start") }
            }
            if (enabled && region!=null) {
                Surface(Modifier.fillMaxWidth().padding(top=8.dp),shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        StudioIcon(R.drawable.ic_repeat)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(if (playing) "Looping · pass ${passes+1}" else "Ready to loop",style=MaterialTheme.typography.titleSmall,
                                modifier=Modifier.testTag("loop-state"))
                            Text(if (playing) "Playback keeps returning to the start of the range."
                                else "Press play. Playback returns to the start of the range after its last bar.",
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}
