package com.reau.app.aa

import com.reau.app.aa.proto.Control
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music and phone status cards, behind their switches: off, the service discovery response is byte for
 * byte the one of before; on, it adds the music status service on channel 9 and the phone status service on channel
 * 12, after the others, and nothing else changes.
 */
class ServiceDiscoveryCardsTest {

    private fun response(config: AaConfig): Control.ServiceDiscoveryResponse =
        ServiceDiscoveryResponse(config).parse(Control.ServiceDiscoveryResponse.newBuilder()).build()

    @Test
    fun `off, the response is byte for byte the one without the switches`() {
        assertFalse(AaConfig().media)
        assertFalse(AaConfig().phone)
        for (base in listOf(AaConfig(), AaConfig(navigation = true), AaConfig(touch = true))) {
            assertArrayEquals(ServiceDiscoveryResponse(base).data, ServiceDiscoveryResponse(base.copy(media = false, phone = false)).data)
        }
    }

    @Test
    fun `music on adds an empty music status service on channel 9, last`() {
        val on = response(AaConfig(navigation = true, media = true))
        val off = response(AaConfig(navigation = true))
        assertEquals(off.servicesList, on.servicesList.dropLast(1))
        val music = on.servicesList.last()
        assertEquals(Channel.ID_MPB, music.id)
        assertTrue(music.hasMediaPlaybackService())
    }

    @Test
    fun `the caller on adds an empty phone status service on channel 12, last`() {
        val on = response(AaConfig(phone = true))
        val off = response(AaConfig())
        assertEquals(off.servicesList, on.servicesList.dropLast(1))
        val phone = on.servicesList.last()
        assertEquals(Channel.ID_PHONE, phone.id)
        assertTrue(phone.hasPhoneStatusService())
    }

    @Test
    fun `both on add music then phone`() {
        val ids = response(AaConfig(media = true, phone = true)).servicesList.map { it.id }
        assertEquals(listOf(Channel.ID_MPB, Channel.ID_PHONE), ids.takeLast(2))
        assertEquals(ids.size, ids.toSet().size)
    }
}
