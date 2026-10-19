package com.reau.app.dash

/**
 * What the dash's own words mean, for the screen "Dados da moto": the hex that
 * [DashReadings] keeps turned into things a rider reads. Pure, so a test can feed it the bytes of a real dash.
 *
 * Where the meanings come from: what the dash says by itself, observed on a real dash: on every connection
 * the dash says its settings (group 03), its firmware by partition (`0B02`, answering the `0A02` REau's burst sends) and
 * the variant and safe-mode flag (`0F0B`, `0F04`).
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
object DashReport {
    /** The code of TLV ([type], [sub]) as [DashReadings] keys it: `"0303"`. */
    fun key(type: Int, sub: Int): String = "%02X%02X".format(type and 0xFF, sub and 0xFF)

    // ── the dash's own settings (group 03, said on every connection) ────────────────────────────────────────────────

    enum class TempUnit { CELSIUS, FAHRENHEIT }
    enum class DistanceUnit { KM, MILES }

    /** The dash's theme: automatic or always dark; the dash has no light-only choice. */
    enum class Theme { AUTO, DARK }
    enum class ConsumptionUnit { KM_PER_L, L_PER_100KM, MPG_UK, MPG_US }
    enum class ClockFormat { H24, H12 }
    enum class Language(val code: Int) { EN(1), IT(2), ES(3), PT(4), DE(5), FR(6) }

    /**
     * The settings the dash reported. Each is null until the dash has said it. [notifications] (messages, incoming call,
     * weather alerts, context alerts, vehicle alerts) and [pois] (fuel, restaurants, service workshop, parking) are on
     * or off in that order.
     */
    data class Settings(
        val temperature: TempUnit? = null,
        val distance: DistanceUnit? = null,
        val theme: Theme? = null,
        val consumption: ConsumptionUnit? = null,
        val clock: ClockFormat? = null,
        val language: Language? = null,
        val notifications: List<Boolean>? = null,
        val pois: List<Boolean>? = null,
    )

    @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
    fun settings(all: Map<String, DashReading>): Settings = Settings(
        temperature = all["0303"]?.hex?.let { if (it == "55") TempUnit.CELSIUS else if (it == "AA") TempUnit.FAHRENHEIT else null },
        distance = all["0304"]?.hex?.let { if (it == "55") DistanceUnit.KM else if (it == "AA") DistanceUnit.MILES else null },
        theme = all["0305"]?.hex?.let(::theme),
        consumption = all["0306"]?.hex?.let {
            when (it) {
                "10" -> ConsumptionUnit.KM_PER_L
                "20" -> ConsumptionUnit.L_PER_100KM
                "30" -> ConsumptionUnit.MPG_UK
                "40" -> ConsumptionUnit.MPG_US
                else -> null
            }
        },
        clock = all["0308"]?.hex?.let { if (it == "55") ClockFormat.H24 else if (it == "AA") ClockFormat.H12 else null },
        language = all["0309"]?.hex?.toIntOrNull(16)?.let { code -> Language.entries.firstOrNull { it.code == code } },
        notifications = all["030A"]?.hex?.let { switches(it, 5) },
        pois = all["030B"]?.hex?.let { switches(it, 4) },
    )

    /** The theme the dash says: `55` automatic, `AA` dark (to be confirmed on the bike). */
    fun theme(hex: String): Theme? = when (hex) {
        "55" -> Theme.AUTO
        "AA" -> Theme.DARK
        else -> null
    }

    /** [count] bytes of `AA` (on) and `55` (off); null when the value is not that. */
    internal fun switches(hex: String, count: Int): List<Boolean>? {
        if (hex.length != count * 2) return null
        val bytes = hex.chunked(2)
        if (bytes.any { it != "AA" && it != "55" }) return null
        return bytes.map { it == "AA" }
    }

    // ── versions and identity ───────────────────────────────────────────────────────────────────────────────────────

    /** One partition of the dash's firmware as `0B02` lists them: block `BB`, version `MM.mm`. */
    data class Partition(val block: Int, val major: Int, val minor: Int) {
        val version: String get() = "%d.%02d".format(major, minor)
    }

    /**
     * The firmware partitions the dash answers `0A02` with: a status byte (`AA` good), a byte, then the ASCII
     * `BB.MM.mm` of each partition, 8 characters each ("00.02.07" is block 0, version 2.07), then zeros.
     */
    fun partitions(hex: String?): List<Partition> {
        val bytes = bytesOf(hex ?: return emptyList())
        if (bytes.size <= 2) return emptyList()
        val text = bytes.drop(2).takeWhile { it.toInt() != 0 }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        return PARTITION.findAll(text).map {
            val (block, major, minor) = it.destructured
            Partition(block.toInt(), major.toInt(), minor.toInt())
        }.toList()
    }

    private val PARTITION = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{2})")

    /** `0F04`: `55` normal, `AA` the dash is in its recovery ("safe") mode; null when it said neither. */
    fun safeMode(hex: String?): Boolean? = when (hex) {
        "55" -> false
        "AA" -> true
        else -> null
    }

    /** The decrypted text of a 0F value (ASCII or UTF-8 up to the first zero), or null when empty. */
    fun text(hex: String?): String? {
        val bytes = bytesOf(hex ?: return null).takeWhile { it.toInt() != 0 }.toByteArray()
        return String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
    }

    // ── the bike's state (group 0C, said when it changes) ───────────────────────────────────────────────────────────

    /** `0C14`: `00`/`01` Preferred-ABS on/off, `02`/`03` Eco-ABS on/off; null for anything else. */
    data class Abs(val mode: String, val on: Boolean)

    fun abs(hex: String?): Abs? = when (hex) {
        "00" -> Abs("Preferred", true)
        "01" -> Abs("Preferred", false)
        "02" -> Abs("Eco", true)
        "03" -> Abs("Eco", false)
        else -> null
    }

    /** `0C05`, low-fuel reminder: `55` on, `AA` off (reversed from the masks); null otherwise. */
    fun lowFuel(hex: String?): Boolean? = when (hex) {
        "55" -> true
        "AA" -> false
        else -> null
    }

    /**
     * The codes the screen "Dados da moto" shows (what never came, or nobody knows what it is, is left out): the dash's
     * settings, the firmware parts, the variant, the safe mode, the ABS, the low-fuel reminder and the range.
     * [DashReadings] keeps only these; the rest of what the dash says (the identity, the unnamed `0C` codes) is not kept.
     */
    fun isShown(key: String): Boolean = key in SHOWN

    private val SHOWN = setOf("0303", "0304", "0305", "0306", "0308", "0309", "030A", "030B", "0B02", "0F04", "0F0B", "0C05", "0C07", "0C14")

    // ── the range (group 0C, said every ~5 s) ───────────────────────────────────────────────────────────────────────

    /**
     * `0C07`: the range, the distance the bike still goes on the fuel it has, as the dash itself counts it. Two bytes,
     * most significant first (`004C` is 76), in kilometres: seen on a real dash, where it only falls, one by one,
     * and jumps up at the fuel stops; confirmed by use as the range. Null when there is no reading
     * or the value is not two bytes of hex; no number is ever made up.
     */
    fun rangeKm(hex: String?): Int? {
        if (hex == null || !TWO_BYTES.matches(hex)) return null
        return hex.toInt(16)
    }

    /** The range in kilometres [km] as the rider reads it, in the unit the dash is set to ([unit], null: not said yet). */
    fun rangeText(km: Int, unit: DistanceUnit?): String =
        if (unit == DistanceUnit.MILES) "${Math.round(km * MILES_PER_KM)} mi ($km km)" else "$km km"

    private const val MILES_PER_KM = 0.621371

    private val TWO_BYTES = Regex("[0-9A-Fa-f]{4}")

    private fun bytesOf(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
