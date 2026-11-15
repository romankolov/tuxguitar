// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.tuxcompose.demo.DemoSequence
import com.tuxcompose.layout.TGSongLayout
import com.tuxcompose.model.TGMeasure

/**
 * A practice loop expressed as a half-open score-tick range: [startTick] is played,
 * [endTick] is the first tick after the range. Measure boundaries make the range stable
 * when the score is re-laid out at another width or zoom.
 *
 * Sample-only: the core renderer keeps no clock, transport or repeat state.
 */
@Immutable
data class LoopRegion(val startTick: Long, val endTick: Long) {
    val isValid: Boolean get() = endTick > startTick
}

/**
 * A contiguous window of **performance** time for a region. TuxGuitar's parser expands
 * repeats before the demo sequence is built, so a region is resolved against the native
 * measure spans instead of assuming measured time equals score time.
 */
internal data class LoopWindow(val startMicros: Long, val endMicros: Long)

/**
 * Maps [region] to the first performance window that plays it.
 *
 * Repeats that start *inside* the region are included, so a looping verse plays all of
 * its native passes before returning. A repeat that opened before the region start is not
 * entered, otherwise the loop would replay material the user did not select.
 */
internal fun DemoSequence.loopWindow(region: LoopRegion): LoopWindow? {
    if (!region.isValid || spans.isEmpty()) return null
    val firstIndex = spans.indexOfFirst {
        region.startTick >= it.scoreStart && region.startTick < it.scoreStart + it.length
    }
    if (firstIndex < 0) return null
    val firstSpan = spans[firstIndex]
    val windowStart = microsAt(firstSpan.performanceStart + (region.startTick - firstSpan.scoreStart))
    var windowEnd = -1L
    var lastPerformanceEnd = firstSpan.performanceStart + firstSpan.length
    var index = firstIndex
    while (index < spans.size) {
        val span = spans[index]
        // A backward jump before the selected start is a repeat we must not half-enter.
        if (index > firstIndex && span.scoreStart < region.startTick) break
        if (span.scoreStart >= region.endTick) { windowEnd = microsAt(span.performanceStart); break }
        if (region.endTick < span.scoreStart + span.length) {
            windowEnd = microsAt(span.performanceStart + (region.endTick - span.scoreStart)); break
        }
        lastPerformanceEnd = span.performanceStart + span.length
        index++
    }
    if (windowEnd < 0) windowEnd = microsAt(lastPerformanceEnd)
    val bounded = windowEnd.coerceAtMost(endMicros)
    return if (bounded > windowStart) LoopWindow(windowStart, bounded) else null
}

/** Index of the measure containing [tick]; the first measure for ticks before the score. */
internal fun List<TGMeasure>.indexOfMeasureAt(tick: Long): Int {
    if (isEmpty()) return 0
    var low = 0
    var high = lastIndex
    var found = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].start <= tick) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

/** Inclusive measure range to a half-open tick region, or null for an empty score. */
internal fun loopRegionFor(measures: List<TGMeasure>, startIndex: Int, endIndex: Int): LoopRegion? {
    if (measures.isEmpty()) return null
    val first = measures[startIndex.coerceIn(0, measures.lastIndex)]
    val last = measures[endIndex.coerceIn(0, measures.lastIndex)]
    val region = if (first.start <= last.start) LoopRegion(first.start, last.end) else LoopRegion(last.start, first.end)
    return region.takeIf { it.isValid }
}

/** The measure under the playhead; a predictable starting point before the user adjusts it. */
internal fun defaultLoopRegion(measures: List<TGMeasure>, cursorTick: Long): LoopRegion? {
    if (measures.isEmpty()) return null
    val index = measures.indexOfMeasureAt(cursorTick)
    return loopRegionFor(measures, index, index)
}

/** "measure 5" / "measures 5–8" using the score's own numbering, not list indices. */
internal fun loopMeasureLabel(measures: List<TGMeasure>, region: LoopRegion): String? {
    if (measures.isEmpty()) return null
    val first = measures[measures.indexOfMeasureAt(region.startTick)].number
    val last = measures[measures.indexOfMeasureAt((region.endTick - 1).coerceAtLeast(region.startTick))].number
    return if (first == last) "measure $first" else "measures $first–$last"
}

/**
 * Tints the looping bars above the notation so the selected range stays visible while
 * scrolling. Only visible measures are visited. `TablatureView` runs `drawOverlay`
 * outside its `canvasViewport`, so the score transform is re-applied here: without it the
 * band would be drawn at unscaled, unscrolled coordinates. A configured but paused range
 * stays visible, dimmed, so it can be re-enabled knowingly.
 */
internal fun DrawScope.drawLoopRegion(layout: TGSongLayout, region: LoopRegion, trackIndex: Int,
    allTracks: Boolean, viewport: Rect, color: Color, factor: Float, active: Boolean = true) {
    withTransform({
        scale(factor,factor,Offset.Zero)
        translate(-viewport.left,-viewport.top)
    }) { drawLoopBars(layout,region,trackIndex,allTracks,viewport,color,active) }
}

private fun DrawScope.drawLoopBars(layout: TGSongLayout, region: LoopRegion, trackIndex: Int,
    allTracks: Boolean, viewport: Rect, color: Color, active: Boolean) {
    for (staff in layout.staves) {
        if (!allTracks && staff.trackIndex != trackIndex) continue
        for (measure in layout.visibleMeasures(staff, viewport)) {
            if (measure.startTick >= region.endTick || measure.endTick <= region.startTick) continue
            val top = measure.staffTop ?: measure.tabTop ?: measure.bounds.top
            val bottom = measure.tabBottom ?: measure.bounds.bottom
            if (bottom <= top) continue
            drawRoundRect(
                color = color.copy(alpha = if (active) .10f else .06f),
                topLeft = Offset(measure.bounds.left, top),
                size = Size(measure.bounds.width, bottom - top),
                cornerRadius = CornerRadius(3f, 3f),
            )
            val edge = color.copy(alpha = if (active) .55f else .30f)
            if (measure.startTick == region.startTick) {
                drawLine(edge, Offset(measure.bounds.left, top), Offset(measure.bounds.left, bottom), strokeWidth = 2f)
            }
            if (measure.endTick == region.endTick) {
                drawLine(edge, Offset(measure.bounds.right, top), Offset(measure.bounds.right, bottom), strokeWidth = 2f)
            }
        }
    }
}

/** Everything the sample UI needs to describe the loop; owned by the session, not the core. */
@Immutable
data class LoopUiState(
    val enabled: Boolean = false,
    val region: LoopRegion? = null,
    /** True while the user is choosing the range by tapping bars on the score. */
    val picking: Boolean = false,
    /** The first bar already picked; the next score tap completes the range. */
    val pendingStart: LoopRegion? = null,
)

data class LoopPickResult(val region: LoopRegion, val complete: Boolean)

/**
 * One tap during "pick on the score". The first tap selects a bar and the second completes
 * a whole-bar range, ordered even when the user taps backwards. Purely score ticks: the
 * transport resolves repeats later.
 */
fun pickLoopRange(measures: List<TGMeasure>, pending: LoopRegion?, tick: Long): LoopPickResult? {
    if (measures.isEmpty()) return null
    val bar = measures[measures.indexOfMeasureAt(tick)]
    val tapped = LoopRegion(bar.start, bar.end)
    if (pending == null || !pending.isValid) return LoopPickResult(tapped, false)
    val start = minOf(pending.startTick, tapped.startTick)
    val end = maxOf(pending.endTick, tapped.endTick)
    return LoopPickResult(LoopRegion(start, end), true)
}
