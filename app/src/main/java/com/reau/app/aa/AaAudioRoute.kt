package com.reau.app.aa

/**
 * Where the phone's own sound goes. With the sinks announced, Android Auto registers an audio policy that loops back
 * every player of usage MEDIA or GAME on the phone (`dumpsys audio`: `match usage USAGE_MEDIA`, `USAGE_GAME`), and REau's own track,
 * played as media, was captured and sent back to REau, round and round. Asking not to be captured (`ALLOW_CAPTURE_BY_NONE`) was not
 * honoured by that mix (the track still went to its "Remote Submix Out"), so the phone's sound plays with a usage the mix does not match,
 * and REau checks where the system routed the track and does not play it on the phone if it went to the loop.
 */
object AaAudioRoute {
    /** `AudioDeviceInfo.TYPE_REMOTE_SUBMIX` (a hidden constant): the device Android Auto's capture is. */
    private const val TYPE_REMOTE_SUBMIX = 25

    /** The track is first checked after this many chunks written: the route is known by then, as a rule. */
    const val CHECK_AFTER = 8

    /** And checked again at these, in case the system had not routed it yet (the loop would show later). */
    private val CHECK_AGAIN = setOf(16, 32, 64)

    /** Android's `AudioAttributes.USAGE_UNKNOWN`: plain music volume, matched by none of Android Auto's mixes. */
    private const val USAGE_UNKNOWN = 0

    /** True when [routedDeviceType] is Android Auto's loop-back; a route not known yet (null) is not. */
    fun isTheLoop(routedDeviceType: Int?): Boolean = routedDeviceType == TYPE_REMOTE_SUBMIX

    /** Whether it is time to look at the route: at the [CHECK_AFTER]th chunk and a few times after it. */
    fun checkNow(written: Int): Boolean = written == CHECK_AFTER || written in CHECK_AGAIN

    /**
     * The usage the phone's sound of [stream] plays with: none of those Android Auto's captures take (MEDIA and GAME for the music; with
     * the speech sink announced, also ASSISTANT and NAVIGATION_GUIDANCE: the guide's voice played as navigation was
     * captured back). So every stream plays as UNKNOWN.
     */
    @Suppress("UNUSED_PARAMETER")
    fun usageOf(stream: AaAudioFeed.Stream): Int = USAGE_UNKNOWN
}
