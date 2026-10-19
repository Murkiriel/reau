package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.dash.protocol.K1GPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DashStats.sent] reads the first TLV's type and
 * sub in place instead of copying every TLV with [DashDump.outgoingTlvs], under the same conditions as before: at
 * least 21 bytes and at least one TLV past the header. Every packet [DashCommands] builds, and the edges, give the key
 * the old way gave.
 */
class DashStatsSentKeyTest {
    private fun before(packet: ByteArray): String? =
        DashDump.outgoingTlvs(packet).firstOrNull()?.let { "%02X%02X".format(it.type, it.sub) }

    private val commands: List<ByteArray> = buildList {
        add(DashCommands.authRequest())
        add(DashCommands.authSendKey(ByteArray(128) { it.toByte() }))
        addAll(DashCommands.initialBurst("REau"))
        add(DashCommands.timeSync())
        add(DashCommands.hostnameAnnounce("REau"))
        add(DashCommands.navContext())
        add(DashCommands.emptyLists())
        add(DashCommands.navStart())
        add(DashCommands.navPlaceholder())
        add(DashCommands.projectionFrame())
        add(DashCommands.projectionOn())
        add(DashCommands.projectionStop())
        add(DashCommands.projectionOff())
        add(DashCommands.frameDecodedIdr())
        add(DashCommands.frameDecodedP())
        add(DashCommands.buttonAck(0x09))
        add(DashCommands.exitBack())
        add(DashCommands.exitProjectionIdle())
        add(DashCommands.exitSessionBoundary())
        add(DashCommands.heartbeat())
        add(DashCommands.metadata0030(3))
        add(DashCommands.routeCard("Casa"))
        add(DashCommands.routeCard("Casa", projectionOn = true, maneuver = 2, primaryUnit = 1, totalDist = 12, totalUnit = 2, etaHHMM = "1845"))
        add(DashCommands.standbyRouteCard("REau", projectionOn = false))
        DashIdleCard.entries.forEach { add(DashCommands.idleRouteCard(it, "REau", projectionOn = true, clockHHMM = "0930")) }
        add(DashCommands.activeNavPacket())
        add(DashCommands.activeNavPacket(totalDist = 0))
        add(DashCommands.routeCard("Casa", projectionOn = true, totalDist = 0))
        add(DashCommands.zoomIcons())
        add(DashCommands.nowPlaying("Título", "Álbum", "Artista"))
        add(DashCommands.callNotify("Fulano", ByteArray(32)))
        DashCommands.CallState.entries.forEach { add(DashCommands.callState(it)) }
        add(DashCommands.callStateAfter())
        add(DashCommands.musicActive(true))
        add(DashCommands.musicPlaying(false))
        add(DashCommands.musicCover(false))
    }

    @Test
    fun `every packet DashCommands builds gives the key the old way gave`() {
        assertTrue("the list has the commands", commands.size > 30)
        for (packet in commands) {
            assertEquals("packet ${packet.size} bytes", before(packet), DashStats.sentKey(packet))
        }
    }

    @Test
    fun `the edges keep the old conditions - 21 bytes and one TLV past the header`() {
        val edges = buildList {
            add(ByteArray(0))
            add(ByteArray(16))
            for (size in 17..24) {
                for (segments in 0..3) {
                    add(ByteArray(size) { (0x30 + it).toByte() }.also { it[2] = 0; it[3] = segments.toByte() })
                }
            }
            // The segment count is two bytes: 256 and 257 have a low byte of 0 and 1.
            for (low in 0..1) add(ByteArray(24) { (0x30 + it).toByte() }.also { it[2] = 1; it[3] = low.toByte() })
            add(K1GPacket.build()) // the header TLV only
            add(K1GPacket.build(K1GPacket.tlv(0x05, 0x56)))
        }
        for (packet in edges) {
            assertEquals("size ${packet.size}, segments ${if (packet.size > 3) packet[3] else "-"}", before(packet), DashStats.sentKey(packet))
        }
    }
}
