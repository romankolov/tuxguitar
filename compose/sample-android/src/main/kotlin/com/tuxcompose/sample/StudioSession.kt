// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tuxcompose.component.TablatureState
import com.tuxcompose.demo.ImportedScore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Activity-independent ownership: rotation/resize must not drop the score, bank or position. */
@OptIn(FlowPreview::class)
class StudioSession(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val state=TablatureState(initialCursorTick=saved.get<Long>("tick") ?: 960L).apply {
        selectedTrackIndex=saved.get<Int>("track") ?: 0
        setZoom(saved.get<Float>("zoom") ?: 1f)
    }
    val player=DemoPlayback(application,viewModelScope,state)
    val midi=ExternalMidi(application,player.audio)
    var imported by mutableStateOf<ImportedScore?>(null)
    internal var pendingPdf by mutableStateOf<PendingPdfExport?>(null)
    internal var pdfOptions by mutableStateOf(com.tuxcompose.pdf.TGPdfOptions())
    var status by mutableStateOf("Preparing Technique Atlas…")
    var soundFont by mutableStateOf("GeneralUser GS 2.0.3")
    var midiStatus by mutableStateOf("Built-in FluidSynth")
    var sourceUri: String?
        get()=saved["sourceUri"]
        set(value) { saved["sourceUri"]=value }
    var sourceName: String
        get()=saved["sourceName"] ?: "score.gp5"
        set(value) { saved["sourceName"]=value }
    init {
        viewModelScope.launch {
            snapshotFlow { Triple(state.cursorTick,state.selectedTrackIndex,state.zoomScale) }.sample(500).collect { (tick,track,zoom) ->
                saved["tick"]=tick; saved["track"]=track; saved["zoom"]=zoom
            }
        }
    }
    override fun onCleared() {
        midi.close(); player.dispose()
        super.onCleared()
    }
}
