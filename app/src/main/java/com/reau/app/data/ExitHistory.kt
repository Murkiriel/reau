package com.reau.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Tells in the ride log why the app's processes ended before, from what Android kept
 * (`getHistoricalProcessExitReasons`, Android 11+, no permission): one `exit` line per exit not told yet, with the
 * ANR or native-crash trace kept beside the log when Android has it.
 */
object ExitHistory {
    /** How many past exits to ask Android for (it keeps a few dozen per package). */
    private const val ASK = 32

    /**
     * Tells the exits not told yet, once each. Called when the app's process opens (REauApp, off the main thread: a use of the app with no projection gets
     * its `exit` line too) and again at each projection's start ([com.reau.app.service.ProjectionService]); the book of what was told
     * ([ExitBook], on disk) makes the second call tell only the exits that came after the first, and the lock makes two calls at once take turns.
     */
    @Synchronized
    fun reportPrevious(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val dir = AppFolders.diag(context) // internal storage ([AppFolders])
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { manager.getHistoricalProcessExitReasons(null, 0, ASK) }.getOrDefault(emptyList())
        val byExit = infos.associateBy { pastExit(it) }
        val book = ExitBook(dir)
        tellNews(book, byExit.keys.toList(), { exit ->
            ExitReasons.tracePrefix(exit.reason)?.let { byExit[exit]?.let { info -> keepTrace(book, exit, info) } }
        }) { RideDiagnostics.log("exit", it) }
    }

    /**
     * The `exit` lines for the [exits] [book] has not told, written by [say] (oldest first), their traces kept by [keep]; then they are marked as told. The
     * exits it returns are the ones told. Nothing of Android in it, so the tests drive it with exits of their own.
     */
    internal fun tellNews(book: ExitBook, exits: List<PastExit>, keep: (PastExit) -> File?, say: (String) -> Unit): List<PastExit> {
        val news = book.unreported(exits)
        val traces = news.associateWith { keep(it) }
        ExitReasons.describeRuns(news) { traces[it]?.name }.forEach(say)
        book.markReported(news)
        return news
    }

    /** REau's last exit, as Android kept it; null before Android 11 or with none. */
    fun newest(context: Context): PastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { manager.getHistoricalProcessExitReasons(null, 0, 1) }.getOrNull()?.firstOrNull()?.let { pastExit(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun pastExit(info: ApplicationExitInfo) = PastExit(
        timestampMs = info.timestamp,
        processName = info.processName,
        pid = info.pid,
        reason = info.reason,
        status = info.status,
        importance = info.importance,
        pssKb = info.pss,
        rssKb = info.rss,
        description = info.description,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun keepTrace(book: ExitBook, exit: PastExit, info: ApplicationExitInfo): File? = try {
        info.traceInputStream?.use { book.saveTrace(exit, it) }
    } catch (_: IOException) {
        null
    }
}
