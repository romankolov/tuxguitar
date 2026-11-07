// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.component.*
import kotlin.test.*

class CapsuleCursorTest {
    @Test fun completeStrokeIsInsetAndHasATransparentInterior() {
        val config = TGCursorConfig(style=CursorStyle.Capsule)
        val g = capsuleCursorGeometry(80.25f,Rect(0f,10f,300f,150f),config)!!
        val outer = g.pathBounds.inflate(g.strokeWidth/2f)
        assertEquals(80.25f,outer.center.x,.0001f)
        assertEquals(17f,outer.width,.0001f)
        assertEquals(16f,outer.top,.0001f)
        assertEquals(144f,outer.bottom,.0001f)
        assertTrue(g.pathBounds.width-g.strokeWidth > 0f)
        assertEquals(g.pathBounds.width/2f,g.radius,.0001f)
    }
    @Test fun tinyRowsAndHugeStrokeRequestsDoNotCollapseOrOverflow() {
        for (height in listOf(.5f,2f,6f,12f,150f)) {
            val g = capsuleCursorGeometry(25f,Rect(0f,0f,50f,height),TGCursorConfig(width=100f,capsuleInset=100f))!!
            val outer = g.pathBounds.inflate(g.strokeWidth/2f)
            assertTrue(outer.top >= -.0001f && outer.bottom <= height+.0001f)
            assertTrue(g.strokeWidth > 0f && g.radius > 0f)
            assertTrue(g.pathBounds.width > g.strokeWidth)
            assertTrue(g.pathBounds.height > g.strokeWidth)
        }
    }
    @Test fun emptyOrInvalidGeometryDoesNotDraw() {
        val config = TGCursorConfig()
        assertNull(capsuleCursorGeometry(0f,Rect.Zero,config))
        assertNull(capsuleCursorGeometry(Float.NaN,Rect(0f,0f,10f,100f),config))
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,10f,Float.POSITIVE_INFINITY),config))
    }
    @Test fun configurationRejectsInvalidCapsuleDimensions() {
        assertFailsWith<IllegalArgumentException> { TGCursorConfig(capsuleWidth=0f) }
        assertFailsWith<IllegalArgumentException> { TGCursorConfig(capsuleWidth=Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TGCursorConfig(capsuleInset=-1f) }
        assertFailsWith<IllegalArgumentException> { TGCursorConfig(capsuleInset=Float.POSITIVE_INFINITY) }
        for (padding in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { TGCursorConfig(capsuleInnerPadding=padding) }
    }
    @Test fun defaultInnerMarginClearsTheCompleteNoteInk() {
        val cfg=TGCursorConfig(style=CursorStyle.Capsule)
        val g=capsuleCursorGeometry(50f,Rect(0f,0f,200f,150f),cfg,4f)!!
        val inside=g.pathBounds.width/2f-g.strokeWidth/2f
        assertEquals(cfg.capsuleInnerPadding,inside-4f,.0001f)
    }
    @Test fun multiDigitFretsAndDisplacedHeadsGetTheSamePadding() {
        val cfg=TGCursorConfig(capsuleInnerPadding=5f)
        val g=capsuleCursorGeometry(50f,Rect(0f,0f,200f,150f),cfg,8f)!!
        assertEquals(29f,g.pathBounds.width+g.strokeWidth,.0001f)
        assertEquals(5f,g.pathBounds.width/2f-g.strokeWidth/2f-8f,.0001f)
    }
    @Test fun explicitMinimumWidthCanAddSpaceButNeverRemoveTheInnerMargin() {
        val cfg=TGCursorConfig(capsuleWidth=44f,capsuleInnerPadding=2f)
        val g=capsuleCursorGeometry(50f,Rect(0f,0f,200f,150f),cfg,4f)!!
        assertEquals(44f,g.pathBounds.width+g.strokeWidth,.0001f)
        assertTrue(g.pathBounds.width/2f-g.strokeWidth/2f-4f >= 2f)
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,100f,150f),cfg,Float.NaN))
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,100f,150f),cfg,-1f))
    }
    @Test fun fractionalCursorPositionsAreNotSnappedAndGeometryScalesExactlyOnce() {
        val b=Rect(0f,0f,100f,150f)
        val a=capsuleCursorGeometry(30.125f,b,TGCursorConfig())!!
        val c=capsuleCursorGeometry(30.375f,b,TGCursorConfig())!!
        assertEquals(.25f,c.pathBounds.center.x-a.pathBounds.center.x,.0001f)
        for (factor in listOf(1f,1.25f,2.625f,4f))
            assertEquals(17f*factor,(a.pathBounds.width+a.strokeWidth)*factor,.001f)
    }
}
