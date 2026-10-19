// Written for REau after OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AaMicrophone.kt,
// commit 0abbe2a. Kept: open on Android Auto's MICROPHONE_REQUEST and send until it asks to close, the answer saying
// whether it really opened, 20 ms chunks of 16 kHz mono PCM, and the media data message (channel, flags 0x0b, type 0,
// the time in big-endian microseconds, the samples little-endian). Changes: the capture is behind VoiceSource (the
// phone's is PhoneVoice, where the Bluetooth headset is preferred), so the protocol side is tested on the JVM; the
// answer itself is sent by AapControlMedia; the source is closed on the pump's own thread, never under a read; a voice
// that breaks while listening is let go, and the next request opens it again.
package com.reau.app.aa

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where the rider's voice comes from: 16 kHz, 16-bit, mono, as the service discovery response announces. */
interface VoiceSource {
    /** Starts capturing; false if it could not (no permission, the microphone busy). */
    fun open(): Boolean

    /** Waits for samples and puts them in [into]; how many, or a negative number once closed or broken. */
    fun read(into: ShortArray): Int

    fun close()
}

/**
 * Android Auto's microphone, for Gemini (behind the switch "Gemini pelo joystick").
 * Android Auto asks to open it when the assistant starts listening and to close it when it is done; while open, what
 * [source] hears goes to [send] as media data on the microphone channel.
 */
internal class AaMicrophone(
    private val source: VoiceSource,
    private val send: (AapMessage) -> Unit,
    private val clockUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / NS_PER_US },
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    @Volatile private var pump: Thread? = null

    // Each listening closes its source once, whichever side gets there first (the pump, or stop() on a stalled read).
    @Volatile private var closed = AtomicBoolean(true)

    /** Android Auto's request to open or close. True when the answer is success (closing always is). */
    fun request(open: Boolean): Boolean {
        if (!open) {
            stop("Android Auto closed it")
            return true
        }
        return start()
    }

    private fun start(): Boolean = synchronized(lock) {
        if (pump != null) return true
        val opened = runCatching { source.open() }.onFailure { log("microphone: could not open: $it") }.getOrDefault(false)
        if (!opened) {
            log("microphone: could not open, Android Auto is told so")
            return false
        }
        log("microphone: open, $SAMPLE_RATE Hz mono to Android Auto")
        val once = AtomicBoolean(false)
        closed = once
        // Made, then known as the pump, then started: started first, it could find itself not the pump yet and quit.
        val listening = thread(start = false, name = "aa-mic", isDaemon = true) { pumpLoop(once) }
        pump = listening
        listening.start()
        true
    }

    /**
     * Stops listening (a close from Android Auto, the session ending). The pump closes the source after its read; a
     * read that does not come back in [STOP_WAIT_MS] (a voice that stalled) is unblocked by closing it from here.
     *
     * The session's end calls this from the `finally` of
     * [AaReceiver]'s session, on a thread that [AaReceiver.stop] interrupts. An interruption during the wait must not
     * leave this method by exception, which would skip the release of the decoder, the video thread, the statistics
     * executor, the recorder and the socket that follow it. The wait ends at once instead, the source is closed from
     * here (which unblocks the pump's read), and the thread keeps its interrupted status for the callers after it.
     */
    fun stop(reason: String) {
        val running: Thread
        val once: AtomicBoolean
        synchronized(lock) {
            running = pump ?: return
            once = closed
            pump = null
        }
        try {
            running.join(STOP_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (running.isAlive) closeOnce(once)
        log("microphone: closed ($reason)")
    }

    private fun closeOnce(once: AtomicBoolean) {
        if (once.compareAndSet(false, true)) runCatching { source.close() }
    }

    private fun pumpLoop(once: AtomicBoolean) {
        val me = Thread.currentThread()
        val buffer = ShortArray(CHUNK_SAMPLES)
        var chunks = 0
        try {
            var n = 0
            while (pump === me && n >= 0) {
                n = runCatching { source.read(buffer) }.getOrDefault(-1)
                if (n > 0 && pump === me) {
                    send(dataMessage(buffer, n, clockUs()))
                    chunks++
                }
            }
            if (n < 0) log("microphone: the voice stopped ($n) after $chunks chunks")
        } finally {
            closeOnce(once)
            synchronized(lock) { if (pump === me) pump = null }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms of voice per message, as OpenCfMoto sends. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 50
        private const val NS_PER_US = 1_000L
        private const val STOP_WAIT_MS = 500L
        private const val TIME_BYTES = 8
        private const val MEDIA_FLAGS: Byte = 0x0b

        /** One media data message: header, type 0 (MEDIA_MESSAGE_DATA), the time, then [count] samples. */
        fun dataMessage(samples: ShortArray, count: Int, timestampUs: Long): AapMessage {
            val payload = MsgType.SIZE + TIME_BYTES + count * 2
            val total = AapMessage.HEADER_SIZE + payload
            val data = ByteArray(total)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = MEDIA_FLAGS
            Utils.intToBytes(payload, 2, data)
            // data[4], data[5]: the type, 0
            val body = ByteBuffer.wrap(data, AapMessage.HEADER_SIZE + MsgType.SIZE, TIME_BYTES + count * 2)
            body.order(ByteOrder.BIG_ENDIAN).putLong(timestampUs)
            body.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) body.putShort(samples[i])
            return AapMessage(Channel.ID_MIC, MEDIA_FLAGS, 0, AapMessage.HEADER_SIZE + MsgType.SIZE, total, data)
        }
    }
}
