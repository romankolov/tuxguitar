// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.*

class AnnotationTest {
    @Test fun existingAnnotationKindExhaustiveConsumersRemainSourceCompatible() {
        fun legacy(kind: TGAnnotationKind): String = when(kind) {
            TGAnnotationKind.Text->"text";TGAnnotationKind.Accent->"accent";TGAnnotationKind.FadeIn->"fade"
            TGAnnotationKind.Vibrato->"vibrato";TGAnnotationKind.LetRing->"let ring"
        }
        assertEquals(5,TGAnnotationKind.entries.size)
        TGAnnotationKind.entries.forEach {assertTrue(legacy(it).isNotBlank())}
    }
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
                a.lineFrom?.let {
                    assertEquals(a.labelBounds.right+a.dashGap/2f,it.x,.001f)
                    assertEquals(a.labelBounds.center.y,it.y,.001f)
                }
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
        assertEquals(b.x,a.lineTo!!.x,.001f)
        assertEquals(page.staves[0].measures[0].beats.first().x,a.labelBounds.center.x,.001f)
        assertTrue(page.staves[0].measures[0].plannedAnnotations)
        assertClear(page)
    }
    @Test fun middleAndFollowingEffectsAndProseUseMeasuredNonOverlappingLanes() {
        val page=layout(song(bar(other=true)),360f)
        val labels=page.staves.flatMap { it.annotations }.flatMap { it.lines }
        assertTrue(labels.any { it.contains("let ring") })
        assertTrue(labels.any { it=="P.M" })
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
    @Test fun consecutiveFlaggedBeatsRemainLinkedAcrossRepeatAndEndingBoundariesLikeAlphaTab() {
        assertEquals(1,layout(song(bar(),bar(1))).rings().size)
        val first=bar().copy(header=TGMeasureHeader(repeatClose=1))
        assertEquals(1,layout(song(first,bar(1))).rings().size)
        val ending=bar(1).copy(header=TGMeasureHeader(repeatAlternative=1))
        assertEquals(1,layout(song(bar(),ending)).rings().size)
    }
    @Test fun oversizedMeasuresRestartPlainLabelsAndCapEachDrawableRowLikeAlphaTab() {
        val source=bar(count=64)
        val page=layout(song(source,bar(1,flags=emptySet())),400f)
        val spans=page.rings()
        assertTrue(spans.size>2)
        assertFalse(spans.first().continuesFromPrevious)
        assertTrue(spans.drop(1).all { it.continuesFromPrevious })
        assertTrue(spans.dropLast(1).all { it.continuesToNext && (it.endCap==(it.lineFrom!=null)) })
        assertFalse(spans.last().continuesToNext)
        assertEquals(spans.last().lineFrom!=null,spans.last().endCap)
        assertTrue(spans.drop(1).all { it.lines==listOf("let ring") })
        assertEquals(400f,page.width,0f)
        assertTrue(page.staves.flatMap { it.measures }.all { it.contentScale==1f })
        assertClear(page)
    }
    @Test fun explicitlyFlaggedTiedContinuationsSurviveOtherVoiceReattacks() {
        val source=bar(flags=emptySet())
        val beats=source.beats.mapIndexed { i,b -> b.copy(voices=(listOf(TGVoice(1,listOf(TGNote(7,2))))+
            if (i==0) listOf(TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2)))
            else if (i==4) listOf(TGVoice(0,listOf(TGNote(5,1,tied=true,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2))) else emptyList()).toPersistentList()) }
        val runs=letRingRuns(TGTrack(1,"Voices",tuning,listOf(source.copy(beats=beats.toPersistentList()))))
        assertEquals(listOf(960L to 4800L),runs.map { it.start to it.end })
    }
    @Test fun overlappingVoicesKeepTheirOwnEffectBandsInsteadOfInventingAUnion() {
        val source=bar(flags=emptySet())
        val beats=source.beats.mapIndexed { i,b -> b.copy(voices=listOf(when (i) {
            0 -> TGVoice(0,listOf(TGNote(5,1,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2))
            2 -> TGVoice(1,listOf(TGNote(7,2,effects=TGNoteEffects(letRing=true))),duration=TGDuration(2))
            else -> TGVoice(2,listOf(TGNote(9,3)))
        }).toPersistentList()) }
        val page=layout(song(source.copy(beats=beats.toPersistentList())))
        assertEquals(2,page.rings().size)
        assertEquals(listOf(0,1),page.rings().map {it.voiceIndex})
        assertEquals(listOf(2880L,3840L),page.rings().map {it.endTick})
        assertTrue(page.rings().all {it.lineFrom==null && !it.endCap})
        assertClear(page)
    }
    @Test fun flaggedVoiceNeighboursLinkAndInactivePlaceholdersDoNotCancelAHeldNote() {
        val marked=TGNote(5,1,effects=TGNoteEffects(letRing=true))
        val held=TGMeasure(1,TGMeasureHeader(),listOf(
            TGBeat(960,TGDuration(2),listOf(TGVoice(notes=listOf(marked)))),
            TGBeat(1440,TGDuration(8),listOf(TGVoice(empty=true)))
        ),960)
        assertEquals(listOf(960L to 2880L),letRingRuns(TGTrack(1,"Held",tuning,listOf(held))).map { it.start to it.end })
        val gap=held.copy(beats=listOf(960L,1920L).map { TGBeat(it,TGDuration(8),listOf(TGVoice(notes=listOf(marked)))) }.toPersistentList())
        val grouped=letRingRuns(TGTrack(1,"Gap",tuning,listOf(gap))).single()
        assertEquals(960L,grouped.start);assertEquals(1920L,grouped.last)
    }
    @Test fun separateTracksNeverAcquireEachOthersLetRingSpans() {
        val data=TGSong(tracks=listOf(TGTrack(1,"Ring",tuning,listOf(bar())),TGTrack(2,"Dry",tuning,listOf(bar(flags=emptySet())))))
        val page=layout(data,600f,base.copy(multiTrack=true))
        assertEquals(1,page.staves.filter { it.trackIndex==0 }.sumOf { it.annotations.count { a -> a.kind==TGAnnotationKind.LetRing } })
        assertTrue(page.staves.filter { it.trackIndex==1 }.all { it.annotations.isEmpty() })
    }
    @Test fun nativeBaselineAscendersReserveAlphaStaffOverflowBeforeTheFollowingRow() {
        val first=bar(count=64)
        val start=4800L
        val after=TGMeasure(2,TGMeasureHeader(),List(4) {i->
            TGBeat(start+960L*i,TGDuration(4),listOf(TGVoice(notes=listOf(TGNote(5,1)))),
                text=if(i==0) "after ringing" else null)
        },start)
        val fonts=TGFontMetrics {value,role->TGFontMetrics.Approximate.measure(value,role).let {
            TGTextSize(it.width*1.4f,it.height*1.4f)
        }}
        for(style in TGLayoutStyles.entries) for(scale in listOf(1f,2.625f,3.9375f)) {
            val page=layout(song(first,after),240f,base.copy(style=style,renderScale=scale),fonts)
            val rowIndex=page.staves.indexOfFirst {row->row.annotations.any {it.sourceTick==start && it.band==TGAnnotationBand.Text}}
            assertTrue(rowIndex>0)
            val previous=page.staves[rowIndex-1]
            val inkBottom=previous.measures.flatMap {it.beats}.flatMap {it.voices}.filter {it.notes.isNotEmpty()}.maxOf {v->
                val beamOffset=if(v.up) maxOf(0,v.duration.beamCount-1)*4f else 0f
                maxOf(v.stemStartY,v.stemEndY+beamOffset+if(v.duration.beamCount>0) 2.4f else .4f)
            }
            val caption=page.staves[rowIndex].annotations.single {it.band==TGAnnotationBand.Text && it.sourceTick==start}
            assertTrue(caption.bounds.top>inkBottom+.01f,"$style/$scale prose ${caption.bounds} overlaps prior rhythm down to $inkBottom")
            assertClear(page)
        }
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
    @Test fun singletonAndTooShortGroupedSpansRemainLabelOnlyLikeAlphaTab() {
        for(flags in listOf(setOf(0),setOf(0,1))) {
            val page=layout(song(bar(flags=flags)))
            val a=page.rings().single()
            assertEquals(page.staves.first().measures.first().beats.first().x,a.labelBounds.center.x,.001f)
            assertNull(a.lineFrom);assertNull(a.lineTo);assertFalse(a.endCap)
            assertEquals(listOf("let ring"),a.lines)
        }
    }
    @Test fun disablingGroupedLetRingRetainsEveryNativeLRLabel() {
        val page=layout(song(bar(flags=setOf(0,1,2))),config=base.copy(mergeLetRing=false))
        assertTrue(page.rings().isEmpty())
        assertEquals(3,page.staves.flatMap {it.annotations}.count {it.lines==listOf("L.R")})
        assertClear(page)
    }
    @Test fun unmarkedTieDoesNotInventAnInheritedEffectFlag() {
        val source=bar(flags=setOf(0))
        val beats=source.beats.mapIndexed {i,b->if(i==1) b.copy(voices=listOf(TGVoice(notes=listOf(TGNote(5,1,tied=true)))).toPersistentList()) else b}
        val run=letRingRuns(TGTrack(1,"Flags",tuning,listOf(source.copy(beats=beats.toPersistentList())))).single()
        assertEquals(960L,run.last);assertEquals(1440L,run.end)
    }
}
