// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import com.tuxcompose.layout.*
import com.tuxcompose.model.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.*

class ShowcaseAuditTest {
    private fun read()=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.lossless()),"technique-atlas.tg")
    @Test fun losslessDefaultExercisesEverySupportedNoteEffectAndTheActualDecoder() {
        val score=read().score
        assertEquals(ShowcaseSongFactory.TITLE,score.title)
        assertEquals(3,score.tracks.size)
        assertTrue(score.tracks[2].percussion)
        assertTrue(score.tracks.all {it.measures.size==32})
        val measures=score.tracks[0].measures
        assertEquals(8,measures.count {it.header.marker!=null})
        val beats=measures.flatMap {it.beats}
        val voices=beats.flatMap {it.voices}.filterNot {it.empty}
        val notes=voices.flatMap {it.notes}
        val effects=notes.map {it.effects}
        val coverage=linkedMapOf(
            "bend" to effects.any {it.bend!=null}, "tremolo bar" to effects.any {it.tremoloBar!=null},
            "hammer/pull" to effects.any {it.hammer}, "slide" to effects.any {it.slide},
            "ghost" to effects.any {it.ghost}, "dead" to effects.any {it.dead},
            "accent" to effects.any {it.accented}, "heavy accent" to effects.any {it.heavyAccented},
            "vibrato" to effects.any {it.vibrato}, "palm mute" to effects.any {it.palmMute},
            "let ring" to effects.any {it.letRing}, "staccato" to effects.any {it.staccato},
            "tap" to effects.any {it.tapping}, "slap" to effects.any {it.slapping}, "pop" to effects.any {it.popping},
            "fade in" to effects.any {it.fadeIn}, "grace" to effects.any {it.grace!=null},
            "trill" to effects.any {it.trill!=null}, "tremolo picking" to effects.any {it.tremoloPicking>0},
            "ties" to notes.any {it.tied}, "dotted" to voices.any {it.duration?.dotted==true},
            "double dotted" to voices.any {it.duration?.doubleDotted==true},
            "triplet" to voices.any {it.duration?.division==TGDivisionType(3,2)},
            "quintuplet" to voices.any {it.duration?.division==TGDivisionType(5,4)},
            "two voices" to beats.any {b->b.voices.count {!it.empty && it.notes.isNotEmpty()}>1},
            "prose" to beats.any {it.text!=null}, "chords" to beats.any {it.chord!=null},
            "up strum" to beats.any {it.stroke.direction==TGStrokeDirection.Up},
            "down strum" to beats.any {it.stroke.direction==TGStrokeDirection.Down},
            "up pick" to beats.any {it.pickStroke==TGStrokeDirection.Up},
            "down pick" to beats.any {it.pickStroke==TGStrokeDirection.Down},
            "repeat endings" to measures.any {it.header.repeatAlternative>0},
            "swing eighth" to measures.any {it.header.tripletFeel==1},
            "swing sixteenth" to measures.any {it.header.tripletFeel==2},
            "flat key" to measures.any {it.header.keySignature.flats},
            "sharp key" to measures.any {it.header.keySignature.value in 1..7},
            "tenor clef" to measures.any {it.clef==TGClef.Tenor},
            "alto clef" to measures.any {it.clef==TGClef.Alto},
        )
        assertTrue(coverage.values.all {it},"Missing decoded showcase feature(s): ${coverage.filterValues {!it}.keys}")
        assertEquals((1..5).toSet(),effects.map {it.harmonic}.filter {it>0}.toSet())
        assertEquals(setOf(1,2,4,8,16,32,64),voices.mapNotNull {it.duration?.value}.toSet())
        assertEquals(setOf(1,2,4,8,16,32,64),voices.filter {it.notes.isEmpty()}.mapNotNull {it.duration?.value}.toSet())
        assertTrue(measures.any {it.header.timeSignature==TGTimeSignature(6,8)})
        assertTrue(measures.any {it.header.timeSignature==TGTimeSignature(3,4)})
        assertTrue(measures.any {it.header.timeSignature==TGTimeSignature(5,4)})
        File("build/reports/showcase-feature-coverage.json").apply {parentFile.mkdirs();writeText(coverage.entries.joinToString(",",prefix="{",postfix="}") {"\"${it.key}\":${it.value}"})}
    }
    @Test fun everyTrackHasAlignedContiguousMeasuresAndNoNotesBeyondItsMeter() {
        val score=read().score
        score.tracks.forEach {track->
            track.measures.zipWithNext().forEach {(a,b)->assertEquals(a.end,b.start,"${track.name}: measures ${a.number}/${b.number}")}
            track.measures.forEach {m->
                assertEquals(m.start+m.header.timeSignature.ticks,m.end,"${track.name}: bar ${m.number}")
                m.beats.forEach {b->assertTrue(b.start>=m.start && b.start<m.end)}
            }
        }
        assertEquals(960L,score.tracks.first().measures.first().start)
        assertTrue(score.tracks.map {it.measures.map {m->m.start}}.distinct().size==1)
    }
    @Test fun nativePlaybackContainsTheFullArrangementPercussionAndPitchEffects() {
        val decoded=read()
        val sequence=DemoSequence(decoded.native)
        // The original parser emits LOGICAL TuxGuitar channel IDs. The separate
        // GM router maps bank-128 events to physical MIDI channel 10 (index 9).
        val drumId=decoded.native.getTrack(2).channelId
        assertTrue(sequence.events.count {it.kind==DemoMidiEvent.Kind.NoteOn}>200)
        assertTrue(sequence.events.any {it.kind==DemoMidiEvent.Kind.NoteOn && it.channel==drumId && it.a==36})
        assertTrue(sequence.events.any {it.kind==DemoMidiEvent.Kind.Control && it.channel==drumId && it.a==0 && it.b==128})
        (0..2).forEach {ti->assertTrue(sequence.events.any {it.kind==DemoMidiEvent.Kind.NoteOn && it.channel==decoded.native.getTrack(ti).channelId})}
        assertTrue(sequence.events.any {it.kind==DemoMidiEvent.Kind.Pitch})
        assertTrue(sequence.spans.size>32,"Native repeat expansion must still run")
        assertTrue(sequence.endMicros>30_000_000)
        assertTrue(sequence.events.zipWithNext().all {(a,b)->a.micros<=b.micros})
    }
    @Test fun actualGp5CompanionStillImportsWithoutRendererInjectedFeatures() {
        val result=TuxGuitarImporter.read(ByteArrayInputStream(ShowcaseSongFactory.guitarPro()),"technique-atlas.gp5")
        assertTrue(result.format.contains("Guitar Pro"))
        assertEquals(3,result.score.tracks.size)
        assertTrue(result.score.tracks.all {it.measures.size==32})
        assertTrue(DemoSequence(result.native).events.isNotEmpty())
    }
    @Test fun spatialMatrixAudits1152RealDecodedShowcaseLayouts() {
        val data=read().score
        val failures=mutableListOf<String>()
        var cases=0;var rows=0;var captions=0;var fragments=0
        for(width in listOf(240f,320f,411f,960f)) for(style in TGLayoutStyles.entries)
            for(spacing in TGSpacingMode.entries) for(compact in listOf(false,true)) for(justify in listOf(false,true))
                for(focus in listOf(0,1,2,3)) for(scale in listOf(1f,1.25f,2.625f)) {
            cases++
            val label="$width/$style/$spacing/compact=$compact/justify=$justify/focus=$focus/scale=$scale"
            try {
                val cfg=TGLayoutConfig(style=style,spacingMode=spacing,compact=compact,justifySystems=justify,
                    multiTrack=focus==3,showChordNames=true,showChords=width>=400f,renderScale=scale)
                val page=TGLayoutCalculator(cfg).calculate(data,width,focus.coerceAtMost(2))
                assertEquals(width,page.width,.01f,label);assertTrue(page.height.isFinite())
                rows+=page.staves.size
                page.staves.forEach {row->
                    assertTrue(row.bounds.left>=-.01f && row.bounds.right<=width+.01f,"$label row overflow ${row.bounds}")
                    row.measures.forEach {m->
                        assertSame(data.tracks[row.trackIndex].measures[m.measureIndex],m.measure)
                        assertEquals(1f,m.contentScale,0f)
                        if(!m.isFirstFragment || !m.isLastFragment) fragments++
                        m.beats.forEach {b->
                            val cursor=page.cursorAt(row.trackIndex,b.startTick,false)
                            assertNotNull(cursor,"$label cursor missing at ${b.startTick}")
                            assertEquals(b.startTick,cursor.beat.startTick)
                            b.voices.flatMap {it.notes}.forEach {n->
                                listOfNotNull(n.tabBounds,n.scoreBounds).forEach {r->
                                    assertTrue(listOf(r.left,r.right,r.top,r.bottom).all {it.isFinite()},label)
                                    assertTrue(r.top>=row.bounds.top-.01f && r.bottom<=row.bounds.bottom+.01f,"$label glyph outside vertical row: $r vs ${row.bounds}")
                                }
                            }
                        }
                    }
                    captions+=row.annotations.size
                    row.annotations.forEachIndexed {i,a->
                        assertTrue(a.bounds.top>=row.bounds.top-.01f && a.bounds.bottom<=row.bounds.bottom+.01f,"$label caption vertical overflow")
                        assertTrue(a.labelBounds.left>=a.clipBounds.left-.01f && a.labelBounds.right<=a.clipBounds.right+.01f,"$label caption horizontal overflow")
                        row.annotations.drop(i+1).forEach {b->assertFalse(minOf(a.bounds.right,b.bounds.right)-maxOf(a.bounds.left,b.bounds.left)>.01f &&
                            minOf(a.bounds.bottom,b.bounds.bottom)-maxOf(a.bounds.top,b.bounds.top)>.01f,
                            "$label ${a.band} ${a.lines} ${a.bounds} overlaps ${b.band} ${b.lines} ${b.bounds}")}
                    }
                }
            } catch(e: AssertionError) { if(failures.size<12) failures+="$label: ${e.message}" }
        }
        File("build/reports/showcase-layout-audit.json").apply {
            parentFile.mkdirs();writeText("{\"cases\":$cases,\"rows\":$rows,\"captions\":$captions,\"fragments\":$fragments,\"recordedFailures\":${failures.size}}\n")
        }
        assertEquals(1152,cases)
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
}
