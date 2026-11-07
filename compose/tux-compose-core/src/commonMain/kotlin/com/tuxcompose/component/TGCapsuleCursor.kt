// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.component

import androidx.compose.ui.geometry.Rect

/** Stroke centreline, not its outer bounds. All values are logical notation units. */
internal data class TGCapsuleCursorGeometry(val pathBounds: Rect, val radius: Float, val strokeWidth: Float)

/** Keep the complete stroke inset, and retain a transparent interior even on very short rows. */
internal fun capsuleCursorGeometry(x: Float, bounds: Rect, config: TGCursorConfig, inkHalfWidth: Float = 4f): TGCapsuleCursorGeometry? {
    val height = bounds.height
    if (!x.isFinite() || !bounds.top.isFinite() || !height.isFinite() || height <= 0f) return null
    if (!inkHalfWidth.isFinite() || inkHalfWidth < 0f) return null
    // width is a minimum, not a tight six-unit outline through an eight-unit note.
    // Inner half-width = outer half-width - the complete stroke, hence +width here.
    val requested = maxOf(config.capsuleWidth,2f*(inkHalfWidth+config.capsuleInnerPadding+config.width))
    val width = minOf(requested,height)
    val inset = config.capsuleInset.coerceAtMost((height-width)/2f)
    val outerHeight = height-2f*inset
    val stroke = minOf(config.width,width/3f,outerHeight/3f)
    if (stroke <= 0f) return null
    val half = stroke/2f
    return TGCapsuleCursorGeometry(
        Rect(x-width/2f+half,bounds.top+inset+half,x+width/2f-half,bounds.bottom-inset-half),
        (minOf(width,outerHeight)-stroke)/2f,stroke)
}
