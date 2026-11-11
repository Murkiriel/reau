package com.reau.app.settings

import java.io.File

/**
 * The experimental guard of the start ([REauSettings.experimentStartGuard]). An experimental switch that brought the app down right at the
 * start of a projection would bring it down at every start, and the rider on the road would only see that nothing connects. With the guard
 * on, a projection that starts with any other experimental switch on leaves a mark (a small file); the mark goes when the projection has
 * run for [SETTLE_MS] and when it is switched off. A process that begins and finds the mark knows the one before ended inside those
 * seconds: it switches the other experimental switches off, keeps the guard on, and says so in the log.
 *
 * With the guard off (the default) no mark is ever left and nothing is switched off; a mark left from before goes and that is all.
 */
object ExperimentsGuard {
    /** How long a projection must run before its start counts as survived. */
    const val SETTLE_MS = 10_000L

    private const val MARK = "experiments-start-pending"

    /** The mark's file in the app's own folder [dir]. */
    fun mark(dir: File): File = File(dir, MARK)

    /** The experimental switches that are on in [settings], by their field's name; the guard itself is not one of them. */
    fun experimentsOn(settings: REauSettings): List<String> = listOfNotNull(
        "experimentRtpPacing".takeIf { settings.experimentRtpPacing },
        "experimentDebugDscp".takeIf { settings.experimentDebugDscp },
        "experimentAdaptiveBitrate".takeIf { settings.experimentAdaptiveBitrate },
        "experimentWifiBind".takeIf { settings.experimentWifiBind },
        "experimentWifiBand".takeIf { settings.experimentWifiBand },
        "experimentEncoderWatch".takeIf { settings.experimentEncoderWatch },
        "experimentFocusGap".takeIf { settings.experimentFocusGap },
        "experimentNightMemory".takeIf { settings.experimentNightMemory },
    )

    /** [settings] with every experimental switch off but the guard. */
    fun allOff(settings: REauSettings): REauSettings = settings.copy(
        experimentRtpPacing = false, experimentDebugDscp = false, experimentAdaptiveBitrate = false, experimentWifiBind = false,
        experimentWifiBand = false, experimentEncoderWatch = false, experimentFocusGap = false, experimentNightMemory = false,
    )

    /** Whether a start with [settings] is one the guard watches: the guard is on and so is some other experimental switch. */
    fun armed(settings: REauSettings): Boolean = settings.experimentStartGuard && experimentsOn(settings).isNotEmpty()

    /**
     * A process begins: when the [mark] of a start that did not survive is there and the guard still watches the stored settings, the
     * experimental switches are stored off and the line for the log is returned; else null. The mark goes either way.
     */
    fun atProcessStart(store: SettingsStore, mark: File): String? {
        if (!mark.exists()) return null
        mark.delete()
        val stored = store.load()
        if (!armed(stored)) return null
        store.save(allOff(stored))
        return line(experimentsOn(stored))
    }

    /**
     * A projection starts with [settings] at [nowMs]: leaves the [mark] when the guard watches it, and returns what [settled] needs to take
     * that same mark away (null: no mark was left).
     */
    fun projectionStarted(settings: REauSettings, mark: File, nowMs: Long): String? {
        if (!armed(settings)) return null
        val token = nowMs.toString()
        return token.takeIf { runCatching { mark.writeText(it) }.isSuccess }
    }

    /**
     * The projection that left the mark with [token] ran for [SETTLE_MS], or was switched off: its start survived, and its mark goes. The
     * mark of a newer start (another token) stays.
     */
    fun settled(mark: File, token: String?) {
        if (token == null) return
        runCatching { if (mark.exists() && mark.readText() == token) mark.delete() }
    }

    /** The log's line when the switches [names] were switched off. */
    fun line(names: List<String>): String =
        "start guard (experiment): the app ended within ${SETTLE_MS / MS_PER_SECOND} s of a start made with experimental switches on " +
            "(${names.joinToString(", ")}): they were switched off; the guard stays on"

    /** The log's line when a start is watched. */
    fun watchingLine(names: List<String>): String =
        "start guard (experiment): watching this start for ${SETTLE_MS / MS_PER_SECOND} s (${names.joinToString(", ")})"

    private const val MS_PER_SECOND = 1_000L
}
