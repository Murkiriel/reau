// The request goes through a network that really has the internet (never the dash's Wi-Fi, which has none);
// the place is the rounded one REau already keeps for the sun; the answer is kept on the phone; no personal text in the User-Agent.
package com.reau.app.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.reau.app.data.RideDiagnostics
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.service.DayNight
import com.reau.app.service.SunPlace
import com.reau.app.settings.SettingsStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The weather for the dash, from Open-Meteo (free, no key, no account).
 *
 * Only while the rider's switch "Clima no painel" is on and "Usar a localização" has given a place. What leaves the phone:
 * the request, with the place rounded to a tenth of a degree (about 11 km), to api.open-meteo.com; the answer is kept on
 * the phone ([Snapshot]). Never in the ride log: no place, no temperature of the place, only that an update happened or why not.
 */
object WeatherKeeper {
    /** What one fetch gave and when. */
    data class Snapshot(val forecast: PointForecast, val fetchedAtMs: Long, val air: AirQuality? = null)

    /** Why there is no fresh weather, for the screen. */
    enum class Why { NONE, NO_PLACE, NO_NETWORK, FAILED }

    private const val PREFS = "reau_weather"
    private const val KEY_JSON = "json"
    private const val KEY_AT = "at"
    private const val KEY_AIR = "air"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val UA = "REau/1"
    private const val AIR_ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val AIR_FIELDS = "us_aqi,pm10,pm2_5,ozone"

    /** Fourteen days, for the chart; the list shows today and the seven after it. */
    private const val FORECAST_DAYS = 14
    private const val CURRENT_FIELDS = "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation," +
        "weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure,cloud_cover,is_day"
    private const val HOURLY_FIELDS = "temperature_2m,precipitation_probability,weather_code,uv_index"
    private const val DAILY_FIELDS = "temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
        "uv_index_max,sunrise,sunset,daylight_duration,wind_speed_10m_max,wind_gusts_10m_max,weather_code"

    /** A fetch is repeated after this long (a quarter of an hour: the weather moves slowly, the service is shared). */
    const val REFRESH_MS = 15 * 60_000L

    /** On opening REau the weather is fetched again unless it was fetched less than this ago (a minute: reopening is not spam). */
    const val OPEN_GAP_MS = 60_000L

    /** Older than this the weather is not told to the dash any more (three hours): better none than an old one. */
    const val USABLE_MS = 3 * 60 * 60_000L

    private val lock = Mutex()
    private val snapshotState = MutableStateFlow<Snapshot?>(null)
    private val whyState = MutableStateFlow(Why.NONE)
    @Volatile private var cachedPlace: DayNight.Place? = null

    /** The last weather kept, from this run or an earlier one; null with none. */
    val snapshot: StateFlow<Snapshot?> get() = snapshotState

    /** Why the last try gave nothing new ([Why.NONE] when it worked or nothing was tried). */
    val why: StateFlow<Why> get() = whyState

    /** Loads what an earlier run kept. */
    fun attach(context: Context) {
        if (snapshotState.value != null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return
        runCatching { WeatherParser.parse(json) }.getOrNull()?.let {
            val air = prefs.getString(KEY_AIR, null)?.let { raw -> runCatching { AirQualityParser.parse(raw) }.getOrNull() }
            snapshotState.value = Snapshot(it, prefs.getLong(KEY_AT, 0L), air)
            if (prefs.contains(KEY_LAT)) {
                cachedPlace = DayNight.Place(Double.fromBits(prefs.getLong(KEY_LAT, 0)), Double.fromBits(prefs.getLong(KEY_LNG, 0)))
            }
        }
    }

    /** A test shows the section with a weather of its own, with no network. */
    internal fun seed(snapshot: Snapshot?) {
        snapshotState.value = snapshot
    }

    /** Forgets the weather kept (the switch or the location went off). */
    fun clear(context: Context) {
        snapshotState.value = null
        whyState.value = Why.NONE
        cachedPlace = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    /** The weather as the dash's heartbeat takes it, or null: none yet, none usable, or a current reading with no temperature. */
    fun forDash(nowMs: Long = System.currentTimeMillis()): DashCommands.Weather? = forDash(snapshotState.value, nowMs)

    internal fun forDash(snapshot: Snapshot?, nowMs: Long): DashCommands.Weather? {
        snapshot ?: return null
        if (nowMs - snapshot.fetchedAtMs > USABLE_MS) return null
        val now = snapshot.forecast.current ?: return null
        val icon = WeatherCondition.forCode(now.weatherCode).dashIcon ?: DashCommands.WEATHER_NONE
        return DashCommands.Weather(now.temperatureC.roundToInt(), icon)
    }

    /**
     * Fetches when the weather kept is older than [maxAgeMs] ([REFRESH_MS]; [OPEN_GAP_MS] when REau was just opened) or from
     * another place. With "Clima no painel" off it does nothing at all: never a request, never a trace. With "Usar a
     * localização" off, or no place learned yet, it says so ([why]) and fetches nothing.
     */
    @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
    suspend fun refreshIfStale(context: Context, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = REFRESH_MS) = lock.withLock {
        val app = context.applicationContext
        val settings = SettingsStore.open(app).load()
        if (!settings.dashWeather) return@withLock
        attach(app)
        val place = SunPlace.saved(app, settings.useLocation)
        if (place == null) {
            whyState.value = Why.NO_PLACE
            return@withLock
        }
        val kept = snapshotState.value
        if (kept != null && cachedPlace == place && nowMs - kept.fetchedAtMs < maxAgeMs) return@withLock
        val networks = withInternet(app)
        if (networks.isEmpty()) {
            whyState.value = Why.NO_NETWORK
            RideDiagnostics.log("weather", "no network with the internet: weather not updated")
            return@withLock
        }
        // The system's own choice first (it knows which VPN REau is inside of and which not: a VPN may leave REau out,
        // and asking through the VPN's network gave "unable to resolve host"), then the others, one by one.
        var json: String? = null
        var reached: Network? = null
        for (network in networks) {
            val answer = withContext(Dispatchers.IO) { runCatching { get(network, url(place)) } }
            // Why it failed goes to the log by kind only (the exception's class), never the URL, which has the place.
            answer.exceptionOrNull()?.let { RideDiagnostics.log("weather", "the request failed: ${it.javaClass.simpleName}") }
            json = answer.getOrNull()
            if (json != null) {
                reached = network
                break
            }
        }
        val parsed = json?.let { runCatching { WeatherParser.parse(it) } }
        parsed?.exceptionOrNull()?.let { RideDiagnostics.log("weather", "the answer could not be read: ${it.javaClass.simpleName}") }
        val forecast = parsed?.getOrNull()
        // The rider may have switched it off while the answer was on its way: then nothing of it is kept.
        if (!SettingsStore.open(app).load().dashWeather) return@withLock
        if (forecast?.current == null) {
            whyState.value = Why.FAILED
            RideDiagnostics.log("weather", "the weather service gave nothing usable")
            return@withLock
        }
        // Air quality is a separate host, asked through the network that worked; a failure there leaves only that card out.
        val airJson = reached?.let { net -> withContext(Dispatchers.IO) { runCatching { get(net, airUrl(place)) }.getOrNull() } }
        val air = airJson?.let { runCatching { AirQualityParser.parse(it) }.getOrNull() }
        if (!SettingsStore.open(app).load().dashWeather) return@withLock
        snapshotState.value = Snapshot(forecast, nowMs, air)
        cachedPlace = place
        whyState.value = Why.NONE
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_JSON, json)
            putString(KEY_AIR, airJson?.takeIf { air != null })
            putLong(KEY_AT, nowMs)
            putLong(KEY_LAT, place.lat.toRawBits())
            putLong(KEY_LNG, place.lng.toRawBits())
        }
        RideDiagnostics.log("weather", "weather updated: ${forecast.hourly.size} hours, ${forecast.daily.size} days")
    }

    internal fun url(place: DayNight.Place): String {
        // Locale.US: a comma-decimal locale (pt-BR) would corrupt the query.
        val lat = "%.1f".format(Locale.US, place.lat)
        val lng = "%.1f".format(Locale.US, place.lng)
        return "$ENDPOINT?latitude=$lat&longitude=$lng&current=$CURRENT_FIELDS&hourly=$HOURLY_FIELDS&daily=$DAILY_FIELDS" +
            "&forecast_days=$FORECAST_DAYS&timezone=auto"
    }

    internal fun airUrl(place: DayNight.Place): String {
        val lat = "%.1f".format(Locale.US, place.lat)
        val lng = "%.1f".format(Locale.US, place.lng)
        return "$AIR_ENDPOINT?latitude=$lat&longitude=$lng&current=$AIR_FIELDS&timezone=auto"
    }

    /**
     * The networks that really reach the internet (validated), the system's default first, the VPNs last; never the dash's
     * Wi-Fi, which has none.
     */
    private fun withInternet(context: Context): List<Network> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        fun usable(network: Network): Boolean = manager.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        fun vpn(network: Network): Boolean = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val active = manager.activeNetwork?.takeIf(::usable)
        val others = manager.allNetworks.filter { it != active && usable(it) }.sortedBy { vpn(it) }
        return listOfNotNull(active) + others
    }

    private fun get(network: Network, url: String): String {
        val connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
