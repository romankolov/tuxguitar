// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Display-captured glyph pixels, fixed sizing and accidental hit identity on both emulator APIs. */
@RunWith(AndroidJUnit4::class)
class ScoreEngravingDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun closeChordAccidentalsAndGraceInkRemainVisibleAndClickableAcrossModesAndZoom() {
        val beats=List(4) {i->TGBeat(listOf(960L,2400L,2880L,3840L)[i],TGDuration(if(i==1) 8 else 4,dotted=i==0),listOf(TGVoice(notes=listOf(
            TGNote(2,1,pitch=66,effects=TGNoteEffects(grace=if(i==0) TGGraceEffect(1) else null)),
            TGNote(4,2,pitch=63),TGNote(6,3,pitch=61))))) }
        val song=TGSong(tracks=listOf(TGTrack(1,"Close chord",listOf(64,59,55,50,45,40),listOf(TGMeasure(1,TGMeasureHeader(),beats,960)))))
        val state=TablatureState(song,960)
        val mode=mutableStateOf(TGSpacingMode.TuxGuitar)
        val style=mutableStateOf(TGLayoutStyles.Mixed)
        var page: TGSongLayout?=null;var factor=1f
        compose.setContent {
            factor=LocalDensity.current.density*state.zoomScale
            Box(Modifier.size(360.dp,360.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("engraving"),TGLayoutConfig(spacingMode=mode.value,
                    style=style.value,showTempo=false,colors=TGColorPalette.Paper.copy(noteHead=Color.Magenta)),
                    TGCursorConfig(visible=false,follow=false),onLayout={page=it})
            }
        }
        for(s in listOf(TGLayoutStyles.Score,TGLayoutStyles.Mixed)) for(m in TGSpacingMode.entries) for(z in listOf(1f,1.5f)) {
            compose.runOnIdle {style.value=s;mode.value=m;state.setZoom(z);state.scrollTo(y=0f)}
            compose.waitForIdle()
            val p=page!!;assertEquals(360f/z,p.width,.1f);assertEquals(0f,state.maxScrollX,.01f)
            val image=compose.onNodeWithTag("engraving").captureScoreImage()
            image.saveScoreCapture("engraving-$s-$m-$z")
            val pixels=image.toPixelMap()
            val row=p.staves.first();val first=row.measures.first().beats.first()
            val notes=first.voices.single().notes
            assertEquals(3,notes.mapNotNull {it.accidentalBounds}.size)
            notes.forEach {n->
                val r=n.accidentalBounds!!
                assertEquals(n.note.string,p.hitTest(r.center,10f)?.string)
                assertEquals(8f,n.scoreBounds!!.height,.001f)
                assertTrue(r.left>=0 && r.right<=p.width && r.top>=row.bounds.top && r.bottom<=row.bounds.bottom)
                var ink=0
                val x1=(r.left*factor).toInt().coerceIn(0,pixels.width-1);val x2=(r.right*factor).toInt().coerceIn(0,pixels.width-1)
                val y1=(r.top*factor).toInt().coerceIn(0,pixels.height-1);val y2=(r.bottom*factor).toInt().coerceIn(0,pixels.height-1)
                for(y in y1..y2) for(x in x1..x2) {val c=pixels[x,y];if(c.red>.7f && c.blue>.7f && c.green<.4f) ink++}
                assertTrue("$s $m zoom=$z: expected displayed accidental ink inside $r",ink>2)
            }
            notes.forEachIndexed {i,a->notes.drop(i+1).forEach {b->assertFalse(a.accidentalBounds!!.overlaps(b.accidentalBounds!!))}}
            assertTrue(notes.first().graceScoreOrigin!!.y-11.5f>=row.measures.first().notationTop-.01f)
            assertTrue(notes.first().graceScoreOrigin!!.x+6f<notes.mapNotNull {it.accidentalBounds}.minOf {it.left})
            assertTrue(first.voices.single().augmentationDots.all {it.x>notes.maxOf {n->n.scoreBounds!!.right}})
        }
    }
}
