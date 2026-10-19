package com.reau.app.dash.nav

/** Which side of the road traffic drives on. Decides how a roundabout circulates and which way an unspecified U-turn is made. */
enum class DrivingSide {
    RIGHT_HAND, LEFT_HAND;

    /** Right-hand traffic circulates a roundabout counter-clockwise, left-hand traffic clockwise. */
    val roundaboutClockwise: Boolean get() = this == LEFT_HAND

    companion object {
        /** Countries that drive on the left (ISO 3166 alpha-2). Everything else, and anything unknown, is right-hand traffic. */
        private val LEFT = setOf(
            "GB", "IE", "IM", "JE", "GG", "MT", "CY", "IN", "PK", "BD", "LK", "NP", "BT", "MV", "JP", "TH", "MY", "SG", "BN", "ID", "TL",
            "HK", "MO", "AU", "NZ", "PG", "FJ", "WS", "TO", "SB", "KI", "TV", "NR", "ZA", "NA", "BW", "ZW", "ZM", "MZ", "MW", "KE", "TZ", "UG",
            "SZ", "LS", "MU", "SC", "GY", "SR", "JM", "BB", "BS", "TT", "AG", "DM", "GD", "KN", "LC", "VC", "FK", "SH", "BM", "AI", "VG", "KY",
            "MS", "TC",
        )

        fun forCountry(iso2: String?): DrivingSide = if (iso2 != null && iso2.uppercase() in LEFT) LEFT_HAND else RIGHT_HAND
    }
}

/**
 * The byte the dash's nav bubble is told to draw (`05 02 00 01 XX`), and what every byte draws.
 *
 * Source: the glyph table observed on the dash: every byte 0x00..0xFF was sent to a real K1G dash and
 * each visible glyph was checked by eye. The repository holds only the table of names and descriptions
 * (no images or text from third parties).
 * NOT yet confirmed on a real dash: a debug card ([testSequence]) is the tool for it.
 *
 * The old placeholder 0x0B is NOT "continue": it draws roundabout exit 1. Straight ahead is 0x09.
 */
object DashManeuverGlyphs {

    const val STRAIGHT = 0x09
    const val TURN_LEFT = 0x14
    const val TURN_RIGHT = 0x15
    const val SHARP_LEFT = 0x16
    const val SHARP_RIGHT = 0x17
    const val SLIGHT_LEFT = 0x18
    const val SLIGHT_RIGHT = 0x19
    const val UTURN_RIGHT = 0x1A
    const val UTURN_LEFT = 0x3D
    const val MERGE_FROM_LEFT = 0x03      // Y-merge: your road joins the main one from the left (you move right)
    const val MERGE_FROM_RIGHT = 0x04     // Y-merge: your road joins from the right (you move left)
    const val FORK_RIGHT = 0x05
    const val FORK_LEFT = 0x06
    const val ARRIVE = 0x00
    const val ARRIVE_LEFT = 0x01
    const val ARRIVE_RIGHT = 0x02

    /** Spinning compass: the route is being recalculated. */
    const val RECALCULATING = 0x1C

    /** The bubble's "signal lost" icon (0x42): recalculating with no network. */
    const val SIGNAL_LOST = 0x42

    /** Any byte from 0x5A up hides the bubble completely (field-verified byte by byte): "no maneuver". */
    const val HIDDEN_BUBBLE = com.reau.app.dash.protocol.DashCommands.NAV_MANEUVER_NONE

    /** Bytes inside 0x00..0x59 that draw an empty bubble (placeholders). */
    val EMPTY_BUBBLES = setOf(0x2B, 0x2C, 0x41, 0x43, 0x44, 0x45)

    private const val LAST_VISIBLE = 0x59
    private const val MAX_EXIT = 19
    private const val CCW_LOW = 0x0A          // exits 0..9, counter-clockwise
    private const val CCW_HIGH = 0x50         // exits 10..19, counter-clockwise
    private const val CW_LOW = 0x31           // exits 0..9, clockwise
    private const val CW_HIGH = 0x46          // exits 10..19, clockwise
    private const val TEN = 10

    /**
     * The byte for one maneuver.
     *
     * - [roundaboutExit] is the number in the circle (1 = first exit, as the routers count); missing draws the plain circle (0).
     * - [uTurnRight] says which side a U-turn is made when the router knows (BRouter does, OSRM does not); otherwise it follows
     *   [drivingSide] (via the left in right-hand traffic).
     * - [ManeuverType.MERGE_LEFT]/[ManeuverType.MERGE_RIGHT] name the side the rider MOVES to, so "merge right" is the Y-merge
     *   whose road joins from the LEFT (0x03) and "merge left" the one that joins from the RIGHT (0x04).
     */
    @Suppress("CyclomaticComplexMethod")   // a plain lookup: one branch per maneuver type, nothing else
    fun codeFor(
        type: ManeuverType,
        roundaboutExit: Int? = null,
        uTurnRight: Boolean? = null,
        drivingSide: DrivingSide = DrivingSide.RIGHT_HAND,
    ): Int = when (type) {
        ManeuverType.CONTINUE, ManeuverType.DEPART -> STRAIGHT
        ManeuverType.TURN_LEFT -> TURN_LEFT
        ManeuverType.TURN_RIGHT -> TURN_RIGHT
        ManeuverType.SLIGHT_LEFT -> SLIGHT_LEFT
        ManeuverType.SLIGHT_RIGHT -> SLIGHT_RIGHT
        ManeuverType.MERGE_LEFT -> MERGE_FROM_RIGHT
        ManeuverType.MERGE_RIGHT -> MERGE_FROM_LEFT
        ManeuverType.SHARP_LEFT -> SHARP_LEFT
        ManeuverType.SHARP_RIGHT -> SHARP_RIGHT
        ManeuverType.KEEP_LEFT -> FORK_LEFT
        ManeuverType.KEEP_RIGHT -> FORK_RIGHT
        ManeuverType.UTURN -> if (uTurnRight ?: (drivingSide == DrivingSide.LEFT_HAND)) UTURN_RIGHT else UTURN_LEFT
        ManeuverType.ROUNDABOUT -> roundabout(roundaboutExit ?: 0, drivingSide.roundaboutClockwise)
        ManeuverType.ARRIVE -> ARRIVE
        ManeuverType.ARRIVE_LEFT -> ARRIVE_LEFT
        ManeuverType.ARRIVE_RIGHT -> ARRIVE_RIGHT
    }

    /** Roundabout glyph: [exit] 0..19 (clamped), counter-clockwise 0x0A..0x13 / 0x50..0x59, clockwise 0x31..0x3A / 0x46..0x4F. */
    fun roundabout(exit: Int, clockwise: Boolean): Int {
        val n = exit.coerceIn(0, MAX_EXIT)
        val (low, high) = if (clockwise) CW_LOW to CW_HIGH else CCW_LOW to CCW_HIGH
        return if (n < TEN) low + n else high + (n - TEN)
    }

    /** True when [code] draws something in the bubble (0x00..0x59 except the empty placeholders). */
    fun isVisible(code: Int): Boolean = code in 0x00..LAST_VISIBLE && code !in EMPTY_BUBBLES

    /** What the bubble draws for [code] (English, for the debug card and the docs), or null when it draws nothing. */
    fun describe(code: Int): String? = if (isVisible(code)) NAMES[code] else null

    /** [describe] in Portuguese, for the rider (the debug card shows both). */
    fun describePt(code: Int): String? = if (isVisible(code)) NAMES_PT[code] else null

    /** The rider-facing (Portuguese) name of each maneuver of the app. */
    private val TYPE_PT: Map<ManeuverType, String> = mapOf(
        ManeuverType.CONTINUE to "Continuar em frente",
        ManeuverType.DEPART to "Partida",
        ManeuverType.TURN_LEFT to "Virar à esquerda",
        ManeuverType.TURN_RIGHT to "Virar à direita",
        ManeuverType.SLIGHT_LEFT to "Curva suave à esquerda",
        ManeuverType.SLIGHT_RIGHT to "Curva suave à direita",
        ManeuverType.SHARP_LEFT to "Curva fechada à esquerda",
        ManeuverType.SHARP_RIGHT to "Curva fechada à direita",
        ManeuverType.UTURN to "Retorno pela esquerda",
        ManeuverType.ROUNDABOUT to "Rotatória",
        ManeuverType.ARRIVE to "Chegada",
        ManeuverType.ARRIVE_LEFT to "Chegada, à esquerda",
        ManeuverType.ARRIVE_RIGHT to "Chegada, à direita",
        ManeuverType.MERGE_LEFT to "Entrar na pista pela esquerda",
        ManeuverType.MERGE_RIGHT to "Entrar na pista pela direita",
        ManeuverType.KEEP_LEFT to "Bifurcação: seguir à esquerda",
        ManeuverType.KEEP_RIGHT to "Bifurcação: seguir à direita",
    )

    /**
     * One shortcut per maneuver of the app, for the debug card: (label, the byte the app sends for it). Tapping one shows and sends that
     * byte, so the mapping app maneuver -> byte -> picture can be checked one row at a time against the real panel.
     */
    val typeShortcuts: List<Shortcut> =
        ManeuverType.entries.filter { it != ManeuverType.ROUNDABOUT }.map { Shortcut(it.name, TYPE_PT.getValue(it), codeFor(it)) } +
            (1..3).map { Shortcut("ROUNDABOUT exit $it", "Rotatória, saída $it", codeFor(ManeuverType.ROUNDABOUT, roundaboutExit = it)) } +
            listOf(
                Shortcut("UTURN via the right", "Retorno pela direita", codeFor(ManeuverType.UTURN, uTurnRight = true)),
                Shortcut("Recalculating", "Recalculando", RECALCULATING),
                Shortcut("No maneuver (hidden)", "Sem manobra (esconde a bolha)", HIDDEN_BUBBLE),
            )

    /** One shortcut of the debug card: [id] is the app's own name for the maneuver, [labelPt] what the rider reads, [code] the byte sent. */
    data class Shortcut(val id: String, val labelPt: String, val code: Int)


    private val NAMES: Map<Int, String> = buildMap {
        put(0x00, "Arrive: destination straight ahead")
        put(0x01, "Arrive: destination ahead, on the left")
        put(0x02, "Arrive: destination ahead, on the right")
        put(0x03, "Y-merge: your road joins from the left")
        put(0x04, "Y-merge: your road joins from the right")
        put(0x05, "Y-fork: stay right")
        put(0x06, "Y-fork: stay left")
        put(0x07, "Side road branches off to the right")
        put(0x08, "Side road branches off to the left")
        put(0x09, "Continue straight")
        put(0x14, "Turn left")
        put(0x15, "Turn right")
        put(0x16, "Sharp left")
        put(0x17, "Sharp right")
        put(0x18, "Slight left")
        put(0x19, "Slight right")
        put(0x1A, "U-turn via the right")
        put(0x1B, "Y-fork: continue straight")
        put(0x1C, "Recalculating route (spinning compass)")
        for (b in 0x1D..0x20) put(b, "Y-fork: continue straight (variant)")
        put(0x21, "Y-merge: joins from the left (same as 0x03)")
        put(0x22, "Y-merge: joins from the right (same as 0x04)")
        put(0x23, "Side road joins from the left, shallow angle")
        put(0x24, "Side road joins from the right, shallow angle")
        put(0x25, "Side road joins from the left, steeper angle")
        put(0x26, "Side road joins from the right, steeper angle")
        put(0x27, "Exit right, gentle ramp")
        put(0x28, "Exit left, gentle ramp")
        put(0x29, "Turn off to the right, sharper")
        put(0x2A, "Turn off to the left, sharper")
        put(0x2D, "Slight exit right")
        put(0x2E, "Slight exit left")
        put(0x2F, "Exit right, sharper")
        put(0x30, "Exit left, sharper")
        put(0x3B, "Continue straight (long distance)")
        put(0x3C, "Arrival pin only")
        put(0x3D, "U-turn via the left")
        put(0x3E, "Ferry crossing")
        put(0x3F, "Railway crossing")
        put(0x40, "Arrival approaching")
        put(0x42, "Signal lost (not a maneuver)")
        for (n in 0..MAX_EXIT) {
            put(roundabout(n, clockwise = false), "Roundabout counter-clockwise, exit $n")
            put(roundabout(n, clockwise = true), "Roundabout clockwise, exit $n")
        }
    }

    private val NAMES_PT: Map<Int, String> = buildMap {
        put(0x00, "Chegada: destino em frente")
        put(0x01, "Chegada: destino em frente, à esquerda")
        put(0x02, "Chegada: destino em frente, à direita")
        put(0x03, "Junção em Y: sua via entra pela esquerda")
        put(0x04, "Junção em Y: sua via entra pela direita")
        put(0x05, "Bifurcação em Y: seguir à direita")
        put(0x06, "Bifurcação em Y: seguir à esquerda")
        put(0x07, "Via lateral sai para a direita")
        put(0x08, "Via lateral sai para a esquerda")
        put(0x09, "Continuar em frente")
        put(0x14, "Virar à esquerda")
        put(0x15, "Virar à direita")
        put(0x16, "Curva fechada à esquerda")
        put(0x17, "Curva fechada à direita")
        put(0x18, "Curva suave à esquerda")
        put(0x19, "Curva suave à direita")
        put(0x1A, "Retorno pela direita")
        put(0x1B, "Bifurcação em Y: seguir em frente")
        put(0x1C, "Recalculando a rota (bússola girando)")
        for (b in 0x1D..0x20) put(b, "Bifurcação em Y: seguir em frente (variante)")
        put(0x21, "Junção em Y: entra pela esquerda (igual ao 0x03)")
        put(0x22, "Junção em Y: entra pela direita (igual ao 0x04)")
        put(0x23, "Via lateral entra pela esquerda, ângulo suave")
        put(0x24, "Via lateral entra pela direita, ângulo suave")
        put(0x25, "Via lateral entra pela esquerda, ângulo maior")
        put(0x26, "Via lateral entra pela direita, ângulo maior")
        put(0x27, "Saída à direita, rampa suave")
        put(0x28, "Saída à esquerda, rampa suave")
        put(0x29, "Desvio à direita, mais fechado")
        put(0x2A, "Desvio à esquerda, mais fechado")
        put(0x2D, "Saída suave à direita")
        put(0x2E, "Saída suave à esquerda")
        put(0x2F, "Saída à direita, mais fechada")
        put(0x30, "Saída à esquerda, mais fechada")
        put(0x3B, "Continuar em frente (longa distância)")
        put(0x3C, "Só o alfinete de chegada")
        put(0x3D, "Retorno pela esquerda")
        put(0x3E, "Travessia de balsa")
        put(0x3F, "Passagem de trem")
        put(0x40, "Chegada se aproximando")
        put(0x42, "Sinal perdido (não é manobra)")
        for (n in 0..MAX_EXIT) {
            put(roundabout(n, clockwise = false), "Rotatória anti-horária, saída $n")
            put(roundabout(n, clockwise = true), "Rotatória horária, saída $n")
        }
    }

    /**
     * The bytes worth looking at on the bike, ascending: one per kind of arrow, both sides of each pair, the roundabout
     * boundaries, recalculating, the hidden bubble, and the two the app used to send (0x0B) or leaves out (Y-merge 0x03/0x04).
     * The debug card jumps through this list; each entry has a [describe] text to compare with what the panel really draws.
     */
    val testSequence: List<Int> = listOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x09, 0x0A, 0x0B, 0x0C, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A,
        RECALCULATING, 0x27, 0x28, 0x31, 0x32, 0x33, 0x3B, 0x3D, 0x50, HIDDEN_BUBBLE,
    ).sorted().distinct()

    /** The next byte of [testSequence] strictly after [current], wrapping to the first (pure, so the debug button is testable). */
    fun nextCandidate(current: Int): Int = testSequence.firstOrNull { it > current } ?: testSequence.first()
}
