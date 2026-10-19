package com.reau.app.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One past end of a process of the app, as Android kept it (`ApplicationExitInfo`, Android 11+). */
data class PastExit(
    val timestampMs: Long,
    val processName: String,
    val pid: Int,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
)

/**
 * Why the app's processes ended before: each [PastExit] as one ride-log line. The
 * codes are Android's (`ApplicationExitInfo.REASON_*`, `RunningAppProcessInfo.IMPORTANCE_*`), copied as numbers so
 * this stays plain Kotlin; ExitReasonsTest checks them against Android's.
 */
object ExitReasons {
    private const val SIGNALED = 2
    private const val EXIT_SELF = 1
    private const val CRASH_NATIVE = 5
    private const val ANR = 6
    private const val DESCRIPTION_MAX = 160
    private const val KB_PER_MB = 1024

    /** Exits whose line says nothing but the time (the app was replaced by an install): in a row, they are one line. */
    private val REPEATABLE = setOf(16)

    /** An install, a forced stop ("user requested", "user stopped"): REau ended from outside, maybe mid-session. */
    private val CUT_FROM_OUTSIDE = setOf(10, 11, 16)

    /** At most "foreground service": REau was running a projection, not sitting in the background. */
    private const val PROJECTING_IMPORTANCE = 125

    /** How long after such an exit Android Auto's server is taken as still holding the old session. */
    const val HANGING_FOR_MS = 10 * 60_000L

    /**
     * Whether [newest], REau's last exit, can have left Android Auto's server holding its session (it then accepts and
     * answers nothing until restarted): ended from outside while projecting, less than [HANGING_FOR_MS] before [nowMs].
     */
    fun leftAndroidAutoHanging(newest: PastExit?, nowMs: Long): Boolean =
        newest != null && newest.reason in CUT_FROM_OUTSIDE && newest.importance <= PROJECTING_IMPORTANCE &&
            nowMs - newest.timestampMs in 0..HANGING_FOR_MS

    private val REASONS = mapOf(
        0 to "unknown",
        EXIT_SELF to "exit self",
        SIGNALED to "signaled",
        3 to "low memory",
        4 to "crash",
        CRASH_NATIVE to "native crash",
        ANR to "ANR",
        7 to "initialization failure",
        8 to "permission change",
        9 to "excessive resource usage",
        10 to "user requested",
        11 to "user stopped",
        12 to "dependency died",
        13 to "other",
        14 to "freezer",
        15 to "package state change",
        16 to "package updated",
    )

    private val IMPORTANCES = mapOf(
        100 to "foreground",
        125 to "foreground service",
        200 to "visible",
        230 to "perceptible",
        300 to "service",
        325 to "top sleeping",
        350 to "can't save state",
        400 to "cached",
        1000 to "gone",
    )

    private val STAMP get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun reasonName(reason: Int): String = REASONS[reason] ?: "reason $reason"

    fun importanceName(importance: Int): String = IMPORTANCES[importance] ?: "importance $importance"

    /**
     * The ride-log lines of [exits] (oldest first): one each, except that a run of two or more exits in a row for a reason
     * in [REPEATABLE] is one line (seven installs in a day would leave seven "package updated" lines), with the
     * count and the first and last time; the process on it is the last one's. [traceOf] names the trace kept for an exit.
     */
    fun describeRuns(exits: List<PastExit>, traceOf: (PastExit) -> String? = { null }): List<String> {
        val lines = mutableListOf<String>()
        var i = 0
        while (i < exits.size) {
            var end = i + 1
            while (exits[i].reason in REPEATABLE && end < exits.size && exits[end].reason == exits[i].reason) end++
            val run = exits.subList(i, end)
            lines += describe(run.last(), traceOf(run.last()), run)
            i = end
        }
        return lines
    }

    /** The ride-log line of one exit; [traceFile] names the trace kept beside the log, if any. [run]: the exits it stands for. */
    fun describe(exit: PastExit, traceFile: String? = null, run: List<PastExit> = emptyList()): String = buildString {
        val name = "past exit: ${reasonName(exit.reason)} (${exit.reason})"
        if (run.size < 2) {
            append("$name at ${STAMP.format(Date(exit.timestampMs))}")
        } else {
            append("$name ×${run.size} from ${STAMP.format(Date(run.first().timestampMs))} to ${STAMP.format(Date(exit.timestampMs))}")
        }
        append(", process ${exit.processName} pid ${exit.pid}")
        append(", was ${importanceName(exit.importance)} (${exit.importance})")
        when (exit.reason) {
            SIGNALED -> append(", signal ${exit.status}")
            EXIT_SELF -> append(", exit code ${exit.status}")
        }
        if (exit.pssKb > 0 || exit.rssKb > 0) append(", pss ${exit.pssKb / KB_PER_MB} MB, rss ${exit.rssKb / KB_PER_MB} MB")
        exit.description?.takeIf { it.isNotBlank() }?.let { append(", \"${oneShortLine(it)}\"") }
        traceFile?.let { append(", trace in $it") }
    }

    /** The file-name start of the trace worth keeping for [reason], or null when Android keeps none worth it. */
    fun tracePrefix(reason: Int): String? = when (reason) {
        ANR -> "anr"
        CRASH_NATIVE -> "native-crash"
        else -> null
    }

    private fun oneShortLine(text: String): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= DESCRIPTION_MAX) line else line.take(DESCRIPTION_MAX) + "…"
    }
}

/**
 * Which past exits the ride log has told (the newest exit time told, in `exit-reported` in [dir]), so each is told
 * once; and the ANR or native-crash traces, kept in [dir] beside the ride logs.
 */
class ExitBook(private val dir: File) {

    /** The exits not told yet, oldest first, at most the newest [max]. */
    fun unreported(exits: List<PastExit>, max: Int = MAX_TOLD): List<PastExit> {
        val told = toldUpTo()
        return exits.filter { it.timestampMs > told }.sortedBy { it.timestampMs }.takeLast(max)
    }

    fun markReported(exits: List<PastExit>) {
        val newest = exits.maxOfOrNull { it.timestampMs } ?: return
        if (newest <= toldUpTo()) return
        runCatching {
            dir.mkdirs()
            File(dir, REPORTED).writeText(newest.toString())
        }
    }

    /** Copies at most [capBytes] of the [trace] of [exit] into a file named after it; null when not kept. */
    fun saveTrace(exit: PastExit, trace: InputStream, capBytes: Long = TRACE_CAP): File? {
        val prefix = ExitReasons.tracePrefix(exit.reason) ?: return null
        return try {
            dir.mkdirs()
            val file = File(dir, "$prefix-${NAME.format(Date(exit.timestampMs))}-${exit.pid}.txt")
            file.outputStream().use { out ->
                if (copyAtMost(trace, out, capBytes)) out.write("\n… trace cut at $capBytes bytes\n".toByteArray())
            }
            file
        } catch (_: IOException) {
            null
        }
    }

    /** Copies at most [cap] bytes; true when [from] had more. */
    private fun copyAtMost(from: InputStream, to: OutputStream, cap: Long): Boolean {
        val buffer = ByteArray(BUFFER)
        var left = cap
        while (left > 0) {
            val n = from.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) return false
            to.write(buffer, 0, n)
            left -= n
        }
        return from.read() >= 0
    }

    private fun toldUpTo(): Long = runCatching { File(dir, REPORTED).readText().trim().toLong() }.getOrDefault(0L)

    companion object {
        const val MAX_TOLD = 10
        private const val REPORTED = "exit-reported"
        private const val TRACE_CAP = 2L * 1024 * 1024
        private const val BUFFER = 8 * 1024
        private val NAME get() = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
