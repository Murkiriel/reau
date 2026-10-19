// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/Channel.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/protocol/Channel.kt
package com.reau.app.aa

object Channel {

    const val ID_CTR = 0
    const val ID_SEN = 1
    const val ID_VID = 2
    const val ID_INP = 3
    const val ID_AUD = 6
    const val ID_AU1 = 4
    const val ID_AU2 = 5
    const val ID_MIC = 7
    const val ID_BTH = 8
    const val ID_MPB = 9
    const val ID_NAV = 10
    const val ID_NOTI = 11
    const val ID_PHONE = 12
    const val ID_WIFI = 13

    /** The assistant's voice sink (an experiment): a number of REau's own, no other service uses it. */
    const val ID_SPE = 14

    fun name(channel: Int): String = when (channel) {
        ID_CTR -> "CONTROL"
        ID_VID -> "VIDEO"
        ID_INP -> "INPUT"
        ID_SEN -> "SENSOR"
        ID_MIC -> "MIC"
        ID_AUD -> "AUDIO"
        ID_AU1 -> "AUDIO1"
        ID_AU2 -> "AUDIO2"
        ID_SPE -> "SPEECH"
        ID_BTH -> "BLUETOOTH"
        ID_MPB -> "MUSIC_PLAYBACK"
        ID_NAV -> "NAVIGATION_DIRECTIONS"
        ID_NOTI -> "NOTIFICATION"
        ID_PHONE -> "PHONE_STATUS"
        else -> "UNK"
    }

    fun isAudio(chan: Int): Boolean = chan == ID_AUD || chan == ID_AU1 || chan == ID_AU2 || chan == ID_SPE
}
