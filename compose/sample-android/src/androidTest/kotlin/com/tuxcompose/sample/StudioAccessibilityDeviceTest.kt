// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioAccessibilityDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun twoHundredPercentTextKeepsMusicAndPrimaryTargetsUsable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("studio-settings",Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,2f)) { TuxComposeDemo() }
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("next").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        listOf("open-file","settings","play","previous","next","follow-cursor").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTouchWidthIsAtLeast(48.dp).assertTouchHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("score-canvas").assertHeightIsAtLeast(140.dp).assertWidthIsAtLeast(250.dp)
        val folder=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(folder,"studio-large-text.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-Cursor").performClick()
        compose.onNodeWithTag("smooth-follow").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-close").performClick()
        compose.onNodeWithTag("score-canvas").assertIsDisplayed()
    }
    @Test fun corruptStoredChoicesFallBackWithoutBreakingTheWorkspace() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=context.getSharedPreferences("invalid-studio-test",Context.MODE_PRIVATE)
        try {
            store.edit().putString("theme","removed-theme").putString("cursorStyle","removed-style").putFloat("cursorPadding",Float.NaN).commit()
            val loaded=StudioPreferences.load(store)
            assertEquals(StudioThemeMode.System,loaded.theme)
            assertEquals(com.tuxcompose.component.CursorStyle.Capsule,loaded.cursorStyle)
            assertEquals(3f,loaded.cursorPadding,0f)
        } finally { store.edit().clear().commit() }
    }
}
