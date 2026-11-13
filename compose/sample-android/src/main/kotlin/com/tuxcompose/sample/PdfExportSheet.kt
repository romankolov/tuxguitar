// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.tuxcompose.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tuxcompose.demo.ImportedScore
import com.tuxcompose.layout.TGLayoutStyles
import com.tuxcompose.model.TGSong
import com.tuxcompose.pdf.*

internal data class PendingPdfExport(val song: TGSong,val options: TGPdfOptions,val metadata: TGPdfMetadata)
internal fun ImportedScore.pdfSnapshot(options: TGPdfOptions)=PendingPdfExport(score,options,TGPdfMetadata(
    author=native.author.orEmpty(),year=native.date.orEmpty(),copyright=native.copyright.orEmpty(),
    transcriber=native.transcriber.orEmpty(),writer=native.writer.orEmpty(),
    lyrics=(0 until native.countTracks()).associateWith {i->native.getTrack(i).lyrics.let {TGPdfLyrics(it.lyrics,it.from)}}))

private enum class PdfPaper(val label: String,val page: TGPdfPage) {
    Native("TuxGuitar",TGPdfPage.TuxGuitar),A4("A4",TGPdfPage.A4),Letter("Letter",TGPdfPage.Letter)
}

@Composable
internal fun PdfExportSheet(song: TGSong,selectedTrack: Int,initial: TGPdfOptions,
    onDismiss: ()->Unit,onSave: (TGPdfOptions)->Unit) {
    val count=song.tracks.firstOrNull()?.measures?.size ?: 0
    var style by remember {mutableStateOf(initial.style)}
    var all by remember {mutableStateOf(initial.trackIndex==null)}
    var first by remember {mutableStateOf(initial.fromMeasure.coerceIn(1,count.coerceAtLeast(1)).toString())}
    var last by remember {mutableStateOf((initial.toMeasure ?: count).coerceIn(1,count.coerceAtLeast(1)).toString())}
    var zoom by remember {mutableStateOf(initial.zoomPercent.toString())}
    var diagrams by remember {mutableStateOf(initial.chordDiagrams)}
    var names by remember {mutableStateOf(initial.chordNames)}
    var compact by remember {mutableStateOf(initial.compact)}
    var paper by remember {mutableStateOf(PdfPaper.entries.firstOrNull {it.page==initial.page} ?: PdfPaper.Native)}
    val from=first.toIntOrNull();val to=last.toIntOrNull();val percent=zoom.toIntOrNull()
    val valid=from!=null && to!=null && from in 1..count && to in from..count && percent!=null && percent in 25..400
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).semantics {testTagsAsResourceId=true}.testTag("pdf-export-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Export score / tab as PDF",style=MaterialTheme.typography.headlineSmall)
                    Text("TuxGuitar print layout",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudioIconButton(R.drawable.ic_close,"Close PDF export","pdf-close",onClick=onDismiss)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp)) {
                Text("Vector notation and searchable text. Each part starts a new page; playback cursors and screen colors are not printed.",
                    style=MaterialTheme.typography.bodyMedium)
                SettingsHeading("Notation")
                StudioChoices(TGLayoutStyles.entries.toList(),style,
                    {when(it) {TGLayoutStyles.Score->"Score";TGLayoutStyles.Tablature->"Tab";else->"Both"}},
                    {"pdf-style-${it.name}"}) {style=it}
                SettingsHeading("Parts",song.tracks.getOrNull(selectedTrack)?.name)
                StudioChoices(listOf(true,false),all,{if(it) "All parts" else "Current part"},{if(it) "pdf-parts-all" else "pdf-parts-current"}) {all=it}
                SettingsHeading("Measure range","Inclusive native measure numbers, 1–$count.")
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(first,{first=it},label={Text("From")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("pdf-first"))
                    OutlinedTextField(last,{last=it},label={Text("To")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("pdf-last"))
                }
                SettingsHeading("Paper","Native PDF default: 550 × 800 points, with 20-point margins. A point is 1/72 inch.")
                StudioChoices(PdfPaper.entries.toList(),paper,{it.label},{"pdf-paper-${it.name}"}) {paper=it}
                SettingsHeading("Print scale","Independent of phone density, accessibility text and screen zoom. Native default is 100%.")
                OutlinedTextField(zoom,{zoom=it},label={Text("Percent · 25–400")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("pdf-zoom"))
                SettingsSwitch("Chord diagrams","Use the native printed chord diagrams.",diagrams,"pdf-chords") {diagrams=it}
                SettingsSwitch("Chord names","Print the original decoded chord names.",names,"pdf-names") {names=it}
                SettingsSwitch("Compact spacing","Native print-profile spacing; no screen-layout rescaling.",compact,"pdf-compact") {compact=it}
            }
            HorizontalDivider()
            Button(onClick={onSave(TGPdfOptions(style,if(all) null else selectedTrack,from!!,to!!,percent!!,paper.page,diagrams,names,compact))},
                enabled=valid,modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp).heightIn(min=48.dp).testTag("pdf-save")) {
                StudioIcon(R.drawable.ic_download);Spacer(Modifier.width(8.dp));Text("Choose where to save PDF")
            }
        }
    }
}
