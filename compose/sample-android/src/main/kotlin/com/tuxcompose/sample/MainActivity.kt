// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.tuxcompose.sample

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuxcompose.component.*
import com.tuxcompose.demo.*
import com.tuxcompose.layout.*
import com.tuxcompose.pdf.TuxGuitarPdfExporter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TuxComposeDemo() }
    }
}

@Composable
fun TuxComposeDemo() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val uriHandler=LocalUriHandler.current
    val store=remember { context.getSharedPreferences("studio-settings",android.content.Context.MODE_PRIVATE) }
    var preferences by remember { mutableStateOf(StudioPreferences.load(store)) }
    LaunchedEffect(preferences) { preferences.save(store) }
    val session: StudioSession=viewModel()
    val state=session.state
    val player=session.player
    val midi=session.midi
    var imported by session::imported
    var status by session::status
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf<SettingsPage?>(null) }
    var trackSheet by remember { mutableStateOf(false) }
    var sectionsSheet by remember { mutableStateOf(false) }
    var scoreLayout by remember { mutableStateOf<TGSongLayout?>(null) }
    var midiSheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var pdfSheet by remember { mutableStateOf(false) }
    var midiStatus by session::midiStatus
    var soundFont by session::soundFont
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(preferences.reverb,preferences.chorus) {
        withContext(Dispatchers.IO) { player.audio.setEffects(preferences.reverb,preferences.chorus) }
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun install(score: ImportedScore, resetPosition: Boolean = true) {
        scoreLayout=null
        player.load(score); imported=score; state.song=score.score
        state.selectedTrackIndex=if(resetPosition) 0 else state.selectedTrackIndex.coerceIn(0,score.score.tracks.lastIndex)
        val measures=score.score.tracks[state.selectedTrackIndex].measures
        val tick=if(resetPosition) measures.first().start else state.cursorTick.coerceIn(measures.first().start,measures.last().end-1)
        state.clearSelection(); state.cursor.seek(tick); player.seek(tick)
        if(resetPosition) state.scrollTo(0f,0f)
        status="${score.format} · ${score.score.tracks.first().measures.size} measures"; error=null
    }
    LaunchedEffect(Unit) {
        if(imported!=null) { busy=false; return@LaunchedEffect }
        val restoredUri=session.sourceUri
        val restoredName=session.sourceName
        try {
            // Retain decoded data/bank across resize; re-decode the saved URI after process death.
            install(withContext(Dispatchers.IO) {
                restoredUri?.let { value ->
                    requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(value))).use { TuxGuitarImporter.read(it,restoredName) }
                } ?: TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"technique-atlas.tg")
            },resetPosition=false)
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=e.message ?: "The demo could not be opened"; status="Unable to load score" }
        finally { busy=false }
    }
    fun fileName(uri: android.net.Uri, fallback: String): String =
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
            if(c.moveToFirst()) c.getString(0) else null
        } ?: fallback
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            busy=true; error=null; player.pause()
            try {
                val name=withContext(Dispatchers.IO) { fileName(uri,"score.gp5") }
                install(withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { TuxGuitarImporter.read(it,name) }
                })
                session.sourceName=name
                session.sourceUri=try {
                    context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    uri.toString()
                } catch(_: SecurityException) { null }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error=e.message ?: "Unable to decode this file" }
            finally { busy=false }
        }
    }
    val openSoundFont=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            busy=true; error=null; player.pause()
            try {
                soundFont=withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use(player.audio::loadSoundFont)
                    fileName(uri,"Custom SoundFont")
                }; error=null
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error=e.message ?: "Unable to load this SoundFont" }
            finally { busy=false }
        }
    }
    fun resetBank() { scope.launch {
        busy=true; error=null; player.pause()
        try { withContext(Dispatchers.IO) { player.audio.resetSoundFont() }; soundFont="GeneralUser GS 2.0.3" }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=e.message ?: "Unable to restore the bank" }
        finally { busy=false }
    } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri!=null) scope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(ShowcaseSongFactory.guitarPro()) } }
                snackbar.showSnackbar("Bundled demo exported")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error=e.message ?: "Unable to export the bundled demo" }
        }
    }
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {uri->
        val request=session.pendingPdf
        session.pendingPdf=null
        if(uri!=null) scope.launch {
            if(request==null) {error="The PDF request was interrupted. Reopen Export PDF.";return@launch}
            busy=true;error=null
            try {
                val job=currentCoroutineContext()
                val result=withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {output->
                        TuxGuitarPdfExporter(context).export(request.song,output,request.options,request.metadata) {job.ensureActive()}
                    }
                }
                busy=false
                snackbar.showSnackbar("PDF exported · ${result.pageCount} ${if(result.pageCount==1) "page" else "pages"}")
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {error=e.message ?: "Unable to export PDF"}
            finally {busy=false}
        }
    }
    LaunchedEffect(error,player.error) {
        (error ?: player.error)?.let { snackbar.showSnackbar(it,withDismissAction=true,duration=SnackbarDuration.Long) }
    }
    StudioTheme(preferences) {
        val colors=MaterialTheme.colorScheme.notationPalette(preferences.highContrast)
        val layout=remember(preferences,colors) {
            TGLayoutConfig(style=preferences.mode,orientation=preferences.orientation,colors=colors,
                multiTrack=preferences.allTracks,stringSpacing=if(preferences.wideStrings) 14f else 10f,
                compact=preferences.compact,justifySystems=preferences.justify,spacingMode=preferences.spacing,
                showChords=preferences.chordDiagrams,showChordNames=preferences.chordNames)
        }
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId=true }.testTag("studio-root")) {
            // Landscape phones and expanded windows get a proper side panel, not a crushed canvas.
            val wide=maxWidth>=840.dp || (maxWidth>=640.dp && maxHeight<480.dp)
            Scaffold(containerColor=MaterialTheme.colorScheme.surface,
                contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom),
                topBar={ StudioTopBar(imported?.score?.title ?: "Your practice space",status,busy,
                    {open.launch(arrayOf("*/*"))},{more=true}) {
                    DropdownMenu(expanded=more,onDismissRequest={more=false},modifier=Modifier.semantics {testTagsAsResourceId=true}) {
                        DropdownMenuItem(text={Text("Score details")},leadingIcon={StudioIcon(R.drawable.ic_info)},
                            onClick={more=false;details=true},modifier=Modifier.testTag("score-details"))
                        DropdownMenuItem(text={Text("Explore score sections")},leadingIcon={StudioIcon(R.drawable.ic_graphic_eq)},
                            onClick={more=false;sectionsSheet=true},modifier=Modifier.testTag("score-sections"))
                        DropdownMenuItem(text={Text("Export score / tab as PDF")},leadingIcon={StudioIcon(R.drawable.ic_download)},
                            enabled=imported!=null && !busy,onClick={more=false;pdfSheet=true},modifier=Modifier.testTag("export-pdf"))
                        DropdownMenuItem(text={Text("Export showcase GP5")},leadingIcon={StudioIcon(R.drawable.ic_download)},
                            onClick={more=false;export.launch("technique-atlas.gp5")})
                        DropdownMenuItem(text={Text("Return to beginning")},leadingIcon={StudioIcon(R.drawable.ic_restart_alt)},
                            onClick={more=false;player.stop()})
                        HorizontalDivider()
                        DropdownMenuItem(text={Text("About Tux Compose")},onClick={more=false;about=true})
                    }
                } },
                bottomBar={ if(!wide) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)) {
                    StudioTransport(state,player,busy,{settings=SettingsPage.Sound},{settings=SettingsPage.Sound},Modifier.fillMaxWidth(),onSections={sectionsSheet=true})
                } },
                snackbarHost={ SnackbarHost(snackbar) { data -> Snackbar(data,modifier=Modifier.testTag("error")) } }) { padding ->
                val track=state.song?.tracks?.getOrNull(state.selectedTrackIndex)
                Row(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp).padding(bottom=if(wide) 12.dp else 0.dp),
                    horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    StudioScore(state,preferences,layout,track?.name ?: "Choose a track",busy,
                        {trackSheet=true},{settings=SettingsPage.Display},{preferences=it},{player.seek(it)},
                        Modifier.weight(1f).fillMaxHeight(),onLayout={scoreLayout=it})
                    if(wide) Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("wide-controls"),
                        verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        SettingsHeading("Practice, at your pace","Focus on the music. Your controls stay close.")
                        StudioTransport(state,player,busy,{settings=SettingsPage.Sound},{settings=SettingsPage.Sound},onSections={sectionsSheet=true})
                        SettingsAction("Workspace settings","Layout, playhead, sound and color.",R.drawable.ic_tune,"wide-settings") { settings=SettingsPage.Display }
                    }
                }
            }
        }
        settings?.let { page -> StudioSettings(preferences,page,{settings=it},{preferences=it},
            state.zoomScale,{state.setZoom(it)},player.speedPercent,{player.pause();player.speedPercent=it},
            imported?.score?.tracks?.size ?: 0,soundFont,midiStatus,busy,
            {settings=null;openSoundFont.launch(arrayOf("*/*"))},{resetBank()},
            {settings=null;midiSheet=true},{settings=null}) }
        if(sectionsSheet) StudioSections(state.song,state.selectedTrackIndex,{sectionsSheet=false}) { tick ->
            player.pause();state.clearSelection();state.cursor.seek(tick);player.seek(tick)
            // Explicit navigation reveals the requested section even when automatic
            // following or the cursor itself is disabled. Do not overwrite either preference.
            scoreLayout?.beatAt(state.selectedTrackIndex,tick)?.let { beat ->
                state.scrollTo(x=if(preferences.orientation==TGLayoutOrientation.Horizontal) (beat.x-28f).coerceAtLeast(0f) else 0f,
                    y=if(preferences.orientation==TGLayoutOrientation.Page) (beat.bounds.top-4f).coerceAtLeast(0f) else 0f)
            }
            state.resumeAutoScroll();sectionsSheet=false
        }
        if(pdfSheet) imported?.let {score->PdfExportSheet(score.score,state.selectedTrackIndex,session.pdfOptions,
            onDismiss={pdfSheet=false},onSave={options->
                try {
                    val request=score.pdfSnapshot(options)
                    session.pdfOptions=options;session.pendingPdf=request
                    pdfSheet=false
                    val name=score.score.title.ifBlank {"score"}.replace(Regex("[^A-Za-z0-9 ._-]"),"_")
                    savePdf.launch("$name.pdf")
                } catch(e: Exception) {
                    session.pendingPdf=null;error=e.message ?: "Unable to prepare PDF export"
                }
            })}
        if(trackSheet) ModalBottomSheet(onDismissRequest={trackSheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId=true }.padding(horizontal=16.dp).verticalScroll(rememberScrollState()).padding(bottom=24.dp).selectableGroup()) {
                Text("Find your part",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(horizontal=8.dp))
                Text("Choose a track to read. Audio keeps the full arrangement.",style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(8.dp))
                imported?.score?.tracks?.forEachIndexed { index,track ->
                    val selected=state.selectedTrackIndex==index
                    ListItem(headlineContent={Text(track.name)},supportingContent={Text("${track.stringTuning.size} strings")},
                        leadingContent={StudioIcon(R.drawable.ic_music_note)},trailingContent={RadioButton(selected,onClick=null)},
                        colors=ListItemDefaults.colors(containerColor=if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier=Modifier.fillMaxWidth().testTag("track-$index").selectable(selected,role=Role.RadioButton) {
                            state.selectedTrackIndex=index;state.clearSelection();trackSheet=false
                        })
                }
            }
        }
        if(midiSheet) ModalBottomSheet(onDismissRequest={midiSheet=false}) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("MIDI output",style=MaterialTheme.typography.headlineSmall)
                Text("Mirror standard MIDI to a connected device.",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={midi.close();midiStatus="Built-in FluidSynth";midiSheet=false}) { Text("Use built-in FluidSynth") }
                val devices=midi.devices()
                if(devices.isEmpty()) Text("No external MIDI devices connected.",modifier=Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                devices.forEach { device ->
                    val name=device.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device ${device.id}"
                    TextButton(onClick={player.pause();midi.connect(device) { message -> if(message!=null) error=message else midiStatus=name };midiSheet=false}) { Text(name) }
                }
            }
        }
        if(details) AlertDialog(onDismissRequest={details=false},icon={StudioIcon(R.drawable.ic_music_note)},
            title={Text(imported?.score?.title ?: "Score details")},text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(imported?.score?.artist.orEmpty());Text(status)
                    Text("${imported?.score?.tracks?.size ?: 0} tracks · $soundFont")
                    Text("Tick ${state.cursorTick}",modifier=Modifier.testTag("cursor-tick"))
                }
            },confirmButton={TextButton(onClick={details=false}) {Text("Done")}})
        if(about) AlertDialog(onDismissRequest={about=false},icon={StudioIcon(R.drawable.ic_music_note)},
            title={Text("Tux Compose 1.1.0")},text={ Text("A focused practice workspace built with Material 3 Expressive.\n\nTuxGuitar Guitar Pro import · FluidSynth 2.6.2 · GeneralUser GS 2.0.3.\n\nThis is an evaluation app. Source, usage, mathematics and third-party licensing notes are available in the project documentation; licence texts are bundled in the APK.") },
            confirmButton={TextButton(onClick={about=false;uriHandler.openUri("https://github.com/romankolov/tuxguitar/tree/arena/3dc87cab-tuxguitar/compose")}) {Text("Source & licences")}},
            dismissButton={TextButton(onClick={about=false}) {Text("Close")}})
    }
}
