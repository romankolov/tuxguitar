// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.component.*
import kotlin.test.*

class CapsuleCursorTest {
    private fun outer(g: TGCapsuleCursorGeometry)=g.pathBounds.inflate(g.strokeWidth/2f)
    @Test fun completeStrokeIsInsetAndHasATransparentInterior() {
        val g=capsuleCursorGeometry(80.25f,Rect(0f,10f,300f,150f),TGCursorConfig())!!
        assertEquals(80.25f,outer(g).center.x,.0001f)
        assertEquals(19f,outer(g).width,.0001f) // 12-unit fixed slot + 2*2 padding + 2*1.5 stroke
        assertEquals(16f,outer(g).top,.0001f);assertEquals(144f,outer(g).bottom,.0001f)
        assertTrue(g.pathBounds.width-g.strokeWidth>0f)
        assertEquals(g.pathBounds.width/2f,g.radius,.0001f)
    }
    @Test fun widthIsIndependentOfTheActiveBeatChordAndRowDimensions() {
        for(width in listOf(3f,12f,40f,300f,900f)) for(height in listOf(3f,15f,50f,150f,400f)) {
            val g=capsuleCursorGeometry(80f,Rect(0f,10f,width,10f+height),TGCursorConfig())!!
            assertEquals(19f,outer(g).width,.0001f,"Must not pulse with score heads, voices, rests or row height")
        }
    }
    @Test fun tinyRowsAndHugeStrokeRequestsDoNotCollapseOrOverflowVertically() {
        for(height in listOf(.5f,2f,6f,12f,150f)) {
            val g=capsuleCursorGeometry(25f,Rect(0f,0f,50f,height),TGCursorConfig(width=100f,capsuleInset=100f))!!
            assertTrue(outer(g).top>=-.0001f && outer(g).bottom<=height+.0001f)
            assertTrue(g.strokeWidth>0f && g.radius>0f)
            assertTrue(g.pathBounds.width>g.strokeWidth && g.pathBounds.height>g.strokeWidth)
        }
    }
    @Test fun emptyOrInvalidGeometryDoesNotDraw() {
        val c=TGCursorConfig()
        assertNull(capsuleCursorGeometry(0f,Rect.Zero,c))
        assertNull(capsuleCursorGeometry(Float.NaN,Rect(0f,0f,10f,100f),c))
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,10f,Float.POSITIVE_INFINITY),c))
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,100f,150f),c,Float.NaN))
        assertNull(capsuleCursorGeometry(0f,Rect(0f,0f,100f,150f),c,-1f))
    }
    @Test fun configurationRejectsInvalidCapsuleDimensions() {
        for(w in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> {TGCursorConfig(capsuleWidth=w)}
        for(p in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {TGCursorConfig(capsuleInset=p)}
            assertFailsWith<IllegalArgumentException> {TGCursorConfig(capsuleInnerPadding=p)}
        }
    }
    @Test fun fixedTwoDigitSlotHasExactInternalPadding() {
        for(slot in listOf(10f,12f,16f,24f)) for(padding in listOf(0f,1f,2f,5f,8f)) {
            val cfg=TGCursorConfig(capsuleInnerPadding=padding)
            val g=capsuleCursorGeometry(50f,Rect(0f,0f,200f,150f),cfg,slot)!!
            val inner=g.pathBounds.width-g.strokeWidth
            assertEquals(slot+2f*padding,inner,.0001f)
        }
    }
    @Test fun explicitOuterFloorIsStableAndCanAccommodateAnUnusualCustomFont() {
        val c=TGCursorConfig(capsuleWidth=44f)
        for(slot in listOf(6f,10f,12f)) {
            val g=capsuleCursorGeometry(50f,Rect(0f,0f,200f,150f),c,slot)!!
            assertEquals(44f,outer(g).width,.0001f)
        }
    }
    @Test fun fractionalCursorPositionsAndDensityAreNotSnappedOrDoubleScaled() {
        val b=Rect(0f,0f,100f,150f)
        val a=capsuleCursorGeometry(30.125f,b,TGCursorConfig())!!
        val c=capsuleCursorGeometry(30.375f,b,TGCursorConfig())!!
        assertEquals(.25f,c.pathBounds.center.x-a.pathBounds.center.x,.0001f)
        for(factor in listOf(1f,1.25f,2.625f,4f)) assertEquals(19f*factor,outer(a).width*factor,.001f)
    }
    @Test fun widthChangesOnlyWhenTheFontSlotOrExplicitConfigurationChanges() {
        val b=Rect(0f,0f,100f,200f)
        val normal=outer(capsuleCursorGeometry(30f,b,TGCursorConfig(),10f)!!).width
        assertEquals(17f,normal,.001f)
        assertEquals(normal+4f,outer(capsuleCursorGeometry(30f,b,TGCursorConfig(capsuleInnerPadding=4f),10f)!!).width,.001f)
        assertEquals(normal+6f,outer(capsuleCursorGeometry(30f,b,TGCursorConfig(),16f)!!).width,.001f)
    }
}
