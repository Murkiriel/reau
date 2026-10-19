// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt, commit 0abbe2a.
// Changes: package; the calls into OpenCfMoto's AaVideoBridge are cut; a microphone request is answered here, as one that
// did not open unless the session was given Gemini's microphone (AaMicrophone, behind a
// switch), which then says whether it opened; a stop on the microphone channel closes it; the service discovery
// response is built from an AaConfig handed down by the dispatcher; the dispatcher hands the navigation status channel
// to AapControlNavigation (from MOTO-HUB) when it is given a place for the guidance. An audio focus request
// also goes to the `system` log line.
// Adapted from headunit-revived (AGPLv3): aap/AapControl.kt
// Trimmed for video-only headless projection: no audio sink / system audio focus, no mic
// recorder, no Settings/Context UI. gainVideoFocus() is replaced by sending VideoFocusEvent
// directly (there is no projection Activity — the encoder surface is always ready).
package com.reau.app.aa

import com.reau.app.aa.proto.Common
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Input
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors

interface AapControl {
    fun execute(message: AapMessage): Int
}

internal class AapControlMedia(
    private val aapTransport: AapTransport,
    private val microphone: AaMicrophone? = null,
) : AapControl {

    /** The session Android Auto started on the microphone channel: echoed back in the answer to a request. */
    private var micSessionId = 0

    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val setupRequest = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                return mediaSinkSetupRequest(setupRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val startRequest = message.parse(Media.Start.newBuilder()).build()
                return mediaStartRequest(startRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> {
                if (message.channel == Channel.ID_MIC) microphone?.stop("Android Auto stopped the microphone channel")
                return mediaSinkStopRequest(message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                AaLog.i("RX: Video Focus Request - mode: %s, reason: %s", focusRequest.mode, focusRequest.reason)
                if (focusRequest.mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE) {
                    AaLog.i("Video Focus NATIVE received. User likely clicked Exit. Stopping transport.")
                    aapTransport.wasUserExit = true
                    aapTransport.stop()
                } else if (focusRequest.mode == Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED) {
                    AaLog.i("Video Focus PROJECTED received. Replying with VideoFocusEvent GAIN.")
                    aapTransport.send(VideoFocusEvent(gain = true, unsolicited = false))
                }
                return 0
            }
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> {
                // AA wants the mic (Assistant starting/stopping).
                val req = message.parse(Media.MicrophoneRequest.newBuilder()).build()
                AaLog.i("RX: Microphone request open=%b", req.open)
                return microphoneRequest(req.open, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY_VALUE -> {
                AaLog.i("RX: Update UI Config Reply received.")
                return 0
            }
            Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> return 0
            else -> AaLog.e("Unsupported Media message type: ${message.type}")
        }
        return 0
    }

    private fun mediaStartRequest(request: Media.Start, channel: Int): Int {
        AaLog.i("Media Start Request %s: session=%d, config_index=%d", Channel.name(channel), request.sessionId, request.configurationIndex)
        aapTransport.setSessionId(channel, request.sessionId)
        // The mic must echo this session id back in its MICROPHONE_RESPONSE.
        if (channel == Channel.ID_MIC) micSessionId = request.sessionId
        return 0
    }

    /**
     * The microphone is advertised (Android Auto wants one to connect). Without Gemini's microphone a request to
     * open is answered the way OpenCfMoto answers one that failed, so Android Auto is not left listening to a
     * stream that never comes; with it, success if the voice really opened. A request to close is answered with
     * success.
     */
    private fun microphoneRequest(open: Boolean, channel: Int): Int {
        val opened = microphone?.request(open) ?: !open
        val status = if (opened) Common.MessageStatus.STATUS_SUCCESS_VALUE else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
        aapTransport.send(
            AapMessage(
                channel, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
                Media.MicrophoneResponse.newBuilder().setStatus(status).setSessionId(micSessionId).build()
            )
        )
        return 0
    }

    private fun mediaSinkSetupRequest(request: Media.MediaSetupRequest, channel: Int): Int {
        AaLog.i("Media Sink Setup Request: %d on channel %s", request.type, Channel.name(channel))
        val maxUnacked = when {
            channel == Channel.ID_VID -> 12
            else -> 16
        }
        val configResponse = Media.Config.newBuilder().apply {
            status = Media.Config.ConfigStatus.HEADUNIT
            this.maxUnacked = maxUnacked
            addConfigurationIndices(0)
        }.build()
        AaLog.i("Config response: %s (maxUnacked=%d)", configResponse, maxUnacked)
        aapTransport.send(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, configResponse))

        if (channel == Channel.ID_VID) {
            // Headless: no projection Activity to broadcast to — grant video focus directly so
            // the phone starts streaming H.264 into our (always-ready) encoder surface.
            AaLog.i("Video channel set up → sending VideoFocus GAIN")
            aapTransport.send(VideoFocusEvent(gain = true, unsolicited = true))
        }
        return 0
    }

    private fun mediaSinkStopRequest(channel: Int): Int {
        AaLog.i("Media Sink Stop Request: " + Channel.name(channel))
        if (channel == Channel.ID_VID) {
            if (aapTransport.ignoreNextStopRequest) {
                AaLog.i("Video Sink Stopped -> Ignored (Forced Keyframe Request)")
                aapTransport.ignoreNextStopRequest = false
                return 0
            }
            AaLog.i("Video Sink Stopped -> Normal background/transition behavior")
        }
        return 0
    }
}

internal class AapControlTouch(private val aapTransport: AapTransport) : AapControl {
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Input.MsgType.BINDINGREQUEST_VALUE -> {
                message.parse(Input.KeyBindingRequest.newBuilder()).build() // parsed only to validate the payload
                return inputBinding(message.channel)
            }
            else -> AaLog.e("Unsupported Input message type: ${message.type}")
        }
        return 0
    }

    private fun inputBinding(channel: Int): Int {
        aapTransport.send(
            AapMessage(
                channel, Input.MsgType.BINDINGRESPONSE_VALUE,
                Input.BindingResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()
            )
        )
        return 0
    }
}

internal class AapControlSensor(private val aapTransport: AapTransport) : AapControl {
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorRequest.newBuilder()).build()
                return sensorStartRequest(request, message.channel)
            }
            else -> AaLog.e("Unsupported Sensor message type: ${message.type}")
        }
        return 0
    }

    private fun sensorStartRequest(request: Sensors.SensorRequest, channel: Int): Int {
        AaLog.i("Sensor Start Request sensor: %s, minUpdatePeriod: %d", request.type.name, request.minUpdatePeriod)
        aapTransport.send(
            AapMessage(
                channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE,
                Sensors.SensorResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()
            )
        )
        aapTransport.startSensor(request.type.number)
        // Once AA starts the NIGHT sensor, immediately report the current day/night so Maps picks the
        // right map theme from the first frame (and later toggles go through AapTransport.sendNightMode).
        if (request.type.number == SENSOR_NIGHT) {
            aapTransport.send(NightModeEvent(aapTransport.nightMode))
        }
        return 0
    }

    private companion object {
        const val SENSOR_NIGHT = 10
    }
}

internal class AapControlService(private val aapTransport: AapTransport, private val config: AaConfig) : AapControl {

    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> {
                val request = message.parse(Control.ServiceDiscoveryRequest.newBuilder()).build()
                return serviceDiscoveryRequest(request)
            }
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> {
                message.parse(Control.PingRequest.newBuilder()).build() // parsed only to validate the payload
                return pingRequest(message.channel)
            }
            // The answer to REau's own ping (AapTransport.pinger); arriving is all it has to do.
            Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE -> return 0
            Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> {
                val navigationFocusRequest = message.parse(Control.NavFocusRequestNotification.newBuilder()).build()
                return navigationFocusRequest(navigationFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE -> {
                val shutdownRequest = message.parse(Control.ByeByeRequest.newBuilder()).build()
                return byebyeRequest(shutdownRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE -> {
                AaLog.i("Byebye Response received")
                return -1
            }
            Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> {
                val voiceRequest = message.parse(Control.VoiceSessionNotification.newBuilder()).build()
                return voiceSessionNotification(voiceRequest)
            }
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> {
                val audioFocusRequest = message.parse(Control.AudioFocusRequestNotification.newBuilder()).build()
                return audioFocusRequest(audioFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> {
                AaLog.i("RX: Channel Close Notification on chan ${message.channel}")
                return 0
            }
            else -> AaLog.e("Unsupported Control message type: ${message.type}")
        }
        return 0
    }

    private fun serviceDiscoveryRequest(request: Control.ServiceDiscoveryRequest): Int {
        AaLog.i("Service Discovery Request: %s", request.phoneName)
        aapTransport.send(ServiceDiscoveryResponse(config))
        return 0
    }

    private fun pingRequest(channel: Int): Int {
        val response = Control.PingResponse.newBuilder().setTimestamp(System.nanoTime()).build()
        aapTransport.send(AapMessage(channel, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, response))
        return 0
    }

    private fun navigationFocusRequest(request: Control.NavFocusRequestNotification, channel: Int): Int {
        AaLog.i("Navigation Focus Request: %s", request.focusType)
        val response = Control.NavFocusNotification.newBuilder().setFocusType(Control.NavFocusType.NAV_FOCUS_2).build()
        aapTransport.send(AapMessage(channel, Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE, response))
        return 0
    }

    private fun byebyeRequest(request: Control.ByeByeRequest, channel: Int): Int {
        AaLog.i("!!! RECEIVED BYEBYE REQUEST FROM PHONE !!! Reason: ${request.reason}")
        aapTransport.send(AapMessage(channel, Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, Control.ByeByeResponse.newBuilder().build()))
        Utils.ms_sleep(500)
        AaLog.i("Calling aapTransport.quit(clean=true)")
        aapTransport.quit(clean = true)
        return -1
    }

    private fun voiceSessionNotification(request: Control.VoiceSessionNotification): Int {
        if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START)
            AaLog.i("Voice Session Notification: START")
        else if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_STOP)
            AaLog.i("Voice Session Notification: STOP")
        return 0
    }

    private fun audioFocusRequest(notification: Control.AudioFocusRequestNotification, channel: Int): Int {
        AaLog.i("Audio Focus Request: ${notification.request}")
        com.reau.app.service.SystemEvents.log("aa audio focus: ${notification.request}") // for the `system` log line
        // Always grant — the phone must believe the head unit has audio focus.
        val mappedState = focusResponse[notification.request]
        if (mappedState != null) {
            val response = Control.AudioFocusNotification.newBuilder().setFocusState(mappedState).build()
            aapTransport.send(AapMessage(channel, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, response))
        }
        return 0
    }

    companion object {
        private val focusResponse = mapOf(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE to Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT to
                Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK to
                Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
        )
    }
}

internal class AapControlGateway(
    private val aapTransport: AapTransport,
    private val serviceControl: AapControl,
    private val mediaControl: AapControl,
    private val touchControl: AapControl,
    private val sensorControl: AapControl,
    private val navigationControl: AapControl? = null,
) : AapControl {

    constructor(
        aapTransport: AapTransport,
        config: AaConfig,
        onNavigation: ((NavGuidance) -> Unit)? = null,
        microphone: AaMicrophone? = null,
    ) : this(
        aapTransport,
        AapControlService(aapTransport, config),
        AapControlMedia(aapTransport, microphone),
        AapControlTouch(aapTransport),
        AapControlSensor(aapTransport),
        onNavigation?.let { AapControlNavigation(it) },
    )

    override fun execute(message: AapMessage): Int {
        if (message.type == 7) {
            message.parse(Control.ChannelOpenRequest.newBuilder()).build() // parsed only to validate the payload
            return channelOpenRequest(message.channel)
        }
        when (message.channel) {
            Channel.ID_CTR -> return serviceControl.execute(message)
            Channel.ID_INP -> return touchControl.execute(message)
            Channel.ID_SEN -> return sensorControl.execute(message)
            Channel.ID_NAV -> return navigationControl?.execute(message) ?: 0
            Channel.ID_VID, Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_SPE, Channel.ID_MIC ->
                return mediaControl.execute(message)
        }
        return 0
    }

    private fun channelOpenRequest(channel: Int): Int {
        AaLog.i("Channel Open Request on chan %d %s", channel, Channel.name(channel))
        aapTransport.send(
            AapMessage(
                channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE,
                Control.ChannelOpenResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()
            )
        )
        if (channel == Channel.ID_SEN) {
            aapTransport.send(DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED))
        }
        return 0
    }
}
