package com.reau.app.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The rider's marks of one projection, numbered from one. [mark] gives the log line
 * (the log stamps the time), [markedAt] the time of the last one, for what the notification says afterwards (the service
 * puts it in the rider's language): there is no vibration, which would need a new permission.
 */
class RiderMarks(private val zone: TimeZone = TimeZone.getDefault()) {
    @Volatile var count = 0
        private set

    @Volatile var markedAt: String? = null
        private set

    @Synchronized
    fun mark(nowMs: Long): String {
        count++
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
        markedAt = time
        return "MARCA DO PILOTO $count"
    }

    /** A new projection: counting starts again. */
    @Synchronized
    fun reset() {
        count = 0
        markedAt = null
    }
}
