// The dash's info ring: what it says and when. Pure logic over the forecast REau already keeps ([WeatherKeeper]); the drawing is
// [com.reau.app.video.InfoRingInk], the place on the frame [com.reau.app.video.InfoRingSpot].
package com.reau.app.weather

import com.reau.app.service.DayNight
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** What the ring's big picture is. */
enum class RingIcon {
    CLEAR, CLEAR_NIGHT, PARTLY_CLOUDY, PARTLY_CLOUDY_NIGHT, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, THUNDER,
    WIND, UV, AIR, SUNSET, SUNRISE, THERMOMETER, NO_DATA,

    /** The cards of the phone ([RingDetail]): their pictures are drawn from numbers, not from layers. */
    BATTERY, LINK, GPS,
}

/** The colour of the ring's state band: what the page warns of (the disk itself goes red for [DANGER]). */
enum class RingTone {
    NONE, RAIN, ATTENTION, ALERT, DANGER,
    UV_HIGH, UV_VERY_HIGH, UV_EXTREME,
    AIR_SENSITIVE, AIR_UNHEALTHY, AIR_VERY_UNHEALTHY, AIR_HAZARDOUS,
}

/** The pages the ring has, one of each at most. */
enum class RingKind { WIND_DANGER, THUNDER, WIND_ALERT, RAIN_SOON, RAIN_NOW, WIND_ATTENTION, SUN, UV, AIR, SKY, TEMPS, BATTERY, LINK, GPS }

/** What the rider can choose to have on the ring: each is a bit of the setting, and a page belongs to one of them. */
enum class RingData(val bit: Int) {
    RAIN(1), WIND(2), STORM(4), UV(8), AIR(16), SUN(32), SKY(64), TEMPS(128), BATTERY(256), LINK(512), GPS(1024),
    ;

    companion object {
        /** Every kind: all the bits there are (what a setting is held to, and what a rider who ticks them all gets). */
        const val ALL = 2047

        /** What a new setting starts with (12/11/2026): RAIN (1), WIND (2, the gusts) and STORM (4, the thunder) only; the other cards stay in the code, off until ticked. */
        const val DEFAULT = 1 or 2 or 4

        /** What "every kind" was before the phone's cards: a setting saved with it means every kind still. */
        const val BEFORE_PHONE_CARDS = 255

        /** The kind a page belongs to. */
        fun of(kind: RingKind): RingData = when (kind) {
            RingKind.RAIN_SOON, RingKind.RAIN_NOW -> RAIN
            RingKind.WIND_DANGER, RingKind.WIND_ALERT, RingKind.WIND_ATTENTION -> WIND
            RingKind.THUNDER -> STORM
            RingKind.UV -> UV
            RingKind.AIR -> AIR
            RingKind.SUN -> SUN
            RingKind.SKY -> SKY
            RingKind.TEMPS -> TEMPS
            RingKind.BATTERY -> BATTERY
            RingKind.LINK -> LINK
            RingKind.GPS -> GPS
        }

        fun shown(mask: Int, data: RingData): Boolean = mask and data.bit != 0
    }
}

/**
 * The rider's choices for the ring: which [data] it may say (a mask of [RingData] bits), whether it is a [carousel] that turns from
 * one page to the next every [seconds], or stays on the most urgent one.
 */
data class RingOptions(val data: Int = RingData.ALL, val carousel: Boolean = true, val seconds: Int = DEFAULT_SECONDS) {
    val periodMs: Long get() = seconds.coerceIn(SECONDS) * MS_PER_SECOND

    companion object {
        /** Each change costs bits of the video and the ring blurs for about a second after it: not faster than every five seconds. */
        val SECONDS = 5..30
        const val DEFAULT_SECONDS = 7
        private const val MS_PER_SECOND = 1_000L
    }
}

/** One piece of the ring's one line of words: a number is [big] (20 px), a word or a unit is not (16 px). */
data class RingText(val text: String, val big: Boolean)

/**
 * One page of the ring. [level] is its urgency, 1 (danger) to 4 (normal): level 1 pages alternate on their own and push everything
 * else out; the others take turns.
 */
data class RingPage(
    val kind: RingKind,
    val level: Int,
    val icon: RingIcon,
    val tone: RingTone,
    val line: List<RingText>,
    /** The numbers a card of the phone is drawn from (the battery, the link, the GPS); null for the weather's pages. */
    val detail: RingDetail? = null,
)

/** The numbers a card of the phone says, besides its words. */
sealed interface RingDetail

/** The phone's battery: [percent] 0 to 100, whether it is [charging] (plugged in) and whether the system's battery [saver] is on. */
data class BatteryDetail(val percent: Int, val charging: Boolean, val saver: Boolean) : RingDetail

/** How the phone reaches the world: what it uses to carry data. */
enum class LinkKind { AIRPLANE, OFFLINE, WIFI, STARLINK, SATELLITE, CELLULAR }

/** The phone's link: its [kind], the signal [bars] (0 to 4; 0 when none or unknown) and, on the mobile network, its [generation] ("5G"). */
data class LinkDetail(val kind: LinkKind, val bars: Int, val generation: String?) : RingDetail

/** The phone's GPS: the signal [bars] (0, no fix, to 4), the satellites in the fix, the heading in degrees when it is moving, the error in metres. */
data class GpsDetail(val bars: Int, val satellites: Int, val bearingDeg: Int?, val accuracyM: Int?) : RingDetail

/** How the phone reaches the world, as read: the [operator]'s name on the mobile network, shown short. */
data class LinkReading(val kind: LinkKind, val bars: Int = 0, val generation: String? = null, val operator: String? = null)

/** What the GPS says: satellites in the fix and in view, the error, the heading and the speed of the last fix; null where it does not say. */
data class GpsReading(
    val usedSatellites: Int,
    val visibleSatellites: Int,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMps: Float?,
)

/** What the phone itself says, read by the feed: any part is null while it is not known. */
data class PhoneReadings(val battery: BatteryDetail? = null, val link: LinkReading? = null, val gps: GpsReading? = null)

/** What the ring draws now: a page, whether the forecast behind it is old, and whether the map is its night one. */
data class RingFrame(val page: RingPage, val stale: Boolean, val night: Boolean)

/** The words of the line, in the rider's language (the phone's resources give them). */
data class RingWords(
    val inWord: String,
    val minutes: String,
    val until: String,
    val raining: String,
    val noRain: String,
    val thunder: String,
    /** The short names of the UV index and of the air quality index, as the rider's language writes them. */
    val uv: String,
    val aqi: String,
    val approx: String = "~",
    val charging: String = "charging",
    val onBattery: String = "battery",
    val saver: String = "saver",
    val airplane: String = "airplane",
    val offline: String = "no network",
    val mobile: String = "mobile",
    val satellite: String = "satellite",
    val satellites: String = "sat",
    val noGps: String = "no GPS",
    /** The sky in a word, for the sky card when it is not dry: clear, partly cloudy, cloudy, fog, drizzle, rain, snow (a storm uses [thunder]). */
    val skyClear: String = "clear",
    val skyPartly: String = "clouds",
    val skyCloudy: String = "cloudy",
    val skyFog: String = "fog",
    val skyDrizzle: String = "drizzle",
    val skyRain: String = "rain",
    val skySnow: String = "snow",
    /** The sixteen points of the compass, north first, clockwise, as the rider's language writes them. */
    val compass: List<String> = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"),
)

@Suppress("TooManyFunctions") // flat set of pure rules, one per page, each tested alone
object InfoRing {
    /**
     * The gust (km/h, what a forecast says at 10 m: where a bridge or a gap in the hills is exposed, the real one is higher) at
     * which the ring warns. No standard sets these; they follow what riders say: many feel a crosswind from about 48 km/h, a
     * bike "becomes a kite" near 64 and most put their limit between 72 and 80 km/h, gusts worse than steady wind.
     */
    const val GUST_ATTENTION_KMH = 40.0
    const val GUST_ALERT_KMH = 55.0
    const val GUST_DANGER_KMH = 70.0

    /** A quarter of an hour with at least this much rain is wet (0.4 mm/h): drizzle counts, a damp road is what riders fear first. */
    const val WET_MM_PER_QUARTER = 0.1

    /** Heavy rain: more than 7.6 mm/h, the meteorologists' (AMS) line between moderate and heavy. */
    const val HEAVY_MM_PER_HOUR = 7.6

    /** Rain, or a storm, this close is level 1; up to [LOOK_AHEAD_MIN] it is level 2. */
    const val RAIN_DANGER_MIN = 30
    const val LOOK_AHEAD_MIN = 60

    /** Without real quarters the rain comes from the hourly forecast, spread over the hour: it must be at least this likely. */
    const val HOURLY_MIN_PROBABILITY = 30

    /** The battery is a warning from this low (and a stronger one from the next), while it is not charging. */
    const val BATTERY_LOW_PERCENT = 20
    const val BATTERY_CRITICAL_PERCENT = 10

    /** The GPS says its heading only while the phone moves at least this fast (m/s: 3.6 km/h); standing still the heading is noise. */
    const val MOVING_MPS = 1.0f

    /** The GPS signal in bars by the error of the fix, metres: up to 5 four bars, 12 three, 25 two, worse one; no fix, none. */
    private val GPS_BAR_LIMITS_M = listOf(5f, 12f, 25f)

    /** The mobile operator's name is shown at most this long. */
    private const val OPERATOR_MAX = 8

    /** The sun's rise or set is said only this close. */
    const val SUN_WINDOW_MIN = 60

    /** From "high" (WHO) the UV is said; from 101 (EPA: unhealthy for sensitive groups) the air is. */
    const val UV_FROM = 6.0
    const val AQI_ABOVE = 100

    /** Older than this the ring marks its contour as dashed (two fetches missed); over [WeatherKeeper.USABLE_MS] it is not shown. */
    const val STALE_MS = 30 * 60_000L

    private const val MS_PER_SECOND = 1_000L
    private const val QUARTER_MIN = 15L
    private const val ROUND_TO_MIN = 10
    private const val QUARTERS_PER_HOUR = 4
    private val THUNDER_CODES = setOf(95, 96, 99)

    /**
     * Whether Open-Meteo has real quarter-hour data at [place]: North America (NOAA HRRR) and Central Europe (DWD ICON-D2,
     * Météo-France AROME). Anywhere else it spreads the hourly forecast over the quarters, so the minutes are only a guess. The two
     * boxes are cautious on purpose: a place at their edge is taken as a guess.
     */
    fun hasRealQuarters(place: DayNight.Place?): Boolean {
        place ?: return false
        val northAmerica = place.lat in 25.0..52.0 && place.lng in -125.0..-67.0
        val centralEurope = place.lat in 43.0..55.0 && place.lng in -3.0..18.0
        return northAmerica || centralEurope
    }

    /** The wind level a gust reaches, or null below the first. */
    fun windTone(gustKmh: Double): RingTone? = when {
        gustKmh >= GUST_DANGER_KMH -> RingTone.DANGER
        gustKmh >= GUST_ALERT_KMH -> RingTone.ALERT
        gustKmh >= GUST_ATTENTION_KMH -> RingTone.ATTENTION
        else -> null
    }

    /** The place's time now: the clock with the answer's offset; with none, the reading's own time moved on by the answer's age. */
    internal fun localNow(snapshot: WeatherKeeper.Snapshot, wallNowMs: Long, monoNowMs: Long): LocalDateTime? {
        val offset = snapshot.forecast.utcOffsetSeconds
        val base = snapshot.forecast.current?.timeIso?.let(::parse)
        return when {
            offset != null -> LocalDateTime.ofEpochSecond(Math.floorDiv(wallNowMs, MS_PER_SECOND), 0, ZoneOffset.ofTotalSeconds(offset))
            base != null -> base.plus(snapshot.ageMs(wallNowMs, monoNowMs).coerceAtLeast(0L), ChronoUnit.MILLIS)
            else -> null
        }
    }

    internal fun parse(iso: String): LocalDateTime? = runCatching { LocalDateTime.parse(iso) }.getOrNull()

    private class Quarter(val end: LocalDateTime, val mm: Double?, val code: Int?, val gusts: Double?)

    private fun quarters(forecast: PointForecast): List<Quarter> =
        forecast.minutely.mapNotNull { m -> parse(m.timeIso)?.let { Quarter(it, m.precipitationMm, m.weatherCode, m.gustsKmh) } }
            .sortedBy { it.end }

    private fun minutesUntil(now: LocalDateTime, at: LocalDateTime): Double = Duration.between(now, at).toMillis() / MS_PER_MIN

    private const val MS_PER_MIN = 60_000.0

    private fun wet(mm: Double?) = mm != null && mm >= WET_MM_PER_QUARTER

    /** A whole number of minutes, at least one. */
    private fun wholeMinutes(minutes: Double): Int = max(1, ceil(minutes).toInt())

    /** What the rain does in the next hour. */
    private sealed interface Rain {
        object None : Rain
        class Falling(val untilMin: Int?, val quarter: Quarter?) : Rain
        class Starts(val inMin: Int, val quarter: Quarter) : Rain
    }

    @Suppress("ReturnCount") // the rain now first, then the rain to come, each its own early answer
    private fun rain(forecast: PointForecast, now: LocalDateTime, real: Boolean): Rain {
        val all = quarters(forecast)
        val past = all.lastOrNull { !it.end.isAfter(now) }
        val future = all.filter { it.end.isAfter(now) }
        fun likely(q: Quarter): Boolean {
            if (real) return true
            val hour = q.end.minusMinutes(1).truncatedTo(ChronoUnit.HOURS).toString()
            val probability = forecast.hourly.firstOrNull { it.timeIso == hour }?.precipitationProbabilityPercent
            return probability == null || probability >= HOURLY_MIN_PROBABILITY
        }
        val raining = wet(past?.mm) || (past == null && wet(forecast.current?.precipitationMm))
        // The first quarter that changes what is happening now, and when in it: the earliest instant, or half of what is left of it
        // when it already began.
        fun change(q: Quarter): Int {
            val earliest = minutesUntil(now, q.end.minusMinutes(QUARTER_MIN))
            return wholeMinutes(if (earliest > 0.0) earliest else minutesUntil(now, q.end) / 2.0)
        }
        if (raining) {
            val stop = future.firstOrNull { !wet(it.mm) || !likely(it) }
            return Rain.Falling(stop?.let(::change), past ?: future.firstOrNull())
        }
        val start = future.firstOrNull { wet(it.mm) && likely(it) } ?: return Rain.None
        val inMin = change(start)
        return if (inMin <= LOOK_AHEAD_MIN) Rain.Starts(inMin, start) else Rain.None
    }

    private fun rainIcon(q: Quarter?, current: CurrentWeather?, raining: Boolean): RingIcon {
        val condition = WeatherCondition.forCode(q?.code ?: if (raining) current?.weatherCode else null)
        val perHour = (q?.mm ?: current?.precipitationMm ?: 0.0) * QUARTERS_PER_HOUR
        return when {
            condition == WeatherCondition.SNOW -> RingIcon.SNOW
            condition == WeatherCondition.DRIZZLE -> RingIcon.DRIZZLE
            perHour > HEAVY_MM_PER_HOUR -> RingIcon.HEAVY_RAIN
            else -> RingIcon.RAIN
        }
    }

    /** When the first storm in the next hour is, in minutes (0: it is on now); null for none. */
    private fun thunder(forecast: PointForecast, now: LocalDateTime): Int? {
        val all = quarters(forecast)
        val past = all.lastOrNull { !it.end.isAfter(now) }
        val soon = all.firstOrNull { it.end.isAfter(now) && it.code in THUNDER_CODES }
        return when {
            forecast.current?.weatherCode in THUNDER_CODES || past?.code in THUNDER_CODES -> 0
            soon == null -> null
            else -> wholeMinutes(minutesUntil(now, soon.end)).takeIf { it <= LOOK_AHEAD_MIN }
        }
    }

    /** The strongest gust now or in the next hour, km/h; the steady wind when no gust is said. */
    private fun gust(forecast: PointForecast, now: LocalDateTime): Double? {
        val from = now.minusMinutes(QUARTER_MIN)
        val to = now.plusMinutes(LOOK_AHEAD_MIN.toLong())
        val near = quarters(forecast).filter { !it.end.isBefore(from) && !it.end.isAfter(to) }
        val gusts = near.mapNotNull { it.gusts } + listOfNotNull(forecast.current?.windGustsKmh)
        return gusts.maxOrNull() ?: forecast.current?.windSpeedKmh
    }

    private fun uvNow(forecast: PointForecast, now: LocalDateTime): Double? {
        val hour = now.truncatedTo(ChronoUnit.HOURS).toString()
        return forecast.hourly.firstOrNull { it.timeIso == hour }?.uvIndex
    }

    /** The next sunrise or sunset within [SUN_WINDOW_MIN]: its icon and the minutes; null when none is that near. */
    private fun sun(forecast: PointForecast, now: LocalDateTime): Pair<RingIcon, Int>? {
        val events = forecast.daily.flatMap { d ->
            listOfNotNull(
                d.sunriseIso?.let(::parse)?.let { it to RingIcon.SUNRISE },
                d.sunsetIso?.let(::parse)?.let { it to RingIcon.SUNSET },
            )
        }
        val next = events.filter { it.first.isAfter(now) }.minByOrNull { it.first } ?: return null
        val minutes = wholeMinutes(minutesUntil(now, next.first))
        return (next.second to minutes).takeIf { minutes <= SUN_WINDOW_MIN }
    }

    private fun skyIcon(current: CurrentWeather): RingIcon? {
        val night = current.isDay == false
        return when (WeatherCondition.forCode(current.weatherCode)) {
            WeatherCondition.CLEAR -> if (night) RingIcon.CLEAR_NIGHT else RingIcon.CLEAR
            WeatherCondition.PARTLY_CLOUDY -> if (night) RingIcon.PARTLY_CLOUDY_NIGHT else RingIcon.PARTLY_CLOUDY
            WeatherCondition.CLOUDY -> RingIcon.CLOUDY
            WeatherCondition.FOG -> RingIcon.FOG
            WeatherCondition.DRIZZLE -> RingIcon.DRIZZLE
            WeatherCondition.RAIN -> RingIcon.RAIN
            WeatherCondition.SNOW -> RingIcon.SNOW
            WeatherCondition.THUNDERSTORM -> RingIcon.THUNDER
            WeatherCondition.UNKNOWN -> null
        }
    }

    /** The sky in a word, for each icon [skyIcon] may give. */
    private fun skyWord(icon: RingIcon, words: RingWords): String = when (icon) {
        RingIcon.CLEAR, RingIcon.CLEAR_NIGHT -> words.skyClear
        RingIcon.PARTLY_CLOUDY, RingIcon.PARTLY_CLOUDY_NIGHT -> words.skyPartly
        RingIcon.CLOUDY -> words.skyCloudy
        RingIcon.FOG -> words.skyFog
        RingIcon.DRIZZLE -> words.skyDrizzle
        RingIcon.RAIN, RingIcon.HEAVY_RAIN -> words.skyRain
        RingIcon.SNOW -> words.skySnow
        RingIcon.THUNDER -> words.thunder
        else -> words.skyCloudy
    }

    private fun isPrecipitating(icon: RingIcon) = icon in setOf(RingIcon.DRIZZLE, RingIcon.RAIN, RingIcon.SNOW, RingIcon.THUNDER)

    private fun minutesText(inMin: Int, real: Boolean, words: RingWords, lead: String): List<RingText> {
        val shown = if (real) inMin.toString() else words.approx + (((inMin + ROUND_TO_MIN / 2) / ROUND_TO_MIN) * ROUND_TO_MIN).coerceAtLeast(ROUND_TO_MIN)
        return listOf(RingText(lead, false), RingText(shown, true), RingText(words.minutes, false))
    }

    private fun uvTone(uv: Double): RingTone = when {
        uv >= EXTREME_UV -> RingTone.UV_EXTREME
        uv >= VERY_HIGH_UV -> RingTone.UV_VERY_HIGH
        else -> RingTone.UV_HIGH
    }

    private const val VERY_HIGH_UV = 8.0
    private const val EXTREME_UV = 11.0

    private fun airTone(severity: AirQualitySeverity): RingTone = when (severity) {
        AirQualitySeverity.UNHEALTHY_SENSITIVE -> RingTone.AIR_SENSITIVE
        AirQualitySeverity.UNHEALTHY -> RingTone.AIR_UNHEALTHY
        AirQualitySeverity.VERY_UNHEALTHY -> RingTone.AIR_VERY_UNHEALTHY
        else -> RingTone.AIR_HAZARDOUS
    }

    /** The GPS signal in bars, 0 (no fix) to 4, from the fix's error; a fix that does not say its error is taken as two bars. */
    fun gpsBars(gps: GpsReading): Int {
        val error = gps.accuracyM
        return when {
            gps.usedSatellites <= 0 -> 0
            error == null -> 2
            else -> GPS_BAR_LIMITS_M.indexOfFirst { error <= it }.let { if (it < 0) 1 else MAX_BARS - it }
        }
    }

    private const val MAX_BARS = 4

    /** The heading in whole degrees (0 to 359), only while the phone is moving; null standing still or with no heading. */
    fun gpsBearing(gps: GpsReading): Int? {
        val bearing = gps.bearingDeg ?: return null
        val speed = gps.speedMps ?: return null
        return if (speed >= MOVING_MPS) Math.floorMod(bearing.roundToInt(), FULL_TURN) else null
    }

    private const val FULL_TURN = 360

    /** The pages of the phone: its battery, the link it carries data on and the GPS; each only when the feed read it. */
    internal fun phonePages(phone: PhoneReadings, words: RingWords): List<RingPage> = listOfNotNull(
        phone.battery?.let { batteryPage(it, words) },
        phone.link?.let { linkPage(it, words) },
        phone.gps?.let { gpsPage(it, words) },
    )

    private fun batteryPage(b: BatteryDetail, words: RingWords): RingPage {
        val critical = !b.charging && b.percent <= BATTERY_CRITICAL_PERCENT
        val low = !b.charging && b.percent <= BATTERY_LOW_PERCENT
        val status = when {
            b.charging -> words.charging
            b.saver -> words.saver
            else -> words.onBattery
        }
        return RingPage(
            RingKind.BATTERY, if (critical) 1 else if (low) 2 else 4, RingIcon.BATTERY,
            if (critical) RingTone.ALERT else if (low) RingTone.ATTENTION else RingTone.NONE,
            listOf(RingText(status, false)), b,
        )
    }

    private fun linkPage(l: LinkReading, words: RingWords): RingPage {
        val line = when (l.kind) {
            LinkKind.AIRPLANE -> listOf(RingText(words.airplane, false))
            LinkKind.OFFLINE -> listOf(RingText(words.offline, false))
            LinkKind.WIFI -> listOf(RingText("Wi-Fi", false))
            LinkKind.STARLINK -> listOf(RingText("Starlink", false))
            LinkKind.SATELLITE -> listOf(RingText(words.satellite, false))
            LinkKind.CELLULAR -> listOfNotNull(
                l.generation?.let { RingText(it, true) },
                (l.operator?.takeIf { it.isNotBlank() }?.take(OPERATOR_MAX) ?: words.mobile.takeIf { l.generation == null })?.let { RingText(it, false) },
            ).ifEmpty { listOf(RingText(words.mobile, false)) }
        }
        val warns = l.kind == LinkKind.AIRPLANE || l.kind == LinkKind.OFFLINE
        return RingPage(
            RingKind.LINK, if (warns) 2 else 4, RingIcon.LINK, if (warns) RingTone.ATTENTION else RingTone.NONE,
            line, LinkDetail(l.kind, l.bars.coerceIn(0, MAX_BARS), l.generation),
        )
    }

    private fun gpsPage(g: GpsReading, words: RingWords): RingPage {
        val bars = gpsBars(g)
        val bearing = gpsBearing(g)
        val line = if (bars == 0) {
            listOf(RingText(words.noGps, false))
        } else {
            val point = bearing?.let { words.compass.getOrNull(windPointIndex(it)) } ?: "—"
            listOf(RingText(point, true), RingText("${g.usedSatellites} ${words.satellites}", false))
        }
        return RingPage(
            RingKind.GPS, if (bars == 0) 3 else 4, RingIcon.GPS, if (bars == 0) RingTone.ATTENTION else RingTone.NONE,
            line, GpsDetail(bars, g.usedSatellites, bearing, g.accuracyM?.roundToInt()),
        )
    }

    /** Every page there is something to say for, most urgent first; an empty list when the forecast says nothing. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // one block per page; the forecast, the clock and the rider's choices
    internal fun pages(
        forecast: PointForecast,
        air: AirQuality?,
        now: LocalDateTime,
        real: Boolean,
        units: WeatherUnits,
        words: RingWords,
        phone: PhoneReadings = PhoneReadings(),
    ): List<RingPage> {
        val current = forecast.current ?: return phonePages(phone, words)
        val pages = ArrayList<RingPage>()
        val gust = gust(forecast, now)
        val windLevel = gust?.let(::windTone)
        fun windPage(kind: RingKind, level: Int) = RingPage(
            kind, level, RingIcon.WIND, checkNotNull(windLevel),
            listOf(RingText(units.speedNumber(checkNotNull(gust)), true), RingText(units.speedUnit(), false)),
        )
        if (windLevel == RingTone.DANGER) pages += windPage(RingKind.WIND_DANGER, 1)
        thunder(forecast, now)?.let { inMin ->
            val line = if (inMin == 0) listOf(RingText(words.thunder, false)) else minutesText(inMin, real, words, words.inWord)
            pages += RingPage(RingKind.THUNDER, 1, RingIcon.THUNDER, RingTone.DANGER, line)
        }
        if (windLevel == RingTone.ALERT) pages += windPage(RingKind.WIND_ALERT, 1)
        val rain = rain(forecast, now, real)
        if (rain is Rain.Starts) {
            val level = if (rain.inMin <= RAIN_DANGER_MIN) 1 else 2
            pages += RingPage(
                RingKind.RAIN_SOON, level, rainIcon(rain.quarter, current, raining = false), RingTone.RAIN,
                minutesText(rain.inMin, real, words, words.inWord),
            )
        }
        if (rain is Rain.Falling) {
            val line = rain.untilMin?.let { minutesText(it, real, words, words.until) } ?: listOf(RingText(words.raining, false))
            pages += RingPage(RingKind.RAIN_NOW, 2, rainIcon(rain.quarter, current, raining = true), RingTone.RAIN, line)
        }
        if (windLevel == RingTone.ATTENTION) pages += windPage(RingKind.WIND_ATTENTION, 2)
        sun(forecast, now)?.let { (icon, minutes) ->
            pages += RingPage(RingKind.SUN, 2, icon, RingTone.NONE, minutesText(minutes, true, words, words.inWord))
        }
        uvNow(forecast, now)?.takeIf { it >= UV_FROM }?.let { uv ->
            pages += RingPage(RingKind.UV, 3, RingIcon.UV, uvTone(uv), listOf(RingText(words.uv, false), RingText(uv.roundToInt().toString(), true)))
        }
        air?.usAqi?.takeIf { it > AQI_ABOVE }?.let { aqi ->
            pages += RingPage(
                RingKind.AIR, 3, RingIcon.AIR, airTone(AirQualitySeverity.forUsAqi(aqi)),
                listOf(RingText(words.aqi, false), RingText(aqi.toString(), true)),
            )
        }
        skyIcon(current)?.let { icon ->
            val dry = rain is Rain.None && !isPrecipitating(icon) && forecast.minutely.isNotEmpty()
            // Never a bare icon (28/10/2026): dry says so, otherwise the sky in a word.
            pages += RingPage(RingKind.SKY, 4, icon, RingTone.NONE, listOf(RingText(if (dry) words.noRain else skyWord(icon, words), false)))
        }
        val today = forecast.daily.firstOrNull { it.dateIso == now.toLocalDate().toString() } ?: forecast.daily.firstOrNull()
        val temps = if (today != null) "${units.number(today.tempMaxC)}/${units.number(today.tempMinC)}°" else "${units.number(current.temperatureC)}°"
        pages += RingPage(RingKind.TEMPS, 4, RingIcon.THERMOMETER, RingTone.NONE, listOf(RingText(temps, true)))
        pages += phonePages(phone, words)
        return pages.sortedBy { it.level }
    }

    /**
     * What the ring draws now, or null for nothing: no forecast, one older than [WeatherKeeper.USABLE_MS], or one with no time to
     * count from. [rotation] is the ring's own memory of which page it is on.
     */
    @Suppress("LongParameterList", "ReturnCount") // the clocks, the place, the phone and the rider's choices, all given; a guard per reason for no ring
    fun frame(
        snapshot: WeatherKeeper.Snapshot?,
        rotation: InfoRingRotation,
        wallNowMs: Long,
        monoNowMs: Long,
        place: DayNight.Place?,
        night: Boolean,
        units: WeatherUnits,
        words: RingWords,
        atMs: Long,
        options: RingOptions = RingOptions(),
        phone: PhoneReadings = PhoneReadings(),
    ): RingFrame? {
        val age = snapshot?.ageMs(wallNowMs, monoNowMs)
        val weatherUsable = snapshot != null && age != null && age <= WeatherKeeper.USABLE_MS
        val now = if (weatherUsable) localNow(snapshot!!, wallNowMs, monoNowMs) else null
        // The phone's own cards need no forecast: with none usable (no location, no network, one too old) they still say what they know.
        val all = if (now != null && snapshot != null) {
            pages(snapshot.forecast, snapshot.air, now, hasRealQuarters(place), units, words, phone)
        } else {
            phonePages(phone, words)
        }
        val pages = all.filter { RingData.shown(options.data, RingData.of(it.kind)) }
        val page = rotation.pick(pages, atMs, options.carousel, options.periodMs) ?: return null
        return RingFrame(page, stale = now != null && age != null && age > STALE_MS, night = night)
    }
}

/**
 * Which page the ring is on. Level 1 pages (danger) alone take their turns, and a new one shows at once; with none, the others take
 * theirs from the most urgent, each for the carousel's time ([RingOptions.seconds]). With the carousel off the ring stays on the most urgent
 * page. Only the set of pages, not their words, restarts the turns.
 */
class InfoRingRotation {
    private var kinds: List<RingKind> = emptyList()
    private var sinceMs = 0L

    fun pick(pages: List<RingPage>, nowMs: Long, carousel: Boolean = true, periodMs: Long = RingOptions().periodMs): RingPage? {
        if (pages.isEmpty()) {
            kinds = emptyList()
            return null
        }
        val danger = pages.filter { it.level == 1 }
        val shown = (danger.ifEmpty { pages }).let { if (carousel) it else it.take(1) }
        val period = periodMs.coerceAtLeast(1L)
        val now = shown.map { it.kind }
        if (now != kinds) {
            kinds = now
            sinceMs = nowMs
        }
        val index = (((nowMs - sinceMs).coerceAtLeast(0L) / period) % shown.size).toInt()
        return shown[index]
    }
}
