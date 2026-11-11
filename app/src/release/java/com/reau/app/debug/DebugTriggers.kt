package com.reau.app.debug

import androidx.compose.runtime.Composable

/** The release build's [DebugTriggers] (the real one is in `src/debug`): no row at all. DebugTriggersAbsenceInReleaseTest holds that. */
object DebugTriggers {
    @Composable
    fun rows(): List<@Composable () -> Unit> = emptyList()
}
