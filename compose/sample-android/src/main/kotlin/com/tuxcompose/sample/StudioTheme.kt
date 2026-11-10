// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.sample

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.tuxcompose.layout.TGColorPalette

/** Official Expressive baseline roles; dynamic wallpaper colors are an explicit preference. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StudioTheme(preferences: StudioPreferences, content: @Composable () -> Unit) {
    val dark=when (preferences.theme) {
        StudioThemeMode.System -> isSystemInDarkTheme()
        StudioThemeMode.Dark -> true
        StudioThemeMode.Light -> false
    }
    val context=LocalContext.current
    val scheme=when {
        preferences.dynamicColor && Build.VERSION.SDK_INT>=31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    MaterialExpressiveTheme(colorScheme=scheme,motionScheme=MotionScheme.expressive(),content=content)
}

/** Keep the notation quiet and high-contrast; reserve primary for the playhead and active states. */
fun ColorScheme.notationPalette(highContrast: Boolean): TGColorPalette =
    if (highContrast) TGColorPalette.HighContrast else TGColorPalette(
        background=surfaceContainerLowest,
        staffLines=onSurfaceVariant,
        tablatureLines=outline,
        noteText=onSurface,
        noteHead=onSurface,
        cursor=primary,
        selection=primary.copy(alpha=.16f),
        beatText=onSurfaceVariant,
        measureNumber=tertiary,
    )
