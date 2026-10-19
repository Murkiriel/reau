// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AaInput.kt.
// Changes: package; the multi-touch part left out (one finger, from the phone's screen); the transport is a send
// function and the clock is given, so the JVM tests read back what goes out; the keycodes live in
// ServiceDiscoveryResponse, which announces them; RideDiagnostics through the log function.
// OpenCfMoto ported it from headunit-revived (AGPLv3).
package com.reau.app.aa

import com.reau.app.aa.proto.Input

/**
 * Sends keys and the rotary wheel to Android Auto over the INPUT channel: the dash's
 * joystick drives Android Auto's focus, so the rider can leave the split screen, open the app list and pick Waze
 * without touching the phone. Android Auto acts on keys announced in service discovery
 * ([ServiceDiscoveryResponse.SUPPORTED_KEYCODES]); announcing the scroll wheel is what makes it draw a focus.
 */
class AaInput(
    private val send: (AapMessage) -> Unit,
    private val clockUs: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() / 1000 },
    private val log: (String) -> Unit,
) {
    /** One key press (down, then up). */
    fun sendKey(keycode: Int) {
        runCatching {
            sendKeyReport(keycode, down = true)
            sendKeyReport(keycode, down = false)
        }.onSuccess { log("key $keycode sent to Android Auto") }.onFailure { log("key $keycode failed: $it") }
    }

    /** One click of the rotary knob: -1 back (previous item), +1 forward (next item). */
    fun sendScroll(delta: Int) {
        runCatching {
            val rel = Input.RelativeEvent.newBuilder()
                .addData(Input.RelativeEvent_Rel.newBuilder().setKeycode(ServiceDiscoveryResponse.KEY_SCROLL_WHEEL).setDelta(delta).build())
                .build()
            val report = Input.InputReport.newBuilder().setTimestamp(clockUs()).setRelativeEvent(rel).build()
            send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        }.onSuccess { log("wheel $delta sent to Android Auto") }.onFailure { log("wheel $delta failed: $it") }
    }

    /**
     * One finger on Android Auto's announced touch screen (only with the phone touch switched
     * on), at ([x], [y]) in the video's pixels. Always pointer 0: the phone's screen sends one finger.
     */
    fun sendTouch(action: PhoneTouch.Action, x: Int, y: Int) {
        runCatching {
            val pointer = Input.TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(0).build()
            val code = when (action) {
                PhoneTouch.Action.DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                PhoneTouch.Action.MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                PhoneTouch.Action.UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                // A touch taken back, as Android's own (MotionEvent.ACTION_CANCEL): never a click nor a drag
                PhoneTouch.Action.CANCEL -> Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL
            }
            val touch = Input.TouchEvent.newBuilder().addPointerData(pointer).setActionIndex(0).setAction(code).build()
            val report = Input.InputReport.newBuilder().setTimestamp(clockUs()).setTouchEvent(touch).build()
            send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        }.onSuccess { if (action != PhoneTouch.Action.MOVE) log("touch ${action.name.lowercase()} sent to Android Auto") }
            .onFailure { log("touch failed: $it") }
    }

    private fun sendKeyReport(keycode: Int, down: Boolean) {
        val keyEvent = Input.KeyEvent.newBuilder()
            .addKeys(Input.Key.newBuilder().setKeycode(keycode).setDown(down).setMetastate(0).setLongpress(false).build())
            .build()
        val report = Input.InputReport.newBuilder().setTimestamp(clockUs()).setKeyEvent(keyEvent).build()
        send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
    }
}
