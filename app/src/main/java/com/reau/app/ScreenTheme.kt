package com.reau.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import kotlin.concurrent.thread

/** How often the screens' theme asks again whether it is night. */
private const val THEME_EVERY_MS = 60_000L

/** The place for the sun, learned while REau is on the screen (off the main thread). */
internal fun learnSunPlace(context: android.content.Context, thenWeather: Boolean = false, then: () -> Unit = {}) {
    val settings = SettingsStore.open(context).load()
    thread(name = "sun-place") {
        com.reau.app.service.PhonePlace.learn(context, settings.useLocation)
        then()
        // Each time REau is opened it fetches the weather by itself (only with "Clima no
        // painel" on; the keeper itself does nothing otherwise), once the place is learned.
        if (thenWeather && settings.dashWeather) {
            kotlinx.coroutines.runBlocking {
                runCatching {
                    com.reau.app.weather.WeatherKeeper.refreshIfStale(context, maxAgeMs = com.reau.app.weather.WeatherKeeper.OPEN_GAP_MS)
                }
            }
        }
    }
}

/**
 * Whether it is night for the screens' theme, asked again each minute, as the rider's "Tema" says: by the sun
 * on automatic. The dash's own theme (03 05) has no say in it.
 */
@androidx.compose.runtime.Composable
internal fun rememberNight(context: android.content.Context, settings: REauSettings): Boolean {
    val night by produceState(true, settings.mapTheme, settings.useLocation) {
        val clock = com.reau.app.service.DayNight.Clock(
            settings.mapTheme,
            place = { com.reau.app.service.SunPlace.saved(context, settings.useLocation) },
            offsetMs = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
        )
        while (true) {
            value = clock.isNight()
            kotlinx.coroutines.delay(THEME_EVERY_MS)
        }
    }
    return night
}
