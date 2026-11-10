// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.android.view.tablature.TGSongViewStyles
import app.tuxguitar.graphics.control.*
import app.tuxguitar.song.factory.TGFactory
import app.tuxguitar.song.models.TGDuration as NativeDuration
import app.tuxguitar.song.models.TGSong as NativeSong
import com.tuxcompose.layout.*
import com.tuxcompose.layout.TGLayoutStyles
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.*

/** Full Java layout differentials. No expected measure widths or onset equations are mocked. */
class NativeLayoutParityTest {
    private val factory = TGFactory()
    private val base = TGLayoutConfig()
    private val tolerance = .025f // physical pixels, including fractional density/zoom

    private fun close(expected: Float, actual: Float, field: String) = assertEquals(expected,actual,tolerance,field)

    private fun compare(ref: NativeLayoutReference): Int {
        val scale = ref.scale
        var beatsChecked = 0
        val indices = if (ref.config.multiTrack) ref.model.tracks.indices.toList() else listOf(ref.actual.staves.first().trackIndex)
        indices.forEach { ti ->
            val nativeTrack = ref.song.getTrack(ti)
            val rows = ref.actual.staves.filter { it.trackIndex == ti }
            val expectedRows = (0 until nativeTrack.countMeasures()).groupBy {
                (nativeTrack.getMeasure(it) as TGMeasureImpl).posY
            }.values.toList()
            assertEquals(expectedRows,rows.map { row -> row.measures.map { it.measureIndex } },"${ref.config.style}/compact=${ref.config.compact}/multi=${ref.config.multiTrack}/scale=$scale/track=$ti row membership")
            rows.flatMap { it.measures }.forEach { m ->
                val original = nativeTrack.getMeasure(m.measureIndex) as TGMeasureImpl
                val prefix = "${ref.config.style}/compact=${ref.config.compact}/multi=${ref.config.multiTrack}/scale=$scale/track=$ti/bar=${m.measureIndex}"
                close(original.posX,m.bounds.left*scale,"$prefix left")
                close(original.posY,m.bounds.top*scale,"$prefix top")
                close(original.getWidth(ref.layout)+original.spacing,m.bounds.width*scale,"$prefix width")
                close(original.ts.size,m.bounds.height*scale,"$prefix track height")
                close(original.posX+original.headerImpl.getLeftSpacing(ref.layout)+original.getFirstNoteSpacing(ref.layout),m.beatAreaLeft*scale,"$prefix first-note/header spacing")
                assertEquals(original.compactMode,m.compact,"$prefix effective compact")
                assertEquals(original.isPaintClef,m.paintClef,"$prefix clef flag")
                assertEquals(original.isPaintKeySignature,m.paintKey,"$prefix key flag")
                assertEquals(original.headerImpl.shouldPaintTempo(),m.paintTempo,"$prefix tempo flag")
                assertEquals(original.headerImpl.shouldPaintTimeSignature(),m.paintTime,"$prefix meter flag")
                assertEquals(original.headerImpl.shouldPaintTripletFeel(),m.paintTripletFeel,"$prefix triplet-feel flag")
                close(original.getClefSpacing(ref.layout),m.clefWidth*scale,"$prefix shared clef maximum")
                close(original.getKeySignatureSpacing(ref.layout),m.keyWidth*scale,"$prefix shared key maximum")
                if (ref.config.showStandardNotation) close(original.posY+original.ts.getPosition(TGTrackSpacing.POSITION_SCORE_MIDDLE_LINES),m.staffTop!!*scale,"$prefix score lane")
                if (ref.config.showTablature) close(original.posY+original.ts.getPosition(TGTrackSpacing.POSITION_TABLATURE),m.tabTop!!*scale,"$prefix tab lane")
                close(original.posY+original.ts.getPosition(TGTrackSpacing.POSITION_BEND),m.bendTop*scale,"$prefix bend lane")
                close(original.posY+original.ts.getPosition(TGTrackSpacing.POSITION_EFFECTS),m.effectsTop*scale,"$prefix effect lane")
                close(original.posY+original.ts.getPosition(TGTrackSpacing.POSITION_CHORD),m.chordTop*scale,"$prefix chord lane")
                assertEquals(original.countBeats(),m.beats.size,"$prefix beat count")
                assertEquals(1f,m.contentScale,0f,"$prefix glyphs must not be scaled to fit a row")
                m.beats.forEachIndexed { bi,b ->
                    val beat = original.getBeat(bi) as TGBeatImpl
                    val x = original.posX+original.headerImpl.getLeftSpacing(ref.layout)+beat.posX+beat.getSpacing(ref.layout)
                    close(x,b.x*scale,"$prefix beat=$bi justified onset")
                    assertEquals(beat.start,b.startTick,"$prefix beat=$bi tick identity")
                    b.voices.forEach { voice ->
                        val v = beat.getVoice(voice.voice.index) as TGVoiceImpl
                        if (voice.notes.isNotEmpty()) assertEquals(v.beatGroup.direction == TGBeatGroup.DIRECTION_UP,voice.up,"$prefix beat=$bi stem direction")
                        else {
                            assertEquals(v.isHiddenSilence,voice.hiddenSilence,"$prefix beat=$bi rest visibility")
                            if (!v.isHiddenSilence) close(original.posY+original.ts.getPosition(if (ref.config.showStandardNotation) TGTrackSpacing.POSITION_SCORE_MIDDLE_LINES else TGTrackSpacing.POSITION_TABLATURE)+v.minY,
                                voice.silenceY!!*scale,"$prefix beat=$bi displaced rest")
                        }
                    }
                    beatsChecked++
                }
            }
        }
        close(ref.layout.height,ref.actual.height*scale,"song scroll height")
        return beatsChecked
    }

    @Test fun defaultsUseTheActualUpstreamAndroidStylesAndCompactFlag() {
        val styles = TGSongViewStyles()
        assertEquals(TGSpacingMode.TuxGuitar,base.spacingMode)
        assertTrue(base.compact)
        assertFalse(base.showChords)
        assertFalse(base.showChordNames)
        assertFalse(base.respectSystemFontScale)
        assertEquals(styles.firstMeasureSpacing,base.pagePadding)
        assertEquals(styles.firstTrackSpacing,base.firstTrackSpacing)
        assertEquals(styles.trackSpacing,base.systemSpacing)
        assertEquals(styles.stringSpacing,base.stringSpacing)
        assertEquals(styles.scoreLineSpacing,base.staffSpacing)
        assertEquals(styles.durationWidths.toList(),base.durationWidths.toList())
        compare(NativeLayoutReference(fixture(),base,700f))
    }

    @Test fun fullNativeMatrixMatchesWidthsEveryOnsetPackingJustificationAndVerticalLanes() {
        val report = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (style in TGLayoutStyles.entries) for (compact in listOf(false,true))
            for (chords in 0..2) for (scale in listOf(1f,1.25f,2.625f)) {
                val config = base.copy(style=style,compact=compact,showChords=chords==1,showChordNames=chords==2)
                // All these normalised bars fit a complete row, so default parent-width mode
                // must agree with native; no fragment extension is involved in this matrix.
                val ref = NativeLayoutReference(fixture(),config,1000f*scale,scale)
                var count = 0
                var passed = true
                try { count = compare(ref) } catch (e: AssertionError) {
                    passed = false
                    failures += "$style/compact=$compact/chords=$chords/scale=$scale: ${e.message}"
                }
                report += "{\"passed\":$passed,\"style\":\"$style\",\"compact\":$compact,\"chords\":$chords,\"scale\":$scale,\"rows\":${ref.actual.staves.size},\"beats\":$count}"
            }
        File("build/reports/native-layout-parity.json").apply {
            parentFile.mkdirs()
            writeText("{\"reference\":\"unmodified TGLayoutVertical + TGSongViewStyles\",\"tolerancePhysicalPixels\":$tolerance,\"cases\":[${report.joinToString(",")}]}\n")
        }
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }

    @Test fun trueMultiTrackUsesNativeHeaderMaximaAndPerTrackEffectSlack() {
        for (style in TGLayoutStyles.entries) for (compact in listOf(false,true)) {
            compare(NativeLayoutReference(fixture(tracks=2,secondVoice=true),base.copy(style=style,multiTrack=true,compact=compact),1000f))
        }
    }

    @Test fun multitrackFlagWithOnlyOneSongTrackStillAllowsCompact() {
        compare(NativeLayoutReference(fixture(),base.copy(multiTrack=true),700f))
    }

    @Test fun selectedTrackStillUsesUnseenTracksSignatureMaxima() {
        for (selected in 0..1) compare(NativeLayoutReference(fixture(tracks=2),base,1000f,selectedTrack=selected))
    }

    @Test fun horizontalLayoutMatchesOriginalIncludingMixedVoicesAndSignatures() {
        for (multi in listOf(false,true)) for (style in TGLayoutStyles.entries) {
            compare(NativeLayoutReference(fixture(tracks=2,secondVoice=true),base.copy(style=style,multiTrack=multi,orientation=TGLayoutOrientation.Horizontal),800f,1.5f))
        }
    }

    @Test fun decodedGp3Gp4AndGp5ShowcaseUseNativePackingAndGeometry() {
        for (version in 3..5) {
            val decoded = TuxGuitarImporter.read(ByteArrayInputStream(DemoSongFactory.guitarPro(version)),"demo.gp$version")
            for (multi in listOf(false,true)) compare(NativeLayoutReference(decoded.native,
                // Merged spans are deliberately NOT upstream behavior. This oracle tests
                // native geometry; annotation enhancement coverage lives in AnnotationTest.
                base.copy(multiTrack=multi,mergeLetRing=false),1100f))
        }
    }

    @Test fun exactGreedyThresholdRejectsNextBarAtEquality() {
        // Read the two widths from native, then put the next bar EXACTLY on the threshold.
        val input = simple(listOf(2,4,8,8),3)
        val natural = NativeLayoutReference(input,base.copy(orientation=TGLayoutOrientation.Horizontal),1000f)
        val track = natural.song.getTrack(0)
        val width = (track.getMeasure(0) as TGMeasureImpl).getWidth(natural.layout)+(track.getMeasure(1) as TGMeasureImpl).getWidth(natural.layout)+base.pagePadding+10f
        val ref = NativeLayoutReference(input,base,width)
        compare(ref)
        assertEquals(1,ref.actual.staves.first().measures.size)
        assertEquals(2,ref.actual.staves.last().measures.size)
    }

    @Test fun justificationIsProportionalToNaturalOffsetNotBeatIndex() {
        val ref = NativeLayoutReference(simple(listOf(2,4,8,8),8),base,600f)
        compare(ref)
        val beats = ref.actual.staves.first().measures.first().beats
        assertTrue(beats[1].x-beats[0].x > beats[3].x-beats[2].x)
    }

    @Test fun shortAndOverfullCallerDataUseTheActualPreciseEnding() {
        for (values in listOf(listOf(2),List(10) { 8 })) for (compact in listOf(false,true)) {
            compare(NativeLayoutReference(simple(values,2),base.copy(compact=compact,orientation=TGLayoutOrientation.Horizontal),1000f,completeRests=false))
        }
    }

    @Test fun overflowOptOutMatchesOriginalCrowdedOversizedSingletonWithoutScalingGlyphs() {
        compare(NativeLayoutReference(simple(List(64) { 64 },2),base.copy(fitPageToWidth=false),320f))
    }

    @Test fun tripletFeelChangesRetainNativeHeaderSpaceAndDecodedMetadata() {
        val input = simple(listOf(2,4,8,8),4)
        listOf(2,2,3,1).forEachIndexed { i,feel -> input.getMeasureHeader(i).tripletFeel=feel }
        val ref = NativeLayoutReference(input,base,700f)
        compare(ref)
        assertEquals(listOf(1,1,2,0),ref.model.tracks[0].measures.map { it.header.tripletFeel })
    }

    @Test fun rawTempoAnnotationChangesAreNotLostWhenQuarterBpmStaysEqual() {
        val input = simple(listOf(2,4,8,8),5)
        input.getMeasureHeader(0).tempo.setValueBase(120,4,false)
        input.getMeasureHeader(1).tempo.setValueBase(60,2,false)
        input.getMeasureHeader(2).tempo.setValueBase(240,8,false)
        input.getMeasureHeader(3).tempo.setValueBase(80,4,true)
        // Raw annotated BPM is not quarter BPM: a valid 64th-based 120-quarter mark is 1920.
        input.getMeasureHeader(4).tempo.setValueBase(1920,64,false)
        val ref = NativeLayoutReference(input,base,700f)
        compare(ref)
        assertEquals(listOf(120,120,120,120,120),ref.model.tracks[0].measures.map { it.header.tempo })
        assertEquals(listOf(120,60,240,80,1920),ref.model.tracks[0].measures.map { it.header.tempoMark!!.value })
    }

    private fun simple(values: List<Int>, bars: Int): NativeSong {
        val song = factory.newSong()
        val track = factory.newTrack().apply { number=1; name="Guitar" }
        listOf(64,59,55,50,45,40).forEachIndexed { i,value -> track.strings.add(factory.newString().apply { number=i+1; this.value=value }) }
        song.addTrack(track)
        repeat(bars) { mi ->
            val start = 960+3840L*mi
            val header = factory.newHeader().apply { number=mi+1; this.start=start }
            song.addMeasureHeader(header)
            val measure = factory.newMeasure(header); track.addMeasure(measure)
            var precise = NativeDuration.toPreciseTime(start)
            values.forEach { value ->
                val beat = factory.newBeat().apply { preciseStart=precise }
                beat.getVoice(0).apply { isEmpty=false; duration.value=value; addNote(factory.newNote().apply { this.value=5; string=1 }) }
                precise += beat.getVoice(0).duration.preciseTime
                measure.addBeat(beat)
            }
        }
        return song
    }

    private fun fixture(tracks: Int = 1, secondVoice: Boolean = false): NativeSong {
        val song = factory.newSong()
        val nativeTracks = List(tracks) { ti ->
            factory.newTrack().apply {
                number=ti+1; name="Track ${ti+1}"
                listOf(64,59,55,50,45,40).forEachIndexed { i,v -> strings.add(factory.newString().apply { number=i+1; value=v }) }
                song.addTrack(this)
            }
        }
        var start = 960L
        repeat(12) { mi ->
            val values = when (mi) { 0 -> listOf(2,4,8,8); 1,5 -> listOf(4,if (mi==1) 8 else 16,2); 3 -> List(16) { 16 }; 4 -> List(12) { 8 }; 6 -> List(6) { 8 }; 7 -> List(7) { 8 }; 8 -> List(11) { 8 }; 9 -> List(13) { 16 }; 10 -> listOf(1); else -> List(8) { 8 } }
            val quarters = when (mi) { 6 -> 3; 7,9 -> 2; else -> 4 }
            val header = factory.newHeader().apply {
                number=mi+1; this.start=start
                timeSignature.numerator=if (mi==6) 6 else quarters
                timeSignature.denominator.value=if (mi==6) 8 else 4
                tempo.quarterValue=if (mi<6) 120 else 96
                isLineBreak=mi==6
                isRepeatOpen=mi==0
                repeatClose=if (mi==3) 1 else 0
                repeatAlternative=if (mi==2) 1 else 0
                if (mi==0) marker=factory.newMarker().apply { title="Native layout fixture" }
            }
            song.addMeasureHeader(header)
            nativeTracks.forEachIndexed { ti,track ->
                val measure = factory.newMeasure(header).apply {
                    clef=if (ti==0) 1 else if (mi<7) 2 else 4
                    keySignature=if (mi<2) ti*4 else if (mi<6) 2 else 8
                }
                track.addMeasure(measure)
                var precise = NativeDuration.toPreciseTime(start)
                values.forEachIndexed { bi,value ->
                    val beat = factory.newBeat().apply { preciseStart=precise }
                    val voice = beat.getVoice(0).apply {
                        isEmpty=false; duration.value=value
                        duration.isDotted=mi==1 && bi==0
                        duration.isDoubleDotted=mi==5 && bi==0
                        if (mi in listOf(4,7,8,9)) { duration.division.enters=when(mi) { 4 -> 3; 7 -> 7; 8 -> 11; else -> 13 }; duration.division.times=when(mi) { 4 -> 2; 7 -> 4; else -> 8 } }
                    }
                    if (!(mi==11 && bi%3==0)) voice.addNote(factory.newNote().apply {
                        this.value=(bi+mi*2)%19; string=bi%6+1
                        if (mi==2 && bi==ti) effect.bend=factory.newEffectBend().apply { addPoint(0,0); addPoint(6,4); if (ti==0) addPoint(12,0) }
                        effect.isAccentuatedNote=mi==1 && bi==0
                        effect.isVibrato=mi==1 && bi==0
                        effect.isPalmMute=mi==1 && bi==0
                        if (mi==3 && bi==3) effect.harmonic=factory.newEffectHarmonic().apply { type=1 }
                    })
                    if (bi==0 || (values.size==8 && bi==1)) beat.chord=factory.newChord(6).apply { name="Am"; firstFret=1; listOf(0,1,2,2,0,-1).forEachIndexed { i,v -> strings[i]=v } }
                    if (mi==3 && bi==2) beat.text=factory.newText().apply { this.value="Text does not enlarge the native rhythmic grid" }
                    if (mi==2 && bi==2) { beat.stroke.direction=1; beat.stroke.value=16; beat.pickStroke.direction=-1 }
                    if (secondVoice && bi==0) beat.getVoice(1).apply {
                        isEmpty=false; duration.value=if (quarters==4) 1 else 2; duration.isDotted=quarters==3
                        addNote(factory.newNote().apply { this.value=7; string=4 })
                    }
                    precise += voice.duration.preciseTime
                    measure.addBeat(beat)
                }
            }
            start += header.length
        }
        return song
    }
}
