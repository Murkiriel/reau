package com.reau.app.phone

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Where the sound of a call goes (to test calls with a helmet communicator): the audio mode, the kind of device the
 * call is routed to (the phone's ear, the speaker, a Bluetooth headset), whether the headset link is on, and the call's volume. Only kinds and
 * numbers: never a device's name or address.
 */
object CallAudioRoute {
    private const val MODE_NORMAL = 0
    private const val MODE_RINGTONE = 1
    private const val MODE_IN_CALL = 2
    private const val MODE_IN_COMMUNICATION = 3
    private const val MODE_CALL_SCREENING = 4

    fun modeName(mode: Int): String = when (mode) {
        MODE_NORMAL -> "NORMAL"
        MODE_RINGTONE -> "RINGTONE"
        MODE_IN_CALL -> "IN_CALL"
        MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> "mode $mode"
    }

    /** AudioDeviceInfo's TYPE_* numbers that a call can meet on a bike. */
    private val DEVICES = mapOf(
        1 to "BUILTIN_EARPIECE", 2 to "BUILTIN_SPEAKER", 3 to "WIRED_HEADSET", 4 to "WIRED_HEADPHONES",
        7 to "BLUETOOTH_SCO", 8 to "BLUETOOTH_A2DP", 22 to "USB_HEADSET", 23 to "HEARING_AID", 26 to "BLE_HEADSET", 27 to "BLE_SPEAKER",
    )

    fun deviceName(type: Int?): String = if (type == null) "none" else DEVICES[type] ?: "type $type"

    fun line(mode: Int, deviceType: Int?, scoOn: Boolean, volume: Int, max: Int): String =
        "audio route: mode=${modeName(mode)} device=${deviceName(deviceType)} sco=$scoOn volume=$volume/$max"

    /** The line for the phone now; a phone that will not say is said so, and the log goes on. */
    @Suppress("DEPRECATION") // isBluetoothScoOn: still the only plain answer below Android 14's headset APIs
    fun describe(context: Context): String = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.communicationDevice?.type else null
        line(
            audio.mode, device, audio.isBluetoothScoOn,
            audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL), audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
        )
    }.getOrElse { "audio route: not readable (${it.javaClass.simpleName})" }
}
