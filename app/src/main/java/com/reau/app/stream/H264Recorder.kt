package com.reau.app.stream

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug option: a copy of the H.264 sent to the dash, for looking at afterwards.
 *
 * Two files in [dir] per recording, named after the moment it began:
 * - `<name>.h264`: the units exactly as they went out (SPS + PPS + IDR bundled, SPS already rewritten for the
 *   dash), each after a 00 00 00 01 start code. A plain Annex-B stream: `ffplay`, VLC and MediaCodec open it.
 * - `<name>.csv`: one line per unit with the time it was stamped with (the video clock, which an H.264 stream
 *   does not carry), its size and whether it is a keyframe. That is what shows the pace the dash was given.
 *
 * It opens with the first keyframe it is handed, stops by itself after [maxFrames] units, and keeps only the last
 * [KEEP] recordings in the folder. Whatever goes wrong with the files costs the recording and one line in the log,
 * never the stream: [write] is called on the encoder's output thread, in the middle of sending.
 *
 * 50 kept; names to the
 * millisecond, and a recording never takes a name already in the folder (to the second, two recordings begun in the
 * same second shared one file and the second overwrote the first); both files are flushed at every keyframe, so an
 * app killed mid-recording leaves everything up to the last keyframe on disk, decodable.
 */
internal class H264Recorder(
    private val dir: File,
    private val maxFrames: Int,
    name: String,
    private val log: (String) -> Unit,
) {
    private val base = freeName(dir, name)
    val videoFile = File(dir, "$base.$VIDEO_EXT")
    val indexFile = File(dir, "$base.csv")

    /** Units written so far. */
    var frames = 0
        private set

    /** Complete, closed or failed: nothing more will be written. */
    var isDone = false
        private set

    private var bytes = 0L
    private var video: OutputStream? = null
    private var index: Writer? = null

    /** One unit that went to the dash (no start code), with its frame's time. */
    @Synchronized
    fun write(nal: ByteArray, isKey: Boolean, ptsMs: Long) {
        val recording = !isDone && maxFrames > 0
        // A file that does not open with a keyframe cannot be decoded.
        if (!recording || (video == null && !isKey)) return
        runCatching {
            val out = video ?: open()
            out.write(START_CODE)
            out.write(nal)
            checkNotNull(index).write("${frames + 1},$ptsMs,${nal.size},${if (isKey) 1 else 0}\n")
            frames++
            bytes += START_CODE.size + nal.size
            if (isKey) {
                out.flush()
                index?.flush()
            }
            if (frames >= maxFrames) finish("complete")
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
        prune()
        com.reau.app.data.RecordingsSpace.makeRoom(dir)?.let(log) // Both recording folders together under their ceiling
        index = indexFile.bufferedWriter().also { it.write("frame,pts_ms,bytes,key\n") }
        return BufferedOutputStream(videoFile.outputStream()).also { video = it }
    }

    /** Makes room for this recording: only the newest [KEEP] - 1 older ones stay (names sort by time). */
    private fun prune() {
        val old = dir.listFiles { file -> file.extension == VIDEO_EXT }.orEmpty().sortedBy { it.name }
        for (file in old.dropLast(KEEP - 1)) {
            file.delete()
            File(dir, "${file.nameWithoutExtension}.csv").delete()
        }
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
        const val KEEP = 50
        private const val VIDEO_EXT = "h264"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** The file name (no extension) of a recording that begins at [nowMs]: it sorts by time. */
        fun nameFor(nowMs: Long): String = "reau_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(nowMs))

        /** [name], or `<name>_2`, `_3`… when a recording in [dir] already has it: a recording never overwrites another. */
        private fun freeName(dir: File, name: String): String {
            fun taken(n: String) = File(dir, "$n.$VIDEO_EXT").exists() || File(dir, "$n.csv").exists()
            return if (!taken(name)) name else generateSequence(2) { it + 1 }.map { "${name}_$it" }.first { !taken(it) }
        }
    }
}
