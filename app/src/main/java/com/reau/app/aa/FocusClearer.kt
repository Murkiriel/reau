package com.reau.app.aa

/**
 * Clears the blue focus border Android Auto leaves on the screen after the joystick: Android Auto draws its focus
 * once keys or the wheel come, and only a touch takes it away. [idleMs] after the last joystick input sent to
 * Android Auto, one touch ([TAP]), cancelled at once, does, once per quiet spell. Only on the navigation's screen: in the
 * app menu it is the suspected cause of a wheel step focusing an app without the list scrolling to it (touch mode, then a new focus).
 */
class FocusClearer(private val idleMs: Long = IDLE_MS) {
    private var lastInputMs: Long? = null

    /** A joystick input went to Android Auto at [nowMs]: the quiet spell starts again. */
    @Synchronized
    fun inputSent(nowMs: Long) {
        lastInputMs = nowMs
    }

    /**
     * Whether to touch now: the last input is [idleMs] old or more, and was not touched for yet, and [onNavigation] (the screen
     * shown is the navigation's). On another screen the quiet spell is kept, not spent: the touch comes when the navigation is back.
     */
    @Synchronized
    fun tapDue(nowMs: Long, onNavigation: Boolean): Boolean {
        val last = lastInputMs ?: return false
        if (nowMs - last < idleMs || !onNavigation) return false
        lastInputMs = null
        return true
    }

    companion object {
        const val IDLE_MS = 60_000L

        // The middle of Android Auto's 800x480 picture.
        private const val TAP_X = 400
        private const val TAP_Y = 240

        /**
         * The touch: down in the middle of the picture, cancelled at once. Going down is what ends the focus (Android's
         * touch mode); a cancelled touch is never a click nor a drag, wherever it lands. So neither the density (the
         * user may change Android Auto's dpi) nor the side the side bar is on (the user may move it) matters. It was
         * a drag from the bar's right edge onto the map at first, which needed the bar on the right; Android Auto does
         * not say on which side it is (REau tells it the driver sits on the left).
         */
        val TAP: List<AaCommand> = listOf(
            AaCommand.Touch(PhoneTouch.Action.DOWN, TAP_X, TAP_Y),
            AaCommand.Touch(PhoneTouch.Action.CANCEL, TAP_X, TAP_Y),
        )

        /** Whether a joystick press' commands ([sent]) went to Android Auto: anything but the phone's call keys. */
        fun toAndroidAuto(sent: List<AaCommand>): Boolean = sent.any { !forThePhoneOrThePicture(it) }

        /** The phone's call keys, and the picture's zoom: none of them reaches Android Auto. */
        private fun forThePhoneOrThePicture(command: AaCommand): Boolean = when (command) {
            AaCommand.AnswerCall, AaCommand.EndCall, AaCommand.PanelZoomUndo, is AaCommand.CallVolume, is AaCommand.PanelZoom -> true
            else -> false
        }
    }
}
