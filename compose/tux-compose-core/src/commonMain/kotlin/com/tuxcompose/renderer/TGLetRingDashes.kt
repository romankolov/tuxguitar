// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.renderer

import kotlin.math.floor

/**
 * Explicit 3-on/2-off segments: Android 7 hardware drawLine ignores dash PathEffect.
 * Start from the visible period, not from a potentially huge offscreen caption.
 * Clipping never restarts the phase or changes the score's source coordinates.
 */
internal inline fun forEachLetRingDash(from: Float, to: Float, visibleLeft: Float, visibleRight: Float,
    draw: (Float,Float) -> Unit) {
    if (!from.isFinite() || !to.isFinite() || !visibleLeft.isFinite() || !visibleRight.isFinite()) return
    val start=maxOf(from,visibleLeft)
    val end=minOf(to,visibleRight)
    if (end<=start) return
    var period=from+floor((start-from)/5f)*5f
    while (period<end) {
        val left=maxOf(period,start)
        val right=minOf(period+3f,end)
        if (right>left) draw(left,right)
        val next=period+5f
        if (next<=period) break // Float precision guard for arbitrary external coordinates.
        period=next
    }
}
