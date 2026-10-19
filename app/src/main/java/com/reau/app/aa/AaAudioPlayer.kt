package com.reau.app.aa

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays Android Auto's sound on the phone itself (as well as in the browser): when REau announces the media and
 * guidance sinks, Android Auto stops playing them on the phone and sends them here, so REau plays them again, through the phone's
 * own output (the rider's headset). One lane for each stream, each with a queue ([AaAudioQueue]) and a thread of its own: the read
 * thread that calls [play] never waits for the speaker.
 *
 * Why the track is not played as media: with the sinks announced, Android Auto registers an audio policy that captures (loops back)
 * every player of usage MEDIA or GAME on the phone and sends it to REau (see [AaAudioRoute]). REau's own track played as media was
 * captured and sent back, round and round (old songs went on playing after the music was paused). The mix did not
 * honour `ALLOW_CAPTURE_BY_NONE`, so the phone's sound plays with a usage it does not match, and the route the system gave the track is
 * checked after a few chunks: if it went to the loop-back, the lane stops and says so, rather than howl.
 */
class AaAudioPlayer(private val log: (String) -> Unit = {}) {
    private class Lane(val queue: AaAudioQueue, val track: AudioTrack, @Volatile var running: Boolean = true) {
        lateinit var thread: Thread
    }

    private val lock = Any()
    private val lanes = HashMap<AaAudioFeed.Stream, Lane>()
    private var released = false

    /** Queues [pcm] for its stream; the lane is made with the first sound. Never waits. */
    fun play(pcm: AaAudioFeed.Pcm) {
        val lane = synchronized(lock) { if (released) null else lanes.getOrPut(pcm.stream) { newLane(pcm.stream) } } ?: return
        if (lane.running) lane.queue.offer(pcm.data)
    }

    fun release() {
        val all = synchronized(lock) {
            released = true
            lanes.values.toList().also { lanes.clear() }
        }
        for (lane in all) {
            lane.running = false
            runCatching { lane.track.stop() }
            runCatching { lane.track.release() }
        }
    }

    // False positive: AaAudioRoute.usageOf returns AudioAttributes.USAGE_UNKNOWN (0), but lint cannot see a constant behind a
    // function call, so it flags the setUsage argument. AaAudioRouteTest pins the value.
    @SuppressLint("WrongConstant")
    private fun newLane(stream: AaAudioFeed.Stream): Lane {
        val media = stream == AaAudioFeed.Stream.MEDIA
        val attributes = AudioAttributes.Builder()
            .setUsage(AaAudioRoute.usageOf(stream))
            .setContentType(if (media) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val mask = if (stream.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder().setSampleRate(stream.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()
        val wanted = AaAudioQueue.maxBytesFor(stream) * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(wanted, AudioTrack.getMinBufferSize(stream.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val lane = Lane(AaAudioQueue(AaAudioQueue.maxBytesFor(stream)), track)
        lane.thread = Thread({ pump(stream, lane) }, "aa-audio-${stream.name.lowercase()}").apply { isDaemon = true; start() }
        log("audio: playing $stream on the phone, ${stream.sampleRate} Hz, ${stream.channels} channel(s)")
        return lane
    }

    /** The lane's thread: what the queue gives goes to the speaker, which may hold this thread, and nobody else. */
    private fun pump(stream: AaAudioFeed.Stream, lane: Lane) {
        try {
            lane.track.play()
            var written = 0
            while (lane.running) {
                val chunk = lane.queue.take(WAIT_MS) ?: continue
                lane.track.write(chunk, 0, chunk.size)
                written++
                if (AaAudioRoute.checkNow(written) && AaAudioRoute.isTheLoop(lane.track.routedDevice?.type)) {
                    log("audio: the phone's sound for $stream was routed into Android Auto's capture: not playing it on the phone")
                    lane.running = false
                    lane.queue.clear()
                    lane.track.pause()
                }
            }
        } catch (e: IllegalStateException) {
            log("audio: the phone's speaker for $stream stopped: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val WAIT_MS = 200L
    }
}
