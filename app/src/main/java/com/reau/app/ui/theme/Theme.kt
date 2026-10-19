package com.reau.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val REauShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // chips
    small = RoundedCornerShape(12.dp),       // supporting buttons, counters, fields
    medium = RoundedCornerShape(16.dp),      // shortcuts, state indicators
    large = RoundedCornerShape(18.dp),       // main button, group cards
    extraLarge = RoundedCornerShape(26.dp),  // bridge card, sheets
)

object REauSpace { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 20.dp; val xxl = 24.dp; val touch = 48.dp }

/**
 * Aurum, dark first. The app passes [dark] from the rider's "Tema" ([LocalREauNight]); without it, the system's.
 */
@Composable
fun REauTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalREauStatus provides if (dark) REauStatusDark else REauStatusLight, LocalREauDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) REauDark else REauLight,
            typography = REauTypography,
            shapes = REauShapes,
            content = content,
        )
    }
}

/** Whether the theme in force is the dark one: the components draw borders and gold differently in each. */
val LocalREauDark = staticCompositionLocalOf { true }

/** Whether it is night for the app's theme (the rider's "Tema", by the sun or fixed): dark at night. Dark by default. */
val LocalREauNight = staticCompositionLocalOf { true }
