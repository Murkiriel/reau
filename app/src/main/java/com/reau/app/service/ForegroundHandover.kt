package com.reau.app.service

/**
 * Switching off and on within a second, while the stop was still stopping Android Auto's receiver, once let the
 * stop's release take away the foreground the new start had just taken, and the projection went on as a plain
 * service. In the background, with the battery saver on, Android blocked its network (EPERM) and killed it as
 * "service (300)". Every projection seen failing with EPERM had begun that way.
 */
object ForegroundHandover {
    /**
     * Whether the release of the stop brought by [stopCommand] may let the foreground go: not when a start came after
     * it ([lastStart], the start id of the last start that took the foreground), which already holds it.
     */
    fun dropsForeground(stopCommand: Int, lastStart: Int): Boolean = lastStart <= stopCommand

    // ActivityManager.RunningAppProcessInfo's importances, as numbers so the words stay testable off the device.
    private val NAMES = mapOf(
        100 to "foreground",
        125 to "foreground service",
        200 to "visible",
        230 to "perceptible",
        300 to "service",
        400 to "cached",
    )

    /** The process's importance with its name, for the ride log. */
    fun importance(importance: Int): String = NAMES[importance]?.let { "$importance ($it)" } ?: "$importance"

    /** How the process stands, for the `app background` line: the projection's network depends on its foreground. */
    fun standing(importance: Int, projectionForeground: Boolean, projectionOn: Boolean): String {
        val projection = when {
            !projectionOn -> "projection off"
            projectionForeground -> "projection in the foreground"
            else -> "projection NOT in the foreground: its network may be blocked"
        }
        return "process importance ${importance(importance)}, $projection"
    }
}
