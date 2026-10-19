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
import org.junit.Test

/**
 * The link to the bike's Wi-Fi tells whether the network it is about to ask for was never approved by the rider AND the dash
 * is in the last scan (Android's dialog is really expected), and that it is joined; the screen's hint follows. With the bike
 * off or out of reach no dialog ever comes, so the hint is not told (the 18/10 phone session waited minutes for it).
 */
class WifiDashLinkUpFirstJoinTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val wifi = MutableStateFlow(WifiState())
    private val told = mutableListOf<Boolean>()

    private class Pairing(
        override val joinedSsid: String?,
        override var ssid: String = "RE_AB12_345678",
        var dashInScan: Boolean = true,
    ) : WifiDashLinkUp.Pairing {
        override val ssidPrefix = "RE_"
        override val password = "secret"
        override fun remember(ssid: String) { this.ssid = ssid }
        val asked = mutableListOf<Pair<String, Boolean>>()
        override fun seenInScan(wanted: String, isPrefix: Boolean): Boolean {
            asked += wanted to isPrefix
            return dashInScan
        }
    }

    private val connects = mutableListOf<Pair<String, Boolean>>()
    private val logged = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    private fun linkUp(pairing: Pairing) = WifiDashLinkUp(
        scope, wifi, { null }, { ssid, _, prefix -> connects += ssid to prefix }, {}, pairing,
    ).also { up ->
        up.onFirstJoin { told += it }
        up.joining = WifiDashLinkUp.Joining(log = { logged += it })
    }

    private fun WifiDashLinkUp.go() = start({ _, _ -> }, { failed += it })

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a dash never joined from here is announced as a first approval before it is asked for`() {
        linkUp(Pairing(joinedSsid = "")).start({ _, _ -> }, {})

        assertEquals(listOf(true), told)
    }

    @Test
    fun `a dash never joined that the scan does not see is not announced, the dialog will not come`() {
        linkUp(Pairing(joinedSsid = "", dashInScan = false)).start({ _, _ -> }, {})

        assertEquals(listOf(false), told)
    }

    @Test
    fun `the hint comes with the next request once the scan sees the dash`() {
        val pairing = Pairing(joinedSsid = "", dashInScan = false)
        val up = linkUp(pairing)
        up.start({ _, _ -> }, {})

        pairing.dashInScan = true
        up.start({ _, _ -> }, {})

        assertEquals(listOf(false, true), told)
    }

    @Test
    fun `the scan is asked about the exact name when it is known and the prefix when it is not`() {
        val known = Pairing(joinedSsid = "")
        linkUp(known).start({ _, _ -> }, {})
        assertEquals(listOf("RE_AB12_345678" to false), known.asked)

        val unknown = Pairing(joinedSsid = "", ssid = "")
        linkUp(unknown).start({ _, _ -> }, {})
        assertEquals(listOf("RE_" to true), unknown.asked)
    }

    @Test
    fun `a dash joined before does not even look at the scan`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678")
        linkUp(pairing).start({ _, _ -> }, {})

        assertEquals(emptyList<Pair<String, Boolean>>(), pairing.asked)
    }

    @Test
    fun `a dash joined before is not a first approval`() {
        linkUp(Pairing(joinedSsid = "RE_AB12_345678")).start({ _, _ -> }, {})

        assertEquals(listOf(false), told)
    }

    @Test
    fun `without a memory of joins nothing is announced as a first approval`() {
        linkUp(Pairing(joinedSsid = null)).start({ _, _ -> }, {})

        assertEquals(listOf(false), told)
    }

    @Test
    fun `another dash than the one joined is a first approval`() {
        linkUp(Pairing(joinedSsid = "RE_OTHER_000001")).start({ _, _ -> }, {})

        assertEquals(listOf(true), told)
    }

    @Test
    fun `joining the network ends the first approval`() {
        val up = linkUp(Pairing(joinedSsid = ""))
        up.start({ _, _ -> }, {})

        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678")

        assertEquals(listOf(true, false), told)
    }

    @Test
    fun `a refusal leaves the first approval standing for the next try`() {
        val up = linkUp(Pairing(joinedSsid = ""))
        up.start({ _, _ -> }, {})

        wifi.value = WifiState(WifiConnStatus.ERROR, ssid = "RE_AB12_345678", error = "no")

        assertEquals(listOf(true), told)
    }

    @Test
    fun `a Wi-Fi already up is no first approval and clears any hint left`() {
        wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = "RE_AB12_345678")

        linkUp(Pairing(joinedSsid = "")).start({ _, _ -> }, {})

        assertEquals(listOf(false), told)
    }

    // ── the request that opens Android's dialog waits for a sighting of the dash ─────────────────────────────────

    @Test
    fun `a first approval with the dash not in the recent scan asks Android for nothing and fails at once`() {
        linkUp(Pairing(joinedSsid = "", dashInScan = false)).go()

        assertEquals(emptyList<Pair<String, Boolean>>(), connects)
        assertEquals(listOf(WifiDashLinkUp.DASH_NOT_SEEN_YET), failed)
        assertEquals(DashLink.DROPPED, WifiDashLinkUp.linkAfterFailure(failed.single()))
    }

    @Test
    fun `the request of the first approval goes out on the retry that finds the dash in the scan`() {
        val pairing = Pairing(joinedSsid = "", dashInScan = false)
        val up = linkUp(pairing)
        up.go()
        up.go()

        pairing.dashInScan = true
        up.go()

        assertEquals(listOf("RE_AB12_345678" to false), connects)
        assertEquals(2, failed.size)
        assertEquals(listOf(false, false, true), told)
    }

    @Test
    fun `a dash unknown by name is not asked for by the prefix before it is seen either`() {
        val pairing = Pairing(joinedSsid = "", ssid = "", dashInScan = false)
        val up = linkUp(pairing)
        up.go()
        assertEquals(emptyList<Pair<String, Boolean>>(), connects)

        pairing.dashInScan = true
        up.go()
        assertEquals(listOf("RE_" to true), connects)
    }

    @Test
    fun `a network already approved is asked for with the dash out of the scan, as always`() {
        val pairing = Pairing(joinedSsid = "RE_AB12_345678", dashInScan = false)
        linkUp(pairing).go()

        assertEquals(listOf("RE_AB12_345678" to false), connects)
        assertEquals(emptyList<String>(), failed)
        assertEquals(emptyList<Pair<String, Boolean>>(), pairing.asked)
    }

    @Test
    fun `without a memory of joins the request goes out whatever the scan says`() {
        linkUp(Pairing(joinedSsid = null, dashInScan = false)).go()

        assertEquals(listOf("RE_AB12_345678" to false), connects)
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `waiting for the dash is told to the log once per wait, not at every retry`() {
        val pairing = Pairing(joinedSsid = "", dashInScan = false)
        val up = linkUp(pairing)
        repeat(3) { up.go() }
        assertEquals(1, logged.size)

        pairing.dashInScan = true
        up.go()
        pairing.dashInScan = false
        up.go()

        assertEquals("a new wait after the request went out is told again", 2, logged.size)
    }
}
