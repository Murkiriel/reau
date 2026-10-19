package com.reau.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The Aurum palette: REau's brand colours.

// Aurum: the 5 brand colours
val Ouro = Color(0xFFE1AD01)       // main action, active states
val OuroSuave = Color(0xFFC5A059)  // borders, supporting text
val Carvao = Color(0xFF211D16)     // surface (dark) · chrome (light)
val Grafite = Color(0xFF121212)    // background (dark) · text (light)
val Marfim = Color(0xFFECE7DD)     // text (dark) · background (light)
// Aurum: the 4 status colours (the same in both themes)
val StatusErro = Color(0xFFD64A4A)
val StatusSucesso = Color(0xFF3E8C45)
val StatusAtencao = Color(0xFFC26A22)
val StatusInfo = Color(0xFF4A82C3)

// Blends worked out beforehand (Material 3 wants solid colours)
private val OuroSobreGrafite12 = Color(0xFF2B2510)  // Ouro 12% over Grafite
private val OuroSobreMarfim12 = Color(0xFFEBE0C3)   // Ouro 12% over Marfim
private val OuroSuaveSobreGrafite22 = Color(0xFF393122)
private val Grafite62SobreMarfim = Color(0xFF65635F) // supporting text in the light theme

val REauDark = darkColorScheme(
    primary = Ouro, onPrimary = Grafite,
    primaryContainer = OuroSobreGrafite12, onPrimaryContainer = Ouro,
    secondary = OuroSuave, onSecondary = Grafite,
    background = Grafite, onBackground = Marfim,
    surface = Grafite, onSurface = Marfim,
    surfaceVariant = Carvao, onSurfaceVariant = OuroSuave,
    surfaceContainerLowest = Grafite, surfaceContainerLow = Carvao,
    surfaceContainer = Carvao, surfaceContainerHigh = Carvao, surfaceContainerHighest = Carvao,
    outline = OuroSuave, outlineVariant = OuroSuaveSobreGrafite22,
    error = StatusErro, onError = Grafite,
    inverseSurface = Marfim, inverseOnSurface = Grafite, inversePrimary = Ouro,
)

val REauLight = lightColorScheme(
    primary = Ouro, onPrimary = Grafite,            // in the light theme Ouro is only a fill: never text
    primaryContainer = OuroSobreMarfim12, onPrimaryContainer = Grafite,
    secondary = OuroSuave, onSecondary = Grafite,
    background = Marfim, onBackground = Grafite,
    surface = Marfim, onSurface = Grafite,
    surfaceVariant = Marfim, onSurfaceVariant = Grafite62SobreMarfim,
    surfaceContainerLowest = Marfim, surfaceContainerLow = Marfim,
    surfaceContainer = Marfim, surfaceContainerHigh = Marfim,
    surfaceContainerHighest = Carvao,               // chrome: the top bar, the tabs' track
    outline = OuroSuave, outlineVariant = OuroSuave,
    error = StatusErro, onError = Grafite,
    inverseSurface = Carvao, inverseOnSurface = Marfim, inversePrimary = Ouro,
)

@Immutable
data class REauStatusColors(
    val success: Color = StatusSucesso,
    val warning: Color = StatusAtencao,
    val info: Color = StatusInfo,
    val error: Color = StatusErro,
    /** The "off" state's colour (icon and border). */
    val idle: Color,
    /** Chrome: the top bar in the light theme, the tabs' track. */
    val chrome: Color = Carvao,
    val onChrome: Color = Marfim,
)

val REauStatusDark = REauStatusColors(idle = OuroSuave)
val REauStatusLight = REauStatusColors(idle = Grafite62SobreMarfim)

val LocalREauStatus = staticCompositionLocalOf { REauStatusDark }
