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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Real hardware pixels: a tiny normalized vector must not become a scaled blurry cache mask. */
@RunWith(AndroidJUnit4::class)
class VectorGlyphDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun filledNoteheadsHaveAnOpaqueCoreAtNormalAndZoomedPhysicalScale() {
        val beats=List(4) {i->TGBeat(960+960L*i,TGDuration(4),listOf(TGVoice(notes=listOf(TGNote(5,1)))))}
        val data=TGSong(tracks=listOf(TGTrack(1,"Vector rasterization",listOf(64,59,55,50,45,40),
            listOf(TGMeasure(1,TGMeasureHeader(),beats,960)))))
        val state=TablatureState(data,960)
        var page: TGSongLayout?=null;var factor=1f
        compose.setContent {
            factor=LocalDensity.current.density*state.zoomScale
            Box(Modifier.size(360.dp,320.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("vector-score"),
                    TGLayoutConfig(style=TGLayoutStyles.Score,showTempo=false,showMeasureNumbers=false,
                        colors=TGColorPalette.Paper.copy(background=Color.White,noteHead=Color.Black,
                            staffLines=Color.Transparent,tablatureLines=Color.Transparent,beatText=Color.Transparent)),
                    TGCursorConfig(visible=false,follow=false),onLayout={page=it})
            }
        }
        for(zoom in listOf(1f,1.75f)) {
            compose.runOnIdle {state.setZoom(zoom)};compose.waitForIdle()
            val heads=page!!.staves.first().measures.flatMap {it.beats}.flatMap {it.voices}.flatMap {it.notes}.mapNotNull {it.scoreBounds}
            assertTrue(heads.isNotEmpty())
            val pixels=compose.onNodeWithTag("vector-score").captureScoreImage().toPixelMap()
            heads.forEach {b->
                // Interior of the original 1.33 x 1.0 TG NOTE curve. Do not let a
                // staff line or the off-centre stem fake opaque notehead coverage.
                val size=b.height*.9f
                val cx=((b.left-.06f*b.height+.665f*size)*factor).roundToInt()
                val cy=((b.top+.06f*b.height+.5f*size)*factor).roundToInt()
                assertTrue(cx in 1 until pixels.width-1 && cy in 1 until pixels.height-1)
                for(dy in -1..1) for(dx in -1..1) {
                    val c=pixels[cx+dx,cy+dy]
                    assertTrue("zoom=$zoom: the native vector core at ($cx,$cy) must be opaque, not a blurred mask: $c",
                        c.red<.2f && c.green<.2f && c.blue<.2f)
                }
            }
        }
    }
}
