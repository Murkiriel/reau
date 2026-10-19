package com.reau.app.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.reau.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only ride/connection log written to the app's internal files dir so it can be pulled
 * off the phone after a ride without root:
 *
 *   adb exec-out run-as com.reau.app tar c files/diag | tar x   (internal storage: [AppFolders])
 *
 * Its main job is to localize the dash's "Timeout!": every connection stage is stamped with a
 * +millis offset from the session start, so the gap between "READY", "first video frame sent"
 * and "dash decoded first IDR" is visible after the fact. Here it also records every stage of the
 * Android Auto side (connection, handshake, video) and the frame/thermal stats. Every write is
 * guarded so logging can never crash a ride.
 *
 * Events can happen before any session exists yet — e.g. the Android Auto server is found off
 * before the rider ever connects to the dash. Rather than silently drop those (no session file to
 * write into yet), [log] buffers them and [start] flushes the buffer into the fresh session file,
 * so nothing from setup is lost just because it happened first.
 *
 * [enabled] is the rider's own kill switch for this ongoing, per-event logging — every
 * [start]/[log] call becomes a no-op while it's off, which is where the real disk-write volume is.
 */
@Suppress("TooManyFunctions") // the logging calls plus logAndWait and the process-long file
object RideDiagnostics {
    // Count-based, not size-based: failed connect attempts leave tiny logs, and at 12 a busy day of
    // retries rotated out a 30fps test session before it could be read.
    internal const val KEEP_FILES = 50
    private const val MAX_PENDING = 50
    // Lines waiting for the writer, at most. Past it they are dropped (a disk that stopped answering must
    // not take the app's memory with it), and the next line written says how many.
    internal const val MAX_QUEUED = 5_000
    private const val CLOSE_WAIT_S = 5L
    private const val LINE_BREAK = " ⏎ "
    const val WARN = "W: "
    const val ERROR = "E: "
    private val BREAKS = Regex("""[ \t]*(?:\r\n|\r|\n)+[ \t]*""")
    private const val PREFS_NAME = "ride_diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var sessionStartMs = 0L
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val pending = ArrayList<String>()

    // The file opened by startProcess lives as long as the process; a projection only marks itself
    // on and off inside it (sessionActive). A new file starts each day and once the file passes maxFileBytes.
    @Volatile private var processOpen = false
    @Volatile private var sessionActive = false
    @Volatile internal var maxFileBytes = MAX_FILE_BYTES

    /** The diag folder's ceiling, kept by [DiagSpace] whenever a file is opened. */
    @Volatile internal var spaceCapBytes = DiagSpace.CAP_BYTES
    private var spaceNote: String? = null
    private val defaultToday: () -> String = { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
    private var today: () -> String = defaultToday
    private var fileDay = ""
    private var fileBytes = 0L
    // log()/raw() are called from every dispatcher in the app -- Main (state collectors), the
    // Default streaming loop (tick(), every ~15s), and even the MediaCodec callback thread. The
    // actual disk write is offloaded to this single-thread executor so none of those callers ever
    // block on File I/O; the executor's single thread also keeps writes in submission order.
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val queued = java.util.concurrent.atomic.AtomicInteger()
    private var dropped = 0

    // The file stays open on the writer's thread (these three are touched only there) instead of being
    // opened and closed for every line; each line is flushed, so a process that dies loses none already written. The
    // writer moves to the new file when the lines start naming another one, and closes at stop/stopProcess.
    private var writer: java.io.Writer? = null
    private var writerFile: File? = null
    private val writerOpens = java.util.concurrent.atomic.AtomicInteger()

    /** On by default — logging is what makes every fix in this app's history diagnosable from a
     * ride log without needing to reproduce it live. Off trades that away for less disk I/O
     * during the ride itself. */
    var enabled: Boolean
        get() = enabledNow
        set(v) {
            enabledNow = v
            prefs?.edit { putBoolean(KEY_ENABLED, v) }
        }

    // Read on every line from every thread, so kept in memory; the preference is read once, in [init].
    @Volatile private var enabledNow = true

    /** The directory holding every rotated log file — used by Settings' "Export logs" (zips
     *  everything here for the share sheet) so a ride log can be taken without `adb`. Null
     *  before [init] has run. */
    fun logDir(): File? = dir

    /** Point the logger at the app's internal diag folder ([AppFolders.diag]). Call once (e.g. from connect()). */
    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            enabledNow = p.getBoolean(KEY_ENABLED, true)
        }
        if (dir != null) return
        runCatching { AppFolders.diag(context).apply { mkdirs() } }
            .onSuccess { dir = it }
    }

    /**
     * Opens the file that lives as long as the process (call from the Application), with what was
     * logged before it. From then on [start]/[stop] only mark the projection on and off inside it.
     */
    fun startProcess(reason: String) {
        if (!enabled) return
        val d = dir ?: return
        synchronized(lock) {
            if (file != null) return
            openFile(d)
            processOpen = true
            sessionStartMs = System.currentTimeMillis()
            // Which APK wrote the log: name, code and the commit it was built from.
            val app = "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_COMMIT})"
            val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            raw("==== process start: $reason — $app, $device ====")
            noteSpace()
            flushPending()
        }
    }

    /** Closes the process file (instrumented tests that need the per-session files of before). */
    fun stopProcess(reason: String) {
        synchronized(lock) {
            if (!processOpen) return
            raw("==== process end: $reason ====")
            file = null
            closeWriterLater()
            processOpen = false
            sessionActive = false
        }
    }

    /**
     * Open a fresh session file, rotate old ones, and flush anything logged before this session
     * existed. More than one part of the app may call this for the same ride (the Android Auto side
     * and the dash side come up independently), so it is a no-op beyond a marker line when a
     * session is already open, instead of fragmenting one ride into two files.
     */
    fun start(reason: String) {
        if (!enabled) return
        val d = dir ?: return
        synchronized(lock) {
            if (sessionActive) {
                raw("---- $reason (session already active) ----")
                return
            }
            sessionActive = true
            sessionStartMs = System.currentTimeMillis()
            if (processOpen && file != null) {
                raw("---- projeção ligada: $reason ----")
                return
            }
            openFile(d)
            raw("==== session start: $reason — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ====")
            noteSpace()
            flushPending()
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        raw("---- pre-connect events (buffered before this session started) ----")
        pending.forEach { raw(it) }
        pending.clear()
    }

    /** A new file in [d], named by the second (with a suffix when that name is taken), and the old ones rotated. */
    private fun openFile(d: File) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var f = File(d, "ride-$stamp.log")
        var n = 2
        while (f.exists()) f = File(d, "ride-$stamp-${n++}.log")
        // Created synchronously so it exists the instant this returns; only the lines go through the writer.
        runCatching { f.createNewFile() }
        file = f
        fileDay = today()
        fileBytes = 0L
        rotate(d)
        // The folder under its ceiling, said right after the new file's header (noteSpace).
        spaceNote = DiagSpace.trim(d, spaceCapBytes, setOfNotNull(f.name, OwnLogcat.currentFile()?.name))
        ageNote = trimByAge(d)
    }

    private fun noteSpace() {
        spaceNote?.let { raw("diag space: $it") }
        spaceNote = null
        ageNote?.let { raw("diag age: $it") }
        ageNote = null
    }

    /** The name of the file being written, if any (never removed to make room). */
    internal fun currentFileName(): String? = file?.name

    /** The process file goes on in a new one: a new day, or past [maxFileBytes]. */
    private fun rollIfDue() {
        val d = dir ?: return
        if (!processOpen || file == null) return
        if (fileBytes < maxFileBytes && today() == fileDay) return
        val old = file?.name
        openFile(d)
        raw("==== continued from $old ====")
        noteSpace()
    }

    /** True once a session file is open (between [start] and [stop]) — lets a caller avoid
     * stopping a session someone else is still using. */
    fun isSessionActive(): Boolean = sessionActive

    /**
     * Record one event, stamped with wall clock + offset from session start. Before any session
     * has started, this buffers the line (bounded, oldest dropped first) instead of discarding it —
     * see class doc.
     */
    fun log(tag: String, msg: String) {
        if (!enabled) return
        synchronized(lock) {
            if (file == null) {
                val line = "(pre-connect)  [$tag] $msg"
                pending.add(line)
                if (pending.size > MAX_PENDING) pending.removeAt(0)
                return
            }
            rollIfDue()
            // The offset is the projection's, so none outside one (not from the process start).
            val rel = if (sessionActive && sessionStartMs > 0) "+%6dms".format(System.currentTimeMillis() - sessionStartMs) else "         "
            raw("$rel  [$tag] $msg")
        }
    }

    /**
     * A line that says something went wrong or is going wrong carries its level at the start of its text
     * (`W: ` a warning, `E: ` an error), as the Android Auto lines already did; the tag stays what it was, so the reader
     * still finds it by tag. Sites that only hold a `(String) -> Unit` write the prefix themselves.
     */
    fun warn(tag: String, msg: String) = log(tag, WARN + msg)

    /** See [warn]. */
    fun error(tag: String, msg: String) = log(tag, ERROR + msg)

    /**
     * [log], then waits (at most [timeoutMs]) until the line and everything logged before it are in the file. For a
     * process about to die: the background writer would not get to it.
     */
    fun logAndWait(tag: String, msg: String, timeoutMs: Long = 1_000) {
        log(tag, msg)
        // The writer is one thread taking its work in order: once an empty task has run, every earlier line is written.
        runCatching { ioExecutor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun stop(reason: String) {
        synchronized(lock) {
            when {
                // The process file stays; only the projection is marked off.
                processOpen -> if (sessionActive) {
                    if (enabled) raw("---- projeção desligada: $reason ----")
                    sessionActive = false
                }
                // Still clear the open file even if logging got turned off mid-ride — never leave a
                // dangling session reference just because writes are currently suppressed.
                file != null -> {
                    if (enabled) raw("==== session end: $reason ====")
                    file = null
                    closeWriterLater()
                    sessionActive = false
                }
            }
        }
    }

    /** Always called holding [lock] (the clock and [dropped] need it). */
    private fun raw(line: String) {
        val f = file ?: return
        if (queued.get() >= MAX_QUEUED) {
            dropped++
            return
        }
        val now = clock.format(Date())
        val note = if (dropped > 0) "$now  log: $dropped lines dropped, the writer fell behind\n" else ""
        dropped = 0
        val stamped = "$note$now  ${oneLine(line)}\n"
        fileBytes += stamped.length
        queued.incrementAndGet()
        ioExecutor.execute {
            queued.decrementAndGet()
            write(f, stamped)
        }
    }

    /**
     * One event, one line. A message that came in several lines (Android Auto's "Config response")
     * would leave lines with no time in front; each break, with the spaces around it, becomes [LINE_BREAK], and breaks at
     * the end go.
     */
    private fun oneLine(text: String): String {
        if ('\n' !in text && '\r' !in text) return text
        return text.trimEnd('\n', '\r', ' ').replace(BREAKS, LINE_BREAK)
    }

    /** On the writer's thread only. */
    private fun write(f: File, text: String) {
        runCatching {
            val w = writer.takeIf { writerFile == f } ?: run {
                closeWriter()
                java.io.FileOutputStream(f, true).bufferedWriter().also {
                    writer = it
                    writerFile = f
                    writerOpens.incrementAndGet()
                }
            }
            w.write(text)
            w.flush()
        }.onFailure { closeWriter() }
    }

    /** On the writer's thread only. */
    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
        writerFile = null
    }

    /** After the lines already queued. */
    private fun closeWriterLater() = ioExecutor.execute { closeWriter() }

    /** Test-only hook: points [log]/[raw] straight at a real file, bypassing [init]/[start]'s
     * dependency on a live Android Context (this module has no Robolectric). */
    internal fun testOnlySetFile(f: File) {
        file = f
        sessionActive = true
        sessionStartMs = System.currentTimeMillis()
    }

    /** Test-only: a directory without a Context, and the day as the test wants it. */
    internal fun testOnlyUseDir(d: File, today: () -> String) {
        testOnlyReset()
        dir = d
        this.today = today
    }

    /**
     * The rider switched the log off on the screen: one last line says so, the file is closed,
     * and nothing more is written (nor kept for later) until [switchOn].
     */
    fun switchOff(reason: String) {
        synchronized(lock) {
            if (file != null) raw("==== log switched off: $reason ====")
            file = null
            processOpen = false
            sessionActive = false
            pending.clear()
            closeWriterLater()
            enabled = false
        }
    }

    /** Logs and recordings older than this many days go (0, never). */
    @Volatile internal var keepDays = 0
    private var ageNote: String? = null

    /** The rider's choice of days, applied at once (and again whenever a file is opened); the log says what went. */
    fun applyKeepDays(days: Int) {
        keepDays = days
        synchronized(lock) {
            val d = dir ?: return
            ageNote = trimByAge(d)
            noteSpace()
        }
    }

    private fun trimByAge(d: File): String? {
        val recordings = d.parentFile?.let { listOf(File(it, "video"), File(it, "sessions")) }.orEmpty()
        return LogAge.trim(listOf(d) + recordings, keepDays, System.currentTimeMillis(), setOfNotNull(file?.name, OwnLogcat.currentFile()?.name))
    }

    /** The log on again: a new process file, as at the app's start. */
    fun switchOn(reason: String) {
        enabled = true
        startProcess(reason)
    }

    /**
     * The rider's "Apagar registros": the open file closed (it must be, to be deleted), [cleanup] run on the folder,
     * and, if a file was open, a new one opened in its place that starts by saying what [cleanup] said went. Null when
     * there is no folder yet.
     */
    fun clearAndReopen(cleanup: (File) -> String): String? {
        synchronized(lock) {
            val d = dir ?: return null
            val wasOpen = file != null
            file = null
            pending.clear()
            runCatching { ioExecutor.submit { closeWriter() }.get(CLOSE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) }
            val said = cleanup(d)
            if (wasOpen && enabled) {
                openFile(d)
                raw("==== the rider deleted the logs: $said ====")
                noteSpace()
            }
            return said
        }
    }

    /** Test-only: how many times the writer opened a file. */
    internal fun testOnlyWriterOpens(): Int = writerOpens.get()

    /** Test-only: whether the writer holds a file open, once the lines already queued are written. */
    internal fun testOnlyWriterIsOpen(): Boolean =
        ioExecutor.submit<Boolean> { writer != null }.get(10, java.util.concurrent.TimeUnit.SECONDS)

    /** Test-only: holds the writer until the returned function is called, so lines pile up in its queue. */
    internal fun testOnlyHoldWriter(): () -> Unit {
        val gate = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute { gate.await() }
        return { gate.countDown() }
    }

    /** Test-only: back to nothing open. */
    internal fun testOnlyReset() {
        synchronized(lock) {
            file = null; processOpen = false; sessionActive = false; pending.clear(); dropped = 0; enabledNow = true
            maxFileBytes = MAX_FILE_BYTES; today = defaultToday; dir = null; spaceCapBytes = DiagSpace.CAP_BYTES
            keepDays = 0; ageNote = null
        }
        runCatching { ioExecutor.submit { closeWriter() }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        writerOpens.set(0)
    }

    /**
     * Test-only: the currently-open session file's name, or null if none. [RideDiagnostics] is a
     * real process-wide singleton (by design — there's only ever one dash link on a real phone),
     * so an instrumented test asserting "exactly N files in [logDir]" is asserting about the WHOLE
     * shared directory, not just what that one test wrote — any other test class in the same
     * instrumentation process whose ViewModel/coroutines haven't fully wound down yet (a
     * `viewModelScope` job cancellation is asynchronous; code already past its last suspension
     * point keeps running to completion) can land a stray file in that same window. This accessor
     * lets a test assert the actual invariant it cares about (same file stays open across repeated
     * [start] calls, a session did/didn't open) against [RideDiagnostics]'s own state instead of a
     * shared directory listing — confirmed live: a full instrumented suite run caught
     * exactly this race failing [startIsIdempotentAndDoesNotFragmentOneRideIntoTwoFiles]-shaped
     * assertions nondeterministically.
     */
    internal fun testOnlyActiveFileName(): String? = file?.name

    /** Internal (not private) so RideDiagnosticsRotateTest can exercise it with a real temp dir. */
    internal fun rotate(d: File) {
        val logs = d.listFiles { x -> x.name.startsWith("ride-") && x.name.endsWith(".log") } ?: return
        if (logs.size <= KEEP_FILES) return
        logs.sortedBy { it.lastModified() }.dropLast(KEEP_FILES).forEach { runCatching { it.delete() } }
    }
}
