// Written for REau after OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AaMicrophone.kt,
// commit 0abbe2a. Kept: the VOICE_RECOGNITION input at 16 kHz, 16-bit mono, a buffer of at least four chunks, and the
// Bluetooth headset's microphone preferred (setCommunicationDevice on Android 12+, Bluetooth SCO before), let go when
// it closes. Changes: behind VoiceSource; the recorder is also pointed at the headset's input (setPreferredDevice);
// a recorder that does not start is refused at once instead of reading silence; no change to the service's type here
// (the projection asks for the microphone type when it starts, ForegroundTypes).
package com.reau.app.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.reau.app.aa.AaMicrophone
import com.reau.app.aa.VoiceSource

/**
 * The rider's voice for Gemini: the Bluetooth headset's microphone (the helmet's intercom) when one is connected, else
 * the phone's own.
 */
class PhoneVoice(private val context: Context, private val log: (String) -> Unit) : VoiceSource {
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var recorder: AudioRecord? = null
    private var routed = false

    fun permitted(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // The permission is checked first: without it, open() refuses and Android Auto is told the microphone failed.
    @Synchronized
    override fun open(): Boolean {
        if (!permitted()) {
            log("microphone: no permission to record, Gemini cannot hear")
            return false
        }
        val headset = preferHeadset()
        val started = newRecorder()?.let { start(it, headset) }
        if (started == null) {
            releaseHeadset()
        } else {
            recorder = started
            log("microphone: listening on ${headset?.let { "the Bluetooth headset (${it.productName})" } ?: "the phone's own microphone"}")
        }
        return started != null
    }

    /** A recorder ready to start, or null (released) if Android would not make one. */
    @SuppressLint("MissingPermission") // open() checked it
    private fun newRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(AaMicrophone.SAMPLE_RATE, CHANNEL, ENCODING)
        val size = maxOf(min, AaMicrophone.CHUNK_SAMPLES * 2 * MIN_CHUNKS)
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AaMicrophone.SAMPLE_RATE, CHANNEL, ENCODING, size) }
            .onFailure { log("microphone: the recorder could not be made: $it") }
            .getOrNull()
        if (r != null && r.state != AudioRecord.STATE_INITIALIZED) r.release()
        return r?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    /** [r] pointed at the headset's input and recording, or null (released) if it would not start. */
    private fun start(r: AudioRecord, headset: AudioDeviceInfo?): AudioRecord? {
        headset?.let { device -> inputOf(device)?.let { r.preferredDevice = it } }
        runCatching { r.startRecording() }
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return r
        log("microphone: the recorder did not start (the microphone busy?)")
        r.release()
        return null
    }

    override fun read(into: ShortArray): Int = recorder?.read(into, 0, into.size) ?: -1

    @Synchronized
    override fun close() {
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        releaseHeadset()
    }

    /** The headset for the voice, if one is connected; null for the phone's own microphone. */
    private fun preferHeadset(): AudioDeviceInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val headset = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (headset != null) routed = audio.setCommunicationDevice(headset)
            headset?.takeIf { routed }
        } else {
            @Suppress("DEPRECATION")
            if (audio.isBluetoothScoAvailableOffCall) {
                audio.startBluetoothSco()
                routed = true
            }
            null
        }
    }.onFailure { log("microphone: the headset could not be chosen ($it), the phone's own microphone goes") }.getOrNull()

    /** The input side of [device] (the headset's microphone), to point the recorder at. */
    private fun inputOf(device: AudioDeviceInfo): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == device.type && it.address == device.address }

    private fun releaseHeadset() {
        if (!routed) return
        routed = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            }
        }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MIN_CHUNKS = 4
    }
}
