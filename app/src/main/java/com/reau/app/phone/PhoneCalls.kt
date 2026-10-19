package com.reau.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.reau.app.data.RideDiagnostics

/** The phone's call, as far as the dash's joystick needs it. */
enum class CallState { IDLE, RINGING, IN_CALL }

/**
 * Answering and ending calls by the dash's joystick (behind the switch "Atender ligação pelo joystick", off): the
 * system's own TelecomManager acts on whatever call is ringing or going on, with no caller name read. Needs
 * ANSWER_PHONE_CALLS (to answer and end) and READ_PHONE_STATE (to
 * know a call is ringing, so the joystick's sides walk Android Auto the rest of the time).
 */
class PhoneCalls(context: Context) {
    private val app = context.applicationContext
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)

    /** Both permissions granted. */
    fun permitted(): Boolean = PERMISSIONS.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    /** Ringing, on a call or neither; [CallState.IDLE] without the permissions. */
    @Suppress("MissingPermission", "DEPRECATION") // checked by permitted(); TelecomManager's isRinging is not public
    fun state(): CallState = if (!permitted()) {
        CallState.IDLE
    } else {
        runCatching {
            val callState = telephony?.callState
            stateOf(callState == TelephonyManager.CALL_STATE_RINGING, callState == TelephonyManager.CALL_STATE_OFFHOOK)
        }.getOrDefault(CallState.IDLE)
    }

    /**
     * Whether Telecom has a call, the self-managed ones of voice apps too (which [state]'s telephony does not
     * see); null without the permissions.
     */
    @Suppress("MissingPermission") // checked by permitted()
    fun telecomInCall(): Boolean? = if (!permitted()) null else runCatching { telecom?.isInCall }.getOrNull()

    /** Answers the ringing call; a no-op without the permission or with nothing ringing. */
    @Suppress("MissingPermission", "DEPRECATION") // checked by permitted(); still the system's only way for an app
    fun answer(): Boolean = act("answer") { telecom?.acceptRingingCall(); true }

    // acceptRingingCall says nothing back (and does nothing for a voice app's call), so its line says "asked".

    /** Rejects the ringing call or ends the one going on; a no-op without the permission or with no call. */
    @Suppress("MissingPermission", "DEPRECATION") // checked by permitted(); still the system's only way for an app
    fun end(): Boolean = act("end") { telecom?.endCall() == true }

    /**
     * The dash's volume codes while a call goes on (`adjustStreamVolume(STREAM_VOICE_CALL, ±1)` with the click): one [step] up or down on the call's own volume, which a Bluetooth headset follows. No permission. The line says the
     * level before and after, so a change made by anyone else in between shows in the next one.
     */
    fun adjustCallVolume(step: Int): Boolean = runCatching {
        val audio = app.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audio.adjustStreamVolume(
            AudioManager.STREAM_VOICE_CALL, if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND,
        )
        val after = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        RideDiagnostics.log("call", "volume of the call: $before -> $after of ${audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
        true
    }.getOrElse {
        RideDiagnostics.log("call", "volume of the call failed: ${it.javaClass.simpleName}")
        false
    }

    private fun act(what: String, block: () -> Boolean): Boolean {
        if (!permitted()) {
            RideDiagnostics.log("call", "$what: no permission to answer calls")
            return false
        }
        return runCatching(block)
            .onSuccess {
                val line = if (what == "answer") "answer asked of Telecom (it does not say whether it answered)" else "$what by the joystick: $it"
                RideDiagnostics.log("call", line)
            }
            .onFailure { RideDiagnostics.log("call", "$what failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)

        /** Ringing wins over a call already going on (a second call ringing is still a call to answer). */
        fun stateOf(ringing: Boolean, inCall: Boolean): CallState = when {
            ringing -> CallState.RINGING
            inCall -> CallState.IN_CALL
            else -> CallState.IDLE
        }

        /** What the joystick sees: no call at all with the switch off or without the permissions. */
        fun visibleState(switchOn: Boolean, permitted: Boolean, state: CallState): CallState =
            if (switchOn && permitted) state else CallState.IDLE
    }
}
