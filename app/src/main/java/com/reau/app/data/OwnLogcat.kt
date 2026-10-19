package com.reau.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * REau reads its OWN logcat (`logcat --pid`, no permission needed) into
 * `diag/logcat-AAAAMMDD-HHMMSS.txt` while the app lives. It catches what goes only to logcat, the libraries' and the
 * system's messages about this process included. The reader is opened again if it dies ([LogcatPump]); a new file
 * starts at [MAX_FILE_BYTES], and the newest [KEEP_FILES] are kept; the whole folder stays under its ceiling
 * ([DiagSpace]).
 */
@Suppress("TooManyFunctions") // the reader and its file, the flush, the pause the rider's switch and deletion need
object OwnLogcat {
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    internal var maxFileBytes = MAX_FILE_BYTES
    private const val KEEP_FILES = 10
    private val lock = Any()

    @Volatile private var pump: LogcatPump? = null
    @Volatile private var reader: Process? = null
    @Volatile private var opens = 0
    private var dir: File? = null
    private var out: PrintWriter? = null
    private var file: File? = null
    private var bytes = 0L
    private var paused = false

    /** Starts reading, once per process (call from the Application). */
    fun start(context: Context) {
        synchronized(lock) {
            if (pump != null) return
            dir = runCatching { AppFolders.diag(context).apply { mkdirs() } }.getOrNull() ?: return
            // The log switched off on the screen: the reader starts, but nothing is written until it is on again.
            paused = !RideDiagnostics.enabled
            if (!paused) newFile()
            pump = LogcatPump(open = ::openReader, sink = ::write, log = { RideDiagnostics.log("logcat", it) }).also { it.start() }
        }
        startFlushing()
    }

    /** The file being written, for tests. */
    internal fun currentFile(): File? = file

    /** Test-only: a folder without a Context and without the logcat reader; the flush once a second runs. */
    internal fun testOnlyUseDir(d: File) {
        synchronized(lock) {
            dir = d
            newFile()
        }
        startFlushing()
    }

    /**
     * The log switched off, or its folder about to be cleared: the file is closed and
     * nothing is written until [resume]. The reader goes on (it is opened once per process), its lines dropped.
     */
    fun pause() {
        synchronized(lock) {
            paused = true
            out?.close()
            out = null
            file = null
        }
    }

    /** Writing again, into a new file. */
    fun resume() {
        synchronized(lock) {
            if (!paused) return
            paused = false
            newFile()
        }
    }

    /** Test-only: back to nothing open. */
    internal fun testOnlyReset() {
        synchronized(lock) {
            out?.close()
            out = null
            file = null
            dir = null
            paused = false
            maxFileBytes = MAX_FILE_BYTES
        }
    }

    // The file is flushed once a second (and when closed), not for every line logcat gives.
    private val flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "own-logcat-flush").apply { isDaemon = true }
    }
    @Volatile private var flushing = false

    private fun startFlushing() {
        synchronized(lock) {
            if (flushing) return
            flushing = true
        }
        flusher.scheduleWithFixedDelay({ synchronized(lock) { out?.flush() } }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Kills the logcat process being read, for the test of the reader coming back. */
    internal fun testOnlyKillReader() {
        reader?.destroy()
    }

    private fun openReader(): Sequence<String> {
        // The first reader also takes what this process logged before it; later ones only what is new (-T 1), so a
        // reader opened again does not copy the whole buffer a second time.
        val since = if (opens++ == 0) emptyList() else listOf("-T", "1")
        val command = listOf("logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}") + since
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        reader = p
        return p.inputStream.bufferedReader().lineSequence()
    }

    internal fun write(line: String) {
        synchronized(lock) {
            if (paused) return
            if (bytes >= maxFileBytes) newFile()
            out?.println(line)
            bytes += line.length + 1
        }
    }

    private fun newFile() {
        val d = dir ?: return
        out?.close()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var f = File(d, "logcat-$stamp.txt")
        var n = 2
        while (f.exists()) f = File(d, "logcat-$stamp-${n++}.txt")
        file = f
        bytes = 0L
        out = runCatching { PrintWriter(f.outputStream().bufferedWriter(), false) }.getOrNull()
        d.listFiles { x -> x.name.startsWith("logcat-") && x.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }?.dropLast(KEEP_FILES)?.forEach { runCatching { it.delete() } }
        DiagSpace.trim(d, DiagSpace.CAP_BYTES, setOfNotNull(f.name, RideDiagnostics.currentFileName()))
            ?.let { RideDiagnostics.log("diag", "space: $it") }
    }
}
