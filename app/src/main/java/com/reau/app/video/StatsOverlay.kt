package com.reau.app.video

import com.reau.app.dash.BitrateMode
import java.util.Locale

/**
 * REau writes the stream's FPS and bitrate on the dash's own video, red with a black
 * border, behind a switch in the debug settings. [x] and [y] are the top-left corner of the text's box and [size]
 * the height of its letters, all in pixels of the dash's 526×300 frame ([DashGlass]); the user sets them on the
 * "Tela do Android Auto", where the box is drawn at the place it lands. No Android in it: tested on the JVM.
 */
data class StatsOverlay(
    val on: Boolean = false,
    val x: Int = DEFAULT_X,
    val y: Int = DEFAULT_Y,
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        val X = 0..DashGlass.FRAME_W
        val Y = 0..DashGlass.FRAME_H
        val SIZE = 10..60

        // Inside the round glass, where its chord at the top is wide enough for the default text.
        const val DEFAULT_X = 160
        const val DEFAULT_Y = 44
        const val DEFAULT_SIZE = 22

        /** What the numbers say before the first measurement arrives. */
        const val UNKNOWN = "-- fps  -- kbps"

        /** The label under the numbers: Android Auto's own picture on the phone. */
        const val APP = "app"

        /**
         * What the dash's video says now, for the preview on the "Tela do Android Auto" to show the same (the stream and
         * the screen are in the one process); the app's, with dashes, while nothing is measured.
         */
        @Volatile var latest: StatsText = StatsText(UNKNOWN, APP)

        /** A rate and a bitrate as the text says them; [UNKNOWN] with none. */
        fun numbers(reading: Reading?): String =
            if (reading == null) UNKNOWN else String.format(Locale.US, "%.1f fps  %.0f kbps", reading.fps, reading.kbps)

        /** [frames] and [bytes] counted over [elapsedMs]: the rate in frames a second and the bitrate in kbit/s. */
        fun reading(frames: Long, bytes: Long, elapsedMs: Long): Reading? =
            if (elapsedMs <= 0) null else Reading(frames * MS_PER_S / elapsedMs, bytes * BITS_PER_BYTE / elapsedMs.toDouble())

        /** The average size of a frame, the bitrate over the rate (kbit/s ÷ 8 ÷ fps = KB, 1000 bytes); dashes with none. */
        fun frameSize(reading: Reading?, words: StatsWords): String =
            if (reading == null || reading.fps <= 0.0) {
                "-- ${words.frameUnit}"
            } else {
                String.format(Locale.US, "%.1f %s", reading.kbps / BITS_PER_BYTE / reading.fps, words.frameUnit)
            }

        /** The bitrate mode the encoder runs in; the [StatsWords.defaultMode] word when none is asked (the codec's own). */
        fun mode(mode: BitrateMode?, words: StatsWords): String = mode?.name ?: words.defaultMode

        /** The keyframe interval: `KF 1s` when the rider fixed it, `KF auto 2s` when it is automatic (the abbreviation is a word of [words]). */
        fun keyframe(seconds: Int, fixed: Boolean, words: StatsWords): String =
            if (fixed) "${words.keyframe} ${seconds}s" else "${words.keyframe} auto ${seconds}s"

        /** The route's speed, whole km/h; null when REau has none. */
        fun speed(kmh: Double?): String? = kmh?.let { "${Math.round(it)} km/h" }

        private const val MS_PER_S = 1000.0
        private const val BITS_PER_BYTE = 8
    }
}

/**
 * The few words the text is written with, in the rider's language: [panel] (the label of the stream to the dash),
 * [defaultMode] (the encoder's own bitrate mode), [frameUnit] ("KB/frame") and [keyframe] (the abbreviation). The service
 * reads them from the resources; the numbers, "fps", "kbps" and "km/h" are the same in every language.
 */
data class StatsWords(val panel: String, val defaultMode: String, val frameUnit: String, val keyframe: String)

/** A measured frame rate and bitrate (kbit/s). */
data class Reading(val fps: Double, val kbps: Double)

/**
 * What is written: the [numbers]; under them the [label] saying whose they are ("app" or [StatsWords.panel]), with the [extra]
 * after it (the bitrate mode and the keyframe, of the stream to the dash); and, last, the [detail] (the average frame
 * size and, when REau has it, the speed).
 */
data class StatsText(val numbers: String, val label: String, val extra: String = "", val detail: String = "")

/**
 * Decides which numbers the text shows: Android Auto's own on the phone ("app") while
 * the dash is not connected, and the stream to the dash's (labelled [StatsWords.panel]) while it is. [publish] gets the
 * text whenever it changes.
 */
class StatsFeed(private val words: StatsWords, private val publish: (StatsText) -> Unit) {
    private var app: Reading? = null
    private var panel: Reading? = null
    private var panelOn = false
    private var last: StatsText? = null
    private var mode = ""
    private var keyframe = ""
    private var speed: String? = null

    /** Android Auto's rate and bitrate over the last second, null when it is not measured. */
    @Synchronized fun app(reading: Reading?) {
        app = reading
        show()
    }

    /** The stream to the dash's over the last second. */
    @Synchronized fun panel(reading: Reading?) {
        panel = reading
        show()
    }

    /** The stream to the dash's bitrate mode (null: the codec's own) and keyframe interval ([fixed]: the rider chose it), written as [StatsOverlay.mode] and [StatsOverlay.keyframe] say. */
    @Synchronized fun stream(mode: BitrateMode?, keyframeSec: Int, fixed: Boolean) {
        this.mode = StatsOverlay.mode(mode, words)
        this.keyframe = StatsOverlay.keyframe(keyframeSec, fixed, words)
        show()
    }

    /** The route's speed in km/h when REau has it (null when it has none): written in both modes. */
    @Synchronized fun speed(kmh: Double?) {
        speed = StatsOverlay.speed(kmh)
        show()
    }

    /** The stream to the dash begins: its numbers, not the app's, from now on (dashes until the first second is measured). */
    @Synchronized fun panelStarted() {
        panelOn = true
        panel = null
        show()
    }

    @Synchronized fun panelStopped() {
        panelOn = false
        panel = null
        mode = ""
        keyframe = ""
        show()
    }

    private fun show() {
        val reading = if (panelOn) panel else app
        val detail = listOfNotNull(StatsOverlay.frameSize(reading, words), speed).joinToString("  ")
        val text = if (panelOn) {
            StatsText(StatsOverlay.numbers(panel), words.panel, listOf(mode, keyframe).filter { it.isNotEmpty() }.joinToString("  "), detail)
        } else {
            StatsText(StatsOverlay.numbers(app), StatsOverlay.APP, "", detail)
        }
        if (text == last) return
        last = text
        publish(text)
    }
}
