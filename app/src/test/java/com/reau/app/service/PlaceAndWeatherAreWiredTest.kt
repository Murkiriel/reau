package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The store of the place, the weather keeper and the projection's loop need Android to run, so the lines that only an emulator could prove
 * (the keeper's own calls) are read from the source, as [ConnectionExperimentsAreWiredTest] does. What can be run on the JVM is run:
 * the permission gate of the place (SunPlaceTest, SunPlaceSavedTest), what comes before any network (WeatherGateTest) and the waits
 * (WeatherBackoffTest).
 *
 * Classes: the place's store read by SunPlace alone is a rule of form (b); the lines of the keeper that call the gate, the backoff and
 * the store are (c): they sit inside `refreshIfStale`, which needs a Context, a network and the settings.
 */
class PlaceAndWeatherAreWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val sunPlace = File(main, "service/SunPlace.kt").readText()
    private val keeper = File(main, "weather/WeatherKeeper.kt").readText()
    private val live = File(main, "service/LiveSides.kt").readText()

    @Test
    fun `the place kept is given through the permission check of Android and the store of the place`() {
        val saved = sunPlace.substringAfter("fun saved(context: Context").substringBefore("/** [saved] with the question")
        assertTrue(saved.contains("context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED"))
        assertTrue(saved.contains("read(context)"))
    }

    @Test
    fun `the store of the place is read by SunPlace alone, so nothing can skip the gate`() {
        val readers = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("reau_sun_place") }.map { it.name }.toList()
        assertEquals(listOf("SunPlace.kt"), readers)
    }

    @Test
    fun `the weather asks for the place through SunPlace and puts it through the gate before any request`() {
        val refresh = keeper.substringAfter("suspend fun refreshIfStale(").substringBefore("internal fun url(")
        assertTrue(refresh.contains("val place = SunPlace.saved(app, settings.useLocation)"))
        assertTrue(refresh.contains("val gate = WeatherGate(maxAgeMs, respectBackoff, backoff).verdict(place, snapshotState.value, cachedPlace, nowMs, monoNowMs)"))
        assertTrue(refresh.contains("if (gate == WeatherGate.Verdict.NO_PLACE) whyState.value = Why.NO_PLACE"))
        assertTrue(refresh.contains("if (gate != WeatherGate.Verdict.GO || place == null) return@withLock"))
        assertTrue("the request is built after the gate", refresh.indexOf("url(place)") > refresh.indexOf("if (gate != WeatherGate.Verdict.GO"))
    }

    @Test
    fun `a failed request starts the backoff, a success or a clear starts it over, and the projection's look respects it`() {
        val refresh = keeper.substringAfter("suspend fun refreshIfStale(").substringBefore("internal fun url(")
        val failure = refresh.substringAfter("if (forecast?.current == null) {").substringBefore("return@withLock")
        assertTrue(failure.contains("val wait = backoff.failed(monoNowMs)"))
        assertTrue(failure.contains("next try in "))
        assertTrue("the gate comes before any network", refresh.indexOf(").verdict(place") < refresh.indexOf("withInternet(app)"))
        assertTrue(refresh.contains("        backoff.succeeded()\n"))
        assertTrue(keeper.substringAfter("fun clear(").substringBefore("/** The weather as the dash's heartbeat").contains("backoff.reset()"))
        assertTrue(live.contains("WeatherKeeper.refreshIfStale(context, respectBackoff = true)"))
    }

    @Test
    fun `no network is not a failure that waits longer, since nothing was sent and the first minute with a network is the wanted one`() {
        val refresh = keeper.substringAfter("suspend fun refreshIfStale(").substringBefore("internal fun url(")
        val noNetwork = refresh.substringAfter("if (networks.isEmpty()) {").substringBefore("}")
        assertTrue(!noNetwork.contains("backoff"))
    }
}
