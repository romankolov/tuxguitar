// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt

/**
 * Compose's Window PixelCopy test helper needs API 26. On the supported API-24
 * floor, crop an ACTUAL UiAutomation display screenshot instead of skipping pixel
 * tests or substituting an offscreen software re-draw with different rasterization.
 */
fun SemanticsNodeInteraction.captureScoreImage(): ImageBitmap {
    if(Build.VERSION.SDK_INT>=26) return captureToImage()
    val node=fetchSemanticsNode() // synchronizes pending Compose/layout work
    val bounds=node.boundsInWindow
    val display=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    val left=bounds.left.roundToInt().coerceIn(0,display.width-1)
    val top=bounds.top.roundToInt().coerceIn(0,display.height-1)
    val right=bounds.right.roundToInt().coerceIn(left+1,display.width)
    val bottom=bounds.bottom.roundToInt().coerceIn(top+1,display.height)
    val cropped=Bitmap.createBitmap(display,left,top,right-left,bottom-top)
    if(cropped!==display) display.recycle()
    return cropped.asImageBitmap()
}
