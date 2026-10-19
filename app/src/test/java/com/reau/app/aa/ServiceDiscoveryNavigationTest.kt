package com.reau.app.aa

import com.reau.app.aa.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The navigation status channel behind the switch: off, the service discovery response is
 * byte for byte the one of before the switch existed; on, it adds the navigation status service on channel 10, for
 * maneuver codes only, at most once a second.
 */
class ServiceDiscoveryNavigationTest {

    /** The response of AaConfig() as it was before the navigation switch existed, with the head unit's model "REau". */
    private val before =
        "000B00F000060A0C080112080A02080D0A02080A0A1908021A1508031000220D080110021800200028A001500328010A1408032210088080" +
            "040813081408150816081708040A1108051A0D080110021A0708807D101018010A0F08072A0B0801120708807D101018011204524561751A" +
            "04524561752204323032362A047265617530003A04524561754204524561754A01315205302E312E30580060007204524561758A012E0A04" +
            "524561751204524561751A04524561752204524561752A04323032363201313A04726561754205302E312E30"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    private fun response(config: AaConfig): Control.ServiceDiscoveryResponse =
        ServiceDiscoveryResponse(config).parse(Control.ServiceDiscoveryResponse.newBuilder()).build()

    /**
     * [before] with the one change made on purpose since: the input service
     * also announces home (3) and assistant (84), for the dash's joystick. Everything else must still be identical.
     */
    private val beforeWithJoystickKeys: String by lazy {
        val bytes = before.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val old = Control.ServiceDiscoveryResponse.parseFrom(bytes.copyOfRange(HEADER, bytes.size))
        val withKeys = old.toBuilder().apply {
            val i = servicesList.indexOfFirst { it.id == Channel.ID_INP }
            setServices(i, getServices(i).toBuilder().apply {
                inputSourceService = inputSourceService.toBuilder().addKeycodesSupported(3).addKeycodesSupported(84)
                    .addKeycodesSupported(87).addKeycodesSupported(88).addKeycodesSupported(85)
                    .addKeycodesSupported(5).addKeycodesSupported(6).build()
            })
        }.build()
        hex(ServiceDiscoveryResponse(AaConfig()).data.copyOfRange(0, HEADER)) + hex(withKeys.toByteArray())
    }

    // Changed: was "byte for byte the one of before"; now the same bytes plus the two joystick keys.
    // Changed again: plus the car's phone keys, call (5) and end call (6), for the joystick's answer and end.
    // Changed again (the joystick's double tap): plus next and previous song (87, 88).
    // And play/pause (85), for the double tap down.
    @Test
    fun `off, the response is the one of before plus the joystick's keys`() {
        assertFalse("off unless asked", AaConfig().navigation)
        assertEquals(beforeWithJoystickKeys, hex(ServiceDiscoveryResponse(AaConfig()).data))
        assertEquals(beforeWithJoystickKeys, hex(ServiceDiscoveryResponse(AaConfig(navigation = false)).data))
    }

    @Test
    fun `on, the navigation status service is added on channel 10, and nothing else changes`() {
        val on = response(AaConfig(navigation = true))
        assertEquals(listOf(Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC, Channel.ID_NAV), on.servicesList.map { it.id })
        val off = response(AaConfig())
        assertEquals("the other five are the same", off.servicesList, on.servicesList.dropLast(1))
    }

    @Test
    fun `the navigation service asks for maneuver codes only, at most once a second`() {
        val service = response(AaConfig(navigation = true)).servicesList.firstOrNull { it.id == Channel.ID_NAV }
        assertNotNull("a service on channel 10", service)
        val nav = service!!.navigationStatusService
        assertEquals(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly, nav.type)
        assertEquals(1_000, nav.minimumIntervalMs)
    }

    private companion object {
        /** The message's channel, flags, length and type (AapMessage.HEADER_SIZE + MsgType.SIZE). */
        const val HEADER = 6
    }
}
