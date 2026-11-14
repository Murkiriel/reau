package com.reau.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-20 of the independent review of 26/10/2026: the service's `onDestroy` lets go of its sides, its
 * worker and its locks on a thread of its own, up to 5 s after it returns, and Android may build the next instance meanwhile. Both write the same
 * process-wide state. Without an owner, the old instance's late writes -- a stop finishing, the release step of `onDestroy` resetting the state to idle --
 * land over the new instance's projection ([the first test shows it with plain flows]).
 */
class StateOwnerTest {
    private enum class Phase { STOPPED, STARTING, RUNNING }

    /** What the service did before: every instance writes the one flow. */
    @Test
    fun `without an owner the old instance's late reset lands over the new instance's projection`() {
        val state = MutableStateFlow(Phase.RUNNING)
        // The old instance is destroyed while its stop is still going on; a Ligar builds the next instance, which starts at once.
        state.value = Phase.STARTING
        state.value = Phase.RUNNING
        // The old instance's release step runs now ("state" step of onDestroy) and sets the flow back to idle.
        state.value = Phase.STOPPED
        assertEquals("the screen says stopped while the dash is being connected", Phase.STOPPED, state.value)
    }

    @Test
    fun `with the owner the old instance's late writes are dropped once the new one has written`() {
        val owner = StateOwner()
        val state = MutableStateFlow(Phase.STOPPED)
        val old = owner.newInstance()
        assertTrue(owner.publish(old) { state.value = Phase.RUNNING })
        val next = owner.newInstance() // Android builds the next instance while the old one releases
        assertTrue("the new instance's first write goes through", owner.publish(next) { state.value = Phase.STARTING })
        assertTrue(owner.publish(next) { state.value = Phase.RUNNING })

        assertFalse("the old stop finishing", owner.publish(old) { state.value = Phase.STOPPED })
        assertFalse("the release step of onDestroy", owner.publish(old) { state.value = Phase.STOPPED })
        assertEquals(Phase.RUNNING, state.value)
        assertEquals(next, owner.owner())
    }

    @Test
    fun `a new instance that has written nothing yet does not take the state from the old one`() {
        // A service made only to be stopped (a Desligar while none was running) writes nothing; the old instance's stop must still reach the screen.
        val owner = StateOwner()
        val state = MutableStateFlow(Phase.RUNNING)
        val old = owner.newInstance()
        owner.publish(old) { state.value = Phase.RUNNING }
        owner.newInstance()
        assertTrue(owner.publish(old) { state.value = Phase.STOPPED })
        assertEquals(Phase.STOPPED, state.value)
    }

    @Test
    fun `with one instance at a time every write goes through, as before`() {
        val owner = StateOwner()
        val state = MutableStateFlow(Phase.STOPPED)
        val only = owner.newInstance()
        for (phase in listOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPED)) assertTrue(owner.publish(only) { state.value = phase })
        assertEquals(Phase.STOPPED, state.value)
    }

    @Test
    fun `instances are numbered upward and a write is run exactly once`() {
        val owner = StateOwner()
        val a = owner.newInstance()
        val b = owner.newInstance()
        assertTrue(b > a)
        var ran = 0
        owner.publish(b) { ran++ }
        owner.publish(a) { ran++ }
        assertEquals(1, ran)
    }

    @Test
    fun `the service writes its state only through the owner`() {
        val source = java.io.File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
        // Every write of the two process-wide flows is inside a publish { }: the lines that assign them.
        val writes = source.lines().filter { Regex("""\b(current|started)\.value = """).containsMatchIn(it) }
        assertTrue(writes.toString(), writes.isNotEmpty() && writes.size <= 6)
        val unguarded = source.lines().withIndex().filter { (i, line) ->
            Regex("""\b(current|started)\.value = """).containsMatchIn(line) &&
                "publish" !in line && source.lines().subList((i - 3).coerceAtLeast(0), i).none { "publish" in it }
        }
        assertEquals("a write of the shared state outside the owner: $unguarded", emptyList<Any>(), unguarded.map { it.value })
    }
}
