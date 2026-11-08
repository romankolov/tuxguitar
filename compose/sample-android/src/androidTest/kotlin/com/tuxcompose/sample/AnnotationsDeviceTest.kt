// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.rememberTextMeasurer
import com.tuxcompose.renderer.TGComposeTextMetrics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.tuxcompose.component.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual-font bounds, hollow cursor pixels, dashed continuation pixels and zoom transforms. */
@RunWith(AndroidJUnit4::class)
class AnnotationsDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val tuning=listOf(64,59,55,50,45,40)
    private fun song(ring: Boolean)=TGSong(tracks=listOf(TGTrack(1,"Annotations",tuning,List(2) { mi ->
        val d=TGDuration(if (ring && mi==0) 64 else 4)
        val start=960+mi*3840L
        TGMeasure(mi+1,TGMeasureHeader(),List((3840/d.ticks).toInt()) { i ->
            TGBeat(start+i*d.ticks,d,listOf(TGVoice(notes=listOf(TGNote(5,1,effects=TGNoteEffects(
                letRing=ring && mi==0,palmMute=ring && mi==0 && i==4,tapping=ring && mi==0 && i==8))))),
                text=if (ring && i==5) "play gently through this passage" else if (ring && mi==1 && i==0) "after ringing" else null)
        },start)
    })))
    private fun save(image: ImageBitmap, name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun magenta(c: Color)=c.red>.7f && c.blue>.7f && c.green<.4f

    @Test fun capsuleIsAnInsetHollowStrokeAtNormalAndZoomedDensity() {
        val state=TablatureState(song(false),960)
        val cursor=TGCursorConfig(style=CursorStyle.Capsule,color=Color.Magenta,width=1.2f,
            capsuleWidth=8f,capsuleInset=8f,follow=false,movementAnimationMillis=0)
        var page: TGSongLayout?=null
        var factor=1f
        var slot=0f
        compose.setContent {
            factor=LocalDensity.current.density*state.zoomScale
            val measurer=rememberTextMeasurer()
            val metrics=remember(factor) { TGComposeTextMetrics(measurer,TGTypography(),renderScale=factor) }
            slot=(0..99).maxOf {metrics.measure(it.toString().padStart(2,'0'),TGTextRole.Note).width}
            Box(Modifier.size(360.dp,280.dp)) {
                TablatureView(state,Modifier.fillMaxSize().testTag("capsule"),TGLayoutConfig(),cursor,onLayout={page=it})
            }
        }
        for (zoom in listOf(1f,1.5f)) {
            compose.runOnIdle { state.setZoom(zoom) }
            compose.waitForIdle()
            val anchor=page!!.cursorAt(0,960)!!
            val b=anchor.bounds
            val pixels=compose.onNodeWithTag("capsule").captureToImage().also { save(it,"capsule-$zoom") }.toPixelMap()
            fun at(x: Float,y: Float)=pixels[(x*factor).toInt().coerceIn(0,pixels.width-1),(y*factor).toInt().coerceIn(0,pixels.height-1)]
            val cy=b.center.y
            assertFalse("Capsule centre must stay transparent",magenta(at(anchor.x,cy)))
            // A score head is deliberately NOT the width reference. The capsule is
            // one fixed two-digit fret slot, regardless of the active ink shape.
            val noteBounds=anchor.beat.voices.flatMap { it.notes }.mapNotNull {it.tabBounds}
            val inkHalf=slot/2f
            val outerWidth=maxOf(cursor.capsuleWidth,slot+2f*(cursor.capsuleInnerPadding+cursor.width))
            val edge=anchor.x-outerWidth/2f+cursor.width/2f
            assertTrue("The capsule's outlined side must be visible",(-1..1).any { dx -> magenta(at(edge+dx/factor,cy)) })
            noteBounds.forEach { note ->
                for (direction in listOf(-1f,1f)) {
                    val gap=anchor.x+direction*(inkHalf+cursor.capsuleInnerPadding/2f)
                    assertFalse("Inner margin around real note ink must stay clear",magenta(at(gap,note.center.y)))
                }
            }
            assertFalse("Top margin must stay clear",magenta(at(anchor.x,b.top+2f)))
            assertFalse("Bottom margin must stay clear",magenta(at(anchor.x,b.bottom-2f)))
            assertEquals(0f,state.maxScrollX,.01f)
        }
    }

    @Test fun letRingCaptionsAndOtherTextStayClearWithRealFontsReflowAndZoom() {
        val state=TablatureState(song(true),960)
        val style=mutableStateOf(TGLayoutStyles.Mixed)
        var page: TGSongLayout?=null
        var factor=1f
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,1.4f)) {
                factor=density*state.zoomScale
                Box(Modifier.size(360.dp,320.dp)) {
                    TablatureView(state,Modifier.fillMaxSize().testTag("annotations"),
                        TGLayoutConfig(style=style.value,respectSystemFontScale=true,colors=TGColorPalette.Paper.copy(beatText=Color.Magenta)),
                        TGCursorConfig(visible=false,follow=false),onLayout={page=it})
                }
            }
        }
        for (mode in TGLayoutStyles.entries) for (zoom in listOf(1f,1.5f)) {
            compose.runOnIdle { style.value=mode; state.setZoom(zoom); state.scrollTo(y=0f) }
            compose.waitForIdle()
            val layout=page!!
            assertEquals(0f,state.maxScrollX,.01f)
            assertEquals(360f/zoom,layout.width,.1f)
            layout.staves.forEach { row -> row.annotations.forEachIndexed { i,a ->
                assertTrue(a.labelBounds.left>=a.clipBounds.left-.01f && a.labelBounds.right<=a.clipBounds.right+.01f)
                assertTrue(a.bounds.top>=row.bounds.top-.01f && a.bounds.bottom<=row.bounds.bottom+.01f)
                row.annotations.drop(i+1).forEach { b -> assertFalse("${a.lines} overlaps ${b.lines}",a.bounds.overlaps(b.bounds)) }
            } }
            val rings=layout.staves.flatMap { it.annotations }.filter { it.kind==TGAnnotationKind.LetRing }
            assertTrue(rings.size>1)
            assertEquals(1,rings.count { !it.continuesFromPrevious })
            assertEquals(1,rings.count { it.endCap })
            // Greedy packing can put the following ordinary bar on a separate, unplanned
            // row. In that case its text must still render through the legacy measure pass.
            val afterRow=layout.staves.single { row -> row.measures.any { m -> m.beats.any { it.beat.text=="after ringing" } } }
            val afterMeasure=afterRow.measures.single { m -> m.beats.any { it.beat.text=="after ringing" } }
            val afterBeat=afterMeasure.beats.single { it.beat.text=="after ringing" }
            val afterCaption=afterRow.annotations.firstOrNull { it.band==TGAnnotationBand.Text && it.sourceTick==afterBeat.startTick }
            if (afterMeasure.plannedAnnotations) {
                assertNotNull("$mode zoom=$zoom: planned following text was lost",afterCaption)
                assertTrue(afterCaption!!.lines.joinToString(" ").contains("after ringing"))
            } else assertNull("Unrelated rows must retain their existing measure-text pass",afterCaption)
            val last=rings.last { it.lineFrom!=null && it.lineTo!=null && it.endCap }
            compose.runOnIdle { state.scrollTo(y=(last.bounds.top-8f).coerceAtLeast(0f)) }
            compose.waitForIdle()
            val pixels=compose.onNodeWithTag("annotations").captureToImage().also { save(it,"annotations-$mode-$zoom") }.toPixelMap()
            val end=last.lineTo!!
            val px=((end.x-state.scrollX)*factor).toInt()
            fun painted(x: Int,y: Int)=(-1..1).any { dx -> (-1..1).any { dy ->
                magenta(pixels[(x+dx).coerceIn(0,pixels.width-1),(y+dy).coerceIn(0,pixels.height-1)])
            } }
            for (dy in listOf(-2f,2f)) {
                val py=((end.y+dy-state.scrollY)*factor).toInt()
                assertTrue("$mode zoom=$zoom: terminal cap must extend vertically beyond the dash at $end, scroll=${state.scrollY}",painted(px,py))
            }
            val from=last.lineFrom!!
            if (end.x-from.x>10f) {
                val py=((end.y-state.scrollY)*factor).toInt().coerceIn(0,pixels.height-1)
                val xs=((from.x+1f-state.scrollX)*factor).toInt()..((end.x-2f-state.scrollX)*factor).toInt()
                val ink=xs.filter { it in 0 until pixels.width }.map { magenta(pixels[it,py]) }
                assertTrue("Dashes must paint some ink",ink.any { it })
                assertTrue("Dashes must retain clear gaps, not a solid line",ink.any { !it })
            }
            val textBounds=afterCaption?.labelBounds ?: Rect(afterBeat.x,afterMeasure.textTop,afterBeat.x+100f,afterMeasure.textTop+20f)
            compose.runOnIdle { state.scrollTo(y=(textBounds.top-8f).coerceAtLeast(0f)) }
            compose.waitForIdle()
            val afterPixels=compose.onNodeWithTag("annotations").captureToImage().also { save(it,"after-$mode-$zoom") }.toPixelMap()
            val lx=((textBounds.left-state.scrollX)*factor).toInt().coerceIn(0,afterPixels.width-1)
            val rx=((textBounds.right-state.scrollX)*factor).toInt().coerceIn(lx,afterPixels.width-1)
            val ty=((textBounds.top-state.scrollY)*factor).toInt().coerceIn(0,afterPixels.height-1)
            val by=((textBounds.bottom-state.scrollY)*factor).toInt().coerceIn(ty,afterPixels.height-1)
            assertTrue("$mode zoom=$zoom: following text must paint in both planned and untouched rows",
                (lx..rx).any { x -> (ty..by).any { y -> magenta(afterPixels[x,y]) } })
        }
    }
}
