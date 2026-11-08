// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuxcompose.component.CursorStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveChoicesDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun narrowChoicesAtTwoHundredPercentKeepFullLabelsAndDisjoint48DpTargets() {
        val selected=mutableStateOf(CursorStyle.Capsule)
        compose.setContent {
            val d=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(d,2f)) {
                StudioTheme(StudioPreferences()) {
                    Column(Modifier.width(288.dp).testTag("choice-root").padding(16.dp)) {
                        StudioChoices(CursorStyle.entries.toList(),selected.value,{it.name},{"audit-${it.name}"}) { selected.value=it }
                    }
                }
            }
        }
        compose.waitForIdle()
        val root=compose.onNodeWithTag("choice-root").fetchSemanticsNode().boundsInRoot
        val bounds=CursorStyle.entries.map {style->
            val node=compose.onNodeWithTag("audit-${style.name}")
            node.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val b=node.fetchSemanticsNode().boundsInRoot
            assertTrue(b.left>=root.left && b.right<=root.right && b.top>=root.top && b.bottom<=root.bottom)
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("choice-label-audit-${style.name}",useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1,layouts.size)
            assertFalse("${style.name} must not be cropped",layouts.single().hasVisualOverflow)
            repeat(layouts.single().lineCount) {assertFalse(layouts.single().isLineEllipsized(it))}
            node.performClick().assertIsOn()
            b
        }
        bounds.forEachIndexed {i,a->bounds.drop(i+1).forEach {b->assertFalse("Touch targets overlap",a.overlaps(b))}}
        assertTrue("Large labels should get multiple rows",bounds.map {it.top}.distinct().size>1)
    }
    @Test fun allSettingsHeadingsExposeHeadingSemantics() {
        compose.setContent {StudioTheme(StudioPreferences()) {SettingsHeading("Measured spacing","No hidden labels")}}
        compose.onNodeWithText("Measured spacing").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
    }
}
