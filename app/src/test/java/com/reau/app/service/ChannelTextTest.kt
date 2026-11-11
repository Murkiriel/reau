package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notification channel that already exists kept its description of the day it was created (11/11/2026):
 * `createChannel` returned when the channel was there. Android takes a new name and description from a second `createNotificationChannel` with the same
 * id (and keeps the rider's own sound and vibration), so the channel is made again exactly when its text is not today's.
 */
class ChannelTextTest {
    private val name = "Avisos do painel"
    private val description = "Avisos que precisam ser ouvidos com a tela apagada"

    @Test
    fun `a channel with today's name and description is left alone`() {
        assertEquals(false, ProjectionNotification.channelTextStale(name, description, name, description))
    }

    @Test
    fun `an old description is renewed, an old name too`() {
        assertEquals(true, ProjectionNotification.channelTextStale(name, "the old description", name, description))
        assertEquals(true, ProjectionNotification.channelTextStale("Old name", description, name, description))
    }

    @Test
    fun `a channel with no description yet is renewed`() {
        assertEquals(true, ProjectionNotification.channelTextStale(name, null, name, description))
        assertEquals(true, ProjectionNotification.channelTextStale(null, null, name, description))
    }
}
