package com.reau.app.ui

/**
 * What the owner's "Detalhes" of the video profiles holds (12/11/2026): the profiles are fixed, and their numbers (the frame ceiling, the
 * bitrate, the mode and the keyframe) confused more than they helped, so only the name and the short line of each profile are in sight and
 * the numbers sit behind "Detalhes", closed at every visit to the screen. Nothing about the values changed.
 */
internal object ProfileDetails {
    /** "Detalhes" starts closed. */
    const val START_OPEN = false

    /** The tags of the rows of numbers behind "Detalhes", in the order they are shown ([StreamProfileRows]). */
    val ROWS = listOf("fps", "bitrate", "bitrate_mode", "keyframe")

    /** The rows of numbers in sight with "Detalhes" [open] or not: none while it is closed. */
    fun rowsShown(open: Boolean): List<String> = if (open) ROWS else emptyList()
}
