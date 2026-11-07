// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import com.tuxcompose.layout.*
import java.io.ByteArrayInputStream
import kotlin.test.*

class LetRingImportTest {
    @Test fun realGp3Gp4AndGp5FlagsProduceOneSpanWithoutChangingTheDecodedScore() {
        for (version in 3..5) {
            val decoded=TuxGuitarImporter.read(ByteArrayInputStream(DemoSongFactory.guitarPro(version)),"ring.gp$version")
            val source=decoded.score.tracks[0].measures[2]
            assertEquals(8,source.beats.count { b -> b.voices.any { v -> v.notes.any { it.effects.letRing } } })
            val before=decoded.score
            val page=TGLayoutCalculator(TGLayoutConfig(justifySystems=false)).calculate(before,1800f)
            val spans=page.staves.filter { it.trackIndex==0 }.flatMap { it.annotations }.filter { it.kind==TGAnnotationKind.LetRing }
            assertEquals(1,spans.size,"GP$version")
            assertEquals(source.start,spans.single().sourceTick)
            assertEquals(source.end,spans.single().endTick)
            assertTrue(spans.single().endCap)
            assertEquals(before,decoded.score)
        }
    }
}
