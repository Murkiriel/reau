package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A change that needs a new Android Auto session waits one second with no other change before the session is opened.
 * Without the wait, four density changes within a second opened three sessions, each a reconnection of Android Auto.
 */
class SessionSettleTest {
    /** A clock in the test's hand: actions due at a time run when it gets there. */
    private class Clock {
        var now = 0L
        private val due = mutableListOf<Pair<Long, () -> Unit>>()

        fun later(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = (now + delayMs) to action
            due += entry
            return { due.remove(entry) }
        }

        fun advanceTo(ms: Long) {
            now = ms
            while (true) {
                val next = due.filter { it.first <= now }.minByOrNull { it.first } ?: return
                due.remove(next)
                next.second()
            }
        }
    }

    private val clock = Clock()
    private val opened = mutableListOf<Int>()
    private val settle = SessionSettle(later = clock::later)

    private fun change(at: Long, value: Int) {
        clock.advanceTo(at)
        settle.offer { opened += value }
    }

    @Test
    fun `four changes within a second open one session, with the last values`() {
        change(0, 1)
        change(300, 2)
        change(600, 3)
        change(900, 4)
        clock.advanceTo(1_899)
        assertEquals(emptyList<Int>(), opened)
        clock.advanceTo(1_900)
        assertEquals(listOf(4), opened)
        clock.advanceTo(10_000)
        assertEquals(listOf(4), opened)
    }

    @Test
    fun `one change opens one session a second later`() {
        change(0, 1)
        clock.advanceTo(999)
        assertEquals(emptyList<Int>(), opened)
        clock.advanceTo(1_000)
        assertEquals(listOf(1), opened)
    }

    @Test
    fun `changes more than a second apart open one session each`() {
        change(0, 1)
        change(1_500, 2)
        clock.advanceTo(5_000)
        assertEquals(listOf(1, 2), opened)
    }

    @Test
    fun `the wait is one second`() {
        assertEquals(1_000L, SessionSettle.WAIT_MS)
    }
}
