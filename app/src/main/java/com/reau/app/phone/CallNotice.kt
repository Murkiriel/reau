package com.reau.app.phone

/**
 * A voice app's call notification as REau's accessibility service sees it, said in the ride log so a WhatsApp call on
 * the phone shows how it rings before the joystick answers it. Telecom will not answer or end a self-managed call for
 * an app (only the system may), so the way left is the notification's own buttons. Never the caller's name or number:
 * the notification's title and text are not read.
 *
 * [template] is the notification's style (`android.template`), [callType] the call style's `android.callType`,
 * [answer], [decline] and [hangUp] whether its intents are there, [actionTitles] the buttons' words, [telecomInCall]
 * what `TelecomManager.isInCall` said (it counts self-managed calls), null without READ_PHONE_STATE.
 */
data class CallNotice(
    val pkg: String,
    val template: String?,
    val callType: Int?,
    val answer: Boolean,
    val decline: Boolean,
    val hangUp: Boolean,
    val actionTitles: List<String>,
    val telecomInCall: Boolean?,
) {
    /** Notification.CALL_TYPE_*: 1 incoming, 2 ongoing, 3 screening. */
    val kind: String get() = when (callType) {
        CALL_TYPE_INCOMING -> "incoming"
        CALL_TYPE_ONGOING -> "ongoing"
        CALL_TYPE_SCREENING -> "screening"
        null -> "no type"
        else -> "type $callType"
    }

    fun line(): String {
        val style = if (template == CALL_STYLE) "call style, $kind" else "not call style"
        val actions = actionTitles.ifEmpty { listOf("none") }.joinToString(" | ")
        val telecom = when (telecomInCall) {
            true -> "yes"
            false -> "no"
            null -> "unknown (no permission)"
        }
        return "call notification from $pkg: $style; answer=${yes(answer)} decline=${yes(decline)} hang up=${yes(hangUp)}; " +
            "actions: $actions; Telecom in a call: $telecom"
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    companion object {
        const val CALL_STYLE = "android.app.Notification\$CallStyle"

        /** The notification's extras (Notification.EXTRA_*, API 31; read by name so older Androids just miss them). */
        const val EXTRA_TEMPLATE = "android.template"
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CATEGORY_CALL = "call" // Notification.CATEGORY_CALL
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3

        /**
         * The voice apps the accessibility service listens to (with Android Auto; `res/xml/aa_server_accessibility.xml`,
         * kept the same by CallNoticeTest): WhatsApp, WhatsApp Business, Telegram and Signal.
         */
        val VOICE_APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms")

        /** A voice app's notification of the call category. */
        fun isCall(pkg: String, category: String?): Boolean = pkg in VOICE_APPS && category == CATEGORY_CALL
    }
}
