package com.reau.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.reau.app.R

// The fonts live in res/font, inside the APK
// (REau downloads nothing): static instances of Google Fonts' variable files, all OFL 1.1 (licenses/fonts/).
// fraunces_500.ttf, fraunces_600.ttf · hanken_grotesk_400/500/600.ttf · jetbrains_mono_400/500.ttf
val Fraunces = FontFamily(Font(R.font.fraunces_500, FontWeight.Medium), Font(R.font.fraunces_600, FontWeight.SemiBold))
val Hanken = FontFamily(
    Font(R.font.hanken_grotesk_400, FontWeight.Normal),
    Font(R.font.hanken_grotesk_500, FontWeight.Medium),
    Font(R.font.hanken_grotesk_600, FontWeight.SemiBold),
)
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_400, FontWeight.Normal), Font(R.font.jetbrains_mono_500, FontWeight.Medium))

val REauTypography = Typography(
    // "REau"
    displaySmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    // headline
    headlineLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 35.sp),
    // bridge card
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 31.sp),
    // screen title
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 28.sp),
    // sheet, dialog
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 26.sp),
    // shortcut
    titleMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    // row title
    titleSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    // supporting line
    bodySmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // buttons
    labelLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    // SECTION LABEL (capitals)
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.22.em),
    // the NA HORA chip
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 0.12.em),
)

/** REau's own: the log, the technical line, the counters' values. */
val REauMono = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)
val REauCounterValue = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 18.sp)
