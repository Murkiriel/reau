package com.reau.app.aa

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One message of a recorded session: [timeMs] after the first one, and the [payload] with its two type bytes. */
class RecordedMessage(val timeMs: Long, val channel: Int, val flags: Int, val payload: ByteArray) {
    val type: Int get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
}

/** What a recording file holds. [truncated]: the file ends in the middle of a message, which is not in [messages]. */
class SessionRecording(val startedAtMs: Long, val messages: List<RecordedMessage>, val truncated: Boolean)

/**
 * Debug option: keeps what Android Auto sent in a session, message by message as the handler got them, already
 * decrypted. With such a file a test can hand a real session to the message handler again, with no phone.
 *
 * The recording holds the video of the session, so what was on the map: it stays in the app's own folder, off by
 * default, and only the last [KEEP] are kept.
 *
 * The file: "REAA", the format version (1 byte), the clock time of the first
 * message (8 bytes); then per message
 * the milliseconds since the first (4 bytes), channel (1), flags (1), payload length (4) and the payload. Numbers
 * are big-endian.
 *
 * The file is created by the first message, ends by itself before it would pass [maxBytes] (0 or less: the option
 * is off) and whatever goes wrong with it costs the recording and one line in the log, never the session: [write]
 * runs on the session's read thread.
 */
internal class SessionRecorder(
    private val dir: File,
    name: String,
    private val maxBytes: Long,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val file = File(dir, "$name.$EXTENSION")

    /** Messages written so far. */
    var messages = 0
        private set

    /** Closed, full or failed: nothing more will be written. */
    var isDone = false
        private set

    private var bytes = 0L
    private var startedAtMs = 0L
    private var out: DataOutputStream? = null

    @Synchronized
    fun write(message: AapMessage) {
        if (isDone || maxBytes <= 0) return
        val size = (if (out == null) HEADER_BYTES else 0) + RECORD_HEADER_BYTES + message.size
        if (bytes + size > maxBytes) {
            finish("full")
            return
        }
        try {
            val stream = out ?: open()
            stream.writeInt((nowMs() - startedAtMs).toInt())
            stream.writeByte(message.channel)
            stream.writeByte(message.flags.toInt())
            stream.writeInt(message.size)
            stream.write(message.data, 0, message.size)
            messages++
            bytes += size
        } catch (e: IOException) {
            closeFile()
            isDone = true
            log("session recording failed: $e")
        }
    }

    /** The session ended: closes the file. */
    @Synchronized
    fun close() = finish("closed")

    private fun open(): DataOutputStream {
        dir.mkdirs()
        prune()
        com.reau.app.data.RecordingsSpace.makeRoom(dir)?.let(log) // both recording folders together under their ceiling
        startedAtMs = nowMs()
        return DataOutputStream(BufferedOutputStream(file.outputStream())).also {
            out = it
            it.write(MAGIC)
            it.writeByte(VERSION)
            it.writeLong(startedAtMs)
        }
    }

    /** Makes room for this recording: only the newest [KEEP] - 1 older ones stay (names sort by time). */
    private fun prune() {
        val old = dir.listFiles { candidate -> candidate.extension == EXTENSION }.orEmpty().sortedBy { it.name }
        old.dropLast(KEEP - 1).forEach { it.delete() }
    }

    private fun finish(how: String) {
        val opened = out != null
        closeFile()
        isDone = true
        if (opened || how != "closed") log("session recording $how: $messages messages, $bytes bytes, ${file.path}")
    }

    private fun closeFile() {
        try {
            out?.close()
        } catch (e: IOException) {
            log("session recording: closing the file failed: $e")
        }
        out = null
    }

    companion object {
        const val KEEP = 3
        const val EXTENSION = "aasession"
        private val MAGIC = byteArrayOf(0x52, 0x45, 0x41, 0x41) // "REAA"

        private const val VERSION = 1
        private const val HEADER_BYTES = 13
        private const val RECORD_HEADER_BYTES = 10

        /** The file name (no extension) of a recording that begins at [nowMs]: it sorts by time. */
        fun nameFor(nowMs: Long): String = "reau_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(nowMs))

        /**
         * Reads a recording. A file that ends in the middle of a message (the app was killed) gives the messages
         * before it; a file that is not a recording, or of another version of the format, is refused.
         */
        fun read(file: File): SessionRecording {
            val length = file.length()
            require(length >= HEADER_BYTES) { "${file.name}: too short to be a session recording" }
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                require(magic.contentEquals(MAGIC)) { "${file.name}: not a session recording" }
                val version = input.readUnsignedByte()
                require(version == VERSION) { "${file.name}: session recording format $version, this app reads $VERSION" }
                val startedAtMs = input.readLong()

                val messages = mutableListOf<RecordedMessage>()
                var left = length - HEADER_BYTES
                while (left >= RECORD_HEADER_BYTES) {
                    val timeMs = input.readInt().toLong()
                    val channel = input.readUnsignedByte()
                    val flags = input.readUnsignedByte()
                    val size = input.readInt()
                    left -= RECORD_HEADER_BYTES
                    if (size < 0 || size > left) return SessionRecording(startedAtMs, messages, truncated = true)
                    messages += RecordedMessage(timeMs, channel, flags, ByteArray(size).also { input.readFully(it) })
                    left -= size
                }
                return SessionRecording(startedAtMs, messages, truncated = left > 0)
            }
        }
    }
}

/** Puts a [SessionRecorder] in front of the session's message handler: every message is kept, then handled. */
internal class RecordingHandler(private val recorder: SessionRecorder, private val next: AapMessageHandler) : AapMessageHandler {
    override fun handle(message: AapMessage) {
        recorder.write(message)
        next.handle(message)
    }
}
