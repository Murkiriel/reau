package com.reau.app.dash.video

import android.media.MediaFormat

/**
 * The colour description the dash's encoder is asked for with the experimental switch
 * ([com.reau.app.settings.REauSettings.experimentColorMatrix]): BT.601, limited range, SDR transfer, through `KEY_COLOR_STANDARD`, `KEY_COLOR_RANGE` and
 * `KEY_COLOR_TRANSFER`. The picture is 526 x 300, a size every decoder reads as BT.601 when the stream says nothing, so that is what the encoder is told
 * to convert with and to write in the stream. Without the switch no colour key is set and the encoder converts as its own default says, as always.
 *
 * A codec that refuses the keys is asked again without them ([DashEncoder.prepare]); [acceptedLine] and [refusedLine] are what the ride log says of it.
 */
internal object ColorMatrix {
    const val STANDARD = MediaFormat.COLOR_STANDARD_BT601_NTSC
    const val RANGE = MediaFormat.COLOR_RANGE_LIMITED
    const val TRANSFER = MediaFormat.COLOR_TRANSFER_SDR_VIDEO

    /** The colour keys to set in the encoder's format: none unless [on] (the format is then as it always was). */
    fun keysFor(on: Boolean): Map<String, Int> = if (on) keys() else emptyMap()

    /**
     * How the encoder is started: with [on], first [withColor] (the format with the colour keys); if that fails, [refused] hears why and [asBefore] (the
     * format as it always was, with its own fallbacks) is tried. Not [on]: only [asBefore], and [withColor] is never run.
     */
    fun <T> attempt(on: Boolean, withColor: () -> Result<T>, asBefore: () -> Result<T>, refused: (Throwable) -> Unit): Result<T> {
        if (!on) return asBefore()
        val asked = withColor()
        asked.exceptionOrNull()?.let(refused)
        return if (asked.isSuccess) asked else asBefore()
    }

    /** What the format is asked for, by key. */
    fun keys(): Map<String, Int> = mapOf(
        MediaFormat.KEY_COLOR_STANDARD to STANDARD,
        MediaFormat.KEY_COLOR_RANGE to RANGE,
        MediaFormat.KEY_COLOR_TRANSFER to TRANSFER,
    )

    /** What is asked, in words: for the `encoder:` line of the log. */
    const val ASKED = "BT.601 limited-range SDR"

    /**
     * The log's line of what the codec said of the colour keys: [got] gives the number a key holds in the codec's formats (null: not echoed, the
     * c2 encoders echo little). A key echoed with another value than the one asked is said so.
     */
    fun acceptedLine(got: (String) -> Int?): String {
        val parts = keys().map { (key, asked) ->
            val value = got(key)
            val name = key.removePrefix("color-")
            when {
                value == null -> "$name not echoed"
                value == asked -> "$name=${word(key, value)}"
                else -> "$name=${word(key, value)} (asked ${word(key, asked)})"
            }
        }
        return "encoder colour: asked $ASKED; the codec says ${parts.joinToString(", ")}"
    }

    /** The log's line when the codec refused the format with the colour keys: the encoder is asked again as without the switch. */
    fun refusedLine(name: String?, error: String): String =
        "encoder ${name ?: "OS-default encoder"} refused the colour keys ($ASKED): $error; asked again without them"

    private fun word(key: String, value: Int): String = when (key) {
        MediaFormat.KEY_COLOR_STANDARD -> when (value) {
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601-NTSC"
            MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601-PAL"
            MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
            MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
            else -> "$value"
        }
        MediaFormat.KEY_COLOR_RANGE -> when (value) {
            MediaFormat.COLOR_RANGE_LIMITED -> "limited"
            MediaFormat.COLOR_RANGE_FULL -> "full"
            else -> "$value"
        }
        else -> when (value) {
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
            MediaFormat.COLOR_TRANSFER_LINEAR -> "linear"
            MediaFormat.COLOR_TRANSFER_ST2084 -> "ST2084"
            MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
            else -> "$value"
        }
    }
}
