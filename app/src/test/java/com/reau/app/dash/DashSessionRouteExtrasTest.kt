package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behind its switch, the route card carries the real time left and the maneuver after the
 * next; with the switch off it is the card of the captured template as before.
 */
class DashSessionRouteExtrasTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private data class Tlv(val type: Int, val sub: Int, val value: ByteArray)

    private fun tlvs(pkt: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            out += Tlv(pkt[i].toInt() and 0xFF, pkt[i + 1].toInt() and 0xFF, pkt.copyOfRange(i + 4, minOf(i + 4 + len, pkt.size)))
            i += 4 + len
        }
        return out
    }

    private fun guide(meters: Int, extras: Boolean, remaining: Long = 900L, next: Int = 0x14) = session.updateNavInfo(
        0x14, meters, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS,
        remainingSeconds = remaining, nextManeuver = next, extras = extras,
    )

    private fun Tlv.byte() = value.single().toInt() and 0xFF

    @Test
    fun `with the switch off the card keeps the template's time and maneuver`() {
        guide(80, extras = false)
        val t = tlvs(session.routeCardNow())
        assertEquals("001000", String(t.single { it.type == 5 && it.sub == 0x0B }.value, Charsets.US_ASCII))
        assertEquals(0x34, t.single { it.type == 5 && it.sub == 0x03 }.byte())
    }

    @Test
    fun `with the switch on the card has the real time left and the next maneuver, close to the turn`() {
        guide(80, extras = true)
        val t = tlvs(session.routeCardNow())
        assertEquals("001500", String(t.single { it.type == 5 && it.sub == 0x0B }.value, Charsets.US_ASCII))
        assertEquals(0x20, t.single { it.type == 5 && it.sub == 0x55 }.byte())
        assertEquals(0x14, t.single { it.type == 5 && it.sub == 0x03 }.byte())
    }

    @Test
    fun `far from the turn the next maneuver is none`() {
        guide(2_000, extras = true)
        assertEquals(DashCommands.NEXT_MANEUVER_NONE, tlvs(session.routeCardNow()).single { it.type == 5 && it.sub == 0x03 }.byte())
    }

    @Test
    fun `with no time left known the two fields are out`() {
        guide(80, extras = true, remaining = DashCommands.REMAINING_OMIT)
        val t = tlvs(session.routeCardNow())
        assertTrue(t.none { it.type == 5 && it.sub == 0x0B })
        assertTrue(t.none { it.type == 5 && it.sub == 0x55 })
    }
}
