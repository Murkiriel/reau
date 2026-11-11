package com.reau.app.aa

import com.reau.app.video.AaMargins

/**
 * The frame Android Auto renders for REau: 800×480, the smallest it has, and always this one.
 */
object AaVideo {
    const val WIDTH = 800
    const val HEIGHT = 480
}

/**
 * What this head unit asks of Android Auto, decided before a session starts (it goes out in the service discovery
 * response and cannot change during a session).
 *
 * [density] is the dots per inch announced: the higher, the larger Android Auto draws buttons and text.
 * [margins] make Android Auto draw its screen in a smaller, centred area of the frame ([AaMargins]).
 * [navigation] announces the navigation status channel: Android Auto then sends turn-by-turn guidance.
 * [media] announces the music status channel: Android Auto then sends what is playing. [phone] announces the
 * phone status channel: Android Auto then sends the calls, with the caller. [protocolMinor] is the protocol
 * version asked in the handshake, 1.[protocolMinor] (1.7 behind a test switch, else 1.2). [versionRequests] is how many times that request
 * may be sent while no answer comes ([VERSION_REQUESTS]; more only in the transport's tests).
 */
data class AaConfig(
    val density: Int = 160,
    val margins: AaMargins = AaMargins.NONE,
    val navigation: Boolean = false,
    /** Announce a touch screen (the phone's "Tela do Android Auto" touches Android Auto). */
    val touch: Boolean = false,
    /** The song on the dash's own music card. */
    val media: Boolean = false,
    /** The caller on the dash's own call card. */
    val phone: Boolean = false,
    /** For the web server: announce the media and guidance sinks, so Android Auto sends those sounds to REau. */
    val audio: Boolean = false,
    val protocolMinor: Int = Messages.MINOR_DEFAULT,
    val versionRequests: Int = VERSION_REQUESTS,
    /** Experimental: protocol 6.0 is asked first ([ProtocolAsk]); [protocolMinor] is the version to go back to. */
    val protocolSix: Boolean = false,
) {
    companion object {
        /**
         * The version request is sent once, and the handshake waits its whole budget for the answer. REau talks to Android Auto's
         * head unit server over a socket: a socket delivers a request or drops the link, so a resend there reaches the server
         * only as a duplicate, and open-headunit measured (commit 68c2bbb5, on Android Auto 18.0) that a duplicate leaves the
         * server answering nobody until it is stopped and started again. It was three (2 s apart) until 30/10/2026; in 659
         * handshakes of the ride logs (Android Auto 17.8 and 17.9) a resend never rescued one, and three slow answers (6 to 9 s)
         * came to all three requests at once.
         */
        const val VERSION_REQUESTS = 1
    }
}
