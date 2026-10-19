package com.reau.app.aa

import java.util.concurrent.atomic.AtomicLong

/**
 * Android Auto's video as it reaches the phone, counted for ever since the process began: the pictures (access units)
 * and their bytes, before the decoder ([AaStats.unit] adds to it). The stats text of the dash's video
 * ([com.reau.app.video.StatsOverlay]) reads it once a second and takes the difference: that is the "app" rate and
 * bitrate, the same whether or not a dash is connected.
 */
object AaVideoMeter {
    val units = AtomicLong()
    val bytes = AtomicLong()
}
