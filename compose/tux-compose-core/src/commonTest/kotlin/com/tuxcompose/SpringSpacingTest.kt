// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlin.random.Random
import kotlin.test.*

class SpringSpacingTest {
    @Test fun hardRodsAreNeverSqueezedEvenWithImpossibleTarget() {
        assertEquals(listOf(20f,40f,10f),distributeSprings(listOf(20f,40f,10f),listOf(1.0,2.0,4.0),30f))
    }
    @Test fun waterFillingSaturatesOnlyTheRequiredRods() {
        val result=distributeSprings(listOf(30f,1f),listOf(1.0,2.0),60f)
        assertEquals(30f,result[0],.0001f);assertEquals(30f,result[1],.0001f)
    }
    @Test fun randomizedForceSolutionsPreserveMinimumsAndRequestedWidth() {
        val random=Random(119)
        repeat(2048) {
            val n=random.nextInt(1,24)
            val minima=List(n) {random.nextInt(40).toFloat()}
            val weights=List(n) {random.nextDouble(.1,100.0)}
            val target=minima.sum()+random.nextInt(1000)
            val widths=distributeSprings(minima,weights,target)
            widths.indices.forEach {assertTrue(widths[it]>=minima[it]-.0001f)}
            assertEquals(target,widths.sum(),.002f)
            val forces=widths.indices.filter {widths[it]>minima[it]+.001f}.map {widths[it]/weights[it]}
            if(forces.isNotEmpty()) assertTrue(forces.max()-forces.min()<.001)
        }
    }
    @Test fun invalidWeightsCannotCreateNaNOrAnInfiniteLayoutLoop() {
        assertFailsWith<IllegalArgumentException> {distributeSprings(listOf(1f),listOf(0.0),20f)}
        assertFailsWith<IllegalArgumentException> {distributeSprings(listOf(1f),listOf(Double.NaN),20f)}
        assertFailsWith<IllegalArgumentException> {TGLayoutConfig(springSpacingRatio=Float.NaN)}
        assertFailsWith<IllegalArgumentException> {TGLayoutConfig(springSpacingRatio=2.1f)}
    }
    @Test fun doublingDurationMultipliesComplianceByConfiguredRatio() {
        for(ratio in listOf(1.2f,1.5f,2f)) {
            val (_,p)=elasticMeasureSpacing(0,420,mapOf(0L to 60L,60L to 120L,180L to 240L),emptyList(),60,ratio,18f)
            assertEquals(ratio.toDouble(),p.weights[1]/p.weights[0],.00001)
            assertEquals(ratio.toDouble(),p.weights[2]/p.weights[1],.00001)
        }
    }
    private fun data(): TGSong {
        val bars=List(8) {m->val start=960+m*3840L
            TGMeasure(m+1,TGMeasureHeader(),List(64) {i->TGBeat(start+i*60,TGDuration(64),listOf(TGVoice(notes=listOf(TGNote(i%24,1)))) )},start)}
        return TGSong(tracks=listOf(TGTrack(1,"Springs",listOf(64,59,55,50,45,40),bars)))
    }
    @Test fun everyStyleAndWidthFragmentsWithoutChangingGlyphOrParentSize() {
        for(style in TGLayoutStyles.entries) for(width in listOf(240f,320f,960f)) {
            val p=TGLayoutCalculator(TGLayoutConfig(style=style,spacingMode=TGSpacingMode.Spring)).calculate(data(),width)
            assertEquals(width,p.width);assertTrue(p.staves.size>1)
            p.staves.forEach {row->
                assertTrue(row.bounds.right<=width+.01f)
                row.measures.forEach {m->assertEquals(1f,m.contentScale)
                    m.beats.flatMap {it.voices}.flatMap {it.notes}.forEach {n->n.scoreBounds?.let {assertEquals(8f,it.height,.001f)}}}
            }
        }
    }
    @Test fun simultaneousMultiTrackOnsetsRemainAlignedUnderSpringJustification() {
        val a=data().tracks[0]
        val b=TGTrack(2,"Second",a.stringTuning,a.measures)
        val p=TGLayoutCalculator(TGLayoutConfig(spacingMode=TGSpacingMode.Spring,multiTrack=true)).calculate(TGSong(tracks=listOf(a,b)),320f)
        p.staves.groupBy {it.systemIndex}.values.forEach {rows->
            assertEquals(rows[0].measures.flatMap {it.beats}.map {it.startTick to it.x},rows[1].measures.flatMap {it.beats}.map {it.startTick to it.x})
        }
    }
    @Test fun nativeRemainsTheDefaultAndSpringRequiresExplicitSelection() {
        assertTrue(TGLayoutConfig().nativeSpacing)
        assertFalse(TGLayoutConfig(spacingMode=TGSpacingMode.Spring).nativeSpacing)
    }
}
