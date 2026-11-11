package com.reau.app.service

import com.reau.app.data.SolarClock
import com.reau.app.settings.MapTheme

/**
 * Day or night for Android Auto's night sensor, which switches Waze's map between its day and night themes.
 *
 * [MapTheme.AUTO] goes by the sun where the phone is ([SolarClock], with its dusk
 * dead-band: between 0° and 6° below the horizon the answer stays what it was). With no place known, the time zone
 * stands in for one ([standIn]): its offset gives a longitude, 15° an hour, on the equator, where day and night are
 * twelve hours each. Day and night then come at about 6:00 and 18:00 by the zone's clock: off by up to an hour or so
 * at mid latitudes in summer, never by half a day.
 */
object DayNight {
    data class Place(val lat: Double, val lng: Double)

    private const val DEGREES_PER_HOUR = 15.0
    private const val MS_PER_HOUR = 3_600_000.0

    @Suppress("LongParameterList") // the rider's theme and what each theme goes by: the sun's facts and the dash's light
    fun isNight(
        theme: MapTheme,
        epochMs: Long,
        place: Place?,
        utcOffsetMs: Long,
        wasNight: Boolean,
    ): Boolean = when {
        theme == MapTheme.DAY -> false
        theme == MapTheme.NIGHT -> true
        else -> {
            val where = place ?: standIn(utcOffsetMs)
            !SolarClock.resolveIsLight(SolarClock.elevationDeg(where.lat, where.lng, epochMs), previouslyLight = !wasNight)
        }
    }

    /** The place a time zone stands for when the phone's own is not known. */
    fun standIn(utcOffsetMs: Long): Place = Place(0.0, utcOffsetMs / MS_PER_HOUR * DEGREES_PER_HOUR)

    /**
     * The projection's memory of its last answer. The rule of the sun has a band between day and night in which the answer is the one before
     * (dusk turns to night at one edge, dawn to day at the other). The projection asked with a new [Clock] at every look, so "the one
     * before" was always day: dusk came out right, and dawn came about half an hour early. With [remember] (the experimental switch,
     * [com.reau.app.settings.REauSettings.experimentNightMemory]) the last answer is the one before; without it every look starts as day,
     * as it always did.
     */
    class Memory {
        private var wasNight = false

        @Synchronized
        fun isNight(remember: Boolean, theme: MapTheme, epochMs: Long, place: Place?, utcOffsetMs: Long): Boolean =
            isNight(theme, epochMs, place, utcOffsetMs, wasNight = remember && wasNight).also { wasNight = it }

        /** [isNight] at this moment of the phone's clock, in the phone's time zone. */
        fun isNightNow(remember: Boolean, theme: MapTheme, place: Place?, nowMs: Long = System.currentTimeMillis()): Boolean =
            isNight(remember, theme, nowMs, place, java.util.TimeZone.getDefault().getOffset(nowMs).toLong())
    }

    /** Asks [isNight] again each time, remembering its last answer for the dusk rule. */
    class Clock(
        private val theme: MapTheme,
        private val place: () -> Place?,
        private val offsetMs: () -> Long,
        private val nowMs: () -> Long = System::currentTimeMillis,
    ) {
        private var wasNight = false

        fun isNight(): Boolean = isNight(theme, nowMs(), place(), offsetMs(), wasNight).also { wasNight = it }
    }
}
