// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/MsgType.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/protocol/MsgType.kt
package com.reau.app.aa

import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media

object MsgType {

    const val SIZE = 2

    fun isControl(type: Int): Boolean = type in 1..26

    fun name(type: Int, channel: Int): String = when (type) {
        Control.ControlMsgType.MESSAGE_VERSION_REQUEST_VALUE -> "Version Request"
        Control.ControlMsgType.MESSAGE_VERSION_RESPONSE_VALUE -> "Version Response"
        Control.ControlMsgType.MESSAGE_ENCAPSULATED_SSL_VALUE -> "SSL Handshake Data"
        Control.ControlMsgType.MESSAGE_AUTH_COMPLETE_VALUE -> "SSL Authentication Complete Notification"
        Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> "Service Discovery Request"
        Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE -> "Service Discovery Response"
        Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE -> "Channel Open Request"
        Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE -> "Channel Open Response"
        Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> "Channel Close Notification"
        Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> "Ping Request"
        Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE -> "Ping Response"
        Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> "Navigation Focus Request"
        Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE -> "Navigation Focus Notification"
        Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE -> "Byebye Request"
        Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE -> "Byebye Response"
        Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> "Voice Session Notification"
        Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> "Audio Focus Request"
        Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE -> "Audio Focus Notification"
        Control.ControlMsgType.MESSAGE_CAR_CONNECTED_DEVICES_REQUEST_VALUE -> "Car Connected Devices Request"
        Control.ControlMsgType.MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE_VALUE -> "Car Connected Devices Response"
        Control.ControlMsgType.MESSAGE_USER_SWITCH_REQUEST_VALUE -> "User Switch Request"
        Control.ControlMsgType.MESSAGE_BATTERY_STATUS_NOTIFICATION_VALUE -> "Battery Status Notification"
        Control.ControlMsgType.MESSAGE_CALL_AVAILABILITY_STATUS_VALUE -> "Call Availability Status"
        Control.ControlMsgType.MESSAGE_USER_SWITCH_RESPONSE_VALUE -> "User Switch Response"
        Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_UPDATE_VALUE -> "Service Discovery Update"

        Media.MsgType.MEDIA_MESSAGE_DATA_VALUE -> "Media Data"
        Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE -> "Codec Config"
        Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> "Media Setup Request"
        Media.MsgType.MEDIA_MESSAGE_START_VALUE -> byChannel(channel, "Sensor Start Request", "Input Event", "Media Start Request")
        Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> byChannel(channel, "Sensor Start Response", "Input Binding Request", "Media Stop Request")
        Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE -> byChannel(channel, "Sensor Event", "Input Binding Response", "Media Config Response")
        Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> "Codec/Media Data Ack"
        Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> "Mic Start/Stop Request"
        Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE -> "Mic Response"
        Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> "Video Focus Request"
        Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE -> "Video Focus Notification"
        Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE -> "Update UI Config Request"
        Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY_VALUE -> "Update UI Config Reply"
        Media.MsgType.MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION_VALUE -> "Audio Underflow Notification"

        Control.ControlMsgType.MESSAGE_UNEXPECTED_MESSAGE_VALUE -> "Unexpected Message"
        Control.ControlMsgType.MESSAGE_FRAMING_ERROR_VALUE -> "Framing Error Notification"
        else -> "Unknown ($type)"
    }

    /** The channel changes what a few message types mean: the sensor, input and media playback channels reuse them. */
    private fun byChannel(channel: Int, sensor: String, input: String, other: String): String = when (channel) {
        Channel.ID_SEN -> sensor
        Channel.ID_INP -> input
        Channel.ID_MPB -> "Media Playback Status"
        else -> other
    }
}
