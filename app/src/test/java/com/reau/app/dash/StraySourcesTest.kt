package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packets from an address other than the dash's, told once per session and counted at the close (item 9 of the 11/11/2026 list). */
class StraySourcesTest {
    private val dash = "192.168.1.1"

    @Test
    fun `the dash's own address says nothing, however many packets`() {
        val strays = StraySources(dash)
        repeat(100) { assertNull(strays.heard(dash)) }
        assertNull(strays.summary())
    }

    @Test
    fun `the first packet from another address is told with the address, and not filtered`() {
        val strays = StraySources(dash)
        val line = strays.heard("192.168.1.77")!!
        assertTrue(line, "192.168.1.77" in line && dash in line && "not filtered" in line)
    }

    @Test
    fun `the others are not told again, and a single stray packet leaves no summary`() {
        val strays = StraySources(dash)
        strays.heard("10.0.0.5")
        assertNull(strays.heard("10.0.0.5"))
        assertNull(strays.heard("10.0.0.6"))
        val single = StraySources(dash).also { it.heard("10.0.0.5") }
        assertNull(single.summary())
    }

    @Test
    fun `the summary says how many packets from how many addresses, and the first`() {
        val strays = StraySources(dash)
        strays.heard("10.0.0.5")
        strays.heard("10.0.0.6")
        strays.heard("10.0.0.5")
        strays.heard(dash)
        assertEquals(
            "rx from other addresses than the dash's $dash: 3 packets from 2 address(es), first 10.0.0.5, not filtered",
            strays.summary(),
        )
    }

    @Test
    fun `only a few addresses are kept apart, the rest are said to be more`() {
        val strays = StraySources(dash)
        for (n in 1..10) strays.heard("10.0.0.$n")
        val summary = strays.summary()!!
        assertTrue(summary, "10 packets from ${StraySources.MAX_ADDRESSES} address(es) (and others past the first ${StraySources.MAX_ADDRESSES})" in summary)
    }
}
