// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import com.tuxcompose.renderer.TGComposeTextMetrics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel regressions for the requested fixed width, rather than just testing a duplicated equation. */
@RunWith(AndroidJUnit4::class)
class FixedCapsuleDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun oneAndTwoDigitFretsChordHeadsAndRestsShareOneHollowPaddedWidth() {
        val notes=listOf(listOf(TGNote(3,1)),listOf(TGNote(24,1)),listOf(TGNote(12,1),TGNote(14,2),TGNote(10,3)),emptyList())
        val data=TGSong(tracks=listOf(TGTrack(1,"Capsule",listOf(64,59,55,50,45,40),listOf(
            TGMeasure(1,TGMeasureHeader(),notes.mapIndexed {i,n->TGBeat(960+960L*i,TGDuration(4),listOf(TGVoice(notes=n)))},960)))))
        val state=TablatureState(data,960)
        val mode=mutableStateOf(TGLayoutStyles.Mixed)
        val padding=mutableStateOf(2f)
        var page: TGSongLayout?=null;var factor=1f;var slot=0f
        compose.setContent {
            factor=LocalDensity.current.density*state.zoomScale
            val measurer=rememberTextMeasurer()
            val metrics=remember(factor) {TGComposeTextMetrics(measurer,TGTypography(),renderScale=factor)}
            slot=(0..99).maxOf {metrics.measure(it.toString().padStart(2,'0'),TGTextRole.Note).width}
            Box(Modifier.size(360.dp,300.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("fixed-capsule"),TGLayoutConfig(style=mode.value,justifySystems=false),
                    TGCursorConfig(style=CursorStyle.Capsule,color=Color.Magenta,follow=false,movementAnimationMillis=0,
                        capsuleInnerPadding=padding.value),onLayout={page=it})
            }
        }
        for(zoom in listOf(1f,1.5f)) for(style in TGLayoutStyles.entries) for(p in listOf(2f,5f)) {
            compose.runOnIdle {state.setZoom(zoom);mode.value=style;padding.value=p}
            compose.waitForIdle()
            val widths=mutableListOf<Int>()
            for(tick in listOf(960L,1920L,2880L,3840L)) {
                compose.runOnIdle {state.cursor.seek(tick)};compose.waitForIdle()
                val anchor=page!!.cursorAt(0,tick)!!
                val pixels=compose.onNodeWithTag("fixed-capsule").captureToImage().toPixelMap()
                var lo=pixels.width;var hi=-1
                for(y in 0 until pixels.height) for(x in 0 until pixels.width) {
                    val c=pixels[x,y]
                    if(c.red>.75f && c.blue>.75f && c.green<.25f) {lo=minOf(lo,x);hi=maxOf(hi,x)}
                }
                assertTrue("A hollow cursor must actually be painted",hi>=lo)
                val width=hi-lo+1;widths+=width
                val expected=(slot+2f*(p+1.5f))*factor
                assertEquals("$style/$zoom/$tick",expected,width.toFloat(),2f)
                val cx=(anchor.x*factor).toInt().coerceIn(0,pixels.width-1)
                val cy=(anchor.bounds.center.y*factor).toInt().coerceIn(0,pixels.height-1)
                val center=pixels[cx,cy]
                assertFalse("The interior must not be filled",center.red>.75f && center.blue>.75f && center.green<.25f)
            }
            assertTrue("Beat-dependent widths must not return",widths.max()-widths.min()<=2)
            assertEquals(0f,state.maxScrollX,.01f)
        }
    }
}
