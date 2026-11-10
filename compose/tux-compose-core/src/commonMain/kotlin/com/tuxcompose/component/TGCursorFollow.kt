// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.layout.TGCursorLayout

/** Visibility is used to rejoin after manual browsing, not to block normal system transitions. */
internal fun cursorIntersectsViewport(cursor: TGCursorLayout?, viewport: Rect, notationOnly: Boolean = false): Boolean = cursor != null &&
    cursor.x >= viewport.left && cursor.x <= viewport.right &&
    (if (notationOnly) cursor.capsuleBounds else cursor.bounds).let { it.bottom > viewport.top && it.top < viewport.bottom }

/**
 * Test against the PLANNED viewport, not the in-flight animated scroll position. Otherwise
 * every playback tick would cancel/restart the animation as soon as the cursor becomes visible.
 */
internal fun cursorFollowDestination(
    cursor: TGCursorLayout,
    planned: Offset,
    width: Float,
    height: Float,
    maxX: Float,
    maxY: Float,
): Offset {
    var x = planned.x
    var y = planned.y
    val margin = minOf(12f,width / 4)
    if (cursor.x < x + margin || cursor.x > x + width - margin) x = cursor.x - width * .25f
    val bounds = cursor.beat.bounds
    if (bounds.top < y || bounds.bottom > y + height) y = bounds.top - 8f
    return Offset(x.coerceIn(0f,maxX),y.coerceIn(0f,maxY))
}
