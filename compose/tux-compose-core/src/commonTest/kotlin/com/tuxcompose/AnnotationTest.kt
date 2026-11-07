// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.*

class AnnotationTest {
    private val tuning=listOf(64,59,55,50,45,40)
    private val base=TGLayoutConfig(justifySystems=false)
    private fun bar(index: Int=0, count: Int=8, flags: Set<Int> =(0 until count).toSet(), other: Boolean=false): TGMeasure {
        val d=TGDuration(if (count==64) 64 else 8)
        val start=960+index*3840L
        return TGMeasure(index+1,TGMeasureHeader(),List(count) { i ->
            TGBeat(start+i*d.ticks,d,listOf(TGVoice(notes=listOf(TGNote(5,1,effects=TGNoteEffects(
                letRing=i in flags,palmMute=other && i==1,tapping=other && i==2,vibrato=other && i==3))))),
                text=if (other && i in listOf(1,2,4)) listOf("","with a gentle touch","then softer","","after the ringing passage")[i] else null)
        },start)
    }
    private fun song(vararg bars: TGMeasure)=TGSong(tracks=listOf(TGTrack(1,"Guitar",tuning,bars.toList())))
    private fun layout(data: TGSong, width: Float=900f, config: TGLayoutConfig=base, fonts: TGFontMetrics=TGFontMetrics.Approximate)=TGLayoutCalculator(config,fonts).calculate(data,width)
    private fun TGSongLayout.rings()=staves.flatMap { it.annotations }.filter { it.kind==TGAnnotationKind.LetRing }
    private fun assertClear(page: TGSongLayout) {
        page.staves.forEach { row ->
            row.annotations.forEach { a ->
                assertTrue(a.bounds.top >= row.bounds.top-.01f)
                assertTrue(a.bounds.bottom <= row.bounds.bottom+.01f)
                assertTrue(a.labelBounds.left >= a.clipBounds.left-.01f)
                assertTrue(a.labelBounds.right <= a.clipBounds.right+.01f)
                assertTrue(a.bounds.height.isFinite())
                a.lineFrom?.let { assertTrue(it.x >= a.labelBounds.right+2.99f || it.y >= a.labelBounds.bottom+3f) }
            }
            row.annotations.forEachIndexed { i,a -> row.annotations.drop(i+1).forEach { b ->
                assertFalse(a.bounds.overlaps(b.bounds),"Annotations collide: ${a.lines} and ${b.lines}")
            } }
        }
    }
    @Test fun threeMarkedAttacksBecomeOneLabelDashedLineAndEndCap() {
        val page=layout(song(bar(flags=setOf(0,1,2))))
        val a=page.rings().single()
        assertEquals(listOf("let ring"),a.lines)
        assertEquals(960L,a.sourceTick)
        assertEquals(2400L,a.endTick)
        assertTrue(a.endCap)
        val b=page.staves[0].measures[0].beats[2]
        assertEquals(b.endX-2f,a.lineTo!!.x,.001f)
        assertTrue(page.staves[0].measures[0].plannedAnnotations)
        assertClear(page)
    }
    @Test fun middleAndFollowingEffectsAndProseUseMeasuredNonOverlappingLanes() {
        val page=layout(song(bar(other=true)),360f)
        val labels=page.staves.flatMap { it.annotations }.flatMap { it.lines }
        assertTrue(labels.any { it.contains("let ring") })
        assertTrue(labels.any { it=="P.M." })
        assertTrue(labels.any { it=="T" })
        assertTrue(labels.joinToString(" ").contains("after the ringing passage"))
        assertTrue(page.staves.flatMap { it.annotations }.any { it.kind==TGAnnotationKind.Vibrato })
        assertClear(page)
    }
    @Test fun unmarkedAttackAndRestStopRunsRatherThanMergingUnrelatedPhrases() {
        val source=bar(flags=setOf(0,1,3,4,6,7))
        val beats=source.beats.mapIndexed { i,b -> if (i!=5) b else b.copy(voices=listOf(TGVoice()).toPersistentList()) }
        val page=layout(song(source.copy(beats=beats.toPersistentList())))
        assertEquals(listOf(960L,2400L,3840L),page.rings().map { it.sourceTick })
        assertEquals(listOf(1920L,3360L,4800L),page.rings().map { it.endTick })
        assertClear(page)
    }
    @Test fun consecutiveBarsShareOneRunButRepeatAndEndingBoundariesStopIt() {
        assertEquals(1,layout(song(bar(),bar(1))).rings().size)
        val first=bar().copy(header=TGMeasureHeader(repeatClose=1))
        assertEquals(2,layout(song(first,bar(1))).rings().size)
        val ending=bar(1).copy(header=TGMeasureHeader(repeatAlternative=1))
        assertEquals(2,layout(song(bar(),ending)).rings().size)
    }
    @Test fun oversizedMeasuresContinueWithRowLocalLinesAndOneCapAtTheRealEnd() {
        val source=bar(count=64)
        val page=layout(song(source,bar(1,flags=emptySet())),400f)
        val spans=page.rings()
        assertTrue(spans.size>2)
        assertFalse(spans.first().continuesFromPrevious)
        assertTrue(spans.drop(1).all { it.continuesFromPrevious })
        assertTrue(spans.dropLast(1).all { it.continuesToNext && !it.endCap })
        assertFalse(spans.last().continuesToNext)
        assertTrue(spans.last().endCap)
        assertTrue(spans.drop(1).all { it.lines==listOf("(let ring)") })
        assertEquals(400f,page.width,0f)
        assertTrue(page.staves.flatMap { it.measures }.all { it.contentScale==1f })
        assertClear(page)
    }
    @Test fun sustainedVoiceSurvivesOtherVoiceReattacksAndTiedContinuations() {
        val source=bar(flags=emptySet())
        val beats=source.beats.mapIndexed { i,b -> b.copy(voices=(listOf(TGVoice(1,listOf(TGNote(7,2))))+
            if (i==0) listOf(TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2)))
            else if (i==4) listOf(TGVoice(0,listOf(TGNote(5,1,tied=true)),duration=TGDuration(2))) else emptyList()).toPersistentList()) }
        val runs=letRingRuns(TGTrack(1,"Voices",tuning,listOf(source.copy(beats=beats.toPersistentList()))))
        assertEquals(listOf(960L to 4800L),runs.map { it.start to it.end })
    }
    @Test fun overlappingMarkedVoicesShareOneDisplaySpan() {
        val source=bar(flags=emptySet())
        val beats=source.beats.mapIndexed { i,b -> b.copy(voices=listOf(when (i) {
            0 -> TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2))
            2 -> TGVoice(1,listOf(TGNote(7,2,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2))
            else -> TGVoice(2,listOf(TGNote(9,3)))
        }).toPersistentList()) }
        val page=layout(song(source.copy(beats=beats.toPersistentList())))
        assertEquals(1,page.rings().size)
        assertEquals(3840L,page.rings().single().endTick)
        assertClear(page)
    }
    @Test fun gapsSplitRunsAndInactiveVoicePlaceholdersDoNotCancelAHeldNote() {
        val marked=TGNote(5,1,effects=TGNoteEffects(letRing=true))
        val held=TGMeasure(1,TGMeasureHeader(),listOf(
            TGBeat(960,TGDuration(2),listOf(TGVoice(notes=listOf(marked)))),
            TGBeat(1440,TGDuration(8),listOf(TGVoice(empty=true)))
        ),960)
        assertEquals(listOf(960L to 2880L),letRingRuns(TGTrack(1,"Held",tuning,listOf(held))).map { it.start to it.end })
        val gap=held.copy(beats=listOf(960L,1920L).map { TGBeat(it,TGDuration(8),listOf(TGVoice(notes=listOf(marked)))) }.toPersistentList())
        assertEquals(2,letRingRuns(TGTrack(1,"Gap",tuning,listOf(gap))).size)
    }
    @Test fun separateTracksNeverAcquireEachOthersLetRingSpans() {
        val data=TGSong(tracks=listOf(TGTrack(1,"Ring",tuning,listOf(bar())),TGTrack(2,"Dry",tuning,listOf(bar(flags=emptySet())))))
        val page=layout(data,600f,base.copy(multiTrack=true))
        assertEquals(1,page.staves.filter { it.trackIndex==0 }.sumOf { it.annotations.count { a -> a.kind==TGAnnotationKind.LetRing } })
        assertTrue(page.staves.filter { it.trackIndex==1 }.all { it.annotations.isEmpty() })
    }
    @Test fun annotationPackingDoesNotChangeNativeOnsetsOrGlyphSizes() {
        val data=song(bar(other=true),bar(1))
        val plain=layout(data,800f,base.copy(mergeLetRing=false))
        val planned=layout(data,800f)
        assertEquals(plain.staves.map { it.measures.map { m -> m.measureIndex } },planned.staves.map { it.measures.map { m -> m.measureIndex } })
        val before=plain.staves.flatMap { it.measures }.flatMap { it.beats }
        val after=planned.staves.flatMap { it.measures }.flatMap { it.beats }
        assertEquals(before.map { it.x },after.map { it.x })
        before.zip(after).forEach { (a,b) ->
            val beforeBounds=a.voices[0].notes[0].tabBounds!!; val afterBounds=b.voices[0].notes[0].tabBounds!!
            assertEquals(beforeBounds.width,afterBounds.width,.001f)
            assertEquals(beforeBounds.height,afterBounds.height,.001f)
        }
        assertClear(planned)
    }
    @Test fun longCustomFontCaptionsWrapWithoutCoveringTheNextCaption() {
        val fonts=TGFontMetrics { value,_ -> TGTextSize(value.length*12f,22f) }
        val page=layout(song(bar(other=true)),260f,fonts=fonts)
        assertTrue(page.staves.flatMap { it.annotations }.any { it.lines.size>1 })
        assertClear(page)
    }
    @Test fun scoreOnlyCaptionsUseTheOriginalEffectsBandAboveScoreInk() {
        val page=layout(song(bar(other=true)),800f,base.copy(style=TGLayoutStyles.Score))
        page.staves.forEach { row ->
            val first=row.measures.first()
            row.annotations.filter { it.band==TGAnnotationBand.Effects }.forEach { a ->
                assertTrue(a.bounds.bottom<=first.staffTop!!+.001f)
            }
        }
        assertClear(page)
    }
    @Test fun unicodeEmergencyWrappingPreservesCodePointsAndExplicitNewlines() {
        val original="𝄞𝄞𝄞𝄞"
        val lines=wrapAnnotationText(original,22f,TGFontMetrics.Approximate)
        assertEquals(original,lines.joinToString(""))
        assertTrue(lines.all { it.length%2==0 })
        assertEquals(listOf("first","second"),wrapAnnotationText("first\nsecond",200f,TGFontMetrics.Approximate))
    }
    @Test fun horizontallyOffscreenSourceStillHasAVisibleContinuation() {
        val page=layout(song(*Array(40) { bar(it) }),600f,base.copy(orientation=TGLayoutOrientation.Horizontal))
        val row=page.staves.single()
        val ring=page.rings().single()
        val viewport=Rect(1800f,row.bounds.top,2000f,row.bounds.bottom)
        assertTrue(ring.labelBounds.right < viewport.left)
        assertTrue(page.visibleAnnotations(row,viewport).any { it.kind==TGAnnotationKind.LetRing })
        assertTrue(ring.lineTo!!.x>viewport.right)
    }
    @Test fun tinyViewportsHaveFiniteClippedAnnotationsWithoutShrinkingNotation() {
        for (width in listOf(1f,40f,100f)) {
            val page=layout(song(bar()),width)
            assertEquals(width,page.width,0f)
            assertTrue(page.height.isFinite())
            assertTrue(page.staves.flatMap { it.measures }.all { it.contentScale==1f })
            page.staves.flatMap { it.annotations }.forEach { assertTrue(it.bounds.width.isFinite()) }
        }
    }
}
