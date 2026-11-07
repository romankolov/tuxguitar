// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.tuxcompose.component.CursorStyle
import com.tuxcompose.layout.TGLayoutOrientation
import com.tuxcompose.layout.TGLayoutStyles

enum class StudioThemeMode { System, Light, Dark }
enum class SettingsPage(val label: String) { Display("Display"), Cursor("Cursor"), Sound("Sound"), Appearance("Theme") }

/** Showcase preferences only. No UI, persistence or playback dependency is added to core. */
@Immutable
data class StudioPreferences(
    val theme: StudioThemeMode = StudioThemeMode.System,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
    val mode: TGLayoutStyles = TGLayoutStyles.Mixed,
    val orientation: TGLayoutOrientation = TGLayoutOrientation.Page,
    val cursorStyle: CursorStyle = CursorStyle.Capsule,
    val cursorPadding: Float = 3f,
    val cursorVisible: Boolean = true,
    val smoothCursor: Boolean = true,
    val smoothFollow: Boolean = true,
    val follow: Boolean = true,
    val allTracks: Boolean = false,
    val compact: Boolean = true,
    val justify: Boolean = true,
    val chordDiagrams: Boolean = false,
    val chordNames: Boolean = false,
    val wideStrings: Boolean = false,
    val reverb: Boolean = true,
    val chorus: Boolean = true,
) {
    fun save(store: SharedPreferences) {
        store.edit().apply {
            putString("theme",theme.name); putString("mode",mode.name)
            putString("orientation",orientation.name); putString("cursorStyle",cursorStyle.name)
            putFloat("cursorPadding",cursorPadding)
            flags().forEach { (key,value) -> putBoolean(key,value) }
        }.apply()
    }
    private fun flags() = mapOf("dynamicColor" to dynamicColor,"highContrast" to highContrast,
        "cursorVisible" to cursorVisible,"smoothCursor" to smoothCursor,"smoothFollow" to smoothFollow,
        "follow" to follow,"allTracks" to allTracks,"compact" to compact,"justify" to justify,
        "chordDiagrams" to chordDiagrams,"chordNames" to chordNames,"wideStrings" to wideStrings,
        "reverb" to reverb,"chorus" to chorus)
    companion object {
        fun load(store: SharedPreferences): StudioPreferences {
            fun flag(key: String, default: Boolean) = store.getBoolean(key,default)
            fun <T : Enum<T>> choice(key: String, default: T, values: Array<T>): T =
                values.firstOrNull { it.name==store.getString(key,null) } ?: default
            return StudioPreferences(
                theme=choice("theme",StudioThemeMode.System,StudioThemeMode.entries.toTypedArray()),
                mode=choice("mode",TGLayoutStyles.Mixed,TGLayoutStyles.entries.toTypedArray()),
                orientation=choice("orientation",TGLayoutOrientation.Page,TGLayoutOrientation.entries.toTypedArray()),
                cursorStyle=choice("cursorStyle",CursorStyle.Capsule,CursorStyle.entries.toTypedArray()),
                cursorPadding=store.getFloat("cursorPadding",3f).takeIf { it.isFinite() }?.coerceIn(0f,8f) ?: 3f,
                dynamicColor=flag("dynamicColor",false), highContrast=flag("highContrast",false),
                cursorVisible=flag("cursorVisible",true), smoothCursor=flag("smoothCursor",true),
                smoothFollow=flag("smoothFollow",true), follow=flag("follow",true),
                allTracks=flag("allTracks",false), compact=flag("compact",true), justify=flag("justify",true),
                chordDiagrams=flag("chordDiagrams",false), chordNames=flag("chordNames",false),
                wideStrings=flag("wideStrings",false), reverb=flag("reverb",true), chorus=flag("chorus",true))
        }
    }
}
