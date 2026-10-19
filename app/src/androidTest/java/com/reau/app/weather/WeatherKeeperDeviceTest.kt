package com.reau.app.weather

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** With "Clima no painel" never switched on, the weather is never fetched, and not even asked about. */
@RunWith(AndroidJUnit4::class)
class WeatherKeeperDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun withTheSwitchOffNothingIsFetchedAndNothingIsSaid() {
        val store = SettingsStore.open(context)
        val before = store.load()
        try {
            store.save(before.copy(dashWeather = false, useLocation = true))
            WeatherKeeper.clear(context)
            runBlocking { WeatherKeeper.refreshIfStale(context) }
            runBlocking { WeatherKeeper.refreshIfStale(context, maxAgeMs = WeatherKeeper.OPEN_GAP_MS) }
            assertNull("nothing kept", WeatherKeeper.snapshot.value)
            // Past the switch it would have said NO_PLACE, NO_NETWORK or FAILED: it did not even look.
            assertEquals(WeatherKeeper.Why.NONE, WeatherKeeper.why.value)
        } finally {
            store.save(before)
        }
    }

    @Test
    fun switchedOffAgainWhatWasKeptIsGone() {
        WeatherKeeper.clear(context)
        assertNull(WeatherKeeper.snapshot.value)
        assertEquals(WeatherKeeper.Why.NONE, WeatherKeeper.why.value)
        assertNull(WeatherKeeper.forDash())
    }
}
