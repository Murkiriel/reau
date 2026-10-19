package com.reau.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web server's PIN: four random digits, to be easy to type. Four digits are
 * only 10,000 guesses, so the guard also counts the wrong ones: five wrong from one address lock that address for five
 * minutes, and thirty wrong in all change the PIN, so a guesser never gets to the end of the list.
 */
class WebGuardTest {
    private var now = 0L
    private val rotated = mutableListOf<String>()

    /** A guard whose draws are [draws] in turn (the first is its first pin) and whose clock is [now]. */
    private fun guard(limits: WebGuard.Limits = WebGuard.Limits(), vararg draws: Int): WebGuard {
        val queue = ArrayDeque((if (draws.isEmpty()) intArrayOf(4821, 1111, 2222, 3333, 4444, 5555) else draws).toList())
        return WebGuard(limits, { now }, { queue.removeFirst() }) { rotated += it }
    }

    @Test
    fun `the pin is four digits, with the zeros in front`() {
        assertEquals("4821", WebGuard.format(4821))
        assertEquals("0007", WebGuard.format(7))
        assertEquals("0000", WebGuard.format(0))
        assertEquals("9999", WebGuard.format(9999))
        assertEquals("4821", guard().pin)
        val real = WebGuard()
        assertTrue(real.pin, real.pin.length == 4 && real.pin.all { it in '0'..'9' })
    }

    @Test
    fun `real guards do not all start with the same pin`() {
        val pins = (1..40).map { WebGuard().pin }.toSet()
        assertTrue("40 draws gave ${pins.size} different pins", pins.size > 10)
    }

    @Test
    fun `the right pin is ok, anything else is wrong`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 99, rotateAfter = 999))
        assertEquals(WebGuard.Verdict.OK, g.check("a", "4821"))
        for (bad in listOf("4822", "", "482", "48210", "abcd", " 4821", "4821 ", "04821")) {
            assertEquals(bad, WebGuard.Verdict.WRONG, g.check("a", bad))
        }
        assertEquals(WebGuard.Verdict.WRONG, g.check("b", null))
    }

    @Test
    fun `the fifth wrong pin from one address locks it, and the right one does not get through while locked`() {
        val g = guard()
        repeat(4) { assertEquals(WebGuard.Verdict.WRONG, g.check("a", "0000")) }
        assertEquals(WebGuard.Verdict.LOCKED, g.check("a", "0000"))
        assertEquals("the right pin is no key while locked", WebGuard.Verdict.LOCKED, g.check("a", "4821"))
        assertEquals("another address is not held", WebGuard.Verdict.OK, g.check("b", "4821"))
    }

    @Test
    fun `the lock ends after five minutes and then the right pin works`() {
        val g = guard()
        repeat(5) { g.check("a", "0000") }
        now += 299_999
        assertEquals(WebGuard.Verdict.LOCKED, g.check("a", "4821"))
        now += 1
        assertEquals(WebGuard.Verdict.OK, g.check("a", "4821"))
    }

    @Test
    fun `wrong pins already counted do not outlive the right one`() {
        val g = guard()
        repeat(4) { g.check("a", "0000") }
        assertEquals(WebGuard.Verdict.OK, g.check("a", "4821"))
        repeat(4) { assertEquals(WebGuard.Verdict.WRONG, g.check("a", "0000")) }
    }

    @Test
    fun `the tries of a locked address are not counted again`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 2, rotateAfter = 5))
        g.check("a", "0000")
        assertEquals(WebGuard.Verdict.LOCKED, g.check("a", "0000"))
        repeat(20) { g.check("a", "0000") }
        assertTrue("the pin did not change for tries that were not made", rotated.isEmpty())
    }

    @Test
    fun `enough wrong pins in all change the pin, and the old one stops working`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 3, rotateAfter = 6))
        val old = g.pin
        for (address in listOf("a", "b")) repeat(3) { g.check(address, "0000") }
        assertEquals(1, rotated.size)
        assertNotEquals(old, g.pin)
        assertEquals(g.pin, rotated.single())
        assertEquals("held for the lock time, so a new address does not start fresh", WebGuard.Verdict.LOCKED, g.check("c", old))
    }

    @Test
    fun `after a change everyone is held for the lock time, the new pin included, and then it works`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 3, rotateAfter = 6))
        for (address in listOf("a", "b")) repeat(3) { g.check(address, "0000") }
        assertEquals(WebGuard.Verdict.LOCKED, g.check("a", g.pin))
        assertEquals(WebGuard.Verdict.LOCKED, g.check("somebody new", g.pin))
        now += 299_999
        assertEquals(WebGuard.Verdict.LOCKED, g.check("a", g.pin))
        now += 1
        assertEquals(WebGuard.Verdict.OK, g.check("a", g.pin))
    }

    @Test
    fun `a guesser who changes address all the time still gets thirty tries, then five minutes of nothing, and a new pin`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 5, rotateAfter = 30))
        val first = g.pin
        var wrong = 0
        for (i in 0 until 30) if (g.check("address $i", "0000") == WebGuard.Verdict.WRONG) wrong++
        assertEquals("one try each address, none of them locked", 30, wrong)
        assertEquals(1, rotated.size)
        assertNotEquals(first, g.pin)
        for (i in 0 until 100) assertEquals(WebGuard.Verdict.LOCKED, g.check("another $i", g.pin))
    }

    @Test
    fun `an address locked before a change stays locked after it`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 2, rotateAfter = 3, lockMs = 1_000))
        g.check("a", "0000")
        g.check("a", "0000")
        g.check("b", "0000")
        assertEquals(1, rotated.size)
        now += 1_000
        assertEquals("the global hold is over, but a's own lock ends with its own time", WebGuard.Verdict.OK, g.check("b", g.pin))
        assertEquals(WebGuard.Verdict.OK, g.check("a", g.pin))
    }

    @Test
    fun `renew changes the pin at once, with no hold and no count`() {
        val g = guard()
        val old = g.pin
        val next = g.renew()
        assertEquals(next, g.pin)
        assertNotEquals(old, next)
        assertEquals(WebGuard.Verdict.WRONG, g.check("a", old))
        assertEquals(WebGuard.Verdict.OK, g.check("a", next))
    }

    @Test
    fun `the new pin is never the old one`() {
        val g = guard(WebGuard.Limits(maxPerAddress = 9, rotateAfter = 1), 4821, 4821, 4821, 77)
        assertEquals("4821", g.pin)
        g.check("a", "0000")
        assertEquals("0077", g.pin)
    }

    @Test
    fun `the limits of the app are five, five minutes and thirty`() {
        val l = WebGuard.Limits()
        assertEquals(5, l.maxPerAddress)
        assertEquals(300_000L, l.lockMs)
        assertEquals(30, l.rotateAfter)
    }
}
