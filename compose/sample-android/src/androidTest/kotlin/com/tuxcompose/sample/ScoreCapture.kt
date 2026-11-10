// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.graphics.Bitmap
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Compose's Window PixelCopy helper needs API 26. At API 24, first fence an actual
 * hardware draw and two display vsyncs, then crop a real UiAutomation screenshot.
 * Merely waiting for Compose idleness can capture the PREVIOUS SurfaceFlinger frame,
 * especially while the test clock is paused to inspect an intermediate animation.
 * No pixel test is skipped and no software/offscreen redraw is substituted.
 */
fun SemanticsNodeInteraction.captureScoreImage(): ImageBitmap {
    if(Build.VERSION.SDK_INT>=26) return captureToImage()
    val node=fetchSemanticsNode()
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val view=(requireNotNull(node.root) as ViewRootForTest).view
    val drawn=CountDownLatch(1)
    instrumentation.runOnMainSync {
        var queued=false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener=ViewTreeObserver.OnDrawListener {
            if(!queued) {
                queued=true
                // Listeners may not be removed during dispatchDraw itself.
                view.post {
                    if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                    view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
        view.postInvalidateOnAnimation()
    }
    check(drawn.await(5,TimeUnit.SECONDS)) { "Timed out waiting for the API-24 display draw" }
    val bounds=node.boundsInWindow
    val display=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    // boundsInWindow is not necessarily screen-relative (dialogs/insets). Include
    // the root view's real screen/window translation instead of assuming origin 0.
    val screen=IntArray(2); val window=IntArray(2)
    instrumentation.runOnMainSync { view.getLocationOnScreen(screen); view.getLocationInWindow(window) }
    val dx=screen[0]-window[0]; val dy=screen[1]-window[1]
    val left=(bounds.left+dx).roundToInt().coerceIn(0,display.width-1)
    val top=(bounds.top+dy).roundToInt().coerceIn(0,display.height-1)
    val right=(bounds.right+dx).roundToInt().coerceIn(left+1,display.width)
    val bottom=(bounds.bottom+dy).roundToInt().coerceIn(top+1,display.height)
    val cropped=Bitmap.createBitmap(display,left,top,right-left,bottom-top)
    if(cropped!==display) display.recycle()
    return cropped.asImageBitmap()
}

/**
 * Preserve real hardware captures BEFORE UTP uninstalls the debug target. App-owned
 * external files can disappear during connectedAndroidTest cleanup, before adb pull.
 * UiAutomation copies the already-captured PNG into a shell-owned QA directory;
 * this neither redraws the UI nor requires storage permission in the shipped app.
 */
fun ImageBitmap.saveScoreCapture(name: String) {
    require(name.matches(Regex("[A-Za-z0-9_.-]+")))
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots").apply {check(mkdirs() || isDirectory)}
    val source=File(folder,"$name.png")
    source.outputStream().use {check(asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it))}
    // UiAutomation executes argv via Runtime.exec, NOT a shell expression. Never
    // pass &&, redirects or shell quoting as though they would be interpreted.
    fun shellBytes(command: String): ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
    check(source.absolutePath.matches(Regex("[A-Za-z0-9_/.-]+")))
    val destination="/sdcard/Download/tux-compose-qa/$name.png"
    shellBytes("mkdir -p /sdcard/Download/tux-compose-qa")
    shellBytes("cp ${source.absolutePath} $destination")
    val preserved=shellBytes("cat $destination")
    val original=source.readBytes()
    check(preserved.contentEquals(original)) {
        "Could not preserve hardware capture $name: copied ${preserved.size}/${original.size} bytes"
    }
}
