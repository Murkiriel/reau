package com.reau.app.weather

import com.reau.app.service.DayNight

/**
 * What [WeatherKeeper.refreshIfStale] decides before it looks for a network, in this order: no place (nothing is asked, so nothing of the
 * rider leaves the phone), a weather kept for this same place and younger than [maxAgeMs], and, when the caller respects it ([respectBackoff]:
 * the projection's look of every minute), the wait after failed requests ([backoff]).
 */
internal class WeatherGate(private val maxAgeMs: Long, private val respectBackoff: Boolean, private val backoff: WeatherBackoff) {
    enum class Verdict { NO_PLACE, FRESH_ENOUGH, BACKING_OFF, GO }

    /** The verdict for [place], with the weather [kept] (fetched for [keptPlace]) at the wall clock [nowMs] and the monotonic [monoNowMs]. */
    fun verdict(place: DayNight.Place?, kept: WeatherKeeper.Snapshot?, keptPlace: DayNight.Place?, nowMs: Long, monoNowMs: Long): Verdict = when {
        place == null -> Verdict.NO_PLACE
        kept != null && keptPlace == place && kept.ageMs(nowMs, monoNowMs) < maxAgeMs -> Verdict.FRESH_ENOUGH
        respectBackoff && !backoff.mayTry(monoNowMs) -> Verdict.BACKING_OFF
        else -> Verdict.GO
    }
}
