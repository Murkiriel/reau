package com.reau.app.dash.nav

import com.reau.app.aa.NavGuidance
import com.reau.app.dash.protocol.DashCommands
import kotlin.math.roundToInt

/**
 * Android Auto's turn-by-turn guidance as the dash's own nav bubble shows it.
 *
 * The maneuver type is Android Auto's NavigationManeuver.NavigationType wire number; each one becomes the dash glyph
 * of the catalog ([DashManeuverGlyphs], the glyph table observed on the dash, still to be checked
 * on the bike): turns by sharpness and side, ramps as the turn they are, keep-left/right and forks as the Y fork, merges
 * through the catalog's own rule, roundabouts with their exit (counter-clockwise, as right-hand traffic goes, unless
 * Android Auto says clockwise), ferries, arrivals. Anything else is the hidden bubble here, which the session never
 * sends while guiding (DashSession.ringManeuver keeps the last drawable maneuver); rerouting shows the spinning
 * compass. Distances under a kilometre go in metres, from there in tenths of a kilometre (the dash's two units).
 */
object AaManeuvers {
    /** What the dash's nav packet carries ([com.reau.app.dash.DashSession.updateNavInfo]). */
    data class DashNav(
        val maneuver: Int, val primaryDist: Int, val primaryUnit: Int, val totalDist: Int, val totalUnit: Int, val eta: String?,
        /** The street of the next maneuver, for the dash only; blank when Android Auto does not say it. */
        val road: String = "",
        /** The time left in seconds, [DashCommands.REMAINING_OMIT] when Android Auto does not say it. */
        val remainingSeconds: Long = DashCommands.REMAINING_OMIT,
        /** The glyph of the maneuver after the next, [DashCommands.NEXT_MANEUVER_NONE] for none. */
        val next: Int = DashCommands.NEXT_MANEUVER_NONE,
    )

    /** The scanned catalog's ferry and railway-crossing glyphs ([DashManeuverGlyphs.describe] 0x3E, 0x3F). */
    private const val FERRY = 0x3E
    private const val RAIL = 0x3F
    private const val METRES_PER_KM_TENTH = 100.0
    private const val ONE_KM = 1_000
    private const val FIELD_MAX = 0xFFFF
    private val CLOCK = Regex("""\d{1,2}:\d{2}""")

    // Android Auto's NavigationType numbers, by what the dash draws for them.
    private val BY_TYPE: Map<Int, Int> = buildMap {
        for (t in listOf(1, 2, 29, 36)) put(t, DashManeuverGlyphs.STRAIGHT) // depart, name change, merge (no side), straight
        put(3, DashManeuverGlyphs.FORK_LEFT)
        put(4, DashManeuverGlyphs.FORK_RIGHT)
        put(25, DashManeuverGlyphs.FORK_LEFT)
        put(26, DashManeuverGlyphs.FORK_RIGHT)
        // Turns (5..10), ramps on (13..18) and off (21..24): slight, normal, sharp, each left then right.
        val turns = listOf(
            DashManeuverGlyphs.SLIGHT_LEFT, DashManeuverGlyphs.SLIGHT_RIGHT, DashManeuverGlyphs.TURN_LEFT,
            DashManeuverGlyphs.TURN_RIGHT, DashManeuverGlyphs.SHARP_LEFT, DashManeuverGlyphs.SHARP_RIGHT,
        )
        turns.forEachIndexed { i, glyph -> put(5 + i, glyph) }
        turns.forEachIndexed { i, glyph -> put(13 + i, glyph) }
        turns.take(4).forEachIndexed { i, glyph -> put(21 + i, glyph) }
        put(11, DashManeuverGlyphs.UTURN_LEFT)
        put(12, DashManeuverGlyphs.UTURN_RIGHT)
        put(19, DashManeuverGlyphs.UTURN_LEFT)
        put(20, DashManeuverGlyphs.UTURN_RIGHT)
        put(27, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_LEFT))
        put(28, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_RIGHT))
        put(37, FERRY)
        put(38, RAIL)
        put(39, DashManeuverGlyphs.ARRIVE)
        put(40, DashManeuverGlyphs.ARRIVE)
        put(41, DashManeuverGlyphs.ARRIVE_LEFT)
        put(42, DashManeuverGlyphs.ARRIVE_RIGHT)
    }
    private val ROUNDABOUT_CCW = setOf(30, 31, 34, 35)
    private val ROUNDABOUT_CW = setOf(32, 33)

    fun glyphFor(navigationType: Int, roundaboutExit: Int, rerouting: Boolean = false): Int = when {
        rerouting -> DashManeuverGlyphs.RECALCULATING
        navigationType in ROUNDABOUT_CCW -> DashManeuverGlyphs.roundabout(roundaboutExit, clockwise = false)
        navigationType in ROUNDABOUT_CW -> DashManeuverGlyphs.roundabout(roundaboutExit, clockwise = true)
        else -> BY_TYPE[navigationType] ?: DashManeuverGlyphs.HIDDEN_BUBBLE
    }

    /** [meters] as the dash's (value, unit); unknown (negative) is 0 m. */
    fun distance(meters: Int): Pair<Int, Int> = when {
        meters < 0 -> 0 to DashCommands.NAV_UNIT_METERS
        meters < ONE_KM -> meters to DashCommands.NAV_UNIT_METERS
        else -> (meters / METRES_PER_KM_TENTH).roundToInt().coerceAtMost(FIELD_MAX) to DashCommands.NAV_UNIT_KM_TENTHS
    }

    private const val SECONDS_PER_DAY = 86_400L
    private const val JUST_PASSED_S = 120L
    private const val HALF_MINUTE_S = 30L

    /**
     * The time left from the arrival time text ("08:01") and [now]: Waze never says it in seconds. The text is
     * to the minute, so the arrival is taken at the middle of its minute ("08:01" is 08:01:30) and the answer is rounded to whole
     * minutes: from hh:mm:00 the dash said 1 min less than Waze in all 6 checks in use (11 for 12, ..., 1
     * for 2), from the middle it says what Waze says in all 6. An arrival that has just passed is 0, not a day; a text that is no time
     * is null.
     */
    fun remainingFromEta(eta: String, now: java.time.LocalTime): Long? {
        val digits = DashCommands.etaDigits(eta) ?: return null
        val arrival = digits.substring(0, 2).toLong() * 3_600 + digits.substring(2).toLong() * 60 + HALF_MINUTE_S
        val ahead = ((arrival - now.toSecondOfDay()) % SECONDS_PER_DAY + SECONDS_PER_DAY) % SECONDS_PER_DAY
        if (ahead > SECONDS_PER_DAY - JUST_PASSED_S) return 0L
        return (ahead + HALF_MINUTE_S) / 60 * 60
    }

    /**
     * The dash's nav figures for [guidance]; null when there is no guidance. [noNetwork]: the phone has been
     * without a network for a while, which turns the recalculating compass into the lost-signal icon; a real turn is never hidden.
     */
    fun toDash(guidance: NavGuidance, noNetwork: Boolean = false, now: java.time.LocalTime = java.time.LocalTime.now()): DashNav? {
        if (!guidance.active) return null
        val (primary, primaryUnit) = distance(guidance.distanceToManeuverMeters)
        val (total, totalUnit) = distance(guidance.distanceRemainingMeters)
        val glyph = if (guidance.rerouting && noNetwork) {
            DashManeuverGlyphs.SIGNAL_LOST
        } else {
            glyphFor(guidance.maneuverType, guidance.roundaboutExitNumber, guidance.rerouting)
        }
        val next = glyphFor(guidance.nextManeuverType, guidance.nextRoundaboutExit)
            .takeIf { guidance.nextManeuverType >= 0 && DashManeuverGlyphs.isVisible(it) } ?: DashCommands.NEXT_MANEUVER_NONE
        return DashNav(
            glyph,
            primary, primaryUnit, total, totalUnit,
            guidance.estimatedTimeAtArrival.takeIf { CLOCK.matches(it) },
            guidance.road,
            guidance.timeToArrivalSeconds.takeIf { it >= 0 } ?: remainingFromEta(guidance.estimatedTimeAtArrival, now) ?: DashCommands.REMAINING_OMIT,
            next,
        )
    }
}
