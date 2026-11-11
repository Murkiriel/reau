package com.reau.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** The tag of the [PhoneFrame]. */
internal const val PHONE_TAG = "phone_frame"

/**
 * A small phone for the legibility tests: [widthDp] by [heightDp] (a 360 x 640 dp one by default, the narrow end of the
 * phones the app meets) with the system's font scale at [fontScale] (1.3 is "large" in Android's settings). The screen
 * inside is given exactly those constraints, so what is cut or pushed out of sight here is cut or pushed out on such a phone.
 */
@Composable
internal fun PhoneFrame(widthDp: Int = 360, heightDp: Int = 640, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        Box(Modifier.width(widthDp.dp).height(heightDp.dp).testTag(PHONE_TAG)) { content() }
    }
}
