package com.reau.app.service

import com.reau.app.dash.WifiConnStatus
import com.reau.app.dash.WifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting onto the bike's Wi-Fi before the dash session connects: by the exact name once the dash is known, by its
 * name's beginning until then, and the name learned on the way is remembered. The dash checks its own name inside
 * the encrypted handshake, so the session needs the exact one.
 */
class WifiDashLinkUpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val wifi = MutableStateFlow(WifiState())
    private val connects = mutableListOf<Triple<String, String, Boolean>>()
    private var disconnects = 0
    private val ready = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    private class Remembered(
        override var ssid: String = "",
        override val ssidPrefix: String = "RE_",
        override val password: String = "secret",
        val scan: (String) -> String? = { null },
    ) : WifiDashLinkUp.Pairing {
        override fun remember(ssid: String) { this.ssid = ssid }
        override fun discover(prefix: String): String? = scan(prefix)
    }

    private fun linkUp(pairing: Remembered = Remembered()) = WifiDashLinkUp(
        scope, wifi, { null },
        { ssid, password, prefix -> connects += Triple(ssid, password, prefix) },
        { disconnects++ },
        pairing,
    )

    // ── the prefix taken for the dash's name ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown dash found in the last Wi-Fi scan is asked for by its exact name, which is remembered`() {
        val pairing = Remembered(scan = { prefix -> if (prefix == "RE_") "RE_AB12_345678" else null })
        linkUp(pairing).go()
        assertEquals(listOf(Triple("RE_AB12_345678", "secret", false)), connects)
        assertEquals("RE_AB12_345678", pairing.ssid)
    }

    @Test
    fun `a scan answer that is only the prefix, or another network, is not taken for the dash`() {
        for (answer in listOf("RE_", "XRE_DASH", "")) {
            connects.clear()
            val pairing = Remembered(scan = { answer })
            linkUp(pairing).go()
            assertEquals("scan said '$answer'", listOf(Triple("RE_", "secret", true)), connects)
            assertEquals("scan said '$answer'", "", pairing.ssid)
        }
    }

    @Test
    fun `connected by the prefix with only the prefix as the name, nothing is remembered nor authenticated`() {
        val pairing = Remembered()
        linkUp(pairing).go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_")
        assertTrue("the session must not get the prefix: $ready", ready.isEmpty())
        assertEquals("", pairing.ssid)
        assertEquals(listOf(WifiDashLinkUp.FULL_NAME_NEEDED), failed)
    }

    /** A way round the prefix: the whole name typed where the prefix goes. */
    @Test
    fun `the whole name typed as the prefix still works, scan or no scan`() {
        val pairing = Remembered(ssidPrefix = "RE_AB12_345678")
        linkUp(pairing).go()
        assertEquals(listOf(Triple("RE_AB12_345678", "secret", true)), connects)
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678")
        assertEquals(listOf("RE_AB12_345678"), ready)
        assertEquals("RE_AB12_345678", pairing.ssid)

        connects.clear()
        wifi.value = WifiState() // off the bike's Wi-Fi again
        linkUp(pairing).go() // the next start: the name is now remembered and asked for exactly
        assertEquals(listOf(Triple("RE_AB12_345678", "secret", false)), connects)
    }

    @Test
    fun `the missing full name tells the projection so, any other failure is a dropped dash`() {
        assertEquals(DashLink.NAME_NEEDED, WifiDashLinkUp.linkAfterFailure(WifiDashLinkUp.FULL_NAME_NEEDED))
        assertEquals(DashLink.DROPPED, WifiDashLinkUp.linkAfterFailure("rede não encontrada"))
    }

    @Test
    fun `a known dash keeps its name when the Wi-Fi reports only the prefix`() {
        val pairing = Remembered(ssid = "RE_AB12_345678")
        linkUp(pairing).go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_")
        assertEquals(listOf("RE_AB12_345678"), ready)
        assertEquals("RE_AB12_345678", pairing.ssid)
    }

    private fun WifiDashLinkUp.go() = start({ ssid, _ -> ready += ssid }, { failed += it })

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a known dash is asked for by its exact name`() {
        linkUp(Remembered(ssid = "RE_TEST_1234")).go()
        assertEquals(listOf(Triple("RE_TEST_1234", "secret", false)), connects)
    }

    @Test
    fun `an unknown dash is asked for by the beginning of its name`() {
        linkUp().go()
        assertEquals(listOf(Triple("RE_", "secret", true)), connects)
    }

    @Test
    fun `connected, the session is told the exact name, and a name learned on the way is remembered`() {
        val pairing = Remembered()
        linkUp(pairing).go()
        assertTrue("not before the Wi-Fi is up", ready.isEmpty())

        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_TEST_9876")

        assertEquals(listOf("RE_TEST_9876"), ready)
        assertEquals("RE_TEST_9876", pairing.ssid)
    }

    @Test
    fun `connected without a name it can read, the remembered one is used`() {
        linkUp(Remembered(ssid = "RE_TEST_1234")).go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "")
        assertEquals(listOf("RE_TEST_1234"), ready)
    }

    @Test
    fun `connected with no name at all is a failure, said in Portuguese`() {
        linkUp().go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "")
        assertTrue(ready.isEmpty())
        assertEquals(listOf("nome do Wi-Fi do painel desconhecido"), failed)
    }

    @Test
    fun `a Wi-Fi error is a failure with its reason`() {
        linkUp().go()
        wifi.value = WifiState(WifiConnStatus.ERROR, error = "rede não encontrada")
        assertEquals(listOf("rede não encontrada"), failed)
        assertTrue(ready.isEmpty())
    }

    /**
     * The reason goes to the ride log, and the screen's words for a stuck dash say the factory
     * password. The log never carries a password: neither the factory one nor the paired one.
     */
    @Test
    fun `the reason that reaches the log carries no password, the factory one nor the paired one`() {
        val reason = "O Wi‑Fi do painel 'RE_****78' está recusando. Se a senha não mudou (a de fábrica é 12345678), ele travou; a sua é secret"
        linkUp().go()
        wifi.value = WifiState(WifiConnStatus.ERROR, error = reason)
        val said = failed.single()
        assertFalse(said, "12345678" in said)
        assertFalse(said, "secret" in said)
        assertTrue(said, "RE_****78" in said && "ele travou" in said) // the rest of the words stay
    }

    @Test
    fun `a password that is empty hides nothing and breaks nothing`() {
        assertEquals("rede não encontrada", WifiDashLinkUp.withoutPasswords("rede não encontrada", "", "12345678"))
        assertEquals("a (…) b", WifiDashLinkUp.withoutPasswords("a (12345678) b", "12345678"))
    }

    @Test
    fun `a Wi-Fi error left from the attempt before is not taken as this one's`() {
        wifi.value = WifiState(WifiConnStatus.ERROR, error = "a de antes")
        linkUp().go()
        assertTrue("got $failed", failed.isEmpty())
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_A")
        assertEquals(listOf("RE_A"), ready)
    }

    @Test
    fun `the Wi-Fi already up from before is used at once, without asking again`() {
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_TEST_1234")
        linkUp(Remembered(ssid = "RE_TEST_1234")).go()
        assertTrue("got $connects", connects.isEmpty())
        assertEquals(listOf("RE_TEST_1234"), ready)
    }

    @Test
    fun `each start reports once, even if the Wi-Fi state is repeated or changes later`() {
        val up = linkUp()
        up.go()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_A")
        wifi.value = WifiState(WifiConnStatus.ERROR, error = "caiu")
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_A2")
        assertEquals(listOf("RE_A"), ready)
        assertTrue("a later drop is the session's to notice: $failed", failed.isEmpty())

        up.go() // the next attempt, after the session dropped
        assertEquals(listOf("RE_A", "RE_A2"), ready)
    }

    // ── a reconnection of the Wi-Fi manager's own is joined, not replaced ───────────────────────────────────────

    private fun linkUpJoining(pairing: Remembered, underWay: Boolean, joinWaitMs: Long = WifiDashLinkUp.JOIN_WAIT_MS) = WifiDashLinkUp(
        scope, wifi, { null },
        { ssid, password, prefix -> connects += Triple(ssid, password, prefix) },
        { disconnects++ },
        pairing,
    ).apply { joining = WifiDashLinkUp.Joining(underWay = { underWay }, waitMs = joinWaitMs) }

    @Test
    fun `a reconnection the Wi-Fi manager has under way for the known dash is joined, not asked anew`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_TEST_1234")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = true).go()
        assertTrue("no fresh request, which would have cancelled the manager's own: $connects", connects.isEmpty())
        assertTrue(ready.isEmpty())

        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_TEST_1234")
        assertEquals(listOf("RE_TEST_1234"), ready)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `a manager getting the dash back by the prefix is getting back the dash known by its full name`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = true).go()
        assertTrue("got $connects", connects.isEmpty())
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_TEST_1234")
        assertEquals(listOf("RE_TEST_1234"), ready)
    }

    @Test
    fun `a reconnection that failed and is said so is this start's failure too`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_TEST_1234")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = true).go()
        wifi.value = WifiState(WifiConnStatus.ERROR, error = "rede não encontrada")
        assertEquals(listOf("rede não encontrada"), failed)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `a reconnection that is not recent any more is replaced by a fresh request`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_TEST_1234")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = false).go()
        assertEquals(listOf(Triple("RE_TEST_1234", "secret", false)), connects)
    }

    @Test
    fun `a reconnection for another dash is not joined`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_OTHER_9999")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = true).go()
        assertEquals(listOf(Triple("RE_TEST_1234", "secret", false)), connects)
    }

    @Test
    fun `a joined reconnection that does not come back in time is a failure, so the projection keeps its own pace`() {
        wifi.value = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_TEST_1234")
        linkUpJoining(Remembered(ssid = "RE_TEST_1234"), underWay = true, joinWaitMs = 50).go()
        val deadline = System.currentTimeMillis() + 2_000
        while (failed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf(WifiDashLinkUp.RECONNECT_NOT_BACK_YET), failed)
        assertEquals(DashLink.DROPPED, WifiDashLinkUp.linkAfterFailure(WifiDashLinkUp.RECONNECT_NOT_BACK_YET))
        assertTrue("still no fresh request of this start's: $connects", connects.isEmpty())
        assertTrue(ready.isEmpty())
    }

    @Test
    fun `the rule of joining, on its own`() {
        val requesting = WifiState(WifiConnStatus.REQUESTING, ssid = "RE_TEST_1234")
        assertTrue(WifiDashLinkUp.joinsReconnection(requesting, wanted = "RE_TEST_1234", underWay = true))
        assertTrue(WifiDashLinkUp.joinsReconnection(requesting.copy(ssid = "RE_"), wanted = "RE_TEST_1234", underWay = true))
        assertTrue("a start still by the prefix joins a manager asking by it", WifiDashLinkUp.joinsReconnection(requesting.copy(ssid = "RE_"), wanted = "RE_", underWay = true))
        assertFalse("not under way", WifiDashLinkUp.joinsReconnection(requesting, wanted = "RE_TEST_1234", underWay = false))
        assertFalse("another dash", WifiDashLinkUp.joinsReconnection(requesting.copy(ssid = "RE_OTHER_1"), wanted = "RE_TEST_1234", underWay = true))
        assertFalse("no name at all", WifiDashLinkUp.joinsReconnection(requesting.copy(ssid = ""), wanted = "RE_TEST_1234", underWay = true))
        assertFalse("not requesting", WifiDashLinkUp.joinsReconnection(requesting.copy(status = WifiConnStatus.ERROR), wanted = "RE_TEST_1234", underWay = true))
        assertFalse("already connected is reported, not joined", WifiDashLinkUp.joinsReconnection(requesting.copy(status = WifiConnStatus.CONNECTED), wanted = "RE_TEST_1234", underWay = true))
    }

    @Test
    fun `stop lets go of the Wi-Fi and nothing is reported after it`() {
        val up = linkUp()
        up.go()
        up.stop()
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_A")
        assertEquals(1, disconnects)
        assertTrue(ready.isEmpty())
        assertNull(failed.firstOrNull())
    }

    @Test
    fun `the Wi-Fi manager getting the bike's network tells the one who listens, the latest listener only`() {
        val up = linkUp()
        up.bikeNetworkAvailable() // nobody listens yet: nothing happens
        var first = 0
        var second = 0
        up.onBikeNetwork { first++ }
        up.onBikeNetwork { second++ }
        up.bikeNetworkAvailable()
        up.bikeNetworkAvailable()
        assertEquals(0, first)
        assertEquals(2, second)
    }
}
