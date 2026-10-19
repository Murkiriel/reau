package com.reau.app.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Os extras do clima que não vêm da rede: as faixas do índice de qualidade do ar (EPA) e do índice UV (OMS), e a fase da Lua
 * calculada em casa. O leitor do JSON do Open-Meteo usa org.json, que a JVM dos testes não traz: o teste dele roda no aparelho.
 */
class WeatherExtrasTest {
    // ---- qualidade do ar ----

    @Test
    fun `o indice dos EUA cai nas faixas da EPA, com o limite de cima dentro da faixa`() {
        val bands = listOf(
            0 to AirQualitySeverity.GOOD, 50 to AirQualitySeverity.GOOD,
            51 to AirQualitySeverity.MODERATE, 100 to AirQualitySeverity.MODERATE,
            101 to AirQualitySeverity.UNHEALTHY_SENSITIVE, 150 to AirQualitySeverity.UNHEALTHY_SENSITIVE,
            151 to AirQualitySeverity.UNHEALTHY, 200 to AirQualitySeverity.UNHEALTHY,
            201 to AirQualitySeverity.VERY_UNHEALTHY, 300 to AirQualitySeverity.VERY_UNHEALTHY,
            301 to AirQualitySeverity.HAZARDOUS, 500 to AirQualitySeverity.HAZARDOUS,
        )
        for ((aqi, band) in bands) assertEquals("AQI $aqi", band, AirQualitySeverity.forUsAqi(aqi))
    }

    @Test
    fun `sem indice a faixa e desconhecida, nunca boa`() {
        assertEquals(AirQualitySeverity.UNKNOWN, AirQualitySeverity.forUsAqi(null))
    }

    // ---- UV ----

    @Test
    fun `o indice UV cai nas faixas da OMS, com o limite de baixo na faixa de cima`() {
        val bands = listOf(
            0.0 to UvSeverity.LOW, 2.9 to UvSeverity.LOW,
            3.0 to UvSeverity.MODERATE, 5.9 to UvSeverity.MODERATE,
            6.0 to UvSeverity.HIGH, 7.9 to UvSeverity.HIGH,
            8.0 to UvSeverity.VERY_HIGH, 10.9 to UvSeverity.VERY_HIGH,
            11.0 to UvSeverity.EXTREME, 14.5 to UvSeverity.EXTREME,
        )
        for ((uv, band) in bands) assertEquals("UV $uv", band, UvSeverity.forIndex(uv))
    }

    @Test
    fun `sem indice UV a faixa e desconhecida`() {
        assertEquals(UvSeverity.UNKNOWN, UvSeverity.forIndex(null))
    }

    // ---- fase da Lua ----

    @Test
    fun `a lua nova de referencia e nova`() {
        assertEquals(MoonPhase.NEW, MoonPhaseCalculator.phaseFor(MoonPhaseCalculator.KNOWN_NEW_MOON_MS))
    }

    @Test
    fun `as quatro fases principais de janeiro de 2025 saem certas`() {
        // Instantes publicados (UTC): quarto crescente 6/jan 23:56, cheia 13/jan 22:27, quarto minguante 21/jan 20:31, nova 29/jan 12:36.
        assertEquals(MoonPhase.FIRST_QUARTER, MoonPhaseCalculator.phaseFor(1_736_207_760_000L))
        assertEquals(MoonPhase.FULL, MoonPhaseCalculator.phaseFor(1_736_807_220_000L))
        assertEquals(MoonPhase.LAST_QUARTER, MoonPhaseCalculator.phaseFor(1_737_491_460_000L))
        assertEquals(MoonPhase.NEW, MoonPhaseCalculator.phaseFor(1_738_154_160_000L))
    }

    @Test
    fun `antes da lua de referencia o calculo continua valendo`() {
        // A lua cheia de 22/dez/1999 13:31 UTC, 15 dias antes da lua nova de referencia.
        assertEquals(MoonPhase.FULL, MoonPhaseCalculator.phaseFor(945_869_460_000L))
    }

    @Test
    fun `as oito fatias seguem a ordem do ciclo e a lua nova fecha o ciclo`() {
        val month = MoonPhaseCalculator.SYNODIC_MONTH_DAYS
        val order = listOf(
            MoonPhase.NEW, MoonPhase.WAXING_CRESCENT, MoonPhase.FIRST_QUARTER, MoonPhase.WAXING_GIBBOUS,
            MoonPhase.FULL, MoonPhase.WANING_GIBBOUS, MoonPhase.LAST_QUARTER, MoonPhase.WANING_CRESCENT,
        )
        // O meio de cada fatia (a nova, o quarto e a cheia ficam centrados na sua).
        order.forEachIndexed { i, phase ->
            assertEquals("fatia $i", phase, MoonPhaseCalculator.phaseForAge(month * i / 8.0))
        }
        assertEquals("o fim do ciclo volta a ser nova", MoonPhase.NEW, MoonPhaseCalculator.phaseForAge(month - 0.01))
        assertEquals(MoonPhase.NEW, MoonPhaseCalculator.phaseForAge(0.0))
    }

    @Test
    fun `os limites das fatias trocam de fase na meia fatia`() {
        val eighth = MoonPhaseCalculator.SYNODIC_MONTH_DAYS / 8.0
        val half = eighth / 2.0
        assertEquals(MoonPhase.NEW, MoonPhaseCalculator.phaseForAge(half - 0.001))
        assertEquals(MoonPhase.WAXING_CRESCENT, MoonPhaseCalculator.phaseForAge(half + 0.001))
        assertEquals(MoonPhase.WAXING_CRESCENT, MoonPhaseCalculator.phaseForAge(half + eighth - 0.001))
        assertEquals(MoonPhase.FIRST_QUARTER, MoonPhaseCalculator.phaseForAge(half + eighth + 0.001))
    }

    @Test
    fun `uma lua cheia a cada mes sinodico, nunca duas fases seguidas fora de ordem`() {
        val dayMs = 86_400_000L
        var previous = MoonPhaseCalculator.phaseFor(1_735_689_600_000L) // 1/jan/2025 00:00 UTC
        val seen = mutableListOf(previous)
        for (day in 1..59) {
            val phase = MoonPhaseCalculator.phaseFor(1_735_689_600_000L + day * dayMs)
            if (phase != previous) seen += phase
            previous = phase
        }
        // Dois meses: o ciclo passa por todas as fases e cada troca vai para a fase seguinte.
        val cycle = MoonPhase.entries
        for (i in 1 until seen.size) {
            assertEquals("depois de ${seen[i - 1]}", cycle[(cycle.indexOf(seen[i - 1]) + 1) % cycle.size], seen[i])
        }
        assertEquals(2, seen.count { it == MoonPhase.FULL })
    }
}
