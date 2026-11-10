package com.reau.app.stream

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional (off by default in a release, on in a debug build): a copy of the H.264 sent to the dash, for looking at afterwards.
 *
 * Two files in [dir] per recording, named after the moment it began:
 * - `<name>.h264`: the units exactly as they went out (SPS + PPS + IDR bundled, SPS already rewritten for the
 *   dash), each after a 00 00 00 01 start code. A plain Annex-B stream: `ffplay`, VLC and MediaCodec open it.
 * - `<name>.csv`: one line per unit with the time it was stamped with (the video clock, which an H.264 stream
 *   does not carry), its size in the file (`bytes_in_file`: the start code counts, so the column adds up to the file) and whether it is a keyframe. That is what shows the pace the dash was given. The time
 *   counts from the file's first unit (0), whatever the clock read when the recording began: the clock runs on across
 *   a dash that dropped and came back, and a second recording of the same projection began at 400000 ms.
 *
 * It opens with the first keyframe it is handed. After [maxFrames] units it stops by itself, or, with [rolling] (the app's option),
 * goes on in a new part (`<name>_p02`, `_p03`…, each with its own csv, opening with the next keyframe): a connection of hours is then
 * recorded to its end, and the ceiling of the recordings (1024 MB in a debug build, with the sessions') lets the oldest parts go, with no count of files. Whatever goes wrong with the files costs the recording and one line in the log,
 * never the stream: [write] is called on the encoder's output thread, in the middle of sending.
 *
 * Names to the
 * millisecond, and a recording never takes a name already in the folder (to the second, two recordings begun in the
 * same second shared one file and the second overwrote the first); both files are flushed at every keyframe, so an
 * app killed mid-recording leaves everything up to the last keyframe on disk, decodable.
 */
internal class H264Recorder(
    private val dir: File,
    private val maxFrames: Int,
    name: String,
    private val rolling: Boolean = false,
    private val log: (String) -> Unit,
) {
    private val firstBase = freeName(dir, name)

    /** The part being written: the whole recording when it does not roll. */
    var videoFile = File(dir, "$firstBase.$VIDEO_EXT")
        private set
    var indexFile = File(dir, "$firstBase.csv")
        private set

    /** Which part of the recording is being written (1 without [rolling]). */
    var part = 1
        private set

    /** Units written so far in this part. */
    var frames = 0
        private set

    /** Complete, closed or failed: nothing more will be written. */
    var isDone = false
        private set

    private var bytes = 0L
    // The clock's reading at the file's first unit: the csv counts from it.
    private var firstPtsMs = 0L
    private var video: OutputStream? = null
    private var index: Writer? = null

    /** One unit that went to the dash (no start code), with its frame's time. */
    @Synchronized
    fun write(nal: ByteArray, isKey: Boolean, ptsMs: Long) {
        val recording = !isDone && maxFrames > 0
        // A file that does not open with a keyframe cannot be decoded.
        if (!recording || (video == null && !isKey)) return
        runCatching {
            val out = video ?: open().also { firstPtsMs = ptsMs }
            out.write(START_CODE)
            out.write(nal)
            checkNotNull(index).write("${frames + 1},${ptsMs - firstPtsMs},${START_CODE.size + nal.size},${if (isKey) 1 else 0}\n")
            frames++
            bytes += START_CODE.size + nal.size
            if (isKey) {
                out.flush()
                index?.flush()
            }
            if (frames >= maxFrames) {
                if (rolling) rollToNextPart() else finish("complete")
            }
        }.onFailure {
            closeFiles()
            isDone = true
            log("video recording failed: $it")
        }
    }

    /** The stream ended: closes the files. Nothing is written after this. */
    @Synchronized
    fun close() {
        if (!isDone && video != null) finish("stopped early")
        isDone = true
    }

    private fun open(): OutputStream {
        dir.mkdirs()
        com.reau.app.data.RecordingsSpace.makeRoom(dir)?.let(log) // Both recording folders together under their ceiling
        index = indexFile.bufferedWriter().also { it.write("$INDEX_HEADER\n") }
        return BufferedOutputStream(videoFile.outputStream()).also { video = it }
    }

    /** This part is full: it is closed and the next one begins with the next keyframe. */
    private fun rollToNextPart() {
        finish("part $part complete, the next follows")
        part++
        val base = freeName(dir, "${firstBase}_p${"%02d".format(Locale.US, part)}")
        videoFile = File(dir, "$base.$VIDEO_EXT")
        indexFile = File(dir, "$base.csv")
        frames = 0
        bytes = 0L
        isDone = false
    }

    private fun finish(how: String) {
        closeFiles()
        isDone = true
        log("video recording $how: $frames frames, $bytes bytes, ${videoFile.path}")
    }

    private fun closeFiles() {
        runCatching { video?.close() }
        runCatching { index?.close() }
        video = null
        index = null
    }

    companion object {
        private const val VIDEO_EXT = "h264"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /**
         * The first line of every `.csv`. `bytes_in_file` is the size of the unit **in the `.h264`, start code included**, so the column adds up
         * to the file's size (and a unit's offset is the sum of the ones before it). Recordings from 20/10 to 03/11/2026 have `frame,pts_ms,bytes,key`,
         * where `bytes` left the 4 bytes of the start code out: a tool that reads both adds 4 per unit to the old ones.
         */
        const val INDEX_HEADER = "frame,pts_ms,bytes_in_file,key"

        /** The file name (no extension) of a recording that begins at [nowMs]: it sorts by time. */
        fun nameFor(nowMs: Long): String = "reau_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(nowMs))

        /** [name], or `<name>_2`, `_3`… when a recording in [dir] already has it: a recording never overwrites another. */
        private fun freeName(dir: File, name: String): String {
            fun taken(n: String) = File(dir, "$n.$VIDEO_EXT").exists() || File(dir, "$n.csv").exists()
            return if (!taken(name)) name else generateSequence(2) { it + 1 }.map { "${name}_$it" }.first { !taken(it) }
        }
    }
}
