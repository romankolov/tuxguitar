// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** All dimensions are logical canvas units (dp at zoom 1); density is applied exactly once. */
@Immutable
data class TGLayoutConfig(
    val scale: Float = 1f,
    val style: TGLayoutStyles = TGLayoutStyles.Mixed,
    val orientation: TGLayoutOrientation = TGLayoutOrientation.Page,
    val stringSpacing: Float = 10f,
    val staffSpacing: Float = 8f,
    val noteHeadRadius: Float = 4f,
    val measurePadding: Float = 15f,
    val firstNoteSpacing: Float = 10f,
    val clefSpacing: Float = 30f,
    val keySignatureSpacing: Float = 15f,
    val timeSignatureSpacing: Float = 15f,
    val staffPadding: Float = 10f,
    val systemSpacing: Float = 20f,
    val scoreTabSpacing: Float = 5f,
    val pagePadding: Float = 12f,
    val effectSpacing: Float = 8f,
    val durationWidths: PersistentList<Float> = persistentListOf(30f, 25f, 21f, 20f, 19f, 18f),
    val compact: Boolean = false,
    val multiTrack: Boolean = false,
    val justifySystems: Boolean = true,
    /** Upstream only repeats signatures when changed; opt in to repeating at EVERY system. */
    val repeatSystemSignatures: Boolean = false,
    val showMeasureNumbers: Boolean = true,
    val showTempo: Boolean = true,
    val typography: TGTypography = TGTypography(),
    val colors: TGColorPalette = TGColorPalette.Paper,
) {
    init {
        require(scale.isFinite() && scale in 0.1f..8f)
        require(listOf(stringSpacing, staffSpacing, noteHeadRadius).all { it.isFinite() && it > 0 })
        require(listOf(measurePadding, firstNoteSpacing, clefSpacing, keySignatureSpacing, timeSignatureSpacing, staffPadding, systemSpacing, scoreTabSpacing, pagePadding, effectSpacing).all { it.isFinite() && it >= 0 })
        require(durationWidths.isNotEmpty() && durationWidths.all { it.isFinite() && it > 0 })
    }
    val showStandardNotation: Boolean get() = style != TGLayoutStyles.Tablature
    val showTablature: Boolean get() = style != TGLayoutStyles.Score
}

enum class TGLayoutStyles { Score, Tablature, Mixed }
enum class TGLayoutOrientation { Horizontal, Page }

@Immutable
data class TGTypography(
    val noteFamily: FontFamily = FontFamily.SansSerif,
    val textFamily: FontFamily = FontFamily.SansSerif,
    val noteWeight: FontWeight = FontWeight.Bold,
    val noteSize: Float = 9f,
    val textSize: Float = 8f,
    val measureSize: Float = 8f,
    val chordSize: Float = 9f,
) {
    init { require(listOf(noteSize, textSize, measureSize, chordSize).all { it.isFinite() && it > 0 }) }
}

@Immutable
data class TGColorPalette(
    val background: Color = Color(0xFFFFFEFA),
    val staffLines: Color = Color(0xFF7E857F),
    val tablatureLines: Color = Color(0xFFA7AEA7),
    val noteText: Color = Color(0xFF1C2820),
    val noteHead: Color = Color(0xFF26342A),
    val cursor: Color = Color(0xFF188351),
    val selection: Color = Color(0x333C8B64),
    val beatText: Color = Color(0xFF526359),
    val measureNumber: Color = Color(0xFF188351),
) {
    companion object {
        val Paper = TGColorPalette()
        val Dark = TGColorPalette(Color(0xFF17221E), Color(0xFF728679), Color(0xFF4C6154), Color(0xFFF0F4EE), Color(0xFFE0EBDC), Color(0xFF8FDEA6), Color(0x444F9566), Color(0xFFA4B9AB), Color(0xFF8FDEA6))
        val HighContrast = TGColorPalette(Color.White, Color.Black, Color.Black, Color.Black, Color.Black, Color(0xFF0033CC), Color(0x550033CC), Color.Black, Color.Black)
    }
}

/** Pure metrics allow deterministic layout tests without a platform font/canvas. */
fun interface TGFontMetrics {
    fun measure(text: String, role: TGTextRole): TGTextSize
    companion object {
        val Approximate = TGFontMetrics { text, role ->
            val height = if (role == TGTextRole.Note) 10f else 9f
            TGTextSize(text.length * height * 0.6f, height)
        }
    }
}
enum class TGTextRole { Note, Text, Measure, Chord, Grace }
@Immutable
data class TGTextSize(val width: Float, val height: Float) {
    init { require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0) }
}
