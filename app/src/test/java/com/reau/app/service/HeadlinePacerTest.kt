package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification is told only what is worth showing: Android drops updates that come too fast, and the last one
 * lost is a notification saying "connecting" over a projection that is streaming.
 */
class HeadlinePacerTest {
    private val shown = mutableListOf<Headline>()
    private val waiting = mutableListOf<Pair<Long, () -> Unit>>()
    private val cancelled = mutableListOf<Int>()

    private val pacer = HeadlinePacer(
        paceMs = 250,
        schedule = { delayMs, action ->
            val index = waiting.size
            waiting += delayMs to action
            { cancelled += index }
        },
        show = { shown += it },
    )

    /** Runs what is due and was not cancelled, in order. */
    private fun timePasses() {
        waiting.forEachIndexed { index, (_, action) -> if (index !in cancelled) action() }
        waiting.clear()
        cancelled.clear()
    }

    @Test
    fun `a new headline is shown after the pace`() {
        pacer.offer(Headline.CONNECTING)
        assertEquals("not at once", emptyList<Headline>(), shown)
        assertEquals(250L, waiting.single().first)
        timePasses()
        assertEquals(listOf(Headline.CONNECTING), shown)
    }

    @Test
    fun `however fast the state flaps, one post, with the last word`() {
        repeat(10) {
            pacer.offer(Headline.CONNECTING)
            pacer.offer(Headline.STREAMING)
        }
        timePasses()
        assertEquals(listOf(Headline.STREAMING), shown)
    }

    @Test
    fun `showNow posts at once, with no wait, and drops the headline that was waiting`() {
        pacer.offer(Headline.STREAMING)
        pacer.showNow(Headline.STOPPING)
        assertEquals(listOf(Headline.STOPPING), shown)
        timePasses()
        assertEquals("what waited was called off", listOf(Headline.STOPPING), shown)
        pacer.showNow(Headline.STOPPING)
        assertEquals("the same one is not posted twice", listOf(Headline.STOPPING), shown)
    }

    @Test
    fun `the headline on show is not posted again`() {
        pacer.offer(Headline.STREAMING)
        timePasses()
        pacer.offer(Headline.STREAMING)
        timePasses()
        assertEquals(listOf(Headline.STREAMING), shown)
    }

    @Test
    fun `the notification the service started with counts as shown`() {
        pacer.alreadyShown(Headline.PREPARING)
        pacer.offer(Headline.PREPARING)
        timePasses()
        assertEquals(emptyList<Headline>(), shown)
        pacer.offer(Headline.CONNECTING)
        timePasses()
        assertEquals(listOf(Headline.CONNECTING), shown)
    }

    @Test
    fun `a flap back to what is on show posts nothing`() {
        pacer.offer(Headline.STREAMING)
        timePasses()
        pacer.offer(Headline.DASH_LOST)
        pacer.offer(Headline.STREAMING)
        timePasses()
        assertEquals(listOf(Headline.STREAMING), shown)
    }
}
