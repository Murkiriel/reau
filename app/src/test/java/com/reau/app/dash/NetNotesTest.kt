package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Wi-Fi manager keeps of the network that is up and of the channel of the one it lost ([NetNotes]; it was three fields and a
 * function of the manager until 14/11/2026, which only an instrumented test reached).
 */
class NetNotesTest {
    private fun read(mhz: Int? = 2447, rssi: Int? = -50, specifier: Boolean? = true, validated: Boolean = false, notRestricted: Boolean = true) =
        CapsRead(mhz, rssi, specifier, validated, notRestricted)

    // ── What is kept of each snapshot ──────────────────────────────────────────────────────────────

    @Test
    fun `no snapshot changes nothing of what was known, and the first one only marks when the network came up`() {
        val notes = NetNotes()
        notes.note(null, nowMs = 1_000L)
        assertEquals(NetFacts(upAtMs = 1_000L), notes.facts)
        notes.note(read(), nowMs = 2_000L)
        val known = notes.facts
        assertEquals(NetFacts(upAtMs = 1_000L, mhz = 2447, rssiDbm = -50, specifier = true, validated = false, notRestricted = true), known)
        notes.note(null, nowMs = 3_000L)
        assertEquals(known, notes.facts)
    }

    @Test
    fun `a channel is worth keeping only above zero`() {
        val notes = NetNotes()
        notes.note(read(mhz = 0), nowMs = 0L)
        assertNull("Android's 'no channel' is not a channel", notes.facts?.mhz)
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.note(read(mhz = -1), nowMs = 0L)
        assertEquals("the one known before stays", 2447, notes.facts?.mhz)
        notes.note(read(mhz = null), nowMs = 0L)
        assertEquals(2447, notes.facts?.mhz)
    }

    @Test
    fun `a signal is worth keeping only above Android's 'no reading'`() {
        val notes = NetNotes()
        notes.note(read(rssi = -127), nowMs = 0L)
        assertNull(notes.facts?.rssiDbm)
        notes.note(read(rssi = -126), nowMs = 0L)
        assertEquals(-126, notes.facts?.rssiDbm)
        notes.note(read(rssi = -127), nowMs = 0L)
        assertEquals("the one known before stays", -126, notes.facts?.rssiDbm)
    }

    @Test
    fun `a snapshot that does not say whether the request named the network keeps what was said before`() {
        val notes = NetNotes()
        notes.note(read(specifier = null), nowMs = 0L)
        assertNull(notes.facts?.specifier)
        notes.note(read(specifier = true), nowMs = 0L)
        notes.note(read(specifier = null, validated = true, notRestricted = false), nowMs = 0L)
        assertEquals(true, notes.facts?.specifier)
        assertEquals("the flags of the snapshot are the ones kept", true, notes.facts?.validated)
        assertEquals(false, notes.facts?.notRestricted)
        notes.note(read(specifier = false), nowMs = 0L)
        assertEquals(false, notes.facts?.specifier)
    }

    // ── The channel the dash comes back on ─────────────────────────────────────────────────────────

    @Test
    fun `the first connection of a projection says nothing of the channel`() {
        val notes = NetNotes()
        assertNull(notes.note(read(mhz = 2447), nowMs = 0L))
        assertNull(notes.note(read(mhz = 2427), nowMs = 0L))
    }

    @Test
    fun `after a loss the channel is told once, when it is first known`() {
        val notes = NetNotes()
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.lost()
        assertNull("the facts of the lost network are gone", notes.facts)
        assertNull("nothing to say while the channel is not known", notes.note(read(mhz = 0), nowMs = 10L))
        assertEquals(LinkLoss.backLine(2447, 2427), notes.note(read(mhz = 2427), nowMs = 20L))
        assertNull("told once", notes.note(read(mhz = 2427), nowMs = 30L))
        assertEquals("the network that came back counts from its own start", 10L, notes.facts?.upAtMs)
    }

    @Test
    fun `back on the same channel is said too`() {
        val notes = NetNotes()
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.lost()
        assertEquals("the dash's Wi-Fi is back on the same channel (2447 MHz)", notes.note(read(mhz = 2447), nowMs = 0L))
    }

    @Test
    fun `a loss with the channel unknown keeps the channel of the loss before it`() {
        val notes = NetNotes()
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.lost()
        notes.note(read(mhz = 0), nowMs = 0L)
        notes.lost()
        assertEquals(LinkLoss.backLine(2447, 2427), notes.note(read(mhz = 2427), nowMs = 0L))
    }

    @Test
    fun `a loss before any channel was known has no channel to compare the next one with`() {
        val notes = NetNotes()
        notes.note(read(mhz = 0), nowMs = 0L)
        notes.lost()
        assertNull(notes.note(read(mhz = 2447), nowMs = 0L))
        notes.lost()
        assertEquals("the loss after it has one", LinkLoss.backLine(2447, 2447), notes.note(read(mhz = 2447), nowMs = 0L))
    }

    @Test
    fun `a fresh connect forgets the network and the channel of the one lost`() {
        val notes = NetNotes()
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.lost()
        notes.forget()
        assertNull(notes.facts)
        assertNull("no channel is waited for, and none is remembered", notes.note(read(mhz = 2427), nowMs = 0L))
        notes.note(read(mhz = 2447), nowMs = 0L)
        notes.forget()
        assertNull(notes.facts)
    }

    // ── Whose the network is ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the owner's flags are the ones given, and the ones kept when none is given`() {
        val notes = NetNotes()
        assertEquals(NetworkOwner.words(true, 3, null, null), notes.owner(true, 3, null, null))
        notes.note(read(validated = true, notRestricted = false), nowMs = 0L)
        assertEquals(NetworkOwner.words(false, 4, notRestricted = false, validated = true), notes.owner(false, 4, null, null))
        assertEquals(NetworkOwner.words(false, 4, notRestricted = true, validated = false), notes.owner(false, 4, notRestricted = true, validated = false))
    }
}
