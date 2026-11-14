package com.reau.app.aa

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The rider's mark keeps a copy of the session recording, up to that moment (plan 22, item 2).
 *
 * [SessionRecorder] writes Android Auto's session in a ring of 100 MB (768 MB in a debug build; two halves, `name.1.aasession` and `name.aasession`) and the ring
 * overwrites itself: about 11 minutes of a long ride survive. On 01/11/2026 the mark of 19:15:51 was past the ring by the time the
 * phone was read. The mark is the rider saying "look at this", so at that moment the two halves are copied to a name the ring never
 * touches, `reau_<date>_<time>_marca.1.aasession` and `reau_<date>_<time>_marca.aasession` (the same pair, the older half first, as the readers
 * take them: `scripts/dev/teste_cego/extrair.py`).
 *
 * - **Never on the read thread.** [SessionRecorder.snapshot] only flushes and opens the two files; the bytes are copied here, on the thread
 *   the caller gives ([freeze]), from the lengths the files had at the mark.
 * - **One copy per stretch of the ring.** Marks in the same session with no rollover between them share one copy, which the later mark
 *   replaces with a larger one: the ring still held the same beginning, so nothing is lost and no 100 MB is spent twice ([Last]).
 * - **A ceiling.** [maxMarks] copies or [maxBytes] together, whichever comes first: the oldest copies beyond it go, never the one just made
 *   ([overCeiling], pure). Free space is checked first: with less than the copy plus [reserveBytes] free, nothing is copied and the log says so.
 * - They are recordings like the others: [com.reau.app.data.RecordingsSpace] and the age setting may still remove the oldest.
 * - Every step logs one line (`mark N: …`), and a failure costs the copy only, never the session.
 */
internal class MarkFreezer(
    private val dir: File,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val limits: Limits = Limits(),
    private val freeBytes: (File) -> Long = { android.os.StatFs(it.path).availableBytes },
) {
    /** The ceiling on the copies kept, and the free space the copy has to leave. */
    data class Limits(val maxMarks: Int = MAX_MARKS, val maxBytes: Long = MAX_BYTES, val reserveBytes: Long = RESERVE_BYTES) {
        companion object {
            /** The ceiling of this build: [MAX_BYTES] in a release; in a debug build, whose ring is far larger, up to the recordings' whole budget. */
            fun forBuild(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG) =
                if (debugBuild) Limits(maxBytes = com.reau.app.data.RecordingsSpace.DEBUG_CAP_BYTES) else Limits()
        }
    }

    /** The copy the last mark made: the same stretch of the same session's ring is the one to replace. */
    private class Last(val sessionName: String, val rollovers: Int, val base: String)

    private var last: Last? = null

    /**
     * Copies [snapshot] for the mark [mark]; closes it. Blocks for the copy: run it off the read thread. True if the copy is in place.
     */
    @Synchronized
    fun freeze(snapshot: SessionRecorder.Snapshot, mark: Int): Boolean = snapshot.use {
        val started = nowMs()
        dir.mkdirs()
        removeStale()
        val again = last?.takeIf { it.sessionName == snapshot.sessionName && it.rollovers == snapshot.rollovers && groupExists(it.base) }
        val base = again?.base ?: nameFor(started, zone, taken = ::groupExists)
        val need = snapshot.bytes
        if (freeBytes(dir) < need + limits.reserveBytes) {
            log(
                "mark $mark: the session recording was not copied: ${need / KB} KB to copy, and the phone does not have that " +
                    "and ${limits.reserveBytes / MB} MB more free",
            )
            return false
        }
        try {
            copy(snapshot.older, File(dir, "$base.1.${SessionRecorder.EXTENSION}"))
            copy(snapshot.newer, File(dir, "$base.${SessionRecorder.EXTENSION}"))
        } catch (e: IOException) {
            discardParts(base)
            log("mark $mark: the session recording could not be copied: $e")
            return false
        }
        // A replaced copy that had two halves and now one (the ring cannot lose a half in the same stretch, but a failed rename could).
        if (snapshot.older == null) File(dir, "$base.1.${SessionRecorder.EXTENSION}").delete()
        last = Last(snapshot.sessionName, snapshot.rollovers, base)
        val took = nowMs() - started
        log(
            "mark $mark: the session recording is kept as $base (${need / KB} KB in ${if (snapshot.older != null) "2 parts" else "1 part"}, " +
                (if (again != null) "replacing the copy of an earlier mark in the same stretch of the ring, " else "") +
                "$took ms): the ring will not overwrite it",
        )
        trim(keep = base)
        return true
    }

    private fun groupExists(base: String): Boolean = File(dir, "$base.${SessionRecorder.EXTENSION}").exists() ||
        File(dir, "$base.1.${SessionRecorder.EXTENSION}").exists()

    /** Copies [part] to [target] by way of a `.tmp` file, so a copy that fails half way never leaves a file that looks whole. */
    private fun copy(part: SessionRecorder.Part?, target: File) {
        part ?: return
        val tmp = File(target.path + TMP)
        tmp.outputStream().buffered().use { part.input.copyUpTo(it, part.length) }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("could not rename ${tmp.name}")
        }
    }

    private fun discardParts(base: String) {
        dir.listFiles().orEmpty().filter { it.name.startsWith(base) && it.name.endsWith(TMP) }.forEach { it.delete() }
    }

    /** A `.tmp` of a copy that a killed process left: older than [STALE_TMP_MS] is not in use. */
    private fun removeStale() {
        val now = nowMs()
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(TMP) && isFrozenName(it.name.removeSuffix(TMP)) && now - it.lastModified() > STALE_TMP_MS }
            .forEach { it.delete() }
    }

    /** The oldest copies beyond the ceiling, never [keep]. */
    private fun trim(keep: String) {
        val copies = copies(dir)
        val doomed = overCeiling(copies.map { Copy(it.base, it.bytes, it.modifiedMs) }, keep, limits.maxMarks, limits.maxBytes)
        for (base in doomed) {
            val gone = copies.first { it.base == base }
            gone.files.forEach { it.delete() }
            log(
                "mark: the oldest kept session recording $base (${gone.bytes / KB} KB) removed: " +
                    "over the ceiling of ${limits.maxMarks} copies / ${limits.maxBytes / MB} MB",
            )
        }
    }

    /** One kept copy, as the folder shows it. */
    class Copy(val base: String, val bytes: Long, val modifiedMs: Long)

    private class Found(val base: String, val files: List<File>) {
        val bytes: Long get() = files.sumOf { it.length() }
        val modifiedMs: Long get() = files.maxOf { it.lastModified() }
    }

    companion object {
        /** The most copies kept at once, and their bytes together (each is at most the ring: 100 MB; see [Limits.forBuild] for a debug build). */
        const val MAX_MARKS = 3
        const val MAX_BYTES = 250L * 1024 * 1024

        /** Free space left after the copy. */
        const val RESERVE_BYTES = 100L * 1024 * 1024
        private const val TMP = ".tmp"
        private const val STALE_TMP_MS = 10 * 60 * 1000L
        private const val KB = 1024L
        private const val MB = 1024L * 1024

        private const val SUFFIX = "_marca"
        private val FROZEN = Regex("^(reau_\\d{8}_\\d{6}$SUFFIX\\d*)(\\.1)?\\.${SessionRecorder.EXTENSION}$")

        /** The name (no extension) of the copy made at [nowMs]: `reau_20261101_191551_marca`, a number after `marca` if that second is taken. */
        fun nameFor(nowMs: Long, zone: TimeZone = TimeZone.getDefault(), taken: (String) -> Boolean = { false }): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
            val first = "reau_$stamp$SUFFIX"
            if (!taken(first)) return first
            return generateSequence(2) { it + 1 }.map { "$first$it" }.first { !taken(it) }
        }

        /** Whether [fileName] is a part of a kept copy (and not of the ring, `reau_<date>_<time>_<ms>.aasession`). */
        fun isFrozenName(fileName: String): Boolean = FROZEN.matches(fileName)

        /** The copy [fileName] belongs to (`reau_20261101_191551_marca` for both halves), null if it is not one. */
        fun baseOf(fileName: String): String? = FROZEN.matchEntire(fileName)?.groupValues?.get(1)

        /**
         * The copies to remove, oldest first, when [copies] are over [maxMarks] or [maxBytes] together: only as many as it takes, never [keep]
         * (the one just made), even if that one alone is over the bytes.
         */
        fun overCeiling(copies: List<Copy>, keep: String, maxMarks: Int, maxBytes: Long): List<String> {
            var count = copies.size
            var bytes = copies.sumOf { it.bytes }
            val doomed = mutableListOf<String>()
            for (copy in copies.filter { it.base != keep }.sortedWith(compareBy({ it.modifiedMs }, { it.base }))) {
                if (count <= maxMarks && bytes <= maxBytes) break
                doomed += copy.base
                count--
                bytes -= copy.bytes
            }
            return doomed
        }

        private fun copies(dir: File): List<Found> =
            dir.listFiles().orEmpty().filter { it.isFile }
                .mapNotNull { file -> baseOf(file.name)?.let { it to file } }
                .groupBy({ it.first }, { it.second })
                .map { (base, files) -> Found(base, files) }

        /** Copies [count] bytes of this stream, no more (the file went on growing after the mark), or all of it if it is shorter. */
        private fun java.io.InputStream.copyUpTo(out: OutputStream, count: Long) {
            val buffer = ByteArray(COPY_BUFFER)
            var left = count
            while (left > 0) {
                val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
        }

        private const val COPY_BUFFER = 64 * 1024
    }
}
