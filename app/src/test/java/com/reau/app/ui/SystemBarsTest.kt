package com.reau.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarsTest {
    @Test
    fun `the navigation bar takes dark icons on the light theme's Marfim page and light ones on the dark theme's Grafite`() {
        assertTrue(SystemBars.darkNavigationIcons(darkTheme = false))
        assertFalse(SystemBars.darkNavigationIcons(darkTheme = true))
    }

    @Test
    fun `the status bar keeps light icons in both themes, its top bar being dark in both`() {
        assertFalse(SystemBars.DARK_STATUS_ICONS)
    }
}
