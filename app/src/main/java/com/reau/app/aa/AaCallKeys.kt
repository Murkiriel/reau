package com.reau.app.aa

/**
 * The joystick's answer and end as a car's phone keys for Android Auto, call (5) and end call (6), which answer and
 * end on Android Auto's own call card the phone's calls and the voice apps' alike. The system's Telecom cannot answer
 * a voice app's call for an app (only the system may), and Android Auto mutes the notification whose buttons could
 * have, so the keys are the way left with no new permission. Without an Android Auto session, nothing goes to it.
 */
object AaCallKeys {
    fun toAndroidAuto(command: AaCommand, androidAuto: Boolean): List<AaCommand> = when {
        !androidAuto -> emptyList()
        command == AaCommand.AnswerCall -> listOf(AaCommand.Key(ServiceDiscoveryResponse.KEY_CALL))
        command == AaCommand.EndCall -> listOf(AaCommand.Key(ServiceDiscoveryResponse.KEY_ENDCALL))
        else -> emptyList()
    }
}
