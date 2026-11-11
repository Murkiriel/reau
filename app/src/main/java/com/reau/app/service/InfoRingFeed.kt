package com.reau.app.service

import android.content.Context
import com.reau.app.R
import com.reau.app.dash.DashReadings
import com.reau.app.util.MonotonicClock
import com.reau.app.weather.InfoRing
import com.reau.app.weather.InfoRingRotation
import com.reau.app.weather.PhoneReadings
import com.reau.app.weather.RingData
import com.reau.app.weather.RingFrame
import com.reau.app.weather.RingOptions
import com.reau.app.weather.RingWords
import com.reau.app.weather.WeatherKeeper
import com.reau.app.weather.WeatherUnits

/** What the dash's info ring says now, from what the phone keeps: the weather ([WeatherKeeper]), the place for the sun, the dash's units. */
object InfoRingFeed {
    /**
     * Whether the ring is on the video: the rider's switch is on and the dash's own maneuver ring is on the glass
     * ([com.reau.app.dash.DashSession.ringVisible]: its switch, the joystick, the screen or a call). The two rings show and hide together.
     */
    fun shown(switchOn: Boolean, dashRingVisible: Boolean): Boolean = switchOn && dashRingVisible

    /**
     * The ride log's line when making the ring's picture failed with [error], [failuresBefore] failures into this projection: the first one
     * is told (the ring just vanished, with not a word, before), the others are not (the loop tries again every second).
     */
    fun failureLine(error: Throwable, failuresBefore: Int): String? =
        if (failuresBefore == 0) "the info ring could not be made (${error.javaClass.simpleName}): it is off the video until it can; told once" else null

    /**
     * One look of the ring's loop, many times a second: with the ring off the video the frame is dropped, the count of looks starts over and
     * the GPS is given back ([stopGps]: the ring's listener is a process-wide one at 1 Hz, and it must not go on for a ring nobody sees);
     * with it on, a new picture is made every [ticksPerLook] looks, and a picture that failed is told once ([failureLine], to [warn]) and
     * leaves no ring until the next try. Between the looks the frame is the last one made.
     */
    class Loop(private val ticksPerLook: Int, private val stopGps: () -> Unit, private val warn: (String) -> Unit) {
        private var frame: RingFrame? = null
        private var tick = 0
        private var failures = 0

        /** The frame to show now, or null for none. [on]: the ring is on the video; [make] makes the picture. */
        fun step(on: Boolean, make: () -> RingFrame?): RingFrame? {
            if (!on) {
                frame = null
                tick = 0
                stopGps()
            } else if (tick++ % ticksPerLook == 0) {
                frame = runCatching(make).onFailure { e -> failureLine(e, failures++)?.let(warn) }.getOrNull()
            }
            return frame
        }
    }

    /** The ring's switch and the dash's ring are looked at this often: a press of the dash's down button moves the turns. */
    const val POLL_MS = 50L

    /** With the rider's switch off nothing can show the ring, so it is looked at twice a second, not twenty times. */
    const val IDLE_POLL_MS = 500L

    fun pollMs(switchOn: Boolean): Long = if (switchOn) POLL_MS else IDLE_POLL_MS

    /**
     * What was last handed to the compositor: [changed] is true only for a frame that differs, so the thread that draws is not woken
     * twenty times a second to be told what it already shows (the compositor compares too; this keeps the message from being sent).
     */
    class Posts {
        private var last: RingFrame? = null
        private var any = false

        fun changed(frame: RingFrame?): Boolean {
            if (any && frame == last) return false
            any = true
            last = frame
            return true
        }
    }

    /** The words of the line in the language the app shows, from its resources. */
    fun words(context: Context): RingWords = RingWords(
        inWord = context.getString(R.string.ring_in),
        minutes = context.getString(R.string.ring_minutes),
        until = context.getString(R.string.ring_until),
        raining = context.getString(R.string.ring_raining),
        noRain = context.getString(R.string.ring_no_rain),
        thunder = context.getString(R.string.ring_thunder),
        uv = context.getString(R.string.ring_uv),
        aqi = context.getString(R.string.ring_aqi),
        charging = context.getString(R.string.ring_charging),
        onBattery = context.getString(R.string.ring_on_battery),
        saver = context.getString(R.string.ring_saver),
        airplane = context.getString(R.string.ring_airplane),
        offline = context.getString(R.string.ring_offline),
        mobile = context.getString(R.string.ring_mobile),
        satellite = context.getString(R.string.ring_satellite),
        satellites = context.getString(R.string.ring_satellites),
        noGps = context.getString(R.string.ring_no_gps),
        skyClear = context.getString(R.string.ring_sky_clear),
        skyPartly = context.getString(R.string.ring_sky_partly),
        skyCloudy = context.getString(R.string.ring_sky_cloudy),
        skyFog = context.getString(R.string.ring_sky_fog),
        skyDrizzle = context.getString(R.string.ring_sky_drizzle),
        skyRain = context.getString(R.string.ring_sky_rain),
        skySnow = context.getString(R.string.ring_sky_snow),
        compass = context.resources.getStringArray(R.array.wind_points).toList(),
    )

    /** Whether the GPS card may be listened to: the rider ticked it and "Usar a localização" is on; with either off the GPS is off. */
    fun gpsWanted(options: RingOptions, useLocation: Boolean): Boolean = RingData.shown(options.data, RingData.GPS) && useLocation

    /** What the phone says of itself, only the parts the rider ticked (the others are not even read); the GPS listens only while it is ticked. */
    fun phone(context: Context, options: RingOptions, useLocation: Boolean): PhoneReadings {
        GpsWatch.want(context, gpsWanted(options, useLocation))
        return PhoneReadings(
            battery = PhoneReader.battery(context).takeIf { RingData.shown(options.data, RingData.BATTERY) },
            link = if (RingData.shown(options.data, RingData.LINK)) PhoneReader.link(context) else null,
            gps = GpsWatch.read().takeIf { gpsWanted(options, useLocation) },
        )
    }

    /** The frame now, or null for no ring (no weather, or one too old). [night] is the map's: the ring's palette follows it. */
    fun frame(context: Context, rotation: InfoRingRotation, useLocation: Boolean, night: Boolean, options: RingOptions): RingFrame? {
        val mono = MonotonicClock.nowMs()
        return InfoRing.frame(
            snapshot = WeatherKeeper.snapshot.value,
            rotation = rotation,
            wallNowMs = System.currentTimeMillis(),
            monoNowMs = mono,
            place = SunPlace.saved(context, useLocation),
            night = night,
            units = WeatherUnits.of(DashReadings.settings.value),
            words = words(context),
            atMs = mono,
            options = options,
            phone = phone(context, options, useLocation),
        )
    }
}
