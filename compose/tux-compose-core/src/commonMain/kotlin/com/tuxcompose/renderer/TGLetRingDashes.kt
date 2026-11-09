// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.renderer

import kotlin.math.floor

/**
 * Explicit upstream-sized segments: Android 7 hardware drawLine ignores dash PathEffect.
 * Start from the visible period, not from a potentially huge offscreen caption.
 * Clipping never restarts the phase or changes the score's source coordinates.
 */
internal inline fun forEachLetRingDash(from: Float, to: Float, visibleLeft: Float, visibleRight: Float,
    dashSize: Float = 3f, dashGap: Float = 2f,
    draw: (Float,Float) -> Unit) {
    if (!from.isFinite() || !to.isFinite() || !visibleLeft.isFinite() || !visibleRight.isFinite()) return
    if(!dashSize.isFinite() || !dashGap.isFinite() || dashSize<=0f || dashGap<0f) return
    val stride=dashSize+dashGap
    if(!stride.isFinite()) return
    val start=maxOf(from,visibleLeft)
    val end=minOf(to,visibleRight)
    if (end<=start) return
    var period=from+floor((start-from)/stride)*stride
    while (period<end) {
        val left=maxOf(period,start)
        val right=minOf(period+dashSize,end)
        if (right>left) draw(left,right)
        val next=period+stride
        if (next<=period) break // Float precision guard for arbitrary external coordinates.
        period=next
    }
}
