package com.reau.app.web

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The web server's PIN (four random digits, easier to type than a long token). It is new
 * every time the server comes up and only lives in memory. Four digits are 10,000 guesses, so the guard counts the wrong ones:
 * [Limits.maxPerAddress] wrong from one address lock that address for [Limits.lockMs] (the right PIN is no key while locked), and
 * [Limits.rotateAfter] wrong in all change the PIN (and let every address go), so a guesser never gets to the end of the list.
 * The attempts of a locked address are not counted again. The new PIN is told to [onRotated] (never to a log).
 */
class WebGuard(
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val draw: () -> Int = systemDraw,
    private val onRotated: (String) -> Unit = {},
) {
    class Limits(val maxPerAddress: Int = 5, val lockMs: Long = 300_000L, val rotateAfter: Int = 30)

    /** What the server answers to each: no body, only the status. */
    enum class Verdict(val status: Int) { OK(204), WRONG(403), LOCKED(429) }

    private val lock = Any()
    private val failures = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    private var total = 0

    /** After a change forced by wrong guesses, nobody gets in until this time: a guesser with many addresses must not start fresh. */
    private var heldUntil = 0L

    @Volatile var pin: String = format(draw())
        private set

    /** The verdict on [given] from [address]. */
    fun check(address: String, given: String?): Verdict {
        var changedTo: String? = null
        val verdict = synchronized(lock) {
            when {
                isHeld(address) -> Verdict.LOCKED
                matches(given) -> Verdict.OK.also { failures.remove(address) }
                else -> countWrong(address).also { if (total >= limits.rotateAfter) changedTo = rotate(hold = true) }
            }
        }
        changedTo?.let(onRotated)
        return verdict
    }

    /** Whether [address] cannot try now: the whole guard is held after a change, or the address is locked (a lock that ended is forgotten). */
    private fun isHeld(address: String): Boolean {
        val held = clock() < heldUntil || lockedUntil[address]?.let { clock() < it } == true
        if (!held) lockedUntil.remove(address)
        return held
    }

    private fun matches(given: String?): Boolean =
        given != null && given.length == DIGITS && MessageDigest.isEqual(pin.toByteArray(), given.toByteArray())

    /** One more wrong from [address]: the count, and the lock when it reaches the limit. */
    private fun countWrong(address: String): Verdict {
        total++
        val count = (failures[address] ?: 0) + 1
        if (count < limits.maxPerAddress) {
            failures[address] = count
            return Verdict.WRONG
        }
        failures.remove(address)
        lockedUntil[address] = clock() + limits.lockMs
        return Verdict.LOCKED
    }

    /** A new PIN right now, with no hold (the rider's "Derrubar todos": whoever was in must type it again). */
    fun renew(): String = synchronized(lock) { rotate(hold = false) }

    private fun rotate(hold: Boolean): String {
        var next: String
        do {
            next = format(draw())
        } while (next == pin)
        pin = next
        total = 0
        failures.clear()
        if (hold) heldUntil = clock() + limits.lockMs
        return next
    }

    companion object {
        private const val DIGITS = 4
        private const val PINS = 10_000
        private val random = SecureRandom()

        /** The draw the app uses: any of the 10,000 PINs from the system's secure random. */
        val systemDraw: () -> Int = { random.nextInt(PINS) }

        /** [number] (0 to 9999) as four digits, with the zeros in front. */
        fun format(number: Int): String = "%04d".format(number)
    }
}
