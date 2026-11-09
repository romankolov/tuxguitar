// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import com.tuxcompose.layout.TGSkyline
import kotlin.random.Random
import kotlin.test.*

class SkylineTest {
    @Test fun touchingIntervalsDoNotLiftOneAnother() {
        val s=TGSkyline(0f,100f);s.reserve(0f,20f,8f)
        assertEquals(0f,s.maximum(20f,40f));assertEquals(8f,s.maximum(19f,21f))
    }
    @Test fun fullContainmentAndNestedRangesUseMaximumNotLastWriter() {
        val s=TGSkyline(0f,100f);s.reserve(10f,90f,40f);s.reserve(20f,30f,2f)
        assertEquals(40f,s.maximum(0f,100f));assertEquals(40f,s.maximum(25f,26f))
    }
    @Test fun equalNeighboursCoalesceAndSentinelSurvives() {
        val s=TGSkyline(0f,100f)
        repeat(100) {s.reserve(it.toFloat(),it+1f,20f)}
        assertEquals(1,s.segmentCount);assertEquals(20f,s.height)
        assertEquals(0f,s.maximum(100f,110f))
    }
    @Test fun outsideAndEmptyIntervalsCannotCreatePhantomReservations() {
        val s=TGSkyline(0f,100f)
        s.reserve(-50f,-10f,30f);s.reserve(15f,15f,20f);s.reserve(110f,120f,40f)
        assertEquals(0f,s.height);assertEquals(1,s.segmentCount)
        s.reserve(-50f,10f,9f);assertEquals(9f,s.maximum(-100f,1000f))
    }
    @Test fun adversarialInsertionsMatchBruteForceCells() {
        val r=Random(1042);val s=TGSkyline(0f,256f);val oracle=FloatArray(256)
        repeat(4096) {
            val a=r.nextInt(256);val b=r.nextInt(a+1,257);val h=r.nextInt(200).toFloat()
            s.reserve(a.toFloat(),b.toFloat(),h)
            for(i in a until b) oracle[i]=maxOf(oracle[i],h)
            val x=r.nextInt(256);val y=r.nextInt(x+1,257)
            assertEquals((x until y).maxOf {oracle[it]},s.maximum(x.toFloat(),y.toFloat()))
            assertTrue(s.segmentCount<=257)
        }
    }
    @Test fun invalidAndNonFiniteGeometryFailsBeforeMutation() {
        assertFailsWith<IllegalArgumentException> {TGSkyline(0f,Float.NaN)}
        val s=TGSkyline(0f,100f)
        assertFailsWith<IllegalArgumentException> {s.reserve(20f,10f,4f)}
        assertFailsWith<IllegalArgumentException> {s.reserve(0f,10f,Float.POSITIVE_INFINITY)}
        assertEquals(0f,s.height)
    }
}
