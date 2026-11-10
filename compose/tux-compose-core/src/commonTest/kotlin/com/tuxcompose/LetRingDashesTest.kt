// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose

import com.tuxcompose.renderer.forEachLetRingDash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetRingDashesTest {
    private fun segments(from: Float=10f,to: Float=24f,left: Float=0f,right: Float=30f)=
        mutableListOf<Pair<Float,Float>>().also { out -> forEachLetRingDash(from,to,left,right) { a,b -> out+=a to b } }

    @Test fun completeSpanKeepsThreeOnTwoOffAndClearTerminalGap() {
        assertEquals(listOf(10f to 13f,15f to 18f,20f to 23f),segments())
    }
    @Test fun clippingBothEndsPreservesTheOriginalPhase() {
        assertEquals(listOf(17f to 18f,20f to 21f),segments(left=17f,right=21f))
        assertEquals(listOf(10f to 11f),segments(left=9f,right=11f))
    }
    @Test fun viewportInsideAGapPaintsNothing() {
        assertTrue(segments(left=13.5f,right=14.5f).isEmpty())
    }
    @Test fun millionUnitSpanVisitsOnlyVisibleDashes() {
        assertEquals(listOf(999990f to 999993f,999995f to 999998f),
            segments(from=0f,to=1000000f,left=999990f,right=1000000f))
    }
    @Test fun emptyReversedAndNonfiniteSpansDoNotDraw() {
        assertTrue(segments(from=10f,to=10f).isEmpty())
        assertTrue(segments(left=25f,right=20f).isEmpty())
        assertTrue(segments(from=Float.NaN).isEmpty())
        assertTrue(segments(right=Float.POSITIVE_INFINITY).isEmpty())
    }
}
