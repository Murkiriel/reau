package com.reau.app.service

/**
 * When the rider is told the phone is hot (plan 22, item 4). Ride log of 01/11/2026: 40 to 42 C for 90 minutes with the charger in; the
 * Android thermal status stayed `none` throughout, because it follows the skin, which is cooler than the battery. In the summer sun, a closed
 * pocket or a tank bag the battery adds 8 to 10 C to that, past the 45 C where Android cuts the charge and the phone slows down.
 *
 * The decision, pure: [offer] is given the battery temperature of each look (every 5 s) and says [Verdict.WARN] once when it reaches [hotC], then
 * nothing until it has fallen under [coolC] ([Verdict.COOLED], which takes the notice away) and gone over [hotC] again. One watch per projection:
 * a new projection is a new object, armed.
 */
class BatteryHeatWatch(private val hotC: Float = HOT_C, private val coolC: Float = COOL_C) {
    enum class Verdict { NONE, WARN, COOLED }

    private var warned = false

    /** The look at [tempC] (null: the battery did not say, which changes nothing). */
    fun offer(tempC: Float?): Verdict {
        tempC ?: return Verdict.NONE
        return when {
            !warned && tempC >= hotC -> {
                warned = true
                Verdict.WARN
            }
            warned && tempC < coolC -> {
                warned = false
                Verdict.COOLED
            }
            else -> Verdict.NONE
        }
    }

    companion object {
        /** The battery temperature the rider is told at (the Android charge cut is near 45 C). */
        const val HOT_C = 44f

        /** The temperature it has to fall under for a new warning to be possible: well under [HOT_C], so 43.9 and 44.0 are not a warning each. */
        const val COOL_C = 41f
    }
}
