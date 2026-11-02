// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tuxcompose.component.*
import com.tuxcompose.demo.*
import com.tuxcompose.layout.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TuxComposeDemo() }
    }
}

@Composable
fun TuxComposeDemo() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberTablatureState(initialCursorTick=960)
    val player = remember { DemoPlayback(context,scope,state) }
    val midi = remember { ExternalMidi(context,player.audio) }
    var imported by remember { mutableStateOf<ImportedScore?>(null) }
    var status by remember { mutableStateOf("Preparing Guitar Pro demo…") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(TGLayoutStyles.Mixed) }
    var orientation by remember { mutableStateOf(TGLayoutOrientation.Page) }
    var palette by remember { mutableIntStateOf(0) }
    var cursorStyle by remember { mutableStateOf(CursorStyle.Line) }
    var smoothCursor by remember { mutableStateOf(true) }
    var smoothFollow by remember { mutableStateOf(true) }
    var cursorVisible by remember { mutableStateOf(true) }
    var followCursor by remember { mutableStateOf(true) }
    var multiTrack by remember { mutableStateOf(false) }
    var optimizeSpacing by remember { mutableStateOf(true) }
    var compact by remember { mutableStateOf(false) }
    var justifyRows by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var midiMenu by remember { mutableStateOf(false) }
    var midiStatus by remember { mutableStateOf("Internal synth") }
    var spacing by remember { mutableFloatStateOf(10f) }
    val colors = when (palette) { 1 -> TGColorPalette.Dark; 2 -> TGColorPalette.HighContrast; else -> TGColorPalette.Paper }
    val layout = remember(mode,orientation,colors,multiTrack,spacing,optimizeSpacing,compact,justifyRows) {
        TGLayoutConfig(style=mode,orientation=orientation,colors=colors,multiTrack=multiTrack,stringSpacing=spacing,
            optimizeSpacing=optimizeSpacing,compact=compact,justifySystems=justifyRows)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); midi.close(); player.dispose() }
    }
    fun install(score: ImportedScore) {
        player.load(score); imported=score; state.song=score.score; state.selectedTrackIndex=0
        state.clearSelection(); state.cursor.seek(score.score.tracks.first().measures.first().start); state.scrollTo(0f,0f)
        status="${score.format} · ${score.score.tracks.size} tracks · ${score.score.tracks.first().measures.size} measures"
        error=null
    }
    LaunchedEffect(Unit) {
        busy=true
        try {
            // Exercise the actual GP5 decoder on startup, not a parallel mock data path.
            val score=withContext(Dispatchers.IO) { TuxGuitarImporter.read(ByteArrayInputStream(DemoSongFactory.guitarPro()),"greenwood.gp5") }
            install(score)
        } catch (e: Exception) { error=e.message; status="Demo could not load" }
        finally { busy=false }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy=true; player.pause()
            try {
                val score=withContext(Dispatchers.IO) {
                    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "score.gp5"
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { TuxGuitarImporter.read(it,name) }
                }
                install(score)
            } catch (e: Exception) { error=e.message ?: "Unable to decode the selected file" }
            finally { busy=false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(DemoSongFactory.guitarPro()) } }
                .onFailure { withContext(Dispatchers.Main) { error=it.message } }
        }
    }
    MaterialTheme(colorScheme=if (palette==1) darkColorScheme(primary=Color(0xFF8FDEA6),surface=Color(0xFF17221E)) else lightColorScheme(primary=Color(0xFF176B47),surface=Color(0xFFF4F6F0))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF17221E)).padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TUX / COMPOSE",color=Color(0xFFE7F0E3),fontSize=15.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                        Text("THE CANVAS SESSIONS",color=Color(0xFF8DAF98),fontSize=9.sp,letterSpacing=1.7.sp)
                    }
                    OutlinedButton(onClick={open.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.testTag("open-file"),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFE7F0E3))) { Text("Open file") }
                }
                Column(Modifier.padding(start=20.dp,end=20.dp,top=16.dp,bottom=8.dp)) {
                    Text(imported?.score?.title ?: "Score workspace",fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                    Text(imported?.score?.artist ?: "Model → Layout → Canvas",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(status,fontSize=10.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("import-status"))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    imported?.score?.tracks?.forEachIndexed { i,track ->
                        FilterChip(selected=state.selectedTrackIndex==i,onClick={state.selectedTrackIndex=i;state.clearSelection()},label={Text(track.name,fontSize=11.sp)},modifier=Modifier.testTag("track-$i"))
                    }
                    FilterChip(selected=followCursor&&!state.isAutoScrollSuspended,
                        onClick={ if (!followCursor || state.isAutoScrollSuspended) { followCursor=true;state.resumeAutoScroll() } else followCursor=false },
                        label={Text(if (state.isAutoScrollSuspended) "Resume follow" else "Follow",fontSize=11.sp)},modifier=Modifier.testTag("follow-cursor"))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    TGLayoutStyles.entries.forEach { style -> FilterChip(selected=mode==style,onClick={mode=style},label={Text(style.name,fontSize=11.sp)},modifier=Modifier.testTag("mode-${style.name}")) }
                    TextButton(onClick={orientation=if (orientation==TGLayoutOrientation.Page) TGLayoutOrientation.Horizontal else TGLayoutOrientation.Page},modifier=Modifier.testTag("orientation")) { Text(if (orientation==TGLayoutOrientation.Page) "↵ Page" else "↔ Continuous",fontSize=11.sp) }
                    TextButton(onClick={showSettings=!showSettings},modifier=Modifier.testTag("settings")) { Text("Customize",fontSize=11.sp) }
                }
                if (showSettings) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={palette=(palette+1)%3},modifier=Modifier.testTag("palette")) { Text(listOf("Paper","Dark","Contrast")[palette]) }
                        TextButton(onClick={cursorStyle=CursorStyle.entries[(cursorStyle.ordinal+1)%3]}) { Text("Cursor: ${cursorStyle.name}") }
                        FilterChip(selected=smoothCursor,onClick={smoothCursor=!smoothCursor},label={Text("Cursor glide")},modifier=Modifier.testTag("smooth-cursor"))
                        FilterChip(selected=smoothFollow,onClick={smoothFollow=!smoothFollow},label={Text("Smooth scroll")},modifier=Modifier.testTag("smooth-follow"))
                        FilterChip(selected=cursorVisible,onClick={cursorVisible=!cursorVisible},label={Text("Show cursor")},modifier=Modifier.testTag("show-cursor"))
                        FilterChip(selected=multiTrack,onClick={multiTrack=!multiTrack},label={Text("All tracks")})
                        TextButton(onClick={spacing=if (spacing==10f) 14f else 10f}) { Text("Strings ${spacing.toInt()}") }
                        TextButton(onClick={export.launch("greenwood-study.gp5")}) { Text("Export demo GP5") }
                    }
                    val compactAvailable = !multiTrack || (state.song?.tracks?.size ?: 0) <= 1
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected=optimizeSpacing,onClick={optimizeSpacing=!optimizeSpacing},label={Text("Balanced spacing")},modifier=Modifier.testTag("balanced-spacing"))
                        FilterChip(selected=compact&&compactAvailable,onClick={compact=!compact},enabled=compactAvailable,label={Text("Compact")},modifier=Modifier.testTag("compact-spacing"))
                        FilterChip(selected=justifyRows,onClick={justifyRows=!justifyRows},label={Text("Fill rows")},modifier=Modifier.testTag("justify-rows"))
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TablatureView(state,Modifier.fillMaxSize().testTag("score-canvas"),layout,TGCursorConfig(style=cursorStyle,visible=cursorVisible,follow=followCursor,smoothMovement=smoothCursor,smoothScroll=smoothFollow),onBeatClicked={tick,_ -> player.seek(tick)})
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={state.setZoom(state.zoomScale/.85f)},modifier=Modifier.testTag("zoom-in")) { Text("＋") }
                    Text("${(state.zoomScale*100).toInt()}%",fontSize=11.sp)
                    TextButton(onClick={state.setZoom(state.zoomScale*.85f)},modifier=Modifier.testTag("zoom-out")) { Text("−") }
                    Text("Tick ${state.cursorTick}",fontSize=11.sp,modifier=Modifier.testTag("cursor-tick"))
                    TextButton(onClick={player.pause();player.speedPercent=if (player.speedPercent==100) 75 else if (player.speedPercent==75) 125 else 100}) { Text("${player.speedPercent}%",fontSize=11.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={player.pause();state.cursor.previousBeat();state.resumeAutoScroll();player.seek(state.cursorTick)},enabled=!busy,modifier=Modifier.testTag("previous")) { Text("‹") }
                    Button(onClick={if (player.playing) player.pause() else player.play()},enabled=!busy&&!player.loading&&imported!=null,modifier=Modifier.weight(1f).testTag("play")) { Text(if (player.loading) "Loading audio…" else if (player.playing) "Pause" else "Play") }
                    OutlinedButton(onClick={player.stop()},enabled=!busy,modifier=Modifier.testTag("stop")) { Text("■") }
                    OutlinedButton(onClick={player.pause();state.cursor.nextBeat();state.resumeAutoScroll();player.seek(state.cursorTick)},enabled=!busy,modifier=Modifier.testTag("next")) { Text("›") }
                }
                Box {
                    TextButton(onClick={midiMenu=true},modifier=Modifier.padding(horizontal=12.dp)) { Text("MIDI · $midiStatus",fontSize=10.sp) }
                    DropdownMenu(expanded=midiMenu,onDismissRequest={midiMenu=false}) {
                        DropdownMenuItem(text={Text("Internal Gervill synthesizer")},onClick={midi.close();midiStatus="Internal synth";midiMenu=false})
                        val devices=midi.devices()
                        if (devices.isEmpty()) DropdownMenuItem(text={Text("No MIDI devices connected")},onClick={},enabled=false)
                        devices.forEach { device ->
                            val name=device.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device ${device.id}"
                            DropdownMenuItem(text={Text(name)},onClick={player.pause();midi.connect(device) { message -> if (message!=null) error=message else midiStatus=name };midiMenu=false})
                        }
                    }
                }
                (error ?: player.error)?.let { message ->
                    Text(message,color=MaterialTheme.colorScheme.error,fontSize=11.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp).testTag("error"))
                }
            }
        }
    }
}
