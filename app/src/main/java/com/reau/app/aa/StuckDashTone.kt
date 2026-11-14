package com.reau.app.aa

import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The rider's earphone for the hung dash. The notice of a dash whose Wi-Fi hung (DashStuck) is a sound on the phone, which in a pocket under a
 * jacket, with a helmet on, may not be heard. The dash cannot be told: a hung dash is one REau cannot talk to (it refuses every join, there is
 * no link to send a card on), so the only other place that reaches the rider is the phone's output, the headset or the helmet's intercom the
 * navigation voice already goes to. Three short beeps are played there through [AaAudioPlayer], the player that already carries Android
 * Auto's sound (its usage keeps it out of Android Auto's capture, and it needs no permission, no focus and no setting): once per absence,
 * together with the notification, never repeated while the dash stays hung, and let go as soon as it is played.
 *
 * The tone is plain: 880 Hz, 180 ms three times, 120 ms apart, a 12 ms fade at each end so it does not click, and a peak at 30% of full scale, below
 * the level of a spoken instruction. Pure ([pcm]), so the JVM tests hold it.
 */
object StuckDashTone {
    /** The player's lane for it: the guidance one (16 kHz mono), a voice-prompt lane, apart from the media's. */
    val STREAM = AaAudioFeed.Stream.GUIDANCE

    const val FREQUENCY_HZ = 880
    const val BEEPS = 3
    const val BEEP_MS = 180
    const val GAP_MS = 120
    const val FADE_MS = 12

    /** The peak of the beep, as a fraction of full scale. */
    const val PEAK = 0.30
    private const val MS_PER_S = 1_000
    private const val FULL_SCALE = 32_767
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    /** How long the tone lasts. */
    const val DURATION_MS = BEEPS * BEEP_MS + (BEEPS - 1) * GAP_MS

    /** The tone as 16-bit little-endian mono PCM at the guidance lane's rate. */
    fun pcm(): ByteArray {
        val rate = STREAM.sampleRate
        val beep = rate * BEEP_MS / MS_PER_S
        val gap = rate * GAP_MS / MS_PER_S
        val fade = rate * FADE_MS / MS_PER_S
        val total = BEEPS * beep + (BEEPS - 1) * gap
        val out = ByteArray(total * BYTES_PER_SAMPLE)
        for (b in 0 until BEEPS) {
            val start = b * (beep + gap)
            for (i in 0 until beep) {
                val envelope = minOf(1.0, i.toDouble() / fade, (beep - 1 - i).toDouble() / fade)
                val sample = (sin(2 * PI * FREQUENCY_HZ * i / rate) * envelope * PEAK * FULL_SCALE).toInt()
                val at = (start + i) * BYTES_PER_SAMPLE
                out[at] = (sample and BYTE_MASK).toByte()
                out[at + 1] = ((sample shr BYTE_BITS) and BYTE_MASK).toByte()
            }
        }
        return out
    }

    /**
     * Plays the tone on a player of its own, made by [newPlayer], and lets it go [holdMs] later (the track needs the time to finish the last beep).
     * Never throws: a phone that cannot play it says so in the log and the notification stands alone.
     */
    fun play(
        scope: CoroutineScope, log: (String) -> Unit, holdMs: Long = DURATION_MS + RELEASE_MARGIN_MS,
        newPlayer: () -> AaAudioPlayer = { AaAudioPlayer(log) },
    ) {
        scope.launch {
            val player = runCatching(newPlayer).onFailure { log("audio: the tone for the hung dash could not be started: $it") }.getOrNull() ?: return@launch
            try {
                player.play(AaAudioFeed.Pcm(STREAM, pcm()))
                log("audio: the hung dash's tone (3 beeps, $DURATION_MS ms) goes to the phone's output, as the notification does")
                delay(holdMs)
            } finally {
                runCatching { player.release() }
            }
        }
    }

    private const val RELEASE_MARGIN_MS = 1_500L
}
