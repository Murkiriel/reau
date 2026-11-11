package com.reau.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The system bars' icons, edge to edge. The app draws under both bars: the top bar is dark in both themes (Grafite, or the Carvão
 * chrome), so the status bar's icons are always light; the page under the navigation bar is Grafite in the dark theme and Marfim in
 * the light one, so the navigation bar's icons (or the gesture handle) are light on Grafite and dark on Marfim. Left to the platform,
 * the icons stayed light and vanished on Marfim. The rider's "Tema" decides the theme, not the system, so it is set here.
 */
object SystemBars {
    /** Whether the status bar takes dark icons ("light status bar"): never, its ground is dark in both themes. */
    const val DARK_STATUS_ICONS = false

    /** Whether the navigation bar takes dark icons ("light navigation bar"): on the light theme's Marfim page. */
    fun darkNavigationIcons(darkTheme: Boolean): Boolean = !darkTheme
}

/** Sets the bars' icons for the theme in force, on the window this screen is in (nothing in a preview or a test with no window). */
@Composable
internal fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.activity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = SystemBars.DARK_STATUS_ICONS
        controller.isAppearanceLightNavigationBars = SystemBars.darkNavigationIcons(darkTheme)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
