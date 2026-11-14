package com.reau.app.aa

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One message of a recorded session: [timeMs] after the first one, and the [payload] with its two type bytes. */
class RecordedMessage(val timeMs: Long, val channel: Int, val flags: Int, val payload: ByteArray) {
    val type: Int get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    /** One REau sent to Android Auto (a key, a wheel step, a touch), not one it got: a replay leaves it out. */
    val sent: Boolean get() = flags and SessionRecorder.SENT != 0
}

/** What a recording file holds. [truncated]: the file ends in the middle of a message, which is not in [messages]. */
class SessionRecording(val startedAtMs: Long, val messages: List<RecordedMessage>, val truncated: Boolean)

/**
 * Optional (off by default in a release, on in a debug build): keeps what Android Auto sent in a session, message by message as the handler got them, already
 * decrypted. With such a file a test can hand a real session to the message handler again, with no phone.
 *
 * The recording holds the video of the session, so what was on the map: it stays in the app's own folder, off by
 * default, and kept until the recordings' ceiling ([com.reau.app.data.RecordingsSpace]) is reached, the oldest going first.
 *
 * Besides what Android Auto sent, the keys, wheel steps and touches REau sent to it ([writeSent]) are in the file, in their place in time, with
 * [SENT] in their flags, so a session can be read together with what was done in it. They are the rider's use of the phone and the dash, which
 * is why the option is the rider's to switch on (Settings > Debug) and never leaves the phone by itself.
 *
 * The file: "REAA", the format version (1 byte), the clock time of the first
 * message (8 bytes); then per message
 * the milliseconds since the first (4 bytes), channel (1), flags (1), payload length (4) and the payload. Numbers
 * are big-endian.
 *
 * The file is created by the first message and whatever goes wrong with it costs the recording and one line in the log, never the
 * session: [write] runs on the session's read thread. With [ring] false it ends by itself before it would pass [maxBytes] (0 or less:
 * the option is off). With [ring] true (the app's option) it **rolls over**: it fills `name.aasession` up to half of [maxBytes], then keeps it
 * as `name.1.aasession` (the older one of the two dropped) and goes on in a new `name.aasession` with its own header, so the last half to the
 * whole of [maxBytes] of the connection is always there. A connection filled the 100 MB in 13 minutes and the recording stopped, so what was
 * saved was the beginning and never the minutes before a drop (20/10/2026). A rolled file no longer holds the start of the
 * connection (its handshake): it is for looking at the end, not for replaying the session.
 */
internal class SessionRecorder(
    private val dir: File,
    val name: String,
    private val maxBytes: Long,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ring: Boolean = false,
) {
    val file = File(dir, "$name.$EXTENSION")  // right after a rollover it does not exist until the next message: the data are in [previous]

    /** The older half of a ring, once it has rolled over. */
    private val previous = File(dir, "$name.1.$EXTENSION")

    /** How many times the ring rolled over. */
    var rollovers = 0
        private set


    /** Messages written so far. */
    var messages = 0
        private set

    /** Closed, full or failed: nothing more will be written. */
    var isDone = false
        private set

    private var bytes = 0L
    private var startedAtMs = 0L
    // The time of the last message kept, since the first: how much of the connection the file covers.
    private var lastMs = 0L
    private var out: DataOutputStream? = null

    @Synchronized
    fun write(message: AapMessage) = put(message.channel, message.flags.toInt(), message.data, 0, message.size)

    /**
     * A message REau sent ([frame], as it went out: channel, flags, length, type, body): kept with [SENT] in its flags and the
     * payload as the read loop would have it (the two type bytes, then the body), so the file reads the same.
     */
    @Synchronized
    fun writeSent(frame: AapMessage) =
        put(frame.channel, frame.flags.toInt() or SENT, frame.data, AapMessage.HEADER_SIZE, frame.size - AapMessage.HEADER_SIZE)

    private fun put(channel: Int, flags: Int, data: ByteArray, offset: Int, length: Int) {
        if (isDone || maxBytes <= 0) return
        val size = (if (out == null) HEADER_BYTES else 0) + RECORD_HEADER_BYTES + length
        if (bytes + size > segmentBytes()) {
            // Without the ring the file ends here; with it too when nothing is in this part yet (one message that alone does not fit).
            if (!ring || bytes == 0L) return finish("full")
            rollOver()
            return put(channel, flags, data, offset, length)
        }
        try {
            val stream = out ?: open()
            lastMs = nowMs() - startedAtMs
            stream.writeInt(lastMs.toInt())
            stream.writeByte(channel)
            stream.writeByte(flags)
            stream.writeInt(length)
            stream.write(data, offset, length)
            messages++
            bytes += size
        } catch (e: IOException) {
            closeFile()
            isDone = true
            log("session recording failed: $e")
        }
    }

    /** One part of the ring is half of the limit; without the ring the file may fill the limit. */
    private fun segmentBytes(): Long = if (ring) maxOf(maxBytes / 2, HEADER_BYTES + RECORD_HEADER_BYTES + 1L) else maxBytes

    /** The file is full: it becomes the older half (the one before it goes) and a new one begins with the next message. */
    private fun rollOver() {
        val covered = lastMs / 1_000
        closeFile()
        // The rename replaces the older half in one step; only if the system refuses that is the older half deleted first, then the recording.
        if (!file.renameTo(previous)) {
            previous.delete()
            if (!file.renameTo(previous)) file.delete() // a rename refused twice costs the part just filled, never the recording to come
        }
        rollovers++
        bytes = 0
        log("session recording rolled over (#$rollovers): ${previous.name} keeps the first $covered s of that part; the part before it is dropped")
    }

    /** The session ended: closes the file. */
    @Synchronized
    fun close() = finish("closed")

    /** One half of a ring, opened for [MarkFreezer] to copy: [length] bytes of [input] (the file may go on growing, or be renamed, after this). */
    class Part(val input: FileInputStream, val length: Long)

    /**
     * What a [snapshot] holds: the two halves of the ring as they were at that moment ([older] is `name.1.aasession`, [newer] `name.aasession`),
     * for the ring's [rollovers] count of [sessionName]. Closes both streams.
     */
    class Snapshot(val sessionName: String, val rollovers: Int, val older: Part?, val newer: Part?) : Closeable {
        val bytes: Long get() = (older?.length ?: 0L) + (newer?.length ?: 0L)

        override fun close() {
            runCatching { older?.input?.close() }
            runCatching { newer?.input?.close() }
        }
    }

    /**
     * The ring as it is now, for a copy that the ring will not overwrite (the rider's mark, [MarkFreezer]). It is quick and never copies
     * a byte: it flushes what the stream holds and opens the two files, with the length each has now, so the read thread that writes here
     * (this is its lock) is not held for the copy, which the caller makes elsewhere. A file opened stays readable if the ring renames or
     * drops it afterwards. Null when nothing was written yet, or the files could not be opened.
     */
    @Synchronized
    fun snapshot(): Snapshot? {
        var older: Part? = null
        var newer: Part? = null
        try {
            out?.flush()
            if (previous.isFile) older = Part(FileInputStream(previous), previous.length())
            if (file.isFile) newer = Part(FileInputStream(file), file.length())
        } catch (e: IOException) {
            log("session recording: opening the files for a copy failed: $e")
            Snapshot(name, rollovers, older, newer).close()
            return null
        }
        return if (older == null && newer == null) null else Snapshot(name, rollovers, older, newer)
    }

    private fun open(): DataOutputStream {
        dir.mkdirs()
        // No count of recordings: they all stay until the two folders together pass their ceiling, and then the oldest go (a few short tests had
        // pushed the night ride of 24/10/2026 out of a count of three).
        com.reau.app.data.RecordingsSpace.makeRoom(dir)?.let(log)
        startedAtMs = nowMs()
        return DataOutputStream(BufferedOutputStream(file.outputStream())).also {
            out = it
            it.write(MAGIC)
            it.writeByte(VERSION)
            it.writeLong(startedAtMs)
        }
    }

    private fun finish(how: String) {
        val opened = out != null
        closeFile()
        isDone = true
        if (opened || how != "closed") {
            // A recording that filled up stops there: said, with how far it got (a file of 100 MB once ended 56 s before the connection did).
            val covers = if (rollovers > 0) {
                "the last part covers ${lastMs / 1_000} s, after $rollovers rollover(s)"
            } else {
                "covers the first ${lastMs / 1_000} s" + if (how == "full") " only; the rest of the connection is not recorded" else ""
            }
            val inPart = if (rollovers > 0) " in the last part" else ""
            log("session recording $how: $messages messages, $bytes bytes$inPart, ${file.path} — $covers")
        }
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
        const val EXTENSION = "aasession"

        /** The flag bit of a message REau sent; Android Auto's own flags use only the low nibble. */
        const val SENT = 0x80
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
