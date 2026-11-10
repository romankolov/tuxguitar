// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.renderer

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.tuxcompose.layout.*

/** Shared measurement/drawing cache: fret cutouts and hit boxes use the actual same font result. */
class TGComposeTextMetrics(private val measurer: TextMeasurer, private val typography: TGTypography, private val fontScale: Float = 1f, private val renderScale: Float? = null) : TGFontMetrics {
    private data class Key(val text: String, val role: TGTextRole)
    private val cache = LinkedHashMap<Key, TextLayoutResult>()
    fun layout(text: String, role: TGTextRole): TextLayoutResult {
        val key = Key(text, role)
        cache.remove(key)?.let { cache[key] = it; return it }
        val note = role == TGTextRole.Note || role == TGTextRole.Grace
        val size = when (role) {
            TGTextRole.Note -> typography.noteSize
            TGTextRole.Grace -> typography.graceSize
            TGTextRole.Text -> typography.textSize
            TGTextRole.Measure -> typography.measureSize
            TGTextRole.Chord -> typography.chordSize
        }
        val result = measurer.measure(text, TextStyle(fontFamily = if (note) typography.noteFamily else typography.textFamily,
            fontWeight = if (role == TGTextRole.Note || (role == TGTextRole.Grace && renderScale == null)) typography.noteWeight else FontWeight.Normal, fontSize = (renderScale?.let { kotlin.math.floor(size*fontScale*it+.5f).coerceAtLeast(1f)/it } ?: (size*fontScale)).sp),
            softWrap = false, maxLines = 1, density = Density(1f, 1f))
        cache[key] = result
        if (cache.size > 512) cache.remove(cache.keys.first())
        return result
    }
    override fun firstBaseline(text: String, role: TGTextRole): Float = layout(text,role).firstBaseline
    override fun measure(text: String, role: TGTextRole): TGTextSize = layout(text, role).let { TGTextSize(it.size.width.toFloat(), it.size.height.toFloat()) }
}
