// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.tuxcompose.sample

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.tuxcompose.component.CursorStyle
import com.tuxcompose.layout.TGLayoutOrientation
import kotlin.math.roundToInt

/** Secondary controls live here, not in a wall of horizontally scrolling chips above the music. */
@Composable
fun StudioSettings(preferences: StudioPreferences, page: SettingsPage, onPage: (SettingsPage) -> Unit,
    onChange: (StudioPreferences) -> Unit, zoom: Float, onZoom: (Float) -> Unit,
    speed: Int, onSpeed: (Int) -> Unit, trackCount: Int, soundFont: String, midiStatus: String,
    busy: Boolean, onSoundFont: () -> Unit, onResetBank: () -> Unit, onMidi: () -> Unit,
    onDismiss: () -> Unit) {
    val scroll=remember(page) { ScrollState(0) }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).semantics { testTagsAsResourceId=true }.testTag("settings-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Make it yours",style=MaterialTheme.typography.headlineSmallEmphasized)
                    Text("A workspace that feels right.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudioIconButton(R.drawable.ic_close,"Close settings","settings-close",onClick=onDismiss)
            }
            PrimaryScrollableTabRow(selectedTabIndex=page.ordinal,edgePadding=16.dp,
                containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                SettingsPage.entries.forEach { tab -> Tab(selected=tab==page,onClick={onPage(tab)},
                    modifier=Modifier.testTag("settings-${tab.name}"),text={Text(tab.label)}) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal=24.dp).padding(bottom=32.dp)) {
                when (page) {
                    SettingsPage.Display -> {
                        SettingsHeading("Reading layout","Notation keeps its size. Page mode wraps to the available width.")
                        StudioChoices(listOf(TGLayoutOrientation.Page,TGLayoutOrientation.Horizontal),preferences.orientation,
                            {if(it==TGLayoutOrientation.Page) "Page" else "Continuous"},{"orientation-${it.name}"}) { onChange(preferences.copy(orientation=it)) }
                        SettingsHeading("Zoom · ${(zoom*100).roundToInt()}%")
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            StudioIconButton(R.drawable.ic_zoom_out,"Zoom out","zoom-out") { onZoom(zoom*.85f) }
                            Slider(value=zoom.coerceIn(.5f,2f),onValueChange=onZoom,valueRange=.5f..2f,
                                modifier=Modifier.weight(1f).testTag("zoom"))
                            StudioIconButton(R.drawable.ic_zoom_in,"Zoom in","zoom-in") { onZoom(zoom/.85f) }
                        }
                        TextButton(onClick={onZoom(1f)},modifier=Modifier.testTag("zoom-reset")) { Text("Reset to 100%") }
                        SettingsHeading("Score details")
                        SettingsSwitch("All tracks","Align every part in the same system.",preferences.allTracks,"all-tracks") { onChange(preferences.copy(allTracks=it)) }
                        val compactAvailable=!preferences.allTracks || trackCount<=1
                        SettingsSwitch("Compact spacing",if (compactAvailable) "Use TuxGuitar’s native compact rhythm spacing." else "Native compact mode is unavailable when multiple tracks are shown.",
                            preferences.compact&&compactAvailable,"compact-spacing",compactAvailable) { onChange(preferences.copy(compact=it)) }
                        SettingsSwitch("Fill rows","Distribute spare space across complete systems.",preferences.justify,"justify-rows") { onChange(preferences.copy(justify=it)) }
                        SettingsSwitch("Roomier strings","Increase tablature string spacing from 10 to 14 units.",preferences.wideStrings,"string-spacing") { onChange(preferences.copy(wideStrings=it)) }
                        SettingsSwitch("Chord diagrams","Show decoded chord fingerings above the score.",preferences.chordDiagrams,"show-chords") { onChange(preferences.copy(chordDiagrams=it)) }
                        SettingsSwitch("Chord names","Show chord names without changing the imported notes.",preferences.chordNames,"show-chord-names") { onChange(preferences.copy(chordNames=it)) }
                    }
                    SettingsPage.Cursor -> {
                        SettingsHeading("Playhead style","Capsule is a hollow outline with breathing room around the notes.")
                        StudioChoices(CursorStyle.entries.toList(),preferences.cursorStyle,{it.name},{"cursor-${it.name}"}) { onChange(preferences.copy(cursorStyle=it)) }
                        SettingsHeading("Inner margin · ${preferences.cursorPadding.roundToInt()} units","Clear space on either side between note ink and the inside edge of the stroke.")
                        Slider(preferences.cursorPadding,{onChange(preferences.copy(cursorPadding=it))},
                            valueRange=0f..8f,steps=7,modifier=Modifier.testTag("capsule-padding"))
                        SettingsSwitch("Show playhead","Keep your position visible while practising.",preferences.cursorVisible,"show-cursor") { onChange(preferences.copy(cursorVisible=it)) }
                        SettingsSwitch("Glide between notes","Move smoothly rather than snapping to each beat.",preferences.smoothCursor,"smooth-cursor") { onChange(preferences.copy(smoothCursor=it)) }
                        SettingsHeading("Following the music")
                        SettingsSwitch("Follow playhead","Your own scrolling always takes priority. Use Resume follow to return.",preferences.follow,"follow-setting") { onChange(preferences.copy(follow=it)) }
                        SettingsSwitch("Smooth scrolling","Ease into the next visible system.",preferences.smoothFollow,"smooth-follow") { onChange(preferences.copy(smoothFollow=it)) }
                    }
                    SettingsPage.Sound -> {
                        SettingsHeading("Practice speed · $speed%","Changing speed pauses playback and keeps your score position.")
                        Slider(speed.toFloat(),{onSpeed(it.roundToInt())},valueRange=50f..150f,steps=19,
                            modifier=Modifier.testTag("playback-speed"))
                        TextButton(onClick={onSpeed(100)},modifier=Modifier.testTag("speed-reset")) { Text("Original tempo") }
                        SettingsHeading("Studio sound","FluidSynth 2.6.2 · stereo SoundFont playback")
                        SettingsSwitch("Reverb","Add a natural room tail to the notes.",preferences.reverb,"reverb") { onChange(preferences.copy(reverb=it)) }
                        SettingsSwitch("Chorus","Add gentle width and movement.",preferences.chorus,"chorus") { onChange(preferences.copy(chorus=it)) }
                        SettingsHeading("Sound bank",soundFont)
                        SettingsAction("Choose SoundFont","Open an SF2 or SF3 file from your device.",R.drawable.ic_piano,"soundfont",!busy,onSoundFont)
                        if (soundFont!="GeneralUser GS 2.0.3") TextButton(onClick=onResetBank,enabled=!busy,modifier=Modifier.testTag("reset-soundfont")) { Text("Restore GeneralUser GS") }
                        SettingsHeading("MIDI output")
                        SettingsAction("Output device",midiStatus,R.drawable.ic_headphones,"midi-output",!busy,onMidi)
                    }
                    SettingsPage.Appearance -> {
                        SettingsHeading("Color & light","Material 3 Expressive’s tonal palette, paired surfaces and emphasized type.")
                        StudioChoices(StudioThemeMode.entries.toList(),preferences.theme,{it.name},{"theme-${it.name}"}) { onChange(preferences.copy(theme=it)) }
                        SettingsSwitch("Wallpaper colors",if(Build.VERSION.SDK_INT>=31) "Use your Android dynamic color palette." else "Available on Android 12 and later.",
                            preferences.dynamicColor,"dynamic-color",Build.VERSION.SDK_INT>=31) { onChange(preferences.copy(dynamicColor=it)) }
                        SettingsHeading("Notation contrast")
                        SettingsSwitch("High-contrast paper","Black notes on white, with a blue playhead. App controls keep your chosen theme.",preferences.highContrast,"high-contrast") { onChange(preferences.copy(highContrast=it)) }
                        SettingsHeading("Designed for focus","Only the score, track selector and playback stay on the workspace. These preferences are saved on this device.")
                    }
                }
            }
        }
    }
}
